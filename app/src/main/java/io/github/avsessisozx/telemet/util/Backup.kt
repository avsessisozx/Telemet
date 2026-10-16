package io.github.avsessisozx.telemet.util

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.avsessisozx.telemet.service.RideStore
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Uygulama verilerinin yedeği: ayarlar, sürüş listesi (özet + ham noktalar), yarım kalmış kayıtlar
 * ve günlük tek bir .zip dosyasına, İndirilenler/Telemet/Backup klasörüne yazılır. "Tüm verileri temizle"
 * önce bunu yapar; "Yedekten geri yükle" aynı dosyayı geri okur.
 */
object Backup {

    const val FOLDER = "Telemet/Backup"
    private const val PREFIX = "telemet-backup-"
    private const val FORMAT = 1
    private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

    class Created(val uri: Uri, val displayPath: String)
    class Found(val uri: Uri, val name: String, val modifiedMillis: Long)
    class Restored(val rides: Int, val settings: Boolean)

    private fun ridesRoot(ctx: Context): File = RideStore.dir(ctx).parentFile!!

    /** Yedeği oluşturur. Başarısızsa null döner (bu durumda hiçbir şey silinmemeli). */
    fun create(ctx: Context): Created? {
        return try {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                fun put(name: String, data: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(data)
                    zip.closeEntry()
                }
                val version = try {
                    ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
                } catch (e: Exception) {
                    "?"
                }
                put("manifest.json", JSONObject().put("app", "Telemet").put("format", FORMAT).put("version", version)
                    .put("created", System.currentTimeMillis()).toString().toByteArray())

                val prefs = JSONObject()
                for ((k, v) in Prefs(ctx).export()) {
                    if (k in Prefs.TRANSIENT_KEYS || v == null) continue
                    prefs.put(k, JSONObject().put("type", v.javaClass.simpleName).put("value", v))
                }
                prefs.put("app_language", JSONObject().put("type", "String").put("value", LocaleHelper.current(ctx)))
                put("prefs.json", prefs.toString().toByteArray())

                RideStore.dir(ctx).listFiles()?.filter { it.isFile && it.name.matches(SAFE_NAME) }?.forEach {
                    put("rides/done/${it.name}", it.readBytes())
                }
                ridesRoot(ctx).listFiles()?.filter { it.isFile && it.name.matches(SAFE_NAME) }?.forEach {
                    put("rides/${it.name}", it.readBytes())
                }
                put("log.txt", AppLog.snapshot().joinToString("\n").toByteArray())
            }
            val name = PREFIX + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".zip"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes.toByteArray()) } ?: return null
            AppLog.i("Backup created: Downloads/$FOLDER/$name (${bytes.size()} bytes)")
            Created(uri, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/$name")
        } catch (e: Exception) {
            AppLog.w("Backup failed", e)
            null
        }
    }

    /** Bu uygulamanın oluşturduğu en yeni yedek (varsa). */
    fun findLatest(ctx: Context): Found? = try {
        val projection = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.DATE_MODIFIED)
        ctx.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection,
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("$PREFIX%.zip", "%$FOLDER%"),
            "${MediaStore.Downloads.DATE_MODIFIED} DESC",
        )?.use { c ->
            if (!c.moveToFirst()) {
                null
            } else {
                Found(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)),
                    c.getString(1),
                    c.getLong(2) * 1000,
                )
            }
        }
    } catch (e: Exception) {
        AppLog.w("Could not look for backups", e)
        null
    }

    /**
     * Yedeği geri yükler: ayarlar yazılır, sürüşler eklenir (var olanların üzerine yazılmaz).
     * Geçerli bir Telemet yedeği değilse null döner.
     */
    fun restore(ctx: Context, uri: Uri): Restored? {
        val entries = HashMap<String, ByteArray>()
        try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(input).use { zip ->
                    var total = 0L
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (!e.isDirectory && isAllowed(e.name)) {
                            val data = zip.readBytes()
                            total += data.size
                            if (total > 200L * 1024 * 1024) throw IllegalStateException("backup too large")
                            entries[e.name] = data
                        }
                    }
                }
            } ?: return null
        } catch (e: Exception) {
            AppLog.w("Could not read backup", e)
            return null
        }
        val manifest = entries["manifest.json"]?.let { runCatching { JSONObject(String(it)) }.getOrNull() } ?: return null
        if (manifest.optString("app") != "Telemet") return null

        var settings = false
        entries["prefs.json"]?.let { data ->
            val j = JSONObject(String(data))
            val values = HashMap<String, Any?>()
            var language: String? = null
            for (k in j.keys()) {
                val o = j.optJSONObject(k) ?: continue
                if (k == "app_language") {
                    language = o.optString("value")
                    continue
                }
                values[k] = when (o.optString("type")) {
                    "Boolean" -> o.optBoolean("value")
                    "Integer" -> o.optInt("value")
                    "Long" -> o.optLong("value")
                    "Float" -> o.optDouble("value").toFloat()
                    "String" -> o.optString("value")
                    else -> null
                }
            }
            Prefs(ctx).import(values)
            language?.let { LocaleHelper.set(ctx, it) }
            settings = true
        }

        var rides = 0
        val done = RideStore.dir(ctx)
        val root = ridesRoot(ctx)
        for ((name, data) in entries) {
            val target = when {
                name.startsWith("rides/done/") -> File(done, name.removePrefix("rides/done/"))
                name.startsWith("rides/") -> File(root, name.removePrefix("rides/"))
                else -> null
            } ?: continue
            if (target.exists()) continue
            target.writeBytes(data)
            if (name.startsWith("rides/done/") && name.endsWith(".json")) rides++
        }
        entries["log.txt"]?.let { old ->
            if (AppLog.snapshot().isEmpty()) AppLog.restore(String(old))
        }
        AppLog.i("Backup restored: $rides rides, settings=$settings")
        return Restored(rides, settings)
    }

    private fun isAllowed(name: String): Boolean = when {
        name == "manifest.json" || name == "prefs.json" || name == "log.txt" -> true
        name.startsWith("rides/done/") -> name.removePrefix("rides/done/").matches(SAFE_NAME)
        name.startsWith("rides/") -> name.removePrefix("rides/").matches(SAFE_NAME)
        else -> false
    }.let { it && !name.contains("..") }

    /**
     * Android'in "Verileri temizle" işleminin uygulama içi karşılığı: ayarlar, sürüş listesi, yarım kayıtlar,
     * günlük ve geçici dosyalar silinir. İndirilenler'deki FIT/GPX dosyalarına ve yedeklere dokunulmaz.
     */
    fun clearAppData(ctx: Context) {
        Prefs(ctx).clearAll()
        LocaleHelper.set(ctx, "")
        ridesRoot(ctx).deleteRecursively()
        ctx.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        ctx.filesDir.listFiles()?.forEach { if (it.name != "log.txt") it.deleteRecursively() }
        ctx.getExternalFilesDir(null)?.listFiles()?.forEach { it.deleteRecursively() }
        AppLog.clear()
        AppLog.i("All app data cleared")
    }
}
