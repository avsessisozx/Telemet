package io.github.avsessisozx.telemet.protocol

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.roundToInt

/** Komut kümeleri ve kimlikleri (CmdSet, CmdID). */
object Cmd {
    const val SET_GENERAL = 0x00
    const val ID_VERSION = 0x00
    const val ID_KEY_REPORT = 0x11
    const val ID_GPS_PUSH = 0x17
    const val ID_CONNECT = 0x19
    const val ID_POWER_MODE = 0x1A

    const val SET_CAMERA = 0x1D
    const val ID_STATUS_PUSH = 0x02
    const val ID_RECORD_CONTROL = 0x03
    const val ID_MODE_SWITCH = 0x04
    const val ID_STATUS_SUBSCRIBE = 0x05
    const val ID_NEW_STATUS_PUSH = 0x06
}

/** CmdType baytı değerleri. */
object CmdType {
    const val NO_RESPONSE = 0x00
    const val RESPONSE_OR_NOT = 0x01
    const val WAIT_RESULT = 0x02
    const val ACK_NO_RESPONSE = 0x20
}

internal class LeWriter(size: Int) {
    val bytes = ByteArray(size)
    private var pos = 0

    fun u8(v: Int) = apply { bytes[pos++] = v.toByte() }
    fun u16(v: Int) = apply { u8(v); u8(v ushr 8) }
    fun u32(v: Int) = apply { u16(v); u16(v ushr 16) }
    fun f32(v: Float) = u32(java.lang.Float.floatToIntBits(v))
    fun raw(src: ByteArray, fieldSize: Int) = apply {
        for (i in 0 until fieldSize) bytes[pos++] = if (i < src.size) src[i] else 0
    }
}

internal class LeReader(private val b: ByteArray) {
    var pos = 0
    val remaining: Int get() = b.size - pos

    fun u8(): Int = b[pos++].toInt() and 0xFF
    fun u16(): Int = u8() or (u8() shl 8)
    fun i32(): Int = u16() or (u16() shl 16)
    fun u32(): Long = i32().toLong() and 0xFFFFFFFFL
    fun f32(): Float = java.lang.Float.intBitsToFloat(i32())
    fun raw(n: Int): ByteArray = b.copyOfRange(pos, pos + n).also { pos += n }
}

/** Bağlantı isteği (0x0019) komut çerçevesi. Hem kumanda hem kamera aynı yapıyı kullanır. */
data class ConnectionRequest(
    val deviceId: Int,
    val mac: ByteArray,
    val fwVersion: Int = 0,
    /** 0: kamera geçmişe göre karar verir, 1: doğrulama iste, 2: doğrulama sonucu */
    val verifyMode: Int,
    /** verifyMode 0/1 iken doğrulama kodu, 2 iken sonuç (0 = izin verildi) */
    val verifyData: Int,
) {
    fun toPayload(): ByteArray = LeWriter(SIZE)
        .u32(deviceId)
        .u8(mac.size)
        .raw(mac, 16)
        .u32(fwVersion)
        .u8(0) // conidx
        .u8(verifyMode)
        .u16(verifyData)
        .u32(0)
        .bytes

    companion object {
        const val SIZE = 33

        fun parse(p: ByteArray): ConnectionRequest? {
            if (p.size < 29) return null
            val r = LeReader(p)
            val id = r.i32()
            val macLen = r.u8().coerceAtMost(16)
            val macField = r.raw(16)
            val fw = r.i32()
            r.u8()
            val mode = r.u8()
            val data = r.u16()
            return ConnectionRequest(id, macField.copyOf(macLen), fw, mode, data)
        }
    }
}

/** Bağlantı isteğine verilen yanıt (0x0019 yanıt çerçevesi). */
data class ConnectionResponse(
    val deviceId: Int,
    val retCode: Int,
    val cameraIndex: Int = 0,
) {
    fun toPayload(): ByteArray = LeWriter(9).u32(deviceId).u8(retCode).u32(cameraIndex).bytes

    companion object {
        fun parse(p: ByteArray): ConnectionResponse? {
            if (p.size < 5) return null
            val r = LeReader(p)
            val id = r.i32()
            val ret = r.u8()
            val idx = if (r.remaining >= 4) r.i32() else 0
            return ConnectionResponse(id, ret, idx)
        }
    }
}

