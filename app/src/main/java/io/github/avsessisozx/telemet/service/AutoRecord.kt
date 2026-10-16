package io.github.avsessisozx.telemet.service

/**
 * Hareketle otomatik kamera kaydı.
 * - Hız [startKmh]'nin üstünde [startHoldMs] boyunca kalırsa kayıt başlar.
 * - Kaydı bu sınıf başlattıysa ve hız [stopKmh]'nin altında [stopHoldMs] boyunca kalırsa kayıt durur.
 *   Kullanıcının elle başlattığı kayda dokunulmaz.
 * - Kullanıcı hareket halindeyken kaydı elle durdurursa, bir sonraki uzun duruşa kadar yeniden başlatılmaz.
 *
 * Saf Kotlin; zaman dışarıdan verilir, birim testleriyle doğrulanır.
 */
class AutoRecord(
    private val startKmh: Double = 15.0,
    private val startHoldMs: Long = 3_000,
    private val stopKmh: Double = 5.0,
    private val stopHoldMs: Long = 180_000,
) {
    enum class Action { NONE, START, STOP }

    private var fastSince = -1L
    private var slowSince = -1L
    /** Kaydı biz başlattık. */
    private var ownRecording = false
    /** Kullanıcı hareket halindeyken durdurdu; uzun duruşa kadar bekle. */
    private var suppressed = false
    private var lastRecording = false
    private var expectChange = false
    private var expectSince = 0L

    fun reset() {
        fastSince = -1; slowSince = -1
        ownRecording = false; suppressed = false
        lastRecording = false; expectChange = false
    }

    /**
     * @param speedKmh güncel hız (sinyal yoksa NaN)
     * @param recording kamera şu an kayıtta mı (bilinmiyorsa null)
     */
    fun update(nowMs: Long, speedKmh: Double, recording: Boolean?, cameraReady: Boolean): Action {
        // Kamera komuta 10 sn'de tepki vermediyse (uykuda, fotoğraf modunda…) beklemeyi bırak.
        if (expectChange && nowMs - expectSince > EXPECT_TIMEOUT_MS) {
            expectChange = false
            if (recording != true) ownRecording = false
        }
        if (recording != null) {
            if (recording != lastRecording) {
                if (!expectChange) {
                    // Kayıt durumu bizim dışımızda değişti (kullanıcı elle bastı).
                    if (!recording && ownRecording && slowSince < 0) suppressed = true
                    ownRecording = false
                }
                expectChange = false
                lastRecording = recording
            }
        }
        val fast = !speedKmh.isNaN() && speedKmh >= startKmh
        val slow = speedKmh.isNaN() || speedKmh < stopKmh
        fastSince = if (fast) (if (fastSince < 0) nowMs else fastSince) else -1
        slowSince = if (slow) (if (slowSince < 0) nowMs else slowSince) else -1

        if (slowSince >= 0 && nowMs - slowSince >= stopHoldMs) suppressed = false
        if (!cameraReady || recording == null || expectChange) return Action.NONE

        if (!recording && !suppressed && fastSince >= 0 && nowMs - fastSince >= startHoldMs) {
            ownRecording = true
            expectChange = true
            expectSince = nowMs
            return Action.START
        }
        if (recording && ownRecording && slowSince >= 0 && nowMs - slowSince >= stopHoldMs) {
            ownRecording = false
            expectChange = true
            expectSince = nowMs
            return Action.STOP
        }
        return Action.NONE
    }

    /** Komut kameraya ulaşmadıysa (bağlantı yok) beklemeyi bırak. */
    fun commandFailed() {
        expectChange = false
    }

    private companion object {
        const val EXPECT_TIMEOUT_MS = 10_000L
    }
}
