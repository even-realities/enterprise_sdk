package com.evenrealities.devices

import com.fzfstudio.ezw_ble.ble.BleG2OtaEndpointIdentity
import org.junit.Assert.*
import org.junit.Test

/** The fake owns an independent GATT completion and firmware reply to exercise both orderings. */
class G2OtaCoordinatorTest {
    private data class Write(val leg: BleG2OtaEndpointIdentity, val packets: List<ByteArray>,
                             val done: (String?) -> Unit) {
        val cid get() = packets.first()[8].toInt()
        val sync get() = packets.first()[2].toInt() and 255
    }

    private class Rig : G2OtaTransport {
        val legs = listOf("L", "R").mapIndexed { i, uuid -> BleG2OtaEndpointIdentity(uuid, uuid, 1, i + 1L) }
        val events = mutableListOf<G2OtaEvent>()
        val writes = mutableListOf<Write>()
        var available = true
        var finished = 0
        var recovered = 0
        var reconnected = false
        var read: ByteArray? = null
        lateinit var scope: G2OtaScope
        var now = 0L
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val coordinator = G2OtaCoordinator(this, { delay, work ->
            val task = now + delay to work
            tasks.add(task)
            val cancel: () -> Unit = { tasks.remove(task) }
            cancel
        }, { now }, { events.add(it) })

        fun tick(ms: Long) {
            val end = now + ms
            while (true) {
                val next = tasks.minByOrNull { it.first }?.takeIf { it.first <= end } ?: break
                tasks.remove(next)
                now = next.first
                next.second()
            }
            now = end
        }

        override fun begin(scope: G2OtaScope): String { this.scope = scope; return "lease" }
        override fun bind(scope: G2OtaScope, instance: String, leg: BleG2OtaEndpointIdentity, park: Boolean) = true
        override fun recover(scope: G2OtaScope, instance: String, leg: BleG2OtaEndpointIdentity): Boolean {
            recovered++
            return true
        }
        override fun maxWrite(leg: BleG2OtaEndpointIdentity) = if (available || leg.uuid == "L") 237 else 0
        override fun write(scope: G2OtaScope, instance: String, leg: BleG2OtaEndpointIdentity,
                           packets: List<ByteArray>, completion: (String?) -> Unit) {
            writes += Write(leg, packets, completion)
        }
        override fun finish(scope: G2OtaScope, instance: String, reason: String): Boolean { finished++; return true }
        override fun reconnect(scope: G2OtaScope) { reconnected = true }
        override fun readVersion(leg: BleG2OtaEndpointIdentity, packet: ByteArray): () -> Unit {
            read = packet
            return {}
        }
        fun start(size: Int = 4097) = coordinator.start("S200LABJ270010", 11, legs, firmware(size), "2.4.1.4")

        fun acknowledge(cid: Int, status: Int = 0) {
            val write = writes.last()
            assertEquals(cid, write.cid)
            write.done(null)
            reply(write, cid, status)
        }

        fun reply(write: Write, cid: Int, status: Int, tx: String = scope.tx) {
            val payload = byteArrayOf(cid.toByte(), status.toByte())
            val crc = G2EvenHubTransport.crc16(payload)
            val packet = byteArrayOf(0xaa.toByte(), 0x12, write.sync.toByte(), 4, 1, 1,
                (if (cid == 2) 0xc1 else 0xc0).toByte(), (if (cid == 4) 1 else 0).toByte()) +
                payload + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
            coordinator.onFrame(write.leg.uuid, write.leg.sessionGeneration, write.leg.attemptGeneration, tx,
                scope.operation, "lease", packet)
        }

        fun versionFrame(left: String, right: String): ByteArray {
            val request = read!!
            val setting = byteArrayOf(8, 1, 42, left.length.toByte()) + left.toByteArray() +
                byteArrayOf(50, right.length.toByte()) + right.toByteArray()
            val payload = byteArrayOf(8, 2, 16) + request.copyOfRange(11, request.size - 6) +
                byteArrayOf(34, setting.size.toByte()) + setting
            val crc = G2EvenHubTransport.crc16(payload)
            return byteArrayOf(0xaa.toByte(), 0x12, 99, (payload.size + 2).toByte(), 1, 1, 9, 0) +
                payload + byteArrayOf(crc.toByte(), (crc ushr 8).toByte())
        }

