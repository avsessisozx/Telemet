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
import io.github.avsessisozx.telemet.sensors.SensorHub
import io.github.avsessisozx.telemet.ui.MainActivity
import io.github.avsessisozx.telemet.util.AppLog
import io.github.avsessisozx.telemet.util.LocaleHelper
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
        private const val ACTION_RESUME = "io.github.avsessisozx.telemet.RESUME"
        private const val CHANNEL_ID = "telemet"
        private const val NOTIFICATION_ID = 1
        /** DJI kumandası 10 Hz gönderiyor. */
        private const val PUSH_INTERVAL_MS = 100L
        /** Bundan eski ölçümler gönderilmez. */
        private const val MAX_FIX_AGE_MS = 2_500L
        /** GPS bu kadar süre sessiz kalırsa konum güncellemeleri yeniden kaydedilir. */
        private const val GPS_RESTART_AFTER_MS = 8_000L
        private const val SUMMARY_INTERVAL_MS = 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TelemetService::class.java).setAction(ACTION_START))
        }

        /** Yarım kalmış sürüşe kaldığı yerden devam eder (uygulama yeniden açıldığında). */
        fun resume(context: Context) {
            context.startForegroundService(Intent(context, TelemetService::class.java).setAction(ACTION_RESUME))
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
    private lateinit var ride: RideRecorder
    private lateinit var sensors: SensorHub
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false
    private val autoRecord = AutoRecord()

    private var sentInWindow = 0
    private var windowStart = 0L
    private var lastPushedFixTime = 0L
    private var lastNotificationText = ""

    // GPS bekçisi ve dakikalık özet
    private var gpsLostAt = 0L
    private var lastGpsRestartAt = 0L
    private var gpsRestarts = 0
    private var fixesInSummary = 0
    private var packetsInSummary = 0
    private var summaryStart = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        link = CameraLink(this, prefs.remoteMac, this)
        gps = GpsTracker(this) { fix -> onFix(fix) }
        ride = RideRecorder(this)
        sensors = SensorHub(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                prefs.shouldRun = true
                startTelemetry(resume = false)
            }
            ACTION_RESUME -> {
                prefs.shouldRun = true
                startTelemetry(resume = true)
            }
            ACTION_STOP -> {
                prefs.shouldRun = false
                stopTelemetry()
                stopSelf()
            }
            null -> {
                // Sistem servisi kapatıp yeniden başlattı: kullanıcı Durdur'a basmadıysa kaldığı yerden devam et.
                if (prefs.shouldRun) {
                    AppLog.w("Telemet was closed by the system, resuming where it left off")
                    startTelemetry(resume = true)
                } else {
                    stopSelf()
                }
            }
            ACTION_RECORD -> onRecordPressed()
        }
        // Sistem kapatırsa yeniden başlat (navigasyon uygulamaları gibi).
        return START_STICKY
    }

    private fun startTelemetry(resume: Boolean) {
        try {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(getString(R.string.notif_starting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // Android bazı durumlarda arka plandan ön plan servisine izin vermez (izinler eksikse).
            AppLog.w("Could not start in background; open Telemet and tap Start", e)
            stopSelf()
            return
        }
        if (started) return
        started = true
        TelemetState.serviceRunning = true
        TelemetState.cameraName = prefs.lastCameraName
        AppLog.i("Telemet started (version ${appVersion()})")

        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Telemet:ride")
            ?.apply { setReferenceCounted(false); acquire(12 * 60 * 60 * 1000L) }

        if (!gps.isGpsEnabled()) AppLog.w("Phone location is off. Turn it on in settings.")
        gps.start()
        sensors.start()
        TelemetState.hasGyro = sensors.hasGyro
        TelemetState.hasBarometer = sensors.hasBarometer
        TelemetState.maxLeanDeg = 0.0
        if (prefs.backupEnabled) {
            val id = ride.start(if (resume) prefs.activeRideId else null)
            prefs.activeRideId = id
            TelemetState.rideFile = id
        }
        link.connect(prefs.lastCameraAddress)
        windowStart = SystemClock.elapsedRealtime()
        summaryStart = windowStart
        gpsLostAt = 0L
        gpsRestarts = 0
        autoRecord.reset()
        main.post(pushLoop)
        TelemetState.changed()
    }

    private fun stopTelemetry() {
        if (!started) return
        started = false
        main.removeCallbacks(pushLoop)
        link.disconnect()
        gps.stop()
        sensors.stop()
        ride.stop()
        prefs.activeRideId = null
        TelemetState.rideFile = null
        TelemetState.distanceKm = 0.0
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        TelemetState.serviceRunning = false
        TelemetState.hasFix = false
        TelemetState.recordPending = false
        TelemetState.resetCamera()
        TelemetState.changed()
        AppLog.i("Telemet stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        if (prefs.shouldRun && started) {
            // Kullanıcı durdurmadı; sistem kapatıyor. Kaydı bitirmeden kapat ki yeniden başlayınca devam edilsin.
            AppLog.w("Telemet is being closed by the system")
            main.removeCallbacksAndMessages(null)
            link.disconnect()
            gps.stop()
        sensors.stop()
            started = false
            TelemetState.serviceRunning = false
            TelemetState.changed()
        } else {
            stopTelemetry()
        }
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Uygulama son kullanılanlardan kaydırılıp kapatıldı; sürüş arka planda sürüyor.
        if (started) AppLog.i("App screen closed, recording continues in the background")
        super.onTaskRemoved(rootIntent)
    }

    // ---------------------------------------------------------------- Kayıt tuşu

    /**
     * GPS hazır değilken kayda basılırsa kayıt bekletilir ve sinyal gelir gelmez başlar;
     * böylece videonun ilk saniyelerinde de konum verisi olur. İkinci basış beklemeden başlatır.
     */
    private fun onRecordPressed() {
        val recording = TelemetState.cameraStatus?.isRecording == true
        if (TelemetState.recordPending) {
            TelemetState.recordPending = false
            AppLog.i("Starting recording without waiting for GPS")
            if (!link.pressRecord()) AppLog.w("Camera not connected, record command not sent")
            TelemetState.changed()
            return
        }
        if (!recording && !TelemetState.hasFix) {
            TelemetState.recordPending = true
            AppLog.i("Waiting for GPS; recording starts automatically when the signal arrives (press again to start now)")
            TelemetState.changed()
            return
        }
        if (!link.pressRecord()) AppLog.w("Camera not connected, record command not sent")
    }

    /** Hareketle otomatik kayıt (Ayarlar'da açıksa). */
    private fun runAutoRecord(now: Long) {
        if (!prefs.autoRecord) return
        val speed = if (TelemetState.hasFix) TelemetState.speedKmh else Double.NaN
        val action = autoRecord.update(now, speed, TelemetState.cameraStatus?.isRecording, link.state == CameraLink.State.READY)
        if (action == AutoRecord.Action.NONE) return
        AppLog.i(if (action == AutoRecord.Action.START) "Moving: starting camera recording automatically" else "Stopped for a while: stopping camera recording automatically")
        if (!link.pressRecord()) autoRecord.commandFailed()
    }

    /** Bekleyen kayıt varsa GPS ve kamera hazır olunca başlatır. */
    private fun startPendingRecord() {
        if (!TelemetState.recordPending || !TelemetState.hasFix) return
        if (link.state != CameraLink.State.READY) return
        TelemetState.recordPending = false
        AppLog.i("GPS ready, starting recording")
        link.pressRecord()
        TelemetState.changed()
    }

    // ---------------------------------------------------------------- GPS

    /** Barometre varsa yüksekliği ve düşey hızı ondan alır (GPS yüksekliği gürültülü). */
    private fun withSensors(fix: GpsTracker.Fix): GpsTracker.Fix =
        fix.copy(
            altitudeM = sensors.altitude(fix.altitudeM),
            velocityDownMs = sensors.baroVelocityDown() ?: fix.velocityDownMs,
        )

    private fun onFix(rawFix: GpsTracker.Fix) {
        sensors.onGpsSpeed(rawFix.speedMs, rawFix.elapsedMillis)
        sensors.calibrateAltitude(rawFix.altitudeM, rawFix.verticalAccuracyM)
        val fix = withSensors(rawFix)
        TelemetState.hasFix = true
        TelemetState.speedKmh = fix.speedMs * 3.6
        TelemetState.altitudeM = fix.altitudeM
        TelemetState.horizontalAccuracyM = fix.horizontalAccuracyM
        TelemetState.satellitesUsed = gps.satellitesUsed
        TelemetState.satellitesVisible = gps.satellitesVisible
        fixesInSummary++
        if (gpsLostAt != 0L) {
            val gap = (SystemClock.elapsedRealtime() - gpsLostAt) / 1000
            AppLog.i("GPS signal is back ($gap s gap)")
            gpsLostAt = 0L
        }
        if (ride.isRecording) {
            ride.add(fix, sensors.snapshot(resetPeaks = true))
            TelemetState.distanceKm = ride.distanceM / 1000
        }
        if (!prefs.smoothGps) push(fix)
        TelemetState.changed()
    }

    private val pushLoop = object : Runnable {
        override fun run() {
            if (!started) return
            val age = gps.fixAgeMs()
            TelemetState.fixAgeMs = age
            val now = SystemClock.elapsedRealtime()
            if (age > MAX_FIX_AGE_MS && TelemetState.hasFix) {
                TelemetState.hasFix = false
                gpsLostAt = now
                AppLog.w("GPS signal lost")
                TelemetState.changed()
            }
            // GPS bekçisi: sinyal vardı ama uzun süre ölçüm gelmiyorsa güncellemeleri yeniden kaydet.
            // (İlk sinyal aranırken dokunulmaz; yeniden başlatmak ilk sinyali geciktirebilir.)
            val silentFor = age
            if (gps.lastFix != null && silentFor > GPS_RESTART_AFTER_MS && now - lastGpsRestartAt > GPS_RESTART_AFTER_MS * 2) {
                lastGpsRestartAt = now
                gpsRestarts++
                if (gpsRestarts <= 3 || gpsRestarts % 10 == 0) {
                    AppLog.w("GPS silent for ${silentFor / 1000} s, restarting (attempt $gpsRestarts)")
                }
                gps.restart()
            }
            if (now - summaryStart >= SUMMARY_INTERVAL_MS) {
                AppLog.i(
                    "Last 1 min: $fixesInSummary GPS fixes, $packetsInSummary packets to camera" +
                        (if (ride.isRecording) ", total %.1f km".format(java.util.Locale.US, ride.distanceM / 1000) else ""),
                )
                fixesInSummary = 0
                packetsInSummary = 0
                summaryStart = now
            }
            if (prefs.smoothGps) {
                val fix = gps.lastFix
                if (fix != null && age <= MAX_FIX_AGE_MS) {
                    push(withSensors(GpsTracker.alignedToSecond(fix, age)))
                }
            }
            val snap = sensors.snapshot(resetPeaks = false)
            TelemetState.leanDeg = snap.leanDeg
            TelemetState.gForce = snap.totalG
            if (kotlin.math.abs(snap.leanDeg) > kotlin.math.abs(TelemetState.maxLeanDeg)) TelemetState.maxLeanDeg = snap.leanDeg
            startPendingRecord()
            runAutoRecord(now)
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
            packetsInSummary++
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
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        channel.description = getString(R.string.notif_channel_desc)
        nm.createNotificationChannel(channel)
    }

    private fun notificationText(): String {
        val cam = when (TelemetState.linkState) {
            CameraLink.State.READY -> getString(R.string.cam_ready)
            CameraLink.State.HANDSHAKE -> getString(R.string.cam_handshake)
            CameraLink.State.SCANNING -> getString(R.string.cam_scanning)
            CameraLink.State.CONNECTING -> getString(R.string.cam_connecting)
            CameraLink.State.RECONNECTING -> getString(R.string.cam_reconnecting)
            CameraLink.State.IDLE -> getString(R.string.cam_idle)
        }
        val rec = if (TelemetState.cameraStatus?.isRecording == true) " • " + getString(R.string.notif_rec) else ""
        val gpsText = if (TelemetState.hasFix) getString(R.string.notif_gps_sats, TelemetState.satellitesUsed) else getString(R.string.notif_gps_searching)
        val dist = if (TelemetState.rideFile != null) " • %.1f km".format(TelemetState.distanceKm) else ""
        return "$cam$rec • $gpsText$dist"
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
            .addAction(Notification.Action.Builder(null, getString(R.string.notif_record), record).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.btn_stop), stop).build())
            .build()
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }
}
