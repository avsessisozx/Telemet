package io.github.avsessisozx.telemet.ui

import android.content.Context
import io.github.avsessisozx.telemet.R
import io.github.avsessisozx.telemet.protocol.CameraStatus

/** Kamera modu ve durumunun ekranda gösterilen, çevrilmiş adları. */
object CameraLabels {

    fun mode(ctx: Context, s: CameraStatus): String = when (s.mode) {
        0x00 -> ctx.getString(R.string.mode_slowmo)
        0x01 -> ctx.getString(R.string.mode_video)
        0x02 -> ctx.getString(R.string.mode_timelapse)
        0x05 -> ctx.getString(R.string.mode_photo)
        0x0A -> ctx.getString(R.string.mode_hyperlapse)
        0x1A -> ctx.getString(R.string.mode_live)
        0x23 -> ctx.getString(R.string.mode_uvc)
        0x28 -> ctx.getString(R.string.mode_night)
        0x34 -> ctx.getString(R.string.mode_tracking)
        else -> ctx.getString(R.string.mode_unknown, "0x%02X".format(s.mode))
    }

    fun status(ctx: Context, s: CameraStatus): String = when (s.status) {
        0x00 -> ctx.getString(R.string.status_screen_off)
        0x01 -> ctx.getString(R.string.status_ready)
        0x02 -> ctx.getString(R.string.status_playback)
        CameraStatus.STATUS_RECORDING -> ctx.getString(R.string.status_recording)
        CameraStatus.STATUS_PRE_RECORDING -> ctx.getString(R.string.status_prerec)
        else -> ctx.getString(R.string.status_unknown, "0x%02X".format(s.status))
    }
}
