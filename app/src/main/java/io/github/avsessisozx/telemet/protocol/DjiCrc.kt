package io.github.avsessisozx.telemet.protocol

/**
 * DJI R SDK çerçevelerinde kullanılan CRC hesapları.
 *
 * - CRC-16: yansıtılmış polinom 0xA001, başlangıç değeri 0x3AA3, son XOR yok.
 * - CRC-32: yansıtılmış polinom 0xEDB88320, başlangıç değeri 0x3AA3, son XOR yok.
 *
 * Değerler DJI'nin Osmo-GPS-Controller-Demo projesindeki (MIT) tablolarla aynıdır.
 */
object DjiCrc {
    private const val INIT = 0x3AA3

    private val table16 = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xA001 else c ushr 1 }
        c
    }

    private val table32 = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    fun crc16(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = INIT
        for (i in offset until offset + length) {
            crc = (table16[(crc xor data[i].toInt()) and 0xFF] xor (crc ushr 8)) and 0xFFFF
        }
        return crc
    }

    fun crc32(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = INIT
        for (i in offset until offset + length) {
            crc = table32[(crc xor data[i].toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc
    }
}
