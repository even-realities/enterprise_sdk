package com.evenrealities.devices

import org.junit.Assert.*
import org.junit.Test

class G2EnterpriseRetryBudgetTest {
    private val old = EnterpriseAttempt(11, 12)
    private val fresh = EnterpriseAttempt(13, 14)

    @Test fun onlyFourSixAndResultTimeoutGetOneRetryAfterExactTerminal() {
        for ((result, reason) in listOf(4 to "firmware_rejected", 6 to "firmware_rejected",
            -1 to "result_timeout")) {
            val budget = G2EnterpriseRetryBudget().apply { begin(1, "left", "right") }
            assertEquals(EnterpriseRetryAction.WAIT_FOR_TERMINAL,
                budget.onResult(1, "left", old, result, reason))
            assertFalse(budget.mayUseBusinessChannel(1, "left", old))
            assertEquals(EnterpriseRetryAction.IGNORE,
                budget.onTerminal(1, "left", EnterpriseAttempt(11, 13)))
            assertEquals(EnterpriseRetryAction.RECONNECT, budget.onTerminal(1, "left", old))
            assertEquals(EnterpriseRetryAction.IGNORE, budget.onTerminal(1, "left", old))
            assertEquals(EnterpriseRetryAction.IGNORE,
                budget.onResult(1, "left", old, 0, "authorized"))
            assertFalse(budget.mayUseBusinessChannel(1, "left", old))
            assertTrue(budget.mayUseBusinessChannel(1, "left", fresh))
            assertEquals(EnterpriseRetryAction.FAIL,
                budget.onResult(1, "left", fresh, result, reason))
        }
    }

    @Test fun proofRejectionAndOtherErrorsAreTerminalWithoutRetry() {
        for (result in listOf(1, 2, 3, 5, 99, -1)) {
            val budget = G2EnterpriseRetryBudget().apply { begin(1, "left", "right") }
            assertEquals(EnterpriseRetryAction.FAIL,
                budget.onResult(1, "left", old, result, "firmware_rejected"))
            assertFalse(budget.mayUseBusinessChannel(1, "left", old))
            assertEquals(EnterpriseRetryAction.IGNORE, budget.onTerminal(1, "left", old))
        }
    }

    @Test fun oldPairEventsAndDuplicateCallbacksCannotRestartReplacement() {
        val budget = G2EnterpriseRetryBudget()
        budget.begin(1, "left", "right")
        budget.onResult(1, "left", old, 4, "firmware_rejected")
        budget.cancel()
        budget.begin(2, "left", "right")
        assertEquals(EnterpriseRetryAction.IGNORE, budget.onTerminal(1, "left", old))
        assertEquals(EnterpriseRetryAction.IGNORE,
            budget.onResult(1, "left", old, 4, "firmware_rejected"))
        assertTrue(budget.mayUseBusinessChannel(2, "left", fresh))
        assertEquals(EnterpriseRetryAction.WAIT_FOR_TERMINAL,
            budget.onResult(2, "left", fresh, 6, "firmware_rejected"))
        assertEquals(EnterpriseRetryAction.IGNORE,
            budget.onResult(2, "left", fresh, 6, "firmware_rejected"))
    }

    @Test fun legsHaveIndependentBudgets() {
        val budget = G2EnterpriseRetryBudget().apply { begin(1, "left", "right") }
        assertEquals(EnterpriseRetryAction.WAIT_FOR_TERMINAL,
            budget.onResult(1, "left", old, 4, "firmware_rejected"))
        assertEquals(EnterpriseRetryAction.WAIT_FOR_TERMINAL,
            budget.onResult(1, "right", old, 6, "firmware_rejected"))
        assertEquals(EnterpriseRetryAction.RECONNECT, budget.onTerminal(1, "left", old))
        assertEquals(EnterpriseRetryAction.RECONNECT, budget.onTerminal(1, "right", old))
    }

    @Test fun reentrantPairReplacementCannotSpendOrReconnectTheNewOwner() {
        val budget = G2EnterpriseRetryBudget().apply { begin(1, "left", "right") }
        assertEquals(EnterpriseRetryAction.WAIT_FOR_TERMINAL,
            budget.onResult(1, "left", old, 4, "firmware_rejected"))
        // A host callback can synchronously replace the pair before the old terminal is delivered.
        budget.cancel()
        budget.begin(3, "left", "right")
        assertEquals(EnterpriseRetryAction.IGNORE, budget.onTerminal(1, "left", old))
        assertEquals(EnterpriseRetryAction.IGNORE,
            budget.onResult(1, "left", old, 6, "firmware_rejected"))
        assertEquals(EnterpriseRetryAction.WAIT_FOR_TERMINAL,
            budget.onResult(3, "left", fresh, 4, "firmware_rejected"))
    }
}
