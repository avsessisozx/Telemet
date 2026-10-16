package io.github.avsessisozx.telemet

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.clearText
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.hasDescendant
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.avsessisozx.telemet.fit.FitEncoder
import io.github.avsessisozx.telemet.service.RideStore
import io.github.avsessisozx.telemet.ui.MainActivity
import io.github.avsessisozx.telemet.ui.OnboardingActivity
import io.github.avsessisozx.telemet.ui.LogActivity
import io.github.avsessisozx.telemet.util.Backup
import io.github.avsessisozx.telemet.util.LocaleHelper
import io.github.avsessisozx.telemet.util.Prefs
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.containsString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Uygulamayı gerçek bir Android emülatöründe baştan sona kullanır ve her ekranın görüntüsünü kaydeder.
 * Sıra önemli: testler isim sırasıyla çalışır ve bir öncekinin bıraktığı durumdan devam eder.
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class UiFlowTest {

    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app: Context = inst.targetContext
    private val pkg = app.packageName

    companion object {
        const val SHOTS = "/sdcard/Download/telemet-screens"
    }

    @get:Rule
    val watcher = object : TestWatcher() {
        override fun failed(e: Throwable?, description: Description) {
            try {
                shot("FAIL_${description.methodName}")
            } catch (_: Throwable) {
            }
        }
    }

    // ---------------------------------------------------------------- Yardımcılar

    private fun s(id: Int, vararg args: Any): String = app.getString(id, *args)

    private fun tag(t: String) = withTagValue(`is`(t as Any))

    private fun shell(cmd: String): String {
        val pfd = inst.uiAutomation.executeShellCommand(cmd)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /**
     * Ekran görüntüsünü /sdcard/Download/telemet-screens klasörüne yazar. Dosyayı test süreci değil
     * kabuk yazar; böylece uygulama verileri temizlense de görüntüler kaybolmaz.
     */
    private fun shot(name: String) {
        Thread.sleep(700)
        val bmp: Bitmap = inst.uiAutomation.takeScreenshot() ?: return
        val bytes = ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        shell("mkdir -p $SHOTS")
        // Komutlar kabuksuz çalıştığı için yönlendirme yok; "tee" girdiyi dosyaya yazar.
        // tee'nin çıktısı ayrı bir iş parçacığında okunur, yoksa boru dolup kilitlenir.
        val fds = inst.uiAutomation.executeShellCommandRw("tee $SHOTS/$name.png")
        val reader = Thread { ParcelFileDescriptor.AutoCloseInputStream(fds[0]).use { it.readBytes() } }.apply { start() }
        ParcelFileDescriptor.AutoCloseOutputStream(fds[1]).use { it.write(bytes) }
        reader.join(10_000)
    }

    /** Ana ekranı test eden testler önceki testin sonucuna bağlı kalmasın. */
    private fun ensureSetupDone() {
        inst.runOnMainSync { Prefs(app).onboardingDone = true }
    }

    /** Ekran gelene kadar (en fazla [timeoutMs]) dener. */
    private fun waitUntil(timeoutMs: Long = 15_000, check: () -> Unit) {
        val end = System.currentTimeMillis() + timeoutMs
        var last: Throwable? = null
        while (System.currentTimeMillis() < end) {
            try {
                check()
                return
            } catch (t: Throwable) {
                last = t
                Thread.sleep(250)
            }
        }
        throw last ?: AssertionError("timeout")
    }

    private fun launch(cls: Class<*>, extras: Intent.() -> Unit = {}) {
        app.startActivity(Intent(app, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK).apply(extras))
        Thread.sleep(1200)
    }

    private fun seedRide(id: String, startMs: Long, minutes: Int, lean: Boolean) {
        val out = ByteArrayOutputStream()
        var dist = 0.0
        val n = minutes * 60
        for (i in 0 until n) {
            val t = i.toDouble()
            val a = t / n * 2 * PI
            val speed = 18 + 9 * sin(t / 45)
            if (i > 0) dist += speed
            out.write(FitEncoder.encodeRecord(FitEncoder.Point(
                utcMillis = startMs + i * 1000L,
                latitude = 41.05 + 0.02 * sin(a) + 0.002 * sin(a * 9),
                longitude = 29.0 + 0.03 * cos(a),
                altitudeM = 60 + 25 * sin(t / 300),
                speedMs = speed,
                distanceM = dist,
                gradePercent = 1.0,
                leanDeg = if (lean) 38 * sin(t / 14) else 0.0,
                lateralG = if (lean) 0.7 * sin(t / 14) else 0.0,
                longitudinalG = if (lean) 0.5 * cos(t / 9) else 0.0,
            )))
        }
        val raw = out.toByteArray()
        RideStore.save(app, id, FitEncoder.summarize(raw)!!, null, null, raw)
    }

    // ---------------------------------------------------------------- Testler

    /** İlk açılış: izin vermeden tüm adımlar atlanır; ana ekranda kurulum uyarısı görünür. */
    @Test
    fun t01_firstLaunchSkipEverything() {
        inst.runOnMainSync { Backup.clearAppData(app) }
        launch(MainActivity::class.java)
        waitUntil { onView(withText(s(R.string.ob_welcome_title))).check(matches(isDisplayed())) }
        shot("01_welcome")
        onView(withText(s(R.string.ob_start))).perform(click())
        waitUntil { onView(withText(s(R.string.ob_loc_title))).check(matches(isDisplayed())) }
        shot("02_step_location")
        // Kalan izin adımlarını atla
        repeat(10) {
            val atDone = try {
                onView(withText(s(R.string.ob_done_title))).check(matches(isDisplayed()))
                true
            } catch (t: Throwable) {
                false
            }
            if (atDone) return@repeat
            onView(withText(s(R.string.skip))).perform(click())
            Thread.sleep(400)
        }
        onView(withText(s(R.string.ob_done_title))).check(matches(isDisplayed()))
        shot("03_done")
        onView(withText(s(R.string.ob_finish))).perform(click())
        waitUntil { onView(tag("setup_banner")).check(matches(isDisplayed())) }
        assertTrue(Prefs(app).onboardingDone)
        shot("04_ride_setup_banner")
    }

    /** İzinler verilince uyarı kaybolur; uygulama yeniden açılınca kurulum bir daha görünmez. */
    @Test
    fun t02_permissionsGrantedNoOnboardingAgain() {
        ensureSetupDone()
        for (p in listOf(
            "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT", "android.permission.POST_NOTIFICATIONS",
        )) shell("pm grant $pkg $p")
        shell("dumpsys deviceidle whitelist +$pkg")
        launch(MainActivity::class.java)
        waitUntil { onView(tag("start_button")).check(matches(isDisplayed())) }
        onView(withText(s(R.string.ob_welcome_title))).check(doesNotExist())
        onView(tag("setup_banner")).check(matches(org.hamcrest.Matchers.not(isDisplayed())))
        shot("05_ride_ready")
        onView(tag("sensors_header")).perform(scrollTo(), click())
        shot("06_ride_sensors_open")
        onView(tag("sensors_header")).perform(scrollTo(), click())
    }

    /** Sürüş listesi, ayrıntı sayfası, yeniden adlandırma ve paylaşım dosyaları. */
    @Test
    fun t03_ridesListAndDetail() {
        ensureSetupDone()
        val now = System.currentTimeMillis()
        seedRide("surus_test_a", now - 26 * 3600_000L, 35, lean = true)
        seedRide("surus_test_b", now - 2 * 3600_000L, 12, lean = false)
        launch(MainActivity::class.java) { putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_RIDES) }
        waitUntil { onView(tag("ride_surus_test_a")).check(matches(isDisplayed())) }
        shot("07_rides_list")

        onView(tag("ride_surus_test_a")).perform(scrollTo(), click())
        waitUntil { onView(tag("route_view")).check(matches(isDisplayed())) }
        shot("08_detail_top")
        onView(tag("detail_share")).perform(scrollTo())
        shot("09_detail_bottom")

        onView(tag("detail_rename")).perform(scrollTo(), click())
        onView(tag("rename_input")).perform(clearText(), typeText("Sunday ride"), closeSoftKeyboard())
        onView(withText(s(R.string.save))).inRoot(isDialog()).perform(click())
        waitUntil { onView(withText("Sunday ride")).check(matches(isDisplayed())) }
        assertEquals("Sunday ride", RideStore.get(app, "surus_test_a")!!.name)

        onView(tag("detail_share")).perform(scrollTo(), click())
        Thread.sleep(2500)
        val shared = File(app.cacheDir, "share").listFiles()?.map { it.name }.orEmpty()
        assertTrue("shared files: $shared", shared.any { it.endsWith(".fit") } && shared.any { it.endsWith(".gpx") })
        val fit = File(app.cacheDir, "share").listFiles()!!.first { it.name.endsWith(".fit") }.readBytes()
        assertEquals(0, FitEncoder.crc(0, fit, 0, fit.size))
        shot("10_share_sheet")
        shell("input keyevent 4")
        Thread.sleep(800)
    }

    /** Ayarlar ekranı ve birim değişimi (km ↔ mil). */
    @Test
    fun t04_settingsAndUnits() {
        ensureSetupDone()
        launch(MainActivity::class.java) { putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS) }
        waitUntil { onView(withText(s(R.string.section_recording))).check(matches(isDisplayed())) }
        shot("11_settings_top")
        onView(tag("clear_data")).perform(scrollTo())
        shot("12_settings_bottom")

        onView(withText(s(R.string.setting_units))).perform(scrollTo(), click())
        onView(withText(s(R.string.units_imperial))).inRoot(isDialog()).perform(click())
        assertTrue(Prefs(app).imperial)
        onView(tag("tab_1")).perform(click())
        waitUntil { onView(tag("total_distance")).check(matches(hasDescendant(withText(containsString(" " + s(R.string.unit_mi)))))) }
        shot("13_rides_imperial")
        inst.runOnMainSync { Prefs(app).imperial = false }
    }

    /** Tüm verileri temizle: iki onay, yedek, sıfırdan başlama; sonra yedekten geri yükleme. */
    @Test
    fun t05_clearDataWithBackupAndRestore() {
        assertEquals(2, RideStore.list(app).size)
        launch(MainActivity::class.java) { putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS) }
        waitUntil { onView(tag("clear_data")).perform(scrollTo()) }
        onView(tag("clear_data")).perform(click())
        onView(withText(s(R.string.clear_title1))).inRoot(isDialog()).check(matches(isDisplayed()))
        shot("14_clear_confirm1")
        onView(withText(s(R.string.btn_continue))).inRoot(isDialog()).perform(click())
        onView(withText(s(R.string.clear_title2))).inRoot(isDialog()).check(matches(isDisplayed()))
        shot("15_clear_confirm2_countdown")
        Thread.sleep(3500)
        onView(withText(s(R.string.clear_btn))).inRoot(isDialog()).perform(click())

        waitUntil(30_000) { onView(withText(containsString("Backup:"))).check(matches(isDisplayed())) }
        shot("16_cleared_welcome")
        assertEquals(0, RideStore.list(app).size)
        assertFalse(Prefs(app).onboardingDone)
        assertTrue(Backup.findLatest(app) != null)

        onView(withText(s(R.string.ob_restore))).perform(click())
        onView(withText(s(R.string.backup_restore))).inRoot(isDialog()).perform(click())
        waitUntil(30_000) { assertEquals(2, RideStore.list(app).size) }
        assertEquals("Sunday ride", RideStore.get(app, "surus_test_a")!!.name)
        Thread.sleep(1500)
        shot("17_after_restore")
        // Kurulumu bitir (izinler hâlâ verili olduğu için adımlar "izin verildi" görünür).
        repeat(10) {
            val atDone = try {
                onView(withText(s(R.string.ob_done_title))).check(matches(isDisplayed()))
                true
            } catch (t: Throwable) {
                false
            }
            if (atDone) return@repeat
            try {
                onView(withText(s(R.string.ob_next))).perform(click())
            } catch (t: Throwable) {
                onView(withText(s(R.string.skip))).perform(click())
            }
            Thread.sleep(500)
        }
        onView(withText(s(R.string.ob_finish))).perform(click())
        waitUntil { onView(tag("start_button")).check(matches(isDisplayed())) }
    }

    /** Diller: Türkçe, Arapça (sağdan sola), Japonca ve Almanca ekran görüntüleri. */
    @Test
    fun t06_languages() {
        ensureSetupDone()
        for (lang in listOf("tr", "ar", "ja", "de")) {
            inst.runOnMainSync { LocaleHelper.set(app, lang) }
            Thread.sleep(800)
            launch(MainActivity::class.java)
            Thread.sleep(1500)
            shot("2_${lang}_ride")
            launch(MainActivity::class.java) { putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_RIDES) }
            shot("2_${lang}_rides")
            launch(MainActivity::class.java) { putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_SETTINGS) }
            shot("2_${lang}_settings")
        }
        launch(OnboardingActivity::class.java)
        shot("2_de_onboarding")
        inst.runOnMainSync { LocaleHelper.set(app, "") }
        Thread.sleep(800)
    }

    /** Günlük sayfası açılır ve içerik gösterir. */
    @Test
    fun t07_logScreen() {
        ensureSetupDone()
        launch(LogActivity::class.java)
        waitUntil { onView(tag("log_text")).check(matches(isDisplayed())) }
        shot("30_log")
    }
}
