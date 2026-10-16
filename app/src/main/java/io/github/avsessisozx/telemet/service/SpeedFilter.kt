package io.github.avsessisozx.telemet.service

/**
 * GPS hızındaki tek seferlik sıçramaları ayıklar.
 *
 * Hız, GPS çipinin Doppler ölçümünden gelir (konumdan hesaplanmaz) ve normalde çok isabetlidir.
 * Ama tünel çıkışı, köprü altı ya da bina arası gibi yerlerde bazen tek bir ölçüm saçmalar.
 * - Hata payı çok büyük ölçümlerde son güvenilir hız korunur.
 * - Bir motosikletin yapamayacağı kadar hızlı değişim (yaklaşık 1,2 g'den fazla) sınırlanır.
 * Normal sürüşte hiçbir şeyi değiştirmez ve gecikme eklemez.
 */
class SpeedFilter(
    /** İzin verilen en büyük hız değişimi, m/s². Motosiklet frende ~1 g, gazda daha az. */
    private val maxAccelMs2: Double = 12.0,
    /** Bundan büyük hız hatası bildiren ölçüme güvenilmez, m/s. */
    private val maxSpeedAccuracyMs: Double = 3.0,
    /** Bundan büyük konum hatası bildiren ölçüme güvenilmez, m. */
    private val maxHorizontalAccuracyM: Double = 40.0,
) {
    private var lastSpeed = Double.NaN
    private var lastTimeS = Double.NaN
    /** Art arda kaç güvenilmez ölçüm atlandı; telefon sürekli büyük hata bildirirse hız donmasın. */
    private var skipped = 0

    fun reset() {
        skipped = 0
        lastSpeed = Double.NaN
        lastTimeS = Double.NaN
    }

    /** @param speedAccuracyMs ve [horizontalAccuracyM] bilinmiyorsa NaN */
    fun filter(speedMs: Double, timeS: Double, speedAccuracyMs: Double, horizontalAccuracyM: Double): Double {
        val raw = if (speedMs.isNaN() || speedMs < 0) 0.0 else speedMs
        if (lastSpeed.isNaN()) {
            lastSpeed = raw
            lastTimeS = timeS
            return raw
        }
        val dt = timeS - lastTimeS
        lastTimeS = timeS
        // Uzun kesintiden sonra (ya da saat geri gitti) eski değere bakmadan yeniden başla.
        if (dt <= 0.0 || dt > 5.0) {
            lastSpeed = raw
            return raw
        }
        val unreliable = (!speedAccuracyMs.isNaN() && speedAccuracyMs > maxSpeedAccuracyMs) ||
            (!horizontalAccuracyM.isNaN() && horizontalAccuracyM > maxHorizontalAccuracyM)
        val hold = unreliable && skipped < MAX_SKIPPED
        skipped = if (hold) skipped + 1 else 0
        val target = if (hold) lastSpeed else raw
        val maxStep = maxAccelMs2 * dt
        val out = target.coerceIn(lastSpeed - maxStep, lastSpeed + maxStep).coerceAtLeast(0.0)
        lastSpeed = out
        return out
    }

    private companion object {
        const val MAX_SKIPPED = 3
    }
}
