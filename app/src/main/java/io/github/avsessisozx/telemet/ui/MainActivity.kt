package io.github.avsessisozx.telemet.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.avsessisozx.telemet.ble.CameraLink
import io.github.avsessisozx.telemet.service.TelemetService
import io.github.avsessisozx.telemet.service.TelemetState
import io.github.avsessisozx.telemet.util.AppLog
import io.github.avsessisozx.telemet.util.Prefs

/**
 * Ana ekran. Arayüz kodla kuruldu; hiçbir ek kütüphane kullanılmıyor.
 */
class MainActivity : Activity() {

    private companion object {
        const val REQ_PERMISSIONS = 1
        const val REQ_ENABLE_BT = 2

        const val BG = 0xFF121212.toInt()
        const val CARD = 0xFF1E1E1E.toInt()
        const val TEXT = 0xFFEDEDED.toInt()
        const val MUTED = 0xFF9E9E9E.toInt()
        const val ACCENT = 0xFFFF7A1A.toInt()
        const val GREEN = 0xFF4CAF50.toInt()
        const val RED = 0xFFE53935.toInt()
        const val YELLOW = 0xFFFFC107.toInt()
    }

    private lateinit var prefs: Prefs

    private lateinit var camDot: View
    private lateinit var camTitle: TextView
    private lateinit var camDetail: TextView
    private lateinit var camCode: TextView
    private lateinit var camInfo: TextView

    private lateinit var gpsDot: View
    private lateinit var gpsTitle: TextView
    private lateinit var speedValue: TextView
    private lateinit var altValue: TextView
    private lateinit var satValue: TextView
    private lateinit var gpsInfo: TextView

    private lateinit var startButton: Button
    private lateinit var recordButton: Button
    private lateinit var batteryButton: Button
    private lateinit var logView: TextView

    private val stateListener: () -> Unit = { render() }
    private val logListener: () -> Unit = { renderLog() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        TelemetState.addListener(stateListener)
        AppLog.addListener(logListener)
        render()
        renderLog()
    }

    override fun onStop() {
        TelemetState.removeListener(stateListener)
        AppLog.removeListener(logListener)
        super.onStop()
    }

