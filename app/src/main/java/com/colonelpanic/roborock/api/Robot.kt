package com.colonelpanic.roborock.api

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.SecureRandom
import java.util.Base64

interface RpcSession : AutoCloseable {
    /**
     * Sends [method] and waits for its reply until [deadline] (epoch millis).
     * Returns the decoded `result`; throws [Robot.RpcError] or [Robot.NoReply].
     */
    fun call(
        method: String,
        params: Any? = null,
        deadline: Long,
    ): Any

    override fun close()
}

/** V1 RPC to one robot through Roborock's MQTT broker. */
class Robot(
    private val account: Account,
) {
    private val random = SecureRandom()

    /** The robot answered with an error; the command was refused. */
    class RpcError(
        val method: String,
        val detail: String,
    ) : RoborockException("The robot refused $method: $detail")

    /** The command was published but no reply arrived; it may or may not have run. */
    class NoReply(
        val method: String,
    ) : RoborockException("Sent $method but the robot did not answer in time.")

    fun open(deadline: Long): Session {
        val uri = URI(account.rriot.mqttUrl)
        val rriot = account.rriot
        val user = V1Codec.md5Hex(rriot.u + ":" + rriot.k).substring(2, 10)
        val password = V1Codec.md5Hex(rriot.s + ":" + rriot.k).substring(16)
        val clientId = V1Codec.hex(ByteArray(8).also { random.nextBytes(it) })
        val mqtt = MiniMqtt.connect(uri.host, uri.port, uri.scheme == "ssl", clientId, user, password, deadline)
        try {
            val suffix = "${rriot.u}/$user/${account.device.duid}"
            mqtt.subscribe("rr/m/o/$suffix", deadline)
            return Session(mqtt, "rr/m/i/$suffix")
        } catch (e: Exception) {
            mqtt.close()
            throw e
        }
    }

    inner class Session internal constructor(
        private val mqtt: MiniMqtt,
        private val publishTopic: String,
    ) : RpcSession {
        private val endpoint = Base64.getEncoder().encodeToString(V1Codec.md5(account.rriot.k.toByteArray()).copyOfRange(8, 14))

        override fun call(
            method: String,
            params: Any?,
            deadline: Long,
        ): Any {
            val requestId = 10_000 + random.nextInt(22_767)
            val timestamp = System.currentTimeMillis() / 1000
            val inner =
                JSONObject()
                    .put("id", requestId)
                    .put("method", method)
                    .put("params", params ?: JSONArray())
                    .put(
                        "security",
                        JSONObject()
                            .put("endpoint", endpoint)
                            .put("nonce", V1Codec.hex(ByteArray(16).also { random.nextBytes(it) })),
                    )
            val payload = JSONObject().put("dps", JSONObject().put("101", inner.toString())).put("t", timestamp)
            val message =
                V1Codec.Message(
                    seq = 100_000 + random.nextInt(899_999),
                    random = 10_000 + random.nextInt(89_999),
                    timestamp = timestamp,
                    protocol = V1Codec.PROTOCOL_RPC_REQUEST,
                    payload = payload.toString().toByteArray(),
                )
            mqtt.publish(publishTopic, V1Codec.encode(message, account.device.localKey))
            while (true) {
                val publish = mqtt.receive(deadline) ?: throw NoReply(method)
                val reply =
                    try {
                        V1Codec.decode(publish.payload, account.device.localKey)
                    } catch (e: Exception) {
                        continue
                    }
                if (reply.protocol != V1Codec.PROTOCOL_RPC_RESPONSE) continue
                val body = reply.payload?.let { JSONObject(String(it, Charsets.UTF_8)) } ?: continue
                val point = body.optJSONObject("dps")?.optString("102").orEmpty()
                if (point.isEmpty()) continue
                val response = JSONObject(point)
                if (response.optInt("id") != requestId) continue
                response.opt("error")?.let { throw RpcError(method, it.toString()) }
                val result = response.opt("result") ?: throw RpcError(method, "no result")
                if (result is String && result != "ok") throw RpcError(method, result)
                if (result is JSONArray && result.length() == 1 && result.opt(0) is String && result.getString(0) != "ok") {
                    throw RpcError(method, result.getString(0))
                }
                return result
            }
        }

        override fun close() = mqtt.close()
    }
}
