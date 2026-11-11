package com.evenrealities.devices

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

class EnterpriseLicenseBundleTest {
    private val sn = "S211GABE130301"
    private val signature = Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() })

    @Test fun exactShared8403Vector() {
        val proof = EnterpriseBindingProof(" Demo ", sn, signature)
        val actual = EnterpriseAuthV1Encoder.encode(proof).joinToString("") { "%02x".format(it) }
        val contract = JSONObject(File("../protocol/contract.json").readText())
        assertEquals(contract.getJSONObject("vectors")
            .getString("enterprise_auth_v1_app_demo_sn_130301_signature_0_to_63_hex"), actual)
        assertEquals(87, actual.length / 2)
    }

    @Test fun fullBundleValidationAndExactLookup() {
        val bundle = EnterpriseLicenseBundle.fromJsonString(
            """{"schema_version":1,"app_id":" Demo ","proofs":{"$sn":"$signature"}}""")
        assertNotNull(bundle.findProof(sn))
        assertNull(bundle.findProof(sn.lowercase()))
        val copy = bundle.findProof(sn)!!.signatureBytes
        copy[0] = 99
        assertEquals(0, bundle.findProof(sn)!!.signatureBytes[0].toInt())
    }

    @Test fun portalBundleSelectsExactSerialRatherThanFirstLicense() {
        val secondSn = "S211GABE130302"
        val secondSignature = Base64.getEncoder().encodeToString(ByteArray(64) { (63 - it).toByte() })
        val bundle = EnterpriseLicenseBundle.fromJsonString("""
            {"format":"enterprise-sdk-license-v1","app_id":" Demo ","licenses":[
              {"sn":"$sn","signature":"$signature"},
              {"sn":"$secondSn","signature":"$secondSignature"}
            ]}
        """.trimIndent())
        val proof = bundle.findProof(secondSn)!!
        assertEquals(" Demo ", proof.appId)
        assertEquals(secondSignature, proof.signature)
        val payload = EnterpriseAuthV1Encoder.encode(proof)
        assertArrayEquals(secondSn.toByteArray(Charsets.US_ASCII), payload.copyOfRange(9, 23))
        assertArrayEquals(ByteArray(64) { (63 - it).toByte() }, payload.takeLast(64).toByteArray())
        for (missing in listOf("S211GABE130303", secondSn.lowercase(), " $secondSn", "$secondSn ")) {
            assertNull(bundle.findProof(missing))
        }
    }

    @Test fun portalBundleRejectsEveryInvalidEntryAndMixedSchemas() {
        val entry = """{"sn":"$sn","signature":"$signature"}"""
        for (entries in listOf(
            "$entry,$entry",
            "$entry,{\"sn\":\"S211GABE130302\",\"signature\":\"invalid\"}",
            "$entry,{\"sn\":\"S211GABE130302\"}",
            "$entry,{\"sn\":14,\"signature\":\"$signature\"}",
            "$entry,null",
            "$entry,{\"sn\":\"S211GABE130302\",\"signature\":\"$signature\",\"extra\":true}",
            "$entry,{\"sn\":\"S211GABE130302\",\"sn\":\"S211GABE130303\",\"signature\":\"$signature\"}",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                EnterpriseLicenseBundle.fromJsonString(
                    """{"format":"enterprise-sdk-license-v1","app_id":"app","licenses":[$entries]}""")
            }
        }
        for (source in listOf(
            """{"format":"enterprise-sdk-license-v2","app_id":"app","licenses":[$entry]}""",
            """{"format":"enterprise-sdk-license-v1","app_id":"app","licenses":{}}""",
            """{"format":"enterprise-sdk-license-v1","app_id":"app","licenses":[$entry],"proofs":{}}""",
            """{"schema_version":1,"app_id":"app","proofs":{},"licenses":[$entry]}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) { EnterpriseLicenseBundle.fromJsonString(source) }
        }
    }

    @Test fun duplicateAndMalformedInputFailClosed() {
        val duplicate = """{"schema_version":1,"app_id":"a","proofs":{"$sn":"$signature","S211GABE130301":"$signature"}}"""
        assertThrows(IllegalArgumentException::class.java) {
            EnterpriseLicenseBundle.fromJsonString(duplicate)
        }
        val escapedAlias = duplicate.replace("S211GABE130301\":\"$signature\"}",
            "S211GABE13030\\u0031\":\"$signature\"}")
        assertThrows(IllegalArgumentException::class.java) {
            EnterpriseLicenseBundle.fromJsonString(escapedAlias)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnterpriseLicenseBundle.fromJsonString("""{"schema_version":1,"app_id":"a","proofs":{}} extra""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnterpriseBindingProof(" Demo ", sn, signature.dropLast(2) + "AB")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnterpriseBindingProof(" Demo ", sn, signature.dropLast(3) + "B==")
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnterpriseBindingProof("\uD800", sn, signature)
        }
        assertThrows(IllegalArgumentException::class.java) {
            EnterpriseBindingProof(" Demo ", "短", signature)
        }
    }

    /** Android's JSONObject accepts extensions that the other SDK platforms reject. */
    @Test fun strictJsonRejectsLenientSyntaxBeforeModelParsing() {
        val portal = """{"format":"enterprise-sdk-license-v1","app_id":"app","licenses":[]}"""
        val legacy = """{"schema_version":1,"app_id":"app","proofs":{}}"""
        for (valid in listOf(portal, legacy)) {
            assertNull(EnterpriseLicenseBundle.fromJsonString(valid).findProof(sn))
            for (malformed in listOf(
                valid.replace('"', '\''),
                valid.replace("\"app_id\"", "app_id"),
                valid.replace("\"app\"", "app"),
                valid.replace("\"app_id\"", "/* hidden field */\"app_id\""),
                valid.replace("\"app_id\"", "// hidden field\n\"app_id\""),
                valid.replace("\"app_id\":", "\"app_id\"="),
                valid.dropLast(1) + ",}",
                "$valid {}",
                "$valid /* trailing comment */",
                valid.replace("\"app\"", "null"),
                valid.replace("\"app\"", "1"),
            )) {
                assertThrows(malformed, IllegalArgumentException::class.java) {
                    EnterpriseLicenseBundle.fromJsonString(malformed)
                }
            }
        }
    }

    /** Decode escaped names before checking uniqueness, before JSONObject can overwrite them. */
    @Test fun strictJsonRejectsDuplicateKeysAtEveryObjectDepth() {
        val entry = """{"sn":"$sn","signature":"$signature"}"""
        val portal = """{"format":"enterprise-sdk-license-v1","app_id":"app","licenses":[$entry]}"""
        val malformed = listOf(
            portal.replace("\"app_id\":\"app\"", "\"app_id\":\"first\",\"\\u0061pp_id\":\"app\""),
            portal.replace("\"sn\":\"$sn\"", "\"sn\":\"$sn\",\"\\u0073n\":\"S211GABE130302\""),
            portal.replace(entry, "{'sn':'$sn','sn':'S211GABE130302','signature':'$signature'}"),
            """{"schema_version":1,"app_id":"app","proofs":{"$sn":"$signature","S211GABE13030\u0031":"$signature"}}""",
        )
        for (source in malformed) {
            assertThrows(IllegalArgumentException::class.java) {
                EnterpriseLicenseBundle.fromJsonString(source)
            }
        }
    }

    /** JSON escapes preserve identity; delimiters inside string values are never object syntax. */
    @Test fun strictJsonPreservesEscapedNamesAndStringDelimiters() {
        val source = """{"format":"enterprise-sdk-license-v1","\u0061pp_id":" App \"{[,]}\":id ","licenses":[{"\u0073n":"$sn","signature":"$signature"}]}"""
        val bundle = EnterpriseLicenseBundle.fromJsonString(source)
        assertEquals(" App \"{[,]}\":id ", bundle.appId)
        assertEquals(signature, bundle.findProof(sn)!!.signature)
    }
}
