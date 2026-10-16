package io.github.avsessisozx.telemet.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uygulama içi günlük. Ayarlar → Günlük'te gösterilir ve paylaşılabilir.
 * Satırlar dosyaya da yazılır; uygulama kapansa ya da çökse bile bir sonraki açılışta okunabilir.
 */
object AppLog {
    private const val TAG = "Telemet"
    private const val MAX_LINES = 1500
    private const val FILE_NAME = "log.txt"
    private const val MAX_FILE_BYTES = 400_000L

    private val lines = ArrayDeque<String>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    /** Uygulama açılırken bir kez çağrılır: önceki günlüğü yükler ve çökme yakalayıcıyı kurar. */
    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE_NAME)
        file = f
        try {
            if (f.exists()) {
                if (f.length() > MAX_FILE_BYTES) {
                    // Dosya büyüdüyse son kısmı tut.
                    val keep = f.readLines().takeLast(MAX_LINES / 2)
                    f.writeText(keep.joinToString("\n", postfix = "\n"))
                }
                f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
            }
        } catch (_: Exception) {
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                w("CRASH in ${thread.name}: " + Log.getStackTraceString(error).lines().take(25).joinToString(" | "))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    @Synchronized
    fun i(message: String) {
        Log.i(TAG, message)
        add(message)
    }

    @Synchronized
    fun w(message: String, error: Throwable? = null) {
        Log.w(TAG, message, error)
        add("⚠ " + message + (error?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: ""))
    }

    private fun add(message: String) {
        val line = "${time.format(Date())} $message"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        file?.let {
            try {
                FileWriter(it, true).use { w -> w.write(line + "\n") }
            } catch (_: Exception) {
            }
        }
        main.post { listeners.toList().forEach { it() } }
    }

    @Synchronized
    fun snapshot(maxLines: Int = MAX_LINES): List<String> = lines.toList().takeLast(maxLines)

    @Synchronized
    fun clear() {
        lines.clear()
        try {
            file?.writeText("")
        } catch (_: Exception) {
        }
        main.post { listeners.toList().forEach { it() } }
    }

    /** Yedekten geri yüklenen günlüğü ekler. */
    @Synchronized
    fun restore(text: String) {
        val restored = text.lines().filter { it.isNotBlank() }
        lines.clear()
        restored.takeLast(MAX_LINES).forEach { lines.addLast(it) }
        try {
            file?.writeText(lines.joinToString("\n", postfix = "\n"))
        } catch (_: Exception) {
        }
        main.post { listeners.toList().forEach { it() } }
    }

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }
}
