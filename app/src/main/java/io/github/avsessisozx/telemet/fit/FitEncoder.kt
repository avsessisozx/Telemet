package io.github.avsessisozx.telemet.fit

import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Garmin FIT biçiminde etkinlik dosyası üretir (DJI Mimo / DJI Studio "FIT Dosyasını İçe Aktar" için).
 *
 * Sürüş sırasında her nokta sabit uzunlukta bir "record" mesajı olarak ham dosyaya eklenir
 * ([encodeRecord]). Uygulama kapanırsa bile bu ham noktalar kaybolmaz; sürüş bittiğinde
 * ya da bir sonraki açılışta [buildFile] başlık, özet ve CRC'yi ekleyip geçerli bir .fit üretir.
 *
 * Saf Kotlin/JVM; Android'e bağımlı değil, bu yüzden birim testleriyle doğrulanabiliyor.
 */
object FitEncoder {

    /** FIT zaman sıfırı: 1989-12-31 00:00:00 UTC (Unix saniyesi). */
    const val FIT_EPOCH_S = 631_065_600L

    // Temel tipler
    private const val ENUM = 0x00
    private const val UINT8 = 0x02
    private const val SINT16 = 0x83
    private const val UINT16 = 0x84
    private const val SINT32 = 0x85
    private const val UINT32 = 0x86
    private const val UINT32Z = 0x8C

    // Genel mesaj numaraları
    private const val MSG_FILE_ID = 0
    private const val MSG_SESSION = 18
    private const val MSG_LAP = 19
    private const val MSG_RECORD = 20
    private const val MSG_EVENT = 21
    private const val MSG_ACTIVITY = 34

    // Yerel mesaj tipleri
    private const val L_FILE_ID = 0
    private const val L_RECORD = 1
    private const val L_EVENT = 2
    private const val L_LAP = 3
    private const val L_SESSION = 4
    private const val L_ACTIVITY = 5

    private class Field(val num: Int, val size: Int, val type: Int)

    private val RECORD_FIELDS = listOf(
        Field(253, 4, UINT32), // timestamp
        Field(0, 4, SINT32),   // position_lat (semicircle)
        Field(1, 4, SINT32),   // position_long (semicircle)
        Field(5, 4, UINT32),   // distance (m * 100)
        Field(6, 2, UINT16),   // speed (m/s * 1000)
        Field(73, 4, UINT32),  // enhanced_speed (m/s * 1000)
        Field(2, 2, UINT16),   // altitude ((m + 500) * 5)
        Field(78, 4, UINT32),  // enhanced_altitude ((m + 500) * 5)
        Field(9, 2, SINT16),   // grade (% * 100)
    )

    /** Bir record mesajının diskteki uzunluğu (başlık baytı dahil). */
    val RECORD_SIZE: Int = 1 + RECORD_FIELDS.sumOf { it.size }

    /** Tek bir GPS noktası, FIT birimlerine çevrilmeden önce. */
    data class Point(
        val utcMillis: Long,
        val latitude: Double,
        val longitude: Double,
        val altitudeM: Double,
        val speedMs: Double,
        val distanceM: Double,
        val gradePercent: Double,
    )

    fun fitTime(utcMillis: Long): Long = utcMillis / 1000 - FIT_EPOCH_S

    fun semicircles(deg: Double): Int =
        (deg.coerceIn(-180.0, 179.9999999) * (2147483648.0 / 180.0)).roundToLong().toInt()

    fun degrees(semi: Int): Double = semi * (180.0 / 2147483648.0)

