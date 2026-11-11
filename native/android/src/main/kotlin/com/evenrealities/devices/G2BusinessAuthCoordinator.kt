package com.evenrealities.devices

/** Native business AUTH lifecycle after the BLE engine emits enterprise connectFinish. */
class G2BusinessAuthCoordinator(
    private val send: (endpointId: String, packet: ByteArray, session: Long, attempt: Long) -> Boolean,
    private val commitEndpoint: (endpointId: String, session: Long, attempt: Long) -> Boolean,
    private val onReady: (sn: String, generation: Long) -> Unit,
    private val onFailed: (sn: String, generation: Long, endpointId: String) -> Unit,
    private val identifiers: (generation: Long) -> Pair<Int, Int> = { generation ->
        (generation and 255).toInt() to ((generation % 127) + 1).toInt()
    },
) {
    /** Exact physical owner exposed only after this leg has completed business AUTH. */
    data class ReadyAttempt(val session: Long, val attempt: Long)
    private enum class Phase { WAIT_TRANSPORT, WAIT_RESPONSE, READY }
    private data class Leg(var phase: Phase = Phase.WAIT_TRANSPORT, var session: Long = 0, var attempt: Long = 0,
                           var writeAcknowledged: Boolean = false, var commandAccepted: Boolean = false,
                           var authenticated: Boolean = false)
    private data class PairOwner(
        val sn: String,
        val generation: Long,
        val sync: Int,
        val magic: Int,
        val legs: Map<String, Leg>,
        var timeoutEpoch: Long = 0L,
    )

    private var generation = 0L
    private var current: PairOwner? = null

    @Synchronized
    fun beginPair(sn: String, leftId: String, rightId: String): Long {
        require(sn.length == 14 && leftId.isNotBlank() && rightId.isNotBlank() && leftId != rightId)
        generation++
        val (sync, magic) = identifiers(generation)
        require(sync in 0..255 && magic in 1..127)
        current = PairOwner(sn, generation, sync, magic, mapOf(leftId to Leg(), rightId to Leg()))
        return generation
    }

    /** The transport event must carry an exact positive physical attempt. */
    @Synchronized
    fun onTransportConnected(endpointId: String, session: Long, attempt: Long): Boolean {
        val owner = current ?: return false
        val leg = owner.legs[endpointId]?.takeIf { it.phase == Phase.WAIT_TRANSPORT } ?: return false
        if (session <= 0 || attempt <= 0) return false
        try {
            // A queued connectFinish from an old GATT is not evidence for this pair. The native
            // prepare gate must accept the exact current admission before we claim the attempt.
            if (!send(endpointId, G2BusinessAuthCodec.request(owner.sync, owner.magic), session, attempt)) {
                return false
            }
        } catch (_: Exception) {
            fail(owner, endpointId)
            return false
        }
        if (current !== owner) return false
        leg.phase = Phase.WAIT_RESPONSE
        leg.session = session
        leg.attempt = attempt
        return true
    }

    /** Only the same endpoint and exact native session/attempt may consume the 8402 reply. */
    @Synchronized
    fun onReceive(endpointId: String, session: Long, attempt: Long, packet: ByteArray) {
        receiveSignal(endpointId, session, attempt, packet)
    }

    /** Only fresh evidence for this leg is reported to diagnostics. Either signal can arrive first. */
    @Synchronized
    internal fun receiveSignal(endpointId: String, session: Long, attempt: Long, packet: ByteArray): G2BusinessAuthCodec.Signal? {
        val owner = current ?: return null
        val leg = waitingLeg(owner, endpointId, session, attempt) ?: return null
        val signal = G2BusinessAuthCodec.classify(packet, owner.magic) ?: return null
        when (signal) {
            G2BusinessAuthCodec.Signal.COMMAND_ACCEPTED -> if (leg.commandAccepted) return null else leg.commandAccepted = true
            G2BusinessAuthCodec.Signal.AUTHENTICATED -> if (leg.authenticated) return null else leg.authenticated = true
        }
        complete(owner, endpointId, leg)
        return signal
    }

    /** GATT receipt belongs to the exact queued 8401 write. A failed receipt revokes the pair. */
    @Synchronized
    internal fun onWriteCompleted(endpointId: String, session: Long, attempt: Long, success: Boolean) {
        val owner = current ?: return
        val leg = waitingLeg(owner, endpointId, session, attempt) ?: return
        if (!success) {
            fail(owner, endpointId)
            return
        }
        leg.writeAcknowledged = true
        complete(owner, endpointId, leg)
    }

    private fun waitingLeg(owner: PairOwner, id: String, session: Long, attempt: Long): Leg? =
        owner.legs[id]?.takeIf { it.phase == Phase.WAIT_RESPONSE && it.session == session && it.attempt == attempt }

    /** Install the native connection once all three independent callbacks belong to this attempt. */
    private fun complete(owner: PairOwner, endpointId: String, leg: Leg) {
        if (!leg.writeAcknowledged || !leg.commandAccepted || !leg.authenticated) return
        if (!commitEndpoint(endpointId, leg.session, leg.attempt)) {
            fail(owner, endpointId)
            return
        }
        // Commit can synchronously retire this pair; never publish the retired owner.
        if (current !== owner) return
        leg.phase = Phase.READY
        if (owner.legs.values.all { it.phase == Phase.READY }) {
            // Retain exact endpoint identities: a later physical disconnect revokes logical READY.
            onReady(owner.sn, owner.generation)
        }
    }

    /** A disconnected OTA leg may re-enter AUTH only for its original exact READY lease. */
    @Synchronized
    internal fun reopenEndpoint(endpoint: String, generation: Long, session: Long, attempt: Long): Boolean {
        val owner = current?.takeIf { it.generation == generation } ?: return false
        val previous = owner.legs[endpoint] ?: return false
        if (previous.phase != Phase.READY || previous.session != session || previous.attempt != attempt) return false
        previous.phase = Phase.WAIT_TRANSPORT
        previous.session = 0
        previous.writeAcknowledged = false
        previous.commandAccepted = false
        previous.authenticated = false
        previous.attempt = 0
        owner.timeoutEpoch++ // The OTA controller bounds recovery independently of the original pair timer.
        return true
    }

    /** A captured timeout or disconnect cannot tear down a replacement pair. */
    @Synchronized
    fun onTimeout(endpointId: String, generation: Long, session: Long, attempt: Long) {
        val owner = current?.takeIf { it.generation == generation } ?: return
        val leg = owner.legs[endpointId]?.takeIf {
            it.phase == Phase.WAIT_RESPONSE && it.session == session && it.attempt == attempt
        } ?: return
        if (leg.phase == Phase.WAIT_RESPONSE) fail(owner, endpointId)
    }

    @Synchronized
    fun onDisconnected(endpointId: String, session: Long, attempt: Long) {
        val owner = current ?: return
        owner.legs[endpointId]?.takeIf {
            // An old terminal callback must not fail a newly begun pair before its GATT identity arrives.
            it.session > 0L && it.session == session && it.attempt == attempt
        } ?: return
        fail(owner, endpointId)
    }

    /** A missing connectFinish is bounded independently of the per-leg AuthMgr reply timer. */
    @Synchronized
    fun onPairTimeout(expectedGeneration: Long, expectedEpoch: Long = 0L) {
        val owner = current?.takeIf {
            it.generation == expectedGeneration && it.timeoutEpoch == expectedEpoch
        } ?: return
        val incomplete = owner.legs.entries.firstOrNull { it.value.phase != Phase.READY } ?: return
        fail(owner, incomplete.key)
    }

    /** A retry gets its own bounded connection window; an earlier timer loses ownership. */
    @Synchronized
    fun extendPairTimeout(expectedGeneration: Long): Long? {
        val owner = current?.takeIf { it.generation == expectedGeneration } ?: return null
        owner.timeoutEpoch++
        return owner.timeoutEpoch
    }

    /** Enterprise terminal decisions are made against exact tokens by the retry budget. */
    @Synchronized
    fun failEnterpriseEndpoint(endpointId: String, expectedGeneration: Long) {
        val owner = current?.takeIf { it.generation == expectedGeneration } ?: return
        if (owner.legs.containsKey(endpointId)) fail(owner, endpointId)
    }

    @Synchronized
    fun cancel() { generation++; current = null }

    @Synchronized
    fun generationFor(endpointId: String, session: Long, attempt: Long): Long? {
        val owner = current ?: return null
        val leg = owner.legs[endpointId] ?: return null
        return owner.generation.takeIf { leg.session == session && leg.attempt == attempt }
    }

    @Synchronized
    fun isReady(expectedGeneration: Long): Boolean = current?.let { owner ->
        owner.generation == expectedGeneration && owner.legs.values.all { it.phase == Phase.READY }
    } == true

    /** A business command must bind to the same GATT attempt that was authorized. */
    @Synchronized
    fun readyAttempt(endpointId: String, expectedGeneration: Long): ReadyAttempt? {
        val owner = current?.takeIf { it.generation == expectedGeneration } ?: return null
        val leg = owner.legs[endpointId]?.takeIf { it.phase == Phase.READY } ?: return null
        return ReadyAttempt(leg.session, leg.attempt)
    }

    private fun fail(owner: PairOwner, endpointId: String) {
        if (current !== owner) return
        current = null
        onFailed(owner.sn, owner.generation, endpointId)
    }
}
