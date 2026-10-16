package io.github.avsessisozx.telemet.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import io.github.avsessisozx.telemet.R
import io.github.avsessisozx.telemet.fit.FitEncoder
import io.github.avsessisozx.telemet.overlay.OverlayData
import io.github.avsessisozx.telemet.overlay.OverlayEncoder
import io.github.avsessisozx.telemet.overlay.OverlayRenderer
import io.github.avsessisozx.telemet.service.RideStore
import io.github.avsessisozx.telemet.util.AppLog
import io.github.avsessisozx.telemet.util.LocaleHelper
import io.github.avsessisozx.telemet.util.MediaSaver
import io.github.avsessisozx.telemet.util.Prefs
import io.github.avsessisozx.telemet.util.ShareProvider
import io.github.avsessisozx.telemet.util.Units
import io.github.avsessisozx.telemet.video.Mp4Info
import io.github.avsessisozx.telemet.video.VideoSync
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.text.DateFormat
import java.util.Date
import java.util.TimeZone
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Kameranın böldüğü video parçalarını sürüş kayıtlarıyla saate göre eşleştirir:
 * her parça için ayrı FIT + GPX, hepsi için tek dosya ya da yeşil zeminli göstergeli video üretir.
 * Videolar seçilerek ya da galeriden "Paylaş → Telemet" ile gelir.
 */
class VideoSyncActivity : Activity() {

    companion object {
        /** Test ve iç kullanım için: işlenecek video adresleri (ArrayList<Uri>). */
        const val EXTRA_URIS = "uris"
        private const val REQ_PICK = 41
    }

    private class Loaded(val uri: Uri, val clip: VideoSync.Clip, val width: Int, val height: Int)
    private class RideRaw(val ride: RideStore.Ride, val raw: ByteArray, val seconds: LongArray)

    private lateinit var ui: Ui
    private lateinit var units: Units
    private lateinit var prefs: Prefs
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var rides: List<RideRaw> = emptyList()
    private var ridesLoaded = false
    private var loaded: List<Loaded> = emptyList()
    private var result: VideoSync.Result? = null
    private var offsetS = 0
    private var busy = false
    @Volatile private var cancelled = false
    private var shareFiles: List<File> = emptyList()

    private lateinit var clipList: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var actions: LinearLayout
    private lateinit var offsetText: TextView
    private lateinit var cancelButton: View
    private lateinit var shareButton: View

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        prefs = Prefs(this)
        units = Units(this, prefs.imperial)
        Ui.styleWindow(this)
        setContentView(build())
        io.execute {
            val list = RideStore.list(this).mapNotNull { r ->
                val raw = RideStore.raw(this, r.id) ?: return@mapNotNull null
                val pts = FitEncoder.decodeRecords(raw)
                if (pts.isEmpty()) null else RideRaw(r, raw, LongArray(pts.size) { pts[it].fitTime + FitEncoder.FIT_EPOCH_S })
            }
            main.post {
                rides = list
                ridesLoaded = true
                refresh()
            }
        }
        handleIncoming(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncoming(intent)
    }

    override fun onDestroy() {
        cancelled = true
        io.shutdown()
        super.onDestroy()
    }