        fun leg() {
            acknowledge(0)
            acknowledge(1)
            acknowledge(2)
            acknowledge(2)
            val result = writes.last()
            assertEquals(3, result.cid)
            reply(result, 4, 9) // Device notify is allowed to precede the local final write callback.
            result.done(null)
        }
    }

    @Test fun twoLegRollbackAndFreshVersionEvidence() {
        val r = Rig()
        r.start()
        r.leg()
        assertTrue(r.coordinator.onDisconnected("L", 1, 1))
        r.tick(5000)
        assertEquals("R", r.writes.last().leg.uuid)
        r.leg()
        assertEquals(1, r.finished)
        assertEquals(listOf("transportCompleted", "verifying"), r.events.takeLast(2).map { it.phase })
        r.tick(5000)
        assertTrue(r.reconnected)
        r.coordinator.onReady(r.scope.sn, 12, r.legs)
        assertNull(r.read)
        val fresh = r.legs.map { it.copy(sessionGeneration = 15) }
        r.coordinator.onReady(r.scope.sn, 12, fresh)
        val frame = r.versionFrame("2.4.1.4", "2.4.1.4")
        r.coordinator.onVersion("R", 1, 2, frame)
        assertTrue(r.coordinator.busy)
        r.coordinator.onVersion("R", 15, 2, frame)
        assertEquals("succeeded", r.events.last().phase)
        assertEquals("2.4.1.4", r.events.last().leftVersion)
        assertEquals("2.4.1.4", r.events.last().rightVersion)
        assertFalse(r.coordinator.busy)
    }

    @Test fun rawWaitsForNativeWriteAndMatchingTransactionThenReplaysAfterRecovery() {
        val r = Rig()
        r.start()
        r.acknowledge(0)
        r.acknowledge(1)
        val raw = r.writes.last()
        r.reply(raw, 2, 0, "foreign")
        raw.done(null)
        assertSame(raw, r.writes.last())
        r.reply(raw, 2, 0)
        val second = r.writes.last()
        assertNotSame(raw, second)
        assertTrue(r.coordinator.onDisconnected("L", 1, 1))
        assertEquals(1, r.recovered)
        r.reply(second, 2, 0)
        second.done("stale")
        r.coordinator.onEndpointReady("L", 11, 1, 1)
        assertSame(second, r.writes.last())
        r.coordinator.onEndpointReady("L", 11, 9, 5)
        r.acknowledge(0)
        r.acknowledge(1)
        val replay = r.writes.last()
        assertEquals(raw.packets.size, replay.packets.size)
        assertEquals(5L, replay.leg.attemptGeneration)
        r.reply(replay, 2, 0)
        assertSame(replay, r.writes.last())
        replay.done(null)
        assertNotSame(replay, r.writes.last())
    }

    @Test fun staleCancelAndTimersCannotAdvanceANewOperation() {
        val r = Rig()
        val previous = r.start()
        r.coordinator.cancel(previous)
        val next = r.start()
        r.coordinator.cancel(previous)
        assertTrue(r.coordinator.busy)
        r.coordinator.cancel(next)
        r.tick(120_000)
        assertFalse(r.coordinator.busy)
        assertEquals(2, r.writes.size)
        assertEquals(2, r.finished)
        assertEquals("cancelled", r.events.last().phase)
    }

    @Test fun secondEndpointMissingOtaFailsBeforeFirstStartAndWriteFailureCannotAcceptEarlyAck() {
        val r = Rig()
        r.available = false
        assertThrows(IllegalStateException::class.java) { r.start() }
        assertTrue(r.writes.isEmpty())
        r.available = true
        r.start()
        val w = r.writes.last()
        r.reply(w, 0, 0)
        w.done("writeFailed")
        assertSame(w, r.writes.last())
        r.tick(1000)
        assertEquals("writeFailed", r.events.last().failure)
    }

