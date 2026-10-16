package io.github.avsessisozx.telemet.util

import android.content.Context
import io.github.avsessisozx.telemet.R
import java.util.Locale

/** Ekranda gösterilecek değerleri seçili birimle (metrik / emperyal) biçimlendirir. */
class Units(private val ctx: Context, val imperial: Boolean) {

    private val locale: Locale = ctx.resources.configuration.locales[0]

    val speedUnit: String get() = ctx.getString(if (imperial) R.string.unit_mph else R.string.unit_kmh)
    val distanceUnit: String get() = ctx.getString(if (imperial) R.string.unit_mi else R.string.unit_km)
    val altitudeUnit: String get() = ctx.getString(if (imperial) R.string.unit_ft else R.string.unit_m)

    fun speedValue(ms: Double): Double = if (imperial) ms * 2.236936 else ms * 3.6
    fun distanceValue(m: Double): Double = if (imperial) m / 1609.344 else m / 1000.0
    fun altitudeValue(m: Double): Double = if (imperial) m * 3.28084 else m

    fun speedNumber(ms: Double): String = String.format(locale, "%.0f", speedValue(ms))
    fun distanceNumber(m: Double): String = String.format(locale, "%.1f", distanceValue(m))
    fun altitudeNumber(m: Double): String = String.format(locale, "%.0f", altitudeValue(m))

    fun speed(ms: Double): String = "${speedNumber(ms)} $speedUnit"
    fun distance(m: Double): String = "${distanceNumber(m)} $distanceUnit"
    fun altitude(m: Double): String = "${altitudeNumber(m)} $altitudeUnit"

    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h > 0 -> ctx.getString(R.string.dur_h, h.toInt(), m.toInt())
            m > 0 -> ctx.getString(R.string.dur_m, m.toInt())
            else -> ctx.getString(R.string.dur_s, seconds.toInt())
        }
    }

    fun g(v: Double): String = String.format(locale, "%.2f g", v)
    fun degrees(v: Double): String = String.format(locale, "%.0f°", v)

    companion object {
        fun of(ctx: Context) = Units(ctx, Prefs(ctx).imperial)
    }
}
