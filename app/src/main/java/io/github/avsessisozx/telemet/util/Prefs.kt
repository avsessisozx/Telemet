package io.github.avsessisozx.telemet.util

import android.content.Context
import android.content.SharedPreferences
import kotlin.random.Random

class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("telemet", Context.MODE_PRIVATE)

    /** Son bağlanılan kameranın Bluetooth adresi; yeniden bağlanmayı hızlandırır. */
    var lastCameraAddress: String?
        get() = sp.getString("last_camera_address", null)
        set(v) = sp.edit().putString("last_camera_address", v).apply()

    var lastCameraName: String?
        get() = sp.getString("last_camera_name", null)
        set(v) = sp.edit().putString("last_camera_name", v).apply()

    /** 10 Hz akıcı gönderim (DJI kumandası gibi). Kapalıysa yalnızca gerçek GPS ölçümleri gönderilir. */
    var smoothGps: Boolean
        get() = sp.getBoolean("smooth_gps", true)
        set(v) = sp.edit().putBoolean("smooth_gps", v).apply()

    /** Her sürüşü telefonda ayrıca FIT ve GPX olarak kaydet. Varsayılan: açık. */
    var backupEnabled: Boolean
        get() = sp.getBoolean("backup_enabled", true)
        set(v) = sp.edit().putBoolean("backup_enabled", v).apply()

    /** Telemet ekrandayken ve sürüş sürerken ekran kararmasın. */
    var keepScreenOn: Boolean
        get() = sp.getBoolean("keep_screen_on", true)
        set(v) = sp.edit().putBoolean("keep_screen_on", v).apply()

    /**
     * Kullanıcı Başlat'a bastı ve henüz Durdur'a basmadı. Sistem uygulamayı kapatırsa
     * servis bu bilgiyle kendini yeniden başlatır ve sürüşe kaldığı yerden devam eder.
     */
    var shouldRun: Boolean
        get() = sp.getBoolean("should_run", false)
        set(v) { sp.edit().putBoolean("should_run", v).commit() }

    /** Süren sürüşün kayıt kimliği (yeniden başlatmada aynı dosyaya devam etmek için). */
    var activeRideId: String?
        get() = sp.getString("active_ride_id", null)
        set(v) { sp.edit().putString("active_ride_id", v).commit() }

    /** Xiaomi'deki elle yapılan kurulum adımları (sistem bunları uygulamaya bildirmiyor). */
    var setupAutostartDone: Boolean
        get() = sp.getBoolean("setup_autostart", false)
        set(v) = sp.edit().putBoolean("setup_autostart", v).apply()

    var setupBatterySaverDone: Boolean
        get() = sp.getBoolean("setup_battery_saver", false)
        set(v) = sp.edit().putBoolean("setup_battery_saver", v).apply()

    /**
     * Kameraya kendimizi tanıtırken kullandığımız sabit, uydurma MAC adresi.
     * Android uygulamalara gerçek Bluetooth MAC adresini vermediği için bir kez üretilip saklanır;
     * böylece kamera bizi her seferinde aynı kumanda olarak tanır.
     */
    val remoteMac: ByteArray
        get() {
            val saved = sp.getString("remote_mac", null)
            if (saved != null && saved.length == 12) {
                return ByteArray(6) { saved.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            }
            val mac = Random.nextBytes(6)
            // Yerel olarak yönetilen, tekil (unicast) adres
            mac[0] = ((mac[0].toInt() and 0xFC) or 0x02).toByte()
            sp.edit().putString("remote_mac", mac.joinToString("") { "%02X".format(it) }).apply()
            return mac
        }

    fun forgetCamera() {
        sp.edit().remove("last_camera_address").remove("last_camera_name").apply()
    }
}