    @Test fun freshLeftDisconnectRetiresPendingRightReadback() {
        val r = Rig()
        r.start()
        r.leg()
        r.tick(5000)
        r.leg()
        r.tick(5000)
        r.coordinator.onReady(r.scope.sn, 13, r.legs.map { it.copy(sessionGeneration = 20) })
        val first = r.read!!
        r.tick(2_000)
        assertFalse(first.contentEquals(r.read!!)) // Fresh magic per poll.
        assertFalse(r.coordinator.onDisconnected("L", 20, 1))
        r.coordinator.onVersion("R", 20, 2, byteArrayOf())
        assertFalse(r.coordinator.busy)
        assertEquals("versionUnverified", r.events.last().failure)
    }

    @Test fun unrelatedReplyPreventsSilentStartReplay() {
        val r = Rig()
        r.start()
        val start = r.writes.single()
        start.done(null)
        r.coordinator.onFrame("L", 1, 1, r.scope.tx, r.scope.operation, "lease", byteArrayOf(99))
        r.tick(5_000)
        assertFalse(r.coordinator.busy)
        assertEquals(1, r.writes.size)
    }

    @Test fun unrelatedFirmwareModelAndPlainVersionCannotFlash() {
        val r = Rig()
        for (label in listOf("v2.4.1.4", "r1_v2.4.1.4")) {
            val image = firmware(5)
            image.fill(0, 48, 64)
            label.toByteArray().copyInto(image, 48)
            assertThrows(IllegalArgumentException::class.java) {
                r.coordinator.start("S200LABJ270010", 11, r.legs, image, "2.4.1.4")
            }
            assertTrue(r.writes.isEmpty())
        }
    }

    @Test fun verificationRetriesStaleVersionsButNeverAcceptsAReplyAfterDeadline() {
        val r = Rig()
        r.start()
        r.leg()
        r.tick(5000)
        r.leg()
        r.tick(5000)
        r.coordinator.onReady(r.scope.sn, 12, r.legs.map { it.copy(sessionGeneration = 2) })
        r.coordinator.onVersion("R", 2, 2, r.versionFrame("2.4.1.4", "2.4.1.3"))
        assertTrue(r.coordinator.busy)
        val staleMagic = r.versionFrame("2.4.1.4", "2.4.1.4")
        r.tick(2_000)
        r.coordinator.onVersion("R", 2, 2, staleMagic)
        assertTrue(r.coordinator.busy)
        r.now += 58_000 // Timer delivery delayed by OS suspension.
        r.coordinator.onVersion("R", 2, 2, r.versionFrame("2.4.1.4", "2.4.1.4"))
        assertEquals("versionMismatch", r.events.last().failure)
        assertEquals("2.4.1.3", r.events.last().rightVersion)
    }

    @Test fun rawRetriesDoNotAdvanceOffsetAndFinalResultNeedsNotify() {
        val r = Rig()
        r.start(10)
        r.acknowledge(0)
        r.acknowledge(1)
        val w = r.writes.last()
        r.acknowledge(2, 5)
        r.tick(4_999)
        assertSame(w, r.writes.last())
        r.tick(1)
        assertNotEquals(w.sync, r.writes.last().sync)
        assertArrayEquals(w.packets.last().copyOfRange(8, w.packets.last().size),
            r.writes.last().packets.last().let { it.copyOfRange(8, it.size) })
        r.acknowledge(2)
        r.acknowledge(3, 8)
        r.tick(90_000)
        assertEquals("notifyTimeout", r.events.last().failure)
        assertTrue(r.writes.all { it.leg.uuid == "L" })
    }

    companion object {
        /** An all-zero body has a zero EVEN CRC, including the two-window boundary case. */
        private fun firmware(size: Int) = ByteArray(224 + size).apply {
            fun field(at: Int, value: Int) { repeat(4) { this[at + it] = (value ushr (8 * it)).toByte() } }
            "EVENOTA".toByteArray().copyInto(this)
            field(8, 1)
            "s200_v2.4.1.4".toByteArray().copyInto(this, 48)
            field(64, 1); field(68, 96); field(72, size + 128)
            "evenota".toByteArray().copyInto(this, 80)
            field(104, size)
            "EVEN".toByteArray().copyInto(this, 116)
        }
    }
}
