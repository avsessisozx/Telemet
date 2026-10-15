package io.github.avsessisozx.telemet.service

import android.os.Handler
import android.os.Looper
import io.github.avsessisozx.telemet.ble.CameraLink
import io.github.avsessisozx.telemet.protocol.CameraStatus

/** Servis ile ekran arasında paylaşılan anlık durum. Yalnızca ana iş parçacığında değiştirilir. */
object TelemetState {
    var serviceRunning = false
    var linkState = CameraLink.State.IDLE
    var linkDetail = ""
    var cameraName: String? = null
    var cameraModel: String? = null
    var verifyCode: Int? = null
    var cameraStatus: CameraStatus? = null

    var hasFix = false
    var fixAgeMs = Long.MAX_VALUE
    var satellitesUsed = 0
    var satellitesVisible = 0
    var speedKmh = 0.0
    var altitudeM = 0.0
    var horizontalAccuracyM = 0.0
    var gpsPacketsSent = 0L
    var gpsPacketsPerSecond = 0.0
    var mtu = 23
    /** Süren yedek kaydın adı (FIT + GPX). */
    var rideFile: String? = null
    var distanceKm = 0.0

    // Telefon sensörleri
    /** Kayıt tuşuna GPS hazır olmadan basıldı; sinyal gelince kayıt başlayacak. */
    var recordPending = false

    var hasGyro = false
    var hasBarometer = false
    /** Anlık yatış açısı (sola pozitif) ve toplam G. */
    var leanDeg = 0.0
    var gForce = 0.0
    /** Bu çalıştırmadaki en büyük yatış (işaretli). */
    var maxLeanDeg = 0.0

    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var notifyPending = false

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }

    /** Ekranı en fazla saniyede ~5 kez günceller. */
    fun changed() {
        if (notifyPending) return
        notifyPending = true
        main.postDelayed({
            notifyPending = false
            listeners.toList().forEach { it() }
        }, 200)
    }

    fun resetCamera() {
        linkState = CameraLink.State.IDLE
        linkDetail = ""
        verifyCode = null
        cameraStatus = null
        cameraModel = null
    }
}
