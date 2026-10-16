package io.github.avsessisozx.telemet.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import io.github.avsessisozx.telemet.R
import io.github.avsessisozx.telemet.service.TelemetState
import io.github.avsessisozx.telemet.util.AppLog
import io.github.avsessisozx.telemet.util.LocaleHelper
import io.github.avsessisozx.telemet.util.ShareProvider
import java.io.File

/** Günlük sayfası: son kayıtlar, paylaş (dosya olarak) ve temizle. */
class LogActivity : Activity() {

    private lateinit var ui: Ui
    private val logView by lazy {
        ui.text(11f, 0xFFB0BEC5.toInt()).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            tag = "log_text"
        }
    }
    private val listener: () -> Unit = { render() }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        Ui.styleWindow(this)
        val root = ui.vertical()
        root.addView(ui.toolbar(getString(R.string.log_title)) { finish() })
        root.addView(ui.horizontal().apply {
            layoutParams = ui.matchWrap(12)
            addView(ui.smallButton(getString(R.string.btn_share), Theme.ACCENT) { share() })
            addView(ui.smallButton(getString(R.string.log_clear), Theme.GREY_BUTTON) { confirmClear() })
        })
        root.addView(ui.card().apply { addView(logView) })
        setContentView(ui.scrollPage(root))
    }

    override fun onStart() {
        super.onStart()
        AppLog.addListener(listener)
        render()
    }

    override fun onStop() {
        AppLog.removeListener(listener)
        super.onStop()
    }

    private fun render() {
        logView.text = AppLog.snapshot(400).asReversed().joinToString("\n").ifEmpty { getString(R.string.log_empty) }
    }

    private fun report(): String = buildString {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        appendLine("Telemet $version log")
        appendLine("Phone: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("MTU: ${TelemetState.mtu}")
        appendLine()
        AppLog.snapshot().forEach { appendLine(it) }
    }

    private fun share() {
        val text = report()
        val f = File(ShareProvider.freshDir(this), "telemet-log.txt").apply { writeText(text) }
        ShareProvider.share(this, listOf(f), "Telemet log", getString(R.string.share_log_chooser))
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setMessage(R.string.log_clear_confirm)
            .setPositiveButton(R.string.log_clear) { _, _ -> AppLog.clear() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
