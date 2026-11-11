package com.evenrealities.devices

/** The four-byte AuthMgr body retains the Android phoneType=4 from Dart. */
object G2BusinessAuthCodec {
    private const val DEVICE_SETTINGS_SID = 0x80
    private const val AUTHENTICATION_COMMAND = 0x04

    /** One common-channel packet; caller sends it only after successful 8403 authorization. */
    fun request(syncId: Int, magic: Int): ByteArray {
        require(syncId in 0..255 && magic in 1..127)
        val payload = byteArrayOf(
            0x08, AUTHENTICATION_COMMAND.toByte(),
            0x10, magic.toByte(),
            0x1a, 0x04, 0x08, 0x01, 0x10, 0x04,
        )
        return frame(0x21, syncId, false, payload)
    }

    internal enum class Signal { COMMAND_ACCEPTED, AUTHENTICATED }
    private val envelopeFields = setOf(1, 2, 3)
    private val authFields = setOf(1, 3)

    /** 8402 carries a correlated command ACK and a separate, device-numbered AuthMgr push. */
    internal fun classify(frame: ByteArray, requestMagic: Int): Signal? {
        if (frame.size < 10 || requestMagic !in 1..65535) return null
        val address = frame[1].toInt() and 0xff
        val flags = frame[7].toInt() and 0xff
        val end = 8 + (frame[3].toInt() and 0xff)
        if (frame[0] != 0xaa.toByte() || address !in setOf(0x12, 0x14, 0x15) ||
            frame[4] != 1.toByte() || frame[5] != 1.toByte() || frame[6] != DEVICE_SETTINGS_SID.toByte() ||
            flags and 0xde != 0 || end !in 10..frame.size) return null
        // A longer ATT value may include padding after the declared frame CRC.
        val payload = frame.copyOfRange(8, end - 2)
        val crc = (frame[end - 2].toInt() and 0xff) or ((frame[end - 1].toInt() and 0xff) shl 8)
        if (crc != crc16(payload)) return null
        val fields = G2TimeSyncCodec.readFields(payload, envelopeFields) ?: return null
        if (fields[1]?.scalar != AUTHENTICATION_COMMAND.toLong() ||
            (2 in fields && fields[2]?.scalar == null)) return null
        val nested = fields[3]?.bytes ?: return null
        val auth = G2TimeSyncCodec.readFields(nested, authFields) ?: return null
        if (auth.values.any { it.scalar == null } || (auth[3]?.scalar ?: 0L) != 0L) return null
        return if (flags and 1 != 0) {
            Signal.AUTHENTICATED.takeIf { auth[1]?.scalar == 1L }
        } else {
            Signal.COMMAND_ACCEPTED.takeIf { fields[2]?.scalar == requestMagic.toLong() }
        }
    }

    /** Route only the current AUTH command and identifiers to the strict verifier. */
    fun isResponseFor(frame: ByteArray, syncId: Int, magic: Int): Boolean =
        frame.size >= 14 &&
            (frame[0].toInt() and 0xff) == 0xaa &&
            (frame[1].toInt() and 0xff) == 0x12 &&
            (frame[2].toInt() and 0xff) == syncId &&
            (frame[4].toInt() and 0xff) == 1 &&
            (frame[5].toInt() and 0xff) == 1 &&
            (frame[6].toInt() and 0xff) == DEVICE_SETTINGS_SID &&
            (frame[7].toInt() and 0xff) == 1 &&
            (frame[8].toInt() and 0xff) == 0x08 &&
            (frame[9].toInt() and 0xff) == AUTHENTICATION_COMMAND &&
            (frame[10].toInt() and 0xff) == 0x10 &&
            (frame[11].toInt() and 0xff) == magic

