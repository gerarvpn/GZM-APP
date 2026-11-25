package com.gzmnet.app

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import com.gzmnet.app.handler.AppLocaleManager
import com.gzmnet.app.handler.MmkvManager

class AngApplication : Application() {
    private fun installGzmCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            runCatching { com.gzmnet.app.gzm.GzmDiagnostics.record(this, "Uncaught app error (${thread.name})", failure) }
            previous?.uncaughtException(thread, failure)
        }
    }

    companion object {
        lateinit var application: AngApplication
    }

    /**
     * Attaches the base context to the application.
     * @param base The base context.
     */
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base?.let(ContextCompat::getContextForLanguage))
        application = this
    }

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()
        installGzmCrashLogger()

        MmkvManager.initialize(this)

        AppLocaleManager.initialize(this)

        // Keep application startup lightweight: presets/profile migrations and WorkManager
        // initialization must not delay the first screen on slower Android TV hardware.
        val backgroundBootstrap = Runnable {
            runCatching { com.gzmnet.app.gzm.GzmWorkManagerRuntime.initialize(this) }
            runCatching { com.gzmnet.app.gzm.GzmSettings.initializeAppSettings(this) }
        }
        if (isBackgroundWorkerProcess()) {
            // WorkManager must be ready before Android instantiates a scheduled Worker.
            backgroundBootstrap.run()
        } else {
            Thread(backgroundBootstrap, "gzm-background-bootstrap")
                .apply { priority = Thread.NORM_PRIORITY - 1; start() }
        }

        // GZM's entry screen uses a fixed local palette; avoid opening encrypted legacy theme
        // settings on the application main thread before the first frame.
    }

    private fun isBackgroundWorkerProcess(): Boolean {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            manager?.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }?.processName
        }
        return processName == "${packageName}:bg"
    }
}
