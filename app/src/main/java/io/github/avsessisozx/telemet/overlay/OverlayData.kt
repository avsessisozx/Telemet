package io.github.avsessisozx.telemet.overlay

import io.github.avsessisozx.telemet.fit.FitEncoder
import kotlin.math.abs
import kotlin.math.floor

/**
 * Gösterge videosunun verisi: saniyelik sürüş noktaları, herhangi bir an için ara değerleriyle.
 * Saf Kotlin; birim testleriyle doğrulanır.
 */
class OverlayData(points: List<FitEncoder.Decoded>) {

    data class Sample(
        val speedMs: Double,
        val leanDeg: Double,
        val gForce: Double,
        val altitudeM: Double,
        val lat: Double,
        val lon: Double,
        val distanceM: Double,
    )

    /** Unix saniyesi. */
    val times: LongArray = LongArray(points.size) { points[it].fitTime + FitEncoder.FIT_EPOCH_S }
    val lat = DoubleArray(points.size) { FitEncoder.degrees(points[it].latSemi) }
    val lon = DoubleArray(points.size) { FitEncoder.degrees(points[it].lonSemi) }
    private val speed = DoubleArray(points.size) { points[it].enhancedSpeedMs }
    private val lean = DoubleArray(points.size) { points[it].leanDeg.let { v -> if (v.isNaN()) 0.0 else v } }
    private val g = DoubleArray(points.size) { points[it].gForce.let { v -> if (v.isNaN()) 0.0 else v } }
    private val alt = DoubleArray(points.size) { points[it].enhancedAltitudeM }
    private val dist = DoubleArray(points.size) { points[it].distanceM }

    val isEmpty: Boolean get() = times.isEmpty()
    val hasDynamics: Boolean = lean.any { abs(it) > 0.5 } || g.any { it > 0.05 }

    /** [atMs] anındaki değerler (Unix ms). Veri aralığının dışında kenar değer kullanılır. */
    fun at(atMs: Long): Sample? {
        if (times.isEmpty()) return null
        val t = atMs / 1000.0
        if (t <= times.first()) return sample(0, 0, 0.0)
        if (t >= times.last()) return sample(times.size - 1, times.size - 1, 0.0)
        // İkili arama: times[i] <= t < times[i+1]
        var lo = 0
        var hi = times.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) lo = mid else hi = mid
        }
        val span = (times[hi] - times[lo]).toDouble()
        val f = if (span > 0) (t - times[lo]) / span else 0.0
        // Uzun kesintide (GPS yok) ara değer uydurma: son değeri tut.
        return if (span > 5) sample(lo, lo, 0.0) else sample(lo, hi, f)
    }

    private fun lerp(a: DoubleArray, i: Int, j: Int, f: Double) = a[i] + (a[j] - a[i]) * f

    private fun sample(i: Int, j: Int, f: Double) = Sample(
        speedMs = lerp(speed, i, j, f),
        leanDeg = lerp(lean, i, j, f),
        gForce = lerp(g, i, j, f),
        altitudeM = lerp(alt, i, j, f),
        lat = lerp(lat, i, j, f),
        lon = lerp(lon, i, j, f),
        distanceM = lerp(dist, i, j, f),
    )

    /** [fromMs, toMs] arasındaki en büyük sola/sağa yatış (gösterge işaretleri için). */
    fun maxLean(fromMs: Long, toMs: Long): Pair<Double, Double> {
        var left = 0.0
        var right = 0.0
        val a = floor(fromMs / 1000.0).toLong()
        val b = toMs / 1000
        for (i in times.indices) {
            if (times[i] < a || times[i] > b) continue
            if (lean[i] > left) left = lean[i]
            if (-lean[i] > right) right = -lean[i]
        }
        return left to right
    }
}
