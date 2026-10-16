package io.github.avsessisozx.telemet.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedTest {

    private fun fix(utc: Long, lat: Double, speedNorth: Double) = GpsTracker.Fix(
        utcMillis = utc, elapsedMillis = 0, latitude = lat, longitude = 29.0, altitudeM = 100.0,
        speedMs = speedNorth, velocityNorthMs = speedNorth, velocityEastMs = 0.0, velocityDownMs = 0.0,
        horizontalAccuracyM = 5.0, verticalAccuracyM = 8.0, speedAccuracyMs = 0.5, satellites = 12,
    )

    private fun metersNorth(a: Double, b: Double) = (b - a) * Math.PI / 180 * 6_371_000.0

    /**
     * Kamera her saniye etiketi için tek bir konum görmeli ve ardışık saniyeler arası mesafe
     * gerçek hıza eşit olmalı. Eskiden saniye içindeki paketler farklı konum taşıyordu.
     */
    @Test
    fun packetsWithinOneSecondCarrySamePositionAndTrueSpeed() {
        val speed = 30.0 // 108 km/s
        // GPS ölçümleri saniyenin 0,4'ünde geliyor.
        val fixes = (0 until 5).map { i -> fix(1_000_400L + i * 1000, 41.0 + i * speed / 6_371_000.0 * 180 / Math.PI, speed) }
        val bySecond = mutableMapOf<Long, MutableList<Double>>()
        // 10 Hz gönderim döngüsü; döngü saniyeyle hizalı değil (ör. 0,07 kayık).
        var t = 1_000_470L
        while (t < 1_004_900L) {
            val f = fixes.last { it.utcMillis <= t }
            val p = GpsTracker.alignedToSecond(f, t - f.utcMillis)
            assertEquals(0L, p.utcMillis % 1000)
            assertTrue(p.utcMillis <= t)
            bySecond.getOrPut(p.utcMillis) { mutableListOf() }.add(p.latitude)
            t += 100
        }
        // Aynı saniye etiketinde tek konum (1 mm'den az fark)
        bySecond.values.forEach { assertTrue(metersNorth(it.min(), it.max()) < 0.001) }
        val secs = bySecond.keys.sorted()
        for ((a, b) in secs.zipWithNext()) {
            val d = metersNorth(bySecond[a]!!.first(), bySecond[b]!!.first())
            assertEquals(speed, d, 0.05) // %0,2'den az hata
        }
    }

    @Test
    fun extrapolateMovesTimeAndPositionTogether() {
        val f = fix(5_000_000L, 41.0, 20.0)
        val back = GpsTracker.extrapolate(f, -0.4)
        assertEquals(4_999_600L, back.utcMillis)
        assertEquals(-8.0, metersNorth(41.0, back.latitude), 0.01)
        val far = GpsTracker.extrapolate(f, 9.0) // sınırlanır
        assertEquals(5_001_500L, far.utcMillis)
        assertEquals(30.0, metersNorth(41.0, far.latitude), 0.01)
    }

    @Test
    fun filterPassesNormalRidingUntouched() {
        val sf = SpeedFilter()
        val speeds = listOf(10.0, 12.0, 15.0, 19.0, 24.0, 28.0, 30.0, 29.5, 25.0, 18.0, 12.0)
        speeds.forEachIndexed { i, s -> assertEquals(s, sf.filter(s, i.toDouble(), 0.5, 4.0), 1e-9) }
    }

    @Test
    fun filterLimitsImpossibleSpike() {
        val sf = SpeedFilter()
        sf.filter(27.0, 0.0, 0.5, 4.0)
        // Tek ölçüm 27 → 45 m/s (1 saniyede 18 m/s artış) olamaz.
        val out = sf.filter(45.0, 1.0, 0.5, 4.0)
        assertEquals(39.0, out, 1e-9)
        // Sonraki normal ölçüm geri döner.
        assertEquals(27.5, sf.filter(27.5, 2.0, 0.5, 4.0), 1e-9)
    }

    @Test
    fun filterHoldsUnreliableButNotForever() {
        val sf = SpeedFilter()
        sf.filter(20.0, 0.0, 0.5, 4.0)
        assertEquals(20.0, sf.filter(25.0, 1.0, 6.0, 4.0), 1e-9)
        assertEquals(20.0, sf.filter(25.0, 2.0, 6.0, 4.0), 1e-9)
        assertEquals(20.0, sf.filter(25.0, 3.0, 0.5, 60.0), 1e-9)
        // Dördüncü güvenilmez ölçümde artık kabul edilir (sürekli büyük hata bildiren telefonlar için).
        assertEquals(25.0, sf.filter(25.0, 4.0, 6.0, 4.0), 1e-9)
    }

    @Test
    fun filterRestartsAfterLongGap() {
        val sf = SpeedFilter()
        sf.filter(30.0, 0.0, 0.5, 4.0)
        assertEquals(0.0, sf.filter(0.0, 60.0, 0.5, 4.0), 1e-9)
    }
}
