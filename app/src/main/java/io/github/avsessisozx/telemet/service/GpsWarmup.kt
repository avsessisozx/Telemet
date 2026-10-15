package io.github.avsessisozx.telemet.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.avsessisozx.telemet.util.AppLog

/**
 * Telemet ekranı açıldığı anda GPS'i uydu aramaya başlatır (Başlat'a basılmasını beklemeden).
 * Böylece Başlat'a basıldığında konum çoğu zaman hazır olur. Ekran kapanınca durur;
 * sürüş sırasında asıl GPS takibini servis yapar.
 */
@SuppressLint("MissingPermission")
object GpsWarmup : LocationListener {

    private var lm: LocationManager? = null
    private var running = false
    private val main = Handler(Looper.getMainLooper())

    var ready = false
        private set
    var satellites = 0
        private set

    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            for (i in 0 until status.satelliteCount) if (status.usedInFix(i)) used++
            satellites = used
            TelemetState.changed()
        }
    }

    fun start(context: Context) {
        if (running) return
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(LocationManager::class.java) ?: return
        if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return
        try {
            for (cmd in listOf("force_time_injection", "force_psds_injection", "force_xtra_injection")) {
                try { manager.sendExtraCommand(LocationManager.GPS_PROVIDER, cmd, null) } catch (_: Exception) { }
            }
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this, Looper.getMainLooper())
            manager.registerGnssStatusCallback(gnss, main)
            lm = manager
            running = true
            ready = false
        } catch (e: Exception) {
            AppLog.w("GPS ön ısıtma başlatılamadı", e)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            lm?.removeUpdates(this)
            lm?.unregisterGnssStatusCallback(gnss)
        } catch (_: Exception) {
        }
        lm = null
    }

    override fun onLocationChanged(location: Location) {
        if (!ready) {
            ready = true
            TelemetState.changed()
        }
    }

    @Deprecated("API 29 uyumluluğu")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) {
        ready = false
    }
}
