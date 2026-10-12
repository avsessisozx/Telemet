package io.github.avsessisozx.telemet.service

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import io.github.avsessisozx.telemet.util.AppLog
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Sürüşü yedek olarak GPX dosyasına kaydeder. Kayıt bitince dosya
 * "İndirilenler/Telemet" klasörüne kopyalanır; böylece başka uygulamalarla da kullanılabilir.
 */
class GpxRecorder(private val context: Context) {
    private var writer: BufferedWriter? = null
    private var file: File? = null
    private var points = 0
    private var lastWrittenAt = 0L
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    val isRecording: Boolean get() = writer != null
    val fileName: String? get() = file?.name

    fun start() {
        if (writer != null) return
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "rides").apply { mkdirs() }
        val name = "surus_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".gpx"
        val f = File(dir, name)
        try {
            val w = f.bufferedWriter()
            w.write(
                """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Telemet" xmlns="http://www.topografix.com/GPX/1/1">
<trk><name>${name.removeSuffix(".gpx")}</name><type>motorcycling</type><trkseg>
""",
            )
            writer = w
            file = f
            points = 0
            AppLog.i("GPX kaydı başladı: $name")
        } catch (e: Exception) {
            AppLog.w("GPX dosyası açılamadı", e)
        }
    }

    /** Saniyede en fazla bir nokta yazar. */
    fun add(fix: GpsTracker.Fix) {
        val w = writer ?: return
        if (fix.utcMillis - lastWrittenAt < 900) return
        lastWrittenAt = fix.utcMillis
        try {
            w.write(
                "<trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.1f</ele><time>%s</time></trkpt>\n".format(
                    Locale.US, fix.latitude, fix.longitude, fix.altitudeM, iso.format(Date(fix.utcMillis)),
                ),
            )
            points++
            if (points % 30 == 0) w.flush()
        } catch (e: Exception) {
            AppLog.w("GPX yazma hatası", e)
        }
    }

    fun stop() {
        val w = writer ?: return
        val f = file
        writer = null
        try {
            w.write("</trkseg></trk>\n</gpx>\n")
            w.close()
        } catch (e: Exception) {
            AppLog.w("GPX kapatma hatası", e)
        }
        if (f == null) return
        if (points == 0) {
            f.delete()
            AppLog.i("GPX kaydında nokta yok, silindi")
            return
        }
        copyToDownloads(f)
    }

    private fun copyToDownloads(f: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, f.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/gpx+xml")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Telemet")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                AppLog.w("İndirilenler klasörüne yazılamadı")
                return
            }
            context.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            AppLog.i("GPX kaydedildi: İndirilenler/Telemet/${f.name} ($points nokta)")
        } catch (e: Exception) {
            AppLog.w("GPX kopyalanamadı (dosya uygulama klasöründe duruyor)", e)
        }
    }
}
