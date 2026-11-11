package com.evenrealities.devices

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class G2OtaVersionWireTest {
    private val vectors = JSONObject(File("../protocol/g2_ota_version_vectors.json").readText())
    private fun vector(name: String) = vectors.getString(name).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun pairReadbackValidatesMagicFlagsAndCrc() {
        assertArrayEquals(vector("request"), G2OtaVersionWire.request(23, 169))
        val frame = vector("response")
        val versions = G2OtaVersionWire.response(frame + byteArrayOf(0), 169)!!
        assertEquals(G2OtaVersions("2.4.1.2", "2.4.1.2"), versions)
        assertTrue(versions.matches("V2.4.1.2.0"))
        assertFalse(versions.matches("2.4.1.3"))
        assertFalse(G2OtaVersions(versions.left, "2.4.1").matches(versions.left))
        // All 19 current schema fields at maximum encoded size still fit MTU 150.
        val allFields = vector("responseAllFields")
        assertEquals(146, allFields.size)
        assertTrue(G2OtaVersionWire.response(allFields, 169)!!.matches("2.4.123.456"))
        assertNull(G2OtaVersionWire.response(frame, 170))
        assertNull(G2OtaVersionWire.response(frame.copyOf(frame.size - 1), 169))
        for ((index, value) in listOf(0 to 0, 1 to 0x21, 4 to 2, 5 to 2, 6 to 128, 7 to 1, 7 to 2, 8 to 0)) {
            assertNull(G2OtaVersionWire.response(frame.copyOf().also { it[index] = value.toByte() }, 169))
        }
        // A new readback operation does not admit generic SID 9 business writes.
        assertThrows(IllegalArgumentException::class.java) { G2TimeSyncCodec.singlePacket(9, 23, byteArrayOf(1)) }
    }

    @Test fun invalidSelectorMissingLegAndAmbiguousVersionsDoNotVerify() {
        val frame = vector("response")
        // Recompute CRC so these cases exercise the protobuf checks, independently of transport validity.
        for ((offset, value) in listOf(9 to 1, 13 to 26, 16 to 0, 17 to 50, 26 to 58, 19 to 0xff)) {
            val changed = frame.copyOf()
            changed[offset] = value.toByte()
            val crc = G2EvenHubTransport.crc16(changed.copyOfRange(8, changed.size - 2))
            changed[changed.size - 2] = crc.toByte()
            changed[changed.size - 1] = (crc ushr 8).toByte()
            assertNull(G2OtaVersionWire.response(changed, 169))
        }
        for (invalid in listOf("", "V", "vV2", "0.0.0", "2..4", "2.bad", "2.4-beta", "２.４", "2.4.")) {
            assertFalse(G2OtaVersions.isValidTarget(invalid))
        }
    }
}