    private fun handleIncoming(intent: Intent?) {
        val uris = ArrayList<Uri>()
        if (intent == null) return
        @Suppress("DEPRECATION")
        when (intent.action) {
            Intent.ACTION_SEND -> (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris += it }
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris += it }
            else -> intent.getParcelableArrayListExtra<Uri>(EXTRA_URIS)?.let { uris += it }
        }
        if (uris.isNotEmpty()) readClips(uris)
    }

    // ---------------------------------------------------------------- Ekran

    private fun build(): View {
        val root = ui.vertical()
        root.addView(ui.toolbar(getString(R.string.vs_title)) { finish() })
        root.addView(ui.text(14f, Theme.MUTED, value = getString(R.string.vs_intro)).apply {
            setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(12))
        })
        root.addView(ui.button(getString(R.string.vs_pick), Theme.ACCENT) { pick() }.apply { tag = "vs_pick" })

        clipList = ui.vertical().apply { layoutParams = ui.matchWrap(8) }
        root.addView(clipList)

        status = ui.text(14f, Theme.TEXT).apply { setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(8)); tag = "vs_status" }
        root.addView(status)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(Theme.ACCENT)
            layoutParams = ui.matchWrap(8)
        }
        root.addView(progress)
        cancelButton = ui.button(getString(R.string.cancel), Theme.GREY_BUTTON) { cancelled = true }.apply {
            visibility = View.GONE
            tag = "vs_cancel"
        }
        root.addView(cancelButton)
        shareButton = ui.button(getString(R.string.btn_share), Theme.GREY_BUTTON) { share() }.apply {
            visibility = View.GONE
            tag = "vs_share"
        }
        root.addView(shareButton)

        actions = ui.vertical().apply { layoutParams = ui.matchWrap(0); visibility = View.GONE }
        // Saat düzeltmesi
        offsetText = ui.text(15f, Theme.TEXT, bold = true).apply { tag = "vs_offset_text" }
        actions.addView(ui.card().apply {
            addView(offsetText)
            addView(ui.horizontal().apply {
                setPadding(0, ui.dp(10), 0, 0)
                val steps = listOf(-10, -1, 1, 10)
                steps.forEachIndexed { i, d ->
                    addView(ui.smallButton(if (d > 0) "+$d" else "$d", Theme.GREY_BUTTON) { changeOffset(d) }.apply {
                        tag = "vs_offset_$d"
                        setPadding(0, 0, 0, 0)
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                            if (i < steps.size - 1) marginEnd = ui.dp(8)
                        }
                    })
                }
            })
            addView(ui.text(13f, Theme.MUTED, value = getString(R.string.vs_offset_hint)).apply { setPadding(0, ui.dp(6), 0, 0) })
        })
        actions.addView(ui.button(getString(R.string.vs_save_each), Theme.ACCENT) { saveEach() }.apply { tag = "vs_save_each" })
        actions.addView(ui.button(getString(R.string.vs_save_merged), Theme.GREY_BUTTON) { saveMerged() }.apply { tag = "vs_save_merged" })
        actions.addView(ui.button(getString(R.string.vs_overlay), Theme.GREY_BUTTON) { makeOverlays() }.apply { tag = "vs_overlay" })
        actions.addView(ui.text(13f, Theme.MUTED, value = getString(R.string.vs_overlay_hint)).apply {
            setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(16))
        })
        root.addView(actions)
        return ui.scrollPage(root)
    }

    private fun refresh() {
        val r = result
        clipList.removeAllViews()
        offsetText.text = getString(R.string.vs_offset, if (offsetS > 0) "+$offsetS" else "$offsetS")
        if (!ridesLoaded) return
        if (rides.isEmpty()) {
            status.text = getString(R.string.vs_no_rides)
            actions.visibility = View.GONE
            return
        }
        if (r == null) {
            actions.visibility = View.GONE
            return
        }
        val time: DateFormat = android.text.format.DateFormat.getTimeFormat(this)
        val rows = r.clips.mapIndexed { i, p ->
            val sub = when {
                p.endMs == 0L -> getString(R.string.vs_clip_unreadable)
                p.coveredMs == 0L -> getString(R.string.vs_clip_nomatch, time.format(Date(p.startMs)), time.format(Date(p.endMs)))
                else -> getString(
                    R.string.vs_clip_match, time.format(Date(p.startMs)), time.format(Date(p.endMs)),
                    (p.coverage * 100).roundToInt().coerceIn(1, 100),
                )
            }
            ui.row(p.clip.name, sub, units.duration(p.clip.durationMs / 1000)).also {
                it.subtitle.setTextColor(if (p.coveredMs > 0) Theme.GREEN else Theme.YELLOW)
                it.view.tag = "vs_clip_$i"
            }.view
        }
        clipList.addView(ui.group(*rows.toTypedArray()))
        actions.visibility = if (r.anyMatch) View.VISIBLE else View.GONE
        if (!busy) status.text = if (r.anyMatch) "" else getString(R.string.vs_none_matched)
    }

    private fun setBusy(on: Boolean, cancellable: Boolean = false) {
        busy = on
        cancelled = false
        progress.visibility = if (on) View.VISIBLE else View.GONE
        progress.isIndeterminate = on && !cancellable
        progress.progress = 0
        cancelButton.visibility = if (on && cancellable) View.VISIBLE else View.GONE
        for (i in 0 until actions.childCount) actions.getChildAt(i).isEnabled = !on
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ---------------------------------------------------------------- Video okuma

    private fun pick() {
        if (busy) return
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            AppLog.w("No file picker", e)
        }
    }

    @Deprecated("Activity API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isNotEmpty()) readClips(uris)
    }

    private fun readClips(uris: List<Uri>) {
        setBusy(true)
        status.text = getString(R.string.vs_reading)
        io.execute {
            val list = uris.mapNotNull { readClip(it) }.sortedWith(compareBy({ it.clip.creationS ?: Long.MAX_VALUE }, { it.clip.name }))
            main.post {
                if (isFinishing) return@post
                loaded = list
                setBusy(false)
                AppLog.i("Video match: ${list.size} video(s) read")
                recompute()
            }
        }
    }

    private fun readClip(uri: Uri): Loaded? {
        val name = displayName(uri)
        val info = try {
            contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).channel.use { ch ->
                    Mp4Info.read(object : Mp4Info.Source {
                        override val size: Long = ch.size()
                        override fun read(pos: Long, buf: ByteArray): Int {
                            val bb = ByteBuffer.wrap(buf)
                            var total = 0
                            while (bb.hasRemaining()) {
                                val n = ch.read(bb, pos + total)
                                if (n <= 0) break
                                total += n
                            }
                            return total
                        }
                    })
                }
            }
        } catch (e: Exception) {
            AppLog.w("Could not read video: $name", e)
            null
        }
        var w = 0
        var h = 0
        try {
            MediaMetadataRetriever().also { mmr ->
                try {
                    mmr.setDataSource(this, uri)
                    w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    val rot = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot == 90 || rot == 270) { val t = w; w = h; h = t }
                } finally {
                    mmr.release()
                }
            }
        } catch (_: Exception) {
        }
        if (info == null) {
            AppLog.w("No start time/duration in video: $name")
            return Loaded(uri, VideoSync.Clip(name, null, 0), w, h)
        }
        return Loaded(uri, VideoSync.Clip(name, info.creationS, info.durationMs), w, h)
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "video.mp4"
    }

    private fun recompute() {
        if (loaded.isEmpty()) {
            result = null
        } else {
            val tz = TimeZone.getDefault()
            // Süresi okunamayan video eşleştirilemez ama listede gösterilir.
            result = VideoSync.place(loaded.map { it.clip }, rides.map { it.seconds }, { tz.getOffset(it) }, offsetS)
            result?.let { r -> AppLog.i("Video match: time source ${r.source}, offset $offsetS s, " + r.clips.joinToString { "${it.clip.name} %d%%".format((it.coverage * 100).roundToInt()) }) }
        }
        refresh()
    }

    private fun changeOffset(d: Int) {
        if (busy) return
        offsetS = (offsetS + d).coerceIn(-3600, 3600)
        recompute()
    }

    // ---------------------------------------------------------------- Dosya üretme

    /** Videonun süresine düşen sürüş noktaları (birden çok sürüşe yayılabilir). */
    private fun rawFor(startMs: Long, endMs: Long): ByteArray {
        val parts = rides.filter { r ->
            r.seconds.isNotEmpty() && r.seconds.last() * 1000 >= startMs - 1000 && r.seconds.first() * 1000 <= endMs
        }.map { VideoSync.slice(it.raw, startMs, endMs) }.filter { it.isNotEmpty() }
        return if (parts.size == 1) parts[0] else VideoSync.concat(parts)
    }

    private fun baseName(name: String) = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9._ -]"), "_").ifEmpty { "video" }

    private fun matched(): List<VideoSync.Placed> = result?.clips?.filter { it.coveredMs > 0 }.orEmpty()

    private fun writeFitGpx(base: String, raw: ByteArray, startMs: Long, dir: File): Int {
        val fit = FitEncoder.buildFile(raw, startMs, prefs.mimoExtras) ?: return 0
        val gpx = RideStore.gpx(base, FitEncoder.decodeRecords(raw)).toByteArray(Charsets.UTF_8)
        var n = 0
        if (MediaSaver.saveDownload(this, "$base.fit", "application/vnd.ant.fit", fit) != null) n++
        if (MediaSaver.saveDownload(this, "$base.gpx", "application/gpx+xml", gpx) != null) n++
        File(dir, "$base.fit").writeBytes(fit)
        File(dir, "$base.gpx").writeBytes(gpx)
        return n
    }

    private fun saveEach() = runFiles { dir ->
        var n = 0
        for (p in matched()) {
            val raw = rawFor(p.startMs, p.endMs)
            if (raw.isNotEmpty()) n += writeFitGpx(baseName(p.clip.name), raw, p.startMs, dir)
        }
        n
    }

    private fun saveMerged() = runFiles { dir ->
        val m = matched()
        if (m.isEmpty()) return@runFiles 0
        val start = m.minOf { it.startMs }
        val end = m.maxOf { it.endMs }
        val base = baseName(m.first().clip.name) + if (m.size > 1) "_+${m.size - 1}" else ""
        val raw = rawFor(start, end)
        if (raw.isEmpty()) 0 else writeFitGpx(base, raw, start, dir)
    }

    private fun runFiles(work: (File) -> Int) {
        if (busy) return
        setBusy(true)
        shareButton.visibility = View.GONE
        io.execute {
            val dir = ShareProvider.freshDir(this)
            val n = try {
                work(dir)
            } catch (e: Exception) {
                AppLog.w("Could not create video files", e)
                -1
            }
            val files = dir.listFiles()?.sortedBy { it.name }.orEmpty()
            main.post {
                if (isFinishing) return@post
                setBusy(false)
                status.text = if (n < 0) getString(R.string.vs_failed) else getString(R.string.vs_saved, n)
                AppLog.i("Video match: saved $n file(s)")
                shareFiles = files
                shareButton.visibility = if (files.isNotEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun share() {
        if (shareFiles.isEmpty()) return
        ShareProvider.share(this, shareFiles, getString(R.string.vs_title), getString(R.string.share_chooser))
    }

    // ---------------------------------------------------------------- Göstergeli video

    /** Video boyutuna uygun, kodlayıcının kabul ettiği (16'nın katı) gösterge boyutu; uzun kenar en fazla 1920. */
    private fun overlaySize(w: Int, h: Int): Pair<Int, Int> {
        if (w <= 0 || h <= 0) return 1920 to 1080
        val scale = minOf(1.0, 1920.0 / maxOf(w, h))
        fun r16(v: Double) = (Math.round(v / 16.0) * 16).toInt().coerceAtLeast(256)
        return r16(w * scale) to r16(h * scale)
    }

    private fun makeOverlays() {
        if (busy) return
        val m = matched()
        if (m.isEmpty()) return
        setBusy(true, cancellable = true)
        shareButton.visibility = View.GONE
        progress.isIndeterminate = false
        io.execute {
            var made = 0
            var lastName = ""
            var failed = false
            for ((idx, p) in m.withIndex()) {
                if (cancelled) break
                val l = loaded.firstOrNull { it.clip === p.clip }
                val (w, h) = overlaySize(l?.width ?: 0, l?.height ?: 0)
                val raw = rawFor(p.startMs - 2000, p.endMs + 2000)
                val data = OverlayData(FitEncoder.decodeRecords(raw))
                if (data.isEmpty) continue
                val renderer = OverlayRenderer(w, h, units, data, p.startMs, p.endMs)
                val name = baseName(p.clip.name) + "_overlay.mp4"
                lastName = name
                val started = System.currentTimeMillis()
                AppLog.i("Overlay video: $name ${w}x$h, ${p.clip.durationMs / 1000} s")
                val uri = try {
                    MediaSaver.writeMovie(this, name) { pfd ->
                        OverlayEncoder(w, h).encode(
                            pfd.fileDescriptor, p.clip.durationMs,
                            drawFrame = { canvas, tMs -> renderer.draw(canvas, p.startMs + tMs, p.startMs) },
                            progress = { f ->
                                main.post {
                                    if (!isFinishing) {
                                        progress.progress = ((idx + f) / m.size * 1000).roundToInt()
                                        status.text = getString(R.string.vs_overlay_progress, name, (f * 100).roundToInt())
                                    }
                                }
                                !cancelled
                            },
                        )
                        !cancelled
                    }
                } catch (e: OverlayEncoder.Cancelled) {
                    null
                } catch (e: Throwable) {
                    AppLog.w("Overlay video failed", e)
                    failed = true
                    null
                }
                if (uri != null) {
                    made++
                    AppLog.i("Overlay video ready in ${(System.currentTimeMillis() - started) / 1000} s: Movies/Telemet/$name")
                } else if (failed) {
                    break
                }
            }
            main.post {
                if (isFinishing) return@post
                val wasCancelled = cancelled
                setBusy(false)
                status.text = when {
                    failed -> getString(R.string.vs_failed)
                    wasCancelled -> getString(R.string.vs_cancelled)
                    made > 0 -> getString(R.string.vs_overlay_done, if (made == 1) lastName else "$made")
                    else -> getString(R.string.vs_failed)
                }
            }
        }
    }
}
