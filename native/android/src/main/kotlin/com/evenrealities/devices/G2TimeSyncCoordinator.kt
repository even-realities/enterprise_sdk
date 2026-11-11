package com.evenrealities.devices

/** Dart targets the current right endpoint; prevent duplicate mirrored commands from the left. */
internal fun requireG2TimeSyncRightEndpoint(requested: String, right: String): String {
    require(requested.isNotBlank() && requested.equals(right, ignoreCase = true)) {
        "TimeSync requires the current G2 right endpoint"
    }
    return right
}

/** One in-flight native TimeSync command, owned by an authorized physical attempt. */
class G2TimeSyncCoordinator(
    private val send: (endpoint: String, packet: ByteArray, session: Long, attempt: Long) -> Unit,
    private val onResult: (endpoint: String, generation: Long, operation: Long, success: Boolean) -> Unit,
) {
    private data class Pending(
        val endpoint: String,
        val generation: Long,
        val session: Long,
        val attempt: Long,
        val operation: Long,
        val magic: Int,
    )

    private var sequence = 0L
    private var pending: Pending? = null

    /** The caller has already checked whole-pair READY and exact native admission. */
    @Synchronized
    fun begin(
        endpoint: String,
        generation: Long,
        session: Long,
        attempt: Long,
        epochSeconds: Long,
        offsetMinutes: Int,
        syncBoth: Boolean = true,
    ): Long {
        require(endpoint.isNotBlank() && generation > 0 && session > 0 && attempt > 0)
        check(pending == null) { "G2 TimeSync already in flight" }
        val operation = ++sequence
        // The observed enterprise firmware echoes only the low magic byte
        // despite protobuf's IS16 declaration. Allocate nonzero byte values,
        // leaving operation/session/attempt identity full-width and matching
        // the received magic exactly; zero may be omitted by proto3 encoding.
        val magic = ((operation - 1) % 255 + 1).toInt()
        val packet = G2TimeSyncCodec.request(epochSeconds, offsetMinutes,
            syncId = (operation and 0xff).toInt(), magic = magic, syncBoth = syncBoth)
        val owner = Pending(endpoint, generation, session, attempt, operation, magic)
        pending = owner
        try {
            send(endpoint, packet, session, attempt)
        } catch (error: Exception) {
            if (pending === owner) pending = null
            throw error
        }
        return operation
    }

    /** Device sync is independent; endpoint, native attempt, and magic own the response. */
    @Synchronized
    fun onReceive(endpoint: String, session: Long, attempt: Long, packet: ByteArray) {
        val owner = pending?.takeIf {
            it.endpoint == endpoint && it.session == session && it.attempt == attempt
        } ?: return
        when (G2TimeSyncCodec.response(packet, owner.magic)) {
            G2TimeSyncCodec.Response.IGNORED -> Unit
            G2TimeSyncCodec.Response.SUCCESS -> finish(owner, true)
            G2TimeSyncCodec.Response.REJECTED -> finish(owner, false)
        }
    }

    /** Captured timers and old session results must never complete a replacement command. */
    @Synchronized
    fun onTimeout(operation: Long) {
        val owner = pending?.takeIf { it.operation == operation } ?: return
        finish(owner, false)
    }

    /** OTA cannot take away a business command's pending firmware reply. */
    @Synchronized
    internal fun isPending(): Boolean = pending != null

    @Synchronized
    fun cancel() { pending = null }

    private fun finish(owner: Pending, success: Boolean) {
        if (pending !== owner) return
        pending = null
        onResult(owner.endpoint, owner.generation, owner.operation, success)
    }
}
