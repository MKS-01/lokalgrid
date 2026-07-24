package dev.lokalgrid.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Track record — 32 bytes, fixed width, little-endian (PROJECT.md §4).
 *
 * This is the Kotlin half of the "one wire format, two hand-written codecs"
 * plan (§6). It must decode exactly the bytes the mock node (and later the C
 * firmware) produce. The golden vectors in `src/test/resources/golden` are the
 * contract; when this and the JS/C sides disagree on them, that is the Phase 05
 * drift lesson arriving on schedule.
 *
 * Unsigned wire fields are widened to a signed Kotlin type big enough to hold
 * their full range: u32 -> Long, u16/u8 -> Int. Signed wire fields keep their
 * natural range in an Int. Absent sensors carry sentinels, never a shrunk record.
 */
data class TrackRecord(
    val epoch: Long,   // u32  GPS time when time_valid
    val latE7: Int,    // i32  latitude  ×1e7
    val lonE7: Int,    // i32  longitude ×1e7
    val alt: Int,      // i16  metres, GNSS
    val baro: Int,     // i16  BARO_ABSENT if no BME280
    val spd: Int,      // u16  cm/s
    val hdg: Int,      // u16  centidegrees
    val sv: Int,       // u8   satellite count
    val hd: Int,       // u8   HDOP ×10
    val bat: Int,      // u8   battery %
    val tmp: Int,      // i8   °C, TEMP_ABSENT if no sensor
    val flags: Long,   // u32  see FLAG.*
    val crc32: Long = 0, // u32 trailer, filled on decode
) {
    val timeValid: Boolean get() = flags and FLAG.TIME_VALID != 0L
    val fix3d: Boolean get() = flags and FLAG.FIX_3D != 0L
    val motion: Boolean get() = flags and FLAG.MOTION != 0L
    val tripStart: Boolean get() = flags and FLAG.TRIP_START != 0L
    val charging: Boolean get() = flags and FLAG.CHARGING != 0L
    val zoneMask: Int get() = ((flags ushr 8) and 0xFF).toInt()
    val seqLo: Int get() = ((flags ushr 16) and 0xFFFF).toInt()

    val latDeg: Double get() = latE7 / 1e7
    val lonDeg: Double get() = lonE7 / 1e7

    /** Field map keyed exactly like the golden-vector JSON, for cross-checks. */
    fun toFieldMap(): Map<String, Long> = linkedMapOf(
        "epoch" to epoch,
        "lat_e7" to latE7.toLong(),
        "lon_e7" to lonE7.toLong(),
        "alt" to alt.toLong(),
        "baro" to baro.toLong(),
        "spd" to spd.toLong(),
        "hdg" to hdg.toLong(),
        "sv" to sv.toLong(),
        "hd" to hd.toLong(),
        "bat" to bat.toLong(),
        "tmp" to tmp.toLong(),
        "flags" to flags,
    )

    companion object {
        const val BYTES = 32

        // i16 sentinel 0x8000 read as signed; i8 sentinel 0x80 read as signed.
        const val BARO_ABSENT = -32768
        const val TEMP_ABSENT = -128

        /** Encode to a fresh 32-byte array; CRC over the first 28 bytes. */
        fun encode(r: TrackRecord): ByteArray {
            val buf = ByteBuffer.allocate(BYTES).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(r.epoch.toInt())          // u32 low bits
            buf.putInt(r.latE7)
            buf.putInt(r.lonE7)
            buf.putShort(r.alt.toShort())
            buf.putShort(r.baro.toShort())
            buf.putShort(r.spd.toShort())        // u16 -> low 16 bits
            buf.putShort(r.hdg.toShort())
            buf.put(r.sv.toByte())
            buf.put(r.hd.toByte())
            buf.put(r.bat.toByte())
            buf.put(r.tmp.toByte())
            buf.putInt(r.flags.toInt())          // u32 low bits
            val bytes = buf.array()
            val crc = CRC32().apply { update(bytes, 0, 28) }.value
            ByteBuffer.wrap(bytes, 28, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc.toInt())
            return bytes
        }

        /** Build a record from a golden-style field map (crc computed on encode). */
        fun fromFieldMap(m: Map<String, Long>): TrackRecord = TrackRecord(
            epoch = m.getValue("epoch"),
            latE7 = m.getValue("lat_e7").toInt(),
            lonE7 = m.getValue("lon_e7").toInt(),
            alt = m.getValue("alt").toInt(),
            baro = m.getValue("baro").toInt(),
            spd = m.getValue("spd").toInt(),
            hdg = m.getValue("hdg").toInt(),
            sv = m.getValue("sv").toInt(),
            hd = m.getValue("hd").toInt(),
            bat = m.getValue("bat").toInt(),
            tmp = m.getValue("tmp").toInt(),
            flags = m.getValue("flags"),
        )

        /**
         * Decode a 32-byte record. Throws [BadRecord] on a short buffer or a CRC
         * mismatch — a sync caller should catch and re-request from its last good
         * offset rather than trust the bytes.
         */
        fun decode(bytes: ByteArray, offset: Int = 0): TrackRecord {
            if (bytes.size - offset < BYTES) throw BadRecord("need $BYTES bytes, got ${bytes.size - offset}")
            val b = ByteBuffer.wrap(bytes, offset, BYTES).order(ByteOrder.LITTLE_ENDIAN)
            val epoch = b.int.toUInt().toLong()
            val latE7 = b.int
            val lonE7 = b.int
            val alt = b.short.toInt()
            val baro = b.short.toInt()
            val spd = b.short.toInt() and 0xFFFF
            val hdg = b.short.toInt() and 0xFFFF
            val sv = b.get().toInt() and 0xFF
            val hd = b.get().toInt() and 0xFF
            val bat = b.get().toInt() and 0xFF
            val tmp = b.get().toInt()          // signed i8
            val flags = b.int.toUInt().toLong()
            val stored = b.int.toUInt().toLong()
            val computed = CRC32().apply { update(bytes, offset, 28) }.value
            if (stored != computed) {
                throw BadRecord("crc32 mismatch: stored 0x%08x computed 0x%08x".format(stored, computed))
            }
            return TrackRecord(epoch, latE7, lonE7, alt, baro, spd, hdg, sv, hd, bat, tmp, flags, stored)
        }
    }
}

/** Flags word (offset 24, u32) — bit positions from §4. */
object FLAG {
    const val TIME_VALID = 1L shl 0
    const val FIX_3D = 1L shl 1
    const val MOTION = 1L shl 2
    const val TRIP_START = 1L shl 3
    const val TAMPER = 1L shl 4 // reserved, unused
    const val CHARGING = 1L shl 5

    /** Pack the low flag byte, zone mask (bits 8–15) and seq (bits 16–31). */
    fun pack(bits: Long = 0, zoneMask: Int = 0, seq: Int = 0): Long =
        (bits and 0xFF) or ((zoneMask.toLong() and 0xFF) shl 8) or ((seq.toLong() and 0xFFFF) shl 16)
}

class BadRecord(message: String) : Exception(message)
