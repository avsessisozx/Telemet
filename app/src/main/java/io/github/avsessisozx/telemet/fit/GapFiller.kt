package io.github.avsessisozx.telemet.fit

/**
 * Kayıtta saniye boşluğu bırakmaz. DJI Studio, videonun her anına karşılık gelen veri arıyor;
 * boşluk olursa "süre eşleşmiyor" uyarısı veriyor.
 *
 * - İlk GPS sinyali gelene kadar geçen süre (Başlat'tan itibaren) ilk konumla, hız 0 olarak doldurulur.
 * - Sürüş ortasındaki kesintiler (tünel, köprü altı) iki ölçüm arasında doğrusal ara değerle doldurulur.
 * - Durdur'a basıldığında son ölçüm eskiyse kayıt durdurma anına kadar son konumla uzatılır.
 */
object GapFiller {

    /** Bundan uzun kesintiler doldurulmaz (telefon başka yere taşınmış olabilir). */
    const val MAX_FILL_S = 30 * 60L

    /**
     * [next] yazılmadan önce eklenecek dolgu noktaları ve [next] kendisi.
     * @param prev en son yazılan nokta (yoksa null)
     * @param startSecond sürüşün başladığı an (Unix saniyesi); yalnızca ilk nokta için kullanılır
     */
    fun pointsFor(prev: FitEncoder.Point?, next: FitEncoder.Point, startSecond: Long?): List<FitEncoder.Point> {
        val nextSec = next.utcMillis / 1000
        val out = ArrayList<FitEncoder.Point>()
        if (prev == null) {
            if (startSecond != null && startSecond < nextSec && nextSec - startSecond <= MAX_FILL_S) {
                for (s in startSecond until nextSec) {
                    out += next.copy(
                        utcMillis = s * 1000, speedMs = 0.0, distanceM = 0.0,
                        leanDeg = 0.0, lateralG = 0.0, longitudinalG = 0.0,
                    )
                }
            }
        } else {
            val prevSec = prev.utcMillis / 1000
            val gap = nextSec - prevSec
            if (gap in 2..MAX_FILL_S) {
                for (k in 1 until gap) {
                    val f = k.toDouble() / gap
                    out += FitEncoder.Point(
                        utcMillis = (prevSec + k) * 1000,
                        latitude = lerp(prev.latitude, next.latitude, f),
                        longitude = lerp(prev.longitude, next.longitude, f),
                        altitudeM = lerp(prev.altitudeM, next.altitudeM, f),
                        speedMs = lerp(prev.speedMs, next.speedMs, f),
                        distanceM = lerp(prev.distanceM, next.distanceM, f),
                        gradePercent = lerp(prev.gradePercent, next.gradePercent, f),
                    )
                }
            }
        }
        out += next.copy(utcMillis = nextSec * 1000)
        return out
    }

    /** Durdur'a basıldığında son noktadan durdurma anına kadar uzatma. */
    fun padUntil(last: FitEncoder.Point, endSecond: Long): List<FitEncoder.Point> {
        val lastSec = last.utcMillis / 1000
        if (endSecond <= lastSec + 1 || endSecond - lastSec > MAX_FILL_S) return emptyList()
        return (lastSec + 1..endSecond).map {
            last.copy(utcMillis = it * 1000, speedMs = 0.0, leanDeg = 0.0, lateralG = 0.0, longitudinalG = 0.0)
        }
    }

    private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f
}
