package dev.lokalgrid.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Proves this Kotlin codec agrees with the mock node's JS codec, byte for byte,
 * on the shared golden vectors. If C, JS and Kotlin all reproduce these hex
 * strings the wire format is one thing rather than three hopeful ones (§6).
 */
class TrackRecordTest {

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun bytesToHex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    @Test
    fun `golden vectors decode to their fields and re-encode to their bytes`() {
        val text = javaClass.getResource("/golden/vectors.json")!!.readText()
        val vectors = Json.parseToJsonElement(text).jsonObject["vectors"]!!.jsonArray
        assertEquals("expected the 5 golden cases", 5, vectors.size)

        for (v in vectors) {
            val o = v.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            val hex = o["hex"]!!.jsonPrimitive.content
            val fields = o["fields"]!!.jsonObject.mapValues { it.value.jsonPrimitive.long }

            // decode the canonical bytes → fields match
            val decoded = TrackRecord.decode(hexToBytes(hex))
            assertEquals("$name decoded fields", fields, decoded.toFieldMap())

            // re-encode the fields → exact same bytes
            val reencoded = TrackRecord.encode(TrackRecord.fromFieldMap(fields))
            assertEquals("$name re-encode", hex, bytesToHex(reencoded))
        }
    }

    @Test
    fun `encode then decode round-trips every field`() {
        val r = TrackRecord(
            epoch = 1_800_000_000L,
            latE7 = -338_688_000,
            lonE7 = 1_512_093_000,
            alt = -12,
            baro = TrackRecord.BARO_ABSENT,
            spd = 2500,
            hdg = 35_999,
            sv = 12,
            hd = 7,
            bat = 5,
            tmp = -8,
            flags = FLAG.pack(FLAG.TIME_VALID or FLAG.FIX_3D, zoneMask = 0x5A, seq = 0xFFFF),
        )
        val back = TrackRecord.decode(TrackRecord.encode(r))
        assertEquals(r.toFieldMap(), back.toFieldMap())
        assertEquals(0x5A, back.zoneMask)
        assertEquals(0xFFFF, back.seqLo)
        assertEquals(true, back.fix3d)
    }

    @Test
    fun `unsigned edge values survive the trip`() {
        val r = TrackRecord.fromFieldMap(
            linkedMapOf(
                "epoch" to 0xFFFFFFFFL, "lat_e7" to 900_000_000L, "lon_e7" to -1_800_000_000L,
                "alt" to 32_767L, "baro" to -32_768L, "spd" to 65_535L, "hdg" to 65_535L,
                "sv" to 255L, "hd" to 255L, "bat" to 255L, "tmp" to 127L, "flags" to 0xFFFFFFFFL,
            )
        )
        val back = TrackRecord.decode(TrackRecord.encode(r))
        assertEquals(0xFFFFFFFFL, back.epoch)
        assertEquals(65_535, back.spd)
        assertEquals(255, back.sv)
        assertEquals(0xFFFFFFFFL, back.flags)
    }

    @Test
    fun `little-endian layout at documented offsets`() {
        val bytes = TrackRecord.encode(
            TrackRecord(0x01020304L, 0, 0, 0, TrackRecord.BARO_ABSENT, 0, 0, 0, 0, 0, TrackRecord.TEMP_ABSENT, 0)
        )
        assertArrayEquals(byteArrayOf(0x04, 0x03, 0x02, 0x01), bytes.copyOfRange(0, 4))
        assertEquals((-128).toByte(), bytes[23])                     // temp sentinel
        assertArrayEquals(byteArrayOf(0x00, 0x80.toByte()), bytes.copyOfRange(14, 16)) // baro sentinel
    }

    @Test
    fun `a flipped byte fails the crc check`() {
        val bytes = TrackRecord.encode(TrackRecord(42, 1, 2, 0, 0, 0, 0, 0, 0, 0, 0, 0))
        bytes[4] = (bytes[4].toInt() xor 0xFF).toByte()
        assertThrows(BadRecord::class.java) { TrackRecord.decode(bytes) }
    }
}
