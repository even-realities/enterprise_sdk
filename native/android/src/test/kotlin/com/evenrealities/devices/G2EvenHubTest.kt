package com.evenrealities.devices

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/** Locks the native demo to Dart-generated protocol vectors and hostile lease timelines. */
class G2EvenHubTest {
    private val vectors = JSONObject(File("../protocol/contract.json").readText()).getJSONObject("vectors")
    private val owner = G2EvenHubOwner("S200LABJ270010", "RIGHT", 1, 10, 20)

    @Test fun canonicalProtocolVectorsMatchDart() {
        assertArrayEquals(vector("create"), G2EvenHubCodec.create("Hello EvenHub", 123, 42, 7))
        assertArrayEquals(vector("heartbeat"), G2EvenHubCodec.heartbeat(7, 42, 7))
        assertArrayEquals(vector("close"), G2EvenHubCodec.close(42, 7))
        for (command in listOf(G2EvenHubCodec.Command.CREATE, G2EvenHubCodec.Command.HEARTBEAT,
            G2EvenHubCodec.Command.CLOSE)) {
            val name = when (command) {
                G2EvenHubCodec.Command.CREATE -> "create"
                G2EvenHubCodec.Command.HEARTBEAT -> "heartbeat"
                G2EvenHubCodec.Command.CLOSE -> "close"
                else -> error("Not a short demo vector")
            }
            assertEquals(G2EvenHubCodec.Reply.SUCCESS,
                G2EvenHubCodec.reply(vector(name + "_ack"), command, 42, 7, 7))
        }
        assertEquals("click", G2EvenHubCodec.input(vector("click"), 123)?.name)
    }

    @Test fun textLimitUsesUtf8AndFitsMinimumMtu() {
        assertTrue(G2EvenHubCodec.create("x".repeat(64), 0xffffffffL, 255, 255).size <= 147)
        assertTrue(G2EvenHubCodec.create("中".repeat(21), 1, 1, 1).size <= 147)
        for (text in listOf("", "x".repeat(65), "中".repeat(22))) {
            assertFailure("invalidText") { G2EvenHubCodec.validateText(text) }
        }
    }

    @Test fun responsesRequireActualBranchMagicCrcButHeartbeatCountIsNotAnEchoContract() {
        val valid = vector("create_ack")
        val badCrc = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertEquals(G2EvenHubCodec.Reply.IGNORED,
            G2EvenHubCodec.reply(badCrc, G2EvenHubCodec.Command.CREATE, 42, 7, 0))
        assertEquals(G2EvenHubCodec.Reply.IGNORED,
            G2EvenHubCodec.reply(valid, G2EvenHubCodec.Command.CREATE, 41, 7, 0))
        for (payload in listOf(
            scalar(1, 1) + scalar(2, 42), // Missing response is not proto3 success.
            scalar(1, 1) + scalar(2, 42) + bytes(12, byteArrayOf()),
            scalar(1, 1) + scalar(2, 42) + bytes(4, byteArrayOf()) + bytes(12, byteArrayOf()),
            scalar(1, 1) + scalar(2, 42) + bytes(4, scalar(1, 1)),
        )) assertEquals(G2EvenHubCodec.Reply.REJECTED,
            G2EvenHubCodec.reply(response(payload), G2EvenHubCodec.Command.CREATE, 42, 7, 0))
        assertEquals(G2EvenHubCodec.Reply.SUCCESS,
            G2EvenHubCodec.reply(vector("heartbeat_ack"), G2EvenHubCodec.Command.HEARTBEAT, 42, 7, 8))
        val duplicate = scalar(1, 1) + scalar(2, 42) + bytes(4, byteArrayOf()) + scalar(2, 42)
        assertEquals(G2EvenHubCodec.Reply.IGNORED,
            G2EvenHubCodec.reply(response(duplicate), G2EvenHubCodec.Command.CREATE, 42, 7, 0))
    }

