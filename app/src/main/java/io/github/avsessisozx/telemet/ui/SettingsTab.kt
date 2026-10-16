package io.github.avsessisozx.telemet.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.Toast
import io.github.avsessisozx.telemet.R
import io.github.avsessisozx.telemet.service.TelemetState
import io.github.avsessisozx.telemet.util.AppLog
import io.github.avsessisozx.telemet.util.Backup
import io.github.avsessisozx.telemet.util.LocaleHelper
import io.github.avsessisozx.telemet.util.Permissions
import io.github.avsessisozx.telemet.util.UpdateChecker

/** Ayarlar sekmesi: kayıt, kamera, genel, izinler, sorun giderme ve hakkında. */
class SettingsTab(activity: MainActivity) : MainActivity.Tab(activity) {

    private val ctx = activity
    private val prefs = activity.prefs
    private val root = ui.vertical()
    override val view: View = ui.scrollPage(root, applyTopInset = false, applyBottomInset = false)

    private lateinit var unitsRow: Ui.Row
    private lateinit var languageRow: Ui.Row
    private lateinit var cameraRow: Ui.Row
    private lateinit var updateRow: Ui.Row
    private val permRows = ArrayList<Pair<Ui.Row, () -> Boolean?>>()

    private val version: String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    init {
        build()
    }

