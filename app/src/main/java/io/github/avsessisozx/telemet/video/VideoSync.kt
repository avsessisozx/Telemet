package io.github.avsessisozx.telemet.video

import io.github.avsessisozx.telemet.fit.FitEncoder
import kotlin.math.max
import kotlin.math.min

/**
 * Kameranın böldüğü video parçalarını sürüş kaydıyla saate göre eşleştirir.
 *
 * Kamera her videoya başlangıç zamanı yazar, ama bunun UTC mi yerel saat mi olduğu kameraya ve
 * ayarına göre değişir. Ayrıca DJI dosya adında (DJI_20261016143022_0001_D.MP4) yerel saat bulunur.
 * Üç yorum denenir ve sürüş verisiyle en çok örtüşen seçilir; gerekirse kullanıcı saniye kaydırır.
 */
object VideoSync {

    data class Clip(
        val name: String,
        /** mvhd oluşturma zamanı (Unix saniyesi, saat dilimi belirsiz). */
        val creationS: Long?,
        val durationMs: Long,
    )

    enum class TimeSource { FILE_UTC, FILE_LOCAL, NAME_LOCAL }

    data class Placed(val clip: Clip, val startMs: Long, val endMs: Long, val coveredMs: Long) {
        val coverage: Double get() = if (endMs > startMs) coveredMs.toDouble() / (endMs - startMs) else 0.0
    }

    data class Result(val source: TimeSource?, val clips: List<Placed>) {
        val anyMatch: Boolean get() = clips.any { it.coveredMs > 0 }
    }

    private val NAME_TIME = Regex("""(?:^|[^0-9])(20\d{2})(\d{2})(\d{2})[_-]?(\d{2})(\d{2})(\d{2})(?:[^0-9]|$)""")

    /** Dosya adındaki tarih-saat, saat dilimi uygulanmamış (yerel saat UTC'ymiş gibi) Unix saniyesi. */
    fun nameTimeS(name: String): Long? {
        val m = NAME_TIME.find(name) ?: return null
        val (y, mo, d, h, mi, s) = m.destructured
        val month = mo.toInt()
        val day = d.toInt()
        if (month !in 1..12 || day !in 1..31 || h.toInt() > 23 || mi.toInt() > 59 || s.toInt() > 59) return null
        return daysFromCivil(y.toInt(), month, day) * 86_400L + h.toInt() * 3600 + mi.toInt() * 60 + s.toInt()
    }

    /** Takvim tarihinden 1970'ten bu yana gün (Howard Hinnant'ın algoritması; saat dilimsiz). */
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468
    }

    /**
     * @param rides her sürüşün kayıtlı olduğu saniyeler (sıralı, Unix saniyesi)
     * @param tzOffsetMs yerel saatin UTC'den farkı (o andaki), ms
     * @param manualOffsetS kullanıcının eklediği düzeltme, saniye
     */
    fun place(
        clips: List<Clip>,
        rides: List<LongArray>,
        tzOffsetMs: (Long) -> Int,
        manualOffsetS: Int = 0,
    ): Result {
        val ranges = rides.filter { it.isNotEmpty() }.map { it.first() * 1000 to (it.last() + 1) * 1000 }
        var best: Result? = null
        var bestCovered = -1L
        for (source in TimeSource.values()) {
            val placed = clips.map { c ->
                val start = startMs(c, source, tzOffsetMs)
                if (start == null) {
                    Placed(c, 0, 0, 0)
                } else {
                    val s = start + manualOffsetS * 1000L
                    val e = s + c.durationMs
                    Placed(c, s, e, covered(s, e, rides, ranges))
                }
            }
            if (placed.all { it.endMs == 0L }) continue
            val total = placed.sumOf { it.coveredMs }
            if (total > bestCovered) {
                bestCovered = total
                best = Result(source, placed)
            }
        }
        return best ?: Result(null, clips.map { Placed(it, 0, 0, 0) })
    }

    private fun startMs(c: Clip, source: TimeSource, tzOffsetMs: (Long) -> Int): Long? = when (source) {
        TimeSource.FILE_UTC -> c.creationS?.let { it * 1000 }
        TimeSource.FILE_LOCAL -> c.creationS?.let { it * 1000 - tzOffsetMs(it * 1000) }
        TimeSource.NAME_LOCAL -> nameTimeS(c.name)?.let { it * 1000 - tzOffsetMs(it * 1000) }
    }

    /** Videonun kaç ms'sinde sürüş verisi var (saniyelik noktalara göre). */
    private fun covered(s: Long, e: Long, rides: List<LongArray>, ranges: List<Pair<Long, Long>>): Long {
        var total = 0L
        rides.forEachIndexed { i, secs ->
            if (secs.isEmpty()) return@forEachIndexed
            val (rs, re) = ranges[i]
            if (re <= s || rs >= e) return@forEachIndexed
            // Sürüş içinde kesinti (boşluk) olabilir; yalnızca noktası olan saniyeleri say.
            val from = Math.floorDiv(s, 1000L)
            val to = Math.floorDiv(e - 1, 1000L)
            val lo = lowerBound(secs, from)
            val hi = lowerBound(secs, to + 1)
            total += (hi - lo) * 1000L
        }
        return min(total, e - s)
    }

    private fun lowerBound(a: LongArray, v: Long): Int {
        var lo = 0
        var hi = a.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (a[mid] < v) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Ham sürüş kayıtlarından [startMs, endMs) aralığına düşen noktaları keser.
     * Video ilk/son noktanın biraz dışında başlarsa da kenardaki saniye dahil edilir.
     */
    fun slice(raw: ByteArray, startMs: Long, endMs: Long): ByteArray {
        val size = FitEncoder.RECORD_SIZE
        val records = FitEncoder.decodeRecords(raw)
        val from = Math.floorDiv(startMs, 1000L)
        val to = Math.floorDiv(max(endMs - 1, startMs), 1000L)
        val out = java.io.ByteArrayOutputStream()
        records.forEachIndexed { i, r ->
            val sec = r.fitTime + FitEncoder.FIT_EPOCH_S
            if (sec in from..to) out.write(raw, i * size, size)
        }
        return out.toByteArray()
    }

    /** Birden çok sürüşün kesitlerini zamana göre sıralı tek ham diziye birleştirir. */
    fun concat(parts: List<ByteArray>): ByteArray {
        val size = FitEncoder.RECORD_SIZE
        val recs = parts.flatMap { p ->
            val d = FitEncoder.decodeRecords(p)
            d.indices.map { i -> d[i].fitTime to p.copyOfRange(i * size, (i + 1) * size) }
        }.sortedBy { it.first }
        val out = java.io.ByteArrayOutputStream()
        var last = Long.MIN_VALUE
        for ((t, bytes) in recs) {
            if (t == last) continue // aynı saniye iki kez yazılmasın
            last = t
            out.write(bytes)
        }
        return out.toByteArray()
    }
}