/**
 * GPS veri gönderimi (0x0017).
 * Hız alanları cm/s, yükseklik ve doğruluk mm cinsindendir.
 */
data class GpsPush(
    val yearMonthDay: Int,
    /** (saat + 8) * 10000 + dakika * 100 + saniye. Saat UTC'dir; DJI protokolü 8 ekler. */
    val hourMinuteSecond: Int,
    val longitudeE7: Int,
    val latitudeE7: Int,
    val heightMm: Int,
    val speedNorthCms: Float,
    val speedEastCms: Float,
    val speedDownCms: Float,
    val verticalAccuracyMm: Int,
    val horizontalAccuracyMm: Int,
    val speedAccuracyCms: Int,
    val satellites: Int,
) {
    fun toPayload(): ByteArray = LeWriter(SIZE)
        .u32(yearMonthDay)
        .u32(hourMinuteSecond)
        .u32(longitudeE7)
        .u32(latitudeE7)
        .u32(heightMm)
        .f32(speedNorthCms)
        .f32(speedEastCms)
        .f32(speedDownCms)
        .u32(verticalAccuracyMm)
        .u32(horizontalAccuracyMm)
        .u32(speedAccuracyCms)
        .u32(satellites)
        .bytes

    companion object {
        const val SIZE = 48

        fun parse(p: ByteArray): GpsPush? {
            if (p.size < SIZE) return null
            val r = LeReader(p)
            return GpsPush(
                r.i32(), r.i32(), r.i32(), r.i32(), r.i32(),
                r.f32(), r.f32(), r.f32(),
                r.i32(), r.i32(), r.i32(), r.i32(),
            )
        }

        /**
         * Telefonun konum verisinden GPS paketi üretir.
         * @param utcMillis konumun UTC zamanı
         */
        fun from(
            utcMillis: Long,
            latitude: Double,
            longitude: Double,
            altitudeM: Double,
            velocityNorthMs: Double,
            velocityEastMs: Double,
            velocityDownMs: Double,
            horizontalAccuracyM: Double,
            verticalAccuracyM: Double,
            speedAccuracyMs: Double,
            satellites: Int,
        ): GpsPush {
            val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            c.timeInMillis = utcMillis
            val ymd = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
            // DJI örnek kodu UTC saatine 8 ekliyor (Pekin saati); kamera bunu geri çeviriyor.
            val hms = (c.get(Calendar.HOUR_OF_DAY) + 8) * 10000 + c.get(Calendar.MINUTE) * 100 + c.get(Calendar.SECOND)
            return GpsPush(
                yearMonthDay = ymd,
                hourMinuteSecond = hms,
                longitudeE7 = (longitude * 1e7).roundToInt(),
                latitudeE7 = (latitude * 1e7).roundToInt(),
                heightMm = (altitudeM * 1000.0).roundToInt(),
                speedNorthCms = (velocityNorthMs * 100.0).toFloat(),
                speedEastCms = (velocityEastMs * 100.0).toFloat(),
                speedDownCms = (velocityDownMs * 100.0).toFloat(),
                verticalAccuracyMm = (verticalAccuracyM * 1000.0).roundToInt().coerceAtLeast(0),
                horizontalAccuracyMm = (horizontalAccuracyM * 1000.0).roundToInt().coerceAtLeast(0),
                speedAccuracyCms = (speedAccuracyMs * 100.0).roundToInt().coerceAtLeast(0),
                satellites = satellites.coerceAtLeast(0),
            )
        }
    }
}

/** Kamera durum aboneliği (0x1D05). Frekans yalnızca 20 (2 Hz) olabilir. */
object StatusSubscription {
    const val MODE_OFF = 0
    const val MODE_SINGLE = 1
    const val MODE_PERIODIC = 2
    const val MODE_PERIODIC_AND_ON_CHANGE = 3

    fun payload(mode: Int = MODE_PERIODIC_AND_ON_CHANGE, freq: Int = 20): ByteArray =
        LeWriter(6).u8(mode).u8(freq).u32(0).bytes
}

/** Tuş bildirimi (0x0011). Kayıt tuşuna kısa basmak kaydı başlatır/durdurur. */
object KeyReport {
    const val KEY_RECORD = 0x01
    const val KEY_QS = 0x02
    const val KEY_SNAPSHOT = 0x03