    private fun build() {
        root.removeAllViews()
        permRows.clear()
        root.addView(ui.text(26f, Theme.TEXT, bold = true, value = ctx.getString(R.string.settings_title)).apply {
            setPadding(ui.dp(4), 0, 0, ui.dp(4))
        })

        // Kayıt
        root.addView(ui.sectionHeader(ctx.getString(R.string.section_recording)))
        unitsRow = ui.row(ctx.getString(R.string.setting_units), value = unitsLabel()) { chooseUnits() }
        root.addView(ui.group(
            ui.switchRow(ctx.getString(R.string.setting_backup), ctx.getString(R.string.setting_backup_desc), prefs.backupEnabled) {
                prefs.backupEnabled = it
                if (TelemetState.serviceRunning) Toast.makeText(ctx, R.string.toast_next_start, Toast.LENGTH_SHORT).show()
            },
            ui.switchRow(ctx.getString(R.string.setting_smooth_gps), ctx.getString(R.string.setting_smooth_gps_desc), prefs.smoothGps) {
                prefs.smoothGps = it
                AppLog.i(if (it) "Smooth GPS on (10 Hz)" else "Smooth GPS off (real fixes only)")
            },
            ui.switchRow(ctx.getString(R.string.setting_mimo_extras), ctx.getString(R.string.setting_mimo_extras_desc), prefs.mimoExtras) {
                prefs.mimoExtras = it
            }.apply { tag = "setting_mimo_extras" },
            ui.switchRow(ctx.getString(R.string.setting_keep_screen), null, prefs.keepScreenOn) {
                prefs.keepScreenOn = it
                activity.updateKeepScreenOn()
            },
            unitsRow.view,
        ))

        // Kamera
        root.addView(ui.sectionHeader(ctx.getString(R.string.section_camera)))
        cameraRow = ui.row(ctx.getString(R.string.camera_paired), value = cameraLabel())
        root.addView(ui.group(
            cameraRow.view,
            ui.switchRow(ctx.getString(R.string.setting_auto_record), ctx.getString(R.string.setting_auto_record_desc), prefs.autoRecord) {
                prefs.autoRecord = it
                AppLog.i(if (it) "Automatic recording on" else "Automatic recording off")
            }.apply { tag = "setting_auto_record" },
            ui.row(ctx.getString(R.string.camera_reset), ctx.getString(R.string.camera_reset_desc)) { resetPairing() }.view,
        ))

        // Genel
        root.addView(ui.sectionHeader(ctx.getString(R.string.section_general)))
        languageRow = ui.row(ctx.getString(R.string.language_dialog_title), value = languageLabel()) { chooseLanguage() }
        root.addView(ui.group(languageRow.view))

        // İzinler
        root.addView(ui.sectionHeader(ctx.getString(R.string.section_permissions)))
        val fix = {
            ctx.startActivity(Intent(ctx, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_FIX, true))
        }
        val perms = buildList<Pair<Int, () -> Boolean?>> {
            add(R.string.perm_location to { Permissions.hasLocation(ctx) })
            add(R.string.perm_bg_location to { Permissions.hasBackgroundLocation(ctx) })
            if (Permissions.BLUETOOTH.isNotEmpty()) add(R.string.perm_bluetooth to { Permissions.hasBluetooth(ctx) })
            if (Permissions.NOTIFICATIONS.isNotEmpty()) add(R.string.perm_notifications to { Permissions.hasNotifications(ctx) })
            add(R.string.perm_battery to { Permissions.ignoringBattery(ctx) })
            if (Permissions.isXiaomi) {
                add(R.string.perm_autostart to { if (prefs.setupAutostartDone) true else null })
                add(R.string.perm_saver to { if (prefs.setupBatterySaverDone) true else null })
            }
        }
        val permViews = perms.map { (label, check) ->
            val r = ui.row(ctx.getString(label), value = "") { fix() }
            permRows += r to check
            r.view
        }
        root.addView(ui.group(*permViews.toTypedArray(), ui.row(ctx.getString(R.string.setup_run_again), titleColor = Theme.ACCENT) {
            ctx.startActivity(Intent(ctx, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_ALL, true))
        }.view))

        // Sorun giderme
        root.addView(ui.sectionHeader(ctx.getString(R.string.section_troubleshooting)))
        root.addView(ui.group(
            ui.row(ctx.getString(R.string.log_title), ctx.getString(R.string.log_desc)) {
                ctx.startActivity(Intent(ctx, LogActivity::class.java))
            }.view,
            ui.row(ctx.getString(R.string.backup_create), ctx.getString(R.string.backup_create_desc)) { createBackup() }.view,
            ui.row(ctx.getString(R.string.backup_restore), ctx.getString(R.string.backup_restore_desc)) { pickBackup() }.view,
            ui.row(ctx.getString(R.string.reset_settings), ctx.getString(R.string.reset_settings_desc)) { resetSettings() }.view,
            ui.row(ctx.getString(R.string.clear_data), ctx.getString(R.string.clear_data_desc), titleColor = Theme.RED) { clearData() }
                .also { it.view.tag = "clear_data" }.view,
        ))

        // Hakkında
        root.addView(ui.sectionHeader(ctx.getString(R.string.section_about)))
        updateRow = ui.row(ctx.getString(R.string.update_check), value = ctx.getString(R.string.about_version, version)) { checkUpdate() }
        root.addView(ui.group(
            updateRow.view,
            ui.row(ctx.getString(R.string.about_github)) { open(UpdateChecker.PROJECT_URL) }.view,
            ui.row(ctx.getString(R.string.about_report)) { open(UpdateChecker.ISSUES_URL) }.view,
        ))
        root.addView(ui.text(12f, Theme.MUTED, value = ctx.getString(R.string.about_license)).apply {
            setPadding(ui.dp(4), ui.dp(16), ui.dp(4), 0)
        })
    }

    override fun onShow() = refresh()
    override fun onResume() = refresh()

