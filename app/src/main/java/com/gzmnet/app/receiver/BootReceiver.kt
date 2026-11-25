package com.gzmnet.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.gzmnet.app.AppConfig
import com.gzmnet.app.core.LauncherManager
import com.gzmnet.app.gzm.GzmPanel
import com.gzmnet.app.gzm.GzmSettings
import com.gzmnet.app.handler.MmkvManager
import com.gzmnet.app.util.LogUtil

class BootReceiver : BroadcastReceiver() {
    /**
     * This method is called when the BroadcastReceiver is receiving an Intent broadcast.
     * It handles BOOT_COMPLETED, LOCKED_BOOT_COMPLETED, and MY_PACKAGE_REPLACED.
     * If the conditions are met, it starts the V2Ray service.
     *
     * @param context The Context in which the receiver is running.
     * @param intent The Intent being received.
     */
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (context == null) return

        LogUtil.i(AppConfig.TAG, "BootReceiver received: $action")

        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // Continue
            }

            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                val userManager = context.getSystemService(Context.USER_SERVICE) as? UserManager
                if (userManager != null && !userManager.isUserUnlocked) {
                    LogUtil.w(AppConfig.TAG, "BootReceiver: User is locked, skipping auto start")
                    return
                }
            }

            else -> {
                LogUtil.w(AppConfig.TAG, "BootReceiver: Unhandled action: $action")
                return
            }
        }

        if (!MmkvManager.decodeStartOnBoot()) {
            LogUtil.i(AppConfig.TAG, "BootReceiver: Auto-start on boot is disabled")
            return
        }

        // Restore the selected profile from GZM's encrypted local snapshot before starting;
        // boot must not depend on a server-list download or the panel being reachable.
        runCatching {
            GzmSettings.initializeAppSettings(context.applicationContext)
            GzmSettings.ensureVpnMode()
            GzmPanel.ensureProfile()
        }.onFailure { LogUtil.e(AppConfig.TAG, "BootReceiver: Failed to restore saved GZM profile", it) }

        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            LogUtil.w(AppConfig.TAG, "BootReceiver: No server selected")
            return
        }

        LogUtil.i(AppConfig.TAG, "BootReceiver: Starting V2Ray service")
        // Use the profile already stored on this device. Server-list downloads are left to
        // explicit user refresh or the configured GZM interval; boot is not a refresh trigger.
        LauncherManager.startService(context)
    }
}
