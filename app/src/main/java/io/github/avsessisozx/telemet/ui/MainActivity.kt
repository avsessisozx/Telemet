package io.github.avsessisozx.telemet.ui

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import io.github.avsessisozx.telemet.R
import io.github.avsessisozx.telemet.service.GpsWarmup
import io.github.avsessisozx.telemet.service.TelemetService
import io.github.avsessisozx.telemet.service.TelemetState
import io.github.avsessisozx.telemet.util.AppLog
import io.github.avsessisozx.telemet.util.LocaleHelper
import io.github.avsessisozx.telemet.util.Permissions
import io.github.avsessisozx.telemet.util.Prefs

/**
 * Ana ekran: altta üç sekme (Sürüş, Sürüşlerim, Ayarlar). İlk açılışta kurulum tamamlanmadıysa
 * önce tam ekran kurulum sihirbazı açılır.
 */
class MainActivity : Activity() {

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_RIDE = 0
        const val TAB_RIDES = 1
        const val TAB_SETTINGS = 2
        const val REQ_ENABLE_BT = 2
        const val REQ_RESTORE = 3
    }

    /** Bir sekme: görünümünü bir kez kurar, gösterildiğinde ve durum değiştiğinde güncellenir. */
    abstract class Tab(val activity: MainActivity) {
        val ui = Ui(activity)
        abstract val view: View
        open fun onShow() = Unit
        open fun onResume() = Unit
        open fun render() = Unit
    }

    lateinit var prefs: Prefs
        private set
    private lateinit var content: FrameLayout
    private lateinit var tabs: List<Tab>
    private val navItems = ArrayList<Pair<ImageView, TextView>>()
    private var current = -1

    private val stateListener: () -> Unit = {
        if (current >= 0) tabs[current].render()
        updateKeepScreenOn()
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Ui.styleWindow(this)
        if (!prefs.onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        tabs = listOf(RideTab(this), RidesTab(this), SettingsTab(this))
        setContentView(buildUi())
        select(savedInstanceState?.getInt(EXTRA_TAB) ?: intent.getIntExtra(EXTRA_TAB, TAB_RIDE))
    }

    private val ready: Boolean get() = ::tabs.isInitialized

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (ready && intent.hasExtra(EXTRA_TAB)) select(intent.getIntExtra(EXTRA_TAB, TAB_RIDE))
    }

    private fun buildUi(): View {
        val ui = Ui(this)
        content = FrameLayout(this).apply { setBackgroundColor(Theme.BG) }
        val nav = ui.horizontal().apply {
            setBackgroundColor(Theme.SURFACE)
            gravity = Gravity.CENTER
        }
        val labels = listOf(R.string.tab_ride, R.string.tab_rides, R.string.tab_settings)
        val icons = listOf(R.drawable.ic_tab_ride, R.drawable.ic_tab_rides, R.drawable.ic_tab_settings)
        labels.forEachIndexed { i, label ->
            val icon = ui.icon(icons[i], Theme.MUTED)
            val text = ui.text(12f, Theme.MUTED, value = getString(label)).apply { gravity = Gravity.CENTER }
            navItems += icon to text
            nav.addView(ui.vertical().apply {
                gravity = Gravity.CENTER
                setPadding(0, ui.dp(8), 0, ui.dp(8))
                background = ui.ripple(null, 0)
                isClickable = true
                contentDescription = getString(label)
                tag = "tab_$i"
                addView(icon)
                addView(text)
                setOnClickListener { select(i) }
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
        }
        val root = ui.vertical().apply {
            setBackgroundColor(Theme.BG)
            addView(content, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        // Sistem çubukları: üst boşluk içerikte, alt boşluk menünün altında.
        root.setOnApplyWindowInsetsListener { _, insets ->
            val (top, bottom) = Ui.systemBars(insets)
            content.setPadding(0, top, 0, 0)
            nav.setPadding(0, 0, 0, bottom)
            insets
        }
        return root
    }

    fun select(index: Int) {
        val i = index.coerceIn(0, tabs.size - 1)
        if (i == current) return
        current = i
        content.removeAllViews()
        Ui.removeFromParent(tabs[i].view)
        content.addView(tabs[i].view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        navItems.forEachIndexed { j, (icon, text) ->
            val c = if (j == i) Theme.ACCENT else Theme.MUTED
            icon.imageTintList = android.content.res.ColorStateList.valueOf(c)
            text.setTextColor(c)
        }
        tabs[i].onShow()
        tabs[i].render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (ready) outState.putInt(EXTRA_TAB, current)
    }

    override fun onStart() {
        super.onStart()
        if (!ready) return
        TelemetState.addListener(stateListener)
        GpsWarmup.start(this)
        // Durdur'a basılmadan kapanmış bir sürüş varsa kaldığı yerden devam et.
        if (prefs.shouldRun && !TelemetState.serviceRunning && Permissions.canRun(this)) {
            AppLog.i("Resuming an unfinished ride")
            TelemetService.resume(this)
        }
        updateKeepScreenOn()
    }

    override fun onResume() {
        super.onResume()
        if (ready && current >= 0) {
            tabs[current].onResume()
            tabs[current].render()
        }
    }

    override fun onStop() {
        if (ready) {
            GpsWarmup.stop()
            TelemetState.removeListener(stateListener)
        }
        super.onStop()
    }

    @Deprecated("Geri tuşu")
    override fun onBackPressed() {
        // Diğer uygulamalardaki gibi: önce ana sekmeye dön, oradayken çık.
        if (ready && current != TAB_RIDE) {
            select(TAB_RIDE)
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    fun updateKeepScreenOn() {
        if (prefs.keepScreenOn && TelemetState.serviceRunning) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------------------------------------------------------------- Sürüşü başlatma / durdurma

    /** Başlat: izinler, Bluetooth ve konum hazırsa servisi başlatır; değilse kullanıcıyı ilgili yere yönlendirir. */
    fun startRide() {
        if (!Permissions.canRun(this)) {
            startActivity(Intent(this, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_FIX, true))
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Toast.makeText(this, R.string.toast_no_bt, Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
            } catch (e: SecurityException) {
                Toast.makeText(this, R.string.toast_enable_bt, Toast.LENGTH_LONG).show()
            }
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        if (lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) != true) {
            Toast.makeText(this, R.string.toast_enable_gps, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        TelemetService.start(this)
    }

    /**
     * Durdur: sürüş kaydı varsa ya da kamera kayıttaysa önce onay ister; yanlışlıkla basılıp
     * sürüşün yarıda kesilmesini önler.
     */
    fun stopRide() {
        val recording = TelemetState.cameraStatus?.isRecording == true
        val hasRide = TelemetState.rideFile != null && TelemetState.distanceKm > 0.05
        if (!recording && !hasRide) {
            TelemetService.stop(this)
            return
        }
        val b = AlertDialog.Builder(this)
            .setTitle(R.string.stop_confirm_title)
            .setMessage(if (recording) R.string.stop_confirm_recording_msg else R.string.stop_confirm_msg)
            .setNegativeButton(R.string.cancel, null)
        if (recording) {
            b.setPositiveButton(R.string.btn_stop_both) { _, _ ->
                TelemetService.toggleRecord(this)
                // Kameranın kaydı kapatma komutunu alması için kısa bir süre tanı.
                window.decorView.postDelayed({ TelemetService.stop(this) }, 1500)
            }
            b.setNeutralButton(R.string.btn_stop_ride) { _, _ -> TelemetService.stop(this) }
        } else {
            b.setPositiveButton(R.string.btn_stop_ride) { _, _ -> TelemetService.stop(this) }
        }
        b.show()
    }

    @Deprecated("Activity sonucu")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_ENABLE_BT -> if (resultCode == RESULT_OK) startRide()
            REQ_RESTORE -> if (resultCode == RESULT_OK && ready) {
                data?.data?.let { (tabs[TAB_SETTINGS] as SettingsTab).confirmRestore(it) }
            }
        }
    }
}
