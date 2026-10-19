package io.github.avsessisozx.telemet.ui

import android.content.Intent
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import io.github.avsessisozx.telemet.R
import io.github.avsessisozx.telemet.ble.CameraLink
import io.github.avsessisozx.telemet.service.GpsDiagnosis
import io.github.avsessisozx.telemet.service.GpsWarmup
import io.github.avsessisozx.telemet.service.TelemetService
import io.github.avsessisozx.telemet.service.TelemetState
import io.github.avsessisozx.telemet.util.Permissions
import io.github.avsessisozx.telemet.util.Units

/** Sürüş sekmesi: kamera ve GPS durumu, büyük hız göstergesi, Başlat/Durdur ve Kayıt. */
class RideTab(activity: MainActivity) : MainActivity.Tab(activity) {

    private val ctx = activity
    private val root = ui.vertical()
    override val view: View = ui.scrollPage(root, applyTopInset = false, applyBottomInset = false)

    private val banner: LinearLayout
    private val camDot = ui.dot()
    private val camTitle = ui.text(18f, bold = true)
    private val camDetail = ui.text(14f, Theme.MUTED).apply { setPadding(0, ui.dp(6), 0, 0) }
    private val camCode = ui.text(40f, Theme.YELLOW, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(0, ui.dp(8), 0, ui.dp(4))
        visibility = View.GONE
    }
    private val camInfo = ui.text(14f).apply { setPadding(0, ui.dp(8), 0, 0) }

    private val gpsDot = ui.dot(sizeDp = 10)
    private val gpsTitle = ui.text(14f, Theme.MUTED)
    private val speedValue = ui.text(84f, Theme.TEXT, bold = true, value = "0").apply {
        gravity = Gravity.CENTER
        includeFontPadding = false
    }
    private val speedUnit = ui.text(16f, Theme.MUTED).apply { gravity = Gravity.CENTER }
    private val distance = ui.metric("", 22f)
    private val altitude = ui.metric("", 22f)
    private val satellites = ui.metric(ctx.getString(R.string.label_satellites), 22f)

    private val sensorsBody = ui.vertical()
    private val sensorsArrow = ui.text(16f, Theme.MUTED)
    private val lean = ui.metric(ctx.getString(R.string.label_lean), 22f)
    private val gForce = ui.metric(ctx.getString(R.string.label_gforce), 22f)
    private val maxLean = ui.metric(ctx.getString(R.string.label_max_lean), 22f)
    private val sensorInfo = ui.text(12f, Theme.MUTED).apply { setPadding(0, ui.dp(10), 0, 0) }
    private val gpsInfo = ui.text(12f, Theme.MUTED).apply { setPadding(0, ui.dp(6), 0, 0) }

    private val startButton: Button
    private val recordButton: Button

    init {
        root.addView(ui.text(26f, Theme.ACCENT, bold = true, value = ctx.getString(R.string.app_name)).apply {
            setPadding(ui.dp(4), 0, 0, ui.dp(12))
        })

        // Kurulum eksikse uyarı şeridi
        banner = ui.card(color = Theme.WARNING_BG).apply {
            background = ui.ripple(ui.rounded(Theme.WARNING_BG))
            isClickable = true
            tag = "setup_banner"
            addView(ui.text(16f, Theme.YELLOW, bold = true, value = ctx.getString(R.string.banner_setup_title)))
            addView(ui.text(14f, Theme.TEXT, value = ctx.getString(R.string.banner_setup_msg)).apply { setPadding(0, ui.dp(4), 0, 0) })
            setOnClickListener {
                ctx.startActivity(Intent(ctx, OnboardingActivity::class.java).putExtra(OnboardingActivity.EXTRA_FIX, true))
            }
        }
        root.addView(banner)

        // Kamera
        root.addView(ui.card().apply {
            addView(ui.horizontal().apply {
                addView(camDot)
                addView(camTitle)
            })
            addView(camDetail)
            addView(camCode)
            addView(camInfo)
        })

        // Hız ve GPS
        root.addView(ui.card().apply {
            addView(ui.horizontal().apply {
                gravity = Gravity.CENTER
                addView(gpsDot)
                addView(gpsTitle)
            })
            addView(speedValue)
            addView(speedUnit)
            addView(ui.horizontal().apply {
                setPadding(0, ui.dp(14), 0, 0)
                addView(distance.view)
                addView(altitude.view)
                addView(satellites.view)
            })
        })

        // Sensörler (açılır kart)
        root.addView(ui.card().apply {
            addView(ui.horizontal().apply {
                background = ui.ripple(null, 8)
                isClickable = true
                tag = "sensors_header"
                addView(ui.text(16f, bold = true, value = ctx.getString(R.string.sensors_title)).apply { layoutParams = ui.weight() })
                addView(sensorsArrow)
                setOnClickListener {
                    activity.prefs.sensorsExpanded = !activity.prefs.sensorsExpanded
                    render()
                }
            })
            sensorsBody.addView(ui.horizontal().apply {
                setPadding(0, ui.dp(12), 0, 0)
                addView(lean.view)
                addView(gForce.view)
                addView(maxLean.view)
            })
            sensorsBody.addView(sensorInfo)
            sensorsBody.addView(gpsInfo)
            addView(sensorsBody)
        })

        startButton = ui.button(ctx.getString(R.string.btn_start), Theme.ACCENT) {
            if (TelemetState.serviceRunning) activity.stopRide() else activity.startRide()
        }.apply { tag = "start_button" }
        recordButton = ui.button(ctx.getString(R.string.btn_record_start), Theme.RED) {
            TelemetService.toggleRecord(ctx)
        }.apply { tag = "record_button" }
        root.addView(startButton)
        root.addView(recordButton)
    }

