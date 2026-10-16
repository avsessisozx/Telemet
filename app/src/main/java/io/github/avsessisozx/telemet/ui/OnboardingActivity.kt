package io.github.avsessisozx.telemet.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.avsessisozx.telemet.R
import io.github.avsessisozx.telemet.util.AppLog
import io.github.avsessisozx.telemet.util.Backup
import io.github.avsessisozx.telemet.util.LocaleHelper
import io.github.avsessisozx.telemet.util.Permissions
import io.github.avsessisozx.telemet.util.Prefs
import java.text.DateFormat
import java.util.Date

/**
 * Tam ekran kurulum sihirbazı. İlk açılışta bir kez gösterilir; her izin ayrı sayfadadır.
 * Daha sonra eksik izinleri tamamlamak için ([EXTRA_FIX]) ya da baştan ([EXTRA_ALL]) yeniden açılabilir.
 */
class OnboardingActivity : Activity() {

    companion object {
        /** Yalnızca eksik adımları göster. */
        const val EXTRA_FIX = "fix"
        /** Hoş geldin sayfası olmadan tüm adımları göster. */
        const val EXTRA_ALL = "all"
        /** "Tüm verileri temizle" sonrası: alınan yedeğin yolu. */
        const val EXTRA_CLEARED_BACKUP = "cleared_backup"
        private const val REQ_PERMISSION = 10
        private const val REQ_RESTORE = 11
    }

    private enum class Step { WELCOME, LOCATION, BG_LOCATION, BLUETOOTH, NOTIFICATIONS, BATTERY, AUTOSTART, SAVER, DONE }

    private lateinit var ui: Ui
    private lateinit var prefs: Prefs
    private lateinit var steps: List<Step>
    private var index = 0
    private val permanentlyDenied = HashSet<Step>()
    private var openedSystemScreen: Step? = null
    private val main = Handler(Looper.getMainLooper())

    private lateinit var progress: TextView
    private lateinit var body: LinearLayout
    private lateinit var primary: Button
    private lateinit var secondary: Button

    private val fixMode get() = intent.getBooleanExtra(EXTRA_FIX, false)
    private val allMode get() = intent.getBooleanExtra(EXTRA_ALL, false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        Ui.styleWindow(this)
        steps = buildSteps()
        index = savedInstanceState?.getInt("index") ?: 0
        if (steps.isEmpty()) {
            finish()
            return
        }
        setContentView(buildUi())
        show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    private fun permissionSteps(): List<Step> = buildList {
        add(Step.LOCATION)
        add(Step.BG_LOCATION)
        if (Permissions.BLUETOOTH.isNotEmpty()) add(Step.BLUETOOTH)
        if (Permissions.NOTIFICATIONS.isNotEmpty()) add(Step.NOTIFICATIONS)
        add(Step.BATTERY)
        if (Permissions.isXiaomi) {
            add(Step.AUTOSTART)
            add(Step.SAVER)
        }
    }

    private fun buildSteps(): List<Step> = when {
        fixMode -> permissionSteps().filter { !isDone(it) }.let { if (it.isEmpty()) it else it + Step.DONE }
        allMode -> permissionSteps() + Step.DONE
        else -> listOf(Step.WELCOME) + permissionSteps() + Step.DONE
    }

    private fun isDone(step: Step): Boolean = when (step) {
        Step.LOCATION -> Permissions.hasLocation(this)
        Step.BG_LOCATION -> Permissions.hasBackgroundLocation(this)
        Step.BLUETOOTH -> Permissions.hasBluetooth(this)
        Step.NOTIFICATIONS -> Permissions.hasNotifications(this)
        Step.BATTERY -> Permissions.ignoringBattery(this)
        Step.AUTOSTART -> prefs.setupAutostartDone
        Step.SAVER -> prefs.setupBatterySaverDone
        Step.WELCOME, Step.DONE -> false
    }

    // ---------------------------------------------------------------- Görünüm

    private fun buildUi(): View {
        progress = ui.text(13f, Theme.MUTED).apply { gravity = Gravity.CENTER; tag = "ob_progress" }
        body = ui.vertical().apply { gravity = Gravity.CENTER_HORIZONTAL }
        primary = ui.button("", Theme.ACCENT) { onPrimary() }.apply { tag = "ob_primary" }
        secondary = ui.textButton("") { onSecondary() }.apply { tag = "ob_secondary" }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(ui.vertical().apply {
                gravity = Gravity.CENTER
                setPadding(ui.dp(28), ui.dp(16), ui.dp(28), ui.dp(16))
                addView(body, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            })
        }
        val root = ui.vertical().apply {
            setBackgroundColor(Theme.BG)
            addView(progress, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = ui.dp(16) })
            addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(ui.vertical().apply {
                setPadding(ui.dp(24), 0, ui.dp(24), ui.dp(16))
                addView(primary)
                addView(secondary)
            })
        }
        root.setOnApplyWindowInsetsListener { v, insets ->
            val (top, bottom) = Ui.systemBars(insets)
            v.setPadding(0, top, 0, bottom)
            insets
        }
        return root
    }

