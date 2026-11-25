package com.v2ray.ang.root

import android.content.Context
import com.v2ray.ang.AppConfig
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.util.LogUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking


object RootLanSharing {

    private var lanSharingStarted = false
    private var lanShareJob: Job? = null

    /**
     * Optional root feature: share the proxy with tethered LAN/USB clients while the
     * device itself stays on the VpnService. Runs a dedicated client hev-socks5-tunnel
     * off the main thread so the su calls don't block service startup.
     * The cheap, usually-false preference is checked first so the common path
     * short-circuits before touching root state.
     */
    fun startClientSharing(context: Context): Boolean {
        if (!MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING)) return true
        if (lanShareJob?.isActive == true) return false

        // CoreVpnService runs in :daemon, so RootManager's in-memory cache from the UI
        // process is not shared with it. Probe root from this process before running the
        // private setup script; do not silently skip sharing because the other process cache
        // was empty.
        lanSharingStarted = true
        lanShareJob = CoroutineScope(Dispatchers.IO).launch {
            if (RootManager.cachedRoot() || RootManager.refresh()) {
                if (!RootProxyManager.startClientSharing(context)) {
                    LogUtil.e(AppConfig.TAG, "RootLanSharing: Could not route tethered clients through the VPN")
                }
            } else {
                LogUtil.w(AppConfig.TAG, "RootLanSharing: root permission unavailable; tethering remains unchanged")
            }
        }
        return true
    }

    /**
     * Remove LAN/tethering sharing rules + helper before stopping the core. Wait for the
     * async setup to finish first, otherwise a stop during setup tears down before the
     * rules are installed and they leak (orphan FORWARD/policy-routing rules + client tun).
     */
    fun stopClientSharing(context: Context) {
        if (!lanSharingStarted) return

        lanSharingStarted = false
        runBlocking { lanShareJob?.cancelAndJoin() }
        lanShareJob = null
        RootProxyManager.stop(context)
    }
}
