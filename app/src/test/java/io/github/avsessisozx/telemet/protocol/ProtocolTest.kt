package io.github.avsessisozx.telemet.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Referans çerçeveler DJI'nin Osmo-GPS-Controller-Demo projesinden (MIT) alınmıştır:
 * docs/protocol_data_segment.md, test/test_gps.c, test/connect_cmd_frame_builder/connect_cmd_frame.txt
 */
class ProtocolTest {

    private fun hex(s: String): ByteArray {
        val clean = s.replace(" ", "").replace(",", "").replace("0x", "")
        return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    // Belgedeki "Hyperlapse moduna geç" örneği
    private val modeSwitchFrame = hex("AA1B000100000000050057EE1D04000033FF0A01473936F4FAE1D0")

    // test_gps.c içindeki ilk GPS paketi
    private val gpsFrame = hex(
        "AA4200000000000003 00D2DD00174F00" +
            "35018AA50200BCB2E943A34F750DB96D" +
            "0000DE5824418D9AC541000000 00E803" +
            "0000E80300000A0000000E00000063B5" +
            "D131",
    )

    // connect_cmd_frame.txt ilk satırı
    private val connectFrame = hex(
        "AA330002000000000100982F00197856341206383456789ABC" +
            "00000000000000000000000000000000FE1F00000000796C667C",
    )

    @Test
    fun crcMatchesModeSwitchExample() {
        assertEquals(0xEE57, DjiCrc.crc16(modeSwitchFrame, 0, 10))
        val crc32 = DjiCrc.crc32(modeSwitchFrame, 0, modeSwitchFrame.size - 4)
        assertEquals(0xD0E1FAF4.toInt(), crc32)
    }

    @Test
    fun encodeModeSwitchMatchesReference() {
        val payload = hex("000033FF0A01473936")
        val encoded = DjiFrame.encode(CmdType.RESPONSE_OR_NOT, 5, Cmd.SET_CAMERA, Cmd.ID_MODE_SWITCH, payload)
        assertArrayEquals(modeSwitchFrame, encoded)
    }

    @Test
    fun decodeAndReencodeGpsReference() {
        val frame = DjiFrame.decode(gpsFrame)
        assertNotNull(frame)
        frame!!
        assertEquals(Cmd.SET_GENERAL, frame.cmdSet)
        assertEquals(Cmd.ID_GPS_PUSH, frame.cmdId)
        assertEquals(3, frame.seq)
        assertFalse(frame.isResponse)

        val gps = GpsPush.parse(frame.payload)!!
        assertEquals(20250703, gps.yearMonthDay)
        assertEquals(173450, gps.hourMinuteSecond)
        assertEquals(14, gps.satellites)
        assertArrayEquals(frame.payload, gps.toPayload())
        assertArrayEquals(gpsFrame, DjiFrame.encode(CmdType.NO_RESPONSE, 3, Cmd.SET_GENERAL, Cmd.ID_GPS_PUSH, gps.toPayload()))
    }

    @Test
    fun gpsFromLocationUsesUtcPlusEight() {
        // 2025-07-03 09:34:50 UTC
        val millis = 1751535290000L
        val gps = GpsPush.from(millis, 41.0, 29.0, 100.0, 1.0, 2.0, 0.0, 3.0, 5.0, 0.5, 12)
        assertEquals(20250703, gps.yearMonthDay)
        assertEquals(173450, gps.hourMinuteSecond)
        assertEquals(410000000, gps.latitudeE7)
        assertEquals(290000000, gps.longitudeE7)
        assertEquals(100000, gps.heightMm)
        assertEquals(100f, gps.speedNorthCms)
        assertEquals(200f, gps.speedEastCms)
        assertEquals(3000, gps.horizontalAccuracyMm)
        assertEquals(GpsPush.SIZE, gps.toPayload().size)
    }

    @Test
    fun connectionRequestMatchesReference() {
        val req = ConnectionRequest(
            deviceId = 0x12345678,
            mac = hex("383456789ABC"),
            fwVersion = 0,
            verifyMode = 0,
            verifyData = 0x1FFE,
        )
        val encoded = DjiFrame.encode(CmdType.WAIT_RESULT, 1, Cmd.SET_GENERAL, Cmd.ID_CONNECT, req.toPayload())
        assertArrayEquals(connectFrame, encoded)

        val parsed = ConnectionRequest.parse(DjiFrame.decode(connectFrame)!!.payload)!!
        assertEquals(0x12345678, parsed.deviceId)
        assertEquals(0x1FFE, parsed.verifyData)
        assertArrayEquals(hex("383456789ABC"), parsed.mac)
    }

    @Test
    fun assemblerHandlesSplitAndNoise() {
        val asm = FrameAssembler()
        val noise = hex("55AA0011")
        val combined = noise + gpsFrame + modeSwitchFrame
        val out = ArrayList<DjiFrame>()
        // 20 baytlık parçalar halinde gönder (küçük MTU'yu taklit eder)
        var i = 0
        while (i < combined.size) {
            val end = minOf(i + 20, combined.size)
            out += asm.feed(combined.copyOfRange(i, end))
            i = end
        }
        assertEquals(2, out.size)
        assertEquals(Cmd.ID_GPS_PUSH, out[0].cmdId)
        assertEquals(Cmd.ID_MODE_SWITCH, out[1].cmdId)
    }

    @Test
    fun corruptedFrameIsRejected() {
        val bad = gpsFrame.copyOf()
        bad[20] = (bad[20].toInt() xor 0x01).toByte()
        assertNull(DjiFrame.decode(bad))
    }

    @Test
    fun cameraAdvertisementDetection() {
        // Flags + üretici verisi (AA 08 xx xx FA ...)
        val adv = hex("020106" + "08FF" + "AA08123 4FA0102".replace(" ", ""))
        assertTrue(DjiCamera.isCameraAdvertisement(adv))
        val other = hex("020106" + "08FF" + "4C000215FA0102")
        assertFalse(DjiCamera.isCameraAdvertisement(other))
        assertFalse(DjiCamera.isCameraAdvertisement(null))
    }

    @Test
    fun cameraStatusParse() {
        val p = ByteArray(38)
        p[0] = 0x01
        p[1] = 0x03
        p[28] = 0
        p[37] = 87
        val s = CameraStatus.parse(p)!!
        assertTrue(s.isRecording)
        assertEquals(87, s.batteryPercent)
        assertEquals("Video", s.modeName)
    }

    @Test
    fun modelNames() {
        assertEquals("Osmo Action 4", DjiCamera.modelName(0xFF330000.toInt()))
        assertEquals("Osmo Action 4", DjiCamera.modelName(0xFF33))
        assertNull(DjiCamera.modelName(0x12345678))
    }
}
