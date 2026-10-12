package io.github.avsessisozx.telemet.protocol

/**
 * DJI R SDK çerçevesi.
 *
 * | Alan       | Ofset | Boyut |
 * |------------|-------|-------|
 * | SOF (0xAA) | 0     | 1     |
 * | Ver/Uzunluk| 1     | 2     |  [9:0] toplam uzunluk, [15:10] sürüm (0)
 * | CmdType    | 3     | 1     |  [4:0] yanıt türü, [5] 1 = yanıt çerçevesi
 * | ENC        | 4     | 1     |
 * | RES        | 5     | 3     |
 * | SEQ        | 8     | 2     |
 * | CRC-16     | 10    | 2     |  SOF..SEQ
 * | DATA       | 12    | n     |  CmdSet, CmdID, yük
 * | CRC-32     | 12+n  | 4     |  SOF..DATA
 *
 * Tüm alanlar little-endian.
 */
class DjiFrame(
    val cmdType: Int,
    val seq: Int,
    val cmdSet: Int,
    val cmdId: Int,
    val payload: ByteArray,
) {
    val isResponse: Boolean get() = (cmdType and 0x20) != 0

    fun matches(set: Int, id: Int): Boolean = cmdSet == set && cmdId == id

    fun encode(): ByteArray = encode(cmdType, seq, cmdSet, cmdId, payload)

    override fun toString(): String =
        "DjiFrame(%s cmd=%02X%02X seq=%d len=%d)".format(
            if (isResponse) "yanıt" else "komut", cmdSet, cmdId, seq, payload.size,
        )

    companion object {
        const val SOF = 0xAA
        const val HEADER_LEN = 12
        /** Başlık + CmdSet/CmdID + CRC-32 */
        const val OVERHEAD = HEADER_LEN + 2 + 4
        const val MAX_LEN = 0x3FF

        fun encode(cmdType: Int, seq: Int, cmdSet: Int, cmdId: Int, payload: ByteArray): ByteArray {
            val total = OVERHEAD + payload.size
            require(total <= MAX_LEN) { "Çerçeve çok uzun: $total" }
            val b = ByteArray(total)
            b[0] = SOF.toByte()
            b[1] = (total and 0xFF).toByte()
            b[2] = ((total shr 8) and 0x03).toByte()
            b[3] = cmdType.toByte()
            // 4: şifreleme yok, 5..7: ayrılmış
            b[8] = (seq and 0xFF).toByte()
            b[9] = ((seq shr 8) and 0xFF).toByte()
            val c16 = DjiCrc.crc16(b, 0, 10)
            b[10] = (c16 and 0xFF).toByte()
            b[11] = ((c16 shr 8) and 0xFF).toByte()
            b[12] = cmdSet.toByte()
            b[13] = cmdId.toByte()
            System.arraycopy(payload, 0, b, 14, payload.size)
            val c32 = DjiCrc.crc32(b, 0, total - 4)
            for (i in 0 until 4) b[total - 4 + i] = ((c32 ushr (8 * i)) and 0xFF).toByte()
            return b
        }

        fun frameLength(b: ByteArray, offset: Int): Int =
            (b[offset + 1].toInt() and 0xFF) or ((b[offset + 2].toInt() and 0x03) shl 8)

        /** Geçerli bir çerçeve değilse (uzunluk ya da CRC hatası) null döner. */
        fun decode(b: ByteArray, offset: Int = 0, length: Int = b.size - offset): DjiFrame? {
            if (length < OVERHEAD) return null
            if ((b[offset].toInt() and 0xFF) != SOF) return null
            val total = frameLength(b, offset)
            if (total < OVERHEAD || total > length) return null
            val c16 = (b[offset + 10].toInt() and 0xFF) or ((b[offset + 11].toInt() and 0xFF) shl 8)
            if (DjiCrc.crc16(b, offset, 10) != c16) return null
            var c32 = 0
            for (i in 0 until 4) c32 = c32 or ((b[offset + total - 4 + i].toInt() and 0xFF) shl (8 * i))
            if (DjiCrc.crc32(b, offset, total - 4) != c32) return null
            val seq = (b[offset + 8].toInt() and 0xFF) or ((b[offset + 9].toInt() and 0xFF) shl 8)
            return DjiFrame(
                cmdType = b[offset + 3].toInt() and 0xFF,
                seq = seq,
                cmdSet = b[offset + 12].toInt() and 0xFF,
                cmdId = b[offset + 13].toInt() and 0xFF,
                payload = b.copyOfRange(offset + 14, offset + total - 4),
            )
        }
    }
}

/**
 * Bluetooth bildirimleriyle gelen baytları biriktirip tam çerçevelere ayırır.
 * Bir çerçeve birden çok bildirime bölünse ya da 0xAA ile başlamayan
 * paketler gelse de (ör. Osmo Action 5 Pro'nun 0x55 paketleri) doğru çalışır.
 */
class FrameAssembler(private val maxBuffer: Int = 4096) {
    private var buffer = ByteArray(0)

    var droppedBytes: Long = 0
        private set

    fun reset() {
        buffer = ByteArray(0)
    }

    fun feed(data: ByteArray): List<DjiFrame> {
        buffer += data
        val out = ArrayList<DjiFrame>()
        var pos = 0
        while (true) {
            while (pos < buffer.size && (buffer[pos].toInt() and 0xFF) != DjiFrame.SOF) {
                pos++
                droppedBytes++
            }
            if (buffer.size - pos < DjiFrame.HEADER_LEN) break
            val total = DjiFrame.frameLength(buffer, pos)
            // Başlık CRC'si uzunluk alanını da doğrular; sahte bir 0xAA yüzünden beklemeye takılmayız.
            val headerCrc = (buffer[pos + 10].toInt() and 0xFF) or ((buffer[pos + 11].toInt() and 0xFF) shl 8)
            if (total < DjiFrame.OVERHEAD || DjiCrc.crc16(buffer, pos, 10) != headerCrc) {
                pos++
                droppedBytes++
                continue
            }
            if (buffer.size - pos < total) break
            val frame = DjiFrame.decode(buffer, pos, total)
            if (frame == null) {
                pos++
                droppedBytes++
                continue
            }
            out += frame
            pos += total
        }
        buffer = buffer.copyOfRange(pos, buffer.size)
        if (buffer.size > maxBuffer) {
            droppedBytes += buffer.size
            buffer = ByteArray(0)
        }
        return out
    }
}