    // ---------------------------------------------------------------- Arayüz kurulumu

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(size: Float, color: Int = TEXT, bold: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dot(): View = View(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(MUTED) }
        layoutParams = LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) }
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(CARD) }
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
    }

    private fun header(dotView: View, title: TextView): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(dotView)
        addView(title)
    }

    private fun button(label: String, color: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(color) }
        minHeight = dp(52)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) }
    }

    private fun metric(label: String): Pair<LinearLayout, TextView> {
        val value = text(26f, TEXT, bold = true).apply { text = "–" }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(value)
            addView(text(12f, MUTED).apply { text = label })
        }
        return box to value
    }

    private fun toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch = Switch(this).apply {
        text = label
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isChecked = checked
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }

        root.addView(text(28f, ACCENT, bold = true).apply { text = "Telemet" })
        root.addView(text(13f, MUTED).apply {
            text = "Telefonun GPS'ini DJI kameraya, GPS kumandası gibi gönderir."
            setPadding(0, 0, 0, dp(16))
        })

        // Kamera kartı
        camDot = dot()
        camTitle = text(18f, bold = true)
        camDetail = text(14f, MUTED).apply { setPadding(0, dp(6), 0, 0) }
        camCode = text(40f, YELLOW, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
            visibility = View.GONE
        }
        camInfo = text(14f).apply { setPadding(0, dp(8), 0, 0) }
        root.addView(card().apply {
            addView(header(camDot, camTitle))
            addView(camDetail)
            addView(camCode)
            addView(camInfo)
        })

        // GPS kartı
        gpsDot = dot()
        gpsTitle = text(18f, bold = true)
        val (speedBox, sv) = metric("km/sa")
        val (altBox, av) = metric("yükseklik (m)")
        val (satBox, satv) = metric("uydu")
        speedValue = sv
        altValue = av
        satValue = satv
        gpsInfo = text(13f, MUTED).apply { setPadding(0, dp(10), 0, 0) }
        root.addView(card().apply {
            addView(header(gpsDot, gpsTitle))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
                addView(speedBox)
                addView(altBox)
                addView(satBox)
            })
            addView(gpsInfo)
        })

        // Düğmeler
        startButton = button("Başlat", ACCENT) { onStartStop() }
        recordButton = button("● Kaydı başlat / durdur", RED) { TelemetService.toggleRecord(this) }
        root.addView(startButton)
        root.addView(recordButton)

        // Ayarlar
        root.addView(card().apply {
            addView(text(16f, bold = true).apply { text = "Ayarlar" })
            addView(toggle("Akıcı GPS (10 Hz, DJI kumandası gibi)", prefs.smoothGps) {
                prefs.smoothGps = it
                AppLog.i(if (it) "Akıcı GPS açık (10 Hz)" else "Akıcı GPS kapalı (yalnızca gerçek ölçümler)")
            })
            addView(toggle("Sürüşü GPX olarak da kaydet", prefs.gpxEnabled) {
                prefs.gpxEnabled = it
                if (TelemetState.serviceRunning) {
                    Toast.makeText(this@MainActivity, "Bir sonraki başlatmada geçerli olur", Toast.LENGTH_SHORT).show()
                }
            })
            addView(text(12f, MUTED).apply {
                text = "GPX dosyaları İndirilenler/Telemet klasörüne kaydedilir."
                setPadding(0, 0, 0, dp(8))
            })
            batteryButton = button("Pil kısıtlamasını kaldır (önerilir)", 0xFF37474F.toInt()) { requestBatteryExemption() }
            addView(batteryButton)
            addView(button("Kamera eşleşmesini unut", 0xFF37474F.toInt()) {
                prefs.forgetCamera()
                AppLog.i("Kayıtlı kamera unutuldu. Bir sonraki bağlantıda en yakın kamera seçilecek.")
            })
        })

        // Günlük
        root.addView(card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text(16f, bold = true).apply {
                    text = "Günlük"
                    layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                })
                addView(Button(this@MainActivity).apply {
                    text = "Paylaş"
                    isAllCaps = false
                    setOnClickListener { shareLog() }
                })
            })
            logView = text(11f, 0xFFB0BEC5.toInt()).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                setPadding(0, dp(8), 0, 0)
            }
            addView(logView)
        })

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(root)
            // Android 15+ uygulamayı durum çubuğunun altına kadar çizer; içeriği güvenli alana it.
            setOnApplyWindowInsetsListener { _, insets ->
                val top: Int
                val bottom: Int
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    top = bars.top
                    bottom = bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    top = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    bottom = insets.systemWindowInsetBottom
                }
                root.setPadding(dp(16), dp(20) + top, dp(16), dp(24) + bottom)
                insets
            }
        }
    }

    // ---------------------------------------------------------------- Görüntüleme

    private fun setDot(v: View, color: Int) {
        (v.background as GradientDrawable).setColor(color)
    }

    private fun render() {
        val s = TelemetState

        // Kamera
        val (title, color) = when (s.linkState) {
            CameraLink.State.READY -> "Kamera bağlı" to GREEN
            CameraLink.State.HANDSHAKE -> "Kamerada onay bekleniyor" to YELLOW
            CameraLink.State.SCANNING -> "Kamera aranıyor" to YELLOW
            CameraLink.State.CONNECTING -> "Bağlanılıyor" to YELLOW
            CameraLink.State.RECONNECTING -> "Yeniden bağlanılıyor" to YELLOW
            CameraLink.State.IDLE -> "Kamera bağlı değil" to MUTED
        }
        camTitle.text = title
        setDot(camDot, color)
        camDetail.text = s.linkDetail.ifEmpty { if (s.serviceRunning) "" else "Başlat'a basın." }
        val code = s.verifyCode
        if (code != null && s.linkState == CameraLink.State.HANDSHAKE) {
            camCode.visibility = View.VISIBLE
            camCode.text = code.toString()
        } else {
            camCode.visibility = View.GONE
        }
        val st = s.cameraStatus
        val info = buildList {
            val name = listOfNotNull(s.cameraModel, s.cameraName).distinct().joinToString(" • ")
            if (name.isNotEmpty()) add(name)
            if (st != null && s.linkState == CameraLink.State.READY) {
                add("${st.modeName} • ${st.statusName}" + if (st.isRecording) " ●" else "")
                if (st.batteryPercent >= 0) add("Pil: %${st.batteryPercent}")
                if (st.isSleeping) add("Kamera uykuda — GPS gönderimi duraklatıldı")
                if (st.tempWarning >= 1) add("⚠ Kamera ısınıyor")
            }
        }
        camInfo.text = info.joinToString("\n")
        camInfo.visibility = if (info.isEmpty()) View.GONE else View.VISIBLE

        // GPS
        when {
            !s.serviceRunning -> {
                gpsTitle.text = "GPS kapalı"
                setDot(gpsDot, MUTED)
            }
            s.hasFix -> {
                gpsTitle.text = "GPS sinyali var"
                setDot(gpsDot, GREEN)
            }
            else -> {
                gpsTitle.text = "GPS sinyali aranıyor"
                setDot(gpsDot, YELLOW)
            }
        }
        if (s.serviceRunning && s.hasFix) {
            speedValue.text = "%.0f".format(s.speedKmh)
            altValue.text = "%.0f".format(s.altitudeM)
            satValue.text = "${s.satellitesUsed}"
            gpsInfo.text = "Doğruluk ±%.0f m • Kameraya %.1f paket/sn • Toplam %d".format(
                s.horizontalAccuracyM, s.gpsPacketsPerSecond, s.gpsPacketsSent,
            ) + (s.gpxFile?.let { "\nGPX: $it" } ?: "")
        } else {
            speedValue.text = "–"
            altValue.text = "–"
            satValue.text = if (s.serviceRunning && s.satellitesVisible > 0) "0/${s.satellitesVisible}" else "–"
            gpsInfo.text = if (s.serviceRunning) "Açık havada bekleyin; ilk sinyal 30–60 sn sürebilir." else ""
        }

        // Düğmeler
        startButton.text = if (s.serviceRunning) "Durdur" else "Başlat"
        (startButton.background as GradientDrawable).setColor(if (s.serviceRunning) 0xFF455A64.toInt() else ACCENT)
        val ready = s.linkState == CameraLink.State.READY
        recordButton.isEnabled = ready
        recordButton.alpha = if (ready) 1f else 0.4f
        recordButton.text = when {
            st?.isRecording == true -> "■ Kaydı durdur"
            else -> "● Kaydı başlat"
        }
        batteryButton.visibility = if (isIgnoringBatteryOptimizations()) View.GONE else View.VISIBLE
    }

    private fun renderLog() {
        logView.text = AppLog.snapshot(80).asReversed().joinToString("\n").ifEmpty { "Henüz kayıt yok." }
    }

    // ---------------------------------------------------------------- Başlat / durdur

    private var askedPermissions = false

    private fun onStartStop() {
        if (TelemetState.serviceRunning) {
            TelemetService.stop(this)
            return
        }
        val missing = missingPermissions()
        val requiredMissing = missing.filter { it != Manifest.permission.POST_NOTIFICATIONS }
        // Bildirim izni isteğe bağlı: bir kez sorulur, reddedilse de devam edilir.
        if (requiredMissing.isNotEmpty() || (missing.isNotEmpty() && !askedPermissions)) {
            askedPermissions = true
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) {
            Toast.makeText(this, "Bu telefonda Bluetooth yok", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            try {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
            } catch (e: SecurityException) {
                Toast.makeText(this, "Lütfen Bluetooth'u açın", Toast.LENGTH_LONG).show()
            }
            return
        }
        val lm = getSystemService(LocationManager::class.java)
        if (lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) != true) {
            Toast.makeText(this, "Lütfen telefonun konumunu (GPS) açın", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        TelemetService.start(this)
    }

    private fun missingPermissions(): List<String> {
        val needed = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            needed += Manifest.permission.BLUETOOTH_SCAN
            needed += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        return needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        val stillMissing = missingPermissions().filter { it != Manifest.permission.POST_NOTIFICATIONS }
        if (stillMissing.isEmpty()) {
            onStartStop()
            return
        }
        AppLog.w("Gerekli izinler verilmedi: " + stillMissing.joinToString { it.substringAfterLast('.') })
        Toast.makeText(this, "Konum ve Bluetooth izinleri olmadan çalışamaz", Toast.LENGTH_LONG).show()
        if (stillMissing.any { !shouldShowRequestPermissionRationale(it) }) {
            // "Bir daha sorma" seçildiyse ayarlara yönlendir.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    @Deprecated("Activity sonucu")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE_BT && resultCode == RESULT_OK) onStartStop()
    }

    // ---------------------------------------------------------------- Diğer

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun shareLog() {
        val body = buildString {
            appendLine("Telemet günlüğü")
            appendLine("Telefon: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("MTU: ${TelemetState.mtu}")
            appendLine()
            AppLog.snapshot().forEach { appendLine(it) }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Telemet günlüğü")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        startActivity(Intent.createChooser(send, "Günlüğü paylaş"))
    }
}
