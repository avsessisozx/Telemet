package io.github.avsessisozx.telemet.service

import android.content.Context
import android.net.Uri
import io.github.avsessisozx.telemet.fit.FitEncoder
import io.github.avsessisozx.telemet.util.AppLog
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Biten sürüşlerin listesi ("Sürüşlerim"). Her sürüş için bir özet (.json) ve ham noktalar (.fitraw2)
 * uygulamanın kendi klasöründe tutulur. Böylece İndirilenler'deki dosyalar silinse ya da taşınsa bile
 * sürüş açılabilir, rotası çizilebilir ve FIT/GPX olarak yeniden paylaşılabilir.
 */
object RideStore {

    data class Ride(
        val id: String,
        val name: String,
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
        val hasDynamics: Boolean get() = maxLeanLeftDeg + maxLeanRightDeg + maxBrakeG + maxAccelG > 0
    }

    data class Totals(val rides: Int, val distanceM: Double, val durationS: Long)

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "rides/done").apply { mkdirs() }

    fun save(context: Context, id: String, s: FitEncoder.Summary, fitUri: Uri?, gpxUri: Uri?, raw: ByteArray) {
        val json = JSONObject()
            .put("id", id)
            .put("name", "")
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
            File(dir(context), "$id.fitraw2").writeBytes(raw)
            File(dir(context), "$id.json").writeText(json.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            AppLog.w("Could not add ride to the list", e)
        }
    }

    private fun parse(j: JSONObject) = Ride(
        id = j.getString("id"),
        name = j.optString("name", ""),
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

    /** En yeni sürüş en üstte. */
    fun list(context: Context): List<Ride> {
        val files = dir(context).listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                parse(JSONObject(f.readText(Charsets.UTF_8)))
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.startUtcMillis }
    }

    fun get(context: Context, id: String): Ride? = try {
        parse(JSONObject(File(dir(context), "$id.json").readText(Charsets.UTF_8)))
    } catch (e: Exception) {
        null
    }

    fun totals(rides: List<Ride>) = Totals(rides.size, rides.sumOf { it.distanceM }, rides.sumOf { it.durationS })

    /** Sürüşün ham noktaları; eski sürümlerden kalan sürüşlerde olmayabilir. */
    fun raw(context: Context, id: String): ByteArray? {
        val f = File(dir(context), "$id.fitraw2")
        return if (f.exists()) f.readBytes() else null
    }

    fun points(context: Context, id: String): List<FitEncoder.Decoded> =
        raw(context, id)?.let { FitEncoder.decodeRecords(it) } ?: emptyList()

    fun rename(context: Context, id: String, name: String) {
        val f = File(dir(context), "$id.json")
        try {
            val j = JSONObject(f.readText(Charsets.UTF_8))
            j.put("name", name.trim().take(60))
            f.writeText(j.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            AppLog.w("Could not rename ride", e)
        }
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
        File(dir(context), "${ride.id}.fitraw2").delete()
        AppLog.i("Ride deleted: ${ride.id}")
    }

    /** Ham noktalardan GPX metni üretir. */
    fun gpx(name: String, points: List<FitEncoder.Decoded>): String {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val safe = name.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<gpx version=\"1.1\" creator=\"Telemet\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
            append("<trk><name>").append(safe).append("</name><type>motorcycling</type><trkseg>\n")
            for (p in points) {
                append(
                    String.format(
                        Locale.US, "<trkpt lat=\"%.7f\" lon=\"%.7f\"><ele>%.1f</ele><time>%s</time></trkpt>\n",
                        FitEncoder.degrees(p.latSemi), FitEncoder.degrees(p.lonSemi), p.enhancedAltitudeM,
                        iso.format(Date((p.fitTime + FitEncoder.FIT_EPOCH_S) * 1000)),
                    ),
                )
            }
            append("</trkseg></trk>\n</gpx>\n")
        }
    }
}
