package io.github.avsessisozx.telemet.service

/**
 * GPS'ten konum gelmediğinde nedeni tahmin eder, böylece kullanıcıya "aranıyor" yerine
 * ne yapması gerektiği söylenebilir.
 *
 * - Konum kapalı: sağlayıcı devre dışı.
 * - Uydu verisi yok: sağlayıcı açık ama GPS'ten hiç uydu bilgisi gelmiyor. Telefonun gerçek GPS'i
 *   çalışmıyor demektir; en sık sebep Geliştirici seçeneklerinde unutulmuş "sahte konum uygulaması"
 *   ya da sistemin konumu arka planda kesmesi (emülatörde birebir doğrulandı).
 * - Konum yok ama uydular görünüyor: GPS çalışıyor, sadece henüz konum bulamadı (kapalı alan vb.).
 */
object GpsDiagnosis {

    enum class Issue { NONE, LOCATION_OFF, NO_SATELLITES, NO_POSITION }

    /** İlk konum için tanınan süre; soğuk başlangıç açık havada bile 30 sn'ye kadar sürebilir. */
    const val GRACE_MS = 30_000L

    /** Bu süre boyunca uydu durumu gelmediyse "görünen uydu yok" sayılır. */
    const val GNSS_STALE_MS = 10_000L

    fun diagnose(providerEnabled: Boolean, hasFix: Boolean, silentMs: Long, satellitesVisible: Int): Issue = when {
        !providerEnabled -> Issue.LOCATION_OFF
        hasFix -> Issue.NONE
        silentMs < GRACE_MS -> Issue.NONE
        satellitesVisible <= 0 -> Issue.NO_SATELLITES
        else -> Issue.NO_POSITION
    }
}