    /** Ham dosyaya eklenecek tek record mesajı. */
    fun encodeRecord(p: Point): ByteArray {
        val b = Le()
        b.u8(L_RECORD)
        b.u32(fitTime(p.utcMillis))
        b.s32(semicircles(p.latitude))
        b.s32(semicircles(p.longitude))
        b.u32((p.distanceM.coerceAtLeast(0.0) * 100).roundToLong())
        b.u16((p.speedMs.coerceIn(0.0, 65.534) * 1000).roundToLong().toInt())
        b.u32((p.speedMs.coerceAtLeast(0.0) * 1000).roundToLong())
        b.u16(((p.altitudeM + 500).coerceIn(0.0, 13_106.0) * 5).roundToLong().toInt())
        b.u32(((p.altitudeM + 500).coerceAtLeast(0.0) * 5).roundToLong())
        b.s16((p.gradePercent.coerceIn(-300.0, 300.0) * 100).roundToLong().toInt())
        return b.bytes()
    }

    /** Ham dosyadaki noktaları geri okur (özet hesaplamak için). Bozuk kuyruk yok sayılır. */
    fun decodeRecords(raw: ByteArray): List<Decoded> {
        val out = ArrayList<Decoded>(raw.size / RECORD_SIZE)
        var i = 0
        while (i + RECORD_SIZE <= raw.size) {
            if (raw[i].toInt() != L_RECORD) break
            val r = Reader(raw, i + 1)
            out += Decoded(
                fitTime = r.u32(),
                latSemi = r.s32(),
                lonSemi = r.s32(),
                distanceM = r.u32() / 100.0,
                speedMs = r.u16() / 1000.0,
                enhancedSpeedMs = r.u32() / 1000.0,
                altitudeM = r.u16() / 5.0 - 500,
                enhancedAltitudeM = r.u32() / 5.0 - 500,
                gradePercent = r.s16() / 100.0,
            )
            i += RECORD_SIZE
        }
        return out
    }

    data class Decoded(
        val fitTime: Long,
        val latSemi: Int,
        val lonSemi: Int,
        val distanceM: Double,
        val speedMs: Double,
        val enhancedSpeedMs: Double,
        val altitudeM: Double,
        val enhancedAltitudeM: Double,
        val gradePercent: Double,
    )

    /** Ham noktalardan kaç tanesi geçerli (bozuk kuyruk hariç). */
    /** Sürüş özeti (Sürüşlerim listesi için). */
    data class Summary(
        val startUtcMillis: Long,
        val endUtcMillis: Long,
        val distanceM: Double,
        val maxSpeedMs: Double,
        val ascentM: Double,
        val points: Int,
    ) {
        val durationS: Long get() = (endUtcMillis - startUtcMillis) / 1000
        val avgSpeedMs: Double get() = if (durationS > 0) distanceM / durationS else 0.0
    }

    fun summarize(raw: ByteArray): Summary? {
        val points = decodeRecords(raw)
        if (points.isEmpty()) return null
        var ascent = 0.0
        var ref = points.first().enhancedAltitudeM
        for (p in points) {
            val d = p.enhancedAltitudeM - ref
            if (abs(d) >= 3.0) {
                if (d > 0) ascent += d
                ref = p.enhancedAltitudeM
            }
        }
        return Summary(
            startUtcMillis = (points.first().fitTime + FIT_EPOCH_S) * 1000,
            endUtcMillis = (points.last().fitTime + FIT_EPOCH_S) * 1000,
            distanceM = points.last().distanceM,
            maxSpeedMs = points.maxOf { it.enhancedSpeedMs },
            ascentM = ascent,
            points = points.size,
        )
    }

    fun validLength(raw: ByteArray): Int = decodeRecords(raw).size * RECORD_SIZE

