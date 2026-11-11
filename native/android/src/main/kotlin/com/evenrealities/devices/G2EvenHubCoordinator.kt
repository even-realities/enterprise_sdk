package com.evenrealities.devices

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import com.fzfstudio.ezw_ble.ble.BleCommandLease

/** Immutable page ownership. A new physical attempt always requires a new page. */
data class G2EvenHubOwner(val sn: String, val endpoint: String, val generation: Long,
                         val session: Long, val attempt: Long)

/** Semantic fields only; neither protobuf bytes nor credentials cross this event boundary. */
data class G2EvenHubEvent(val owner: G2EvenHubOwner, val operation: Long, val phase: String,
                         val heartbeatCount: Long, val event: String? = null, val failure: String? = null,
                         val details: Map<String, Any> = emptyMap()) {
    // Native-only delivery token never crosses bridge serialization; same-page re-enable revokes it.
    internal var audioLease: Any? = null
}

/** Native page lease, all-command queue, image chunk progression and transport receive ownership. */
class G2EvenHubCoordinator(
    private val send: (G2EvenHubOwner, ByteArray, BleCommandLease) -> Unit,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onEvent: (G2EvenHubEvent) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sessionIds: () -> Long = { nextSession() },
) {
    /** A decoder belongs to one confirmed page audio lease, never to the SDK singleton. */
    internal interface AudioDecoder : AutoCloseable {
        fun decode(bytes: ByteArray): ByteArray?
    }
    internal var audioDecoderFactory: () -> AudioDecoder = {
        // liblc3 retains synthesis history, so all frames share this lease's decoder.
        val decoder = NativeLc3Decoder()
        object : AudioDecoder {
            override fun decode(bytes: ByteArray): ByteArray? {
                val output = java.io.ByteArrayOutputStream()
                for (offset in bytes.indices step 40) {
                    output.write(decoder.decode(bytes.copyOfRange(offset, offset + 40)) ?: return null)
                }
                return output.toByteArray()
            }
            override fun close() = decoder.close()
        }
    }

    private class Page(val owner: G2EvenHubOwner, val operation: Long, val session: Long,
                       var layout: G2EvenHubCodec.Layout, val inputOwners: List<G2EvenHubOwner>) {
        // Admission requires a successful enable ACK; disable/close revoke before their write.
        val audioDecoders = mutableMapOf<String, AudioDecoder>()
        val audioProcessors = mutableMapOf<String, G2AudioPcmProcessor>()
        var active = false
        var closing = false
        var heartbeatCount = 0L
        var confirmedHeartbeatCount = 0L
        var leaseDueAt = 0L
        var pending: Pending? = null
        var closeJob: Job? = null
        var resumeJob: Job? = null
        var cancelTick: (() -> Unit)? = null
        var tickGeneration = 0L
        val assemblers = inputOwners.associate { it.endpoint to G2EvenHubTransport.Assembler() }
        val oneWayLease = BleCommandLease()
    }
    private class Job(var requests: List<G2EvenHubCodec.Request>,
                      val completion: ((Result<Map<String, Any>>) -> Unit)? = null,
                      val retryImage: (() -> List<G2EvenHubCodec.Request>)? = null) {
        var index = 0
        var retries = 0
        var completed = false
        fun complete(result: Result<Map<String, Any>>) {
            if (completed) return
            completed = true
            completion?.invoke(result)
        }
    }
    private class Pending(val job: Job, val request: G2EvenHubCodec.Request, val magic: Int, val sync: Int,
                          val writeLease: BleCommandLease) {
        var cancelTimeout: (() -> Unit)? = null
    }
    private class Ready(val owners: List<G2EvenHubOwner>) {
        val assemblers = owners.associate { it.endpoint to G2EvenHubTransport.Assembler() }
        val writeLease = BleCommandLease()
        var pending: Job? = null
    }
    /** Lock-free ingress snapshot: GATT callbacks may hold the BLE manager monitor and must
     * never acquire the page monitor. Replacing this map revokes queued old microphone bytes. */
    internal class AudioAdmission internal constructor(val owner: G2EvenHubOwner, internal val decoder: Any)
    @Volatile private var audioAdmissions: Map<String, AudioAdmission> = emptyMap()

    internal fun captureAudioAdmission(endpoint: String, session: Long, attempt: Long): AudioAdmission? =
        audioAdmissions[endpoint]?.takeIf { it.owner.session == session && it.owner.attempt == attempt }

    /** The captured ingress lease must still own this exact decoder after the handler handoff. */
    @Synchronized
    internal fun onAudioReceiveOwned(admission: AudioAdmission, packet: ByteArray) {
        if (audioAdmissions[admission.owner.endpoint] !== admission) return
        val owner = admission.owner
        onAudioReceive(owner.endpoint, owner.session, owner.attempt, packet)
    }

    private var sequence = 0L
    private var commandSequence = 0L
    private var imageSequence = 0L
    private var page: Page? = null
    private var ready: Ready? = null

    /** Install only after whole-pair READY. These frozen attempts own connection-scoped OS events. */
    @Synchronized
    fun onReady(owners: List<G2EvenHubOwner>) {
        require(owners.isNotEmpty() && owners.size <= 2 && owners.map { it.endpoint }.distinct().size == owners.size &&
            owners.all { it.endpoint.isNotEmpty() && it.session > 0 && it.attempt > 0 && it.generation > 0 &&
                it.sn == owners.first().sn && it.generation == owners.first().generation })
        if (ready?.owners == owners) return
        retireReady("replaced")
        ready = Ready(owners.toList())
    }

    /** Other native business operations must not steal a page command/heartbeat transport slot. */
    @Synchronized
    fun hasPage(): Boolean = page != null

    /** Menu jobs can own a business write before a page exists. */
    @Synchronized
    internal fun hasOngoingWork(): Boolean = page != null || ready?.pending != null

    /** The short demo is only a native logical CREATE wrapper, sharing the generic lease. */
    @Synchronized
    fun open(owner: G2EvenHubOwner, text: String, inputOwners: List<G2EvenHubOwner> = listOf(owner)): Long =
        execute(owner, "createPage", G2EvenHubCodec.demoParameters(text), inputOwners)

    /** Validate the whole request before acquiring ownership. Rejected busy requests preserve it. */
    @Synchronized
    fun execute(owner: G2EvenHubOwner, command: String, parameters: Map<String, Any?>,
                inputOwners: List<G2EvenHubOwner> = listOf(owner),
                completion: ((Result<Map<String, Any>>) -> Unit)? = null): Long {
        require(owner.generation > 0 && owner.session > 0 && owner.attempt > 0 && owner.endpoint.isNotEmpty())
        if (command == "createPage") {
            val session = sessionIds()
            val requests = G2EvenHubCodec.prepare(command, parameters, session)
            // Force transport bounds checking before creating a page lease or publishing opening.
            G2EvenHubCodec.packets(requests.single(), 1, 1)
            if (page != null) throw G2EvenHubException("busy")
            if (ready?.pending != null) throw G2EvenHubException("busy")
            require(inputOwners.isNotEmpty() && inputOwners.size <= 2 && inputOwners.contains(owner) &&
                inputOwners.map { it.endpoint }.distinct().size == inputOwners.size &&
                inputOwners.all { it.sn == owner.sn && it.generation == owner.generation && it.session > 0 && it.attempt > 0 })
            val current = Page(owner, ++sequence, session, requests.single().layout!!, inputOwners.toList())
            page = current
            // Already admitted startup-failure tails must not arrive after a newly created page.
            ready?.let { old -> old.writeLease.invalidate(); ready = Ready(old.owners) }
            issue(current, Job(requests, completion), announceOpening = true)
            return current.operation
        }
        if (command == "menuStartupFailed" && page == null) {
            return submitWithoutPage(owner, parameters, completion)
        }
        val current = page ?: throw G2EvenHubException("notReady")
        if (current.owner != owner) throw G2EvenHubException("notReady")
        val nextCount = current.heartbeatCount % UINT32 + 1
        val requests = G2EvenHubCodec.prepare(command, parameters, current.session, current.layout,
            count = nextCount, mapSession = nextImageSession())
        requests.forEach { G2EvenHubCodec.packets(it, 1, 1) }
        if (command == "shutdownPage") {
            requestClose(current, Job(requests, completion))
            return current.operation
        }
        if (current.closing || current.pending != null || current.resumeJob != null) throw G2EvenHubException("busy")
        if (!current.active) throw G2EvenHubException("notReady")
        current.cancelTick?.invoke(); current.cancelTick = null
        if (command == "heartbeat") current.heartbeatCount = nextCount
        // Image bytes are copied now: a host mutation cannot alter a later retry's logical image.
        val frozenImage = if (command == "updateImage") parameters.toMutableMap().also {
            it["data"] = (parameters["data"] as ByteArray).copyOf()
        } else null
        val retry = frozenImage?.let { frozen -> {
            G2EvenHubCodec.prepare("updateImage", frozen, current.session, current.layout,
                mapSession = nextImageSession())
        } }
        if (command == "audioControl" && parameters["enabled"] == false) stopAudio(current)
        issue(current, Job(requests, completion, retry))
        return current.operation
    }

    /** Retain the pending ACK, then issue shutdown. No new heartbeat is scheduled after admission. */
    @Synchronized
    fun close() {
        val current = page ?: throw G2EvenHubException("notReady")
        requestClose(current, Job(G2EvenHubCodec.prepare("shutdownPage", emptyMap(), current.session)))
    }
    private fun requestClose(current: Page, job: Job) {
        if (current.closing) throw G2EvenHubException("busy")
        stopAudio(current)
        current.closing = true
        current.closeJob = job
        current.cancelTick?.invoke(); current.cancelTick = null
        emit(current, "closing")
        if (page === current && current.pending == null) {
            current.closeJob = null
            issue(current, job)
        }
    }

    /** BLE delivery and timers serialize on this monitor; endpoint/session/attempt must all match. */
    @Synchronized
    fun onReceive(endpoint: String, session: Long, attempt: Long, packet: ByteArray) {
        val current = page
        if (current == null) {
            val connection = ready ?: return
            val source = connection.owners.find { it.endpoint == endpoint && it.session == session && it.attempt == attempt } ?: return
            val frame = connection.assemblers.getValue(endpoint).accept(packet, now()) ?: return
            val input = G2EvenHubCodec.unpagedEvent(frame) ?: return
            // No synthetic page/session or heartbeat: startup/diagnostics belong to the READY link.
            onEvent(G2EvenHubEvent(source, ++sequence, "input", 0, input.name, details = input.data))
            return
        }
        val source = current.inputOwners.find {
            it.endpoint == endpoint && it.session == session && it.attempt == attempt
        } ?: return
        val frame = current.assemblers.getValue(source.endpoint).accept(packet, now()) ?: return
        // An unconfirmed or closing page cannot own user input, even when IDs happen to match.
        if (current.active && !current.closing) G2EvenHubCodec.event(frame, current.session, current.layout)?.let { input ->
            emit(current, "input", event = input.name, details = input.data, source = source)
            if (input.terminal && page === current) finish(current, "closed", commandFailure = "pageExited")
            return
        }
        // Input is accepted from both authenticated legs, but only the command leg may ACK writes.
        if (source != current.owner) return
        val pending = current.pending ?: return
        val response = G2EvenHubCodec.response(frame, pending.request, pending.magic, pending.sync)
        when (response.status) {
            G2EvenHubCodec.Reply.IGNORED -> return
            G2EvenHubCodec.Reply.REJECTED -> { failCommand(current, pending, "deviceRejected"); return }
            G2EvenHubCodec.Reply.SUCCESS -> Unit
        }
        pending.cancelTimeout?.invoke(); pending.writeLease.invalidate(); current.pending = null
        val job = pending.job
        if (pending.request.command == G2EvenHubCodec.Command.IMAGE && job.index < job.requests.lastIndex) {
            if (current.closing) {
                job.complete(Result.failure(G2EvenHubException("cancelled")))
                continueAfterCommand(current)
            } else {
                job.index++
                // Long images yield between complete 4K chunks, keeping the page lease alive natively.
                if (now() >= current.leaseDueAt) {
                    current.resumeJob = job
                    sendHeartbeat(current)
                } else issue(current, job)
            }
            return
        }
        when (pending.request.command) {
            G2EvenHubCodec.Command.CREATE -> {
                current.active = true; current.leaseDueAt = now() + 5000
                job.complete(Result.success(result(current, pending.request, response.details)))
                if (page !== current) return
                if (!current.closing) emit(current, "active")
            }
            G2EvenHubCodec.Command.HEARTBEAT -> {
                current.confirmedHeartbeatCount = pending.request.expected.getValue(1)
                current.leaseDueAt = now() + 5000
                job.complete(Result.success(result(current, pending.request, response.details)))
                if (page !== current) return
                if (!current.closing) emit(current, "heartbeat")
            }
            G2EvenHubCodec.Command.CLOSE -> {
                if (pending.request.expected[1] == 1L) {
                    // Firmware mode1 ACK confirms only the prompt; retain and renew the actual page.
                    current.closing = false
                    emit(current, "active", details = mapOf("awaitingExit" to true))
                    job.complete(Result.success(result(current, pending.request, response.details + mapOf("awaitingExit" to true))))
                    continueAfterCommand(current); return
                }
                val value = result(current, pending.request, response.details)
                finish(current, "closed")
                // Release the lease before completion, allowing a host to synchronously open the next page.
                job.complete(Result.success(value)); return
            }
            else -> {
                if (pending.request.command == G2EvenHubCodec.Command.AUDIO) {
                    stopAudio(current)
                    if (pending.request.expected[1] == 1L && !current.closing) {
                        try {
                            for (input in current.inputOwners) {
                                current.audioDecoders[input.endpoint] = audioDecoderFactory()
                                current.audioProcessors[input.endpoint] = G2AudioPcmProcessor()
                            }
                            audioAdmissions = current.inputOwners.associate { input ->
                                input.endpoint to AudioAdmission(input, current.audioDecoders.getValue(input.endpoint))
                            }
                        } catch (_: Throwable) {
                            // Native linkage or allocation failures must settle the pending open.
                            finish(current, "failed", "audioDecoderUnavailable")
                            job.complete(Result.failure(G2EvenHubException("audioDecoderUnavailable")))
                            return
                        }
                    }
                }
                if (pending.request.command == G2EvenHubCodec.Command.REBUILD) current.layout = pending.request.layout!!
                job.complete(Result.success(result(current, pending.request, response.details +
                    if (pending.request.command == G2EvenHubCodec.Command.IMAGE) mapOf("imageFragments" to job.requests.size, "retries" to job.retries) else emptyMap())))
            }
        }
        continueAfterCommand(current)
    }

    /** STREAM payloads are raw LC3, not common-channel protobuf. Both frozen physical
     * owners may deliver; their synthesis/AGC histories remain independent. */
    @Synchronized
    fun onAudioReceive(endpoint: String, session: Long, attempt: Long, packet: ByteArray) {
        val current = page ?: return
        if (!current.active || current.closing) return
        val source = current.inputOwners.find {
            it.endpoint == endpoint && it.session == session && it.attempt == attempt
        } ?: return
        val decoder = current.audioDecoders[endpoint] ?: return
        // A GATT value carries complete 40-byte LC3 groups, or the production five-frame
        // batch with a four-byte target and optional index. Bound admission below ATT's 512B.
        val tagged = packet.size == 204 || packet.size == 205
        val encoded = when {
            tagged -> packet.copyOfRange(0, 200)
            packet.size in 40..480 && packet.size % 40 == 0 -> packet.copyOf()
            else -> return
        }
        val pcm = try { decoder.decode(encoded) } catch (_: Exception) { null } ?: return
        if (page !== current || current.audioDecoders[endpoint] !== decoder || pcm.isEmpty()) return
        // Tail begins with unsigned energyDiff and ends with the signed direction angle.
        val energy = if (tagged) {
            (packet[200].toInt() and 255) or ((packet[201].toInt() and 255) shl 8)
        } else null
        val direction = if (tagged) {
            ((packet[202].toInt() and 255) or ((packet[203].toInt() and 255) shl 8)).toShort().toInt()
        } else null
        val processed = current.audioProcessors.getValue(endpoint).processFrame(pcm, energy, direction)
        val details = mutableMapOf<String, Any>(
            "audioPcm" to processed.audioPcm, "source" to "glasses", "speakerRole" to processed.speakerRole,
            "sampleRate" to 16000, "channels" to 1, "bitsPerSample" to 16)
        processed.direction?.let { details["direction"] = it }
        emit(current, "input", event = "audioEvent", details = details, source = source, audioLease = decoder)
    }

    /** Validate immediately before a deferred host dispatch, then release the monitor before
     * invoking host code. Decoder identity distinguishes same-page disable/re-enable leases. */
    @Synchronized
    internal fun isCurrentAudioDelivery(event: G2EvenHubEvent): Boolean {
        if (event.event != "audioEvent") return true
        val current = page ?: return false
        return current.active && !current.closing && event.operation == current.operation &&
            event.owner in current.inputOwners && event.audioLease != null &&
            current.audioDecoders[event.owner.endpoint] === event.audioLease
    }

    /** Release codec state synchronously so late frames cannot inherit a new page's lease. */
    private fun stopAudio(current: Page) {
        audioAdmissions = emptyMap()
        val decoders = current.audioDecoders.values.toList()
        current.audioDecoders.clear()
        current.audioProcessors.clear()
        decoders.forEach { runCatching { it.close() } }
    }

    /** Teardown clears ownership before callbacks, so reentrant hosts cannot cancel a replacement. */
    @Synchronized
    fun cancel(failure: String = "disconnected") {
        retireReady(failure)
        page?.let { finish(it, "failed", failure) }
    }
    @Synchronized
    fun onDisconnected(endpoint: String, session: Long, attempt: Long) {
        if (ready?.owners?.any { it.endpoint == endpoint && it.session == session && it.attempt == attempt } == true) {
            retireReady("disconnected")
        }
        page?.takeIf { it.inputOwners.any { source -> source.endpoint == endpoint && source.session == session && source.attempt == attempt } }
            ?.let { finish(it, "failed", "disconnected") }
    }

    private fun submitWithoutPage(owner: G2EvenHubOwner, parameters: Map<String, Any?>,
                                  completion: ((Result<Map<String, Any>>) -> Unit)?): Long {
        val connection = ready?.takeIf { owner in it.owners } ?: throw G2EvenHubException("notReady")
        if (connection.pending != null) throw G2EvenHubException("busy")
        val request = G2EvenHubCodec.prepare("menuStartupFailed", parameters, 1).single()
        val number = ++commandSequence
        val packets = G2EvenHubCodec.packets(request, ((number - 1) % 255 + 1).toInt(), (number and 255).toInt())
        val operation = ++sequence
        val job = Job(listOf(request), completion)
        connection.pending = job
        try {
            for (packet in packets) {
                if (ready !== connection || connection.pending !== job || !connection.writeLease.isActive) return operation
                send(owner, packet, connection.writeLease)
            }
        } catch (_: Exception) {
            if (ready === connection && connection.pending === job) {
                connection.pending = null
                // Revoke any partial tail, but keep the authenticated link eligible for a new reply.
                connection.writeLease.invalidate(); ready = Ready(connection.owners)
                job.complete(Result.failure(G2EvenHubException("writeFailed")))
            }
            return operation
        }
        if (ready === connection && connection.pending === job) {
            connection.pending = null
            job.complete(Result.success(mapOf("command" to "menuStartupFailed", "operation" to operation,
                "heartbeatCount" to 0L, "acknowledged" to false, "submitted" to true)))
        }
        return operation
    }

    private fun retireReady(failure: String) {
        val connection = ready ?: return
        ready = null
        connection.writeLease.invalidate()
        val pending = connection.pending; connection.pending = null
        pending?.complete(Result.failure(G2EvenHubException(failure)))
    }
    private fun continueAfterCommand(current: Page) {
        if (page !== current || current.pending != null) return
        if (current.closing) {
            current.resumeJob?.complete(Result.failure(G2EvenHubException("cancelled"))); current.resumeJob = null
            current.closeJob?.let { job -> current.closeJob = null; issue(current, job) }
        } else {
            val resume = current.resumeJob
            if (resume != null) { current.resumeJob = null; issue(current, resume) } else armHeartbeat(current)
        }
    }
    private fun armHeartbeat(current: Page) {
        if (page !== current || !current.active || current.closing || current.pending != null) return
        current.cancelTick?.invoke()
        val tickGeneration = ++current.tickGeneration
        current.cancelTick = schedule(maxOf(0, current.leaseDueAt - now())) {
            synchronized(this) {
                if (page !== current || current.tickGeneration != tickGeneration || !current.active ||
                    current.closing || current.pending != null) return@synchronized
                current.cancelTick = null; sendHeartbeat(current)
            }
        }
    }
    private fun sendHeartbeat(current: Page) {
        current.heartbeatCount = current.heartbeatCount % UINT32 + 1
        issue(current, Job(G2EvenHubCodec.prepare("heartbeat", emptyMap(), current.session, count = current.heartbeatCount)))
    }

    private fun issue(current: Page, job: Job, announceOpening: Boolean = false) {
        if (page !== current) return
        val number = ++commandSequence
        val request = job.requests[job.index]
        val pending = Pending(job, request, ((number - 1) % 255 + 1).toInt(), (number and 255).toInt(),
            if (request.command.response < 0) current.oneWayLease else BleCommandLease())
        val packets = G2EvenHubCodec.packets(pending.request, pending.magic, pending.sync)
        // Reserve pending ownership before any host event, scheduler or BLE callback can reenter.
        current.pending = pending
        if (pending.request.command.response >= 0) pending.cancelTimeout = schedule(3000) {
            synchronized(this) {
                if (page === current && current.pending === pending) failCommand(current, pending, "timedOut")
            }
        }
        if (announceOpening) emit(current, "opening")
        if (page !== current || current.pending !== pending) return
        try {
            for (packet in packets) {
                if (page !== current || current.pending !== pending) return
                send(current.owner, packet, pending.writeLease)
            }
        } catch (_: Exception) {
            if (page === current && current.pending === pending) finish(current, "failed", "writeFailed")
            return
        }
        if (pending.request.command.response < 0 && page === current && current.pending === pending) {
            // These firmware responses have no ACK: report queue admission, never device success.
            current.pending = null
            job.complete(Result.success(result(current, pending.request, mapOf("acknowledged" to false, "submitted" to true))))
            continueAfterCommand(current)
        }
    }

    private fun failCommand(current: Page, pending: Pending, failure: String) {
        if (page !== current || current.pending !== pending) return
        val job = pending.job
        pending.writeLease.invalidate()
        if (pending.request.command == G2EvenHubCodec.Command.IMAGE && !current.closing && job.retries == 0 && job.retryImage != null) {
            pending.cancelTimeout?.invoke(); current.pending = null
            job.retries++; job.index = 0; job.requests = job.retryImage.invoke()
            // A retry always restarts the whole image with a fresh native map session and magic.
            if (now() >= current.leaseDueAt) { current.resumeJob = job; sendHeartbeat(current) } else issue(current, job)
        } else finish(current, "failed", failure)
    }
    private fun finish(current: Page, phase: String, failure: String? = null, commandFailure: String? = null) {
        if (page !== current) return
        page = null
        stopAudio(current)
        current.oneWayLease.invalidate(); current.pending?.writeLease?.invalidate()
        current.cancelTick?.invoke(); current.pending?.cancelTimeout?.invoke()
        val jobs = listOfNotNull(current.pending?.job, current.closeJob, current.resumeJob).distinct()
        current.cancelTick = null; current.pending = null; current.closeJob = null; current.resumeJob = null
        val error = G2EvenHubException(commandFailure ?: failure ?: "pageClosed")
        emit(current, phase, failure)
        // Terminal events precede callbacks that may synchronously create a replacement page.
        jobs.forEach { it.complete(Result.failure(error)) }
    }
    private fun result(current: Page, request: G2EvenHubCodec.Request, details: Map<String, Any>): Map<String, Any> =
        mapOf("command" to request.command.logicalName, "operation" to current.operation,
            "pageSessionId" to current.session, "heartbeatCount" to current.confirmedHeartbeatCount) + details
    private fun emit(current: Page, phase: String, failure: String? = null, event: String? = null,
                     details: Map<String, Any> = emptyMap(), source: G2EvenHubOwner = current.owner,
                     audioLease: Any? = null) {
        onEvent(G2EvenHubEvent(source, current.operation, phase, current.confirmedHeartbeatCount, event, failure,
            mapOf("pageSessionId" to current.session) + details).also { it.audioLease = audioLease })
    }
    private fun nextImageSession(): Long { imageSequence = imageSequence % UINT32 + 1; return imageSequence }
    companion object {
        private const val UINT32 = 0xffffffffL
        // Process scope survives SDK reset/recreation. Random seed also avoids predictable reuse after restart.
        private val sessions = AtomicLong(SecureRandom().nextInt().toLong() and UINT32)
        private fun nextSession(): Long {
            // AtomicLong functional update methods require API24; CAS preserves the API21 contract.
            while (true) {
                val previous = sessions.get()
                val next = previous % UINT32 + 1
                if (sessions.compareAndSet(previous, next)) return next
            }
        }
    }
}
