package io.github.avsessisozx.telemet.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Uygulamanın renkleri. Koyu tema: güneşte ve gece sürüşte okunaklı. */
object Theme {
    const val BG = 0xFF121212.toInt()
    const val SURFACE = 0xFF1E1E1E.toInt()
    const val SURFACE_2 = 0xFF2A2A2A.toInt()
    const val TEXT = 0xFFEDEDED.toInt()
    const val MUTED = 0xFF9E9E9E.toInt()
    const val ACCENT = 0xFFFF7A1A.toInt()
    const val GREEN = 0xFF4CAF50.toInt()
    const val RED = 0xFFE53935.toInt()
    const val DARK_RED = 0xFFB71C1C.toInt()
    const val YELLOW = 0xFFFFC107.toInt()
    const val GREY_BUTTON = 0xFF37474F.toInt()
    const val WARNING_BG = 0xFF3A2A12.toInt()
    const val RIPPLE = 0x33FFFFFF
}

/**
 * Ekranları kodla kurmak için ortak parçalar (ek kütüphane yok).
 * Tüm ekranlar aynı görünsün diye boşluklar, köşeler ve yazı boyları burada tanımlı.
 */
class Ui(val ctx: Context) {

    fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
    fun dpf(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    fun text(size: Float, color: Int = Theme.TEXT, bold: Boolean = false, value: CharSequence? = null): TextView =
        TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            if (value != null) text = value
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }

    fun rounded(color: Int, radiusDp: Int = 14): GradientDrawable =
        GradientDrawable().apply { cornerRadius = dpf(radiusDp.toFloat()); setColor(color) }