    @Test fun unknownOrUnownedEventsCannotMasqueradeAsClicksOrExits() {
        assertEquals("click", G2EvenHubCodec.input(textEvent(scalar(1, 1)), 1)?.name)
        assertEquals("doubleClick", G2EvenHubCodec.input(textEvent(scalar(1, 1) + scalar(3, 3)), 1)?.name)
        assertNull(G2EvenHubCodec.input(textEvent(scalar(1, 0)), 1))
        assertNull(G2EvenHubCodec.input(textEvent(scalar(1, 1) + scalar(3, 99)), 1))
        assertNull(G2EvenHubCodec.input(textEvent(scalar(1, 1) + bytes(2, "other".toByteArray())), 1))
        assertNull(G2EvenHubCodec.input(textEvent(scalar(1, 1) + scalar(3, 0) + scalar(3, 0)), 1))
        assertNull(G2EvenHubCodec.input(systemEvent(scalar(1, 7)), 1))
        assertNull(G2EvenHubCodec.input(systemEvent(scalar(1, 7) + scalar(5, 11417) + scalar(6, 2)), 1))
        assertTrue(G2EvenHubCodec.input(systemEvent(scalar(1, 7) + scalar(5, 11417) + scalar(6, 1)), 1)!!.terminal)
    }

    @Test fun createSuccessStartsSingleFlightNativeHeartbeat() {
        val host = Host()
        host.coordinator.open(owner, "Hello")
        assertEquals(listOf("opening"), host.events.map { it.phase })
        assertEquals(1, host.clock.active().size) // Only CREATE timeout; no page lease yet.
        host.receive(ack(G2EvenHubCodec.Command.CREATE, 1))
        assertEquals("active", host.events.last().phase)
        host.clock.advance(5_000)
        assertEquals(2, host.sent.size)
        host.clock.advance(1_000)
        assertEquals(2, host.sent.size)
        host.receive(ack(G2EvenHubCodec.Command.HEARTBEAT, 2, count = 1))
        assertEquals("heartbeat", host.events.last().phase)
        assertEquals(1L, host.events.last().heartbeatCount)
        host.clock.advance(5_000)
        assertEquals(3, host.sent.size)
    }

    @Test fun closeDuringCreateWaitsForItsAckAndNeverStartsHeartbeat() {
        val host = Host()
        host.coordinator.open(owner, "Hello")
        host.coordinator.close()
        assertEquals(1, host.sent.size)
        assertEquals("closing", host.events.last().phase)
        assertFailure("busy") { host.coordinator.close() }
        host.receive(ack(G2EvenHubCodec.Command.CREATE, 1))
        assertEquals(2, host.sent.size)
        assertEquals(9, host.sent.last()[9].toInt())
        host.receive(ack(G2EvenHubCodec.Command.CLOSE, 2))
        assertEquals("closed", host.events.last().phase)
        host.clock.advance(20_000)
        assertEquals(2, host.sent.size)
    }

    @Test fun closeDuringHeartbeatWaitsAndDisarmsFutureTicks() {
        val host = Host()
        host.coordinator.open(owner, "Hello")
        host.receive(ack(G2EvenHubCodec.Command.CREATE, 1))
        host.clock.advance(5_000)
        host.coordinator.close()
        host.receive(ack(G2EvenHubCodec.Command.HEARTBEAT, 2, count = 1))
        assertEquals(3, host.sent.size)
        host.receive(ack(G2EvenHubCodec.Command.CLOSE, 3))
        assertEquals("closed", host.events.last().phase)
        host.clock.advance(20_000)
        assertEquals(3, host.sent.size)
    }

    @Test fun busyOrInvalidSecondOpenCannotInvalidateCurrentCreate() {
        val host = Host()
        host.coordinator.open(owner, "Hello")
        assertFailure("busy") { host.coordinator.open(owner, "New page") }
        assertFailure("invalidText") { host.coordinator.open(owner, "") }
        host.receive(ack(G2EvenHubCodec.Command.CREATE, 1))
        assertEquals("active", host.events.last().phase)
        assertEquals(1L, host.events.last().operation)
    }

    @Test fun timeoutDisconnectAndOldCallbacksCannotMutateReplacementPage() {
        val host = Host()
        host.coordinator.open(owner, "Hello")
        val oldTimer = host.clock.active().single()
        host.clock.advance(3_000)
        assertEquals("timedOut", host.events.last().failure)
        host.receive(ack(G2EvenHubCodec.Command.CREATE, 1))
        assertEquals("failed", host.events.last().phase)
        val replacement = owner.copy(generation = 2, session = 11, attempt = 21)
        host.coordinator.open(replacement, "Replacement")
        oldTimer.action() // Even a timer delivered after cancellation loses its owner.
        host.coordinator.onDisconnected(owner.endpoint, owner.session, owner.attempt)
        host.coordinator.onReceive(owner.endpoint, owner.session, owner.attempt, ack(G2EvenHubCodec.Command.CREATE, 2))
        assertEquals("opening", host.events.last().phase)
        host.coordinator.onReceive(replacement.endpoint, replacement.session, replacement.attempt,
            ack(G2EvenHubCodec.Command.CREATE, 2))
        assertEquals("active", host.events.last().phase)
        host.coordinator.onDisconnected(replacement.endpoint, replacement.session, replacement.attempt)
        assertEquals("disconnected", host.events.last().failure)
    }

