package io.github.avsessisozx.telemet.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager

/** Uygulamanın ihtiyaç duyduğu izinlerin durumu. Kurulum sihirbazı, uyarı şeridi ve ayarlar aynı kontrolleri kullanır. */
object Permissions {

    val LOCATION = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    val BLUETOOTH: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    val NOTIFICATIONS: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    private fun granted(ctx: Context, perms: Array<String>) =
        perms.all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun hasLocation(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackgroundLocation(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBluetooth(ctx: Context) = granted(ctx, BLUETOOTH)
    fun hasNotifications(ctx: Context) = granted(ctx, NOTIFICATIONS)
    fun ignoringBattery(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) == true

    /** Konum ve Bluetooth olmadan uygulama hiç çalışamaz. */
    fun canRun(ctx: Context) = hasLocation(ctx) && hasBluetooth(ctx)

    val isXiaomi: Boolean
        get() = Build.MANUFACTURER.lowercase().let { it.contains("xiaomi") || it.contains("redmi") || it.contains("poco") }

    /** Kesintisiz kayıt için gereken her şey tamam mı (uyarı şeridi bunu gösterir). */
    fun allSet(ctx: Context, prefs: Prefs): Boolean =
        canRun(ctx) && hasBackgroundLocation(ctx) && hasNotifications(ctx) && ignoringBattery(ctx) &&
            (!isXiaomi || (prefs.setupAutostartDone && prefs.setupBatterySaverDone))
}
