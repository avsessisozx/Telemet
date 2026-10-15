package io.github.avsessisozx.telemet.util

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Uygulama dili. Varsayılan: telefonun dili (desteklenmiyorsa İngilizce).
 * Kullanıcı uygulama içinden başka bir dil seçebilir; Android 13+ sistemin uygulama dili ayarıyla,
 * daha eski sürümlerde her ekranın ve servisin bağlamı sarılarak uygulanır.
 */
object LocaleHelper {

    /** Desteklenen diller: etiket → kendi dilindeki adı. Boş etiket = telefonun dili. */
    val LANGUAGES: List<Pair<String, String>> = listOf(
        "" to "",
        "en" to "English",
        "tr" to "Türkçe",
        "es" to "Español",
        "fr" to "Français",
        "it" to "Italiano",
        "de" to "Deutsch",
        "pt-BR" to "Português (Brasil)",
        "zh-CN" to "简体中文",
        "ja" to "日本語",
        "ko" to "한국어",
        "ru" to "Русский",
        "ar" to "العربية",
    )

    private const val PREFS = "telemet"
    private const val KEY = "app_language"

    /** Seçili dil etiketi; boşsa telefonun dili. */
    fun current(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = context.getSystemService(LocaleManager::class.java)
            val tags = lm?.applicationLocales?.toLanguageTags().orEmpty()
            if (tags.isNotEmpty()) return tags.substringBefore(',')
            // Sistem ayarından "telefonun dili" seçildiyse kendi kaydımız da boş sayılır.
            return ""
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
    }

    fun set(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, tag).commit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
    }

    /** Android 12 ve öncesi için: seçili dili bağlama uygular. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = current(base)
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    /** Dil listesinde gösterilecek ad. */
    fun displayName(context: Context, tag: String, systemLabel: String): String =
        LANGUAGES.firstOrNull { it.first == tag }?.second?.ifEmpty { systemLabel } ?: tag
}
