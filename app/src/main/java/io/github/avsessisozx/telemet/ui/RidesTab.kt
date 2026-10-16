package io.github.avsessisozx.telemet.ui

import android.content.Intent
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import io.github.avsessisozx.telemet.R
import io.github.avsessisozx.telemet.service.RideStore
import io.github.avsessisozx.telemet.service.TelemetState
import io.github.avsessisozx.telemet.util.Units
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date

/** Sürüşlerim sekmesi: toplamlar ve güne göre gruplanmış sürüş listesi. Dokununca ayrıntı açılır. */
class RidesTab(activity: MainActivity) : MainActivity.Tab(activity) {

    private val ctx = activity
    private val root = ui.vertical()
    override val view: View = ui.scrollPage(root, applyTopInset = false, applyBottomInset = false)

    private val totalsRow = ui.horizontal().apply { layoutParams = ui.matchWrap(12) }
    private val ongoing = ui.card(color = 0xFF1B2E1C.toInt())
    private val ongoingText = ui.text(15f, Theme.GREEN, bold = true)
    private val list = ui.vertical()

    private val locale = ctx.resources.configuration.locales[0]
    private val dayFormat = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMMyyyy"), locale)
    private val timeFormat: DateFormat = android.text.format.DateFormat.getTimeFormat(ctx)

    private var lastRideFile: String? = null
    private var shownCount = -1

    init {
        root.addView(ui.text(26f, Theme.TEXT, bold = true, value = ctx.getString(R.string.rides_title)).apply {
            setPadding(ui.dp(4), 0, 0, ui.dp(12))
        })
        root.addView(totalsRow)
        root.addView(ui.button(ctx.getString(R.string.vs_title), Theme.GREY_BUTTON) {
            ctx.startActivity(Intent(ctx, VideoSyncActivity::class.java))
        }.apply { tag = "match_videos" })
        ongoing.addView(ongoingText)
        root.addView(ongoing)
        root.addView(list)
        root.addView(ui.text(12f, Theme.MUTED, value = ctx.getString(R.string.rides_desc)).apply {
            setPadding(ui.dp(4), ui.dp(16), ui.dp(4), 0)
        })
    }

    override fun onShow() = reload()

    override fun onResume() = reload()

    override fun render() {
        val s = TelemetState
        val units = Units(ctx, activity.prefs.imperial)
        if (s.serviceRunning && s.rideFile != null) {
            ongoing.visibility = View.VISIBLE
            ongoingText.text = ctx.getString(R.string.rides_ongoing, units.distance(s.distanceKm * 1000))
        } else {
            ongoing.visibility = View.GONE
        }
        // Sürüş az önce bittiyse listeyi yenile (yeni sürüş en üste gelsin).
        val now = s.rideFile
        if (lastRideFile != null && now == null) view.postDelayed({ reload() }, 1_000)
        lastRideFile = now
    }

    fun reload() {
        render()
        val rides = RideStore.list(ctx)
        val units = Units(ctx, activity.prefs.imperial)
        shownCount = rides.size

        totalsRow.removeAllViews()
        val t = RideStore.totals(rides)
        val tiles = listOf(
            ui.tile(ctx.getString(R.string.label_rides), "${t.rides}"),
            ui.tile(ctx.getString(R.string.label_distance), units.distance(t.distanceM)),
            ui.tile(ctx.getString(R.string.label_time), units.duration(t.durationS)),
        )
        tiles[1].tag = "total_distance"
        tiles.forEachIndexed { i, tile ->
            tile.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i < tiles.size - 1) marginEnd = ui.dp(8)
            }
            totalsRow.addView(tile)
        }
        totalsRow.visibility = if (rides.isEmpty()) View.GONE else View.VISIBLE

        list.removeAllViews()
        if (rides.isEmpty()) {
            list.addView(ui.vertical().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, ui.dp(48), 0, ui.dp(24))
                addView(ui.icon(R.drawable.ic_tab_rides, Theme.MUTED, 48))
                addView(ui.text(16f, Theme.MUTED, value = ctx.getString(R.string.rides_empty)).apply {
                    gravity = Gravity.CENTER
                    setPadding(ui.dp(16), ui.dp(16), ui.dp(16), 0)
                })
            })
            return
        }
        rides.groupBy { dayFormat.format(Date(it.startUtcMillis)) }.forEach { (day, dayRides) ->
            list.addView(ui.horizontal().apply {
                setPadding(ui.dp(4), ui.dp(12), ui.dp(4), ui.dp(8))
                addView(ui.text(14f, Theme.ACCENT, bold = true, value = day).apply { layoutParams = ui.weight() })
                addView(ui.text(13f, Theme.MUTED, value = units.distance(dayRides.sumOf { it.distanceM })))
            })
            list.addView(ui.group(*dayRides.map { rideRow(it, units) }.toTypedArray()))
        }
    }

    private fun rideRow(r: RideStore.Ride, units: Units): View {
        val times = "${timeFormat.format(Date(r.startUtcMillis))} – ${timeFormat.format(Date(r.endUtcMillis))}"
        val title = r.name.ifEmpty { times }
        val sub = ctx.getString(R.string.ride_row_subtitle, units.distance(r.distanceM), units.duration(r.durationS), units.speed(r.maxSpeedMs)) +
            (if (r.name.isNotEmpty()) "\n$times" else "")
        val arrow = if (ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) "‹" else "›"
        val row = ui.row(title, sub, arrow) {
            ctx.startActivity(Intent(ctx, RideDetailActivity::class.java).putExtra(RideDetailActivity.EXTRA_ID, r.id))
        }
        row.title.maxLines = 1
        row.title.ellipsize = TextUtils.TruncateAt.END
        row.value.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 22f)
        row.view.tag = "ride_${r.id}"
        return row.view
    }
}