    private fun refresh() {
        unitsRow.value.text = unitsLabel()
        languageRow.value.text = languageLabel()
        cameraRow.value.text = cameraLabel()
        for ((row, check) in permRows) {
            val ok = check()
            row.value.visibility = View.VISIBLE
            when (ok) {
                true -> {
                    row.value.text = "✓"
                    row.value.setTextColor(Theme.GREEN)
                    row.subtitle.visibility = View.GONE
                }
                false -> {
                    row.value.text = "✗"
                    row.value.setTextColor(Theme.RED)
                    row.subtitle.text = ctx.getString(R.string.perm_missing)
                    row.subtitle.visibility = View.VISIBLE
                }
                null -> {
                    row.value.text = "?"
                    row.value.setTextColor(Theme.YELLOW)
                    row.subtitle.text = ctx.getString(R.string.perm_unknown)
                    row.subtitle.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun unitsLabel() = ctx.getString(if (prefs.imperial) R.string.units_imperial else R.string.units_metric)

    private fun languageLabel() = LocaleHelper.displayName(ctx, LocaleHelper.current(ctx), ctx.getString(R.string.language_system))

    private fun cameraLabel(): String = prefs.lastCameraName ?: prefs.lastCameraAddress ?: ctx.getString(R.string.camera_none)

    private fun open(url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            Toast.makeText(ctx, url, Toast.LENGTH_LONG).show()
        }
    }

    /** Sürüş sürerken veriye dokunan işlemler yapılmaz. */
    private fun requireStopped(): Boolean {
        if (!TelemetState.serviceRunning) return true
        AlertDialog.Builder(ctx).setMessage(R.string.need_stop_first).setPositiveButton(R.string.ok, null).show()
        return false
    }

    // ---------------------------------------------------------------- Kayıt ve genel

    private fun chooseUnits() {
        val items = arrayOf(ctx.getString(R.string.units_metric), ctx.getString(R.string.units_imperial))
        AlertDialog.Builder(ctx)
            .setTitle(R.string.setting_units)
            .setSingleChoiceItems(items, if (prefs.imperial) 1 else 0) { d, which ->
                d.dismiss()
                prefs.imperial = which == 1
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun chooseLanguage() {
        val current = LocaleHelper.current(ctx)
        val tags = LocaleHelper.LANGUAGES.map { it.first }
        val names = LocaleHelper.LANGUAGES.map { it.second.ifEmpty { ctx.getString(R.string.language_system) } }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.language_dialog_title)
            .setSingleChoiceItems(names, tags.indexOf(current).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                val tag = tags[which]
                if (tag == current) return@setSingleChoiceItems
                LocaleHelper.set(ctx, tag)
                AppLog.i("App language: " + tag.ifEmpty { "phone default" })
                // Android 13+ dili sistem uygular ve ekranı yeniler; eski sürümlerde biz yeniliyoruz.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- Kamera

    private fun resetPairing() {
        if (!requireStopped()) return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.camera_reset_confirm)
            .setMessage(R.string.camera_reset_desc)
            .setPositiveButton(R.string.camera_reset) { _, _ ->
                prefs.resetPairing()
                AppLog.i("Camera pairing reset")
                Toast.makeText(ctx, R.string.camera_reset_done, Toast.LENGTH_LONG).show()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------------------------------------------------------------- Sorun giderme

    private fun createBackup() {
        runInBackground({ Backup.create(ctx) }) { created ->
            if (created != null) {
                AlertDialog.Builder(ctx).setMessage(ctx.getString(R.string.backup_saved, created.displayPath))
                    .setPositiveButton(R.string.ok, null).show()
            } else {
                Toast.makeText(ctx, R.string.backup_create_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pickBackup() {
        if (!requireStopped()) return
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        try {
            @Suppress("DEPRECATION")
            ctx.startActivityForResult(intent, MainActivity.REQ_RESTORE)
        } catch (e: Exception) {
            Toast.makeText(ctx, R.string.restore_failed, Toast.LENGTH_LONG).show()
        }
    }

    fun confirmRestore(uri: Uri) {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.backup_restore)
            .setMessage(R.string.restore_confirm)
            .setPositiveButton(R.string.backup_restore) { _, _ -> restoreFrom(uri) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun restoreFrom(uri: Uri) {
        runInBackground({ Backup.restore(ctx, uri) }) { result ->
            if (result == null) {
                AlertDialog.Builder(ctx).setMessage(R.string.restore_failed).setPositiveButton(R.string.ok, null).show()
                return@runInBackground
            }
            Toast.makeText(ctx, ctx.getString(R.string.restore_done, result.rides), Toast.LENGTH_LONG).show()
            ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_RIDES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }

    private fun resetSettings() {
        AlertDialog.Builder(ctx)
            .setTitle(R.string.reset_settings)
            .setMessage(R.string.reset_settings_confirm)
            .setPositiveButton(R.string.reset_settings) { _, _ ->
                prefs.resetSettings()
                AppLog.i("Settings reset to defaults")
                Toast.makeText(ctx, R.string.reset_settings_done, Toast.LENGTH_SHORT).show()
                build()
                refresh()
                activity.updateKeepScreenOn()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Tüm verileri temizle: iki onay, ardından yedek; yedek başarılı olmadan hiçbir şey silinmez.
     * İkinci onaydaki düğme yanlışlıkla basılmasın diye 3 saniye sonra etkinleşir.
     */
    private fun clearData() {
        if (!requireStopped()) return
        AlertDialog.Builder(ctx)
            .setTitle(R.string.clear_title1)
            .setMessage(R.string.clear_msg1)
            .setPositiveButton(R.string.btn_continue) { _, _ -> clearDataSecondConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun clearDataSecondConfirm() {
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(R.string.clear_title2)
            .setMessage(R.string.clear_msg2)
            .setPositiveButton(ctx.getString(R.string.clear_btn_wait, 3)) { _, _ -> doClearData() }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            val b: Button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            b.isEnabled = false
            b.setTextColor(Theme.RED)
            b.tag = "clear_confirm"
            val h = Handler(Looper.getMainLooper())
            var left = 3
            val tick = object : Runnable {
                override fun run() {
                    if (!dialog.isShowing) return
                    left--
                    if (left <= 0) {
                        b.text = ctx.getString(R.string.clear_btn)
                        b.isEnabled = true
                    } else {
                        b.text = ctx.getString(R.string.clear_btn_wait, left)
                        h.postDelayed(this, 1000)
                    }
                }
            }
            h.postDelayed(tick, 1000)
        }
        dialog.show()
    }

    private fun doClearData() {
        runInBackground({ Backup.create(ctx) }) { created ->
            if (created == null) {
                AlertDialog.Builder(ctx).setMessage(R.string.backup_failed).setPositiveButton(R.string.ok, null).show()
                return@runInBackground
            }
            Backup.clearAppData(ctx)
            TelemetState.resetAll()
            ctx.startActivity(Intent(ctx, OnboardingActivity::class.java)
                .putExtra(OnboardingActivity.EXTRA_CLEARED_BACKUP, created.displayPath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }

    // ---------------------------------------------------------------- Hakkında

    private fun checkUpdate() {
        updateRow.value.text = ctx.getString(R.string.update_checking)
        UpdateChecker.check(version) { result ->
            updateRow.value.text = ctx.getString(R.string.about_version, version)
            when (result) {
                is UpdateChecker.Result.Available -> AlertDialog.Builder(ctx)
                    .setMessage(ctx.getString(R.string.update_available, result.latest))
                    .setPositiveButton(R.string.update_download) { _, _ -> open(result.url) }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
                is UpdateChecker.Result.UpToDate -> Toast.makeText(ctx, R.string.update_latest, Toast.LENGTH_LONG).show()
                UpdateChecker.Result.Failed -> Toast.makeText(ctx, R.string.update_failed, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Dosya işlemlerini arayüzü dondurmadan arka planda yapar; bitince sonucu ana iş parçacığında verir. */
    private fun <T> runInBackground(work: () -> T, done: (T) -> Unit) {
        val progress = AlertDialog.Builder(ctx).setMessage(R.string.please_wait).setCancelable(false).show()
        val main = Handler(Looper.getMainLooper())
        Thread {
            val r = work()
            main.post {
                progress.dismiss()
                if (!ctx.isFinishing) done(r)
            }
        }.start()
    }
}
