package io.github.avsessisozx.telemet.service

/**
 * Hareket süresi: sürüş boyunca aracın gerçekten hareket ettiği saniye sayısı.
 *
 * "Toplam süre" Başlat → Durdur arasını sayar; trafik ışığı, mola, duruş da buna dahildir.
 * "Hareket süresi" ise yalnızca hızın bir eşiğin üstünde olduğu saniyeleri sayar; böylece
 * ortalama hız "hareket halindeyken ortalama" olarak da gösterilebilir (Strava'daki "moving time").
 *
 * Ham FIT/GPX verisine dokunulmaz; bu yalnızca bir özet hesabıdır ve eski sürüşlerde de çalışır.
 * Saf Kotlin; birim testleriyle doğrulanır.
 */
object MovingTime {

    /** Bu hızın (m/sn) altı "duruyor" sayılır. ~3 km/sa. */
    const val MOVING_THRESHOLD_MS = 0.8

    /**
     * Ardışık noktaların zaman damgaları ve hızlarından hareket süresini (saniye) hesaplar.
     *
     * @param secondsAsc artan sıralı Unix saniye değerleri (her nokta için)
     * @param speedMs her noktanın hızı (m/sn)
     * @return hareket halinde geçen toplam saniye
     */
    fun seconds(secondsAsc: LongArray, speedMs: DoubleArray): Long {
        require(secondsAsc.size == speedMs.size) { "zaman ve hız aynı boyutta olmalı" }
        if (secondsAsc.size < 2) return 0L
        var moving = 0L
        for (i in 1 until secondsAsc.size) {
            val dt = secondsAsc[i] - secondsAsc[i - 1]
            if (dt <= 0) continue
            // Aradaki boşluğu, iki uçtan en az biri hareketliyse hareket say; büyük boşlukları (duruş/işaret kaybı) atla.
            val movingHere = speedMs[i] >= MOVING_THRESHOLD_MS || speedMs[i - 1] >= MOVING_THRESHOLD_MS
            if (movingHere && dt <= 600) moving += dt
        }
        return moving
    }
}