    @Test fun scopedExitStopsHeartbeatAndUnscopedExitDoesNot() {
        val host = Host()
        host.coordinator.open(owner, "Hello")
        host.receive(ack(G2EvenHubCodec.Command.CREATE, 1))
        host.receive(systemEvent(scalar(1, 7)))
        assertEquals("active", host.events.last().phase)
        host.receive(systemEvent(scalar(1, 7) + scalar(5, 11417) + scalar(6, 1)))
        assertEquals("closed", host.events.last().phase)
        host.clock.advance(20_000)
        assertEquals(1, host.sent.size)
    }

    @Test fun sendFailureAndRejectedHeartbeatReleaseThePage() {
        val failed = Host(failSend = true)
        failed.coordinator.open(owner, "Hello")
        assertEquals("writeFailed", failed.events.last().failure)
        val host = Host()
        host.coordinator.open(owner, "Hello")
        host.receive(ack(G2EvenHubCodec.Command.CREATE, 1))
        host.clock.advance(5_000)
        host.receive(ack(G2EvenHubCodec.Command.HEARTBEAT, 2, count = 1, result = 0))
        assertEquals("deviceRejected", host.events.last().failure)
        host.clock.advance(20_000)
        assertEquals(2, host.sent.size)
        host.coordinator.open(owner, "Retry")
        assertEquals("opening", host.events.last().phase)
    }

    @Test fun reentrantStopDuringOpeningCannotWriteOrLeaveALease() {
        val clock = Clock()
        val sent = mutableListOf<ByteArray>()
        lateinit var coordinator: G2EvenHubCoordinator
        coordinator = G2EvenHubCoordinator({ _, packet, _ -> sent += packet }, clock::schedule, onEvent = { event ->
            if (event.phase == "opening") coordinator.cancel("reset")
        })
        coordinator.open(owner, "Hello")
        assertTrue(sent.isEmpty())
        assertTrue(clock.active().isEmpty())
    }

    @Test fun audioRequiresEnableAckAndExactOwnerAndRevokesBeforeDisableAck() {
        val host = Host()
        var decoded = 0
        var closed = 0
        host.coordinator.audioDecoderFactory = {
            object : G2EvenHubCoordinator.AudioDecoder {
                override fun decode(bytes: ByteArray): ByteArray {
                    assertEquals(200, bytes.size); decoded++; return byteArrayOf(1, 2)
                }
                override fun close() { closed++ }
            }
        }
        fun audio(endpoint: String = owner.endpoint, session: Long = owner.session,
                  attempt: Long = owner.attempt, size: Int = 205) =
            host.coordinator.onAudioReceive(endpoint, session, attempt, ByteArray(size))
        host.coordinator.open(owner, "Hello")
        audio()
        host.receive(ack(G2EvenHubCodec.Command.CREATE, 1))
        host.coordinator.execute(owner, "audioControl", mapOf("enabled" to true))
        audio()
        assertEquals(0, decoded)
        host.receive(ack(G2EvenHubCodec.Command.AUDIO, 2, result = 1))
        audio(endpoint = "LEFT"); audio(session = 999); audio(attempt = 999); audio(size = 203)
        assertEquals(0, decoded)
        audio()
        assertEquals(1, decoded)
        assertEquals("audioEvent", host.events.last().event)
        assertEquals(2, (host.events.last().details["audioPcm"] as ByteArray).size)
        host.coordinator.execute(owner, "audioControl", mapOf("enabled" to false))
        audio()
        assertEquals(1, decoded)
        assertEquals(1, closed)
        host.receive(ack(G2EvenHubCodec.Command.AUDIO, 3, result = 0))
        host.coordinator.execute(owner, "audioControl", mapOf("enabled" to true))
        host.receive(ack(G2EvenHubCodec.Command.AUDIO, 4, result = 1))
        host.coordinator.close()
        audio()
        assertEquals(1, decoded)
        assertEquals(2, closed)
    }

