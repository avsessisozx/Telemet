package io.github.avsessisozx.telemet.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import io.github.avsessisozx.telemet.util.AppLog

/**
 * Telefonun hareket sensörlerini okur (internet gerekmez):
 * - Jiroskop + yerçekimi: yatış açısı ve yanal G (GPS hızıyla birlikte)
 * - Barometre (varsa): GPS'ten çok daha kararlı yükseklik ve eğim
 * Boyuna G (fren/hızlanma) GPS hızındaki değişimden hesaplanır.
 *
 * Sensörler ayrı bir iş parçacığında okunur; değerler [snapshot] ile alınır.
 */
class SensorHub(context: Context) : SensorEventListener {

    data class Snapshot(
        val leanDeg: Double,
        val lateralG: Double,
        val longitudinalG: Double,
        /** Son çağrıdan beri görülen en uç değerler (saniyelik kayıt için). */
        val peakLeanDeg: Double,
        val peakLateralG: Double,
        val peakLongitudinalG: Double,
    ) {
        val totalG: Double get() = RideDynamics.totalG(lateralG, longitudinalG)
    }

    private val sm = context.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY)
    private val accel: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val baro: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)

    val hasGyro: Boolean get() = gyro != null
    val hasBarometer: Boolean get() = baro != null

    private var thread: HandlerThread? = null
    private var running = false

    // Durum (sensör iş parçacığında yazılır, kilitle okunur)
    private val lock = Any()
    private val gravity = FloatArray(3)
    private var hasGravity = false
    private var yawRate = 0.0          // yumuşatılmış, rad/s, sola pozitif
    private var speedMs = 0.0          // son GPS hızı
    private var lean = 0.0
    private var latG = 0.0
    private var lonG = 0.0
    private var peakLean = 0.0
    private var peakLat = 0.0
    private var peakLon = 0.0

    // GPS hızından boyuna ivme
    private var prevSpeed = 0.0
    private var prevSpeedAt = 0L

    // Barometre
    private var pressureHpa = Double.NaN
    private var baroOffset = Double.NaN // GPS yüksekliği - barometrik yükseklik
    private var lastBaroAlt = Double.NaN
    private var lastBaroAt = 0L
    private var baroVerticalSpeed = 0.0 // yukarı pozitif, m/s

    fun start() {
        if (running || sm == null) return
        running = true
        val t = HandlerThread("Telemet-sensors").apply { start() }
        thread = t
        val h = Handler(t.looper)
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, h) }
        (gravitySensor ?: accel)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, h) }
        baro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI, h) }
        AppLog.i(
            "Sensors: gyroscope " + (if (gyro != null) "yes" else "NO") +
                ", gravity " + (if (gravitySensor != null) "yes" else if (accel != null) "from accelerometer" else "NO") +
                ", barometer " + (if (baro != null) "yes" else "no (altitude from GPS)"),
        )
        if (gyro == null) AppLog.w("No gyroscope: lean angle and cornering G cannot be calculated")
    }

    fun stop() {
        if (!running) return
        running = false
        sm?.unregisterListener(this)
        thread?.quitSafely()
        thread = null
        synchronized(lock) {
            hasGravity = false
            yawRate = 0.0
            lean = 0.0; latG = 0.0; lonG = 0.0
            peakLean = 0.0; peakLat = 0.0; peakLon = 0.0
            prevSpeedAt = 0L
            baroOffset = Double.NaN
            lastBaroAlt = Double.NaN
        }
    }

    /** GPS her yeni ölçümde çağırır: hız ve boyuna G güncellenir. */
    fun onGpsSpeed(speed: Double, elapsedMillis: Long) {
        synchronized(lock) {
            if (prevSpeedAt != 0L) {
                val dt = (elapsedMillis - prevSpeedAt) / 1000.0
                val g = RideDynamics.longitudinalG(prevSpeed, speed, dt)
                lonG = lonG * 0.4 + g * 0.6
                peakLon = RideDynamics.peak(peakLon, lonG)
            }
            prevSpeed = speed
            prevSpeedAt = elapsedMillis
            speedMs = speed
            if (speed < RideDynamics.MIN_SPEED_MS) {
                lean = 0.0
                latG = 0.0
            }
        }
    }

    /**
     * Barometre varsa GPS yüksekliğini düzeltir: kısa vadede barometre (kararlı),
     * uzun vadede GPS (mutlak seviye). Barometre yoksa GPS yüksekliği aynen döner.
     */
    fun calibrateAltitude(gpsAltitude: Double, verticalAccuracyM: Double) {
        synchronized(lock) {
            val baroAlt = lastBaroAlt
            if (baroAlt.isNaN()) return
            val diff = gpsAltitude - baroAlt
            baroOffset = when {
                baroOffset.isNaN() -> diff
                verticalAccuracyM < 25 -> baroOffset + (diff - baroOffset) * 0.02
                else -> baroOffset
            }
        }
    }

    /** Barometre ayarlıysa barometrik yükseklik, değilse verilen GPS yüksekliği. */
    fun altitude(gpsAltitude: Double): Double = synchronized(lock) {
        if (lastBaroAlt.isNaN() || baroOffset.isNaN()) gpsAltitude else lastBaroAlt + baroOffset
    }

    /** Barometreden düşey hız (aşağı pozitif; kameranın beklediği yön). Barometre yoksa null. */
    fun baroVelocityDown(): Double? = synchronized(lock) {
        if (lastBaroAlt.isNaN()) null else -baroVerticalSpeed
    }

    fun snapshot(resetPeaks: Boolean): Snapshot = synchronized(lock) {
        val s = Snapshot(lean, latG, lonG, peakLean, peakLat, peakLon)
        if (resetPeaks) {
            peakLean = lean
            peakLat = latG
            peakLon = lonG
        }
        s
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> synchronized(lock) {
                gravity[0] = e.values[0]; gravity[1] = e.values[1]; gravity[2] = e.values[2]
                hasGravity = true
            }
            Sensor.TYPE_ACCELEROMETER -> synchronized(lock) {
                // Yerçekimi sensörü yoksa ivmeölçeri yavaş süzgeçten geçirip yerçekimi say.
                val a = 0.05f
                for (i in 0..2) gravity[i] = if (hasGravity) gravity[i] * (1 - a) + e.values[i] * a else e.values[i]
                hasGravity = true
            }
            Sensor.TYPE_GYROSCOPE -> synchronized(lock) {
                if (!hasGravity) return
                val up = RideDynamics.normalize(gravity) ?: return
                val measured = RideDynamics.dot(e.values, up)
                yawRate = yawRate * 0.9 + measured * 0.1 // ~0,2 sn yumuşatma
                val v = speedMs
                lean = RideDynamics.leanDeg(v, yawRate)
                latG = RideDynamics.lateralG(v, yawRate, lean)
                peakLean = RideDynamics.peak(peakLean, lean)
                peakLat = RideDynamics.peak(peakLat, latG)
            }
            Sensor.TYPE_PRESSURE -> synchronized(lock) {
                val p = e.values[0].toDouble()
                pressureHpa = if (pressureHpa.isNaN()) p else pressureHpa * 0.8 + p * 0.2
                val alt = SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, pressureHpa.toFloat()).toDouble()
                val now = SystemClock.elapsedRealtime()
                if (!lastBaroAlt.isNaN() && lastBaroAt != 0L) {
                    val dt = (now - lastBaroAt) / 1000.0
                    if (dt in 0.05..5.0) {
                        val vs = (alt - lastBaroAlt) / dt
                        baroVerticalSpeed = baroVerticalSpeed * 0.85 + vs.coerceIn(-30.0, 30.0) * 0.15
                    }
                }
                lastBaroAlt = alt
                lastBaroAt = now
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
