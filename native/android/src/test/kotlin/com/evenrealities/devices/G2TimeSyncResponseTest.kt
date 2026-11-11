package com.evenrealities.devices

import org.junit.Assert.*
import org.junit.Test

/** Wire-boundary regressions use synthetic ACKs, not a claim about the current iOS failure. */
class G2TimeSyncResponseTest {
    @Test fun declaredFrameBoundaryAcceptsTailPaddingWithoutWeakeningCrcOrTruncationChecks() {
        val valid = hex("aa12090a01018000088001102a8208009146")
        for (tail in listOf(byteArrayOf(), ByteArray(12), byteArrayOf(0xaa.toByte(), 0xff.toByte(), 0x12))) {
            assertEquals(G2TimeSyncCodec.Response.SUCCESS, G2TimeSyncCodec.response(valid + tail, 42))
        }
        val truncated = valid.copyOf(valid.size - 1)
        val badCrc = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() } + ByteArray(8)
        val missingCrc = valid.copyOf().also { it[3] = 1 }
        val declaredLonger = valid.copyOf().also { it[3] = (it[3] + 1).toByte() }
        for (frame in listOf(truncated, badCrc, missingCrc, declaredLonger)) {
            assertNotEquals(G2TimeSyncCodec.Response.SUCCESS, G2TimeSyncCodec.response(frame, 42))
        }
    }

    @Test fun documentedGlassesSourcesAndMirroredResponseFlagAreAccepted() {
        for (source in listOf(2, 4, 5)) for (flags in listOf(0, 0x20)) {
            val frame = acknowledgement().also { it[1] = (0x10 or source).toByte(); it[7] = flags.toByte() }
            assertEquals("source=$source flags=$flags", G2TimeSyncCodec.Response.SUCCESS, G2TimeSyncCodec.response(frame, 42))
        }
    }

    @Test fun notifyErrorReservedBitsAndUntrustedEndpointsCannotBecomeSuccess() {
        for (flags in listOf(1, 2, 0x1e, 0x21, 0x40, 0x80, 0xc0, 0xff)) {
            val frame = acknowledgement().also { it[7] = flags.toByte() }
            assertNotEquals("flags=$flags", G2TimeSyncCodec.Response.SUCCESS, G2TimeSyncCodec.response(frame, 42))
        }
        for (sourceDestination in listOf(0x10, 0x11, 0x13, 0x16, 0x21, 0x22, 0x02, 0xff)) {
            val frame = acknowledgement().also { it[1] = sourceDestination.toByte() }
            assertNotEquals("endpoints=$sourceDestination", G2TimeSyncCodec.Response.SUCCESS, G2TimeSyncCodec.response(frame, 42))
        }
    }

    @Test fun actualTimeSyncBranchMustBeTheOnlyKnownDeviceConfigOneof() {
        val root = scalar(1, 128) + scalar(2, 42)
        for (other in (3..12).toList() + listOf(14, 129)) {
            for (branches in listOf(bytes(128, byteArrayOf()) + bytes(other, byteArrayOf()),
                bytes(other, byteArrayOf()) + bytes(128, byteArrayOf()))) {
                assertEquals("conflicting branch=$other", G2TimeSyncCodec.Response.REJECTED,
                    G2TimeSyncCodec.response(incoming(root + branches), 42))
            }
        }
        for (payload in listOf(root, root + scalar(128, 0), root + bytes(3, byteArrayOf()))) {
            assertEquals(G2TimeSyncCodec.Response.REJECTED, G2TimeSyncCodec.response(incoming(payload), 42))
        }
        // Unknown extensions retain protobuf compatibility and do not replace a known oneof.
        val extended = root + bytes(128, scalar(4, 9)) + scalar(200, 1)
        assertEquals(G2TimeSyncCodec.Response.SUCCESS, G2TimeSyncCodec.response(incoming(extended), 42))
    }

    @Test fun presentKnownFieldsMustBeScalarsAndResultMustBeSuccessful() {
        for (field in 1..3) {
            assertEquals("wrong wire for TimeSync field=$field", G2TimeSyncCodec.Response.REJECTED,
                G2TimeSyncCodec.response(acknowledgement(bytes(field, byteArrayOf())), 42))
        }
        for (result in listOf(1L, 255L, -1L)) {
            assertEquals(G2TimeSyncCodec.Response.REJECTED, G2TimeSyncCodec.response(acknowledgement(scalar(3, result)), 42))
        }
        assertEquals(G2TimeSyncCodec.Response.SUCCESS, G2TimeSyncCodec.response(acknowledgement(scalar(3, 0)), 42))
        assertEquals(G2TimeSyncCodec.Response.SUCCESS, G2TimeSyncCodec.response(acknowledgement(), 42))
    }

    @Test fun responseMatchingKeepsFullMagicEvenWhenSdkRequestsUseFirmwareByteRange() {
        for (magic in listOf(1, 42, 255, 256, 65535)) {
            val frame = acknowledgement(magic = magic)
            assertEquals(G2TimeSyncCodec.Response.SUCCESS, G2TimeSyncCodec.response(frame, magic))
            assertEquals(G2TimeSyncCodec.Response.IGNORED, G2TimeSyncCodec.response(frame, if (magic == 1) 2 else 1))
        }
    }

    /** The observed beta.10 metadata reconstructs these ACKs; they are not raw
     * captured packets. Restrict request allocation instead of masking an ACK
     * and accidentally authorizing a different pending request. */
    @Test fun observedLowByteRepliesDoNotMatchTheirFormerSixteenBitRequests() {
        for ((requested, returned) in listOf(40085 to 149, 45795 to 227)) {
            val reply = acknowledgement(magic = returned)
            assertEquals(G2TimeSyncCodec.Response.IGNORED, G2TimeSyncCodec.response(reply, requested))
            assertEquals(G2TimeSyncCodec.Response.SUCCESS, G2TimeSyncCodec.response(reply, returned))
        }
    }

    @Test fun unsignedLengthsAndOutOfRangeTagsFailClosedWithoutThrowing() {
        val root = scalar(1, 128) + scalar(2, 42)
        for (length in listOf(Long.MIN_VALUE, -1L, Long.MAX_VALUE, 30L)) {
            val payload = root + varint((128L shl 3) or 2) + varint(length)
            assertNotEquals("unsigned length=$length", G2TimeSyncCodec.Response.SUCCESS,
                G2TimeSyncCodec.response(incoming(payload), 42))
        }
        val nestedNegativeLength = varint((3L shl 3) or 2) + varint(-1L)
        assertNotEquals(G2TimeSyncCodec.Response.SUCCESS, G2TimeSyncCodec.response(acknowledgement(nestedNegativeLength), 42))
        // A 64-bit tag must not truncate its field number to the valid branch 128.
        val aliasedBranch = root + varint(((0x100000000L + 128) shl 3) or 2) + varint(0)
        val illegalExtension = root + bytes(128, byteArrayOf()) + varint(0x20000000L shl 3) + varint(0)
        for (payload in listOf(aliasedBranch, illegalExtension)) {
            assertNotEquals(G2TimeSyncCodec.Response.SUCCESS, G2TimeSyncCodec.response(incoming(payload), 42))
        }
        val largestLegalExtension = root + bytes(128, byteArrayOf()) + varint(0x1fffffffL shl 3) + varint(0)
        assertEquals(G2TimeSyncCodec.Response.SUCCESS, G2TimeSyncCodec.response(incoming(largestLegalExtension), 42))
    }

    @Test fun duplicateFieldsWrongCommandAndMalformedWireNeverCompleteAsSuccess() {
        val root = scalar(1, 128) + scalar(2, 42)
        val malformed = listOf(
            root + bytes(128, scalar(3, 0) + scalar(3, 0)),
            root + bytes(128, byteArrayOf()) + scalar(2, 42),
            root + bytes(128, byteArrayOf()) + bytes(128, byteArrayOf()),
            scalar(1, 127) + scalar(2, 42) + bytes(128, byteArrayOf()),
            root + bytes(128, byteArrayOf(0x18, 0x80.toByte())),
            root + bytes(128, byteArrayOf(0x00, 0x00)),
        )
        for (payload in malformed) assertNotEquals(G2TimeSyncCodec.Response.SUCCESS,
            G2TimeSyncCodec.response(incoming(payload), 42))
    }

    private fun acknowledgement(body: ByteArray = byteArrayOf(), magic: Int = 42) =
        incoming(scalar(1, 128) + scalar(2, magic.toLong()) + bytes(128, body))
    private fun incoming(payload: ByteArray) = G2TimeSyncCodec.singlePacket(128, 9, payload, false).also { it[1] = 0x12 }
    private fun scalar(field: Int, value: Long) = varint(field.toLong() shl 3) + varint(value)
    private fun bytes(field: Int, value: ByteArray) = varint((field.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
    private fun varint(value: Long): ByteArray {
        val output = mutableListOf<Byte>(); var remaining = value
        while (remaining and -128L != 0L) { output += ((remaining and 127) or 128).toByte(); remaining = remaining ushr 7 }
        output += remaining.toByte(); return output.toByteArray()
    }
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
