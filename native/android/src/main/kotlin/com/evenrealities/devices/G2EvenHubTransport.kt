package com.evenrealities.devices

import java.io.ByteArrayOutputStream

/** Bounded SID224 transport. CRC covers the complete protobuf and occurs only in the final frame. */
internal object G2EvenHubTransport {
    const val MAX_PAYLOAD = 32768
    data class Message(val sync: Int, val result: Int, val payload: ByteArray)

    /** Minimum enterprise MTU150 allows 147 ATT bytes; larger negotiated MTUs remain optional. */
    fun packets(payload: ByteArray, sync: Int): List<ByteArray> {
        require(payload.isNotEmpty() && payload.size <= MAX_PAYLOAD)
        val parts = mutableListOf<ByteArray>()
        var offset = 0
        while (offset < payload.size) {
            val remaining = payload.size - offset
            val count = if (remaining <= 137) remaining else minOf(139, remaining - 1)
            parts += payload.copyOfRange(offset, offset + count)
            offset += count
        }
        require(parts.size <= 255)
        val crc = crc16(payload)
        return parts.mapIndexed { index, part ->
            val last = index == parts.lastIndex
            byteArrayOf(0xaa.toByte(), 0x21, sync.toByte(), (part.size + if (last) 2 else 0).toByte(),
                parts.size.toByte(), (index + 1).toByte(), 224.toByte(), 0x20) + part +
                if (last) byteArrayOf(crc.toByte(), (crc ushr 8).toByte()) else byteArrayOf()
        }
    }

    /** Each page owns its assembler; replacement/disconnection discards every partial old message. */
    class Assembler {
        private data class Stream(val total: Int, var next: Int, val started: Long,
                                  val bytes: ByteArrayOutputStream = ByteArrayOutputStream())
        private val streams = linkedMapOf<String, Stream>()

        fun accept(data: ByteArray, now: Long): Message? {
            streams.entries.removeAll { now - it.value.started >= 3000 }
            if (data.size < 8 || data[0].u != 0xaa || data[1].u ushr 4 != 1 ||
                data[1].u and 15 !in listOf(2, 4, 5) || data.size < 8 + data[3].u ||
                data[6].u != 224 || data[7].u and 0xc0 != 0) return null
            // ATT callbacks may include padding. Protocol packetLen, including final CRC,
            // is the frame boundary used by production Dart; padding is never payload or CRC.
            val frameEnd = 8 + data[3].u
            val sync = data[2].u
            val result = (data[7].u ushr 1) and 15
            val key = "${data[1].u}:$sync:${data[7].u}"
            val total = data[4].u
            val serial = data[5].u
            if (total == 0 || serial !in 1..total) { streams.remove(key); return null }
            if (result != 0) {
                streams.remove(key)
                return if (total == 1 && serial == 1) Message(sync, result, byteArrayOf()) else null
            }
            val last = serial == total
            if (frameEnd < 8 + if (last) 2 else 1) { streams.remove(key); return null }
            val end = frameEnd - if (last) 2 else 0
            val part = data.copyOfRange(8, end)
            val stream = if (serial == 1) {
                // Restarting an incomplete sync is ambiguous: drop both rather than splicing messages.
                if (streams.remove(key) != null) return null
                if (streams.size >= 4) return null
                Stream(total, 1, now).also { streams[key] = it }
            } else streams[key] ?: return null
            if (stream.total != total || stream.next != serial || stream.bytes.size() + part.size > MAX_PAYLOAD) {
                streams.remove(key); return null
            }
            stream.bytes.write(part); stream.next++
            if (!last) return null
            streams.remove(key)
            val payload = stream.bytes.toByteArray()
            val crc = data[frameEnd - 2].u or (data[frameEnd - 1].u shl 8)
            if (payload.isEmpty() || crc16(payload) != crc) return null
            return Message(sync, 0, payload)
        }
    }

    internal fun crc16(data: ByteArray): Int {
        var crc = 0xffff
        for (byte in data) {
            crc = ((crc shl 8) or (crc ushr 8)) and 0xffff
            crc = crc xor byte.u
            crc = crc xor ((crc and 255) ushr 4)
            crc = crc xor ((crc shl 12) and 0xffff)
            crc = crc xor (((crc and 255) shl 5) and 0xffff)
        }
        return crc and 0xffff
    }
    private val Byte.u get() = toInt() and 255
}