    /**
     * Tam .fit dosyasını üretir. Hiç nokta yoksa null döner.
     * Spor türü "bisiklet" seçildi: DJI uygulamaları hız/rota panolarını en sorunsuz bu türde gösteriyor.
     */
    fun buildFile(raw: ByteArray, createdUtcMillis: Long): ByteArray? {
        val points = decodeRecords(raw)
        if (points.isEmpty()) return null
        val recordBytes = raw.copyOf(points.size * RECORD_SIZE)

        val first = points.first()
        val last = points.last()
        val elapsedMs = (last.fitTime - first.fitTime).coerceAtLeast(0) * 1000
        val totalDistance = last.distanceM
        val maxSpeed = points.maxOf { it.enhancedSpeedMs }
        val avgSpeed = if (elapsedMs > 0) totalDistance / (elapsedMs / 1000.0) else 0.0
        var ascent = 0.0
        var descent = 0.0
        var ref = first.enhancedAltitudeM
        for (p in points) {
            val d = p.enhancedAltitudeM - ref
            // GPS yüksekliği gürültülü: 3 m'den küçük değişimler sayılmaz.
            if (abs(d) >= 3.0) {
                if (d > 0) ascent += d else descent -= d
                ref = p.enhancedAltitudeM
            }
        }

        val body = Le()

        // file_id
        body.define(L_FILE_ID, MSG_FILE_ID, listOf(
            Field(0, 1, ENUM), Field(1, 2, UINT16), Field(2, 2, UINT16),
            Field(3, 4, UINT32Z), Field(4, 4, UINT32),
        ))
        body.u8(L_FILE_ID)
        body.u8(4)          // type = activity
        body.u16(1)         // manufacturer = garmin (en geniş uyumluluk)
        body.u16(3121)      // product = edge 530
        body.u32(((createdUtcMillis / 1000) and 0x7FFFFFFFL) or 1L) // seri no (0 olmamalı)
        body.u32(fitTime(createdUtcMillis))

        // event: kronometre başladı
        body.define(L_EVENT, MSG_EVENT, listOf(Field(253, 4, UINT32), Field(0, 1, ENUM), Field(1, 1, ENUM), Field(4, 1, UINT8)))
        body.u8(L_EVENT); body.u32(first.fitTime); body.u8(0); body.u8(0); body.u8(0)

        // record'lar
        body.define(L_RECORD, MSG_RECORD, RECORD_FIELDS)
        body.raw(recordBytes)

        // event: kronometre durdu
        body.u8(L_EVENT); body.u32(last.fitTime); body.u8(0); body.u8(4); body.u8(0)

        val elapsed = elapsedMs
        val dist = (totalDistance * 100).roundToLong()
        val avg = (avgSpeed.coerceIn(0.0, 65.534) * 1000).roundToLong().toInt()
        val max = (maxSpeed.coerceIn(0.0, 65.534) * 1000).roundToLong().toInt()

        // lap
        body.define(L_LAP, MSG_LAP, listOf(
            Field(253, 4, UINT32), Field(254, 2, UINT16), Field(2, 4, UINT32),
            Field(3, 4, SINT32), Field(4, 4, SINT32), Field(5, 4, SINT32), Field(6, 4, SINT32),
            Field(7, 4, UINT32), Field(8, 4, UINT32), Field(9, 4, UINT32),
            Field(13, 2, UINT16), Field(14, 2, UINT16), Field(21, 2, UINT16), Field(22, 2, UINT16),
            Field(0, 1, ENUM), Field(1, 1, ENUM), Field(25, 1, ENUM),
        ))
        body.u8(L_LAP)
        body.u32(last.fitTime); body.u16(0); body.u32(first.fitTime)
        body.s32(first.latSemi); body.s32(first.lonSemi); body.s32(last.latSemi); body.s32(last.lonSemi)
        body.u32(elapsed); body.u32(elapsed); body.u32(dist)
        body.u16(avg); body.u16(max); body.u16(ascent.roundToLong().toInt().coerceAtMost(65_534)); body.u16(descent.roundToLong().toInt().coerceAtMost(65_534))
        body.u8(9); body.u8(1); body.u8(2) // event=lap, event_type=stop, sport=cycling

        // session
        body.define(L_SESSION, MSG_SESSION, listOf(
            Field(253, 4, UINT32), Field(254, 2, UINT16), Field(2, 4, UINT32),
            Field(3, 4, SINT32), Field(4, 4, SINT32),
            Field(7, 4, UINT32), Field(8, 4, UINT32), Field(9, 4, UINT32),
            Field(14, 2, UINT16), Field(15, 2, UINT16), Field(22, 2, UINT16), Field(23, 2, UINT16),
            Field(25, 2, UINT16), Field(26, 2, UINT16),
            Field(0, 1, ENUM), Field(1, 1, ENUM), Field(5, 1, ENUM), Field(6, 1, ENUM),
        ))
        body.u8(L_SESSION)
        body.u32(last.fitTime); body.u16(0); body.u32(first.fitTime)
        body.s32(first.latSemi); body.s32(first.lonSemi)
        body.u32(elapsed); body.u32(elapsed); body.u32(dist)
        body.u16(avg); body.u16(max); body.u16(ascent.roundToLong().toInt().coerceAtMost(65_534)); body.u16(descent.roundToLong().toInt().coerceAtMost(65_534))
        body.u16(0); body.u16(1)
        body.u8(8); body.u8(1); body.u8(2); body.u8(0) // event=session, stop, cycling, generic

        // activity
        val tzOffsetS = java.util.TimeZone.getDefault().getOffset(createdUtcMillis) / 1000
        body.define(L_ACTIVITY, MSG_ACTIVITY, listOf(
            Field(253, 4, UINT32), Field(0, 4, UINT32), Field(1, 2, UINT16),
            Field(2, 1, ENUM), Field(3, 1, ENUM), Field(4, 1, ENUM), Field(5, 4, UINT32),
        ))
        body.u8(L_ACTIVITY)
        body.u32(last.fitTime); body.u32(elapsed); body.u16(1)
        body.u8(0); body.u8(26); body.u8(1) // manual, activity, stop
        body.u32(last.fitTime + tzOffsetS)

        val data = body.bytes()

        // 14 baytlık başlık
        val header = Le()
        header.u8(14)
        header.u8(0x10)       // protokol 1.0
        header.u16(2100)      // profil 21.00
        header.u32(data.size.toLong())
        header.raw(".FIT".toByteArray(Charsets.US_ASCII))
        val h12 = header.bytes()
        header.u16(crc(0, h12, 0, h12.size))
        val headerBytes = header.bytes()

        val out = ByteArrayOutputStream(headerBytes.size + data.size + 2)
        out.write(headerBytes)
        out.write(data)
        val all = out.toByteArray()
        val fileCrc = crc(0, all, 0, all.size)
        out.write(fileCrc and 0xFF)
        out.write((fileCrc ushr 8) and 0xFF)
        return out.toByteArray()
    }

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    fun crc(start: Int, bytes: ByteArray, offset: Int, length: Int): Int {
        var crc = start
        for (i in offset until offset + length) {
            val b = bytes[i].toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc ushr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[b and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc ushr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(b ushr 4) and 0xF]
        }
        return crc
    }

    // ---------------------------------------------------------------- Yardımcılar

    private class Le {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v); u8(v ushr 8) }
        fun s16(v: Int) = u16(v)
        fun u32(v: Long) { u8(v.toInt()); u8((v ushr 8).toInt()); u8((v ushr 16).toInt()); u8((v ushr 24).toInt()) }
        fun s32(v: Int) = u32(v.toLong())
        fun raw(b: ByteArray) = out.write(b)
        fun bytes(): ByteArray = out.toByteArray()

        fun define(local: Int, global: Int, fields: List<Field>) {
            u8(0x40 or local)
            u8(0)     // ayrılmış
            u8(0)     // little-endian
            u16(global)
            u8(fields.size)
            for (f in fields) { u8(f.num); u8(f.size); u8(f.type) }
        }
    }

    private class Reader(private val b: ByteArray, private var i: Int) {
        private fun u8(): Int = b[i++].toInt() and 0xFF
        fun u16(): Int = u8() or (u8() shl 8)
        fun s16(): Int = u16().toShort().toInt()
        fun u32(): Long = (u8().toLong()) or (u8().toLong() shl 8) or (u8().toLong() shl 16) or (u8().toLong() shl 24)
        fun s32(): Int = u32().toInt()
    }
}
