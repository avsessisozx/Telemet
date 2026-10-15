package io.github.avsessisozx.telemet.service

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import io.github.avsessisozx.telemet.fit.FitEncoder
import io.github.avsessisozx.telemet.util.AppLog
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Her sürüşü (Başlat → Durdur) telefonda ayrıca FIT ve GPX olarak kaydeder.
 * Kamera bağlantısı kopsa ya da kamera veriyi videoya yazmasa bile elde bir yedek kalır;
 * FIT dosyası DJI Studio / DJI Mimo'da "FIT Dosyasını İçe Aktar" ile kullanılabilir.
 *
 * Noktalar sürüş boyunca hemen diske yazılır. Uygulama ya da telefon sürüş ortasında kapanırsa
 * kayıt bir sonraki açılışta kaldığı yerden sürer ya da tamamlanıp İndirilenler'e çıkarılır.
 */
class RideRecorder(private val context: Context) {

    companion object {
        private const val FOLDER = "Telemet"
        /** Bu kadar uzun kesintiden sonra gelen nokta mesafeye eklenmez (başka bir yere taşınmış olabilir). */
        private const val MAX_GAP_FOR_DISTANCE_S = 600.0
    }

    private val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "rides").apply { mkdirs() }
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    var rideId: String? = null
        private set
    private var fitOut: FileOutputStream? = null
    private var gpxOut: BufferedWriter? = null

    var points = 0
        private set
    var distanceM = 0.0
        private set

    private var lastSecond = 0L
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastUtc = 0L
    private var hasLast = false

    // Eğim: son ~60 m içindeki yükseklik farkından hesaplanır.
    private val gradeWindow = ArrayDeque<Pair<Double, Double>>() // (mesafe, yükseklik)
    private var grade = 0.0

    val isRecording: Boolean get() = fitOut != null

    /**
     * Kaydı başlatır. [resumeId] verilir ve dosyaları duruyorsa aynı sürüşe devam edilir.
     * Bitmemiş başka sürüş varsa önce o tamamlanıp kaydedilir.
     */
    fun start(resumeId: String?): String? {
        if (isRecording) return rideId
        finishOrphans(except = resumeId)
        val id = resumeId?.takeIf { File(dir, "$it.fitraw").exists() }
            ?: ("surus_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
        val fitFile = File(dir, "$id.fitraw")
        val gpxFile = File(dir, "$id.gpxpart")
        val resuming = fitFile.exists() && fitFile.length() > 0
        try {
            if (resuming) restoreFrom(fitFile) else resetCounters()
            fitOut = FileOutputStream(fitFile, true)
            gpxOut = BufferedWriter(OutputStreamWriter(FileOutputStream(gpxFile, true), Charsets.UTF_8))
            rideId = id
            AppLog.i(if (resuming) "Yedek kayıt sürüyor: $id ($points nokta, %.1f km)".format(distanceM / 1000) else "Yedek kayıt başladı: $id")
            return id
        } catch (e: Exception) {
            AppLog.w("Yedek kayıt dosyası açılamadı", e)
            closeStreams()
            return null
        }
    }

    /** Saniyede en fazla bir nokta yazar. */
    fun add(fix: GpsTracker.Fix) {
        val fit = fitOut ?: return
        val second = fix.utcMillis / 1000
        if (second <= lastSecond) return
        lastSecond = second

        if (hasLast) {
            val gapS = (fix.utcMillis - lastUtc) / 1000.0
            val d = haversine(lastLat, lastLon, fix.latitude, fix.longitude)
            // Dururken GPS titremesi mesafe biriktirmesin.
            val moving = fix.speedMs > 0.8 || d > fix.horizontalAccuracyM.coerceAtLeast(5.0)
            if (gapS in 0.0..MAX_GAP_FOR_DISTANCE_S && moving && fix.horizontalAccuracyM < 50) distanceM += d
        }
        hasLast = true
        lastLat = fix.latitude
        lastLon = fix.longitude
        lastUtc = fix.utcMillis
        updateGrade(fix.altitudeM)

        val point = FitEncoder.Point(
            utcMillis = fix.utcMillis,
            latitude = fix.latitude,
            longitude = fix.longitude,
            altitudeM = fix.altitudeM,
            speedMs = fix.speedMs,
            distanceM = distanceM,
            gradePercent = grade,
        )
        try {
            fit.write(FitEncoder.encodeRecord(point))
            gpxOut?.write(
                "<trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.1f</ele><time>%s</time></trkpt>\n".format(
                    Locale.US, fix.latitude, fix.longitude, fix.altitudeM, iso.format(Date(fix.utcMillis)),
                ),
            )
            points++
            // Her 10 noktada bir diske zorla; telefon aniden kapansa bile en fazla ~10 sn kaybolur.
            if (points % 10 == 0) {
                fit.flush()
                fit.fd.sync()
                gpxOut?.flush()
            }
        } catch (e: Exception) {
            AppLog.w("Yedek kayda yazılamadı", e)
        }
    }

    /** Kaydı bitirir; FIT ve GPX dosyalarını İndirilenler/Telemet klasörüne çıkarır. */
    fun stop() {
        val id = rideId ?: return
        closeStreams()
        rideId = null
        finish(id)
    }

    /** Sürüş bitmeden kapanmış eski kayıtları tamamlar. */
    private fun finishOrphans(except: String?) {
        dir.listFiles { f -> f.name.endsWith(".fitraw") }?.forEach { f ->
            val id = f.name.removeSuffix(".fitraw")
            if (id != except) {
                AppLog.i("Yarım kalmış sürüş kaydı tamamlanıyor: $id")
                finish(id)
            }
        }
    }

    private fun finish(id: String) {
        val fitRaw = File(dir, "$id.fitraw")
        val gpxPart = File(dir, "$id.gpxpart")
        try {
            val raw = if (fitRaw.exists()) fitRaw.readBytes() else ByteArray(0)
            val fit = FitEncoder.buildFile(raw, fileTime(id))
            if (fit == null) {
                AppLog.i("Sürüşte GPS noktası yok, yedek dosya oluşturulmadı")
                fitRaw.delete()
                gpxPart.delete()
                return
            }
            val count = raw.size / FitEncoder.RECORD_SIZE
            val km = FitEncoder.decodeRecords(raw).lastOrNull()?.distanceM?.div(1000) ?: 0.0
            val fitOk = saveToDownloads("$id.fit", "application/vnd.ant.fit", fit)
            val gpx = buildString {
                append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
                append("""<gpx version="1.1" creator="Telemet" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
                append("<trk><name>").append(id).append("</name><type>motorcycling</type><trkseg>\n")
                if (gpxPart.exists()) append(completeLines(gpxPart.readText(Charsets.UTF_8)))
                append("</trkseg></trk>\n</gpx>\n")
            }
            val gpxOk = saveToDownloads("$id.gpx", "application/gpx+xml", gpx.toByteArray(Charsets.UTF_8))
            if (fitOk && gpxOk) {
                fitRaw.delete()
                gpxPart.delete()
                AppLog.i("Sürüş kaydedildi: İndirilenler/$FOLDER/$id.fit ve .gpx ($count nokta, %.1f km)".format(km))
            } else {
                AppLog.w("Sürüş dosyaları tam kaydedilemedi; bir sonraki açılışta tekrar denenecek")
            }
        } catch (e: Exception) {
            AppLog.w("Sürüş dosyası oluşturulamadı ($id)", e)
        }
    }

    private fun completeLines(text: String): String {
        // Yarım yazılmış son satırı at.
        val end = text.lastIndexOf('\n')
        return if (end < 0) "" else text.substring(0, end + 1)
    }

    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            AppLog.w("İndirilenler klasörüne yazılamadı: $name")
            false
        } else {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            true
        }
    } catch (e: Exception) {
        AppLog.w("Dosya kaydedilemedi: $name", e)
        false
    }

    private fun fileTime(id: String): Long = try {
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(id.removePrefix("surus_"))?.time ?: System.currentTimeMillis()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }

    private fun restoreFrom(fitFile: File) {
        resetCounters()
        val raw = fitFile.readBytes()
        val valid = FitEncoder.validLength(raw)
        if (valid < raw.size) {
            // Yarım yazılmış son noktayı kes.
            RandomAccessFile(fitFile, "rw").use { it.setLength(valid.toLong()) }
        }
        val gpxPart = File(dir, fitFile.name.removeSuffix(".fitraw") + ".gpxpart")
        if (gpxPart.exists()) {
            val text = gpxPart.readText(Charsets.UTF_8)
            val keep = completeLines(text)
            if (keep.length < text.length) gpxPart.writeText(keep, Charsets.UTF_8)
        }
        val decoded = FitEncoder.decodeRecords(raw)
        points = decoded.size
        decoded.lastOrNull()?.let {
            distanceM = it.distanceM
            lastSecond = it.fitTime + FitEncoder.FIT_EPOCH_S
            lastLat = FitEncoder.degrees(it.latSemi)
            lastLon = FitEncoder.degrees(it.lonSemi)
            lastUtc = lastSecond * 1000
            hasLast = true
        }
    }

    private fun resetCounters() {
        points = 0
        distanceM = 0.0
        lastSecond = 0
        hasLast = false
        gradeWindow.clear()
        grade = 0.0
    }

    private fun updateGrade(altitude: Double) {
        gradeWindow.addLast(distanceM to altitude)
        while (gradeWindow.size > 2 && distanceM - gradeWindow.first().first > 60) gradeWindow.removeFirst()
        val (d0, a0) = gradeWindow.first()
        val dd = distanceM - d0
        val raw = if (dd >= 25) ((altitude - a0) / dd * 100).coerceIn(-30.0, 30.0) else 0.0
        grade = grade * 0.6 + raw * 0.4
    }

    private fun closeStreams() {
        try {
            fitOut?.flush()
            fitOut?.fd?.sync()
            fitOut?.close()
        } catch (_: Exception) {
        }
        try {
            gpxOut?.close()
        } catch (_: Exception) {
        }
        fitOut = null
        gpxOut = null
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(a), sqrt(1 - a))
    }
}
