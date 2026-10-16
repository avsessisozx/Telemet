package io.github.avsessisozx.telemet

import android.app.Application
import io.github.avsessisozx.telemet.util.AppLog

class TelemetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
    }
}