    /**
     * Compare the actual two trailing bytes to CRC16 of the received payload.
     * Never recompute a replacement CRC during parsing as the old Dart parser did.
     */
    fun isAuthenticatedResponse(frame: ByteArray, syncId: Int, magic: Int): Boolean {
        if (frame.size < 10 || (frame[0].toInt() and 0xff) != 0xaa ||
            (frame[1].toInt() and 0xff) != 0x12 ||
            (frame[2].toInt() and 0xff) != syncId ||
            (frame[3].toInt() and 0xff) != frame.size - 8 ||
            (frame[4].toInt() and 0xff) != 1 || (frame[5].toInt() and 0xff) != 1 ||
            (frame[6].toInt() and 0xff) != DEVICE_SETTINGS_SID ||
            (frame[7].toInt() and 0xff) != 1) return false
        val payload = frame.copyOfRange(8, frame.size - 2)
        val receivedCrc = (frame[frame.size - 2].toInt() and 0xff) or
            ((frame[frame.size - 1].toInt() and 0xff) shl 8)
        if (receivedCrc != crc16(payload)) return false
        return parseDevConfigAuth(payload, magic)
    }

    private fun frame(address: Int, syncId: Int, notify: Boolean, payload: ByteArray): ByteArray {
        val crc = crc16(payload)
        return byteArrayOf(
            0xaa.toByte(), address.toByte(), syncId.toByte(), (payload.size + 2).toByte(),
            1, 1, DEVICE_SETTINGS_SID.toByte(), if (notify) 1 else 0,
        ) + payload + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
    }

    private fun parseDevConfigAuth(bytes: ByteArray, magic: Int): Boolean {
        var commandSeen = false
        var magicSeen = false
        var authSeen = false
        var position = 0
        while (position < bytes.size) {
            val (tag, tagEnd) = readVarint(bytes, position) ?: return false
            position = tagEnd
            when (tag) {
                8 -> {
                    if (commandSeen) return false
                    val (value, end) = readVarint(bytes, position) ?: return false
                    if (value != AUTHENTICATION_COMMAND) return false
                    commandSeen = true
                    position = end
                }
                16 -> {
                    if (magicSeen) return false
                    val (value, end) = readVarint(bytes, position) ?: return false
                    if (value != magic) return false
                    magicSeen = true
                    position = end
                }
                26 -> {
                    if (authSeen) return false
                    val (length, start) = readVarint(bytes, position) ?: return false
                    if (length < 0 || start + length > bytes.size) return false
                    if (!parseAuthMgr(bytes.copyOfRange(start, start + length))) return false
                    authSeen = true
                    position = start + length
                }
                else -> return false // Unknown fields cannot masquerade as an AUTH response.
            }
        }
        return commandSeen && magicSeen && authSeen
    }

    private fun parseAuthMgr(bytes: ByteArray): Boolean {
        var secAuth = false
        var position = 0
        while (position < bytes.size) {
            val (tag, tagEnd) = readVarint(bytes, position) ?: return false
            val (value, end) = readVarint(bytes, tagEnd) ?: return false
            when (tag) {
                8 -> {
                    if (secAuth || value != 1) return false
                    secAuth = true
                }
                16 -> if (value !in 0..4) return false // Optional device echo.
                24 -> if (value != 0) return false // Optional eErrorCode.SUCCESS.
                else -> return false
            }
            position = end
        }
        return secAuth
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Int, Int>? {
        var value = 0
        var shift = 0
        var position = start
        while (position < bytes.size && shift <= 28) {
            val byte = bytes[position++].toInt() and 0xff
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) return value to position
            shift += 7
        }
        return null
    }

    private fun crc16(bytes: ByteArray): Int {
        var crc = 0xffff
        for (byte in bytes) {
            crc = ((crc shl 8) or (crc ushr 8)) and 0xffff
            crc = crc xor (byte.toInt() and 0xff)
            crc = crc xor ((crc and 0xff) ushr 4)
            crc = crc xor ((crc shl 12) and 0xffff)
            crc = crc xor (((crc and 0xff) shl 5) and 0xffff)
        }
        return crc and 0xffff
    }
}