    private fun emoji(step: Step) = when (step) {
        Step.LOCATION -> "📍"
        Step.BG_LOCATION -> "🧭"
        Step.BLUETOOTH -> "📡"
        Step.NOTIFICATIONS -> "🔔"
        Step.BATTERY -> "🔋"
        Step.AUTOSTART -> "🔁"
        Step.SAVER -> "⚡"
        Step.DONE -> "🏍️"
        Step.WELCOME -> ""
    }

    private fun title(step: Step) = getString(when (step) {
        Step.LOCATION -> R.string.ob_loc_title
        Step.BG_LOCATION -> R.string.ob_bg_title
        Step.BLUETOOTH -> R.string.ob_bt_title
        Step.NOTIFICATIONS -> R.string.ob_notif_title
        Step.BATTERY -> R.string.ob_battery_title
        Step.AUTOSTART -> R.string.dlg_autostart_title
        Step.SAVER -> R.string.dlg_saver_title
        Step.DONE -> R.string.ob_done_title
        Step.WELCOME -> R.string.ob_welcome_title
    })

    private fun message(step: Step) = getString(when (step) {
        Step.LOCATION -> R.string.ob_loc_msg
        Step.BG_LOCATION -> R.string.dlg_bgloc_msg
        Step.BLUETOOTH -> R.string.ob_bt_msg
        Step.NOTIFICATIONS -> R.string.ob_notif_msg
        Step.BATTERY -> R.string.ob_battery_msg
        Step.AUTOSTART -> R.string.dlg_autostart_msg
        Step.SAVER -> R.string.dlg_saver_msg
        Step.DONE -> if (fixMode || allMode) R.string.ob_done_fix_msg else R.string.ob_done_msg
        Step.WELCOME -> R.string.ob_welcome_msg
    })

    private val step: Step get() = steps[index]

    private fun permissionCount() = steps.count { it != Step.WELCOME && it != Step.DONE }
    private fun permissionNumber() = steps.take(index + 1).count { it != Step.WELCOME && it != Step.DONE }

