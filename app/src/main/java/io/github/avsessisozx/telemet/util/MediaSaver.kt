package io.github.avsessisozx.telemet.util

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore

/** Dosyaları telefonun ortak klasörlerine yazar (İndirilenler/Telemet, Filmler/Telemet). */
object MediaSaver {
    private const val FOLDER = "Telemet"

    /** İndirilenler/Telemet'e yazar; başarısızsa null. */
    fun saveDownload(context: Context, name: String, mime: String, bytes: ByteArray): Uri? = try {
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

    /**
     * Filmler/Telemet'te boş bir video oluşturur ve [write] ile doldurur. [write] hata verirse ya da
     * false dönerse (iptal) yarım dosya silinir.
     */
    fun writeMovie(context: Context, name: String, write: (ParcelFileDescriptor) -> Boolean): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER)
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        var ok = false
        try {
            resolver.openFileDescriptor(uri, "rw")?.use { ok = write(it) }
        } finally {
            if (ok) {
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            } else {
                try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            }
        }
        return if (ok) uri else null
    }
}
