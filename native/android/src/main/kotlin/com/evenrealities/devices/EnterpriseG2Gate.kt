package com.evenrealities.devices

/** A connection attempt is owned by both native BLE generations and a gate generation. */
data class EndpointAttempt(
    val sn: String,
    val endpointId: String,
    val sessionGeneration: Long,
    val attemptGeneration: Long,
    val gateGeneration: Long,
)

enum class EnterpriseAuthPhase {
    AUTH_SUBSCRIBING, AUTH_WRITING, AUTH_WAITING, BUSINESS_SUBSCRIBING,
    BUSINESS_AUTHENTICATING, ENDPOINT_READY, G2_READY, FAILED,
}

enum class EnterpriseAuthFailure {
    MISSING_PROOF, INVALID_FORMAT, SN_MISMATCH, INVALID_SIGNATURE,
    INTERNAL_ERROR, INVALID_CONNECTION_STATE, TIMEOUT, TRANSPORT_ERROR,
    UNKNOWN_RESULT, DISCONNECTED,
}

data class EnterpriseAuthEvent(
    val attempt: EndpointAttempt?,
    val phase: EnterpriseAuthPhase,
    val failure: EnterpriseAuthFailure? = null,
    val resultCode: Int? = null,
)

/**
 * The Android GATT implementation owns callbacks and must use Write Request for 8403.
 * authenticateBusiness completes only for the matching 8401 AuthMgr / 8402 secAuth reply.
 */
interface EnterpriseEndpointPort {
    fun subscribeAuthorization(attempt: EndpointAttempt, completion: (Boolean) -> Unit)
    fun writeAuthorization(attempt: EndpointAttempt, payload: ByteArray, completion: (Boolean) -> Unit)
    fun subscribeBusiness(attempt: EndpointAttempt, completion: (Boolean) -> Unit)
    fun authenticateBusiness(attempt: EndpointAttempt, completion: (Boolean) -> Unit)
}

/**
 * Owns the two-leg authorization sequence. Every transition checks the captured token;
 * old BLE callbacks cannot promote or fail a newer attempt. A missing proof does no I/O.
 */
