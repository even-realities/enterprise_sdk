package com.evenrealities.devices

import java.io.ByteArrayOutputStream

/**
 * First native G2 business command migrated from Dart's createTimeSyncCommand.
 * Only the enterprise SID allowlist may enter 8401; this codec deliberately
 * supports one complete common packet because TimeSync always fits the MTU.
 */
object G2TimeSyncCodec {
    private const val DEVICE_SETTINGS_SID = 128
    private const val TIME_SYNC_COMMAND = 128
    private val allowedSids = setOf(DEVICE_SETTINGS_SID, 4, 224)
    // Device-config oneof membership comes from dev_config_protocol.proto;
    // unknown protobuf extensions do not select or replace a known branch.
    private val deviceConfigBranches = (3..12).toSet() + setOf(14, 128, 129)
    private val glassesSources = setOf(2, 4, 5)

    enum class Response { IGNORED, SUCCESS, REJECTED }

    /** Minutes are truncated toward zero in 15-minute units, matching Dart. */
    fun timezoneUnits(offsetMinutes: Int): Int = (offsetMinutes / 15).coerceIn(-128, 127)

    fun request(epochSeconds: Long, offsetMinutes: Int, syncId: Int, magic: Int,
                syncBoth: Boolean = true): ByteArray {
        require(epochSeconds in 0..0xffffffffL && syncId in 0..255 && magic in 1..65535)
        val timeSync = ByteArrayOutputStream().apply {
            write(8)
            writeVarint(epochSeconds)
            write(16)
            writeVarint(timezoneUnits(offsetMinutes).toLong())
        }.toByteArray()
        val payload = ByteArrayOutputStream().apply {
            write(8)
            writeVarint(TIME_SYNC_COMMAND.toLong())
            write(16)
            writeVarint(magic.toLong())
            // DevCfgDataPackage.timeSync is field 128, wire type 2.
            writeVarint((128L shl 3) or 2L)
            writeVarint(timeSync.size.toLong())
            write(timeSync)
        }.toByteArray()
        return singlePacket(DEVICE_SETTINGS_SID, syncId, payload, syncBoth)
    }

    /** Reject legacy, ring, and unknown service IDs at the native boundary. */
    fun singlePacket(serviceId: Int, syncId: Int, payload: ByteArray,
                     syncBoth: Boolean = true): ByteArray {
        // Enterprise G2 minimum MTU is 150: ATT(3) + header(8) + CRC(2).
        // Larger business payloads must use the later native fragmentation path.
        require(serviceId in allowedSids && syncId in 0..255 && payload.size <= 137)
        val crc = crc16(payload)
        return byteArrayOf(
            0xaa.toByte(), 0x21, syncId.toByte(), (payload.size + 2).toByte(),
            1, 1, serviceId.toByte(), if (syncBoth) 0x20 else 0,
        ) + payload + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
    }

    /** Firmware response sync ID is independent; match protobuf magic, SID, and command. */
    fun response(frame: ByteArray, magic: Int): Response {
        if (frame.size < 10) return Response.IGNORED
        val endpoints = frame[1].toInt() and 0xff
        val packetLength = frame[3].toInt() and 0xff
        val end = 8 + packetLength
        // ATT notifications may contain trailing padding. Use the declared
        // boundary for both payload and CRC, while rejecting any truncation.
        // isBoth is a documented routing flag, not an error; unsolicited,
        // unsuccessful and reserved-bit frames cannot acknowledge this command.
        if ((frame[0].toInt() and 0xff) != 0xaa || endpoints ushr 4 != 1 ||
            (endpoints and 0x0f) !in glassesSources || packetLength < 2 || frame.size < end ||
            (frame[4].toInt() and 0xff) != 1 || (frame[5].toInt() and 0xff) != 1 ||
            (frame[6].toInt() and 0xff) != DEVICE_SETTINGS_SID ||
            (frame[7].toInt() and 0xdf) != 0) return Response.IGNORED
        val payload = frame.copyOfRange(8, end - 2)
        val receivedCrc = (frame[end - 2].toInt() and 0xff) or
            ((frame[end - 1].toInt() and 0xff) shl 8)
        if (receivedCrc != crc16(payload)) return Response.IGNORED
        val fields = readFields(payload) ?: return Response.IGNORED
        if (fields[1]?.scalar != TIME_SYNC_COMMAND.toLong() ||
            fields[2]?.scalar != magic.toLong()) return Response.IGNORED
        if (fields.keys.filter { it in deviceConfigBranches } != listOf(128)) return Response.REJECTED
        val nested = fields[128]?.bytes ?: return Response.REJECTED
        val nestedFields = readFields(nested) ?: return Response.REJECTED
        // Proto3 permits an omitted result=0. A present field with the wrong
        // wire type is malformed, and must never inherit that success default.
        if ((1..3).any { nestedFields[it]?.let { field -> field.scalar == null } == true }) return Response.REJECTED
        val result = nestedFields[3]?.scalar ?: 0L
        return if (result == 0L) Response.SUCCESS else Response.REJECTED
    }

    internal data class Field(val scalar: Long? = null, val bytes: ByteArray? = null)

    /** Minimal protobuf reader; duplicate or unsupported fields fail closed. */
    internal fun readFields(bytes: ByteArray): Map<Int, Field>? = readFields(bytes, null)

    /** AUTH skips unknown protobuf extensions, while rejecting repeated or malformed known fields. */
    internal fun readFields(bytes: ByteArray, include: Set<Int>?): Map<Int, Field>? {
        val fields = mutableMapOf<Int, Field>()
        var offset = 0
        while (offset < bytes.size) {
            val (tag, afterTag) = readVarint(bytes, offset) ?: return null
            val fieldNumber = tag ushr 3
            // Validate before narrowing: oversized UInt64 tags must not alias
            // a supported Int field, and unsigned lengths must not wrap negative.
            if (fieldNumber !in 1L..0x1fffffffL) return null
            val number = fieldNumber.toInt()
            val keep = include == null || number in include
            if (number in fields) return null
            offset = afterTag
            when ((tag and 7L).toInt()) {
                0 -> {
                    val (value, next) = readVarint(bytes, offset) ?: return null
                    if (keep) fields[number] = Field(scalar = value)
                    offset = next
                }
                2 -> {
                    val (length, next) = readVarint(bytes, offset) ?: return null
                    if (length < 0 || length > bytes.size - next) return null
                    offset = next + length.toInt()
                    if (keep) fields[number] = Field(bytes = bytes.copyOfRange(next, offset))
                }
                1, 5 -> {
                    val size = if (tag and 7L == 1L) 8 else 4
                    if (keep || size > bytes.size - offset) return null
                    offset += size
                }
                else -> return null
            }
        }
        return fields
    }

    private fun readVarint(bytes: ByteArray, start: Int): Pair<Long, Int>? {
        var result = 0L
        var offset = start
        for (shift in 0..63 step 7) {
            if (offset >= bytes.size) return null
            val byte = bytes[offset++].toInt() and 0xff
            if (shift == 63 && byte > 1) return null
            result = result or ((byte.toLong() and 0x7f) shl shift)
            if (byte and 0x80 == 0) return result to offset
        }
        return null
    }

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var remaining = value
        while (remaining and -128L != 0L) {
            write(((remaining and 0x7f) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        write(remaining.toInt())
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
