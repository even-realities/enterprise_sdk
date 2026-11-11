package com.evenrealities.devices

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class G2BusinessAuthCoordinatorTest {
    private val push = JSONObject(File("../protocol/contract.json").readText())
        .getJSONObject("vectors").getString("g2_business_auth_push_magic_67_sync_67_hex")
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    // ACK for host magic 42 uses device sync 223 and an empty AuthMgr.
    private val ack = "aa12df08010180000804102a1a004c3c"
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun bothLegsMustCommitBeforeReadyAndOldAttemptCannotPass() {
        val sent = mutableListOf<String>()
        val committed = mutableListOf<String>()
        val ready = mutableListOf<Long>()
        val failed = mutableListOf<String>()
        val auth = G2BusinessAuthCoordinator(
            send = { endpoint, _, _, _ -> sent += endpoint; true },
            commitEndpoint = { endpoint, _, _ -> committed += endpoint; true },
            onReady = { _, generation -> ready += generation },
            onFailed = { _, _, endpoint -> failed += endpoint },
            identifiers = { 7 to 42 },
        )
        val generation = auth.beginPair("S211GABE130301", "left", "right")
        assertEquals(1L, generation)
        assertTrue(auth.onTransportConnected("left", 10, 11))
        assertTrue(auth.onTransportConnected("right", 20, 21))
        auth.onWriteCompleted("left", 10, 11, true)
        assertNull(auth.readyAttempt("left", generation))
        // Complementary signals from different legs or stale sessions cannot combine.
        auth.onReceive("left", 10, 11, ack)
        auth.onReceive("right", 20, 21, push)
        auth.onReceive("left", 9, 11, push)
        auth.onReceive("right", 20, 20, ack)
        auth.onReceive("left", 10, 11, ack)
        auth.onReceive("right", 20, 21, push)
        assertTrue(committed.isEmpty())
        assertTrue(ready.isEmpty())
        auth.onReceive("left", 10, 11, push)
        assertEquals(listOf("left"), committed)
        assertEquals(G2BusinessAuthCoordinator.ReadyAttempt(10, 11), auth.readyAttempt("left", generation))
        assertNull(auth.readyAttempt("right", generation))
        assertTrue(ready.isEmpty())
        auth.onReceive("right", 20, 21, ack)
        // Both replies can precede the write callback. Only this exact receipt may finish AUTH.
        auth.onWriteCompleted("right", 20, 20, true)
        auth.onWriteCompleted("right", 19, 21, false)
        assertEquals(listOf("left"), committed)
        assertTrue(ready.isEmpty())
        auth.onWriteCompleted("right", 20, 21, true)
        auth.onWriteCompleted("right", 20, 21, true)
        auth.onReceive("right", 20, 21, ack)
        auth.onReceive("left", 10, 11, push)
        assertEquals(listOf("left", "right"), committed)
        assertEquals(listOf(generation), ready)
        assertEquals(G2BusinessAuthCoordinator.ReadyAttempt(20, 21), auth.readyAttempt("right", generation))
        assertEquals(listOf("left", "right"), sent)
        assertTrue(failed.isEmpty())
    }

    @Test fun wrongCrcFailsClosedAndStaleTimeoutDoesNotFailNewPair() {
        val failed = mutableListOf<String>()
        val auth = G2BusinessAuthCoordinator(
            send = { _, _, _, _ -> true }, commitEndpoint = { _, _, _ -> true },
            onReady = { _, _ -> fail("unexpected ready") },
            onFailed = { _, _, endpoint -> failed += endpoint },
            identifiers = { 7 to 42 },
        )
        val old = auth.beginPair("S211GABE130301", "left", "right")
        assertTrue(auth.onTransportConnected("left", 1, 1))
        auth.cancel()
        val current = auth.beginPair("S211GABE130301", "left", "right")
        auth.onTimeout("left", old, 1, 1)
        auth.onPairTimeout(old)
        auth.onDisconnected("left", 1, 1)
        assertTrue(failed.isEmpty())
        assertTrue(auth.onTransportConnected("left", 2, 2))
        auth.onWriteCompleted("left", 2, 2, true)
        val bad = push.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        auth.onReceive("left", 2, 2, ack)
        auth.onReceive("left", 2, 2, bad)
        assertNull(auth.readyAttempt("left", current))
        auth.onTimeout("left", current, 2, 2)
        assertEquals(listOf("left"), failed)
        auth.onPairTimeout(current)
        assertEquals(1, failed.size)
    }

    @Test fun reopenedLegRequiresTwoFreshSignalsAndRejectsThePreviousAttempt() {
        val commits = mutableListOf<Long>()
        val auth = G2BusinessAuthCoordinator(
            send = { _, _, _, _ -> true },
            commitEndpoint = { _, _, attempt -> commits += attempt; true },
            onReady = { _, _ -> fail("right leg has not connected") },
            onFailed = { _, _, _ -> fail("unexpected auth failure") },
            identifiers = { 7 to 42 },
        )
        val generation = auth.beginPair("S211GABE130301", "left", "right")
        assertTrue(auth.onTransportConnected("left", 10, 11))
        auth.onReceive("left", 10, 11, push)
        auth.onReceive("left", 10, 11, ack)
        auth.onWriteCompleted("left", 10, 11, true)
        assertTrue(auth.reopenEndpoint("left", generation, 10, 11))
        assertTrue(auth.onTransportConnected("left", 12, 13))
        auth.onReceive("left", 12, 13, push)
        auth.onReceive("left", 10, 11, ack)
        auth.onTimeout("left", generation, 10, 11)
        assertNull(auth.readyAttempt("left", generation))
        auth.onReceive("left", 12, 13, ack)
        auth.onWriteCompleted("left", 10, 11, true)
        assertNull(auth.readyAttempt("left", generation))
        auth.onWriteCompleted("left", 12, 13, true)
        assertEquals(listOf(11L, 13L), commits)
    }

    @Test fun eitherSignalAloneExpiresAtTheOwningEndpointDeadline() {
        for (onlySignal in listOf(ack, push)) {
            val failures = mutableListOf<String>()
            val auth = G2BusinessAuthCoordinator(
                send = { _, _, _, _ -> true },
                commitEndpoint = { _, _, _ -> fail("missing second signal"); false },
                onReady = { _, _ -> fail("missing second signal") },
                onFailed = { _, _, endpoint -> failures += endpoint },
                identifiers = { 7 to 42 },
            )
            val generation = auth.beginPair("S211GABE130301", "left", "right")
            assertTrue(auth.onTransportConnected("left", 10, 11))
            auth.onWriteCompleted("left", 10, 11, true)
            auth.onReceive("left", 10, 11, onlySignal)
            assertTrue(failures.isEmpty())
            auth.onTimeout("left", generation, 10, 11)
            auth.onTimeout("left", generation, 10, 11)
            assertEquals(listOf("left"), failures)
        }
    }

    @Test fun failedOrMissingWriteReceiptCannotCommitEvenWithBothReplies() {
        for (failWrite in listOf(false, true)) {
            val failures = mutableListOf<String>()
            val auth = G2BusinessAuthCoordinator(
                send = { _, _, _, _ -> true },
                commitEndpoint = { _, _, _ -> fail("missing successful receipt"); false },
                onReady = { _, _ -> fail("missing successful receipt") },
                onFailed = { _, _, endpoint -> failures += endpoint },
                identifiers = { 7 to 42 },
            )
            val generation = auth.beginPair("S211GABE130301", "left", "right")
            assertTrue(auth.onTransportConnected("left", 10, 11))
            auth.onReceive("left", 10, 11, ack)
            auth.onReceive("left", 10, 11, push)
            assertTrue(failures.isEmpty())
            if (failWrite) {
                auth.onWriteCompleted("left", 10, 11, false)
                assertEquals(listOf("left"), failures)
            }
            auth.onTimeout("left", generation, 10, 11)
            auth.onWriteCompleted("left", 10, 11, true)
            assertNull(auth.readyAttempt("left", generation))
            assertEquals(listOf("left"), failures)
        }
    }

    @Test fun unrelatedDeviceSettingsNotificationDoesNotFailAuth() {
        val failures = mutableListOf<String>()
        val auth = G2BusinessAuthCoordinator(
            send = { _, _, _, _ -> true }, commitEndpoint = { _, _, _ -> true },
            onReady = { _, _ -> }, onFailed = { _, _, endpoint -> failures += endpoint },
            identifiers = { 7 to 42 },
        )
        auth.beginPair("S211GABE130301", "left", "right")
        assertTrue(auth.onTransportConnected("left", 10, 11))
        val unrelated = push.copyOf().also { it[9] = 0x05 }
        auth.onReceive("left", 10, 11, unrelated)
        assertTrue(failures.isEmpty())
        auth.onReceive("left", 10, 11, push)
        assertTrue(failures.isEmpty())
    }

    @Test fun readyIsRevokedOnExactDisconnectAndLateConnectFinishIsIgnored() {
        val ready = mutableListOf<Long>()
        val failed = mutableListOf<String>()
        val auth = G2BusinessAuthCoordinator(
            send = { _, _, session, _ -> session != 10L },
            commitEndpoint = { _, _, _ -> true },
            onReady = { _, generation -> ready += generation },
            onFailed = { _, _, endpoint -> failed += endpoint },
            identifiers = { 7 to 42 },
        )
        auth.beginPair("S211GABE130301", "left", "right")
        assertFalse(auth.onTransportConnected("left", 10, 1)) // stale native admission
        assertTrue(failed.isEmpty())
        assertTrue(auth.onTransportConnected("left", 11, 2))
        assertTrue(auth.onTransportConnected("right", 12, 3))
        auth.onWriteCompleted("left", 11, 2, true)
        auth.onWriteCompleted("right", 12, 3, true)
        auth.onReceive("left", 11, 2, push)
        auth.onReceive("left", 11, 2, ack)
        auth.onReceive("right", 12, 3, ack)
        auth.onReceive("right", 12, 3, push)
        assertEquals(1, ready.size)
        assertTrue(auth.isReady(ready.single()))
        auth.onDisconnected("left", 10, 1)
        assertTrue(failed.isEmpty())
        auth.onDisconnected("left", 11, 2)
        assertEquals(listOf("left"), failed)
        assertFalse(auth.isReady(ready.single()))
        assertNull(auth.readyAttempt("left", ready.single()))
    }

    @Test fun lateConnectFinishAfterPairReplacementCannotFailNewOwner() {
        val failed = mutableListOf<Long>()
        val auth = G2BusinessAuthCoordinator(
            // Native prepare rejects the old session after the replacement is registered.
            send = { _, _, session, _ -> session != 100L },
            commitEndpoint = { _, _, _ -> true },
            onReady = { _, _ -> }, onFailed = { _, generation, _ -> failed += generation },
        )
        val old = auth.beginPair("S211GABE130301", "left", "right")
        auth.cancel()
        val current = auth.beginPair("S211GABE130301", "left", "right")
        assertNotEquals(old, current)
        assertFalse(auth.onTransportConnected("left", 100, 1))
        assertTrue(failed.isEmpty())
        assertTrue(auth.onTransportConnected("left", 101, 2))
        assertEquals(current, auth.generationFor("left", 101, 2))
    }

    @Test fun retryExtendsPairDeadlineAndOldTimerCannotFailTheNewAttempt() {
        val failed = mutableListOf<String>()
        val auth = G2BusinessAuthCoordinator(
            send = { _, _, _, _ -> true }, commitEndpoint = { _, _, _ -> true },
            onReady = { _, _ -> }, onFailed = { _, _, endpoint -> failed += endpoint },
        )
        val generation = auth.beginPair("S211GABE130301", "left", "right")
        val epoch = auth.extendPairTimeout(generation)!!
        auth.onPairTimeout(generation) // initial connection deadline
        assertTrue(failed.isEmpty())
        assertTrue(auth.onTransportConnected("left", 20, 21))
        auth.onTimeout("left", generation, 19, 20) // old attempt's AuthMgr deadline
        assertTrue(failed.isEmpty())
        auth.onPairTimeout(generation, epoch) // retry connection deadline
        assertEquals(listOf("left"), failed)
    }

    @Test fun reentrantFailureCallbackMayBeginAReplacementPair() {
        lateinit var auth: G2BusinessAuthCoordinator
        var replacement = 0L
        auth = G2BusinessAuthCoordinator(
            send = { _, _, _, _ -> true }, commitEndpoint = { _, _, _ -> true },
            onReady = { _, _ -> },
            onFailed = { _, _, _ ->
                replacement = auth.beginPair("S211GABE130301", "left", "right")
            },
        )
        val old = auth.beginPair("S211GABE130301", "left", "right")
        auth.failEnterpriseEndpoint("left", old)
        assertTrue(replacement > old)
        auth.onPairTimeout(old)
        auth.onDisconnected("left", 1, 1)
        assertTrue(auth.onTransportConnected("left", 2, 2))
        assertEquals(replacement, auth.generationFor("left", 2, 2))
    }
}
