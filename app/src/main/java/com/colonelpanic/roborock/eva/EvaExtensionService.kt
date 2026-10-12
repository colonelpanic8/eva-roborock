package com.colonelpanic.roborock.eva

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.AtomicFile
import com.colonelpanic.eva.extension.IEvaExtension
import com.colonelpanic.eva.extension.IEvaExtensionCallback
import com.colonelpanic.roborock.BuildConfig
import com.colonelpanic.roborock.Store
import com.colonelpanic.roborock.api.Robot
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exported binder EVA discovers through `com.colonelpanic.eva.action.EXTENSION`.
 * Binding cold-starts this process without an activity, so it works while the
 * phone is locked after the first unlock. Binder threads only authenticate and enqueue.
 */
class EvaExtensionService : Service() {
    private lateinit var capabilities: Capabilities
    private lateinit var verifier: EvaCallerVerifier

    override fun onCreate() {
        super.onCreate()
        capabilities = capabilities(applicationContext)
        verifier = EvaCallerVerifier(packageManager, packageName, BuildConfig.DEBUG)
    }

    private val binder =
        object : IEvaExtension.Stub() {
            override fun describe(
                requestId: String?,
                requestJson: String?,
                deadlineElapsedRealtimeMillis: Long,
                callback: IEvaExtensionCallback?,
            ) {
                if (requestId == null || callback == null) return
                val reply = once(callback, requestId)
                if (!verifier.isTrusted(Binder.getCallingUid())) {
                    reply(
                        EvaProtocol.describeFailure(
                            EvaProtocol.notExecuted(EvaProtocol.REASON_UNAUTHORIZED_CALLER, "Caller is not authorized."),
                        ),
                    )
                    return
                }
                submit(
                    { reply(EvaProtocol.describeFailure(EvaProtocol.notExecuted(EvaProtocol.REASON_BUSY, "Roborock for EVA is busy."))) },
                ) {
                    reply(
                        try {
                            capabilities.describe()
                        } catch (e: Exception) {
                            EvaProtocol.describeFailure(EvaProtocol.failed("Roborock for EVA could not describe itself: ${e.message}"))
                        },
                    )
                }
            }

            override fun execute(
                invocationId: String?,
                expectedRevision: String?,
                capability: String?,
                argumentsJson: String?,
                deadlineElapsedRealtimeMillis: Long,
                callback: IEvaExtensionCallback?,
            ) {
                if (invocationId == null || callback == null) return
                val reply = once(callback, invocationId)
                val name = capability.orEmpty()
                if (!verifier.isTrusted(Binder.getCallingUid())) {
                    reply(
                        capabilities.rejection(
                            name,
                            invocationId,
                            EvaProtocol.REASON_UNAUTHORIZED_CALLER,
                            "Caller is not authorized.",
                            "rejected",
                        ),
                    )
                    return
                }
                submit({
                    reply(
                        capabilities.rejection(
                            name,
                            invocationId,
                            EvaProtocol.REASON_BUSY,
                            "Roborock for EVA is busy with other requests. Nothing was sent.",
                            "rejected",
                        ),
                    )
                }) {
                    reply(
                        try {
                            capabilities.execute(
                                invocationId,
                                expectedRevision.orEmpty(),
                                name,
                                argumentsJson.orEmpty(),
                                deadlineElapsedRealtimeMillis,
                            )
                        } catch (e: Exception) {
                            // Reached only through a bug; a command may have been sent.
                            EvaProtocol.executeEnvelope(EvaProtocol.unknown("Roborock for EVA hit an unexpected error: ${e.message}"))
                        },
                    )
                }
            }
        }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun once(
        callback: IEvaExtensionCallback,
        requestId: String,
    ): (String) -> Unit {
        val sent = AtomicBoolean(false)
        return { response ->
            if (sent.compareAndSet(false, true)) {
                try {
                    callback.onResult(requestId, response)
                } catch (e: RemoteException) {
                    // EVA stopped waiting; the journal still holds a write's outcome.
                }
            }
        }
    }

    private fun submit(
        rejected: () -> Unit,
        work: () -> Unit,
    ) {
        try {
            executor.execute(work)
        } catch (e: RejectedExecutionException) {
            rejected()
        }
    }

    companion object {
        private val executor =
            ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, ArrayBlockingQueue(4)).apply { allowCoreThreadTimeOut(true) }

        @Volatile
        private var shared: Capabilities? = null

        fun capabilities(context: Context): Capabilities =
            shared ?: synchronized(this) {
                shared ?: run {
                    val app = context.applicationContext
                    val store = Store(app)
                    Capabilities(
                        configuration = store::configuration,
                        journal = Journal(AtomicFileStore(File(app.noBackupFilesDir, "eva-invocations.json")), System::currentTimeMillis),
                        connect = { account, deadline -> Robot(account).open(deadline) },
                        elapsedNow = SystemClock::elapsedRealtime,
                    )
                }.also { shared = it }
            }
    }
}

private class AtomicFileStore(
    file: File,
) : Journal.Store {
    private val atomic = AtomicFile(file)

    override fun read(): String? = if (atomic.baseFile.exists()) atomic.readFully().toString(Charsets.UTF_8) else null

    override fun write(text: String) {
        val stream = atomic.startWrite()
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) {
            atomic.failWrite(stream)
            throw e
        }
    }
}

/**
 * Trusts a calling UID only when every package sharing it is EVA with the
 * expected signer: release EVA by pinned certificate digest, debug EVA (for
 * debug builds of this app) when it shares this app's signer.
 */
class EvaCallerVerifier(
    private val packageManager: PackageManager,
    private val selfPackage: String,
    private val debugBuild: Boolean,
) {
    fun isTrusted(uid: Int): Boolean {
        val packages = packageManager.getPackagesForUid(uid)?.takeIf { it.isNotEmpty() } ?: return false
        return packages.all(::isTrustedPackage)
    }

    private fun isTrustedPackage(packageName: String): Boolean =
        when (packageName) {
            EVA_PACKAGE -> {
                packageManager.hasSigningCertificate(packageName, EVA_RELEASE_CERT_SHA256, PackageManager.CERT_INPUT_SHA256)
            }

            EVA_DEBUG_PACKAGE -> {
                debugBuild &&
                    ownSigningDigests().any {
                        packageManager.hasSigningCertificate(packageName, it, PackageManager.CERT_INPUT_SHA256)
                    }
            }

            else -> {
                false
            }
        }

    private fun ownSigningDigests(): List<ByteArray> {
        val info =
            try {
                packageManager.getPackageInfo(selfPackage, PackageManager.GET_SIGNING_CERTIFICATES)
            } catch (e: PackageManager.NameNotFoundException) {
                return emptyList()
            }
        val signers = info.signingInfo?.apkContentsSigners ?: return emptyList()
        return signers.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()) }
    }

    companion object {
        const val EVA_PACKAGE = "com.colonelpanic.eva"
        const val EVA_DEBUG_PACKAGE = "com.colonelpanic.eva.debug"

        /** SHA-256 of EVA's release signing certificate. */
        private val EVA_RELEASE_CERT_SHA256 =
            "688df17827dd9a002705baf0400c80f8f4650c6e87c3fc91d41f32be287f8b68".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