    private fun show() {
        body.removeAllViews()
        val s = step
        progress.text = if (s == Step.WELCOME || s == Step.DONE) "" else getString(R.string.ob_step, permissionNumber(), permissionCount())

        if (s == Step.WELCOME) {
            body.addView(ImageView(this).apply {
                setImageResource(R.mipmap.ic_launcher)
                layoutParams = LinearLayout.LayoutParams(ui.dp(96), ui.dp(96)).apply { bottomMargin = ui.dp(20) }
            })
        } else {
            body.addView(ui.text(64f, value = emoji(s)).apply {
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, ui.dp(16))
            })
        }
        body.addView(ui.text(26f, Theme.TEXT, bold = true, value = title(s)).apply {
            gravity = Gravity.CENTER
            tag = "ob_title"
        })
        if (s == Step.WELCOME) {
            body.addView(ui.text(16f, Theme.ACCENT, value = getString(R.string.tagline)).apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(12), 0, 0)
            })
        }
        body.addView(ui.text(16f, Theme.MUTED, value = message(s)).apply {
            gravity = Gravity.CENTER
            setPadding(0, ui.dp(12), 0, 0)
            setLineSpacing(0f, 1.15f)
        })

        val cleared = intent.getStringExtra(EXTRA_CLEARED_BACKUP)
        if (s == Step.WELCOME && cleared != null) {
            body.addView(ui.card(color = 0xFF1B2E1C.toInt()).apply {
                (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(20)
                addView(ui.text(14f, Theme.GREEN, value = getString(R.string.data_cleared, cleared)))
            })
        }

        // Durum ve düğmeler
        val done = isDone(s)
        if (s != Step.WELCOME && s != Step.DONE) {
            val status = when {
                done -> getString(R.string.ob_granted)
                s == Step.BG_LOCATION && !Permissions.hasLocation(this) -> getString(R.string.ob_bg_need_location)
                s in permanentlyDenied -> getString(R.string.ob_denied)
                else -> ""
            }
            if (status.isNotEmpty()) {
                body.addView(ui.text(15f, if (done) Theme.GREEN else Theme.YELLOW, bold = true, value = status).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, ui.dp(20), 0, 0)
                    tag = "ob_status"
                })
            }
        }
        primary.isEnabled = true
        primary.alpha = 1f
        primary.text = when {
            s == Step.WELCOME -> getString(R.string.ob_start)
            s == Step.DONE -> getString(if (fixMode || allMode) R.string.ob_close else R.string.ob_finish)
            done -> getString(R.string.ob_next)
            s == Step.BG_LOCATION && !Permissions.hasLocation(this) -> getString(R.string.ob_next)
            s in permanentlyDenied || s == Step.AUTOSTART || s == Step.SAVER -> getString(R.string.ob_open_settings)
            else -> getString(R.string.ob_allow)
        }
        secondary.visibility = View.VISIBLE
        secondary.text = when {
            s == Step.WELCOME -> getString(R.string.ob_restore)
            s == Step.DONE -> ""
            done -> ""
            else -> getString(R.string.skip)
        }
        if (secondary.text.isNullOrEmpty()) secondary.visibility = View.INVISIBLE
    }

    // ---------------------------------------------------------------- Eylemler

    private fun next() {
        if (index < steps.size - 1) {
            index++
            show()
        }
    }

    private fun onPrimary() {
        val s = step
        if (s == Step.WELCOME) return next()
        if (s == Step.DONE) return finishSetup()
        if (isDone(s) || (s == Step.BG_LOCATION && !Permissions.hasLocation(this))) return next()
        if (s in permanentlyDenied) return openAppSettings(s)
        when (s) {
            Step.LOCATION -> request(s, Permissions.LOCATION)
            Step.BG_LOCATION -> request(s, arrayOf(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            Step.BLUETOOTH -> request(s, Permissions.BLUETOOTH)
            Step.NOTIFICATIONS -> request(s, Permissions.NOTIFICATIONS)
            Step.BATTERY -> openFirst(
                s,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            Step.AUTOSTART -> openFirst(
                s,
                Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                appSettingsIntent(),
            )
            Step.SAVER -> openFirst(
                s,
                Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                    .putExtra("package_name", packageName)
                    .putExtra("package_label", getString(R.string.app_name)),
                appSettingsIntent(),
            )
            else -> Unit
        }
    }

    private fun onSecondary() {
        when (step) {
            Step.WELCOME -> offerRestore()
            Step.DONE -> Unit
            else -> next()
        }
    }

    private fun request(s: Step, perms: Array<String>) {
        if (perms.isEmpty()) return next()
        requestPermissions(perms, REQ_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSION) return
        val s = step
        if (isDone(s)) {
            AppLog.i("Setup: ${s.name} allowed")
            show()
            main.postDelayed({ if (step == s) next() }, 500)
        } else {
            // "Bir daha sorma" seçildiyse sistem pencereyi artık göstermez; ayarlara yönlendiririz.
            if (permissions.any { !shouldShowRequestPermissionRationale(it) }) permanentlyDenied += s
            AppLog.w("Setup: ${s.name} denied")
            show()
        }
    }

    private fun openFirst(s: Step, vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i)
                openedSystemScreen = s
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, R.string.ob_open_failed, Toast.LENGTH_LONG).show()
    }

    private fun appSettingsIntent() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    private fun openAppSettings(s: Step) = openFirst(s, appSettingsIntent())

    override fun onResume() {
        super.onResume()
        if (!::steps.isInitialized || steps.isEmpty()) return
        val opened = openedSystemScreen ?: return
        openedSystemScreen = null
        // Xiaomi ekranlarının sonucunu sistem bildirmiyor; kullanıcı ekrana gidip döndüyse yapıldı sayıyoruz.
        if (opened == Step.AUTOSTART) prefs.setupAutostartDone = true
        if (opened == Step.SAVER) prefs.setupBatterySaverDone = true
        if (isDone(opened)) permanentlyDenied -= opened
        show()
        if (step == opened && isDone(opened)) main.postDelayed({ if (step == opened) next() }, 500)
    }

    private fun finishSetup() {
        if (!prefs.onboardingDone) {
            prefs.onboardingDone = true
            AppLog.i("Setup finished")
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
        finish()
    }

    @Deprecated("Geri tuşu")
    override fun onBackPressed() {
        if (index > 0) {
            index--
            show()
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    // ---------------------------------------------------------------- Yedekten geri yükleme

    private fun offerRestore() {
        val latest = Backup.findLatest(this)
        if (latest == null) {
            pickBackup()
            return
        }
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, resources.configuration.locales[0])
            .format(Date(latest.modifiedMillis))
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.restore_latest, date))
            .setPositiveButton(R.string.backup_restore) { _, _ -> restore(latest.uri) }
            .setNeutralButton(R.string.restore_other) { _, _ -> pickBackup() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun pickBackup() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_RESTORE)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.restore_failed, Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Activity sonucu")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_RESTORE && resultCode == RESULT_OK) data?.data?.let { restore(it) }
    }

    /** Geri yüklemeden sonra kurulum, eksik izin adımlarıyla devam eder. */
    fun restore(uri: Uri) {
        val wait = AlertDialog.Builder(this).setMessage(R.string.please_wait).setCancelable(false).show()
        Thread {
            val result = Backup.restore(this, uri)
            main.post {
                wait.dismiss()
                if (result == null) {
                    AlertDialog.Builder(this).setMessage(R.string.restore_failed).setPositiveButton(R.string.ok, null).show()
                    return@post
                }
                Toast.makeText(this, getString(R.string.restore_done, result.rides), Toast.LENGTH_LONG).show()
                // Geri yüklenen ayarlar kurulumu "tamamlandı" sayabilir; eksik izinler yine de sorulsun.
                prefs.onboardingDone = false
                next()
            }
        }.start()
    }
}
