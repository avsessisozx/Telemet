package io.github.avsessisozx.telemet

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.avsessisozx.telemet.service.GpsTracker
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * GPS teşhis testi. CI betiği emülatörün GERÇEK GPS donanımına (sahte konum değil) saniyede bir
 * konum yolluyor (`adb emu geo fix`). Burada uygulamanın kendi GpsTracker sınıfı kullanılır.
 *
 *  a) Gerçek GPS yolu: konum gelmeli.
 *  b) Kapanmamış "sahte konum" sağlayıcısı: kullanıcının günlüğündeki "0 GPS fixes" tablosu
 *     yeniden üretiliyor mu? Sağlayıcı kaldırılınca konum geri geliyor mu?
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class GpsSelfTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext
    private val main = Handler(Looper.getMainLooper())

    private fun sh(cmd: String): String {
        val pfd = inst.uiAutomation.executeShellCommand(cmd)
        val out = ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        report("sh> $cmd -> ${out.trim().take(300)}")
        return out
    }

    private fun report(msg: String) = Log.i("Telemet", "GPSTEST $msg")

    private fun prepare() {
        val p = app.packageName
        sh("pm grant $p android.permission.ACCESS_FINE_LOCATION")
        sh("pm grant $p android.permission.ACCESS_COARSE_LOCATION")
        sh("pm grant $p android.permission.ACCESS_BACKGROUND_LOCATION")
        sh("cmd location set-location-enabled true")
    }

    /** [seconds] boyunca GpsTracker'ı çalıştırır, gelen ölçüm sayısını ve uydu bilgisini döndürür. */
    private fun run(seconds: Long, label: String): Triple<Int, Int, Int> {
        val fixes = AtomicInteger()
        var tracker: GpsTracker? = null
        val started = CountDownLatch(1)
        main.post {
            tracker = GpsTracker(app) { f ->
                if (fixes.incrementAndGet() <= 3) report("$label fix lat=${f.latitude} lon=${f.longitude} v=${f.speedMs}")
            }
            report("$label gpsEnabled=${tracker!!.isGpsEnabled()} start=${tracker!!.start()}")
            started.countDown()
        }
        started.await(5, TimeUnit.SECONDS)
        Thread.sleep(seconds * 1000)
        var vis = 0
        var used = 0
        val stopped = CountDownLatch(1)
        main.post {
            vis = tracker!!.satellitesVisible
            used = tracker!!.satellitesUsed
            tracker!!.stop()
            stopped.countDown()
        }
        stopped.await(5, TimeUnit.SECONDS)
        report("$label RESULT ${seconds}s: fixes=${fixes.get()} satsVisible=$vis satsUsed=$used")
        return Triple(fixes.get(), vis, used)
    }

    @Test
    fun a_realGpsGivesFixes() {
        prepare()
        sh("cmd location providers remove-test-provider gps")
        val (fixes, _, _) = run(40, "REAL")
        assertTrue("Gerçek GPS'ten 40 sn'de hiç konum gelmedi", fixes >= 5)
    }

    @Test
    fun b_leftoverMockProviderBlocksGps() {
        prepare()
        // Sahte konum uygulaması GPS sağlayıcısını devralıp kapanmadan çıkarsa olan durum.
        sh("appops set com.android.shell android:mock_location allow")
        sh("cmd location providers add-test-provider gps")
        sh("cmd location providers set-test-provider-enabled gps true")
        val (blocked, _, _) = run(25, "MOCK_LEFT")
        sh("cmd location providers remove-test-provider gps")
        val (after, _, _) = run(25, "MOCK_REMOVED")
        report("SUMMARY leftover-mock fixes=$blocked, after-removal fixes=$after")
        assertTrue("Sahte sağlayıcı kaldırılınca konum geri gelmedi", after >= 5)
    }
}
