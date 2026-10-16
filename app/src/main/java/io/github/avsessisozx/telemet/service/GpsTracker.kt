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
    private val speedFilter = SpeedFilter()

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
        injectAssistData(manager)
        return try {
            // 100 ms istiyoruz; çoğu telefon 1 Hz verir, bazıları daha sık.
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100L, 0f, this, Looper.getMainLooper())
            @Suppress("DEPRECATION")
            manager.registerGnssStatusCallback(gnssCallback, main)
            running = true
            AppLog.i("GPS started")
            true
        } catch (e: SecurityException) {
            AppLog.w("No location permission", e)
            false
        } catch (e: IllegalArgumentException) {
            AppLog.w("No GPS provider", e)
            false
        }
    }

    /**
     * İnternet varsa uyduların yörünge bilgisini ve saati indirip GPS'e verir (A-GPS).
     * İlk sinyal süresini 30–60 sn'den birkaç saniyeye indirir. İnternet yoksa sessizce atlanır.
     */
    private fun injectAssistData(manager: LocationManager) {
        for (cmd in listOf("force_time_injection", "force_psds_injection", "force_xtra_injection")) {
            try {
                manager.sendExtraCommand(LocationManager.GPS_PROVIDER, cmd, null)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Konum güncellemelerini yeniden kaydeder. Bazı telefonlar (özellikle MIUI) uygulama arka plandayken
     * güncellemeleri sessizce kesebiliyor; yeniden kayıt çoğu zaman akışı geri getiriyor.
     */
    fun restart() {
        if (!running) return
        val manager = lm ?: return
        try {
            manager.removeUpdates(this)
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100L, 0f, this, Looper.getMainLooper())
        } catch (e: Exception) {
            AppLog.w("Could not restart GPS", e)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        lm?.removeUpdates(this)
        lm?.unregisterGnssStatusCallback(gnssCallback)
        previous = null
        lastFix = null
        speedFilter.reset()
        AppLog.i("GPS stopped")
    }

    override fun onLocationChanged(location: Location) {
        val prev = previous
        previous = location

        var speed = if (location.hasSpeed()) location.speed.toDouble() else Double.NaN
        var bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else Double.NaN

        if (prev != null) {
            val dt = (location.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1e9
            if (dt in 0.05..5.0) {
                if (speed.isNaN()) speed = prev.distanceTo(location) / dt
                if (bearingDeg.isNaN()) bearingDeg = prev.bearingTo(location).toDouble()
                if (location.hasAltitude() && prev.hasAltitude()) {
                    val rawDown = -(location.altitude - prev.altitude) / dt
                    // GPS yüksekliği gürültülü; yumuşatıyoruz.
                    smoothedDown = smoothedDown * 0.7 + rawDown.coerceIn(-50.0, 50.0) * 0.3
                }
            }
        }
        if (bearingDeg.isNaN()) bearingDeg = 0.0
        if (speed.isNaN()) speed = 0.0
        speed = speedFilter.filter(
            speedMs = speed,
            timeS = location.elapsedRealtimeNanos / 1e9,
            speedAccuracyMs = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond.toDouble() else Double.NaN,
            horizontalAccuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else Double.NaN,
        )
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
            satellites = satelliteCountFor(location),
        )
        lastFix = fix
        onFix(fix)
    }

    private var mockLogged = false

    /**
     * Kameraya gidecek uydu sayısı. Konum varsa en az 4 uydu kullanılmış demektir; sayı henüz
     * bilinmiyorsa 0 göndermek kameranın veriyi "sinyal yok" diye atmasına yol açabilir.
     * Sahte konum (test) uygulamaları uydu bildirmez; o durumda iyi bir sinyal varsayılır.
     */
    private fun satelliteCountFor(location: Location): Int {
        val known = satellitesUsed.takeIf { it > 0 }
            ?: location.extras?.getInt("satellites", 0)?.takeIf { it > 0 }
            ?: 0
        val mock = if (android.os.Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider
        if (mock && !mockLogged) {
            mockLogged = true
            AppLog.w("Mock location detected (test mode). Data is coming from a mock location app.")
        }
        return if (mock) maxOf(known, 10) else maxOf(known, 4)
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
        AppLog.i("Location turned on")
    }

    override fun onProviderDisabled(provider: String) {
        AppLog.w("Location turned off! No GPS data will arrive.")
    }

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        /**
         * Ölçümü hız vektörüyle [dtSeconds] kadar ileri (ya da eksi değerde geri) taşır.
         * Konum ve zaman aynı miktarda kaydırılır; en fazla ±1,5 sn.
         */
        fun extrapolate(f: Fix, dtSeconds: Double): Fix {
            val dt = dtSeconds.coerceIn(-1.5, 1.5)
            if (dt == 0.0) return f
            val dLat = f.velocityNorthMs * dt / EARTH_RADIUS_M
            val dLon = f.velocityEastMs * dt / (EARTH_RADIUS_M * cos(Math.toRadians(f.latitude)).coerceAtLeast(1e-6))
            return f.copy(
                utcMillis = f.utcMillis + Math.round(dt * 1000),
                latitude = f.latitude + Math.toDegrees(dLat),
                longitude = f.longitude + Math.toDegrees(dLon),
                altitudeM = f.altitudeM - f.velocityDownMs * dt,
            )
        }

        /**
         * Kameraya giden paketteki saat yalnızca saniye hassasiyetinde. Saniyede 10 paket gönderirken
         * her pakete o anki (salise farklı) konumu koyarsak, kamera aynı saniye etiketli farklı konumlar
         * görür; hızı konumlardan hesaplarsa %5–10 sapma çıkar. Bu yüzden konum, paketin taşıdığı
         * tam saniyeye taşınır: aynı saniyedeki bütün paketler aynı konumu taşır ve ardışık saniyeler
         * arasındaki mesafe gerçekten 1 saniyelik yola karşılık gelir.
         *
         * @param ageMs ölçümden bu yana geçen süre
         */
        fun alignedToSecond(f: Fix, ageMs: Long): Fix {
            val nowUtc = f.utcMillis + ageMs.coerceAtLeast(0)
            val second = Math.floorDiv(nowUtc, 1000L) * 1000L
            val aligned = extrapolate(f, (second - f.utcMillis) / 1000.0)
            return aligned.copy(utcMillis = second)
        }

        fun headingDeg(f: Fix): Double {
            val h = Math.toDegrees(atan2(f.velocityEastMs, f.velocityNorthMs))
            return if (h < 0) h + 360 else h
        }

        fun groundSpeed(f: Fix): Double = sqrt(f.velocityNorthMs * f.velocityNorthMs + f.velocityEastMs * f.velocityEastMs)
    }
}
