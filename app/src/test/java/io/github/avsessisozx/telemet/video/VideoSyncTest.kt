package io.github.avsessisozx.telemet.video

import io.github.avsessisozx.telemet.fit.FitEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class VideoSyncTest {

    private fun box(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val size = 8 + body.size
        out.write(byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        return out.toByteArray()
    }

    private fun be32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be64(v: Long) = be32(v ushr 32) + be32(v and 0xFFFFFFFFL)

    private fun mvhdV0(creationUnixS: Long, timescale: Long, duration: Long): ByteArray =
        box("mvhd", byteArrayOf(0, 0, 0, 0) + be32(creationUnixS + 2_082_844_800L) + be32(0) + be32(timescale) + be32(duration) + ByteArray(80))

    private fun mvhdV1(creationUnixS: Long, timescale: Long, duration: Long): ByteArray =
        box("mvhd", byteArrayOf(1, 0, 0, 0) + be64(creationUnixS + 2_082_844_800L) + be64(0) + be32(timescale) + be64(duration) + ByteArray(80))

    /** DJI gibi: ftyp, büyük mdat (64 bit boyut), en sonda moov. */
    private fun fakeMp4(mvhd: ByteArray): ByteArray {
        val ftyp = box("ftyp", "isom".toByteArray() + be32(0))
        val payload = ByteArray(5000)
        val mdat = be32(1) + "mdat".toByteArray() + be64(16L + payload.size) + payload
        val moov = box("moov", box("udta", ByteArray(20)) + mvhd + box("trak", ByteArray(40)))
        return ftyp + mdat + moov
    }

    @Test
    fun readsCreationAndDurationV0() {
        val info = Mp4Info.read(Mp4Info.BytesSource(fakeMp4(mvhdV0(1_760_623_822L, 1000, 2_480_000))))
        assertNotNull(info)
        assertEquals(1_760_623_822L, info!!.creationS)
        assertEquals(2_480_000L, info.durationMs)
    }

    @Test
    fun readsV1() {
        val info = Mp4Info.read(Mp4Info.BytesSource(fakeMp4(mvhdV1(1_760_623_822L, 90_000, 90_000L * 600))))
        assertEquals(1_760_623_822L, info!!.creationS)
        assertEquals(600_000L, info.durationMs)
    }

    @Test
    fun noMoovIsNull() {
        assertNull(Mp4Info.read(Mp4Info.BytesSource(box("ftyp", ByteArray(8)) + box("mdat", ByteArray(100)))))
    }

    @Test
    fun parsesDjiFileName() {
        // 2026-10-16 14:30:22 (saat dilimsiz)
        assertEquals(1_792_161_022L, VideoSync.nameTimeS("DJI_20261016143022_0001_D.MP4"))
        assertNull(VideoSync.nameTimeS("video.mp4"))
    }

    private val tz3h: (Long) -> Int = { 3 * 3600 * 1000 }

    private fun rideSeconds(from: Long, to: Long) = LongArray((to - from + 1).toInt()) { from + it }

    @Test
    fun picksLocalInterpretationWhenCameraWritesLocalTime() {
        // Sürüş 11:00:00–12:00:00 UTC (14:00–15:00 Türkiye).
        val rideStart = 1_760_612_400L
        val ride = rideSeconds(rideStart, rideStart + 3600)
        // Kamera mvhd'ye yerel saati yazmış: 14:10 "UTC" gibi görünüyor.
        val clips = listOf(
            VideoSync.Clip("a.mp4", rideStart + 600 + 3 * 3600, 1_500_000),
            VideoSync.Clip("b.mp4", rideStart + 2100 + 3 * 3600, 1_200_000),
        )
        val r = VideoSync.place(clips, listOf(ride), tz3h)
        assertEquals(VideoSync.TimeSource.FILE_LOCAL, r.source)
        assertEquals((rideStart + 600) * 1000, r.clips[0].startMs)
        assertEquals(1.0, r.clips[0].coverage, 1e-9)
        assertEquals(1.0, r.clips[1].coverage, 1e-9)
    }

    @Test
    fun picksUtcWhenCameraClockIsUtc() {
        val rideStart = 1_760_612_400L
        val ride = rideSeconds(rideStart, rideStart + 3600)
        val r = VideoSync.place(listOf(VideoSync.Clip("x.mp4", rideStart + 100, 60_000)), listOf(ride), tz3h)
        assertEquals(VideoSync.TimeSource.FILE_UTC, r.source)
        assertEquals(1.0, r.clips[0].coverage, 1e-9)
    }

    @Test
    fun fallsBackToFileNameAndAppliesManualOffset() {
        val rideStart = 1_792_161_022L - 3 * 3600 - 5 // ad 14:30:22 yerel; sürüş 5 sn önce başladı
        val ride = rideSeconds(rideStart, rideStart + 120)
        val clip = VideoSync.Clip("DJI_20261016143022_0001_D.MP4", null, 60_000)
        val r = VideoSync.place(listOf(clip), listOf(ride), tz3h, manualOffsetS = -2)
        assertEquals(VideoSync.TimeSource.NAME_LOCAL, r.source)
        assertEquals((1_792_161_022L - 3 * 3600 - 2) * 1000, r.clips[0].startMs)
    }

    @Test
    fun partialCoverageAndNoMatch() {
        val rideStart = 1_000_000L
        val ride = rideSeconds(rideStart, rideStart + 99)
        val r = VideoSync.place(
            listOf(VideoSync.Clip("half", rideStart + 50, 100_000), VideoSync.Clip("none", rideStart + 5000, 10_000)),
            listOf(ride), { 0 },
        )
        assertEquals(0.5, r.clips[0].coverage, 1e-9)
        assertEquals(0L, r.clips[1].coveredMs)
        assertTrue(r.anyMatch)
    }

    private fun raw(fromSec: Long, n: Int): ByteArray {
        val out = ByteArrayOutputStream()
        for (i in 0 until n) {
            out.write(FitEncoder.encodeRecord(FitEncoder.Point((fromSec + i) * 1000, 41.0 + i * 1e-4, 29.0, 100.0, 20.0, i * 20.0, 0.0)))
        }
        return out.toByteArray()
    }

    @Test
    fun sliceKeepsOnlyClipSeconds() {
        val r = raw(1_700_000_000L, 100)
        val s = VideoSync.slice(r, 1_700_000_010_500L, 1_700_000_020_500L)
        val d = FitEncoder.decodeRecords(s)
        assertEquals(11, d.size) // 10..20. saniyeler
        assertEquals(1_700_000_010L - FitEncoder.FIT_EPOCH_S, d.first().fitTime)
        assertEquals(1_700_000_020L - FitEncoder.FIT_EPOCH_S, d.last().fitTime)
        assertNotNull(FitEncoder.buildFile(s, 1_700_000_010_500L))
    }

    @Test
    fun concatSortsAndDropsDuplicates() {
        val a = raw(1_700_000_050L, 10)
        val b = raw(1_700_000_000L, 55) // 50–54 çakışıyor
        val d = FitEncoder.decodeRecords(VideoSync.concat(listOf(a, b)))
        assertEquals(60, d.size)
        d.zipWithNext().forEach { (x, y) -> assertEquals(1L, y.fitTime - x.fitTime) }
    }
}