    @Test fun eitherFrozenPairEndpointMayOwnStreamWithoutSharingCodecHistory() {
        val host = Host()
        val left = owner.copy(endpoint = "LEFT", session = 11, attempt = 21)
        var decoders = 0
        host.coordinator.audioDecoderFactory = {
            decoders++
            object : G2EvenHubCoordinator.AudioDecoder {
                override fun decode(bytes: ByteArray) = ByteArray(1600)
                override fun close() {}
            }
        }
        host.coordinator.onReady(listOf(left, owner))
        host.coordinator.open(owner, "Hello", listOf(left, owner))
        host.receive(ack(G2EvenHubCodec.Command.CREATE, 1))
        host.coordinator.execute(owner, "audioControl", mapOf("enabled" to true))
        host.receive(ack(G2EvenHubCodec.Command.AUDIO, 2, result = 1))
        assertEquals(2, decoders)
        host.coordinator.onAudioReceive(left.endpoint, left.session, left.attempt, ByteArray(204))
        assertEquals("audioEvent", host.events.last().event)
        assertEquals(left, host.events.last().owner)
        val count = host.events.size
        host.coordinator.onAudioReceive(left.endpoint, left.session, left.attempt + 1, ByteArray(204))
        assertEquals(count, host.events.size)
    }

    @Test fun rawCompleteLc3GroupsPreserveLengthAndDoNotInventMetadata() {
        val host = Host()
        val lengths = mutableListOf<Int>()
        host.coordinator.audioDecoderFactory = {
            object : G2EvenHubCoordinator.AudioDecoder {
                override fun decode(bytes: ByteArray): ByteArray {
                    lengths += bytes.size
                    return ByteArray(bytes.size / 40 * 320)
                }
                override fun close() {}
            }
        }
        host.coordinator.open(owner, "Hello")
        host.receive(ack(G2EvenHubCodec.Command.CREATE, 1))
        host.coordinator.execute(owner, "audioControl", mapOf("enabled" to true))
        host.receive(ack(G2EvenHubCodec.Command.AUDIO, 2, result = 1))
        for (size in listOf(40, 80, 480)) {
            host.coordinator.onAudioReceive(owner.endpoint, owner.session, owner.attempt, ByteArray(size))
            val event = host.events.last()
            assertEquals(size / 40 * 320, (event.details["audioPcm"] as ByteArray).size)
            assertEquals("unknown", event.details["speakerRole"])
            assertFalse(event.details.containsKey("direction"))
        }
        val count = host.events.size
        for (size in listOf(0, 39, 41, 479, 512, 520)) {
            host.coordinator.onAudioReceive(owner.endpoint, owner.session, owner.attempt, ByteArray(size))
        }
        assertEquals(listOf(40, 80, 480), lengths)
        assertEquals(count, host.events.size)
    }

    @Test fun deferredAudioCannotSurviveDisableReenableOrReplacementPage() {
        val host = Host()
        host.coordinator.audioDecoderFactory = {
            object : G2EvenHubCoordinator.AudioDecoder {
                override fun decode(bytes: ByteArray) = ByteArray(320)
                override fun close() {}
            }
        }
        fun audio(): G2EvenHubEvent {
            host.coordinator.onAudioReceive(owner.endpoint, owner.session, owner.attempt, ByteArray(40))
            return host.events.last()
        }
        host.coordinator.open(owner, "Hello")
        host.receive(ack(G2EvenHubCodec.Command.CREATE, 1))
        host.coordinator.execute(owner, "audioControl", mapOf("enabled" to true))
        host.receive(ack(G2EvenHubCodec.Command.AUDIO, 2, result = 1))
        val ingress = host.coordinator.captureAudioAdmission(owner.endpoint, owner.session, owner.attempt)!!
        assertNull(host.coordinator.captureAudioAdmission(owner.endpoint, owner.session, owner.attempt + 1))
        val deferred = audio()
        assertTrue(host.coordinator.isCurrentAudioDelivery(deferred))
        host.coordinator.execute(owner, "audioControl", mapOf("enabled" to false))
        assertFalse(host.coordinator.isCurrentAudioDelivery(deferred))
        host.receive(ack(G2EvenHubCodec.Command.AUDIO, 3, result = 0))
        host.coordinator.execute(owner, "audioControl", mapOf("enabled" to true))
        host.receive(ack(G2EvenHubCodec.Command.AUDIO, 4, result = 1))
        assertFalse(host.coordinator.isCurrentAudioDelivery(deferred))
        val count = host.events.size
        host.coordinator.onAudioReceiveOwned(ingress, ByteArray(40))
        assertEquals(count, host.events.size) // Queued old bytes cannot acquire the new mic lease.
        val next = audio()
        assertTrue(host.coordinator.isCurrentAudioDelivery(next))
        host.coordinator.close()
        assertFalse(host.coordinator.isCurrentAudioDelivery(next))
        host.receive(ack(G2EvenHubCodec.Command.CLOSE, 5))
        host.coordinator.open(owner, "Replacement")
        host.receive(ack(G2EvenHubCodec.Command.CREATE, 6))
        assertFalse(host.coordinator.isCurrentAudioDelivery(next))
    }

