package io.github.avsessisozx.telemet.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.avsessisozx.telemet.util.AppLog
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Telefonun GPS'inden konum, hız ve yükseklik alır.
 * Google Play Hizmetleri kullanmaz (tamamen açık kaynak, doğrudan LocationManager).
 */
@SuppressLint("MissingPermission")
class GpsTracker(context: Context, private val onFix: (Fix) -> Unit) : LocationListener {

    /** Tek bir GPS ölçümü, kameranın beklediği birimlere hazır halde. */
    data class Fix(
        val utcMillis: Long,
        val elapsedMillis: Long,
        val latitude: Double,
        val longitude: Double,
        val altitudeM: Double,
        val speedMs: Double,
        val velocityNorthMs: Double,
        val velocityEastMs: Double,
        val velocityDownMs: Double,
        val horizontalAccuracyM: Double,
        val verticalAccuracyM: Double,
        val speedAccuracyMs: Double,
        val satellites: Int,
    )

    private val lm = context.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var previous: Location? = null
    private var smoothedDown = 0.0

    var satellitesUsed = 0
        private set
    var satellitesVisible = 0
        private set
    var lastFix: Fix? = null
        private set

    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satellitesUsed = used
            satellitesVisible = status.satelliteCount
        }
    }

    fun isGpsEnabled(): Boolean = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true

    fun start(): Boolean {
        if (running) return true
        val manager = lm ?: return false
        return try {
            // 100 ms istiyoruz; çoğu telefon 1 Hz verir, bazıları daha sık.
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100L, 0f, this, Looper.getMainLooper())
            @Suppress("DEPRECATION")
            manager.registerGnssStatusCallback(gnssCallback, main)
            running = true
            AppLog.i("GPS başlatıldı")
            true
        } catch (e: SecurityException) {
            AppLog.w("Konum izni yok", e)
            false
        } catch (e: IllegalArgumentException) {
            AppLog.w("GPS sağlayıcısı yok", e)
            false
        }
    }

    fun stop() {
        if (!running) return
        running = false
        lm?.removeUpdates(this)
        lm?.unregisterGnssStatusCallback(gnssCallback)
        previous = null
        lastFix = null
        AppLog.i("GPS durduruldu")
    }

    override fun onLocationChanged(location: Location) {
        val prev = previous
        previous = location

        var speed = if (location.hasSpeed()) location.speed.toDouble() else 0.0
        var bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else Double.NaN

        if (prev != null) {
            val dt = (location.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
            if (dt in 0.05..5.0) {
                if (!location.hasSpeed()) speed = prev.distanceTo(location) / dt
                if (bearingDeg.isNaN()) bearingDeg = prev.bearingTo(location).toDouble()
                if (location.hasAltitude() && prev.hasAltitude()) {
                    val rawDown = -(location.altitude - prev.altitude) / dt
                    // GPS yüksekliği gürültülü; yumuşatıyoruz.
                    smoothedDown = smoothedDown * 0.7 + rawDown.coerceIn(-50.0, 50.0) * 0.3
                }
            }
        }
        if (bearingDeg.isNaN()) bearingDeg = 0.0
        val rad = Math.toRadians(bearingDeg)

        val fix = Fix(
            utcMillis = location.time,
            elapsedMillis = location.elapsedRealtimeNanos / 1_000_000,
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeM = if (location.hasAltitude()) location.altitude else 0.0,
            speedMs = speed,
            velocityNorthMs = speed * cos(rad),
            velocityEastMs = speed * sin(rad),
            velocityDownMs = smoothedDown,
            horizontalAccuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else 10.0,
            verticalAccuracyM = if (location.hasVerticalAccuracy()) location.verticalAccuracyMeters.toDouble() else 15.0,
            speedAccuracyMs = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else 0.5,
            satellites = satellitesUsed.takeIf { it > 0 }
                ?: location.extras?.getInt("satellites", 0)?.takeIf { it > 0 }
                ?: 0,
        )
        lastFix = fix
        onFix(fix)
    }

    /** Son ölçümün yaşı (ms). */
    fun fixAgeMs(): Long {
        val f = lastFix ?: return Long.MAX_VALUE
        return SystemClock.elapsedRealtime() - f.elapsedMillis
    }

    // Android 10 (API 29) bu metotları zorunlu tutar; boş bırakılmaları gerekir.
    @Deprecated("API 29 uyumluluğu")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

    override fun onProviderEnabled(provider: String) {
        AppLog.i("Konum açıldı")
    }

    override fun onProviderDisabled(provider: String) {
        AppLog.w("Konum kapatıldı! GPS verisi gelmeyecek.")
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        /**
         * Son ölçümü hız vektörüyle kısa süre ileri taşır (en fazla 1 sn).
         * Telefon GPS'i genelde 1 Hz verir; DJI kumandası ise 10 Hz gönderir.
         * Bu sayede kameraya akıcı 10 Hz veri gider.
         */
        fun extrapolate(f: Fix, dtSeconds: Double): Fix {
            if (dtSeconds <= 0.0) return f
            val dt = dtSeconds.coerceAtMost(1.0)
            val dLat = f.velocityNorthMs * dt / EARTH_RADIUS_M
            val dLon = f.velocityEastMs * dt / (EARTH_RADIUS_M * cos(Math.toRadians(f.latitude)).coerceAtLeast(1e-6))
            return f.copy(
                utcMillis = f.utcMillis + (dtSeconds * 1000).toLong(),
                latitude = f.latitude + Math.toDegrees(dLat),
                longitude = f.longitude + Math.toDegrees(dLon),
                altitudeM = f.altitudeM - f.velocityDownMs * dt,
            )
        }

        fun headingDeg(f: Fix): Double {
            val h = Math.toDegrees(atan2(f.velocityEastMs, f.velocityNorthMs))
            return if (h < 0) h + 360 else h
        }

        fun groundSpeed(f: Fix): Double = sqrt(f.velocityNorthMs * f.velocityNorthMs + f.velocityEastMs * f.velocityEastMs)
    }
}
