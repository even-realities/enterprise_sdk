package com.evenrealities.devices

import com.fzfstudio.ezw_ble.ble.EnterpriseAuth
import org.junit.Assert.*
import org.junit.Test

class EnterpriseAuthCredentialTest {
    @Test fun proofAndPairGenerationAreCopiedTogetherAtBothBoundaries() {
        val input = byteArrayOf(1, 2, 3)
        EnterpriseAuth.set("aa:bb", input, 7)
        input[0] = 9
        val snapshot = EnterpriseAuth.getCredential("AA:BB")!!
        assertArrayEquals(byteArrayOf(1, 2, 3), snapshot.payload)
        assertEquals(7L, snapshot.pairGeneration)
        snapshot.payload[1] = 9
        assertArrayEquals(byteArrayOf(1, 2, 3), EnterpriseAuth.getCredential("aa:bb")!!.payload)
        EnterpriseAuth.set("aa:bb", null)
        assertNull(EnterpriseAuth.getCredential("aa:bb"))
    }
}
