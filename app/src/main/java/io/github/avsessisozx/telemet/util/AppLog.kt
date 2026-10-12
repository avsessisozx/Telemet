package io.github.avsessisozx.telemet.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uygulama içi günlük. Ekranda gösterilir ve "Günlüğü paylaş" ile dışarı verilebilir.
 * İlk gerçek testlerde neyin ters gittiğini anlamak için çok önemli.
 */
object AppLog {
    private const val TAG = "Telemet"
    private const val MAX_LINES = 500

    private val lines = ArrayDeque<String>()
    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

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
        lines.addLast("${time.format(Date())} $message")
        while (lines.size > MAX_LINES) lines.removeFirst()
        main.post { listeners.toList().forEach { it() } }
    }

    @Synchronized
    fun snapshot(maxLines: Int = MAX_LINES): List<String> = lines.toList().takeLast(maxLines)

    @Synchronized
    fun clear() {
        lines.clear()
        main.post { listeners.toList().forEach { it() } }
    }

    fun addListener(l: () -> Unit) {
        listeners += l
    }

    fun removeListener(l: () -> Unit) {
        listeners -= l
    }
}
