package com.evenrealities.devices

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class G2BusinessAuthCodecTest {
    private val contract = JSONObject(File("../protocol/contract.json").readText())
    private val vectors = contract.getJSONObject("vectors")

    @Test fun androidRequestUses8401AuthMgrAndValidWireCrc() {
        val request = G2BusinessAuthCodec.request(7, 42)
        assertArrayEquals(hex(vectors.getString("g2_business_auth_android_request_magic_42_sync_7_hex")), request)
        val ios = hex(vectors.getString("g2_business_auth_ios_request_magic_42_sync_7_hex"))
        assertArrayEquals(ios.copyOfRange(0, 17), request.copyOfRange(0, 17))
        assertEquals(4, request[17].toInt()) // Android phoneType; iOS vector uses 3.
        assertEquals(20, request.size)
    }

    @Test fun exactResponseMustContainMatchingMagicSecAuthAndReceivedCrc() {
        val valid = hex(vectors.getString("g2_business_auth_response_magic_42_sync_7_hex"))
        assertTrue(G2BusinessAuthCodec.isAuthenticatedResponse(valid, 7, 42))
        assertFalse(G2BusinessAuthCodec.isAuthenticatedResponse(valid, 8, 42))
        assertFalse(G2BusinessAuthCodec.isAuthenticatedResponse(valid, 7, 43))
        val badCrc = valid.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertFalse(G2BusinessAuthCodec.isAuthenticatedResponse(badCrc, 7, 42))
        val noAuth = valid.copyOf().also { it[15] = 0 }
        assertFalse(G2BusinessAuthCodec.isAuthenticatedResponse(noAuth, 7, 42))
        val wrongSid = valid.copyOf().also { it[6] = 7 }
        assertFalse(G2BusinessAuthCodec.isAuthenticatedResponse(wrongSid, 7, 42))
    }

    @Test fun ackCorrelatesFullHostMagicAndSuccessPushUsesDeviceCounters() {
        val ack = hex(vectors.getString("g2_business_auth_ack_magic_195_sync_223_hex"))
        val push = hex(vectors.getString("g2_business_auth_push_magic_67_sync_67_hex"))
        assertEquals(G2BusinessAuthCodec.Signal.COMMAND_ACCEPTED, G2BusinessAuthCodec.classify(ack, 195))
        assertNull(G2BusinessAuthCodec.classify(ack, 451)) // same low byte
        assertEquals(G2BusinessAuthCodec.Signal.AUTHENTICATED, G2BusinessAuthCodec.classify(push, 195))
        // Wrapped device magic can be omitted on a push. A correlated ACK may itself carry secAuth.
        val omittedMagic = incoming("08041a020801", notification = true)
        val ackWithSecurity = incoming("0804102a1a020801", notification = false)
        assertEquals(G2BusinessAuthCodec.Signal.AUTHENTICATED, G2BusinessAuthCodec.classify(omittedMagic, 42))
        assertEquals(G2BusinessAuthCodec.Signal.COMMAND_ACCEPTED, G2BusinessAuthCodec.classify(ackWithSecurity, 42))
    }

    @Test fun declaredFrameBoundaryPermitsPaddingWhileInvalidTransportOrCrcCannotAuthorize() {
        val push = hex(vectors.getString("g2_business_auth_push_magic_67_sync_67_hex"))
        val padded = push + byteArrayOf(0, 0xaa.toByte(), 0xff.toByte())
        assertEquals(G2BusinessAuthCodec.Signal.AUTHENTICATED, G2BusinessAuthCodec.classify(padded, 42))
        val damaged = listOf(
            push.copyOf(push.size - 1),
            padded.copyOf().also { it[push.lastIndex] = (it[push.lastIndex].toInt() xor 1).toByte() },
            push.copyOf().also { it[6] = 7 }, // other SID
            push.copyOf().also { it[1] = 0x21 }, // outbound
            push.copyOf().also { it[4] = 2 }, // incomplete fragment set
            push.copyOf().also { it[7] = 3 }, // transport error
            push.copyOf().also { it[7] = 0x41 }, // reserved flag
        )
        for (frame in damaged) assertNull(G2BusinessAuthCodec.classify(frame, 42))
    }

    @Test fun missingConflictingAndUnsuccessfulAuthFieldsCannotProduceASignal() {
        // Every payload has a valid transport CRC to exercise protobuf validation.
        for (body in listOf("0804102a", "0805102a1a020801", "0804102a1a0418010801",
            "0804102a102a1a020801", "08040804102a1a020801", "0804102a1a0208011a00",
            "0804102a1a0418001800", "0804102a1a0408010801", "0804102a1a020a00",
            "0804102a1a031802", "0804102a1a020880")) {
            for (notify in listOf(true, false)) {
                assertNull("body=$body notification=$notify",
                    G2BusinessAuthCodec.classify(incoming(body, notify), 42))
            }
        }
        for (auth in listOf("1a00", "1a020800", "1a020802")) {
            assertNull(G2BusinessAuthCodec.classify(incoming("0804102a" + auth, notification = true), 42))
        }
    }

    /** Wrap synthetic protobuf cases with a valid, single-frame device reply. */
    private fun incoming(body: String, notification: Boolean): ByteArray =
        G2TimeSyncCodec.singlePacket(0x80, 7, hex(body), false).also {
            it[1] = 0x12
            it[7] = if (notification) 1 else 0
        }

    private fun hex(value: String): ByteArray = value.chunked(2)
        .map { it.toInt(16).toByte() }.toByteArray()
}
