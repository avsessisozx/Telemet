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
    }

    @Test
    fun brokenTailIsIgnored() {
        val raw = sampleRaw(5)
        val broken = raw + raw.copyOf(7)
        assertEquals(5, FitEncoder.decodeRecords(broken).size)
        assertEquals(raw.size, FitEncoder.validLength(broken))
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
}
