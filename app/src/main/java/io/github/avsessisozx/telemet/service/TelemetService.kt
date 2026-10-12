package io.github.avsessisozx.telemet.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import io.github.avsessisozx.telemet.R
import io.github.avsessisozx.telemet.ble.CameraLink
import io.github.avsessisozx.telemet.protocol.CameraStatus
import io.github.avsessisozx.telemet.protocol.GpsPush
import io.github.avsessisozx.telemet.ui.MainActivity
import io.github.avsessisozx.telemet.util.AppLog
import io.github.avsessisozx.telemet.util.Prefs

/**
 * Sürüş boyunca arka planda çalışan servis: GPS'i okur, kameraya bağlı kalır
 * ve konum verisini DJI GPS kumandası gibi kameraya gönderir.
 */
class TelemetService : Service(), CameraLink.Listener {

    companion object {
        private const val ACTION_START = "io.github.avsessisozx.telemet.START"
        private const val ACTION_STOP = "io.github.avsessisozx.telemet.STOP"
        private const val ACTION_RECORD = "io.github.avsessisozx.telemet.RECORD"
        private const val CHANNEL_ID = "telemet"
        private const val NOTIFICATION_ID = 1
        /** DJI kumandası 10 Hz gönderiyor. */
        private const val PUSH_INTERVAL_MS = 100L
        /** Bundan eski ölçümler gönderilmez. */
        private const val MAX_FIX_AGE_MS = 2_500L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TelemetService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TelemetService::class.java).setAction(ACTION_STOP))
        }

        fun toggleRecord(context: Context) {
            context.startService(Intent(context, TelemetService::class.java).setAction(ACTION_RECORD))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var link: CameraLink
    private lateinit var gps: GpsTracker
    private lateinit var gpx: GpxRecorder
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    private var sentInWindow = 0
    private var windowStart = 0L
    private var lastPushedFixTime = 0L
    private var lastNotificationText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        link = CameraLink(this, prefs.remoteMac, this)
        gps = GpsTracker(this) { fix -> onFix(fix) }
        gpx = GpxRecorder(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTelemetry()
            ACTION_STOP -> {
                stopTelemetry()
                stopSelf()
            }
            ACTION_RECORD -> {
                if (!link.pressRecord()) AppLog.w("Kamera bağlı değil, kayıt komutu gönderilemedi")
            }
        }
        return START_NOT_STICKY
    }

    private fun startTelemetry() {
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Başlatılıyor…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
        if (started) return
        started = true
        TelemetState.serviceRunning = true
        TelemetState.cameraName = prefs.lastCameraName
        AppLog.i("Telemet başlatıldı (sürüm ${appVersion()})")

        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Telemet:ride")
            ?.apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }

        if (!gps.isGpsEnabled()) AppLog.w("Telefonun konumu kapalı. Ayarlardan açın.")
        gps.start()
        if (prefs.gpxEnabled) {
            gpx.start()
            TelemetState.gpxFile = gpx.fileName
        }
        link.connect(prefs.lastCameraAddress)
        windowStart = SystemClock.elapsedRealtime()
        main.post(pushLoop)
        TelemetState.changed()
    }

    private fun stopTelemetry() {
        if (!started) return
        started = false
        main.removeCallbacks(pushLoop)
        link.disconnect()
        gps.stop()
        gpx.stop()
        TelemetState.gpxFile = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        TelemetState.serviceRunning = false
        TelemetState.hasFix = false
        TelemetState.resetCamera()
        TelemetState.changed()
        AppLog.i("Telemet durduruldu")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopTelemetry()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- GPS

    private fun onFix(fix: GpsTracker.Fix) {
        TelemetState.hasFix = true
        TelemetState.speedKmh = fix.speedMs * 3.6
        TelemetState.altitudeM = fix.altitudeM
        TelemetState.horizontalAccuracyM = fix.horizontalAccuracyM
        TelemetState.satellitesUsed = gps.satellitesUsed
        TelemetState.satellitesVisible = gps.satellitesVisible
        if (gpx.isRecording) gpx.add(fix)
        if (!prefs.smoothGps) push(fix)
        TelemetState.changed()
    }

    private val pushLoop = object : Runnable {
        override fun run() {
            if (!started) return
            val age = gps.fixAgeMs()
            TelemetState.fixAgeMs = age
            if (age > MAX_FIX_AGE_MS && TelemetState.hasFix) {
                TelemetState.hasFix = false
                AppLog.w("GPS sinyali kayboldu")
                TelemetState.changed()
            }
            if (prefs.smoothGps) {
                val fix = gps.lastFix
                if (fix != null && age <= MAX_FIX_AGE_MS) {
                    push(GpsTracker.extrapolate(fix, age / 1000.0))
                }
            }
            updateRate()
            main.postDelayed(this, PUSH_INTERVAL_MS)
        }
    }

    private fun push(fix: GpsTracker.Fix) {
        if (!prefs.smoothGps && fix.utcMillis == lastPushedFixTime) return
        lastPushedFixTime = fix.utcMillis
        val payload = GpsPush.from(
            utcMillis = fix.utcMillis,
            latitude = fix.latitude,
            longitude = fix.longitude,
            altitudeM = fix.altitudeM,
            velocityNorthMs = fix.velocityNorthMs,
            velocityEastMs = fix.velocityEastMs,
            velocityDownMs = fix.velocityDownMs,
            horizontalAccuracyM = fix.horizontalAccuracyM,
            verticalAccuracyM = fix.verticalAccuracyM,
            speedAccuracyMs = fix.speedAccuracyMs,
            satellites = fix.satellites.takeIf { it > 0 } ?: gps.satellitesUsed,
        ).toPayload()
        if (link.sendGps(payload)) {
            TelemetState.gpsPacketsSent++
            sentInWindow++
        }
    }

    private fun updateRate() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - windowStart
        if (elapsed >= 2_000) {
            TelemetState.gpsPacketsPerSecond = sentInWindow * 1000.0 / elapsed
            sentInWindow = 0
            windowStart = now
            TelemetState.changed()
            refreshNotification()
        }
    }

    // ---------------------------------------------------------------- Kamera olayları

    override fun onLinkState(state: CameraLink.State, detail: String) {
        TelemetState.linkState = state
        TelemetState.linkDetail = detail
        TelemetState.changed()
        refreshNotification()
    }

    override fun onCameraReady(address: String, name: String?, model: String?) {
        prefs.lastCameraAddress = address
        if (name != null) prefs.lastCameraName = name
        TelemetState.cameraName = name ?: prefs.lastCameraName
        TelemetState.cameraModel = model
        TelemetState.changed()
    }

    override fun onCameraStatus(status: CameraStatus) {
        TelemetState.cameraStatus = status
        TelemetState.changed()
    }

    override fun onVerifyCode(code: Int?) {
        TelemetState.verifyCode = code
        TelemetState.changed()
    }

    override fun onMtu(mtu: Int) {
        TelemetState.mtu = mtu
    }

    // ---------------------------------------------------------------- Bildirim

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, "Sürüş telemetrisi", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Kameraya GPS gönderilirken gösterilir"
        nm.createNotificationChannel(channel)
    }

    private fun notificationText(): String {
        val cam = when (TelemetState.linkState) {
            CameraLink.State.READY -> "Kamera bağlı"
            CameraLink.State.HANDSHAKE -> "Kamerada onay bekleniyor"
            CameraLink.State.SCANNING -> "Kamera aranıyor"
            CameraLink.State.CONNECTING -> "Bağlanılıyor"
            CameraLink.State.RECONNECTING -> "Yeniden bağlanılıyor"
            CameraLink.State.IDLE -> "Kamera bağlı değil"
        }
        val rec = if (TelemetState.cameraStatus?.isRecording == true) " • ● KAYIT" else ""
        val gpsText = if (TelemetState.hasFix) "GPS %d uydu".format(TelemetState.satellitesUsed) else "GPS aranıyor"
        return "$cam$rec • $gpsText"
    }

    private fun refreshNotification() {
        if (!started) return
        val text = notificationText()
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TelemetService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val record = PendingIntent.getService(
            this, 2, Intent(this, TelemetService::class.java).setAction(ACTION_RECORD),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_telemet)
            .setContentTitle("Telemet")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Kayıt", record).build())
            .addAction(Notification.Action.Builder(null, "Durdur", stop).build())
            .build()
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
