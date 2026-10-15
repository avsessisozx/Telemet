package io.github.avsessisozx.telemet.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
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
import android.view.WindowManager
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
import io.github.avsessisozx.telemet.service.GpsWarmup
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
    private lateinit var logView: TextView

    private lateinit var setupCard: LinearLayout
    private lateinit var setupList: TextView
    private lateinit var distValue: TextView
    private lateinit var leanValue: TextView
    private lateinit var gValue: TextView
    private lateinit var maxLeanValue: TextView
    private lateinit var sensorInfo: TextView

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
        // Durdur'a basılmadan kapanmış bir sürüş varsa kaldığı yerden devam et.
        if (prefs.shouldRun && !TelemetState.serviceRunning && requiredPermissionsMissing().isEmpty()) {
            AppLog.i("Yarım kalan sürüşe devam ediliyor")
            TelemetService.resume(this)
        }
        // Başlat'a basılmadan önce GPS uydu aramaya başlasın.
        GpsWarmup.start(this)
        render()
        renderLog()
    }

    override fun onResume() {
        super.onResume()
        render()
        if (awaitingSetupReturn) {
            awaitingSetupReturn = false
            // Ayar ekranından ya da izin penceresinden dönüldü: kuruluma kaldığı yerden devam et.
            window.decorView.postDelayed({ if (startFlowActive) continueStartFlow() }, 300)
        }
    }

    override fun onStop() {
        GpsWarmup.stop()
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

        // Kurulum kartı: her şey tamamsa gizlenir
        setupList = text(14f).apply { setPadding(0, dp(8), 0, dp(8)) }
        setupCard = card().apply {
            addView(text(18f, YELLOW, bold = true).apply { text = "Kurulum" })
            addView(text(13f, MUTED).apply {
                text = "Sürüş boyunca ekran kapansa ya da başka uygulama açılsa bile kaydın durmaması için bunlar gerekli. Bir kez yapılır."
                setPadding(0, dp(4), 0, 0)
            })
            addView(setupList)
            addView(button("Kurulumu tamamla", ACCENT) { startFlow(thenStart = false) })
        }
        root.addView(setupCard)

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
        val (distBox, dv) = metric("km")
        distValue = dv
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
                addView(distBox)
                addView(satBox)
            })
            val (leanBox, lv) = metric("yatış")
            val (gBox, gv) = metric("G")
            val (maxLeanBox, mlv) = metric("en çok yatış")
            leanValue = lv
            gValue = gv
            maxLeanValue = mlv
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
                addView(leanBox)
                addView(gBox)
                addView(maxLeanBox)
            })
            sensorInfo = text(12f, MUTED).apply { setPadding(0, dp(8), 0, 0) }
            addView(sensorInfo)
            addView(gpsInfo)
        })

        // Düğmeler
        startButton = button("Başlat", ACCENT) { onStartStop() }
        recordButton = button("● Kaydı başlat / durdur", RED) { TelemetService.toggleRecord(this) }
        root.addView(startButton)
        root.addView(recordButton)
        root.addView(button("Sürüşlerim", 0xFF2E7D32.toInt()) {
            startActivity(Intent(this, RidesActivity::class.java))
        })

        // Ayarlar
        root.addView(card().apply {
            addView(text(16f, bold = true).apply { text = "Ayarlar" })
            addView(toggle("Akıcı GPS (10 Hz, DJI kumandası gibi)", prefs.smoothGps) {
                prefs.smoothGps = it
                AppLog.i(if (it) "Akıcı GPS açık (10 Hz)" else "Akıcı GPS kapalı (yalnızca gerçek ölçümler)")
            })
            addView(toggle("Her sürüşü ayrıca FIT + GPX olarak kaydet", prefs.backupEnabled) {
                prefs.backupEnabled = it
                if (TelemetState.serviceRunning) {
                    Toast.makeText(this@MainActivity, "Bir sonraki başlatmada geçerli olur", Toast.LENGTH_SHORT).show()
                }
            })
            addView(text(12f, MUTED).apply {
                text = "Dosyalar Durdur'a basınca İndirilenler/Telemet klasörüne çıkar. FIT dosyası DJI Studio'da " +
                    "Pano → Veri kaynağı → FIT Dosyasını İçe Aktar ile kullanılabilir."
                setPadding(0, 0, 0, dp(8))
            })
            addView(toggle("Telemet açıkken ekranı açık tut", prefs.keepScreenOn) {
                prefs.keepScreenOn = it
                render()
            })
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

        // Kurulum
        val steps = setupSteps()
        setupList.text = steps.joinToString("\n") { (if (it.done()) "✓ " else "✗ ") + it.label }
        setupCard.visibility = if (steps.all { it.done() }) View.GONE else View.VISIBLE

        // Ekranı açık tut (video oynatıcı ve navigasyon uygulamaları gibi)
        if (prefs.keepScreenOn && s.serviceRunning) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        distValue.text = if (s.serviceRunning && s.rideFile != null) "%.1f".format(s.distanceKm) else "–"
        if (s.serviceRunning && s.hasGyro) {
            leanValue.text = leanText(s.leanDeg)
            gValue.text = "%.2f".format(s.gForce)
            maxLeanValue.text = leanText(s.maxLeanDeg)
        } else {
            leanValue.text = "–"
            gValue.text = "–"
            maxLeanValue.text = "–"
        }
        sensorInfo.text = if (s.serviceRunning) {
            "Sensörler: GPS ✓  •  jiroskop " + (if (s.hasGyro) "✓" else "✗") +
                "  •  barometre " + (if (s.hasBarometer) "✓ (yükseklik barometreden)" else "✗ (yükseklik GPS'ten)")
        } else {
            ""
        }

        // GPS
        when {
            !s.serviceRunning -> {
                gpsTitle.text = if (GpsWarmup.ready) "GPS hazır, Başlat'a basabilirsiniz" else "GPS uydu arıyor (${GpsWarmup.satellites} uydu)"
                setDot(gpsDot, if (GpsWarmup.ready) GREEN else YELLOW)
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
            ) + (s.rideFile?.let { "\nYedek kayıt: $it" } ?: "")
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
            s.recordPending -> "GPS bekleniyor, sinyal gelince kayıt başlayacak\n(hemen başlatmak için tekrar bas)"
            else -> "● Kaydı başlat"
        }
    }

    /** Sola yatış ◀, sağa yatış ▶ ile gösterilir. */
    private fun leanText(deg: Double): String = when {
        deg > 1 -> "◀%.0f°".format(deg)
        deg < -1 -> "%.0f°▶".format(-deg)
        else -> "0°"
    }

    private fun renderLog() {
        logView.text = AppLog.snapshot(80).asReversed().joinToString("\n").ifEmpty { "Henüz kayıt yok." }
    }

    // ---------------------------------------------------------------- Başlat / durdur ve kurulum

    /** Bir kurulum adımı. [required] olmayanlar atlanabilir ama kesintisiz kayıt için önerilir. */
    private class Step(val key: String, val label: String, val required: Boolean, val done: () -> Boolean, val action: () -> Unit)

    private val isXiaomi: Boolean
        get() = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

    private fun setupSteps(): List<Step> = buildList {
        add(Step("perm", "Konum, Bluetooth ve bildirim izinleri", true, { requiredPermissionsMissing().isEmpty() }) {
            askPermissions(allPermissionsMissing())
        })
        add(Step("bgloc", "Konum izni: \"Her zaman izin ver\"", false, { hasBackgroundLocation() }) {
            explain(
                "Konum: Her zaman izin ver",
                "Navigasyon açıkken ya da ekran kapalıyken de GPS'in kesilmemesi için açılacak ekranda " +
                    "\"Her zaman izin ver\" seçeneğini seçin.",
            ) { askPermissions(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) }
        })
        add(Step("battery", "Pil kısıtlaması kaldırıldı", false, { isIgnoringBatteryOptimizations() }) {
            requestBatteryExemption()
        })
        if (isXiaomi) {
            add(Step("autostart", "Xiaomi: Otomatik başlatma açık", false, { prefs.setupAutostartDone }) {
                explain(
                    "Otomatik başlatma",
                    "Açılacak listede Telemet'i bulup anahtarını açın. Bu, telefon uygulamayı kapatsa bile " +
                        "kaydın kendiliğinden devam etmesini sağlar.",
                ) {
                    prefs.setupAutostartDone = true
                    openFirst(
                        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
                        appDetails(),
                    )
                }
            })
            add(Step("saver", "Xiaomi: Pil tasarrufu \"Kısıtlama yok\"", false, { prefs.setupBatterySaverDone }) {
                explain(
                    "Pil tasarrufu",
                    "Açılacak ekranda \"Kısıtlama yok\" seçeneğini seçin. Varsayılan ayarda MIUI, başka uygulama " +
                        "öndeyken Telemet'in GPS'ini kesiyor; son sürüşte veri kaybının sebebi buydu.",
                ) {
                    prefs.setupBatterySaverDone = true
                    openFirst(
                        Intent().setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                            .putExtra("package_name", packageName)
                            .putExtra("package_label", "Telemet"),
                        appDetails(),
                    )
                }
            })
        }
    }

    private var startFlowActive = false
    private var startAfterSetup = false
    private var awaitingSetupReturn = false
    private val attemptedSteps = mutableSetOf<String>()

    private fun onStartStop() {
        if (TelemetState.serviceRunning) {
            TelemetService.stop(this)
            return
        }
        startFlow(thenStart = true)
    }

    /** Eksik kurulum adımlarını sırayla yürütür, sonra (istenirse) sürüşü başlatır. */
    private fun startFlow(thenStart: Boolean) {
        startFlowActive = true
        startAfterSetup = thenStart
        attemptedSteps.clear()
        continueStartFlow()
    }

    private fun continueStartFlow() {
        val steps = setupSteps()
        val next = steps.firstOrNull { !it.done() && it.key !in attemptedSteps }
        if (next != null) {
            attemptedSteps += next.key
            next.action()
            return
        }
        startFlowActive = false
        render()
        val missingRequired = steps.filter { it.required && !it.done() }
        if (missingRequired.isNotEmpty()) {
            AppLog.w("Gerekli izinler verilmedi: " + requiredPermissionsMissing().joinToString { it.substringAfterLast('.') })
            Toast.makeText(this, "Konum ve Bluetooth izinleri olmadan çalışamaz", Toast.LENGTH_LONG).show()
            startActivity(appDetails())
            return
        }
        val skipped = steps.filter { !it.done() }
        if (skipped.isNotEmpty()) AppLog.w("Kurulum eksik: " + skipped.joinToString { it.label } + ". Kayıt arka planda kesilebilir.")
        if (startAfterSetup) launchService()
    }

    /** Kurulum bitti; Bluetooth ve konum açık mı bakıp servisi başlatır. */
    private fun launchService() {
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

    private fun allPermissionsMissing(): List<String> {
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

    /** Bildirim izni isteğe bağlı; diğerleri olmadan çalışılamaz. */
    private fun requiredPermissionsMissing(): List<String> =
        allPermissionsMissing().filter { it != Manifest.permission.POST_NOTIFICATIONS }

    private fun hasBackgroundLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun askPermissions(perms: List<String>) {
        if (perms.isEmpty()) {
            continueStartFlow()
            return
        }
        awaitingSetupReturn = true
        requestPermissions(perms.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Akış onResume'da sürer. Bazı telefonlar izin penceresinde onResume çağırmıyor; bu yüzden burada da tetikle.
        if (requestCode == REQ_PERMISSIONS && awaitingSetupReturn) {
            awaitingSetupReturn = false
            window.decorView.postDelayed({ if (startFlowActive) continueStartFlow() }, 300)
        }
    }

    @Deprecated("Activity sonucu")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE_BT && resultCode == RESULT_OK) launchService()
    }

    /** Açıklama gösterir; Tamam'a basılırsa [then] çalışır, vazgeçilirse sonraki adıma geçilir. */
    private fun explain(title: String, message: String, then: () -> Unit) {
        var accepted = false
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Tamam") { _, _ ->
                accepted = true
                then()
            }
            .setNegativeButton("Atla", null)
            .setOnDismissListener { if (!accepted && startFlowActive) continueStartFlow() }
            .show()
    }

    /** Verilen ekranlardan açılabilen ilkini açar. */
    private fun openFirst(vararg intents: Intent) {
        for (i in intents) {
            try {
                awaitingSetupReturn = true
                startActivity(i)
                return
            } catch (e: Exception) {
                awaitingSetupReturn = false
            }
        }
        if (startFlowActive) continueStartFlow()
    }

    private fun appDetails(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))

    // ---------------------------------------------------------------- Diğer

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    private fun requestBatteryExemption() {
        openFirst(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
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
