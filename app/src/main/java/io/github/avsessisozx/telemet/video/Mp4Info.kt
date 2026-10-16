package io.github.avsessisozx.telemet.video

/**
 * MP4/MOV dosyasının başlangıç zamanını ve süresini okur (moov → mvhd kutusu).
 *
 * 16 GB'lık bir videonun tamamı okunmaz: yalnızca kutu başlıkları atlanarak mvhd bulunur,
 * birkaç yüz bayt okunur. Kamera moov kutusunu dosyanın sonuna yazsa da hızlıdır.
 * Saf Kotlin; birim testleriyle doğrulanır.
 */
object Mp4Info {

    /** 1904-01-01 ile 1970-01-01 arası saniye (MP4 zaman sıfırı). */
    private const val MP4_EPOCH_OFFSET_S = 2_082_844_800L

    data class Info(
        /** mvhd'deki oluşturma zamanı, Unix saniyesi. Kamera bunu yerel saatle yazmış olabilir. Yoksa null. */
        val creationS: Long?,
        val durationMs: Long,
    )

    /** Rastgele erişimli okuma (Android'de FileChannel, testte bayt dizisi). */
    interface Source {
        val size: Long
        /** [pos]'tan itibaren [buf]'u doldurmaya çalışır; okunan bayt sayısını döndürür. */
        fun read(pos: Long, buf: ByteArray): Int
    }

    class BytesSource(private val bytes: ByteArray) : Source {
        override val size: Long get() = bytes.size.toLong()
        override fun read(pos: Long, buf: ByteArray): Int {
            if (pos >= bytes.size) return 0
            val n = minOf(buf.size.toLong(), bytes.size - pos).toInt()
            System.arraycopy(bytes, pos.toInt(), buf, 0, n)
            return n
        }
    }

    fun read(src: Source): Info? {
        val moov = findBox(src, 0, src.size, "moov") ?: return null
        val mvhd = findBox(src, moov.first, moov.second, "mvhd") ?: return null
        val head = ByteArray(32)
        val n = src.read(mvhd.first, head)
        if (n < 20) return null
        val version = head[0].toInt() and 0xFF
        val creation: Long
        val timescale: Long
        val duration: Long
        if (version == 1) {
            if (n < 32) return null
            creation = u64(head, 4)
            timescale = u32(head, 20)
            duration = u64(head, 24)
        } else {
            creation = u32(head, 4)
            timescale = u32(head, 12)
            duration = u32(head, 16)
        }
        if (timescale <= 0) return null
        val durationMs = if (duration == 0xFFFFFFFFL || duration < 0) 0L else duration * 1000 / timescale
        val creationS = if (creation > MP4_EPOCH_OFFSET_S) creation - MP4_EPOCH_OFFSET_S else null
        return Info(creationS, durationMs)
    }

    /** [start, end) aralığında [type] kutusunu arar; içeriğinin başlangıcını ve sonunu döndürür. */
    private fun findBox(src: Source, start: Long, end: Long, type: String): Pair<Long, Long>? {
        var pos = start
        val h = ByteArray(16)
        var guard = 0
        while (pos + 8 <= end && guard++ < 10_000) {
            if (src.read(pos, h) < 8) return null
            var size = u32(h, 0)
            val t = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8L
            if (size == 1L) {
                size = u64(h, 8)
                header = 16
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < header) return null
            if (t == type) return (pos + header) to minOf(pos + size, end)
            pos += size
        }
        return null
    }

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, o: Int): Long = (u32(b, o) shl 32) or u32(b, o + 4)
}
