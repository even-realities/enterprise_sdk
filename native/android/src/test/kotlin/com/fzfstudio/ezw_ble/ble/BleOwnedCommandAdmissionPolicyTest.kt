package com.fzfstudio.ezw_ble.ble

import org.junit.Assert.*
import org.junit.Test

/** READY no longer has a connection-admission owner; exact retained GATT identity remains valid. */
class BleOwnedCommandAdmissionPolicyTest {
    private val current = BleBusinessConnectionAttempt("RIGHT", 10, 20)

    @Test fun retainedBusinessIdentityCanWriteAfterConnectionAdmissionWasReleased() {
        assertTrue(BleOwnedCommandAdmissionPolicy.canEnqueue(current, current,
            gattReady = true, systemDisconnected = false, leaseActive = true))
    }

    @Test fun staleOrUnreadyPhysicalIdentityAndRetiredLogicalLeaseCannotWrite() {
        for (live in listOf(null, current.copy(sessionGeneration = 11), current.copy(attemptGeneration = 21),
            current.copy(uuid = "LEFT"))) {
            assertFalse(BleOwnedCommandAdmissionPolicy.canEnqueue(current, live,
                gattReady = true, systemDisconnected = false, leaseActive = true))
        }
        assertFalse(BleOwnedCommandAdmissionPolicy.canEnqueue(current, current, false, false, true))
        assertFalse(BleOwnedCommandAdmissionPolicy.canEnqueue(current, current, true, true, true))
        assertFalse(BleOwnedCommandAdmissionPolicy.canEnqueue(current, current, true, false, false))
        val lease = BleCommandLease(); assertTrue(lease.isActive); lease.invalidate(); assertFalse(lease.isActive)
    }
}
