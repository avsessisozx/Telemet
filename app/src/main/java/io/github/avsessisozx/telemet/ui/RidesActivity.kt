package io.github.avsessisozx.telemet.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.avsessisozx.telemet.R
import io.github.avsessisozx.telemet.service.RideStore
import io.github.avsessisozx.telemet.service.TelemetState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Sürüşlerim": her sürüş ayrı satırda, güne göre gruplanmış. */
class RidesActivity : Activity() {

    private companion object {
        const val BG = 0xFF121212.toInt()
        const val CARD = 0xFF1E1E1E.toInt()
        const val TEXT = 0xFFEDEDED.toInt()
        const val MUTED = 0xFF9E9E9E.toInt()
        const val ACCENT = 0xFFFF7A1A.toInt()
        const val GREEN = 0xFF4CAF50.toInt()
        const val GREY_BTN = 0xFF37474F.toInt()
        const val RED = 0xFFB71C1C.toInt()
    }

    // Tarih ve saat, seçili dilin biçiminde
    private val locale: Locale by lazy { resources.configuration.locales[0] }
    private val dayFormat by lazy {
        SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMyyyy"), locale)
    }
    private val timeFormat by lazy { android.text.format.DateFormat.getTimeFormat(this) }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(io.github.avsessisozx.telemet.util.LocaleHelper.wrap(newBase))
    }

    private lateinit var list: LinearLayout
    private lateinit var ongoing: TextView
    private var lastRideFile: String? = null

    private val stateListener: () -> Unit = { onStateChanged() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        TelemetState.addListener(stateListener)
        lastRideFile = TelemetState.rideFile
        reload()
    }

    override fun onStop() {
        TelemetState.removeListener(stateListener)
        super.onStop()
    }

    private fun onStateChanged() {
        renderOngoing()
        // Sürüş az önce bittiyse listeyi yenile (yeni sürüş en üste gelsin).
        val now = TelemetState.rideFile
        if (lastRideFile != null && now == null) {
            ongoing.postDelayed({ reload() }, 1_000)
        }
        lastRideFile = now
    }

    // ---------------------------------------------------------------- Arayüz

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun text(size: Float, color: Int = TEXT, bold: Boolean = false): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun smallButton(label: String, color: Int, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(color) }
        minHeight = dp(40)
        minimumHeight = dp(40)
        setPadding(dp(14), 0, dp(14), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(24))
        }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(text(26f, ACCENT, bold = true).apply {
                text = getString(R.string.rides_title)
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            })
            addView(smallButton(getString(R.string.back), GREY_BTN) { finish() })
        })
        root.addView(text(13f, MUTED).apply {
            text = getString(R.string.rides_desc)
            setPadding(0, dp(4), 0, dp(12))
        })
        ongoing = text(15f, GREEN, bold = true).apply {
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(CARD) }
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(12) }
            visibility = View.GONE
        }
        root.addView(ongoing)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        return ScrollView(this).apply {
            setBackgroundColor(BG)
            isFillViewport = true
            addView(root)
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

    private fun renderOngoing() {
        val s = TelemetState
        if (s.serviceRunning && s.rideFile != null) {
            ongoing.visibility = View.VISIBLE
            ongoing.text = getString(R.string.rides_ongoing, s.distanceKm)
        } else {
            ongoing.visibility = View.GONE
        }
    }

    private fun reload() {
        renderOngoing()
        list.removeAllViews()
        val rides = RideStore.list(this)
        if (rides.isEmpty()) {
            list.addView(text(15f, MUTED).apply {
                text = getString(R.string.rides_empty)
                setPadding(0, dp(24), 0, 0)
                gravity = Gravity.CENTER
            })
            return
        }
        // Güne göre grupla
        rides.groupBy { dayFormat.format(Date(it.startUtcMillis)) }.forEach { (day, dayRides) ->
            val km = dayRides.sumOf { it.distanceM } / 1000
            list.addView(text(15f, ACCENT, bold = true).apply {
                text = getString(R.string.rides_day_header, day, dayRides.size, km)
                setPadding(0, dp(8), 0, dp(8))
            })
            dayRides.forEach { list.addView(rideCard(it)) }
        }
    }

    private fun rideCard(r: RideStore.Ride): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(CARD) }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(10) }

        addView(text(17f, bold = true).apply {
            text = "${timeFormat.format(Date(r.startUtcMillis))} – ${timeFormat.format(Date(r.endUtcMillis))}   ${duration(r.durationS)}"
        })
        addView(text(22f, TEXT, bold = true).apply {
            text = "%.1f %s".format(r.distanceM / 1000, getString(R.string.metric_km))
            setPadding(0, dp(4), 0, 0)
        })
        addView(text(14f, MUTED).apply {
            text = getString(R.string.ride_stats, r.maxSpeedMs * 3.6, r.avgSpeedMs * 3.6, r.ascentM)
            setPadding(0, dp(2), 0, if (r.maxLeanLeftDeg + r.maxLeanRightDeg + r.maxBrakeG > 0) 0 else dp(10))
        })
        if (r.maxLeanLeftDeg + r.maxLeanRightDeg + r.maxBrakeG + r.maxAccelG > 0) {
            addView(text(14f, MUTED).apply {
                text = getString(
                    R.string.ride_dynamics,
                    r.maxLeanLeftDeg, r.maxLeanRightDeg, r.maxLateralG, r.maxBrakeG, r.maxAccelG,
                )
                setPadding(0, dp(2), 0, dp(10))
            })
        }
        addView(LinearLayout(this@RidesActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(smallButton(getString(R.string.ride_share), ACCENT) { share(r) })
            addView(smallButton(getString(R.string.ride_delete), RED) { confirmDelete(r) })
        })
    }

    private fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> getString(R.string.dur_h, h.toInt(), m.toInt())
            m > 0 -> getString(R.string.dur_m, m.toInt())
            else -> getString(R.string.dur_s, seconds.toInt())
        }
    }

    // ---------------------------------------------------------------- Eylemler

    private fun share(r: RideStore.Ride) {
        val uris = arrayListOf<Uri>()
        r.fitUri?.let { uris += it }
        r.gpxUri?.let { uris += it }
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.share_files_missing, Toast.LENGTH_LONG).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject, r.id))
            clipData = ClipData.newRawUri(r.id, uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmDelete(r: RideStore.Ride) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(
                getString(
                    R.string.delete_msg,
                    "${dayFormat.format(Date(r.startUtcMillis))} ${timeFormat.format(Date(r.startUtcMillis))}",
                ),
            )
            .setPositiveButton(R.string.ride_delete) { _, _ ->
                RideStore.delete(this, r)
                reload()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
