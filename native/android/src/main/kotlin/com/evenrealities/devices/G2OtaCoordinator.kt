package com.evenrealities.devices

import com.fzfstudio.ezw_ble.ble.BleG2OtaEndpointIdentity
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Progress counts firmware-acknowledged bytes across both legs. Success requires fresh version evidence. */
data class G2OtaEvent(val sn: String, val generation: Long, val operation: Long, val endpoint: String?,
                      val phase: String, val progress: Double, val failure: String? = null,
                      val leftVersion: String? = null, val rightVersion: String? = null,
                      val session: Long? = null, val attempt: Long? = null)

/** Registry and physical GATT identity are carried on every write and notification. */
internal data class G2OtaScope(val sn: String, val generation: Long, val operation: Long,
                               val legs: List<BleG2OtaEndpointIdentity>, val tx: String = UUID.randomUUID().toString())

internal interface G2OtaTransport {
    fun begin(scope: G2OtaScope): String?
    fun bind(scope: G2OtaScope, instance: String, leg: BleG2OtaEndpointIdentity, park: Boolean): Boolean
    fun recover(scope: G2OtaScope, instance: String, leg: BleG2OtaEndpointIdentity): Boolean
    fun maxWrite(leg: BleG2OtaEndpointIdentity): Int
    fun write(scope: G2OtaScope, instance: String, leg: BleG2OtaEndpointIdentity,
              packets: List<ByteArray>, completion: (String?) -> Unit)
    fun finish(scope: G2OtaScope, instance: String, reason: String): Boolean
    fun reconnect(scope: G2OtaScope)
    fun readVersion(leg: BleG2OtaEndpointIdentity, packet: ByteArray): () -> Unit
}

