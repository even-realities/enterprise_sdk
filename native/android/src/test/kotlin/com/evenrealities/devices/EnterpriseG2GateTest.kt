package com.evenrealities.devices

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class EnterpriseG2GateTest {
    private val sn = "S211GABE130301"
    private val bundle = EnterpriseLicenseBundle.fromProofs("demo", listOf(
        EnterpriseBindingProof("demo", sn, Base64.getEncoder().encodeToString(ByteArray(64)))))

    private class FakePort : EnterpriseEndpointPort {
        val writes = mutableMapOf<String, ByteArray>()
        val business = mutableMapOf<String, (Boolean) -> Unit>()
        var subscriptions = 0
        override fun subscribeAuthorization(attempt: EndpointAttempt, completion: (Boolean) -> Unit) {
            subscriptions++
            completion(true)
        }
        override fun writeAuthorization(attempt: EndpointAttempt, payload: ByteArray, completion: (Boolean) -> Unit) {
            writes[attempt.endpointId] = payload
            completion(true)
        }
        override fun subscribeBusiness(attempt: EndpointAttempt, completion: (Boolean) -> Unit) { completion(true) }
        override fun authenticateBusiness(attempt: EndpointAttempt, completion: (Boolean) -> Unit) {
            business[attempt.endpointId] = completion
        }
    }

    @Test fun readyRequiresBoth8403AndBusinessAuth() {
        val port = FakePort()
        val events = mutableListOf<EnterpriseAuthEvent>()
        val gate = EnterpriseG2Gate(bundle, port, events::add)
        val tokens = gate.beginG2(sn, "left", "right", 1, 1)
        assertEquals(2, port.subscriptions)
        assertArrayEquals(port.writes["left"], port.writes["right"])
        gate.onFirmwareResult(tokens[0], 0)
        port.business["left"]!!(true)
        assertFalse(events.any { it.phase == EnterpriseAuthPhase.G2_READY })
        gate.onFirmwareResult(tokens[1], 0)
        assertFalse(events.any { it.phase == EnterpriseAuthPhase.G2_READY })
        port.business["right"]!!(true)
        assertEquals(1, events.count { it.phase == EnterpriseAuthPhase.G2_READY })
    }

    @Test fun oldGenerationAndFailedLegCannotPromotePair() {
        val port = FakePort()
        val events = mutableListOf<EnterpriseAuthEvent>()
        val gate = EnterpriseG2Gate(bundle, port, events::add)
        val old = gate.beginG2(sn, "left", "right", 1, 1)
        val fresh = gate.beginG2(sn, "left", "right", 2, 1)
        gate.onFirmwareResult(old[0], 0)
        gate.onTimeout(old[1])
        assertTrue(port.business.isEmpty())
        gate.onFirmwareResult(fresh[0], 3)
        assertEquals(EnterpriseAuthFailure.INVALID_SIGNATURE, events.last().failure)
        gate.onFirmwareResult(fresh[1], 0)
        assertTrue(port.business.isEmpty())
        assertFalse(events.any { it.phase == EnterpriseAuthPhase.G2_READY })
    }

    @Test fun missingProofDoesNoBleIo() {
        val port = FakePort()
        val events = mutableListOf<EnterpriseAuthEvent>()
        val gate = EnterpriseG2Gate(bundle, port, events::add)
        assertTrue(gate.beginG2("unknown", "left", "right", 1, 1).isEmpty())
        assertEquals(0, port.subscriptions)
        assertEquals(EnterpriseAuthFailure.MISSING_PROOF, events.single().failure)
    }

    @Test fun multiSerialAdmissionWritesOnlyTheMatchedProofToBothLegs() {
        val secondSn = "S211GABE130302"
        val firstSignature = Base64.getEncoder().encodeToString(ByteArray(64))
        val secondSignature = Base64.getEncoder().encodeToString(ByteArray(64) { 42 })
        val licenses = EnterpriseLicenseBundle.fromJsonString("""
            {"format":"enterprise-sdk-license-v1","app_id":"demo","licenses":[
              {"sn":"$sn","signature":"$firstSignature"},
              {"sn":"$secondSn","signature":"$secondSignature"}
            ]}
        """.trimIndent())
        val port = FakePort()
        val gate = EnterpriseG2Gate(licenses, port) {}
        val tokens = gate.beginG2(secondSn, "left", "right", 1, 1)
        assertTrue(tokens.all { it.sn == secondSn })
        val expected = EnterpriseAuthV1Encoder.encode(licenses.findProof(secondSn)!!)
        assertArrayEquals(expected, port.writes["left"])
        assertArrayEquals(expected, port.writes["right"])
        assertFalse(expected.contentEquals(EnterpriseAuthV1Encoder.encode(licenses.findProof(sn)!!)))

        // An issued proof for another SN never authorizes an unlisted/case-folded target.
        for (target in listOf("S211GABE130303", secondSn.lowercase(), " $secondSn", "$secondSn ")) {
            val rejectedPort = FakePort()
            val events = mutableListOf<EnterpriseAuthEvent>()
            val rejected = EnterpriseG2Gate(licenses, rejectedPort, events::add)
            assertTrue(rejected.beginG2(target, "left", "right", 2, 1).isEmpty())
            assertEquals(0, rejectedPort.subscriptions)
            assertTrue(rejectedPort.writes.isEmpty())
            assertEquals(EnterpriseAuthFailure.MISSING_PROOF, events.single().failure)
        }
    }

    @Test fun everyFirmwareFailureCodeStaysDistinct() {
        val expected = mapOf(
            1 to EnterpriseAuthFailure.INVALID_FORMAT,
            2 to EnterpriseAuthFailure.SN_MISMATCH,
            3 to EnterpriseAuthFailure.INVALID_SIGNATURE,
            4 to EnterpriseAuthFailure.INTERNAL_ERROR,
            6 to EnterpriseAuthFailure.INVALID_CONNECTION_STATE,
            255 to EnterpriseAuthFailure.UNKNOWN_RESULT,
        )
        expected.forEach { (code, failure) ->
            val events = mutableListOf<EnterpriseAuthEvent>()
            val gate = EnterpriseG2Gate(bundle, FakePort(), events::add)
            val token = gate.beginG2(sn, "left", "right", 1, 1).first()
            gate.onFirmwareResult(token, code)
            assertEquals(failure, events.last().failure)
            assertEquals(code, events.last().resultCode)
        }
    }
}
