package com.colonelpanic.roborock.api

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** Roborock's "1.0" message frame as sent over MQTT; ported from python-roborock's protocol.py. */
object V1Codec {
    const val PROTOCOL_RPC_REQUEST = 101
    const val PROTOCOL_RPC_RESPONSE = 102

    private val SALT = "TXdfu\$jyZ#TZHsg4".toByteArray()
    private val VERSION = "1.0".toByteArray()

    class Message(
        val seq: Int,
        val random: Int,
        val timestamp: Long,
        val protocol: Int,
        val payload: ByteArray?,
        val version: String = "1.0",
    )

    fun md5(data: ByteArray): ByteArray = MessageDigest.getInstance("MD5").digest(data)

    fun md5Hex(text: String): String = hex(md5(text.toByteArray()))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun encodeTimestamp(timestamp: Long): ByteArray {
        val digits = "%08x".format(timestamp)
        return intArrayOf(5, 6, 3, 7, 1, 2, 0, 4).map { digits[it] }.joinToString("").toByteArray()
    }

    private fun key(
        localKey: String,
        timestamp: Long,
    ): ByteArray = md5(encodeTimestamp(timestamp) + localKey.toByteArray() + SALT)

    // The robot protocol mandates AES-ECB.
    @Suppress("GetInstance")
    private fun cipher(
        mode: Int,
        key: ByteArray,
    ): Cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(mode, SecretKeySpec(key, "AES")) }

    fun encode(
        message: Message,
        localKey: String,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(VERSION)
        out.write(
            ByteBuffer
                .allocate(
                    14,
                ).putInt(message.seq)
                .putInt(message.random)
                .putInt(message.timestamp.toInt())
                .putShort(message.protocol.toShort())
                .array(),
        )
        message.payload?.let { payload ->
            val encrypted = cipher(Cipher.ENCRYPT_MODE, key(localKey, message.timestamp)).doFinal(payload)
            out.write(ByteBuffer.allocate(2).putShort(encrypted.size.toShort()).array())
            out.write(encrypted)
        }
        val body = out.toByteArray()
        val crc = CRC32().apply { update(body) }.value
        return body + ByteBuffer.allocate(4).putInt(crc.toInt()).array()
    }

    fun decode(
        data: ByteArray,
        localKey: String,
    ): Message {
        val buffer = ByteBuffer.wrap(data)
        val version = ByteArray(3).also { buffer.get(it) }.toString(Charsets.US_ASCII)
        if (version != "1.0") throw RoborockException("Unsupported message version '$version'; only 1.0 devices are supported.")
        val seq = buffer.int
        val random = buffer.int
        val timestamp = buffer.int.toLong() and 0xffffffffL
        val protocol = buffer.short.toInt() and 0xffff
        var payload: ByteArray? = null
        if (buffer.remaining() >= 2) {
            val length = buffer.short.toInt() and 0xffff
            if (length > 0) {
                val encrypted = ByteArray(length).also { buffer.get(it) }
                val bodyEnd = buffer.position()
                if (buffer.remaining() >= 4) {
                    val expected = buffer.int.toLong() and 0xffffffffL
                    val actual = CRC32().apply { update(data, 0, bodyEnd) }.value
                    if (expected != actual) throw RoborockException("Message checksum mismatch.")
                }
                payload = cipher(Cipher.DECRYPT_MODE, key(localKey, timestamp)).doFinal(encrypted)
            }
        }
        return Message(seq, random, timestamp, protocol, payload, version)
    }
}

open class RoborockException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
