package com.gzmnet.app

import android.app.Application
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.work.Configuration
import androidx.work.WorkManager
import com.gzmnet.app.AppConfig.ANG_PACKAGE
import com.gzmnet.app.handler.AppLocaleManager
import com.gzmnet.app.handler.MmkvManager
import com.gzmnet.app.handler.SettingsManager
import com.gzmnet.app.ui.compose.ThemeManager

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

    private val workManagerConfiguration: Configuration = Configuration.Builder()
        .setDefaultProcessName("${ANG_PACKAGE}:bg")
        .build()

    /**
     * Initializes the application.
     */
    override fun onCreate() {
        super.onCreate()
        installGzmCrashLogger()

        MmkvManager.initialize(this)

        AppLocaleManager.initialize(this)

        // Initialize WorkManager with the custom configuration
        WorkManager.initialize(this, workManagerConfiguration)

        // Ensure critical preference defaults are present in MMKV early
        SettingsManager.initApp(this)

        // Initialize theme state from MMKV
        ThemeManager.refresh()
    }
}