/** All entries are serialized by the SDK wrapper. Every delayed callback rechecks its captured owner. */
internal class G2OtaCoordinator(
    private val transport: G2OtaTransport,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val now: () -> Long,
    private val emit: (G2OtaEvent) -> Unit,
) {
    private class Owner(val scope: G2OtaScope, val instance: String, val image: G2OtaImage, val target: String) {
        var legIndex = 0
        var component = 0
        var offset = 0
        var acknowledged = 0L
        val total = image.components.sumOf { it.body.size.toLong() + it.header.size } * 2
        val live = scope.legs.toMutableList()
        val leg get() = live[legIndex]
        var componentStart = 0L
        var recovering = false
        var recoveryRounds = 0
        var recoverySpent = 0L
        var recoveryStart = 0L
        var writeDone = false
        var earlyReply: Int? = null
        var earlyNotify: Int? = null
        var versions: G2OtaVersions? = null
        var retireRead: (() -> Unit)? = null
        var versionRead = 0
        var versionDeadline = 0L
        var verifyLegs = emptyList<BleG2OtaEndpointIdentity>()
        var receivedFrame = false
        val part get() = image.components[component]
        var cid = -1
        var sync = 0
        var epoch = 0L
        var retries = 0
        var rawDeadline = 0L
        var waitingNotify = false
        var verifying = false
        var versionLeg: BleG2OtaEndpointIdentity? = null
        var timer: (() -> Unit)? = null
        var notifyTimer: (() -> Unit)? = null
    }

    private companion object { val operations = AtomicLong() }
    private var owner: Owner? = null
    val busy get() = owner != null

    fun start(sn: String, generation: Long, legs: List<BleG2OtaEndpointIdentity>, firmware: ByteArray, target: String): Long {
        check(owner == null) { "busy" }
        require(G2OtaVersions.isValidTarget(target)) { "invalidTargetVersion" }
        val image = try { G2OtaWire.parse(firmware) } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("invalidFirmware")
        }
        require(image.version.startsWith("s200_")) { "invalidFirmware" }
        val imageVersion = image.version.removePrefix("s200_")
        require(G2OtaVersions(imageVersion, imageVersion).matches(target)) { "invalidTargetVersion" }
        val scope = G2OtaScope(sn, generation, operations.incrementAndGet(), legs)
        check(legs.size == 2 && legs.all { transport.maxWrite(it) >= 139 }) { "notReady" }
        val instance = transport.begin(scope)
        check(!instance.isNullOrEmpty()) { "notReady" }
        val o = Owner(scope, instance, image, target)
        owner = o
        event(o, "started")
        beginLeg(o)
        return scope.operation
    }

    /** BLE can deliver the final device notify ahead of the RESULT_CHECK write completion and ACK. */
    fun onFrame(endpoint: String, session: Long, attempt: Long, tx: String, operation: Long, instance: String,
                bytes: ByteArray) {
        val o = owner ?: return
        if (o.verifying || o.recovering || !o.leg.matches(endpoint, session, attempt) || o.scope.tx != tx ||
            o.scope.operation != operation || o.instance != instance) return
        o.receivedFrame = true
        if (o.waitingNotify) {
            G2OtaWire.notify(bytes)?.let { result ->
                if (o.writeDone) acceptNotify(o, result) else if (o.earlyNotify == null) o.earlyNotify = result
                return
            }
        }
        if (o.cid !in 0..3) return
        val status = G2OtaWire.reply(bytes, o.cid, o.sync) ?: return
        if (o.writeDone) acceptReply(o, status) else if (o.earlyReply == null) o.earlyReply = status
    }

    private fun acceptNotify(o: Owner, status: Int) {
        if (status == 0 || status == 9) completeLeg(o) else fail(o, "notifyRejected:$status")
    }

    private fun acceptReply(o: Owner, status: Int) {
        when {
            o.cid == 2 && status in 3..5 -> retryWindow(o, status)
            o.cid == 3 && (status == 8 || status == 4) -> nextComponent(o)
            status != 0 || o.cid == 3 -> fail(o, "firmwareRejected:$status")
            o.cid == 0 -> send(o, 1)
            o.cid == 1 -> {
                o.acknowledged += o.part.header.size
                nextWindow(o)
            }
            else -> {
                val length = minOf(4096, o.part.body.size - o.offset)
                o.offset += length
                o.acknowledged += length
                event(o, "transferring")
                nextWindow(o)
            }
        }
    }

    /** Consume old physical terminals throughout the handoff so the parked leg cannot revoke the second. */
    fun onDisconnected(endpoint: String, session: Long, attempt: Long): Boolean {
        val o = owner ?: return false
        if (o.verifyLegs.any { it.matches(endpoint, session, attempt) }) {
            fail(o, "versionUnverified")
            return false // Normal auth teardown still owns this new GATT.
        }
        val index = o.live.indexOfFirst { it.matches(endpoint, session, attempt) }
        if (index < 0) return false
        if (o.verifying || index < o.legIndex || (index == o.legIndex && o.recovering)) return true
        if (index != o.legIndex || o.waitingNotify || o.cid == 3) {
            fail(o, "disconnected")
            return true
        }
        clearWait(o)
        if (++o.recoveryRounds > 3 || o.recoverySpent >= 90_000) {
            fail(o, "recoveryExhausted")
            return true
        }
        // Re-enter START and this component's INFORMATION on a new, re-authorized physical link.
        o.recovering = true
        o.recoveryStart = now()
        o.offset = 0
        o.acknowledged = o.componentStart
        event(o, "recovering")
        val epoch = o.epoch
        o.timer = schedule(minOf(20_000L, 90_000 - o.recoverySpent)) {
            if (owner === o && epoch == o.epoch && o.recovering) fail(o, "recoveryTimeout")
        }
        if (!runCatching { transport.recover(o.scope, o.instance, o.leg) }.getOrDefault(false)) {
            fail(o, "recoveryFailed")
        }
        return true
    }

    /** A successful AuthMgr commit for this endpoint provides the only lease that can rebind RAW. */
    fun onEndpointReady(endpoint: String, generation: Long, session: Long, attempt: Long) {
        val o = owner?.takeIf { it.recovering && it.scope.generation == generation } ?: return
        val old = o.leg
        if (old.uuid != endpoint || session <= 0 || attempt <= 0 || old.matches(endpoint, session, attempt)) return
        clearWait(o)
        o.recoverySpent += (now() - o.recoveryStart).coerceAtLeast(0)
        val fresh = old.copy(sessionGeneration = session, attemptGeneration = attempt)
        if (o.recoverySpent >= 90_000 || transport.maxWrite(fresh) < 139 ||
            !transport.bind(o.scope, o.instance, fresh, false)) return fail(o, "recoveryFailed")
        o.live[o.legIndex] = fresh
        o.recovering = false
        o.retries = 0
        send(o, 0)
    }

    val transferring get() = owner?.verifying == false

    fun cancel(operation: Long) {
        owner?.takeIf { it.scope.operation == operation }?.let { terminate(it, "cancelled", null) }
    }

    fun cancelCurrent() { owner?.let { cancel(it.scope.operation) } }

    /** The rebooted pair must establish both fresh authenticated physical attempts before readback. */
    fun onReady(sn: String, generation: Long, legs: List<BleG2OtaEndpointIdentity>) {
        val o = owner?.takeIf { it.verifying && it.versionLeg == null } ?: return
        if (o.scope.sn != sn || generation <= o.scope.generation || legs.size != 2 ||
            legs.zip(o.live).any { (new, old) -> new.uuid != old.uuid ||
                new.sessionGeneration <= 0 || new.attemptGeneration <= 0 ||
                old.matches(new.uuid, new.sessionGeneration, new.attemptGeneration) }) return
        o.verifyLegs = legs.toList()
        o.versionDeadline = now() + 60_000L
        o.versionLeg = legs.last()
        requestVersions(o)
    }

    /** Boot-time empty responses and a lost settings ACK are retried on the same fresh READY owner. */
    private fun requestVersions(o: Owner) {
        val leg = o.versionLeg ?: return
        o.versionRead++
        try {
            o.retireRead?.invoke()
            o.retireRead = transport.readVersion(leg, G2OtaVersionWire.request((o.sync + o.versionRead) and 255, versionMagic(o)))
            o.timer = schedule(minOf(2_000L, (o.versionDeadline - now()).coerceAtLeast(1))) {
                if (owner === o) {
                    if (now() < o.versionDeadline) requestVersions(o)
                    else fail(o, if (o.versions == null) "versionUnverified" else "versionMismatch")
                }
            }
        } catch (_: Exception) { fail(o, "versionReadFailed") }
    }

    fun onVersion(endpoint: String, session: Long, attempt: Long, bytes: ByteArray) {
        val o = owner ?: return
        if (o.versionLeg?.matches(endpoint, session, attempt) != true) return
        if (now() >= o.versionDeadline) return fail(o, if (o.versions == null) "versionUnverified" else "versionMismatch")
        val versions = G2OtaVersionWire.response(bytes, versionMagic(o)) ?: return
        o.versions = versions
        if (versions.matches(o.target)) terminate(o, "succeeded", null)
    }

    fun onConnectionFailed() { owner?.let { fail(it, if (it.verifying) "versionUnverified" else "disconnected") } }

    private fun beginLeg(o: Owner) {
        try {
            if (!transport.bind(o.scope, o.instance, o.leg, false)) return fail(o, "bindFailed")
            o.componentStart = o.acknowledged
            o.component = 0
            o.offset = 0
            o.retries = 0
            send(o, 0)
        } catch (_: Exception) { fail(o, "bindFailed") }
    }

    private fun nextWindow(o: Owner) {
        o.retries = 0
        o.rawDeadline = now() + 30_000
        if (o.offset < o.part.body.size) send(o, 2) else send(o, 3)
    }

    /** Retries never advance the RAW offset and get a new sync ID for each submission. */
    private fun retryWindow(o: Owner, reason: Int) {
        clearWait(o)
        if (++o.retries > 10 || now() >= o.rawDeadline) return fail(o, "rawTimeout")
        val delay = if (reason == 5) minOf(5_000L, o.rawDeadline - now()) else 0L
        val epoch = o.epoch
        o.timer = schedule(delay) {
            if (owner === o && epoch == o.epoch) {
                if (now() >= o.rawDeadline) fail(o, "rawTimeout") else send(o, 2)
            }
        }
    }

    private fun send(o: Owner, cid: Int) {
        clearWait(o)
        o.cid = cid
        o.sync = (o.sync + 1) and 255
        val epoch = o.epoch
        if (cid == 3 && o.component == o.image.components.lastIndex) {
            o.waitingNotify = true
            o.notifyTimer = schedule(90_000) { if (owner === o && o.waitingNotify) fail(o, "notifyTimeout") }
        }
        try {
            val frames = when (cid) {
                1 -> listOf(G2OtaWire.control(cid, o.sync, o.part.header))
                2 -> G2OtaWire.window(o.part.body.copyOfRange(o.offset, minOf(o.offset + 4096, o.part.body.size)),
                    o.sync, transport.maxWrite(o.leg))
                else -> listOf(G2OtaWire.control(cid, o.sync))
            }
            o.receivedFrame = false
            o.writeDone = false
            o.earlyReply = null
            o.earlyNotify = null
            // No retry while the OS still has a batch. Early firmware replies also need write success.
            o.timer = schedule(30_000) { if (owner === o && epoch == o.epoch) fail(o, "writeTimeout") }
            transport.write(o.scope, o.instance, o.leg, frames) { error ->
                if (owner !== o || epoch != o.epoch) return@write
                o.timer?.invoke()
                if (error != null) {
                    // The transport may report loss just before its exact terminal. Allow that
                    // callback to initiate recovery, but never synthesize a disconnected identity.
                    o.timer = schedule(1_000) { if (owner === o && epoch == o.epoch) fail(o, error) }
                    return@write
                }
                if (cid == 2 && now() >= o.rawDeadline) { fail(o, "rawTimeout"); return@write }
                o.writeDone = true
                val response = o.earlyReply
                val notify = o.earlyNotify
                // An explicit RESULT rejection overrides a pending early terminal success.
                if (response != null && (o.cid != 3 || response !in setOf(8, 4))) {
                    acceptReply(o, response)
                } else if (notify != null) {
                    acceptNotify(o, notify)
                } else if (response != null) {
                    acceptReply(o, response)
                } else {
                    val delay = if (cid == 2) minOf(5_000L, (o.rawDeadline - now()).coerceAtLeast(1)) else 5_000L
                    o.timer = schedule(delay) {
                        if (owner === o && epoch == o.epoch) {
                            when {
                                cid == 2 -> retryWindow(o, 4)
                                cid == 3 -> nextComponent(o)
                                cid == 0 && !o.receivedFrame && ++o.recoveryRounds <= 3 -> send(o, 0)
                                else -> fail(o, "responseTimeout")
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { fail(o, "writeFailed") }
    }

    /** Intermediate RESULT_CHECK has no installation notify. Final notify owns the endpoint transition. */
    private fun nextComponent(o: Owner) {
        clearWait(o)
        if (o.waitingNotify) return
        o.component++
        o.componentStart = o.acknowledged
        o.offset = 0
        send(o, 1)
    }

    private fun completeLeg(o: Owner) {
        clearWait(o)
        o.notifyTimer?.invoke()
        o.notifyTimer = null
        o.waitingNotify = false
        if (o.legIndex < o.scope.legs.lastIndex) {
            val parked = o.leg
            o.legIndex++
            o.component = 0
            o.componentStart = o.acknowledged
            if (!transport.bind(o.scope, o.instance, parked, true)) return fail(o, "parkFailed")
            val epoch = o.epoch
            o.timer = schedule(5_000) { if (owner === o && epoch == o.epoch) beginLeg(o) }
            return
        }
        // Mark the old GATT identities retired before finish issues physical disconnects.
        if (!release(o, "transportCompleted")) return fail(o, "retirementFailed")
        o.verifying = true
        event(o, "transportCompleted")
        event(o, "verifying")
        o.notifyTimer = schedule(99_000) { if (owner === o) fail(o, "versionUnverified") }
        o.timer = schedule(5_000) {
            if (owner === o) {
                try { transport.reconnect(o.scope) } catch (_: Exception) { fail(o, "versionUnverified") }
            }
        }
    }

    private fun versionMagic(o: Owner) = ((o.scope.operation + o.versionRead - 2) % 255 + 1).toInt()

    private fun clearWait(o: Owner) {
        o.timer?.invoke()
        o.timer = null
        o.epoch++
        o.cid = -1
    }

    private fun release(o: Owner, reason: String): Boolean =
        runCatching { transport.finish(o.scope, o.instance, reason) }.getOrDefault(false)

    private fun fail(o: Owner, reason: String) { terminate(o, "failed", reason) }

    private fun terminate(o: Owner, phase: String, failure: String?) {
        if (owner !== o) return
        clearWait(o)
        o.notifyTimer?.invoke()
        o.retireRead?.invoke()
        owner = null
        if (!o.verifying && !release(o, if (phase == "cancelled") "cancelled" else "failed")) {
            event(o, "failed", "retirementFailed")
        } else event(o, phase, failure)
    }

    private fun event(o: Owner, phase: String, failure: String? = null) {
        val physical = if (o.verifying) o.versionLeg else o.leg
        emit(G2OtaEvent(o.scope.sn, o.scope.generation, o.scope.operation, physical?.uuid, phase,
            o.acknowledged.toDouble() / o.total, failure, o.versions?.left, o.versions?.right,
            physical?.sessionGeneration, physical?.attemptGeneration))
    }

    private fun BleG2OtaEndpointIdentity.matches(endpoint: String, session: Long, attempt: Long) =
        uuid == endpoint && sessionGeneration == session && attemptGeneration == attempt
}
