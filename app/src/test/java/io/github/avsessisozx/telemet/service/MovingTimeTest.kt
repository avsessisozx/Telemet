package io.github.avsessisozx.telemet.service

import org.junit.Assert.assertEquals
import org.junit.Test

class MovingTimeTest {

    @Test
    fun `hep hareketliyse tum sure sayilir`() {
        val sec = LongArray(10) { it.toLong() }           // 0..9 sn
        val spd = DoubleArray(10) { 10.0 }                 // hep hızlı
        assertEquals(9L, MovingTime.seconds(sec, spd))      // 9 aralık
    }

    @Test
    fun `durus sayilmaz`() {
        // 0-5 hareket, 5-10 durma, 10-15 yine hareket
        val sec = LongArray(16) { it.toLong() }
        val spd = DoubleArray(16) { i -> if (i in 6..10) 0.0 else 10.0 }
        val moving = MovingTime.seconds(sec, spd)
        // Durma bölgesindeki aralıklar düşer; toplam 15'ten az olmalı.
        assertEquals(true, moving < 15L)
        assertEquals(true, moving >= 9L)
    }

    @Test
    fun `tek nokta sifir`() {
        assertEquals(0L, MovingTime.seconds(longArrayOf(5), doubleArrayOf(10.0)))
    }

    @Test
    fun `buyuk bosluk atlanir`() {
        // İki nokta arası 1000 sn boşluk (sinyal kaybı) — hareket sayılmaz.
        val sec = longArrayOf(0, 1000)
        val spd = doubleArrayOf(10.0, 10.0)
        assertEquals(0L, MovingTime.seconds(sec, spd))
    }

    @Test
    fun `esigin hemen altindaki hiz durus sayilir`() {
        val sec = LongArray(5) { it.toLong() }
        val spd = DoubleArray(5) { 0.5 }   // 0.8 eşiğinin altı
        assertEquals(0L, MovingTime.seconds(sec, spd))
    }
}
