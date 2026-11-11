package com.evenrealities.devices

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.json.JSONObject
import org.json.JSONArray
import java.io.StringReader
import java.nio.charset.StandardCharsets

/** An exact portal-issued proof. Firmware, rather than this SDK, verifies its signature. */
class EnterpriseBindingProof(val appId: String, val sn: String, signature: String) {
    private val signatureValue: String = signature
    private val decodedSignature: ByteArray

    init {
        require(appId.isNotEmpty() && appId.toByteArray(StandardCharsets.UTF_8).size in 1..64 &&
            String(appId.toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8) == appId) {
            "app_id must contain 1..64 exact UTF-8 bytes"
        }
        require(sn.length == 14 && sn.all { it.code <= 0x7f }) {
            "SN must contain exactly 14 ASCII bytes"
        }
        decodedSignature = decodeCanonicalSignature(signature)
    }

    val signature: String get() = signatureValue
    val signatureBytes: ByteArray get() = decodedSignature.copyOf()

    companion object {
        /** Strict Base64 makes malformed proofs fail before any GATT operation. */
        private fun decodeCanonicalSignature(value: String): ByteArray {
            require(value.length == 88 && value.endsWith("==")) {
                "Signature must use standard padded Base64"
            }
            val result = ByteArray(64)
            var output = 0
            for (offset in 0 until 84 step 4) {
                val a = alphabetIndex(value[offset])
                val b = alphabetIndex(value[offset + 1])
                val c = alphabetIndex(value[offset + 2])
                val d = alphabetIndex(value[offset + 3])
                require(a >= 0 && b >= 0 && c >= 0 && d >= 0) { "Invalid Base64 alphabet" }
                result[output++] = ((a shl 2) or (b shr 4)).toByte()
                result[output++] = ((b shl 4) or (c shr 2)).toByte()
                result[output++] = ((c shl 6) or d).toByte()
            }
            val a = alphabetIndex(value[84])
            val b = alphabetIndex(value[85])
            require(a >= 0 && b >= 0 && b and 0x0f == 0) { "Non-canonical Base64 padding" }
            result[output] = ((a shl 2) or (b shr 4)).toByte()
            return result
        }

        private fun alphabetIndex(char: Char): Int = when (char) {
            in 'A'..'Z' -> char.code - 'A'.code
            in 'a'..'z' -> char.code - 'a'.code + 26
            in '0'..'9' -> char.code - '0'.code + 52
            '+' -> 62
            '/' -> 63
            else -> -1
        }
    }
}

/** Fully validated license snapshot; replacement affects future connection attempts only. */
class EnterpriseLicenseBundle private constructor(
    val appId: String,
    private val proofs: Map<String, EnterpriseBindingProof>,
) {
    /** Only the exact connected device SN may select a proof; never normalize or fall back. */
    fun findProof(sn: String): EnterpriseBindingProof? = proofs[sn]

    companion object {
        fun fromProofs(appId: String, licenses: Iterable<EnterpriseBindingProof>): EnterpriseLicenseBundle {
            val entries = LinkedHashMap<String, EnterpriseBindingProof>()
            // Validate even an empty bundle so an invalid app_id never silently passes startup.
            require(appId.isNotEmpty() && appId.toByteArray(StandardCharsets.UTF_8).size in 1..64 &&
                String(appId.toByteArray(StandardCharsets.UTF_8), StandardCharsets.UTF_8) == appId)
            for (proof in licenses) {
                require(proof.appId == appId && !entries.containsKey(proof.sn)) {
                    "Mismatched app_id or duplicate SN"
                }
                entries[proof.sn] = proof
            }
            return EnterpriseLicenseBundle(appId, entries.toMap())
        }

        /** Validate strict JSON and duplicate keys before JSONObject can overwrite a signed SN. */
        fun fromJsonString(source: String): EnterpriseLicenseBundle {
            try {
                // Android's JSONObject permits single quotes, comments and unquoted names.
                // Check the same JSON grammar on JVM and device before model conversion.
                validateStrictJson(source)
                val root = JSONObject(source)
                require(root.length() == 3 && root.opt("app_id") is String) {
                    "Invalid enterprise license bundle"
                }
                val appId = root.getString("app_id")
                val proofs = mutableListOf<EnterpriseBindingProof>()
                if (root.has("format")) {
                    // The portal exports one signed proof per SN, not one reusable device key.
                    // Validate the complete array before any target lookup or GATT operation.
                    require(root.opt("format") == "enterprise-sdk-license-v1" &&
                        root.opt("licenses") is JSONArray) { "Invalid enterprise license bundle" }
                    val licenses = root.getJSONArray("licenses")
                    for (index in 0 until licenses.length()) {
                        val license = licenses.opt(index) as? JSONObject
                            ?: throw IllegalArgumentException("Invalid enterprise binding proof")
                        require(license.length() == 2 && license.opt("sn") is String &&
                            license.opt("signature") is String) { "Invalid enterprise binding proof" }
                        proofs += EnterpriseBindingProof(appId, license.getString("sn"), license.getString("signature"))
                    }
                } else {
                    // Retain the original SDK interchange format, without accepting mixed schemas.
                    require(root.opt("schema_version") == 1 && root.opt("proofs") is JSONObject) {
                        "Invalid enterprise license bundle"
                    }
                    val proofObject = root.getJSONObject("proofs")
                    val keys = proofObject.keys()
                    while (keys.hasNext()) {
                        val sn = keys.next()
                        val signature = proofObject.opt(sn) as? String
                            ?: throw IllegalArgumentException("Invalid enterprise binding proof")
                        proofs += EnterpriseBindingProof(appId, sn, signature)
                    }
                }
                return fromProofs(appId, proofs)
            } catch (error: Exception) {
                if (error is IllegalArgumentException) throw error
                throw IllegalArgumentException("Invalid enterprise license bundle", error)
            }
        }

        /** An iterative syntax walk keeps decoded object names unique, including escaped aliases. */
        private fun validateStrictJson(source: String) {
            JsonReader(StringReader(source)).use { reader ->
                reader.strictness = Strictness.STRICT
                // Arrays have no keys; each nested object owns an independent name set.
                val objects = mutableListOf<MutableSet<String>?>()
                while (true) {
                    when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> {
                            reader.beginObject()
                            objects.add(mutableSetOf())
                        }
                        JsonToken.BEGIN_ARRAY -> {
                            reader.beginArray()
                            objects.add(null)
                        }
                        JsonToken.END_OBJECT -> {
                            reader.endObject()
                            objects.removeAt(objects.lastIndex)
                        }
                        JsonToken.END_ARRAY -> {
                            reader.endArray()
                            objects.removeAt(objects.lastIndex)
                        }
                        JsonToken.NAME -> {
                            val key = reader.nextName()
                            require(objects.last()!!.add(key)) { "Duplicate enterprise license JSON key" }
                        }
                        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
                        JsonToken.BOOLEAN -> reader.nextBoolean()
                        JsonToken.NULL -> reader.nextNull()
                        JsonToken.END_DOCUMENT -> return
                    }
                }
            }
        }
    }
}

/** Provisional firmware v1 frame; one acknowledged 8403 write, never fragments. */
object EnterpriseAuthV1Encoder {
    fun encode(proof: EnterpriseBindingProof): ByteArray {
        val appId = proof.appId.toByteArray(StandardCharsets.UTF_8)
        val sn = proof.sn.toByteArray(StandardCharsets.US_ASCII)
        return byteArrayOf(1, appId.size.toByte()) + appId + byteArrayOf(sn.size.toByte()) + sn + proof.signatureBytes
    }
}
