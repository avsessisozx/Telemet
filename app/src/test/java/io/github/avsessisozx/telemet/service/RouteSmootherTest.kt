package io.github.avsessisozx.telemet.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteSmootherTest {

    @Test
    fun `az nokta aynen doner`() {
        val lat = doubleArrayOf(41.0, 41.1, 41.2)
        val lon = doubleArrayOf(29.0, 29.1, 29.2)
        val (sLat, sLon) = RouteSmoother.smooth(lat, lon)
        assertTrue(lat.contentEquals(sLat))
        assertTrue(lon.contentEquals(sLon))
    }

    @Test
    fun `uclar sabit kalir`() {
        val lat = DoubleArray(20) { 41.0 + it * 0.001 }
        val lon = DoubleArray(20) { 29.0 + it * 0.001 }
        val (sLat, sLon) = RouteSmoother.smooth(lat, lon)
        assertEquals(lat.first(), sLat.first(), 1e-12)
        assertEquals(lon.first(), sLon.first(), 1e-12)
        assertEquals(lat.last(), sLat.last(), 1e-12)
        assertEquals(lon.last(), sLon.last(), 1e-12)
    }

    @Test
    fun `girdiyi degistirmez`() {
        val lat = DoubleArray(20) { 41.0 + (if (it % 2 == 0) 0.001 else -0.001) }
        val lon = DoubleArray(20) { 29.0 + it * 0.001 }
        val latCopy = lat.copyOf()
        val lonCopy = lon.copyOf()
        RouteSmoother.smooth(lat, lon)
        assertTrue(lat.contentEquals(latCopy))
        assertTrue(lon.contentEquals(lonCopy))
    }

    @Test
    fun `zikzak titremesi azalir`() {
        // Düz bir çizgi + her noktada küçük zikzak gürültüsü.
        val n = 50
        val lat = DoubleArray(n) { 41.0 + it * 0.0002 + (if (it % 2 == 0) 0.0001 else -0.0001) }
        val lon = DoubleArray(n) { 29.0 + it * 0.0002 }
        val (sLat, _) = RouteSmoother.smooth(lat, lon)
        // Komşular arası toplam dikey oynama (titreme ölçüsü) düşmeli.
        fun wobble(a: DoubleArray): Double {
            var w = 0.0
            for (i in 1 until a.size - 1) w += kotlin.math.abs(a[i] - (a[i - 1] + a[i + 1]) / 2)
            return w
        }
        assertTrue("yumuşatma titremeyi azaltmalı", wobble(sLat) < wobble(lat) * 0.6)
    }
}
