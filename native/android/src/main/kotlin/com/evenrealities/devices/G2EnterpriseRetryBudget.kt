package com.evenrealities.devices

/** One physical 8403 authorization attempt, frozen before asynchronous terminal delivery. */
data class EnterpriseAttempt(val session: Long, val attempt: Long) {
    init { require(session > 0 && attempt > 0) }
}

enum class EnterpriseRetryAction { IGNORE, WAIT_FOR_TERMINAL, RECONNECT, FAIL }

/**
 * Pair-scoped 8403 retry budget. Only result 4/6 and the 8403 result timeout can consume it.
 * Reconnect is released only after the exact failed GATT attempt has emitted its terminal state.
 */
class G2EnterpriseRetryBudget {
    private data class Leg(
        var retryUsed: Boolean = false,
        var failedAttempt: EnterpriseAttempt? = null,
        var pendingTerminal: EnterpriseAttempt? = null,
        var authorizedAttempt: EnterpriseAttempt? = null,
    )
    private data class Owner(val generation: Long, val legs: Map<String, Leg>)
    private var owner: Owner? = null

    @Synchronized
    fun begin(generation: Long, left: String, right: String) {
        require(generation > 0 && left.isNotBlank() && right.isNotBlank() && left != right)
        owner = Owner(generation, mapOf(left to Leg(), right to Leg()))
    }

    @Synchronized
    fun cancel() { owner = null }

    /** A stale failure or success from the retired attempt cannot consume the new budget. */
    @Synchronized
    fun onResult(generation: Long, endpoint: String, identity: EnterpriseAttempt,
                 result: Int, reason: String): EnterpriseRetryAction {
        val leg = owner?.takeIf { it.generation == generation }?.legs?.get(endpoint)
            ?: return EnterpriseRetryAction.IGNORE
        if (identity == leg.failedAttempt || leg.pendingTerminal != null) return EnterpriseRetryAction.IGNORE
        if (identity == leg.authorizedAttempt) return EnterpriseRetryAction.IGNORE
        if (result == 0) {
            leg.authorizedAttempt = identity
            return EnterpriseRetryAction.IGNORE
        }
        val retryable = result == 4 || result == 6 ||
            (result == -1 && reason == "result_timeout")
        if (!retryable || leg.retryUsed) {
            // A terminal verdict cannot later open 8401 if connectFinish was already queued.
            leg.failedAttempt = identity
            return EnterpriseRetryAction.FAIL
        }
        leg.retryUsed = true
        leg.failedAttempt = identity
        leg.pendingTerminal = identity
        return EnterpriseRetryAction.WAIT_FOR_TERMINAL
    }

    /** The native GATT teardown precedes its terminal event; reconnect uses the same proof. */
    @Synchronized
    fun onTerminal(generation: Long, endpoint: String, identity: EnterpriseAttempt): EnterpriseRetryAction {
        val leg = owner?.takeIf { it.generation == generation }?.legs?.get(endpoint)
            ?: return EnterpriseRetryAction.IGNORE
        if (leg.pendingTerminal != identity) return EnterpriseRetryAction.IGNORE
        leg.pendingTerminal = null
        return EnterpriseRetryAction.RECONNECT
    }

    @Synchronized
    fun mayUseBusinessChannel(generation: Long, endpoint: String,
                              identity: EnterpriseAttempt): Boolean {
        val leg = owner?.takeIf { it.generation == generation }?.legs?.get(endpoint) ?: return false
        return leg.pendingTerminal == null && identity != leg.failedAttempt
    }
}
