package com.evenrealities.devices

import org.junit.Assert.*
import org.junit.Test

/** Production-Dart parity: heartbeat Cnt is not an echo token, and ATT tails can contain padding. */
class G2EvenHubHeartbeatCompatibilityTest {
    @Test fun heartbeatWireBodyIsEmptyIndependentOfLocalMetric() {
        for (count in listOf(1L, 7L, 0xffffffffL)) {
            val wire = G2EvenHubCodec.heartbeat(count, 42, 7)
            val payload = wire.copyOfRange(8, wire.size - 2)
            assertArrayEquals(byteArrayOf(8, 12, 16, 42, 114, 0), payload)
        }
    }

    @Test fun omittedAndNonEchoedUnsignedCountersAreBothValidWithinActualSuccessBranch() {
        val request = G2EvenHubCodec.prepare("heartbeat", emptyMap(), 1, count = 7).single()
        for (count in listOf<Long?>(null, 0, 1, 99, 0xffffffffL)) {
            val body = (count?.let { scalar(1, it) } ?: byteArrayOf()) + scalar(2, 12)
            val frame = G2EvenHubTransport.Message(72, 0, acknowledgement(body))
            assertEquals("ACK Cnt=$count is not an echo contract", G2EvenHubCodec.Reply.SUCCESS,
                G2EvenHubCodec.response(frame, request, 42, 7).status)
        }
    }

    @Test fun enterpriseRequestCommandAckStillRequiresTheHeartbeatResponseBody() {
        val request = G2EvenHubCodec.prepare("heartbeat", emptyMap(), 1, count = 7).single()
        // Equivalent fixture reconstructed from the iOS diagnostic field metadata,
        // not a raw payload capture: Cmd=12, Magic=2, branch=15, result=12, no Cnt.
        val observedShape = byteArrayOf(0x08, 0x0c, 0x10, 0x02, 0x7a, 0x02, 0x10, 0x0c)
        assertArrayEquals(observedShape, acknowledgement(scalar(2, 12), magic = 2, command = 12))
        for (command in listOf(12L, 13L)) for (count in listOf<Long?>(null, 99, 0xffffffffL)) {
            val body = (count?.let { scalar(1, it) } ?: byteArrayOf()) + scalar(2, 12)
            val packet = incoming(G2EvenHubTransport.packets(acknowledgement(body, magic = 2, command = command), 7).single())
            val frame = G2EvenHubTransport.Assembler().accept(packet, 0)!!
            assertEquals("Cmd=$command/Cnt=$count retains the same response contract", G2EvenHubCodec.Reply.SUCCESS,
                G2EvenHubCodec.response(frame, request, 2, 7).status)
        }
    }

    @Test fun enterpriseHeartbeatVariantDoesNotAcceptRequestEchoesOrUnownedOrMalformedReplies() {
        val request = G2EvenHubCodec.prepare("heartbeat", emptyMap(), 1, count = 7).single()
        val body = scalar(2, 12)
        val root = scalar(1, 12) + scalar(2, 42)
        val invalid = listOf(
            root + bytes(14, byteArrayOf()), // An actual request echo never renews the page lease.
            root + bytes(14, byteArrayOf()) + bytes(15, body),
            root + bytes(12, body),
            root + bytes(15, body) + bytes(27, byteArrayOf()),
            scalar(1, 12) + bytes(15, body),
            scalar(1, 12) + scalar(2, 41) + bytes(15, body),
            scalar(1, 11) + scalar(2, 42) + bytes(15, body),
            root + bytes(15, scalar(2, 11)),
            root + bytes(15, byteArrayOf()),
            root + bytes(15, bytes(2, byteArrayOf())),
            root + bytes(15, body + body),
            root + bytes(15, body) + scalar(1, 12),
        )
        for (payload in invalid) assertNotEquals(G2EvenHubCodec.Reply.SUCCESS,
            G2EvenHubCodec.response(G2EvenHubTransport.Message(7, 0, payload), request, 42, 7).status)
    }

