package com.fzfstudio.ezw_ble.ble

import java.util.Locale
import java.util.UUID

/** Engine-local credentials, copied on both boundaries so reconnects cannot mutate an active attempt. */
internal object EnterpriseAuth {
    data class Credential(val payload: ByteArray, val pairGeneration: Long)
    val service: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e8450")
    val characteristic: UUID = UUID.fromString("00002760-08c2-11e1-9073-0e8ac72e8403")
    /** Exact failed pairs suppress generic supervisor scheduling, including delayed teardown. */
    private val failedAttempts = mutableMapOf<String, Pair<Long, Long>>()
    @Synchronized fun markFailed(uuid: String, session: Long, attempt: Long) {
        if (session > 0 && attempt > 0) failedAttempts[uuid.uppercase(Locale.ROOT)] = session to attempt
    }
    @Synchronized fun hasFailed(uuid: String, session: Long, attempt: Long): Boolean =
        failedAttempts[uuid.uppercase(Locale.ROOT)] == (session to attempt)
    private val payloads = mutableMapOf<String, Credential>()
    @Synchronized fun set(uuid: String, payload: ByteArray?, pairGeneration: Long = 0L) {
        val key = uuid.uppercase(Locale.ROOT)
        if (payload == null) payloads.remove(key)
        else payloads[key] = Credential(payload.copyOf(), pairGeneration)
    }
    /** Snapshot owner with bytes so delayed firmware results cannot attach to a newer pair. */
    @Synchronized fun getCredential(uuid: String): Credential? =
        payloads[uuid.uppercase(Locale.ROOT)]?.let { Credential(it.payload.copyOf(), it.pairGeneration) }
    @Synchronized fun clear() { payloads.clear(); failedAttempts.clear() }
    /** Reject malformed frames before GATT; firmware remains the signature authority. */
    fun validPayload(bytes: ByteArray): Boolean {
        if (bytes.size < 68 || bytes[0].toInt() != 1) return false
        val appLength = bytes[1].toInt() and 255
        if (appLength !in 1..64 || bytes.size <= 2 + appLength) return false
        val snLength = bytes[2 + appLength].toInt() and 255
        return snLength in listOf(0, 14) && bytes.size == 3 + appLength + snLength + 64
    }
}
