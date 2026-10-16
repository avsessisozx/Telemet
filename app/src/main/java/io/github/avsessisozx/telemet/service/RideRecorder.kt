package io.github.avsessisozx.telemet.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.avsessisozx.telemet.fit.FitEncoder
import io.github.avsessisozx.telemet.fit.GapFiller
import io.github.avsessisozx.telemet.sensors.SensorHub
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
    /** Son yazılan nokta (boşluk doldurma için). */
    private var lastPoint: FitEncoder.Point? = null
    /** Yeni sürüşte Başlat'a basılan an (Unix saniyesi); ilk sinyale kadar olan süre doldurulur. */
    private var rideStartSecond: Long? = null

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
        val id = resumeId?.takeIf { File(dir, "$it.fitraw2").exists() }
            ?: ("surus_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
        val fitFile = File(dir, "$id.fitraw2")
        val gpxFile = File(dir, "$id.gpxpart")
        val resuming = fitFile.exists() && fitFile.length() > 0
        try {
            if (resuming) {
                restoreFrom(fitFile)
            } else {
                resetCounters()
                rideStartSecond = System.currentTimeMillis() / 1000
            }
            fitOut = FileOutputStream(fitFile, true)
            gpxOut = BufferedWriter(OutputStreamWriter(FileOutputStream(gpxFile, true), Charsets.UTF_8))
            rideId = id
            AppLog.i(if (resuming) "Backup recording resumed: $id ($points points, %.1f km)".format(Locale.US, distanceM / 1000) else "Backup recording started: $id")
            return id
        } catch (e: Exception) {
            AppLog.w("Could not open backup file", e)
            closeStreams()
            return null
        }
    }

    /** Saniyede en fazla bir nokta yazar. */
    fun add(fix: GpsTracker.Fix, sensors: SensorHub.Snapshot?) {
        if (fitOut == null) return
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
            leanDeg = sensors?.peakLeanDeg ?: 0.0,
            lateralG = sensors?.peakLateralG ?: 0.0,
            longitudinalG = sensors?.peakLongitudinalG ?: 0.0,
        )
        val toWrite = GapFiller.pointsFor(lastPoint, point, rideStartSecond)
        if (toWrite.size > 1) {
            if (lastPoint == null) {
                AppLog.i("Filled ${toWrite.size - 1} s before the first GPS fix with the first position")
            } else {
                AppLog.i("Filled a ${toWrite.size - 1} s GPS gap with interpolated values")
            }
        }
        rideStartSecond = null
        toWrite.forEach { writePoint(it) }
    }

    private fun writePoint(p: FitEncoder.Point) {
        val fit = fitOut ?: return
        try {
            fit.write(FitEncoder.encodeRecord(p))
            gpxOut?.write(
                "<trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.1f</ele><time>%s</time></trkpt>\n".format(
                    Locale.US, p.latitude, p.longitude, p.altitudeM, iso.format(Date(p.utcMillis)),
                ),
            )
            points++
            lastPoint = p
            // Her 10 noktada bir diske zorla; telefon aniden kapansa bile en fazla ~10 sn kaybolur.
            if (points % 10 == 0) {
                fit.flush()
                fit.fd.sync()
                gpxOut?.flush()
            }
        } catch (e: Exception) {
            AppLog.w("Could not write backup", e)
        }
    }

    /** Kaydı bitirir; FIT ve GPX dosyalarını İndirilenler/Telemet klasörüne çıkarır. */
    fun stop() {
        val id = rideId ?: return
        // Son sinyalden durdurma anına kadar boşluk kalmasın.
        lastPoint?.let { last -> GapFiller.padUntil(last, System.currentTimeMillis() / 1000).forEach { writePoint(it) } }
        closeStreams()
        rideId = null
        finish(id)
    }

    /** Sürüş bitmeden kapanmış eski kayıtları tamamlar. */
    private fun finishOrphans(except: String?) {
        // 0.1.3–0.1.4 sürümünden kalan yarım kayıtları yeni biçime çevir (sensör alanları sıfır).
        dir.listFiles { f -> f.name.endsWith(".fitraw") }?.forEach { old ->
            try {
                val oldSize = FitEncoder.RECORD_SIZE - 16
                val bytes = old.readBytes()
                val out = java.io.ByteArrayOutputStream()
                var i = 0
                while (i + oldSize <= bytes.size && bytes[i].toInt() == 1) {
                    out.write(bytes, i, oldSize)
                    out.write(ByteArray(16))
                    i += oldSize
                }
                File(dir, old.name + "2").writeBytes(out.toByteArray())
                old.delete()
            } catch (e: Exception) {
                AppLog.w("Could not convert old recording: ${old.name}", e)
            }
        }
        dir.listFiles { f -> f.name.endsWith(".fitraw2") }?.forEach { f ->
            val id = f.name.removeSuffix(".fitraw2")
            if (id != except) {
                AppLog.i("Finishing an interrupted ride: $id")
                finish(id)
            }
        }
    }

    private fun finish(id: String) {
        val fitRaw = File(dir, "$id.fitraw2")
        val gpxPart = File(dir, "$id.gpxpart")
        try {
            val raw = if (fitRaw.exists()) fitRaw.readBytes() else ByteArray(0)
            val fit = FitEncoder.buildFile(raw, fileTime(id), io.github.avsessisozx.telemet.util.Prefs(context).mimoExtras)
            if (fit == null) {
                AppLog.i("Ride has no GPS points, no backup file created")
                fitRaw.delete()
                gpxPart.delete()
                return
            }
            val summary = FitEncoder.summarize(raw)
            val count = summary?.points ?: 0
            val km = (summary?.distanceM ?: 0.0) / 1000
            val fitUri = saveToDownloads("$id.fit", "application/vnd.ant.fit", fit)
            val gpx = buildString {
                append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
                append("""<gpx version="1.1" creator="Telemet" xmlns="http://www.topografix.com/GPX/1/1">""").append('\n')
                append("<trk><name>").append(id).append("</name><type>motorcycling</type><trkseg>\n")
                if (gpxPart.exists()) append(completeLines(gpxPart.readText(Charsets.UTF_8)))
                append("</trkseg></trk>\n</gpx>\n")
            }
            val gpxUri = saveToDownloads("$id.gpx", "application/gpx+xml", gpx.toByteArray(Charsets.UTF_8))
            // Sürüş, İndirilenler'e yazılamasa bile listeye eklenir: ham noktalar uygulama klasöründe
            // saklandığı için dosyalar oradan yeniden üretilip paylaşılabilir.
            if (summary != null) RideStore.save(context, id, summary, fitUri, gpxUri, raw.copyOf(FitEncoder.validLength(raw)))
            fitRaw.delete()
            gpxPart.delete()
            if (fitUri != null && gpxUri != null) {
                AppLog.i("Ride saved: Downloads/$FOLDER/$id.fit and .gpx ($count points, %.1f km)".format(Locale.US, km))
            } else {
                AppLog.w("Ride saved in the app, but Downloads could not be written; share it from My rides")
            }
        } catch (e: Exception) {
            AppLog.w("Could not create ride file ($id)", e)
        }
    }

    private fun completeLines(text: String): String {
        // Yarım yazılmış son satırı at.
        val end = text.lastIndexOf('\n')
        return if (end < 0) "" else text.substring(0, end + 1)
    }

    private fun saveToDownloads(name: String, mime: String, bytes: ByteArray): Uri? = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            AppLog.w("Could not write to Downloads: $name")
            null
        } else {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
            uri
        }
    } catch (e: Exception) {
        AppLog.w("Could not save file: $name", e)
        null
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
        val gpxPart = File(dir, fitFile.name.removeSuffix(".fitraw2") + ".gpxpart")
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
            lastPoint = FitEncoder.Point(
                utcMillis = lastUtc,
                latitude = lastLat,
                longitude = lastLon,
                altitudeM = it.enhancedAltitudeM,
                speedMs = it.enhancedSpeedMs,
                distanceM = it.distanceM,
                gradePercent = it.gradePercent,
            )
        }
    }

    private fun resetCounters() {
        points = 0
        distanceM = 0.0
        lastSecond = 0
        hasLast = false
        lastPoint = null
        rideStartSecond = null
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
