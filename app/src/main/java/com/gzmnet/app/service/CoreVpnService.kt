package com.gzmnet.app.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Network
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import com.gzmnet.app.AppConfig
import com.gzmnet.app.AppConfig.LOOPBACK
import com.gzmnet.app.contracts.ServiceControl
import com.gzmnet.app.contracts.Tun2SocksControl
import com.gzmnet.app.core.CoreServiceManager
import com.gzmnet.app.handler.AppLocaleManager
import com.gzmnet.app.handler.MmkvManager
import com.gzmnet.app.handler.NotificationManager
import com.gzmnet.app.handler.SettingsManager
import com.gzmnet.app.gzm.GzmDiagnostics
import com.gzmnet.app.helper.MessageHelper
import com.gzmnet.app.util.LogUtil
import com.gzmnet.app.util.Utils
import java.lang.ref.SoftReference
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun shouldReuseEstablishedVpn(coreRunning: Boolean, interfaceReady: Boolean): Boolean =
    coreRunning && interfaceReady

internal fun shouldDeferTunnelClose(coreStartInProgress: Boolean, onCoreStartThread: Boolean): Boolean =
    coreStartInProgress && !onCoreStartThread

@SuppressLint("VpnServicePolicy")
class CoreVpnService : VpnService(), ServiceControl {
    private lateinit var mInterface: ParcelFileDescriptor
    private var isRunning = false
    @Volatile private var interfaceOpen = false
    private var tun2SocksService: Tun2SocksControl? = null
    private val isStartingLock = AtomicBoolean(false)
    private val coreStartInProgress = AtomicBoolean(false)
    private val stopAfterCoreStart = AtomicBoolean(false)
    private val queuedStop = AtomicBoolean(false)
    private val coreStartThread = AtomicReference<Thread?>()
    // Serialize disconnect behind an in-flight native start so Xray cannot observe a closed TUN fd.
    private val coreStartExecutor = Executors.newSingleThreadExecutor(ThreadFactory { task ->
        Thread(task, "gzm-xray-start").apply {
            priority = Thread.NORM_PRIORITY
            coreStartThread.set(this)
        }
    })

    override fun onCreate() {
        super.onCreate()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service created")
        val policy = StrictMode.ThreadPolicy.Builder().permitAll().build()
        StrictMode.setThreadPolicy(policy)
        CoreServiceManager.serviceControl = SoftReference(this)
    }

    override fun onRevoke() {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: Permission revoked")
        GzmDiagnostics.recordMessage(this, "VPN setup", "Android revocó el permiso del túnel VPN.")
        stopAllService()
    }

//    override fun onLowMemory() {
//        stopV2Ray()
//        super.onLowMemory()
//    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Removing GZMNET from Recents closes only its screen; Android's foreground VpnService
        // must keep routing traffic until the user explicitly presses Disconnect.
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: app task removed; keeping active VPN service")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        super.onDestroy()
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service destroyed")

        // Ensure VPN interface is properly closed when the service is destroyed without
        // going through stopAllService() (e.g. when killed unexpectedly). isRunning is
        // set to false at the start of stopAllService(), so this guard prevents a double-close.
        if (isRunning && !coreStartInProgress.get()) closeVpnInterface("onDestroy")

