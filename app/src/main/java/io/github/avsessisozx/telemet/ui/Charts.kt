package io.github.avsessisozx.telemet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Rotanın kuşbakışı çizimi (harita ve internet gerekmez). Çizgi hıza göre renklenir:
 * yavaş yeşil, orta sarı, hızlı turuncu-kırmızı. Başlangıç yeşil, bitiş kırmızı nokta.
 */
class RouteView(context: Context, private val lat: DoubleArray, private val lon: DoubleArray, private val speed: DoubleArray) : View(context) {

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 5f * resources.displayMetrics.density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint().apply { color = Theme.SURFACE }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.75f).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val r = 12f * resources.displayMetrics.density
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, bg)
        if (lat.size < 2) return
        // Enlem/boylamı düz bir düzleme çevir (kısa mesafelerde yeterince doğru).
        val midLat = Math.toRadians((lat.min() + lat.max()) / 2)
        val xs = DoubleArray(lon.size) { lon[it] * cos(midLat) }
        val ys = DoubleArray(lat.size) { -lat[it] }
        val minX = xs.min()
        val maxX = xs.max()
        val minY = ys.min()
        val maxY = ys.max()
        val pad = 20f * resources.displayMetrics.density
        val spanX = max(maxX - minX, 1e-9)
        val spanY = max(maxY - minY, 1e-9)
        val scale = min((width - 2 * pad) / spanX, (height - 2 * pad) / spanY)
        val offX = (width - spanX * scale) / 2
        val offY = (height - spanY * scale) / 2
        fun px(i: Int) = (offX + (xs[i] - minX) * scale).toFloat()
        fun py(i: Int) = (offY + (ys[i] - minY) * scale).toFloat()

        val maxSpeed = max(speed.maxOrNull() ?: 0.0, 1.0)
        // Her parçayı hız rengiyle çiz; çok nokta varsa seyrelt.
        val step = max(1, lat.size / 1500)
        var prev = 0
        var i = step
        while (i < lat.size) {
            line.color = speedColor(speed[i] / maxSpeed)
            canvas.drawLine(px(prev), py(prev), px(i), py(i), line)
            prev = i
            i += step
        }
        val d = 7f * resources.displayMetrics.density
        dot.color = Theme.GREEN
        canvas.drawCircle(px(0), py(0), d, dot)
        dot.color = Theme.RED
        canvas.drawCircle(px(lat.size - 1), py(lat.size - 1), d, dot)
    }

    companion object {
        fun speedColor(f: Double): Int {
            val t = f.coerceIn(0.0, 1.0)
            return if (t < 0.5) {
                blend(0xFF4CAF50.toInt(), 0xFFFFC107.toInt(), t * 2)
            } else {
                blend(0xFFFFC107.toInt(), 0xFFFF5722.toInt(), (t - 0.5) * 2)
            }
        }

        private fun blend(a: Int, b: Int, t: Double): Int = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt(),
        )
    }
}

/** Zamana göre basit çizgi grafiği (hız, yükseklik, yatış). Altında en düşük/en yüksek değer yazar. */
class LineChartView(
    context: Context,
    private val values: DoubleArray,
    private val color: Int,
    private val symmetric: Boolean = false,
    private val label: (Double) -> String,
) : View(context) {

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * resources.displayMetrics.density
        strokeJoin = Paint.Join.ROUND
        this.color = this@LineChartView.color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = (this@LineChartView.color and 0x00FFFFFF) or 0x33000000
    }
    private val grid = Paint().apply { this.color = 0x22FFFFFF; strokeWidth = 1f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = Theme.MUTED
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val bg = Paint().apply { this.color = Theme.SURFACE }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (140 * resources.displayMetrics.density).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val dens = resources.displayMetrics.density
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 12 * dens, 12 * dens, bg)
        if (values.size < 2) return
        var lo = values.min()
        var hi = values.max()
        if (symmetric) {
            val m = max(kotlin.math.abs(lo), kotlin.math.abs(hi))
            lo = -m
            hi = m
        }
        if (hi - lo < 1e-6) hi = lo + 1
        val left = 12 * dens
        val right = width - 12 * dens
        val top = 22 * dens
        val bottom = height - 12 * dens
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
        fun x(i: Int) = (left + i.toDouble() / (values.size - 1) * (right - left)).toFloat()

        if (symmetric) canvas.drawLine(left, y(0.0), right, y(0.0), grid)
        val step = max(1, values.size / 800)
        val path = Path()
        path.moveTo(x(0), y(values[0]))
        var i = step
        while (i < values.size) {
            path.lineTo(x(i), y(values[i]))
            i += step
        }
        path.lineTo(x(values.size - 1), y(values.last()))
        if (!symmetric) {
            val area = Path(path)
            area.lineTo(x(values.size - 1), bottom)
            area.lineTo(x(0), bottom)
            area.close()
            canvas.drawPath(area, fill)
        }
        canvas.drawPath(path, line)
        val maxText = label(if (symmetric) hi else values.max())
        val minText = label(if (symmetric) lo else values.min())
        canvas.drawText("▲ $maxText", left, 15 * dens, text)
        val w = text.measureText("▼ $minText")
        canvas.drawText("▼ $minText", right - w, 15 * dens, text)
    }
}
