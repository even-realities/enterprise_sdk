package com.evenrealities.devices

/** Header bytes go to INFORMATION; only body bytes enter acknowledged RAW windows. */
internal data class G2OtaComponent(val header: ByteArray, val body: ByteArray)
internal data class G2OtaImage(val version: String, val components: List<G2OtaComponent>)

/** G2 enterprise OTA uses the dedicated C0/C1 transport and the EVENOTA component table. */
internal object G2OtaWire {
    private const val CONTROL = 0xc0
    private const val RAW = 0xc1
    private val Byte.u get() = toInt() and 0xff

    /** Validate the entire manifest before START can put either endpoint into upgrade mode. */
    fun parse(bytes: ByteArray): G2OtaImage {
        fun matches(at: Int, magic: String) = magic.indices.all { bytes[at + it].u == magic[it].code }
        fun u32(at: Int): Long = (0 until 4).fold(0L) { value, i ->
            value or (bytes[at + i].u.toLong() shl (8 * i))
        }
        require(bytes.size >= 80 && matches(0, "EVENOTA\u0000")) { "invalidFirmware" }
        val count = u32(8)
        // Bound count by the table and minimum size of all components before multiplication.
        require(count in 1..((bytes.size - 80) / (16 + 128)).toLong()) { "invalidFirmware" }
        val footer = 64 + count.toInt() * 16
        require(matches(footer, "evenota\u0000")) { "invalidFirmware" }
        val versionEnd = (48 until 64).firstOrNull { bytes[it] == 0.toByte() } ?: 64
        require(versionEnd > 48 && (48 until versionEnd).all { bytes[it].u in 32..126 } &&
            (versionEnd until 64).all { bytes[it] == 0.toByte() }) { "invalidFirmware" }
        val version = String(bytes, 48, versionEnd - 48, Charsets.US_ASCII)
        val components = mutableListOf<G2OtaComponent>()
        var start = footer + 16
        repeat(count.toInt()) { index ->
            val entry = 64 + index * 16
            val length = u32(entry + 8)
            val crc = u32(entry + 12).toInt()
            require(u32(entry + 4) == start.toLong() &&
                length in 128..(bytes.size - start).toLong()) { "invalidFirmware" }
            val end = start + length.toInt()
            require(matches(start + 20, "EVEN") && u32(start + 8) == length - 128 &&
                u32(start + 12).toInt() == crc) { "invalidFirmware" }
            val body = bytes.copyOfRange(start + 128, end)
            require(imageCrc(body) == crc) { "invalidFirmware" }
            components += G2OtaComponent(bytes.copyOfRange(start, start + 128), body)
            start = end
        }
        require(start == bytes.size) { "invalidFirmware" }
        return G2OtaImage(version, components)
    }

    // EVEN component CRC uses the unreflected Castagnoli polynomial, initial state zero, no xorout.
    private val imageTable = IntArray(256) { index ->
        var value = index shl 24
        repeat(8) { value = (value shl 1) xor if (value < 0) 0x1edc6f41 else 0 }
        value
    }
    private fun imageCrc(bytes: ByteArray): Int {
        var crc = 0
        for (byte in bytes) crc = (crc shl 8) xor imageTable[(crc ushr 24) xor byte.u]
        return crc
    }

    /** Control frames always fit one write, including the 128-byte component header. */
    fun control(cid: Int, sync: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(sync in 0..255 && cid in 0..3 && payload.size == if (cid == 1) 128 else 0)
        val body = byteArrayOf(cid.toByte()) + payload
        return frame(CONTROL, sync, 1, 1, body, G2EvenHubTransport.crc16(body))
    }

    /** Each FILE announces exactly one RAW window; the last fragment carries the whole window CRC. */
    fun window(data: ByteArray, sync: Int, maxWrite: Int): List<ByteArray> {
        require(data.size in 1..4096 && maxWrite >= 11)
        val capacity = minOf(255, maxWrite - 8)
        val partCount = (data.size + capacity - 1) / capacity
        val remaining = data.size - (partCount - 1) * capacity
        val total = partCount + if (remaining + 2 > capacity) 1 else 0
        require(total <= 255)
        val frames = mutableListOf(control(2, sync))
        val crc = G2EvenHubTransport.crc16(data)
        for (i in 0 until total) {
            val start = minOf(i * capacity, data.size)
            val part = data.copyOfRange(start, minOf(start + capacity, data.size))
            frames += frame(RAW, sync, total, i + 1, part, crc.takeIf { i + 1 == total })
        }
        return frames
    }

    private fun frame(service: Int, sync: Int, total: Int, serial: Int, part: ByteArray, crc: Int?): ByteArray {
        val length = part.size + if (crc != null) 2 else 0
        val header = byteArrayOf(0xaa.toByte(), 0x21, sync.toByte(), length.toByte(),
            total.toByte(), serial.toByte(), service.toByte(), 0)
        return header + part + if (crc == null) byteArrayOf() else byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
    }

    /** Unrelated receipts are ignored; malformed or rejected matched replies cannot release a window. */
    fun reply(frame: ByteArray, expectedCid: Int, expectedSync: Int): Int? {
        val service = if (expectedCid == 2) RAW else CONTROL
        if (!isSingleInbound(frame, service) || frame[2].u != expectedSync || frame[7].u and 1 != 0) return null
        if (frame[7].u != 0 || !validResponsePayload(frame)) return -1
        if (frame[8].u == 4 && service == CONTROL) return null
        return if (frame[8].u == expectedCid) frame[9].u else -1
    }

    /** Firmware owns the NOTIFY sync byte. The transfer owner must also gate it on endpoint and final RESULT. */
    fun notify(frame: ByteArray): Int? {
        if (!isSingleInbound(frame, CONTROL) || frame[7].u !in 0..1 ||
            !validResponsePayload(frame) || frame[8].u != 4) return null
        return frame[9].u
    }

    private fun isSingleInbound(frame: ByteArray, service: Int) = frame.size >= 8 &&
        frame[0].u == 0xaa && frame[1].u == 0x12 && frame[4].u == 1 && frame[5].u == 1 && frame[6].u == service

    // packetLen includes the little-endian CRC. Ignore ATT padding beyond the declared frame.
    private fun validResponsePayload(frame: ByteArray): Boolean {
        if (frame.size < 12 || frame[3].u != 4) return false
        val crc = frame[10].u or (frame[11].u shl 8)
        return G2EvenHubTransport.crc16(frame.copyOfRange(8, 10)) == crc
    }
}
