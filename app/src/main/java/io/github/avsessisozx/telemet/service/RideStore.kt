package io.github.avsessisozx.telemet.service

import android.content.Context
import android.net.Uri
import io.github.avsessisozx.telemet.fit.FitEncoder
import io.github.avsessisozx.telemet.util.AppLog
import org.json.JSONObject
import java.io.File

/**
 * Biten sürüşlerin listesi ("Sürüşlerim"). Her sürüş için küçük bir özet dosyası tutulur;
 * FIT ve GPX dosyalarının kendisi İndirilenler/Telemet klasöründedir.
 */
object RideStore {

    data class Ride(
        val id: String,
        val startUtcMillis: Long,
        val endUtcMillis: Long,
        val distanceM: Double,
        val maxSpeedMs: Double,
        val ascentM: Double,
        val points: Int,
        val maxLeanLeftDeg: Double,
        val maxLeanRightDeg: Double,
        val maxLateralG: Double,
        val maxBrakeG: Double,
        val maxAccelG: Double,
        val fitUri: Uri?,
        val gpxUri: Uri?,
    ) {
        val durationS: Long get() = (endUtcMillis - startUtcMillis) / 1000
        val avgSpeedMs: Double get() = if (durationS > 0) distanceM / durationS else 0.0
    }

    private fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "rides/done").apply { mkdirs() }

    fun save(context: Context, id: String, s: FitEncoder.Summary, fitUri: Uri?, gpxUri: Uri?) {
        val json = JSONObject()
            .put("id", id)
            .put("start", s.startUtcMillis)
            .put("end", s.endUtcMillis)
            .put("distance", s.distanceM)
            .put("maxSpeed", s.maxSpeedMs)
            .put("ascent", s.ascentM)
            .put("points", s.points)
            .put("leanLeft", s.maxLeanLeftDeg)
            .put("leanRight", s.maxLeanRightDeg)
            .put("latG", s.maxLateralG)
            .put("brakeG", s.maxBrakeG)
            .put("accelG", s.maxAccelG)
            .put("fit", fitUri?.toString() ?: "")
            .put("gpx", gpxUri?.toString() ?: "")
        try {
            File(dir(context), "$id.json").writeText(json.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            AppLog.w("Could not add ride to the list", e)
        }
    }

    /** En yeni sürüş en üstte. */
    fun list(context: Context): List<Ride> {
        val files = dir(context).listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val j = JSONObject(f.readText(Charsets.UTF_8))
                Ride(
                    id = j.getString("id"),
                    startUtcMillis = j.getLong("start"),
                    endUtcMillis = j.getLong("end"),
                    distanceM = j.optDouble("distance", 0.0),
                    maxSpeedMs = j.optDouble("maxSpeed", 0.0),
                    ascentM = j.optDouble("ascent", 0.0),
                    points = j.optInt("points", 0),
                    maxLeanLeftDeg = j.optDouble("leanLeft", 0.0),
                    maxLeanRightDeg = j.optDouble("leanRight", 0.0),
                    maxLateralG = j.optDouble("latG", 0.0),
                    maxBrakeG = j.optDouble("brakeG", 0.0),
                    maxAccelG = j.optDouble("accelG", 0.0),
                    fitUri = j.optString("fit").takeIf { it.isNotEmpty() }?.let(Uri::parse),
                    gpxUri = j.optString("gpx").takeIf { it.isNotEmpty() }?.let(Uri::parse),
                )
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.startUtcMillis }
    }

    /** Sürüşü listeden ve telefondaki dosyalarıyla birlikte siler. */
    fun delete(context: Context, ride: Ride) {
        for (uri in listOfNotNull(ride.fitUri, ride.gpxUri)) {
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (e: Exception) {
                AppLog.w("Could not delete file: $uri", e)
            }
        }
        File(dir(context), "${ride.id}.json").delete()
        AppLog.i("Ride deleted: ${ride.id}")
    }
}
