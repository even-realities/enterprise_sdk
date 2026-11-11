package com.evenrealities.devices

/** Pair snapshot from one fresh, authenticated BASIC_SETTING response. */
internal data class G2OtaVersions(val left: String, val right: String) {
    fun matches(target: String): Boolean {
        val expected = components(target) ?: return false
        return components(left) == expected && components(right) == expected
    }

    companion object {
        fun isValidTarget(target: String): Boolean = components(target) != null

        // Follow firmware's dotted decimal comparison (V prefix and implicit trailing zeros).
        // Reject malformed text instead of coercing nonnumeric components into version zero.
        private fun components(version: String): List<Long>? {
            val trimmed = version.trim()
            val digits = if (trimmed.startsWith("v", ignoreCase = true)) trimmed.substring(1) else trimmed
            if (digits.length !in 1..11) return null
            val parts = digits.split('.').map { part ->
                if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
                part.toLong()
            }
            // The production settings service uses 0.0.0 as an unknown firmware version.
            return parts.dropLastWhile { it == 0L }.takeIf { it.isNotEmpty() }
        }
    }
}

/** Only the OTA owner may request this one selector on UI_SETTING_APP_ID (0x09). */
internal object G2OtaVersionWire {
    private const val SID = 9

    fun request(syncId: Int, magic: Int): ByteArray {
        require(syncId in 0..255 && magic in 1..255)
        val magicBytes = if (magic < 128) byteArrayOf(magic.toByte())
            else byteArrayOf((magic or 128).toByte(), 1)
        // G2SettingPackage { DeviceReceiveRequest, magic, field 4 { BASIC_SETTING=1 } }.
        val payload = byteArrayOf(8, 2, 16) + magicBytes + byteArrayOf(34, 2, 8, 1)
        val crc = G2EvenHubTransport.crc16(payload)
        return byteArrayOf(0xaa.toByte(), 0x21, syncId.toByte(), (payload.size + 2).toByte(),
            1, 1, SID.toByte(), 0x20) + payload + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
    }

    /** Only a correlated reply can yield versions. The caller separately owns SN and link generation. */
    fun response(frame: ByteArray, magic: Int): G2OtaVersions? {
        // The current 19-field nanopb schema caps this complete frame at 146 bytes;
        // enterprise ATT admits at least 147. Partial frames never prove installation.
        if (magic !in 1..255 || frame.size < 10 || frame[0].u != 0xaa ||
            frame[1].u !in setOf(0x12, 0x14, 0x15) || frame[4].u != 1 || frame[5].u != 1 ||
            frame[6].u != SID || frame[7].u and 0xdf != 0) return null
        val end = 8 + frame[3].u
        if (end < 10 || end > frame.size) return null
        val payload = frame.copyOfRange(8, end - 2)
        if (G2EvenHubTransport.crc16(payload) != (frame[end - 2].u or (frame[end - 1].u shl 8))) return null
        val root = G2TimeSyncCodec.readFields(payload) ?: return null
        if (root[1]?.scalar != 2L || root[2]?.scalar != magic.toLong() ||
            root.keys.filter { it in 3..7 } != listOf(4)) return null
        val settings = G2TimeSyncCodec.readFields(root[4]?.bytes ?: return null) ?: return null
        if (settings[1]?.scalar != 1L) return null
        return G2OtaVersions(version(settings[5]?.bytes) ?: return null,
            version(settings[6]?.bytes) ?: return null)
    }

    // nanopb max_size=12 includes the trailing C NUL; on the wire it is a protobuf string.
    private fun version(bytes: ByteArray?): String? {
        if (bytes == null || bytes.size !in 1..11 || bytes.any { it.u > 127 }) return null
        return String(bytes, Charsets.US_ASCII).takeIf(G2OtaVersions::isValidTarget)
    }

    private val Byte.u get() = toInt() and 255
}