    @Test fun requestCommandResponseDialectIsExclusiveToHeartbeat() {
        for (command in G2EvenHubCodec.Command.entries.filter { it != G2EvenHubCodec.Command.HEARTBEAT && it.response >= 0 }) {
            val request = G2EvenHubCodec.Request(command, byteArrayOf(), expected = mapOf(1 to 0L))
            val field = if (command == G2EvenHubCodec.Command.IMAGE) 8 else 1
            val value = if (command in listOf(G2EvenHubCodec.Command.AUDIO, G2EvenHubCodec.Command.IMU)) 0 else command.success
            val payload = scalar(1, command.request.toLong()) + scalar(2, 42) + bytes(command.branch, scalar(field, value))
            assertEquals("${command.logicalName} must retain its own response command", G2EvenHubCodec.Reply.IGNORED,
                G2EvenHubCodec.response(G2EvenHubTransport.Message(7, 0, payload), request, 42, 7).status)
        }
    }

    @Test fun relaxedCounterStillRequiresMagicCommandOneofResultAndValidWireFields() {
        val request = G2EvenHubCodec.prepare("heartbeat", emptyMap(), 1, count = 7).single()
        val invalidBodies = listOf(byteArrayOf(), scalar(2, 0), scalar(2, 11),
            bytes(1, byteArrayOf()), scalar(1, 0) + scalar(1, 0) + scalar(2, 12),
            scalar(1, 0x100000000L) + scalar(2, 12), scalar(2, 12) + scalar(3, 0))
        for (command in listOf(12L, 13L)) for (body in invalidBodies) assertNotEquals(G2EvenHubCodec.Reply.SUCCESS,
            G2EvenHubCodec.response(G2EvenHubTransport.Message(7, 0, acknowledgement(body, command = command)), request, 42, 7).status)
        val validBody = scalar(2, 12)
        for (payload in listOf(scalar(1, 13) + bytes(15, validBody),
            scalar(1, 13) + scalar(2, 41) + bytes(15, validBody),
            scalar(1, 11) + scalar(2, 42) + bytes(15, validBody),
            scalar(1, 13) + scalar(2, 42) + bytes(14, validBody),
            acknowledgement(validBody) + bytes(12, byteArrayOf()))) {
            assertNotEquals(G2EvenHubCodec.Reply.SUCCESS,
                G2EvenHubCodec.response(G2EvenHubTransport.Message(7, 0, payload), request, 42, 7).status)
        }
    }

    @Test fun paddedSingleFrameUsesDeclaredCrcBoundaryInsteadOfTailBytes() {
        val request = G2EvenHubCodec.prepare("heartbeat", emptyMap(), 1, count = 7).single()
        val packet = incoming(G2EvenHubTransport.packets(acknowledgement(scalar(2, 12)), 7).single())
        for (padding in listOf(byteArrayOf(0), ByteArray(30), byteArrayOf(0xaa.toByte(), 0x12, 0xff.toByte()))) {
            val decoded = G2EvenHubTransport.Assembler().accept(packet + padding, 0)
            assertNotNull(decoded)
            assertArrayEquals(acknowledgement(scalar(2, 12)), decoded!!.payload)
            assertEquals(G2EvenHubCodec.Reply.SUCCESS, G2EvenHubCodec.response(decoded, request, 42, 7).status)
        }
    }

    @Test fun paddedMultipartReassemblyKeepsWholePayloadCrcAndPacketOrder() {
        val payload = ByteArray(2000) { (it * 13).toByte() }
        val packets = G2EvenHubTransport.packets(payload, 7).mapIndexed { index, packet ->
            incoming(packet) + ByteArray(index % 11 + 1) { 0x55 }
        }
        val assembler = G2EvenHubTransport.Assembler()
        var decoded: G2EvenHubTransport.Message? = null
        packets.forEachIndexed { index, packet -> decoded = assembler.accept(packet, index.toLong()) }
        assertNotNull(decoded)
        assertArrayEquals(payload, decoded!!.payload)
        val reordered = G2EvenHubTransport.Assembler()
        assertNull(reordered.accept(packets[0], 0))
        assertNull(reordered.accept(packets[2], 1))
        for (packet in packets.drop(1)) assertNull(reordered.accept(packet, 2))
    }

