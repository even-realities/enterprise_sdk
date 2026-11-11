package com.evenrealities.devices

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.File

class G2TimeSyncTest {
    private val vectors = JSONObject(File("../protocol/contract.json").readText()).getJSONObject("vectors")

    @Test fun timeSyncTargetsOnlyTheCurrentRightEndpoint() {
        assertEquals("AA:BB:CC", requireG2TimeSyncRightEndpoint("aa:bb:cc", "AA:BB:CC"))
        try {
            requireG2TimeSyncRightEndpoint("AA:BB:DD", "AA:BB:CC")
            fail("left endpoint must not issue a duplicate mirrored TimeSync")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun timezoneAndWireEnvelopeMatchDartSemantics() {
        assertEquals(-14, G2TimeSyncCodec.timezoneUnits(-210))
        assertEquals(-1, G2TimeSyncCodec.timezoneUnits(-29))
        assertEquals(-128, G2TimeSyncCodec.timezoneUnits(-4000))
        val request = G2TimeSyncCodec.request(1_700_000_000, -210, 7, 42)
        assertArrayEquals(hex(vectors.getString("g2_time_sync_request_magic_42_sync_7_hex")), request)
        assertEquals(0xaa, request[0].toInt() and 0xff)
        assertEquals(0x21, request[1].toInt() and 0xff)
        assertEquals(128, request[6].toInt() and 0xff)
        assertEquals(0x20, request[7].toInt() and 0xff)
        assertEquals(request.size - 8, request[3].toInt() and 0xff)
        // uint32 timestamp, signed int32 timezone, and field-128 protobuf oneof.
        val payload = request.copyOfRange(8, request.size - 2)
        assertArrayEquals(byteArrayOf(8, 0x80.toByte(), 1, 16, 42, 0x82.toByte(), 8),
            payload.copyOfRange(0, 7))
        assertTrue(payload.copyOfRange(7, payload.size).toList().contains(0xff.toByte()))
    }

    @Test fun responseVectorUsesIndependentFirmwareSyncAndSameMagic() {
        val response = hex(vectors.getString("g2_time_sync_response_magic_42_sync_9_hex"))
        assertEquals(G2TimeSyncCodec.Response.SUCCESS, G2TimeSyncCodec.response(response, 42))
        assertEquals(G2TimeSyncCodec.Response.IGNORED, G2TimeSyncCodec.response(response, 43))
    }

    @Test fun onlyEnterpriseSidsEnterCommonWire() {
        for (sid in listOf(128, 4, 224)) {
            assertEquals(sid, G2TimeSyncCodec.singlePacket(sid, 1, byteArrayOf(1))[6].toInt() and 0xff)
        }
        for (sid in listOf(7, 0, 255)) {
            try {
                G2TimeSyncCodec.singlePacket(sid, 1, byteArrayOf(1))
                fail("SID $sid should be rejected")
            } catch (_: IllegalArgumentException) { }
        }
        assertEquals(147, G2TimeSyncCodec.singlePacket(128, 1, ByteArray(137)).size)
        try {
            G2TimeSyncCodec.singlePacket(128, 1, ByteArray(138))
            fail("A single packet must fit the minimum enterprise MTU")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun staleAttemptAndMalformedResponseCannotCompleteCommand() {
        val sent = mutableListOf<ByteArray>()
        val results = mutableListOf<Boolean>()
        val coordinator = G2TimeSyncCoordinator(
            send = { _, packet, _, _ -> sent += packet },
            onResult = { _, _, _, success -> results += success },
        )
        val operation = coordinator.begin("left", 1, 10, 20, 1_700_000_000, -210)
        val reply = sent.single().copyOf().also {
            it[1] = 0x12
            it[2] = 99 // Firmware response sync is independently allocated.
            it[7] = 0
        }
        coordinator.onReceive("left", 9, 20, reply)
        assertTrue(results.isEmpty())
        val badCrc = reply.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        coordinator.onReceive("left", 10, 20, badCrc)
        assertTrue(results.isEmpty())
        coordinator.onReceive("left", 10, 20, reply)
        assertEquals(listOf(true), results)
        coordinator.onTimeout(operation)
        assertEquals(1, results.size)
    }

    @Test fun cancelAndOldTimerDoNotCompleteReplacement() {
        val results = mutableListOf<Long>()
        val coordinator = G2TimeSyncCoordinator(
            send = { _, _, _, _ -> },
            onResult = { _, _, operation, _ -> results += operation },
        )
        val old = coordinator.begin("left", 1, 10, 20, 1_700_000_000, 0)
        coordinator.cancel()
        val current = coordinator.begin("left", 2, 11, 21, 1_700_000_001, 0)
        coordinator.onTimeout(old)
        assertTrue(results.isEmpty())
        coordinator.onTimeout(current)
        assertEquals(listOf(current), results)
    }

    /** Exercise the production allocator across three byte-range wraps, not
     * just a helper: firmware echoes one magic byte although protobuf is IS16.
     * Operation/timer identity must remain monotonic when wire magic wraps. */
    @Test fun coordinatorKeepsFirmwareMagicInByteRangeAcrossRepeatedWraps() {
        val maximum = JSONObject(File("../protocol/contract.json").readText())
            .getJSONObject("g2").getJSONObject("time_sync").getInt("request_magic_max")
        var sent = byteArrayOf()
        val results = mutableListOf<Pair<Long, Boolean>>()
        val coordinator = G2TimeSyncCoordinator(
            send = { _, packet, _, _ -> sent = packet.copyOf() },
            onResult = { _, _, operation, success -> results += operation to success },
        )
        var previousOperation = 0L
        repeat(768) { index ->
            val operation = coordinator.begin("right", 1, 10, 20, 1_700_000_000, 0)
            val expectedMagic = index % maximum + 1
            assertTrue("operation must not wrap with the wire magic", operation > previousOperation)
            // Reverse only the transport direction to independently check the
            // actual request's encoded magic using the strict response reader.
            val requestAsReply = sent.copyOf().also { it[1] = 0x12; it[2] = 99; it[7] = 0 }
            assertEquals("outgoing magic at operation=$operation", G2TimeSyncCodec.Response.SUCCESS,
                G2TimeSyncCodec.response(requestAsReply, expectedMagic))
            val reply = timeSyncAcknowledgement(expectedMagic)
            coordinator.onReceive("right", 9, 20, reply)
            coordinator.onReceive("right", 10, 19, reply)
            coordinator.onReceive("left", 10, 20, reply)
            coordinator.onReceive("right", 10, 20, timeSyncAcknowledgement(expectedMagic % maximum + 1))
            if (previousOperation > 0) coordinator.onTimeout(previousOperation)
            assertEquals("stale or uncorrelated work cannot finish operation=$operation", index, results.size)
            coordinator.onReceive("right", 10, 20, reply)
            assertEquals(operation to true, results.last())
            coordinator.onTimeout(operation)
            coordinator.onReceive("right", 10, 20, reply)
            assertEquals("a completion remains single-shot", index + 1, results.size)
            previousOperation = operation
        }
    }

    /** Real firmware ACK shape is an actual empty TimeSync submessage (proto3
     * result=SUCCESS), rather than an echoed request timestamp/timezone. */
    private fun timeSyncAcknowledgement(magic: Int): ByteArray {
        val encodedMagic = if (magic < 128) byteArrayOf(magic.toByte())
            else byteArrayOf(((magic and 127) or 128).toByte(), (magic ushr 7).toByte())
        val payload = byteArrayOf(8, 0x80.toByte(), 1, 16) + encodedMagic + byteArrayOf(0x82.toByte(), 8, 0)
        return G2TimeSyncCodec.singlePacket(128, 99, payload, false).also { it[1] = 0x12 }
    }

    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
