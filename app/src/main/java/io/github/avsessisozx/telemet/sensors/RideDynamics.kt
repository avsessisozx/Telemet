package io.github.avsessisozx.telemet.sensors

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Motosiklet dinamiği hesapları. Saf Kotlin; birim testleriyle doğrulanır.
 *
 * Yatış açısı, telefonun gidondaki duruşundan bağımsız olarak fizikle bulunur:
 * bir virajda motorun yatışı tan(yatış) = hız × dönüş hızı / g. Dönüş hızını jiroskop,
 * hızı GPS verir. İvmeölçerle doğrudan "eğim" ölçmek virajda işe yaramaz; çünkü
 * merkezkaç kuvveti hissedilen yerçekimini motorla birlikte yatırır.
 */
object RideDynamics {

    const val G = 9.80665

    /** Bu hızın altında yatış ve yanal G hesaplanmaz (park, manevra). */
    const val MIN_SPEED_MS = 3.0

    const val MAX_LEAN_DEG = 65.0

    /**
     * @param speedMs GPS hızı
     * @param yawRateMeasured jiroskopun telefonun "yukarı" eksenindeki dönüş hızı (rad/s).
     *   Sol dönüş pozitif. Yerçekimi vektörü virajda motorla yattığı için ölçülen değer
     *   gerçek dönüş hızının cos(yatış) katıdır; burada düzeltilir.
     * @return yatış açısı (derece), sola pozitif
     */
    fun leanDeg(speedMs: Double, yawRateMeasured: Double): Double {
        if (speedMs < MIN_SPEED_MS) return 0.0
        var lean = atan(speedMs * yawRateMeasured / G)
        // Gerçek dönüş hızı = ölçülen / cos(yatış); yatış buna bağlı olduğu için birkaç adımda yakınsatılır.
        repeat(10) {
            lean = atan(speedMs * (yawRateMeasured / cos(lean).coerceAtLeast(0.3)) / G)
        }
        return Math.toDegrees(lean).coerceIn(-MAX_LEAN_DEG, MAX_LEAN_DEG)
    }

    /** Yanal (viraj) ivmesi, g cinsinden; sola pozitif. */
    fun lateralG(speedMs: Double, yawRateMeasured: Double, leanDeg: Double): Double {
        if (speedMs < MIN_SPEED_MS) return 0.0
        val yaw = yawRateMeasured / cos(Math.toRadians(leanDeg)).coerceAtLeast(0.3)
        return (speedMs * yaw / G).coerceIn(-2.0, 2.0)
    }

    /** Boyuna ivme (hızlanma pozitif, fren negatif), g cinsinden. */
    fun longitudinalG(prevSpeedMs: Double, speedMs: Double, dtSeconds: Double): Double {
        if (dtSeconds <= 0.2 || dtSeconds > 3.0) return 0.0
        return ((speedMs - prevSpeedMs) / dtSeconds / G).coerceIn(-2.0, 2.0)
    }

    fun totalG(lateral: Double, longitudinal: Double): Double = sqrt(lateral * lateral + longitudinal * longitudinal)

    /** Vektörün birim vektördeki bileşeni (u normalize edilmiş olmalı). */
    fun dot(a: FloatArray, u: DoubleArray): Double = a[0] * u[0] + a[1] * u[1] + a[2] * u[2]

    fun normalize(v: FloatArray): DoubleArray? {
        val n = sqrt((v[0] * v[0] + v[1] * v[1] + v[2] * v[2]).toDouble())
        if (n < 1e-3) return null
        return doubleArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    /** Bir saniye içindeki en uç değeri (işaretini koruyarak) seçmek için. */
    fun peak(current: Double, candidate: Double): Double = if (abs(candidate) > abs(current)) candidate else current
}
