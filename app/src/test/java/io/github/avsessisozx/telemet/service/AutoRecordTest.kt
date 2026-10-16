package io.github.avsessisozx.telemet.service

import io.github.avsessisozx.telemet.service.AutoRecord.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class AutoRecordTest {

    /** Saniyede bir çağırır; kamera durumu komutlara göre bir sonraki adımda değişir. */
    private class Sim(val a: AutoRecord = AutoRecord()) {
        var t = 0L
        var recording = false
        val actions = mutableListOf<Pair<Long, Action>>()
        fun run(seconds: Int, kmh: Double) {
            repeat(seconds) {
                val act = a.update(t, kmh, recording, cameraReady = true)
                if (act != Action.NONE) actions += t to act
                if (act == Action.START) recording = true
                if (act == Action.STOP) recording = false
                t += 1000
            }
        }
    }

    @Test
    fun startsAfterMovingAndStopsAfterLongStop() {
        val s = Sim()
        s.run(10, 0.0)
        s.run(10, 40.0)
        assertEquals(listOf(13_000L to Action.START), s.actions)
        s.run(60, 0.0)         // kısa duruş (ışık): durmaz
        assertEquals(1, s.actions.size)
        s.run(200, 0.0)        // uzun duruş: durur
        assertEquals(Action.STOP, s.actions.last().second)
        assertEquals(20_000L + 180_000L, s.actions.last().first)
    }

    @Test
    fun doesNotStopManualRecording() {
        val s = Sim()
        s.recording = true
        s.run(400, 0.0)
        assertEquals(emptyList<Pair<Long, Action>>(), s.actions)
    }

    @Test
    fun manualStopWhileRidingIsRespected() {
        val s = Sim()
        s.run(10, 50.0) // otomatik başladı
        s.recording = false // kullanıcı elle durdurdu
        s.run(120, 50.0)
        assertEquals(1, s.actions.size) // yeniden başlatılmadı
        s.run(200, 0.0) // uzun duruş
        s.run(10, 50.0) // yeni hareket: yeniden başlar
        assertEquals(Action.START, s.actions.last().second)
        assertEquals(2, s.actions.size)
    }

    @Test
    fun brieflyFastIsIgnoredAndNoCameraDoesNothing() {
        val s = Sim()
        s.run(2, 30.0); s.run(5, 0.0); s.run(2, 30.0)
        assertEquals(0, s.actions.size)
        val a = AutoRecord()
        for (i in 0 until 20) assertEquals(Action.NONE, a.update(i * 1000L, 50.0, false, cameraReady = false))
    }

    @Test
    fun retriesWhenCameraIgnoresCommand() {
        val a = AutoRecord()
        var starts = 0
        for (i in 0 until 40) {
            if (a.update(i * 1000L, 50.0, false, cameraReady = true) == Action.START) starts++
        }
        // Kamera tepki vermiyor: 10 sn sonra yeniden dener, art arda basmaz.
        assertEquals(4, starts) // 3, 14, 25, 36. saniyeler
    }
}