    /** Dokunulduğunda dalga efekti veren arka plan. */
    fun ripple(base: Drawable?, radiusDp: Int = 14): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(Theme.RIPPLE), base, rounded(Color.WHITE, radiusDp))

    fun vertical(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun horizontal(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun matchWrap(bottomMarginDp: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(bottomMarginDp) }

    fun weight(w: Float = 1f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, w)

    fun card(bottomMarginDp: Int = 12, color: Int = Theme.SURFACE): LinearLayout = vertical().apply {
        background = rounded(color)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = matchWrap(bottomMarginDp)
    }

    /** Tam genişlikte büyük düğme. */
    fun button(label: String, color: Int, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.DEFAULT_BOLD
        background = ripple(rounded(color, 12), 12)
        minHeight = dp(54)
        stateListAnimator = null
        setOnClickListener { onClick() }
        layoutParams = matchWrap(10)
    }

    /** Arka planı olmayan, yazı rengiyle vurgulanan düğme ("Atla" gibi). */
    fun textButton(label: String, color: Int = Theme.MUTED, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        background = ripple(null, 12)
        stateListAnimator = null
        minHeight = dp(48)
        setOnClickListener { onClick() }
        layoutParams = matchWrap(4)
    }

    fun smallButton(label: String, color: Int, onClick: () -> Unit): Button = Button(ctx).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        background = ripple(rounded(color, 10), 10)
        stateListAnimator = null
        minHeight = dp(42)
        minimumHeight = dp(42)
        setPadding(dp(16), 0, dp(16), 0)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginEnd = dp(8) }
    }

    fun dot(color: Int = Theme.MUTED, sizeDp: Int = 12): View = View(ctx).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { marginEnd = dp(10) }
    }

    fun setDot(v: View, color: Int) {
        (v.background as GradientDrawable).setColor(color)
    }

    fun sectionHeader(label: String): TextView = text(13f, Theme.ACCENT, bold = true, value = label).apply {
        setPadding(dp(4), dp(18), dp(4), dp(8))
    }

    /**
     * Ayarlar listesi satırı: başlık, isteğe bağlı açıklama ve sağda isteğe bağlı değer.
     * [onClick] verilirse dokunulabilir olur.
     */
    class Row(val view: LinearLayout, val title: TextView, val subtitle: TextView, val value: TextView)

    fun row(title: String, subtitle: String? = null, value: String? = null, titleColor: Int = Theme.TEXT, onClick: (() -> Unit)? = null): Row {
        val t = text(16f, titleColor, value = title)
        val s = text(13f, Theme.MUTED, value = subtitle).apply {
            visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
            setPadding(0, dp(2), 0, 0)
        }
        val v = text(14f, Theme.MUTED, value = value).apply {
            visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.END
            setPadding(dp(12), 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
        }
        val left = vertical().apply {
            layoutParams = weight()
            addView(t)
            addView(s)
        }
        val container = horizontal().apply {
            minimumHeight = dp(56)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            addView(left)
            addView(v)
            if (onClick != null) {
                background = ripple(null, 0)
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick() }
            }
        }
        return Row(container, t, s, v)
    }

    fun switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout {
        val sw = Switch(ctx).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Theme.ACCENT, 0xFFBDBDBD.toInt()),
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(0x88FF7A1A.toInt(), 0x55FFFFFF),
            )
            setOnCheckedChangeListener { _, v -> onChange(v) }
            // Satırın kendisi dokunmayı karşılar; anahtar ayrıca odak almasın.
            isClickable = false
            isFocusable = false
        }
        val r = row(title, subtitle) { sw.isChecked = !sw.isChecked }
        r.view.addView(sw)
        return r.view
    }

    /** Ayarlar bölümü: yuvarlak köşeli kart, satırlar arasında ince çizgi. */
    fun group(vararg rows: View): LinearLayout = vertical().apply {
        background = rounded(Theme.SURFACE)
        clipToOutline = true
        layoutParams = matchWrap(4)
        rows.forEachIndexed { i, r ->
            if (i > 0) addView(divider())
            addView(r)
        }
    }

    fun divider(): View = View(ctx).apply {
        setBackgroundColor(0x1FFFFFFF)
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1).apply { marginStart = dp(16) }
    }

    /** Büyük değer + küçük etiket (hız, mesafe gibi). */
    class Metric(val view: LinearLayout, val value: TextView, val label: TextView)

    fun metric(label: String, valueSize: Float = 24f): Metric {
        val v = text(valueSize, Theme.TEXT, bold = true, value = "–").apply { gravity = Gravity.CENTER_HORIZONTAL }
        val l = text(12f, Theme.MUTED, value = label).apply { gravity = Gravity.CENTER_HORIZONTAL }
        val box = vertical().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = weight()
            addView(v)
            addView(l)
        }
        return Metric(box, v, l)
    }

    /** Başlık + değer kutucuğu (sürüş ayrıntısındaki istatistikler). */
    fun tile(label: String, value: String): LinearLayout = vertical().apply {
        background = rounded(Theme.SURFACE, 12)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(text(12f, Theme.MUTED, value = label))
        addView(text(20f, Theme.TEXT, bold = true, value = value).apply { setPadding(0, dp(2), 0, 0) })
    }

    /** Kutucukları ikişerli satırlara dizer. */
    fun tileGrid(tiles: List<View>): LinearLayout = vertical().apply {
        layoutParams = matchWrap(12)
        tiles.chunked(2).forEachIndexed { i, pair ->
            addView(horizontal().apply {
                layoutParams = matchWrap(if (i < (tiles.size - 1) / 2) 8 else 0)
                pair.forEachIndexed { j, t ->
                    t.layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { if (j == 0) marginEnd = dp(8) }
                    addView(t)
                }
                if (pair.size == 1) addView(View(ctx), LinearLayout.LayoutParams(0, 0, 1f))
            })
        }
    }

    fun icon(resId: Int, tint: Int, sizeDp: Int = 24): ImageView = ImageView(ctx).apply {
        setImageResource(resId)
        imageTintList = ColorStateList.valueOf(tint)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Kaydırılabilir sayfa; içerik sistem çubuklarının altında kalmaz. */
    fun scrollPage(content: LinearLayout, applyTopInset: Boolean = true, applyBottomInset: Boolean = true): ScrollView {
        content.setPadding(dp(16), dp(16), dp(16), dp(24))
        return ScrollView(ctx).apply {
            setBackgroundColor(Theme.BG)
            isFillViewport = true
            clipToPadding = false
            addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            setOnApplyWindowInsetsListener { _, insets ->
                val (top, bottom) = systemBars(insets)
                content.setPadding(dp(16), dp(16) + (if (applyTopInset) top else 0), dp(16), dp(24) + (if (applyBottomInset) bottom else 0))
                insets
            }
        }
    }

    /** Sayfa başlığı; [onBack] verilirse solda geri düğmesi olur. */
    fun toolbar(title: String, onBack: (() -> Unit)?): LinearLayout = horizontal().apply {
        layoutParams = matchWrap(8)
        if (onBack != null) {
            addView(TextView(ctx).apply {
                text = if (ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) "→" else "←"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                setTextColor(Theme.TEXT)
                gravity = Gravity.CENTER
                background = ripple(null, 24)
                contentDescription = ctx.getString(android.R.string.cancel)
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) }
                setOnClickListener { onBack() }
            })
        }
        addView(text(24f, Theme.TEXT, bold = true, value = title).apply {
            layoutParams = weight()
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
    }

    companion object {
        fun systemBars(insets: WindowInsets): Pair<Int, Int> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                bars.top to bars.bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetTop to insets.systemWindowInsetBottom
            }

        /** Ekranın kendisini koyu temaya boyar (durum çubuğu ve gezinme çubuğu dahil). */
        fun styleWindow(a: Activity) {
            a.window.setBackgroundDrawable(ColorDrawable(Theme.BG))
            @Suppress("DEPRECATION")
            a.window.statusBarColor = Theme.BG
            @Suppress("DEPRECATION")
            a.window.navigationBarColor = Theme.SURFACE
        }

        fun removeFromParent(v: View) {
            (v.parent as? ViewGroup)?.removeView(v)
        }
    }
}