class EnterpriseG2Gate(
    licenseBundle: EnterpriseLicenseBundle,
    private val port: EnterpriseEndpointPort,
    private val onEvent: (EnterpriseAuthEvent) -> Unit,
) {
    private enum class Step {
        AUTH_SUBSCRIBING, AUTH_WRITING, AUTH_WAITING, BUSINESS_SUBSCRIBING,
        BUSINESS_AUTHENTICATING, READY,
    }

    private class Leg(val token: EndpointAttempt) {
        var step = Step.AUTH_SUBSCRIBING
        var earlyResult: Int? = null
    }

    private class Session(
        val proof: EnterpriseBindingProof,
        val legs: Map<String, Leg>,
    )

    private var licenses = licenseBundle
    private var nextGeneration = 0L
    private var current: Session? = null

    /** New proofs apply only to the next attempt, never a partly authorized pair. */
    @Synchronized
    fun updateLicenses(bundle: EnterpriseLicenseBundle) { licenses = bundle }

    /** Call after native BLE has established both endpoint identities and encrypted links. */
    @Synchronized
    fun beginG2(
        sn: String,
        leftEndpointId: String,
        rightEndpointId: String,
        sessionGeneration: Long,
        attemptGeneration: Long,
    ): List<EndpointAttempt> {
        require(leftEndpointId.isNotBlank() && rightEndpointId.isNotBlank() && leftEndpointId != rightEndpointId)
        require(sessionGeneration > 0 && attemptGeneration > 0)
        nextGeneration++
        current = null // This invalidates all callbacks from the prior pair before new I/O.
        val proof = licenses.findProof(sn)
        if (proof == null) {
            onEvent(EnterpriseAuthEvent(null, EnterpriseAuthPhase.FAILED, EnterpriseAuthFailure.MISSING_PROOF))
            return emptyList()
        }
        val tokens = listOf(leftEndpointId, rightEndpointId).map { endpoint ->
            EndpointAttempt(sn, endpoint, sessionGeneration, attemptGeneration, nextGeneration)
        }
        current = Session(proof, tokens.associate { it.endpointId to Leg(it) })
        tokens.forEach { token ->
            if (current?.legs?.get(token.endpointId)?.token == token) startAuthorization(token)
        }
        return tokens
    }

    /** A 8403 notification may beat the GATT write callback; buffer it until Write Response. */
    @Synchronized
    fun onFirmwareResult(token: EndpointAttempt, resultCode: Int) {
        val leg = currentLeg(token) ?: return
        when (leg.step) {
            Step.AUTH_WRITING -> {
                if (leg.earlyResult == null) leg.earlyResult = resultCode
                else fail(token, EnterpriseAuthFailure.TRANSPORT_ERROR)
            }
            Step.AUTH_WAITING -> acceptFirmwareResult(token, resultCode)
            else -> Unit
        }
    }

    /** The transport must invoke this for its captured timer, disconnection, or GATT failure. */
    @Synchronized
    fun onTimeout(token: EndpointAttempt) { if (currentLeg(token) != null) fail(token, EnterpriseAuthFailure.TIMEOUT) }

    @Synchronized
    fun onDisconnected(token: EndpointAttempt) { if (currentLeg(token) != null) fail(token, EnterpriseAuthFailure.DISCONNECTED) }

    @Synchronized
    fun cancel() { nextGeneration++; current = null }

    private fun currentLeg(token: EndpointAttempt): Leg? = current?.legs?.get(token.endpointId)
        ?.takeIf { it.token == token }

    private fun startAuthorization(token: EndpointAttempt) {
        onEvent(EnterpriseAuthEvent(token, EnterpriseAuthPhase.AUTH_SUBSCRIBING))
        invokePort(token) { port.subscribeAuthorization(token) { success -> onAuthSubscribed(token, success) } }
    }

    @Synchronized
    private fun onAuthSubscribed(token: EndpointAttempt, success: Boolean) {
        val leg = currentLeg(token)?.takeIf { it.step == Step.AUTH_SUBSCRIBING } ?: return
        if (!success) return fail(token, EnterpriseAuthFailure.TRANSPORT_ERROR)
        leg.step = Step.AUTH_WRITING
        onEvent(EnterpriseAuthEvent(token, EnterpriseAuthPhase.AUTH_WRITING))
        val payload = EnterpriseAuthV1Encoder.encode(current!!.proof)
        invokePort(token) { port.writeAuthorization(token, payload) { ok -> onWriteAck(token, ok) } }
    }

    @Synchronized
    private fun onWriteAck(token: EndpointAttempt, success: Boolean) {
        val leg = currentLeg(token)?.takeIf { it.step == Step.AUTH_WRITING } ?: return
        if (!success) return fail(token, EnterpriseAuthFailure.TRANSPORT_ERROR)
        leg.step = Step.AUTH_WAITING
        onEvent(EnterpriseAuthEvent(token, EnterpriseAuthPhase.AUTH_WAITING))
        leg.earlyResult?.let { result ->
            leg.earlyResult = null
            acceptFirmwareResult(token, result)
        }
    }

    private fun acceptFirmwareResult(token: EndpointAttempt, resultCode: Int) {
        val leg = currentLeg(token)?.takeIf { it.step == Step.AUTH_WAITING } ?: return
        val failure = when (resultCode) {
            0 -> null
            1 -> EnterpriseAuthFailure.INVALID_FORMAT
            2 -> EnterpriseAuthFailure.SN_MISMATCH
            3 -> EnterpriseAuthFailure.INVALID_SIGNATURE
            4 -> EnterpriseAuthFailure.INTERNAL_ERROR
            6 -> EnterpriseAuthFailure.INVALID_CONNECTION_STATE
            else -> EnterpriseAuthFailure.UNKNOWN_RESULT
        }
        if (failure != null) return fail(token, failure, resultCode)
        leg.step = Step.BUSINESS_SUBSCRIBING
        onEvent(EnterpriseAuthEvent(token, EnterpriseAuthPhase.BUSINESS_SUBSCRIBING, resultCode = resultCode))
        invokePort(token) { port.subscribeBusiness(token) { success -> onBusinessSubscribed(token, success) } }
    }

    @Synchronized
    private fun onBusinessSubscribed(token: EndpointAttempt, success: Boolean) {
        val leg = currentLeg(token)?.takeIf { it.step == Step.BUSINESS_SUBSCRIBING } ?: return
        if (!success) return fail(token, EnterpriseAuthFailure.TRANSPORT_ERROR)
        leg.step = Step.BUSINESS_AUTHENTICATING
        onEvent(EnterpriseAuthEvent(token, EnterpriseAuthPhase.BUSINESS_AUTHENTICATING))
        invokePort(token) { port.authenticateBusiness(token) { ok -> onBusinessResult(token, ok) } }
    }

    @Synchronized
    private fun onBusinessResult(token: EndpointAttempt, success: Boolean) {
        val leg = currentLeg(token)?.takeIf { it.step == Step.BUSINESS_AUTHENTICATING } ?: return
        if (!success) return fail(token, EnterpriseAuthFailure.TRANSPORT_ERROR)
        leg.step = Step.READY
        onEvent(EnterpriseAuthEvent(token, EnterpriseAuthPhase.ENDPOINT_READY))
        if (current?.legs?.values?.all { it.step == Step.READY } == true) {
            onEvent(EnterpriseAuthEvent(token, EnterpriseAuthPhase.G2_READY))
        }
    }

    private fun invokePort(token: EndpointAttempt, action: () -> Unit) {
        try { action() } catch (_: Exception) { fail(token, EnterpriseAuthFailure.TRANSPORT_ERROR) }
    }

    private fun fail(token: EndpointAttempt, failure: EnterpriseAuthFailure, resultCode: Int? = null) {
        if (currentLeg(token) == null) return
        current = null // Failure closes the entire pair; later success from its sibling is stale.
        onEvent(EnterpriseAuthEvent(token, EnterpriseAuthPhase.FAILED, failure, resultCode))
    }
}
