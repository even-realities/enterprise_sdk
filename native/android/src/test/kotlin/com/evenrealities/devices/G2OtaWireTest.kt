package com.evenrealities.devices

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class G2OtaWireTest {
    private val vectors = JSONObject(File("../protocol/g2_ota_vectors.json").readText())
    private val sync = vectors.getInt("sync_id")
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun vector(key: String) = hex(vectors.getString(key))

    @Test fun controlsAndCrcOnlyFinalFragmentMatchDartVectors() {
        assertArrayEquals(vector("start_hex"), G2OtaWire.control(0, sync))
        assertArrayEquals(vector("information_hex"), G2OtaWire.control(1, sync, vector("information_header_hex")))
        assertArrayEquals(vector("result_check_hex"), G2OtaWire.control(3, sync))
        val raw = vectors.getJSONObject("raw")
        val expected = raw.getJSONArray("frames_hex")
        val frames = G2OtaWire.window(hex(raw.getString("payload_hex")), sync, raw.getInt("write_limit"))
        assertEquals(expected.length(), frames.size)
        for (i in frames.indices) assertArrayEquals(hex(expected.getString(i)), frames[i])
    }

    @Test fun windowBoundariesPreserveEveryByteAndFinalCrc() {
        // Include the one-free-byte case where the CRC must not be split across fragments.
        for (size in listOf(1, 140, 141, 142, 143, 283, 4096)) {
            val body = ByteArray(size) { it.toByte() }
            val frames = G2OtaWire.window(body, 255, 150).drop(1)
            val decoded = frames.flatMapIndexed { index, frame ->
                assertTrue(frame.size <= 150)
                assertEquals(index + 1, frame[5].toInt() and 255)
                assertEquals(frames.size, frame[4].toInt() and 255)
                frame.copyOfRange(8, frame.size - if (index == frames.lastIndex) 2 else 0).toList()
            }
            assertArrayEquals(body, decoded.toByteArray())
            val crc = G2EvenHubTransport.crc16(body)
            assertArrayEquals(byteArrayOf(crc.toByte(), (crc ushr 8).toByte()), frames.last().takeLast(2).toByteArray())
        }
        assertThrows(IllegalArgumentException::class.java) { G2OtaWire.window(ByteArray(4097), 1, 150) }
    }

    @Test fun repliesRequireTheSingleMatchingFrameAndValidatedCrc() {
        val raw = vector("raw_ack_hex")
        assertEquals(0, G2OtaWire.reply(raw + byteArrayOf(99), 2, sync))
        assertEquals(0, G2OtaWire.reply(vector("start_ack_hex"), 0, sync))
        assertEquals(9, G2OtaWire.notify(vector("install_notify_hex")))
        assertNull(G2OtaWire.reply(vector("install_notify_hex"), 3, sync))
        assertNull(G2OtaWire.notify(raw))
        assertNull(G2OtaWire.reply(raw, 2, sync + 1))
        assertNull(G2OtaWire.reply(raw, 3, sync))
        for (offset in listOf(1, 3, 4, 5, 6, 7, 8, 9, 10, 11)) {
            val corrupt = raw.clone().also { it[offset] = (it[offset].toInt() xor 1).toByte() }
            assertNotEquals("byte $offset", 0, G2OtaWire.reply(corrupt, 2, sync))
        }
        val rejected = vector("start_ack_hex").also { it[7] = 2 }
        assertEquals(-1, G2OtaWire.reply(rejected, 0, sync))
    }

    @Test fun imageRequiresMatchingAbsoluteBoundsInnerHeaderAndBodyChecksum() {
        val file = fixture()
        val image = G2OtaWire.parse(file)
        assertEquals("s200_v2.4.1.4", image.version)
        assertArrayEquals("123456789".toByteArray(), image.components.single().body)
        assertArrayEquals(file.copyOfRange(96, 224), image.components.single().header)
        for (at in listOf(0, 8, 48, 68, 72, 76, 80, 104, 108, 116, 224)) {
            val changed = file.clone().also { it[at] = 0xff.toByte() }
            assertThrows("offset $at", IllegalArgumentException::class.java) { G2OtaWire.parse(changed) }
        }
        assertThrows(IllegalArgumentException::class.java) { G2OtaWire.parse(file + 0) }
        assertThrows(IllegalArgumentException::class.java) { G2OtaWire.parse(file.copyOf(file.size - 1)) }
    }

    /** Synthetic component; c052a8c8 is the firmware CRC of the ASCII check string. */
    private fun fixture() = ByteArray(233).apply {
        "EVENOTA".toByteArray().copyInto(this)
        this[8] = 1
        "s200_v2.4.1.4".toByteArray().copyInto(this, 48)
        this[64] = 1
        this[68] = 96
        this[72] = 137.toByte()
        hex("c8a852c0").copyInto(this, 76)
        "evenota".toByteArray().copyInto(this, 80)
        this[104] = 9
        hex("c8a852c0").copyInto(this, 108)
        "EVEN".toByteArray().copyInto(this, 116)
        "123456789".toByteArray().copyInto(this, 224)
    }
}
