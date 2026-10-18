package io.github.avsessisozx.telemet.service

import kotlin.math.cos

/**
 * Rota çizimindeki GPS titremesini azaltır (yalnızca görsel; ham FIT/GPX noktaları değişmez).
 *
 * Gerçek "yola oturtma" bir yol haritası gerektirir; Telemet dış servise bağlı olmadığı için
 * bunun yerine komşu noktaların ağırlıklı ortalamasıyla zikzakı yumuşatan yerel bir filtre uygulanır.
 * Sonuç haritasız, internetsiz, ücretsiz çalışır ve rotanın şeklini belirgin biçimde temizler.
 *
 * Saf Kotlin; Android'e bağlı değil, birim testleriyle doğrulanır.
 */
object RouteSmoother {

    /**
     * [lat]/[lon] dizilerini yumuşatılmış kopya olarak döndürür. Girdiyi değiştirmez.
     * Uçlar (ilk ve son nokta) sabit kalır ki başlangıç/bitiş kaymasın.
     *
     * @param window tek sayı olmalı (3, 5, 7…); kaç komşuyu ortalamaya katacağı. Varsayılan 5.
     * @param passes filtreyi kaç kez uygulayacağı; artarsa daha pürüzsüz ama köşeler biraz yuvarlanır.
     */
    fun smooth(lat: DoubleArray, lon: DoubleArray, window: Int = 5, passes: Int = 2): Pair<DoubleArray, DoubleArray> {
        require(lat.size == lon.size) { "lat ve lon aynı boyutta olmalı" }
        if (lat.size < 5 || window < 3) return lat.copyOf() to lon.copyOf()
        val half = (window / 2)
        var outLat = lat.copyOf()
        var outLon = lon.copyOf()
        repeat(passes.coerceAtLeast(1)) {
            val srcLat = outLat
            val srcLon = outLon
            val dstLat = srcLat.copyOf()
            val dstLon = srcLon.copyOf()
            // Uçları korumak için ilk ve son [half] noktaya dokunma.
            for (i in half until srcLat.size - half) {
                var sLat = 0.0
                var sLon = 0.0
                var wsum = 0.0
                for (k in -half..half) {
                    // Üçgen ağırlık: merkeze yakın nokta daha etkili.
                    val w = (half + 1 - kotlin.math.abs(k)).toDouble()
                    sLat += srcLat[i + k] * w
                    sLon += srcLon[i + k] * w
                    wsum += w
                }
                dstLat[i] = sLat / wsum
                dstLon[i] = sLon / wsum
            }
            outLat = dstLat
            outLon = dstLon
        }
        return outLat to outLon
    }

    /**
     * İki nokta arası yaklaşık mesafe (metre), kısa mesafeler için düz düzlem yaklaşımı.
     * Testlerde ve toplam sapma ölçümünde kullanılır.
     */
    fun approxMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val mLat = Math.toRadians((lat1 + lat2) / 2)
        val dLat = (lat2 - lat1) * 111_320.0
        val dLon = (lon2 - lon1) * 111_320.0 * cos(mLat)
        return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
    }
}
