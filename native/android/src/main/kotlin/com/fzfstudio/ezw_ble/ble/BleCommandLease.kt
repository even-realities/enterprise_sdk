package com.fzfstudio.ezw_ble.ble

import java.util.concurrent.atomic.AtomicBoolean

/** Lock-free command lifetime: BLE queues read it without reentering the business coordinator. */
class BleCommandLease {
    private val valid = AtomicBoolean(true)
    val isActive: Boolean get() = valid.get()
    fun invalidate() { valid.set(false) }
}

/** Business READY releases admission; live callback identity remains the authority for later writes. */
internal object BleOwnedCommandAdmissionPolicy {
    fun canEnqueue(expected: BleBusinessConnectionAttempt, live: BleBusinessConnectionAttempt?,
                   gattReady: Boolean, systemDisconnected: Boolean, leaseActive: Boolean): Boolean =
        expected.uuid.isNotBlank() && expected.sessionGeneration > 0 && expected.attemptGeneration > 0 &&
            expected == live && gattReady && !systemDisconnected && leaseActive
}