    @Test fun paddingDoesNotHideTruncationOrBadDeclaredPayloadCrc() {
        val raw = incoming(G2EvenHubTransport.packets(acknowledgement(scalar(2, 12)), 7).single())
        assertNull(G2EvenHubTransport.Assembler().accept(raw.copyOf(raw.size - 1), 0))
        val missingDeclaredByte = raw.copyOf().also { it[3] = (it[3].toInt() + 1).toByte() }
        assertNull(G2EvenHubTransport.Assembler().accept(missingDeclaredByte, 0))
        val badCrc = raw.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() } + ByteArray(20)
        assertNull(G2EvenHubTransport.Assembler().accept(badCrc, 0))
        val tooShortForCrc = raw.copyOf().also { it[3] = 1 } + ByteArray(20)
        assertNull(G2EvenHubTransport.Assembler().accept(tooShortForCrc, 0))
    }

    @Test fun paddedTransportErrorRemainsRejectedOnlyForTheOwnedRequestSync() {
        val request = G2EvenHubCodec.prepare("heartbeat", emptyMap(), 1, count = 7).single()
        val error = byteArrayOf(0xaa.toByte(), 0x12, 7, 0, 1, 1, 224.toByte(), 2) + ByteArray(12)
        val frame = G2EvenHubTransport.Assembler().accept(error, 0)
        assertNotNull(frame)
        assertEquals(G2EvenHubCodec.Reply.REJECTED, G2EvenHubCodec.response(frame!!, request, 42, 7).status)
        assertEquals(G2EvenHubCodec.Reply.IGNORED, G2EvenHubCodec.response(frame, request, 42, 8).status)
    }

    @Test fun confirmedLocalHeartbeatMetricAdvancesWhenFirmwareOmitsOrChangesCounter() {
        data class Task(val delay: Long, val block: () -> Unit, var cancelled: Boolean = false)
        val tasks = mutableListOf<Task>()
        val events = mutableListOf<G2EvenHubEvent>()
        val owner = G2EvenHubOwner("S200LABJ270010", "RIGHT", 1, 10, 20)
        var now = 0L
        val coordinator = G2EvenHubCoordinator({ _, _, _ -> }, { delay, block ->
            val task = Task(delay, block); tasks += task; { task.cancelled = true }
        }, events::add, { now }, { 123 })
        fun receive(payload: ByteArray) {
            G2EvenHubTransport.packets(payload, 7).forEach {
                coordinator.onReceive(owner.endpoint, owner.session, owner.attempt, incoming(it) + ByteArray(5))
            }
        }
        fun heartbeatTick() {
            val task = tasks.single { !it.cancelled && it.delay == 5000L }
            task.cancelled = true; now += 5000; task.block()
        }
        coordinator.open(owner, "Hello")
        receive(scalar(1, 1) + scalar(2, 1) + bytes(4, byteArrayOf()))
        heartbeatTick(); receive(acknowledgement(scalar(2, 12), magic = 2, command = 12))
        assertEquals("heartbeat", events.last().phase); assertEquals(1L, events.last().heartbeatCount)
        heartbeatTick(); receive(acknowledgement(scalar(1, 999) + scalar(2, 12), magic = 3))
        assertEquals("heartbeat", events.last().phase); assertEquals(2L, events.last().heartbeatCount)
    }

    private fun acknowledgement(body: ByteArray, magic: Long = 42, command: Long = 13) = scalar(1, command) + scalar(2, magic) + bytes(15, body)
    private fun incoming(packet: ByteArray) = packet.copyOf().also { it[1] = 0x12; it[7] = 0 }
    private fun scalar(field: Int, value: Long) = varint(field.toLong() shl 3) + varint(value)
    private fun bytes(field: Int, value: ByteArray) = varint((field.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
    private fun varint(value: Long): ByteArray {
        val output = mutableListOf<Byte>(); var remaining = value
        while (remaining and -128L != 0L) { output += ((remaining and 127) or 128).toByte(); remaining = remaining ushr 7 }
        output += remaining.toByte(); return output.toByteArray()
    }
}
