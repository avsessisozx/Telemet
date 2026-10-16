package io.github.avsessisozx.telemet.util

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** GitHub'daki en son sürümü kontrol eder. Yalnızca kullanıcı istediğinde çalışır; arka planda internete çıkılmaz. */
object UpdateChecker {

    const val RELEASES_URL = "https://github.com/avsessisozx/Telemet/releases/latest"
    const val PROJECT_URL = "https://github.com/avsessisozx/Telemet"
    const val ISSUES_URL = "https://github.com/avsessisozx/Telemet/issues/new"
    private const val API_URL = "https://api.github.com/repos/avsessisozx/Telemet/releases/latest"

    sealed class Result {
        data class UpToDate(val latest: String) : Result()
        data class Available(val latest: String, val url: String) : Result()
        object Failed : Result()
    }

    fun check(currentVersion: String, callback: (Result) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        Thread {
            val result = try {
                val c = URL(API_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.setRequestProperty("Accept", "application/vnd.github+json")
                val body = c.inputStream.bufferedReader().use { it.readText() }
                val j = JSONObject(body)
                val latest = j.getString("tag_name").removePrefix("v")
                if (isNewer(latest, currentVersion)) {
                    Result.Available(latest, j.optString("html_url", RELEASES_URL))
                } else {
                    Result.UpToDate(latest)
                }
            } catch (e: Exception) {
                AppLog.w("Update check failed", e)
                Result.Failed
            }
            main.post { callback(result) }
        }.start()
    }

    /** "0.1.12" > "0.1.9" gibi sayısal karşılaştırma. */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