    override fun render() {
        val s = TelemetState
        val units = Units(ctx, activity.prefs.imperial)

        banner.visibility = if (Permissions.allSet(ctx, activity.prefs)) View.GONE else View.VISIBLE

        // Kamera
        val (title, color) = when (s.linkState) {
            CameraLink.State.READY -> ctx.getString(R.string.cam_ready) to Theme.GREEN
            CameraLink.State.HANDSHAKE -> ctx.getString(R.string.cam_handshake) to Theme.YELLOW
            CameraLink.State.SCANNING -> ctx.getString(R.string.cam_scanning) to Theme.YELLOW
            CameraLink.State.CONNECTING -> ctx.getString(R.string.cam_connecting) to Theme.YELLOW
            CameraLink.State.RECONNECTING -> ctx.getString(R.string.cam_reconnecting) to Theme.YELLOW
            CameraLink.State.IDLE -> ctx.getString(R.string.cam_idle) to Theme.MUTED
        }
        camTitle.text = title
        ui.setDot(camDot, color)
        camDetail.text = s.linkDetail.ifEmpty { if (s.serviceRunning) "" else ctx.getString(R.string.press_start) }
        camDetail.visibility = if (camDetail.text.isNullOrEmpty()) View.GONE else View.VISIBLE
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
            if (name.isNotEmpty() && s.linkState == CameraLink.State.READY) add(name)
            if (st != null && s.linkState == CameraLink.State.READY) {
                add("${CameraLabels.mode(ctx, st)} • ${CameraLabels.status(ctx, st)}" + if (st.isRecording) " ●" else "")
                if (st.batteryPercent >= 0) add(ctx.getString(R.string.cam_battery, st.batteryPercent))
                if (st.isSleeping) add(ctx.getString(R.string.cam_sleeping))
                if (st.tempWarning >= 1) add(ctx.getString(R.string.cam_hot))
            }
        }
        camInfo.text = info.joinToString("\n")
        camInfo.visibility = if (info.isEmpty()) View.GONE else View.VISIBLE

