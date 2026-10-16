package io.github.avsessisozx.telemet.util

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/**
 * Uygulamanın geçici paylaşım klasöründeki dosyaları başka uygulamalara (WhatsApp, Drive, e-posta…)
 * salt okunur verir. AndroidX FileProvider'ın küçük bir karşılığı; ek kütüphane gerekmez.
 */
class ShareProvider : ContentProvider() {

    companion object {
        private const val DIR = "share"

        fun authority(ctx: Context) = ctx.packageName + ".share"

        /** Paylaşım klasörünü temizleyip hazırlar. */
        fun freshDir(ctx: Context): File {
            val d = File(ctx.cacheDir, DIR)
            d.deleteRecursively()
            d.mkdirs()
            return d
        }

        fun uriFor(ctx: Context, file: File): Uri =
            Uri.Builder().scheme("content").authority(authority(ctx)).appendPath(file.name).build()

        /** Dosyaları paylaşım menüsüyle gönderir. */
        fun share(ctx: Context, files: List<File>, subject: String, chooserTitle: String) {
            val uris = ArrayList(files.map { uriFor(ctx, it) })
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
            send.type = "application/octet-stream"
            send.putExtra(Intent.EXTRA_SUBJECT, subject)
            send.clipData = android.content.ClipData.newRawUri(subject, uris[0]).also { c ->
                uris.drop(1).forEach { c.addItem(android.content.ClipData.Item(it)) }
            }
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }

    private fun fileFor(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        // Yalnızca paylaşım klasöründeki düz dosya adları; "../" gibi yollar reddedilir.
        if (!name.matches(Regex("[A-Za-z0-9._ -]+")) || name.startsWith(".")) return null
        val f = File(File(ctx.cacheDir, DIR), name)
        return if (f.isFile) f else null
    }

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String {
        val n = uri.lastPathSegment.orEmpty().lowercase()
        return when {
            n.endsWith(".gpx") -> "application/gpx+xml"
            n.endsWith(".txt") -> "text/plain"
            n.endsWith(".zip") -> "application/zip"
            else -> "application/octet-stream"
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map { col ->
            when (col) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        })
        return c
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: throw java.io.FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