        unlockStart()
        coreStartExecutor.shutdownNow()
        NotificationManager.cancelNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationManager.ensureForeground()
        // Always-on VPN restarts from OS deliver intent.action == SERVICE_INTERFACE or null intent.
        // Reset any stuck start lock left by a killed process to allow setupVpnService() to run.
        val isSystemVpnStart = intent == null || intent.action == SERVICE_INTERFACE
        if (isSystemVpnStart) {
            unlockStart()
        }
        if (shouldReuseEstablishedVpn(CoreServiceManager.isRunning(), isRunning && ::mInterface.isInitialized)) {
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: Existing VPN session reused")
            MessageHelper.sendMsg2UI(this, AppConfig.MSG_STATE_RUNNING, "")
            return START_STICKY
        }
        if (!tryLockStart()) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: Start already in progress")
            return START_NOT_STICKY
        }
        LogUtil.i(AppConfig.TAG, "StartCore-VPN: Service command received, systemVpnStart=$isSystemVpnStart")
        if (!setupVpnService()) {
            unlockStart()
            // Stop service if setup fails to avoid infinite restart loops (START_STICKY)
            stopSelf()
            return START_NOT_STICKY
        }
        startService()
        return START_STICKY
    }

    override fun getService(): Service {
        return this
    }

    override fun startService() {
        try {
            coreStartExecutor.execute {
                coreStartInProgress.set(true)
                try {
                    if (stopAfterCoreStart.get() || !isRunning || !::mInterface.isInitialized) {
                        if (!stopAfterCoreStart.get()) stopAllServiceNow()
                        return@execute
                    }
                    if (!CoreServiceManager.startCoreLoop(mInterface)) {
                        LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to start core loop")
                        GzmDiagnostics.recordMessage(this@CoreVpnService, "Xray startup", "Xray no pudo iniciar el core loop.")
                        if (!stopAfterCoreStart.get()) stopAllServiceNow()
                        return@execute
                    }
                    // A queued explicit stop runs immediately after this task, never during Xray's fd open.
                } catch (failure: Throwable) {
                    val detail = failure.message?.take(180).orEmpty()
                    LogUtil.e(AppConfig.TAG, "StartCore-VPN: Core startup threw ${failure.javaClass.simpleName}: $detail", failure)
                    GzmDiagnostics.record(this, "VPN core startup", failure)
                    if (!stopAfterCoreStart.get()) stopAllServiceNow()
                    MessageHelper.sendMsg2UI(
                        this,
                        AppConfig.MSG_STATE_START_FAILURE,
                        detail.ifBlank { "Xray no pudo iniciar el túnel. Revisa el perfil del servidor e inténtalo otra vez." },
                    )
                } finally {
                    coreStartInProgress.set(false)
                }
            }
        } catch (failure: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Unable to queue Xray startup", failure)
            GzmDiagnostics.record(this, "Queue VPN startup", failure)
            stopAllService()
            MessageHelper.sendMsg2UI(
                this,
                AppConfig.MSG_STATE_START_FAILURE,
                "No se pudo iniciar el servicio Xray. Cierra y vuelve a abrir GZMNET.",
            )
        }
    }

    override fun stopService() {
        stopAllService(true)
    }

    override fun vpnProtect(socket: Int): Boolean {
        return protect(socket)
    }

    override fun setUnderlyingNetworks(networks: Array<Network>?): Boolean {
        return super<VpnService>.setUnderlyingNetworks(networks)
    }

    override fun attachBaseContext(newBase: Context?) {
        val context = newBase?.let(AppLocaleManager::localizedContext)
        super.attachBaseContext(context)
    }

    /**
     * Sets up the VPN service.
     * Prepares the VPN and configures it if preparation is successful.
     */
    private fun setupVpnService(): Boolean {
        val prepare = prepare(this)
        if (prepare != null) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Permission not granted")
            GzmDiagnostics.recordMessage(this, "VPN setup", "Android no concedió el permiso de VPN.")
            MessageHelper.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, "")
            return false
        }

        if (configureVpnService() != true) {
            LogUtil.e(AppConfig.TAG, "StartCore-VPN: Configuration failed")
            GzmDiagnostics.recordMessage(this, "VPN tunnel setup", "Android no pudo configurar la interfaz VPN.")
            return false
        }

        if (!runTun2socks()) {
            LogUtil.e(AppConfig.TAG, "VPN/HEV: tun2socks did not start; refusing to report a connected but non-forwarding VPN")
            GzmDiagnostics.recordMessage(this, "VPN tunnel setup", "El puente del túnel de tráfico no pudo iniciarse.")
            MessageHelper.sendMsg2UI(
                this,
                AppConfig.MSG_STATE_START_FAILURE,
                "El túnel de tráfico no pudo iniciarse. No se activó la VPN; vuelve a intentarlo y, si persiste, revisa la actualización de GZMNET."
            )
            stopAllService()
            return false
        }
        return true
    }

    /**
     * Configures the VPN service.
     * @return True if the VPN service was configured successfully, false otherwise.
     */
    private fun configureVpnService(): Boolean {
        val builder = Builder()

        // Configure network settings (addresses, routing and DNS)
        configureNetworkSettings(builder)

        // Configure app-specific settings (session name and per-app proxy)
        configurePerAppProxy(builder)

        // Close the old interface since the parameters have been changed.
        closeVpnInterface("before reconfigure")

        // Configure platform-specific features
        configurePlatformFeatures(builder)

        // Create a new interface using the builder and save the parameters
        try {
            mInterface = builder.establish()!!
            interfaceOpen = true
            isRunning = true
            return true
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to establish VPN interface", e)
            GzmDiagnostics.record(this, "Establish VPN interface", e)
            stopAllService()
            MessageHelper.sendMsg2UI(this, AppConfig.MSG_STATE_START_FAILURE, "")
        }
        return false
    }

    /**
     * Configures the basic network settings for the VPN.
     * This includes IP addresses, routing rules, and DNS servers.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configureNetworkSettings(builder: Builder) {
        val vpnConfig = SettingsManager.getCurrentVpnInterfaceAddressConfig()
        // Keep local networks reachable for LAN services and the local TV pairing flow;
        // all public IPv4 and IPv6 traffic is routed through the VPN below.
        val bypassLan = true

        // Configure IPv4 settings
        builder.setMtu(SettingsManager.getVpnMtu())
        builder.addAddress(vpnConfig.ipv4Client, 30)

        // Configure routing rules
        if (bypassLan) {
            AppConfig.ROUTED_IP_LIST.forEach {
                val addr = it.split('/')
                builder.addRoute(addr[0], addr[1].toInt())
            }
        } else {
            builder.addRoute("0.0.0.0", 0)
        }

        // GZMNET is a full-device VPN: capture IPv6 too, so IPv6-capable apps do not
        // silently bypass the tunnel when the user leaves the inherited IPv6 toggle off.
        builder.addAddress(vpnConfig.ipv6Client, 126)
        if (bypassLan) {
            builder.addRoute("2000::", 3) // Public IPv6 unicast
            builder.addRoute("64:ff9b::", 96) // Standard NAT64 prefix used by IPv6-only mobile networks
            builder.addRoute("fc00::", 18) // Xray-core fake-IP range
        } else {
            builder.addRoute("::", 0)
        }

        // Configure DNS servers
        //if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED) == true) {
        //  builder.addDnsServer(PRIVATE_VLAN4_ROUTER)
        //} else {
        SettingsManager.getVpnDnsServers().forEach {
            if (Utils.isPureIpAddress(it)) {
                builder.addDnsServer(it)
            }
        }

        //builder.setSession(V2RayServiceManager.getRunningServerName())
    }

    /**
     * Configures platform-specific VPN features for different Android versions.
     *
     * @param builder The VPN Builder to configure
     */
    private fun configurePlatformFeatures(builder: Builder) {
        // Android Q (API 29) and above: Configure metering and HTTP proxy
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_APPEND_HTTP_PROXY)) {
                builder.setHttpProxy(ProxyInfo.buildDirectProxy(LOOPBACK, SettingsManager.getHttpPort()))
            }
        }
    }

    /**
     * Android requires the VPN app's own sockets to bypass its TUN, or Xray's upstream
     * sockets are routed back into the same tunnel. The embedded core accepts the Android
     * TUN file descriptor but exposes no socket-protection callback, so exclude this UID.
     * All other installed apps continue to use the VPN routes.
     */
    private fun configurePerAppProxy(builder: Builder) {
        builder.addDisallowedApplication(packageName)
    }

    /**
     * Runs the tun2socks process.
     * Starts the tun2socks process with the appropriate parameters.
     */
    private fun runTun2socks(): Boolean {
        if (!SettingsManager.isUsingHevTun()) {
            tun2SocksService = null
            return true
        }
        val tunnel = TProxyService(
            context = applicationContext,
            vpnInterface = mInterface,
            isRunningProvider = { isRunning },
            restartCallback = { runTun2socks() }
        )
        tun2SocksService = tunnel
        val started = tunnel.startTun2Socks()
        if (!started) {
            tun2SocksService = null
            tunnel.stopTun2Socks()
        }
        return started
    }

    private fun stopAllService(isForced: Boolean = true) {
        if (shouldDeferTunnelClose(coreStartInProgress.get(), Thread.currentThread() === coreStartThread.get())) {
            stopAfterCoreStart.set(true)
            if (queuedStop.compareAndSet(false, true)) {
                runCatching {
                    coreStartExecutor.execute {
                        queuedStop.set(false)
                        stopAllServiceNow(isForced)
                    }
                }.onFailure {
                    queuedStop.set(false)
                    LogUtil.e(AppConfig.TAG, "StartCore-VPN: Failed to queue safe tunnel shutdown", it)
                }
            }
            return
        }
        stopAllServiceNow(isForced)
    }

    private fun stopAllServiceNow(isForced: Boolean = true) {
        stopAfterCoreStart.set(false)
//        val configName = defaultDPreference.getPrefString(PREF_CURR_CONFIG_GUID, "")
//        val emptyInfo = VpnNetworkInfo()
//        val info = loadVpnNetworkInfo(configName, emptyInfo)!! + (lastNetworkInfo ?: emptyInfo)
//        saveVpnNetworkInfo(configName, info)
        unlockStart()
        isRunning = false

        tun2SocksService?.stopTun2Socks()
        tun2SocksService = null


        CoreServiceManager.stopCoreLoop()

        if (isForced) {
            //stopSelf has to be called ahead of mInterface.close(). otherwise v2ray core cannot be stooped
            //It's strage but true.
            //This can be verified by putting stopself() behind and call stopLoop and startLoop
            //in a row for several times. You will find that later created v2ray core report port in use
            //which means the first v2ray core somehow failed to stop and release the port.
            stopSelf()

            // Add a small delay to allow the async core stop operation to complete
            // before closing the VPN interface, preventing a race condition that can
            // leave the VPN icon in the status bar after stopping the service.
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                LogUtil.w(AppConfig.TAG, "StartCore-VPN: Sleep interrupted", e)
            }

            closeVpnInterface("disconnect")
        }
    }

    @Synchronized
    private fun closeVpnInterface(reason: String) {
        if (!interfaceOpen || !::mInterface.isInitialized) return
        interfaceOpen = false
        try {
            mInterface.close()
            LogUtil.i(AppConfig.TAG, "StartCore-VPN: VPN interface closed ($reason)")
        } catch (e: Exception) {
            LogUtil.w(AppConfig.TAG, "StartCore-VPN: VPN interface close skipped ($reason)", e)
        }
    }

    fun tryLockStart(): Boolean {
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: tryLockStart: ${isStartingLock.get()}")
        return isStartingLock.compareAndSet(false, true)
    }

    fun unlockStart() {
        isStartingLock.set(false)
        LogUtil.w(AppConfig.TAG, "StartCore-VPN: unlockStart")
    }
}
