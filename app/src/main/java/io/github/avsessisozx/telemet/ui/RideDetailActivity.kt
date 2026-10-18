package io.github.avsessisozx.telemet.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import io.github.avsessisozx.telemet.R
import io.github.avsessisozx.telemet.fit.FitEncoder
import io.github.avsessisozx.telemet.service.RideStore
import io.github.avsessisozx.telemet.util.AppLog
import io.github.avsessisozx.telemet.util.LocaleHelper
import io.github.avsessisozx.telemet.util.ShareProvider
import io.github.avsessisozx.telemet.util.Units
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

/** Bir sürüşün ayrıntısı: rota çizimi, istatistikler, grafikler; paylaş, yeniden adlandır, sil. */
class RideDetailActivity : Activity() {

    companion object {
        const val EXTRA_ID = "ride_id"
    }

    private lateinit var ui: Ui
    private lateinit var units: Units
    private lateinit var ride: RideStore.Ride
    private var points: List<FitEncoder.Decoded> = emptyList()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        units = Units.of(this)
        Ui.styleWindow(this)
        val r = intent.getStringExtra(EXTRA_ID)?.let { RideStore.get(this, it) }
        if (r == null) {
            finish()
            return
        }
        ride = r
        points = RideStore.points(this, ride.id)
        setContentView(build())
    }

    private fun build(): View {
        val locale = resources.configuration.locales[0]
        val day = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMyyyy"), locale)
            .format(Date(ride.startUtcMillis))
        val time: DateFormat = android.text.format.DateFormat.getTimeFormat(this)
        val times = "${time.format(Date(ride.startUtcMillis))} – ${time.format(Date(ride.endUtcMillis))}"

        val root = ui.vertical()
        root.addView(ui.toolbar(ride.name.ifEmpty { day }) { finish() })
        root.addView(ui.text(14f, Theme.MUTED, value = if (ride.name.isEmpty()) times else "$day\n$times").apply {
            setPadding(ui.dp(4), 0, 0, ui.dp(12))
        })

        // Rota
        if (points.size >= 2) {
            val rawLat = DoubleArray(points.size) { FitEncoder.degrees(points[it].latSemi) }
            val rawLon = DoubleArray(points.size) { FitEncoder.degrees(points[it].lonSemi) }
            // Çizimde GPS titremesini yumuşat (ayar açıksa). Ham noktalara dokunulmaz.
            val (drawLat, drawLon) = if (io.github.avsessisozx.telemet.util.Prefs(this).smoothRoute) {
                io.github.avsessisozx.telemet.service.RouteSmoother.smooth(rawLat, rawLon)
            } else {
                rawLat to rawLon
            }
            root.addView(RouteView(
                this,
                drawLat,
                drawLon,
                DoubleArray(points.size) { points[it].enhancedSpeedMs },
            ).apply { tag = "route_view" }, ui.matchWrap(12))
        } else {
            root.addView(ui.card().apply { addView(ui.text(14f, Theme.MUTED, value = getString(R.string.detail_no_track))) })
        }

        // İstatistikler
        val tiles = mutableListOf<View>(
            ui.tile(getString(R.string.label_distance), units.distance(ride.distanceM)),
            ui.tile(getString(R.string.label_duration), units.duration(ride.durationS)),
        )
        // Hareket süresi: toplam süreden belirgin azsa (duruş olmuşsa) göster.
        val movingS = if (points.size >= 2) {
            io.github.avsessisozx.telemet.service.MovingTime.seconds(
                LongArray(points.size) { points[it].fitTime + FitEncoder.FIT_EPOCH_S },
                DoubleArray(points.size) { points[it].enhancedSpeedMs },
            )
        } else 0L
        val showMoving = movingS > 0 && ride.durationS - movingS >= 30
        if (showMoving) {
            tiles += ui.tile(getString(R.string.label_moving_time), units.duration(movingS))
            // Hareket halindeyken ortalama hız.
            val movingAvg = if (movingS > 0) ride.distanceM / movingS else 0.0
            tiles += ui.tile(getString(R.string.label_avg_moving_speed), units.speed(movingAvg))
        }
        tiles += ui.tile(getString(R.string.label_avg_speed), units.speed(ride.avgSpeedMs))
        tiles += ui.tile(getString(R.string.label_max_speed), units.speed(ride.maxSpeedMs))
        tiles += ui.tile(getString(R.string.label_climb), units.altitude(ride.ascentM))
        if (points.isNotEmpty()) {
            tiles += ui.tile(getString(R.string.label_max_altitude), units.altitude(points.maxOf { it.enhancedAltitudeM }))
        }
        root.addView(ui.tileGrid(tiles))

        if (ride.hasDynamics) {
            root.addView(ui.sectionHeader(getString(R.string.detail_dynamics)))
            root.addView(ui.tileGrid(listOf(
                ui.tile(getString(R.string.label_lean_left), units.degrees(ride.maxLeanLeftDeg)),
                ui.tile(getString(R.string.label_lean_right), units.degrees(ride.maxLeanRightDeg)),
                ui.tile(getString(R.string.label_corner_g), units.g(ride.maxLateralG)),
                ui.tile(getString(R.string.label_brake_g), units.g(ride.maxBrakeG)),
                ui.tile(getString(R.string.label_accel_g), units.g(ride.maxAccelG)),
            )))
        }

        // Grafikler
        if (points.size >= 2) {
            root.addView(ui.sectionHeader(getString(R.string.detail_speed)))
            root.addView(LineChartView(this, DoubleArray(points.size) { units.speedValue(points[it].enhancedSpeedMs) }, Theme.ACCENT) {
                String.format(locale, "%.0f %s", it, units.speedUnit)
            }, ui.matchWrap(4))
            root.addView(ui.sectionHeader(getString(R.string.label_altitude)))
            root.addView(LineChartView(this, DoubleArray(points.size) { units.altitudeValue(points[it].enhancedAltitudeM) }, 0xFF42A5F5.toInt()) {
                String.format(locale, "%.0f %s", it, units.altitudeUnit)
            }, ui.matchWrap(4))
            if (ride.hasDynamics) {
                root.addView(ui.sectionHeader(getString(R.string.label_lean)))
                root.addView(LineChartView(this, DoubleArray(points.size) { points[it].leanDeg }, 0xFFAB47BC.toInt(), symmetric = true) {
                    String.format(locale, "%.0f°", kotlin.math.abs(it))
                }, ui.matchWrap(4))
            }
        }

        root.addView(ui.text(13f, Theme.MUTED, value = getString(R.string.detail_fit_hint)).apply {
            setPadding(ui.dp(4), ui.dp(16), ui.dp(4), ui.dp(16))
        })
        root.addView(ui.button(getString(R.string.ride_share), Theme.ACCENT) { share() }.apply { tag = "detail_share" })
        root.addView(ui.horizontal().apply {
            layoutParams = ui.matchWrap(0)
            val rename = ui.button(getString(R.string.ride_rename), Theme.GREY_BUTTON) { rename() }.apply { tag = "detail_rename" }
            val delete = ui.button(getString(R.string.ride_delete), Theme.DARK_RED) { confirmDelete(day, times) }.apply { tag = "detail_delete" }
            rename.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = ui.dp(8) }
            delete.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(rename)
            addView(delete)
        })
        return ui.scrollPage(root)
    }

    private fun share() {
        val raw = RideStore.raw(this, ride.id)
        if (raw != null && points.isNotEmpty()) {
            try {
                val dir = ShareProvider.freshDir(this)
                val base = ride.name.ifEmpty { ride.id }.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifEmpty { ride.id }
                val fit = File(dir, "$base.fit").apply { writeBytes(FitEncoder.buildFile(raw, ride.startUtcMillis, io.github.avsessisozx.telemet.util.Prefs(this@RideDetailActivity).mimoExtras) ?: ByteArray(0)) }
                val gpx = File(dir, "$base.gpx").apply { writeText(RideStore.gpx(ride.name.ifEmpty { ride.id }, points)) }
                ShareProvider.share(this, listOf(fit, gpx), getString(R.string.share_subject, ride.name.ifEmpty { ride.id }), getString(R.string.share_chooser))
                return
            } catch (e: Exception) {
                AppLog.w("Could not prepare ride files", e)
            }
        }
        // Eski sürümlerden kalan sürüşler: İndirilenler'deki dosyaları paylaş.
        val uris = arrayListOf<Uri>()
        ride.fitUri?.let { uris += it }
        ride.gpxUri?.let { uris += it }
        if (uris.isEmpty()) {
            Toast.makeText(this, R.string.share_files_missing, Toast.LENGTH_LONG).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_subject, ride.id))
            clipData = ClipData.newRawUri(ride.id, uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_chooser)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun rename() {
        val input = EditText(this).apply {
            setText(ride.name)
            hint = getString(R.string.rename_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
            setSelection(text.length)
            tag = "rename_input"
        }
        val box = FrameLayout(this).apply {
            setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.ride_rename)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                RideStore.rename(this, ride.id, input.text.toString())
                RideStore.get(this, ride.id)?.let { ride = it }
                setContentView(build())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(day: String, times: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_msg, "$day $times"))
            .setPositiveButton(R.string.ride_delete) { _, _ ->
                RideStore.delete(this, ride)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
