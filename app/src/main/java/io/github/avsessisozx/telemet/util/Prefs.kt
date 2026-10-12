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

    /** Sürüşü yedek olarak GPX dosyasına kaydet. */
    var gpxEnabled: Boolean
        get() = sp.getBoolean("gpx_enabled", false)
        set(v) = sp.edit().putBoolean("gpx_enabled", v).apply()

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