    const val MODE_EVENT = 0x01
    const val EVENT_SHORT_PRESS = 0x00

    fun payload(key: Int, mode: Int = MODE_EVENT, value: Int = EVENT_SHORT_PRESS): ByteArray =
        LeWriter(4).u8(key).u8(mode).u16(value).bytes
}

/** Kamera durum bildirimi (0x1D02). */
data class CameraStatus(
    val mode: Int,
    val status: Int,
    val resolution: Int,
    val fpsIndex: Int,
    val eisMode: Int,
    val recordTimeSec: Int,
    val remainCapacityMb: Long,
    val remainTimeSec: Long,
    val powerMode: Int,
    val tempWarning: Int,
    /** -1: bilinmiyor */
    val batteryPercent: Int,
) {
    val isRecording: Boolean get() = status == STATUS_RECORDING || status == STATUS_PRE_RECORDING
    val isSleeping: Boolean get() = powerMode == 3

    val modeName: String
        get() = when (mode) {
            0x00 -> "Slow motion"
            0x01 -> "Video"
            0x02 -> "Timelapse"
            0x05 -> "Photo"
            0x0A -> "Hyperlapse"
            0x1A -> "Live stream"
            0x23 -> "UVC stream"
            0x28 -> "Night video"
            0x34 -> "Subject tracking"
            else -> "Mode 0x%02X".format(mode)
        }

    val statusName: String
        get() = when (status) {
            0x00 -> "Screen off"
            0x01 -> "Ready"
            0x02 -> "Playback"
            STATUS_RECORDING -> "Recording"
            STATUS_PRE_RECORDING -> "Pre-recording"
            else -> "Status 0x%02X".format(status)
        }

    companion object {
        const val STATUS_RECORDING = 0x03
        const val STATUS_PRE_RECORDING = 0x05

        fun parse(p: ByteArray): CameraStatus? {
            if (p.size < 31) return null
            val r = LeReader(p)
            val mode = r.u8()
            val status = r.u8()
            val res = r.u8()
            val fps = r.u8()
            val eis = r.u8()
            val recTime = r.u16()
            r.u8() // fov
            r.u8() // photo ratio
            r.u16() // countdown
            r.u16() // timelapse interval
            r.u16() // timelapse duration
            val capacity = r.u32()
            r.u32() // remaining photos
            val remainTime = r.u32()
            r.u8() // user mode
            val power = r.u8()
            r.u8() // next mode flag
            val temp = r.u8()
            val battery = if (p.size > 37) p[37].toInt() and 0xFF else -1
            return CameraStatus(mode, status, res, fps, eis, recTime, capacity, remainTime, power, temp, battery)
        }
    }
}

object DjiCamera {
    /**
     * Reklam (advertising) paketinin desteklenen bir DJI kameraya ait olup olmadığını kontrol eder:
     * üretici verisinin 0., 1. ve 4. baytları 0xAA, 0x08, 0xFA olmalı.
     */
    fun isCameraAdvertisement(raw: ByteArray?): Boolean {
        if (raw == null) return false
        var i = 0
        while (i < raw.size) {
            val len = raw[i].toInt() and 0xFF
            if (len == 0 || i + len + 1 > raw.size) break
            val type = raw[i + 1].toInt() and 0xFF
            val dataStart = i + 2
            val dataLen = len - 1
            if (type == 0xFF && dataLen >= 5 &&
                (raw[dataStart].toInt() and 0xFF) == 0xAA &&
                (raw[dataStart + 1].toInt() and 0xFF) == 0x08 &&
                (raw[dataStart + 4].toInt() and 0xFF) == 0xFA
            ) return true
            i += len + 1
        }
        return false
    }

    fun modelName(deviceId: Int): String? {
        val candidates = listOf(deviceId and 0xFFFF, (deviceId ushr 16) and 0xFFFF)
        for (id in candidates) {
            when (id) {
                0xFF33 -> return "Osmo Action 4"
                0xFF44 -> return "Osmo Action 5 Pro"
                0xFF55 -> return "Osmo Action 6"
                0xFF66 -> return "Osmo 360"
            }
        }
        return null
    }
}