        // GPS
        when {
            !s.serviceRunning -> {
                gpsTitle.text = if (GpsWarmup.ready) {
                    ctx.getString(R.string.gps_ready_idle)
                } else {
                    ctx.getString(R.string.gps_searching_idle, GpsWarmup.satellites)
                }
                ui.setDot(gpsDot, if (GpsWarmup.ready) Theme.GREEN else Theme.YELLOW)
            }
            s.hasFix -> {
                gpsTitle.text = ctx.getString(R.string.gps_fix)
                ui.setDot(gpsDot, Theme.GREEN)
            }
            s.gpsIssue == GpsDiagnosis.Issue.LOCATION_OFF || s.gpsIssue == GpsDiagnosis.Issue.NO_SATELLITES -> {
                gpsTitle.text = ctx.getString(R.string.gps_nodata)
                ui.setDot(gpsDot, Theme.RED)
            }
            else -> {
                gpsTitle.text = ctx.getString(R.string.gps_searching)
                ui.setDot(gpsDot, Theme.YELLOW)
            }
        }
        val live = s.serviceRunning && s.hasFix
        speedValue.text = if (live) units.speedNumber(s.speedKmh / 3.6) else "0"
        speedValue.setTextColor(if (live) Theme.TEXT else 0xFF555555.toInt())
        speedUnit.text = units.speedUnit
        distance.label.text = ctx.getString(R.string.label_distance) + " (" + units.distanceUnit + ")"
        altitude.label.text = ctx.getString(R.string.label_altitude) + " (" + units.altitudeUnit + ")"
        distance.value.text = if (s.serviceRunning && s.rideFile != null) units.distanceNumber(s.distanceKm * 1000) else "–"
        altitude.value.text = if (live) units.altitudeNumber(s.altitudeM) else "–"
        satellites.value.text = when {
            live -> "${s.satellitesUsed}"
            s.serviceRunning && s.satellitesVisible > 0 -> "0/${s.satellitesVisible}"
            !s.serviceRunning && GpsWarmup.satellites > 0 -> "${GpsWarmup.satellites}"
            else -> "–"
        }

        // Sensörler
        val expanded = activity.prefs.sensorsExpanded
        sensorsBody.visibility = if (expanded) View.VISIBLE else View.GONE
        sensorsArrow.text = if (expanded) "▲" else "▼"
        if (s.serviceRunning && s.hasGyro) {
            lean.value.text = leanText(s.leanDeg)
            gForce.value.text = String.format(ctx.resources.configuration.locales[0], "%.2f", s.gForce)
            maxLean.value.text = leanText(s.maxLeanDeg)
        } else {
            lean.value.text = "–"
            gForce.value.text = "–"
            maxLean.value.text = "–"
        }
        sensorInfo.text = if (s.serviceRunning) {
            ctx.getString(
                R.string.sensors_line,
                if (s.hasGyro) "✓" else "✗",
                ctx.getString(if (s.hasBarometer) R.string.baro_yes else R.string.baro_no),
            )
        } else {
            ctx.getString(R.string.sensors_idle)
        }
        gpsInfo.text = when {
            live -> ctx.getString(R.string.gps_info, s.horizontalAccuracyM, s.gpsPacketsPerSecond, s.gpsPacketsSent) +
                (s.rideFile?.let { "\n" + ctx.getString(R.string.gps_backup_file, it) } ?: "")
            s.serviceRunning -> ctx.getString(
                when (s.gpsIssue) {
                    GpsDiagnosis.Issue.LOCATION_OFF -> R.string.gps_hint_off
                    GpsDiagnosis.Issue.NO_SATELLITES -> R.string.gps_hint_nosat
                    GpsDiagnosis.Issue.NO_POSITION -> R.string.gps_hint_nopos
                    GpsDiagnosis.Issue.NONE -> R.string.gps_wait_hint
                },
            )
            else -> ""
        }
        gpsInfo.visibility = if (gpsInfo.text.isNullOrEmpty()) View.GONE else View.VISIBLE

        // Düğmeler
        startButton.text = ctx.getString(if (s.serviceRunning) R.string.btn_stop else R.string.btn_start)
        startButton.background = ui.ripple(ui.rounded(if (s.serviceRunning) 0xFF455A64.toInt() else Theme.ACCENT, 12), 12)
        val ready = s.linkState == CameraLink.State.READY
        recordButton.isEnabled = ready
        recordButton.alpha = if (ready) 1f else 0.4f
        recordButton.text = when {
            st?.isRecording == true -> ctx.getString(R.string.btn_record_stop)
            s.recordPending -> ctx.getString(R.string.btn_record_pending)
            else -> ctx.getString(R.string.btn_record_start)
        }
    }

    /** Sola yatış ◀, sağa yatış ▶ ile gösterilir. */
    private fun leanText(deg: Double): String = when {
        deg > 1 -> "◀%.0f°".format(deg)
        deg < -1 -> "%.0f°▶".format(-deg)
        else -> "0°"
    }
}
