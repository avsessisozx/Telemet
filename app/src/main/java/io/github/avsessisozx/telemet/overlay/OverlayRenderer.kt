package io.github.avsessisozx.telemet.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import io.github.avsessisozx.telemet.util.Units
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Gösterge videosunun tek karesini çizer: yeşil zemin üzerinde hız, yatış göstergesi, G, yükseklik
 * ve küçük rota haritası. Yeşil zemin video düzenleyicide "renk anahtarı / chroma key" ile silinir.
 *
 * Yarı saydam hiçbir şey çizilmez: yeşil zeminle karışan yarı saydam renkler anahtarlamada lekelenir.
 */
class OverlayRenderer(
    private val width: Int,
    private val height: Int,
    private val units: Units,
    private val data: OverlayData,
    /** Haritada gösterilecek rota (video aralığı). */
    routeFromMs: Long,
    routeToMs: Long,
) {
    companion object {
        const val CHROMA_GREEN = 0xFF00FF00.toInt()
        private const val ACCENT = 0xFFFF7A1A.toInt()
        private const val PANEL = 0xFF1E1E1E.toInt()
        private const val TRACK = 0xFF8A8A8A.toInt()
        /** Göstergenin iki yana en fazla gösterdiği yatış. */
        private const val LEAN_RANGE = 60.0
    }

    private val s = min(width, height) / 1080f
    private val margin = 48 * s

    private fun textPaint(size: Float, color: Int = Color.WHITE, align: Paint.Align = Paint.Align.LEFT) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size * s
            this.color = color
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = align
        }

    private fun outline(p: Paint) = Paint(p).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8 * s
        color = Color.BLACK
        strokeJoin = Paint.Join.ROUND
    }

    private val speedPaint = textPaint(170f)
    private val speedOutline = outline(speedPaint)
    private val unitPaint = textPaint(44f)
    private val unitOutline = outline(unitPaint)
    private val valuePaint = textPaint(52f, align = Paint.Align.CENTER)
    private val valueOutline = outline(valuePaint)
    private val smallPaint = textPaint(34f, align = Paint.Align.CENTER)
    private val smallOutline = outline(smallPaint)
    private val rightPaint = textPaint(44f, align = Paint.Align.RIGHT)
    private val rightOutline = outline(rightPaint)

    private val arcBack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 26 * s; color = PANEL; strokeCap = Paint.Cap.ROUND
    }
    private val arcFill = Paint(arcBack).apply { color = ACCENT; strokeWidth = 18 * s }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 5 * s; strokeCap = Paint.Cap.ROUND }
    private val maxTick = Paint(tick).apply { color = Color.RED; strokeWidth = 7 * s }
    private val panel = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PANEL }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 6 * s; color = TRACK; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val donePaint = Paint(routePaint).apply { color = ACCENT }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    // Harita: rota noktaları kutuya sığdırılmış hâlde önceden hesaplanır.
    private val mapSize = 320 * s
    private val mapRect = RectF(width - margin - mapSize, height - margin - mapSize, width - margin, height - margin)
    private val mapX: FloatArray
    private val mapY: FloatArray
    private val mapT: LongArray
    private val project: (Double, Double) -> Pair<Float, Float>

    init {
        val idx = data.times.indices.filter { data.times[it] * 1000 in (routeFromMs - 1000)..(routeToMs + 1000) }
        val lats = idx.map { data.lat[it] }
        val lons = idx.map { data.lon[it] }
        mapT = LongArray(idx.size) { data.times[idx[it]] }
        if (idx.isEmpty()) {
            mapX = FloatArray(0); mapY = FloatArray(0)
            project = { _, _ -> mapRect.centerX() to mapRect.centerY() }
        } else {
            val minLat = lats.min(); val maxLat = lats.max()
            val minLon = lons.min(); val maxLon = lons.max()
            val k = cos(Math.toRadians((minLat + maxLat) / 2))
            val spanX = max((maxLon - minLon) * k, 1e-6)
            val spanY = max(maxLat - minLat, 1e-6)
            val inner = mapSize - 40 * s
            val scale = inner / max(spanX, spanY)
            val ox = mapRect.left + (mapSize - spanX * scale).toFloat() / 2
            val oy = mapRect.top + (mapSize - spanY * scale).toFloat() / 2
            project = { la, lo ->
                (ox + ((lo - minLon) * k * scale).toFloat()) to (oy + ((maxLat - la) * scale).toFloat())
            }
            mapX = FloatArray(idx.size) { project(lats[it], lons[it]).first }
            mapY = FloatArray(idx.size) { project(lats[it], lons[it]).second }
        }
    }

    private val routeAll = Path().apply {
        for (i in mapX.indices) if (i == 0) moveTo(mapX[i], mapY[i]) else lineTo(mapX[i], mapY[i])
    }

    private fun outlinedText(c: Canvas, text: String, x: Float, y: Float, fill: Paint, line: Paint) {
        c.drawText(text, x, y, line)
        c.drawText(text, x, y, fill)
    }

    /** [tMs] anının karesini çizer (Unix ms). [fromMs] videonun başı (en çok yatış "şimdiye kadar" için). */
    fun draw(c: Canvas, tMs: Long, fromMs: Long) {
        c.drawColor(CHROMA_GREEN)
        val smp = data.at(tMs) ?: return
        val loc = Locale.getDefault()

        // Hız (sol alt)
        val speedText = String.format(loc, "%.0f", units.speedValue(smp.speedMs))
        val baseY = height - margin - 56 * s
        outlinedText(c, speedText, margin, baseY, speedPaint, speedOutline)
        outlinedText(c, units.speedUnit, margin + 8 * s, height - margin, unitPaint, unitOutline)

        // Yatış göstergesi (hızın sağı)
        if (data.hasDynamics) {
            val cx = margin + 560 * s
            val cy = height - margin - 70 * s
            val r = 170 * s
            val oval = RectF(cx - r, cy - r, cx + r, cy + r)
            // Üst yarım daire: 180°(sol) … 360°(sağ). 0 yatış = tepe (270°).
            val sweepHalf = 90f
            c.drawArc(oval, 270f - sweepHalf, 2 * sweepHalf, false, arcBack)
            val lean = smp.leanDeg.coerceIn(-LEAN_RANGE, LEAN_RANGE)
            // Sola yatış (pozitif) göstergede sola doğru dolar.
            val sweep = (-lean / LEAN_RANGE * sweepHalf).toFloat()
            if (abs(sweep) > 0.5f) c.drawArc(oval, 270f, sweep, false, arcFill)
            for (d in listOf(-60, -30, 0, 30, 60)) {
                val a = Math.toRadians(270.0 - d / LEAN_RANGE * sweepHalf)
                val x1 = cx + cos(a).toFloat() * (r + 22 * s); val y1 = cy + sin(a).toFloat() * (r + 22 * s)
                val x2 = cx + cos(a).toFloat() * (r + 36 * s); val y2 = cy + sin(a).toFloat() * (r + 36 * s)
                c.drawLine(x1, y1, x2, y2, tick)
            }
            val (maxL, maxR) = data.maxLean(fromMs, tMs)
            for (m in listOf(maxL, -maxR)) {
                if (abs(m) < 1) continue
                val a = Math.toRadians(270.0 - m.coerceIn(-LEAN_RANGE, LEAN_RANGE) / LEAN_RANGE * sweepHalf)
                c.drawLine(
                    cx + cos(a).toFloat() * (r - 18 * s), cy + sin(a).toFloat() * (r - 18 * s),
                    cx + cos(a).toFloat() * (r + 18 * s), cy + sin(a).toFloat() * (r + 18 * s), maxTick,
                )
            }
            outlinedText(c, String.format(loc, "%.0f°", abs(smp.leanDeg)), cx, cy - 40 * s, valuePaint, valueOutline)
            outlinedText(c, String.format(loc, "%.2f g", smp.gForce), cx, cy + 20 * s, smallPaint, smallOutline)
            outlinedText(c, String.format(loc, "◀ %.0f°   %.0f° ▶", maxL, maxR), cx, cy + 66 * s, smallPaint, smallOutline)
        }

        // Rota haritası (sağ alt)
        if (mapX.size >= 2) {
            c.drawRoundRect(mapRect, 24 * s, 24 * s, panel)
            c.drawPath(routeAll, routePaint)
            val done = Path()
            var started = false
            for (i in mapX.indices) {
                if (mapT[i] * 1000 > tMs) break
                if (!started) { done.moveTo(mapX[i], mapY[i]); started = true } else done.lineTo(mapX[i], mapY[i])
            }
            val (px, py) = project(smp.lat, smp.lon)
            if (started) { done.lineTo(px, py); c.drawPath(done, donePaint) }
            c.drawCircle(px, py, 13 * s, dotRing)
            c.drawCircle(px, py, 9 * s, dotPaint)
        }

        // Yükseklik ve mesafe (haritanın üstü)
        val textRight = width - margin
        val above = if (mapX.size >= 2) mapRect.top - 20 * s else height - margin
        outlinedText(c, units.altitude(smp.altitudeM), textRight, above, rightPaint, rightOutline)
        outlinedText(c, units.distance(smp.distanceM), textRight, above - 56 * s, rightPaint, rightOutline)
    }
}
