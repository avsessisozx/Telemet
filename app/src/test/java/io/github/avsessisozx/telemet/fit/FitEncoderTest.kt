package io.github.avsessisozx.telemet.fit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

class FitEncoderTest {

    /** Örnek sürüş: İstanbul'dan kuzeye, 20 m/s (72 km/sa), hafif yokuş, 10 dakika. */
    private fun sampleRaw(seconds: Int = 600): ByteArray {
        val out = ByteArrayOutputStream()
        val start = 1_791_000_000_000L // 2026-10
        var dist = 0.0
        for (i in 0 until seconds) {
            val speed = if (i < 300) 20.0 else 35.0 // ikinci yarıda 126 km/sa
            if (i > 0) dist += speed
            out.write(
                FitEncoder.encodeRecord(
                    FitEncoder.Point(
                        utcMillis = start + i * 1000L,
                        latitude = 41.0 + dist / 111_000.0,
                        longitude = 29.0,
                        altitudeM = 50.0 + i * 0.2,
                        speedMs = speed,
                        distanceM = dist,
                        gradePercent = 1.5,
                        // Sırayla sola 30°, sağa 25° yatan virajlar
                        leanDeg = if (i % 20 < 10) 30.0 else -25.0,
                        lateralG = if (i % 20 < 10) 0.55 else -0.45,
                        longitudinalG = if (i == 450) -0.8 else 0.1,
                    ),
                ),
            )
        }
        return out.toByteArray()
    }

    @Test
    fun crcMatchesStandardCheckValue() {
        // FIT CRC'si CRC-16/ARC ile aynıdır; "123456789" için kontrol değeri 0xBB3D.
        val data = "123456789".toByteArray(Charsets.US_ASCII)
        assertEquals(0xBB3D, FitEncoder.crc(0, data, 0, data.size))
    }

    @Test
    fun recordsRoundTrip() {
        val raw = sampleRaw(10)
        assertEquals(10 * FitEncoder.RECORD_SIZE, raw.size)
        val d = FitEncoder.decodeRecords(raw)
        assertEquals(10, d.size)
        assertEquals(41.0, FitEncoder.degrees(d[0].latSemi), 1e-6)
        assertEquals(29.0, FitEncoder.degrees(d[0].lonSemi), 1e-6)
        assertEquals(20.0, d[5].enhancedSpeedMs, 1e-3)
        assertEquals(51.0, d[5].enhancedAltitudeM, 0.21)
        assertEquals(180.0, d[9].distanceM, 0.01)
        assertEquals(1.5, d[9].gradePercent, 0.01)
        assertEquals(30.0, d[0].leanDeg, 1e-4)
        assertEquals(0.55, d[0].lateralG, 1e-6)
        assertEquals(0.1, d[0].longitudinalG, 1e-6)
        assertEquals(kotlin.math.sqrt(0.55 * 0.55 + 0.1 * 0.1), d[0].gForce, 1e-6)
    }

    @Test
    fun brokenTailIsIgnored() {
        val raw = sampleRaw(5)
        val broken = raw + raw.copyOf(7)
        assertEquals(5, FitEncoder.decodeRecords(broken).size)
        assertEquals(raw.size, FitEncoder.validLength(broken))
    }

    @Test
    fun summaryOfRide() {
        val sum = FitEncoder.summarize(sampleRaw())
        assertNotNull(sum)
        sum!!
        assertEquals(600, sum.points)
        assertEquals(599L, sum.durationS)
        assertEquals(35.0, sum.maxSpeedMs, 0.001)
        assertEquals(16_480.0, sum.distanceM, 0.01)
        assertEquals(1_791_000_000_000L, sum.startUtcMillis)
        assertTrue(sum.ascentM > 100)
        assertEquals(30.0, sum.maxLeanLeftDeg, 1e-4)
        assertEquals(25.0, sum.maxLeanRightDeg, 1e-4)
        assertEquals(0.55, sum.maxLateralG, 1e-6)
        assertEquals(0.8, sum.maxBrakeG, 1e-6)
        assertEquals(0.1, sum.maxAccelG, 1e-6)
        assertNull(FitEncoder.summarize(ByteArray(0)))
    }

    @Test
    fun emptyRideGivesNoFile() {
        assertNull(FitEncoder.buildFile(ByteArray(0), 1_791_000_000_000L))
    }

    @Test
    fun fileHasValidHeaderAndCrc() {
        val fit = FitEncoder.buildFile(sampleRaw(), 1_791_000_000_000L)
        assertNotNull(fit)
        fit!!
        assertEquals(14, fit[0].toInt())
        assertEquals(".FIT", String(fit, 8, 4, Charsets.US_ASCII))
        val dataSize = (fit[4].toInt() and 0xFF) or ((fit[5].toInt() and 0xFF) shl 8) or
            ((fit[6].toInt() and 0xFF) shl 16) or ((fit[7].toInt() and 0xFF) shl 24)
        assertEquals(fit.size - 14 - 2, dataSize)
        // Başlık CRC'si ve dosya CRC'si: CRC dahil hesaplanınca 0 çıkmalı.
        assertEquals(0, FitEncoder.crc(0, fit, 0, 14))
        assertEquals(0, FitEncoder.crc(0, fit, 0, fit.size))
        assertTrue(fit.size > 600 * FitEncoder.RECORD_SIZE)

        // CI'da bağımsız bir FIT okuyucuyla (Python fitdecode) ayrıca doğrulanması için diske yaz.
        val dir = File("build/fit-sample").apply { mkdirs() }
        File(dir, "sample.fit").writeBytes(fit)
    }

    @Test
    fun mimoExtrasFileIsValid() {
        val plain = FitEncoder.buildFile(sampleRaw(), 1_791_000_000_000L)!!
        val fit = FitEncoder.buildFile(sampleRaw(), 1_791_000_000_000L, mimoExtras = true)!!
        assertEquals(0, FitEncoder.crc(0, fit, 0, 14))
        assertEquals(0, FitEncoder.crc(0, fit, 0, fit.size))
        // Her noktaya 3 bayt (kadans + güç), tanıma 2 alan (6 bayt) eklenir.
        assertEquals(plain.size + 600 * 3 + 6, fit.size)
        assertEquals(30, FitEncoder.mimoCadence(-30.4))
        assertEquals(90, FitEncoder.mimoCadence(120.0))
        assertEquals(125, FitEncoder.mimoPower(1.25))
        val dir = File("build/fit-sample").apply { mkdirs() }
        File(dir, "sample-mimo.fit").writeBytes(fit)
    }
}