    @Test fun unavailableAudioDecoderSettlesEnableAndRetiresPage() {
        val host = Host()
        host.coordinator.audioDecoderFactory = { throw UnsatisfiedLinkError("test") }
        host.coordinator.open(owner, "Hello")
        host.receive(ack(G2EvenHubCodec.Command.CREATE, 1))
        var failure: String? = null
        host.coordinator.execute(owner, "audioControl", mapOf("enabled" to true), completion = {
            failure = (it.exceptionOrNull() as? G2EvenHubException)?.code
        })
        host.receive(ack(G2EvenHubCodec.Command.AUDIO, 2, result = 1))
        assertEquals("audioDecoderUnavailable", failure)
        assertFalse(host.coordinator.hasPage())
    }

    private inner class Host(failSend: Boolean = false) {
        val clock = Clock()
        val events = mutableListOf<G2EvenHubEvent>()
        val sent = mutableListOf<ByteArray>()
        val coordinator = G2EvenHubCoordinator({ _, packet, _ ->
            if (failSend) error("transport failed")
            sent += packet
        }, clock::schedule, events::add, clock::time, sessionIds = { ++pageId })
        private var pageId = 0L
        fun receive(packet: ByteArray) = coordinator.onReceive(owner.endpoint, owner.session, owner.attempt, packet)
    }
    private class Clock {
        data class Task(val due: Long, val action: () -> Unit, var cancelled: Boolean = false)
        private var now = 0L
        fun time() = now
        private val tasks = mutableListOf<Task>()
        fun active() = tasks.filter { !it.cancelled }
        fun schedule(delay: Long, action: () -> Unit): () -> Unit {
            val task = Task(now + delay, action)
            tasks += task
            return { task.cancelled = true }
        }
        fun advance(delay: Long) {
            val target = now + delay
            while (true) {
                val task = active().filter { it.due <= target }.minByOrNull { it.due } ?: break
                now = task.due; task.cancelled = true; task.action()
            }
            now = target
        }
    }
    private fun assertFailure(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: G2EvenHubException) { assertEquals(code, error.code) }
    }
    private fun vector(name: String) = vectors.getString("g2_evenhub_${name}_hex")
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ack(command: G2EvenHubCodec.Command, magic: Long, count: Long = 0, result: Long = command.success): ByteArray {
        val body = if (command == G2EvenHubCodec.Command.HEARTBEAT) scalar(1, count) + scalar(2, result)
            else if (result == 0L) byteArrayOf() else scalar(1, result)
        return response(scalar(1, command.response.toLong()) + scalar(2, magic) + bytes(command.branch, body))
    }
    private fun textEvent(body: ByteArray) = response(scalar(1, 2) + bytes(13, bytes(2, body)), true)
    private fun systemEvent(body: ByteArray) = response(scalar(1, 2) + bytes(13, bytes(3, body)), true)
    private fun response(payload: ByteArray, notify: Boolean = false) =
        G2TimeSyncCodec.singlePacket(224, 7, payload).also { it[1] = 0x12; it[7] = if (notify) 1 else 0 }
    private fun scalar(field: Int, value: Long) = varint(field.toLong() shl 3) + varint(value)
    private fun bytes(field: Int, value: ByteArray) = varint((field.toLong() shl 3) or 2) + varint(value.size.toLong()) + value
    private fun varint(value: Long): ByteArray {
        val output = ByteArrayOutputStream()
        var remaining = value
        while (remaining and -128L != 0L) { output.write(((remaining and 127) or 128).toInt()); remaining = remaining ushr 7 }
        output.write(remaining.toInt())
        return output.toByteArray()
    }
}
