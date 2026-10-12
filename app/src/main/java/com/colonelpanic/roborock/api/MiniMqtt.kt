package com.colonelpanic.roborock.api

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Just enough MQTT 3.1.1 for request/response RPC: connect, one QoS 0
 * subscription, QoS 0 publishes, and blocking reads bounded by a deadline.
 */
class MiniMqtt private constructor(
    private val socket: Socket,
) : AutoCloseable {
    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output: OutputStream = socket.getOutputStream()
    private var nextPacketId = 1

    class Publish(
        val topic: String,
        val payload: ByteArray,
    )

    class AuthenticationException(
        message: String,
    ) : IOException(message)

    fun subscribe(
        topic: String,
        deadline: Long,
    ) {
        val id = nextPacketId++
        val body = ByteArrayOutputStream()
        body.write(id shr 8)
        body.write(id and 0xff)
        body.writeString(topic)
        body.write(0)
        send(0x82, body.toByteArray())
        while (true) {
            val (type, data) = readPacket(deadline)
            if (type == 9) {
                if (data.size >= 3 && (data[2].toInt() and 0xff) == 0x80) throw IOException("Broker refused the subscription.")
                return
            }
        }
    }

    fun publish(
        topic: String,
        payload: ByteArray,
    ) {
        val body = ByteArrayOutputStream()
        body.writeString(topic)
        body.write(payload)
        send(0x30, body.toByteArray())
    }

    /** Returns the next PUBLISH, or null once [deadline] (epoch millis) passes. */
    fun receive(deadline: Long): Publish? {
        while (true) {
            val (header, data) =
                try {
                    readPacket(deadline)
                } catch (e: SocketTimeoutException) {
                    return null
                }
            if (header shr 4 != 3) continue
            val topicLength = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
            val topic = String(data, 2, topicLength, Charsets.UTF_8)
            var offset = 2 + topicLength
            val qos = (header shr 1) and 3
            if (qos > 0) {
                val id = data.copyOfRange(offset, offset + 2)
                offset += 2
                if (qos == 1) send(0x40, id)
            }
            return Publish(topic, data.copyOfRange(offset, data.size))
        }
    }

    override fun close() {
        try {
            send(0xE0, ByteArray(0))
        } catch (e: IOException) {
            // Closing anyway.
        }
        socket.close()
    }

    private fun send(
        header: Int,
        body: ByteArray,
    ) {
        val packet = ByteArrayOutputStream()
        packet.write(header)
        var length = body.size
        do {
            var digit = length % 128
            length /= 128
            if (length > 0) digit = digit or 0x80
            packet.write(digit)
        } while (length > 0)
        packet.write(body)
        synchronized(output) {
            output.write(packet.toByteArray())
            output.flush()
        }
    }

    /** Reads one packet; returns the fixed-header byte (for PUBLISH) or type, and the body. */
    private fun readPacket(deadline: Long): Pair<Int, ByteArray> {
        val remaining = deadline - System.currentTimeMillis()
        if (remaining <= 0) throw SocketTimeoutException("Deadline passed")
        socket.soTimeout = remaining.toInt()
        val header = input.read()
        if (header < 0) throw EOFException("Broker closed the connection.")
        var multiplier = 1
        var length = 0
        while (true) {
            val digit = input.readUnsignedByte()
            length += (digit and 0x7f) * multiplier
            if (digit and 0x80 == 0) break
            multiplier *= 128
        }
        val data = ByteArray(length)
        input.readFully(data)
        val type = header shr 4
        return (if (type == 3) header else type) to data
    }

    companion object {
        fun connect(
            host: String,
            port: Int,
            tls: Boolean,
            clientId: String,
            username: String,
            password: String,
            deadline: Long,
        ): MiniMqtt {
            val timeout = (deadline - System.currentTimeMillis()).coerceAtLeast(1).toInt()
            val raw = Socket().apply { connect(InetSocketAddress(host, port), timeout) }
            val socket =
                if (tls) {
                    (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(raw, host, port, true).also {
                        it.soTimeout = timeout
                        (it as SSLSocket).startHandshake()
                    }
                } else {
                    raw
                }
            val client = MiniMqtt(socket)
            try {
                val body = ByteArrayOutputStream()
                body.writeString("MQTT")
                body.write(4)
                body.write(0xC2)
                body.write(0)
                body.write(60)
                body.writeString(clientId)
                body.writeString(username)
                body.writeString(password)
                client.send(0x10, body.toByteArray())
                val (type, data) = client.readPacket(deadline)
                if (type != 2 || data.size < 2) throw IOException("Unexpected reply to MQTT connect.")
                when (val code = data[1].toInt() and 0xff) {
                    0 -> Unit
                    4, 5 -> throw AuthenticationException("Roborock's MQTT broker rejected the stored credentials (code $code).")
                    else -> throw IOException("Roborock's MQTT broker refused the connection (code $code).")
                }
            } catch (e: Exception) {
                socket.close()
                throw e
            }
            return client
        }

        private fun ByteArrayOutputStream.writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            write(bytes.size shr 8)
            write(bytes.size and 0xff)
            write(bytes)
        }
    }
}
