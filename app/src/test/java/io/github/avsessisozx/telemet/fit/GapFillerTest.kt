package io.github.avsessisozx.telemet.fit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GapFillerTest {

    private fun p(sec: Long, lat: Double, speed: Double, dist: Double) = FitEncoder.Point(
        utcMillis = sec * 1000 + 400, latitude = lat, longitude = 29.0, altitudeM = 100.0,
        speedMs = speed, distanceM = dist, gradePercent = 0.0, leanDeg = 12.0,
    )

    @Test
    fun firstFixBackfillsFromStart() {
        // Başlat 1000. saniyede, ilk GPS sinyali 1035. saniyede geldi.
        val pts = GapFiller.pointsFor(null, p(1035, 41.0, 5.0, 0.0), startSecond = 1000)
        assertEquals(36, pts.size)
        assertEquals(1000_000L, pts.first().utcMillis)
        assertEquals(1035_000L, pts.last().utcMillis)
        assertTrue(pts.dropLast(1).all { it.speedMs == 0.0 && it.latitude == 41.0 && it.leanDeg == 0.0 })
        // Saniyeler ardışık, boşluk yok
        pts.zipWithNext().forEach { (a, b) -> assertEquals(1000L, b.utcMillis - a.utcMillis) }
    }

    @Test
    fun midRideGapIsInterpolated() {
        val prev = p(2000, 41.0, 20.0, 1000.0)
        val next = p(2010, 41.001, 30.0, 1200.0)
        val pts = GapFiller.pointsFor(prev, next, startSecond = null)
        assertEquals(10, pts.size) // 9 ara nokta + kendisi
        assertEquals(41.0005, pts[4].latitude, 1e-9)
        assertEquals(25.0, pts[4].speedMs, 1e-9)
        assertEquals(1100.0, pts[4].distanceM, 1e-9)
        assertEquals(2010_000L, pts.last().utcMillis)
    }

    @Test
    fun consecutiveSecondsNeedNoFill() {
        val pts = GapFiller.pointsFor(p(10, 41.0, 1.0, 0.0), p(11, 41.0, 1.0, 1.0), startSecond = null)
        assertEquals(1, pts.size)
    }

    @Test
    fun hugeGapIsNotFilled() {
        val pts = GapFiller.pointsFor(p(0, 41.0, 1.0, 0.0), p(GapFiller.MAX_FILL_S + 10, 42.0, 1.0, 1.0), startSecond = null)
        assertEquals(1, pts.size)
    }

    @Test
    fun padsUntilStop() {
        val pts = GapFiller.padUntil(p(500, 41.0, 15.0, 300.0), 505)
        assertEquals(5, pts.size)
        assertEquals(505_000L, pts.last().utcMillis)
        assertTrue(pts.all { it.distanceM == 300.0 && it.speedMs == 0.0 })
        assertEquals(0, GapFiller.padUntil(p(500, 41.0, 15.0, 300.0), 501).size)
    }
}
