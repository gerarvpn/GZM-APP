package com.gzmnet.app.gzm

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.VpnService
import androidx.core.content.FileProvider
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.gzmnet.app.AppConfig
import com.gzmnet.app.BuildConfig
import com.gzmnet.app.core.LauncherManager
import com.gzmnet.app.handler.MmkvManager
import com.gzmnet.app.handler.SettingsManager
import com.gzmnet.app.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.HttpURLConnection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

object GzmSettings {
    @Volatile private var appSettingsReady = false

    fun initializeAppSettings(context: Context) {
        if (appSettingsReady) return
        synchronized(this) {
            if (appSettingsReady) return
            SettingsManager.initApp(context.applicationContext)
            appSettingsReady = true
        }
    }

    const val K_AUTO_WIFI = "gzm_auto_wifi"
    const val K_AUTO_MOBILE = "gzm_auto_mobile"

    fun bool(key: String): Boolean = MmkvManager.decodeSettingsBool(key, false)
    fun setBool(key: String, v: Boolean) {
        MmkvManager.encodeSettings(key, v)
    }

    /** GZMNET is a device-wide VPN app; do not inherit proxy-only/root launch mode from old settings. */
    fun ensureVpnMode() {
        if (MmkvManager.decodeSettingsString(AppConfig.PREF_MODE) != AppConfig.VPN) {
            MmkvManager.encodeSettings(AppConfig.PREF_MODE, AppConfig.VPN)
        }
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_MODE_ENABLE, false)) {
            MmkvManager.encodeSettings(AppConfig.PREF_ROOT_MODE_ENABLE, false)
        }
        // Disable the old root/hotspot gateway too; GZMNET only exposes explicit proxy sharing.
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_ROOT_LAN_SHARING, false)) {
            MmkvManager.encodeSettings(AppConfig.PREF_ROOT_LAN_SHARING, false)
        }
        // GZMNET uses Xray's native Android TUN inbound. This avoids the optional HEV JNI
        // bridge, which is not packaged for every ABI present in the universal APK.
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_USE_HEV_TUNNEL, true)) {
            MmkvManager.encodeSettings(AppConfig.PREF_USE_HEV_TUNNEL, false)
        }
        // Android DNS packets are captured by the VPN TUN. Route them through Xray's
        // configured DNS module instead of assuming every selected proxy supports UDP/53.
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LOCAL_DNS_ENABLED, false) != true) {
            MmkvManager.encodeSettings(AppConfig.PREF_LOCAL_DNS_ENABLED, true)
        }
    }

    fun proxySharing(): Boolean = MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING, false)
    fun proxyPort(): String = MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_PORT, AppConfig.PORT_SOCKS) ?: AppConfig.PORT_SOCKS
    fun proxyUser(): String = MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_USERNAME, "") ?: ""
    fun proxyPass(): String = MmkvManager.decodeSettingsString(AppConfig.PREF_SOCKS_PASSWORD, "") ?: ""

    fun setProxySharing(ctx: Context, on: Boolean) {
        MmkvManager.encodeSettings(AppConfig.PREF_PROXY_SHARING, on)
        LauncherManager.restartService(ctx)
    }

    /** Devuelve un mensaje de error o null si se guardó bien. */
    fun saveProxy(ctx: Context, port: String, user: String, pass: String): String? {
        val p = port.trim().toIntOrNull()
        if (p == null || p !in 1024..65535) return "El puerto debe estar entre 1024 y 65535."
        if ((user.isBlank()) != (pass.isBlank())) return "Pon usuario y contraseña, o deja los dos vacíos."
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PORT, p.toString())
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_USERNAME, user.trim())
        MmkvManager.encodeSettings(AppConfig.PREF_SOCKS_PASSWORD, pass.trim())
        LauncherManager.restartService(ctx)
        return null
    }

    fun localIp(): String = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress ?: "—"
    } catch (e: Exception) {
        "—"
    }
}

/** Instala los archivos geosite/geoip locales que Xray necesita antes de arrancar. */
object GzmGeoAssets {
    fun ensure(ctx: Context): Boolean = try {
        val app = ctx.applicationContext
        val dir = File(Utils.userAssetPath(app))
        val names = listOf("geosite.dat", "geoip.dat")
        names.forEach { name ->
            val file = File(dir, name)
            if (file.exists() && (!file.isFile || file.length() <= 1_000_000L)) file.delete()
        }
        SettingsManager.initAssets(app, app.assets)
        names.all { name -> File(dir, name).let { it.isFile && it.length() > 1_000_000L } }
    } catch (e: Exception) {
        Log.e("GzmGeoAssets", "No se pudieron preparar geosite.dat/geoip.dat", e)
        false
    }
}

/** Conexión automática según la red: WiFi y/o datos móviles. */
object GzmAuto {
    private const val ACTION = "com.gzmnet.vpn.NET_EVENT"
    private const val TAG = "GzmAuto"
    @Volatile private var legacyCallback: ConnectivityManager.NetworkCallback? = null

    private fun pending(ctx: Context): PendingIntent {
        val i = Intent(ctx, GzmNetReceiver::class.java).setAction(ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(ctx.applicationContext, 7, i, flags)
    }

    /** Registra o quita la escucha de red según los interruptores. Es seguro llamarla varias veces. */
    fun apply(ctx: Context) {
        val app = ctx.applicationContext
        try {
            GzmSettings.initializeAppSettings(app)
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val enabled = GzmSettings.bool(GzmSettings.K_AUTO_WIFI) || GzmSettings.bool(GzmSettings.K_AUTO_MOBILE)
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            if (Build.VERSION.SDK_INT >= 26) {
                runCatching { cm.unregisterNetworkCallback(pending(app)) }
                if (enabled) cm.registerNetworkCallback(req, pending(app))
            } else {
                legacyCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
                legacyCallback = null
                if (enabled) {
                    val cb = object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            thread { handle(app) }
                        }
                    }
                    cm.registerNetworkCallback(req, cb)
                    legacyCallback = cb
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "apply", e)
        }
    }

    @Suppress("DEPRECATION")
    fun isRunning(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val names = setOf(
            "com.gzmnet.app.service.CoreVpnService",
            "com.gzmnet.app.service.CoreProxyOnlyService",
            "com.gzmnet.app.service.CoreRootService"
        )
        am.getRunningServices(100).any { it.service.className in names }
    } catch (e: Exception) {
        false
    }

    /** Decide si hay que conectar con la red actual. Nunca lanza excepciones. */
    @Suppress("DEPRECATION")
    fun handle(ctx: Context) {
        try {
            val wifiOpt = GzmSettings.bool(GzmSettings.K_AUTO_WIFI)
            val mobOpt = GzmSettings.bool(GzmSettings.K_AUTO_MOBILE)
            if (!wifiOpt && !mobOpt) return
            if (!GzmPanel.hasToken()) return
            // Recover server entries from the encrypted config snapshot before checking whether
            // an auto-connect can proceed; MMKV's selected-profile index may need rebuilding.
            GzmPanel.ensureProfile()
            if (!GzmPanel.hasConfig() || MmkvManager.getSelectServer().isNullOrEmpty()) return
            if (!GzmGeoAssets.ensure(ctx)) {
                Log.e(TAG, "No se puede conectar: faltan geosite.dat/geoip.dat en los assets locales")
                return
            }
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            var wifi = false
            var cell = false
            for (n in cm.allNetworks) {
                val c = cm.getNetworkCapabilities(n) ?: continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) wifi = true
                if (c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) cell = true
            }
            val want = shouldAutoConnectOnGzmNetwork(wifiOpt, mobOpt, wifi, cell)
            if (!want || isRunning(ctx)) return
            if (VpnService.prepare(ctx) != null) return   // sin permiso de VPN no se puede iniciar en segundo plano
            GzmSettings.ensureVpnMode()
            GzmPanel.ensureProfile()
            LauncherManager.startService(ctx)
        } catch (e: Exception) {
            Log.e(TAG, "handle", e)
        }
    }
}

class GzmNetReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val pr = goAsync()
        thread {
            try {
                GzmAuto.handle(context.applicationContext)
            } finally {
                pr.finish()
            }
        }
    }
}

class GzmBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val a = intent?.action
        if (a != Intent.ACTION_BOOT_COMPLETED && a != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pr = goAsync()
        thread {
            try {
                if (shouldCleanGzmUpdateCacheAfter(a)) {
                    GzmUpdate.cleanupAfterSuccessfulInstall(context.applicationContext)
                }
                GzmSettings.initializeAppSettings(context.applicationContext)
                // Rebuild the selected server profile from its encrypted local snapshot after reboot.
                GzmPanel.ensureProfile()
                GzmAuto.apply(context.applicationContext)
                if (GzmPanel.hasToken()) GzmSyncWorker.schedule(context.applicationContext)
                if (a == Intent.ACTION_MY_PACKAGE_REPLACED && GzmPanel.reconnectAfterUpdate()) {
                    // An APK replacement stops Android's VPN service. Restore the previous user intent
                    // using the cached profile/token; no token rotation or config download is required.
                    if (GzmPanel.hasToken() && GzmPanel.hasConfig() && GzmGeoAssets.ensure(context.applicationContext) && VpnService.prepare(context.applicationContext) == null) {
                        GzmSettings.ensureVpnMode()
                        GzmPanel.ensureProfile()
                        LauncherManager.startService(context.applicationContext)
                    }
                }
                if (a != Intent.ACTION_MY_PACKAGE_REPLACED) GzmAuto.handle(context.applicationContext)
            } finally {
                pr.finish()
            }
        }
    }
}

data class GzmRelease(val build: Int, val name: String)

internal fun shouldCleanGzmUpdateCacheAfter(action: String?): Boolean =
    action == "android.intent.action.MY_PACKAGE_REPLACED"

internal fun parseGzmReleaseBuild(name: String): Int? =
    Regex("""(?i)\bbuild\s+(\d+)\b""").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()

internal fun isGzmUpdateDownloadComplete(receivedBytes: Long, expectedBytes: Long): Boolean =
    receivedBytes > 0L && (expectedBytes <= 0L || receivedBytes == expectedBytes)

internal fun isGzmApkZipHeader(header: ByteArray): Boolean =
    header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4b.toByte() &&
        ((header[2] == 0x03.toByte() && header[3] == 0x04.toByte()) ||
            (header[2] == 0x05.toByte() && header[3] == 0x06.toByte()) ||
            (header[2] == 0x07.toByte() && header[3] == 0x08.toByte()))

internal fun canUseGzmPhysicalNetwork(isVpn: Boolean, hasInternet: Boolean): Boolean =
    !isVpn && hasInternet

internal fun gzmNetworkPreferenceScore(isValidated: Boolean, isWifiOrEthernet: Boolean, isCellular: Boolean): Int =
    (if (isValidated) 100 else 0) + (if (isWifiOrEthernet) 10 else 0) + (if (isCellular) 5 else 0)

internal fun isGzmUpdateBuildNewer(downloadedBuild: Int, installedBuild: Int): Boolean = downloadedBuild > installedBuild

internal fun shouldAutoConnectOnGzmNetwork(wifiEnabled: Boolean, mobileEnabled: Boolean, wifiAvailable: Boolean, mobileAvailable: Boolean): Boolean =
    (wifiEnabled && wifiAvailable) || (mobileEnabled && mobileAvailable)

/** Opens a control-plane request on an actual internet network, never on this app's VPN TUN. */
internal fun gzmOpenPhysicalConnection(context: Context, url: URL): HttpURLConnection {
    val connectivity = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return url.openConnection() as HttpURLConnection
    fun usable(network: Network): Boolean? {
        val caps = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull() ?: return null
        return canUseGzmPhysicalNetwork(
            isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        )
    }
    val active = runCatching { connectivity.activeNetwork }.getOrNull()
    val selected = runCatching { connectivity.allNetworks.toList() }.getOrDefault(emptyList())
        .mapNotNull { network ->
            val caps = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull() ?: return@mapNotNull null
            if (!canUseGzmPhysicalNetwork(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN), caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))) return@mapNotNull null
            network to gzmNetworkPreferenceScore(
                isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                isWifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
                isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            )
        }.maxByOrNull { it.second }?.first
    if (selected != null) return selected.openConnection(url) as HttpURLConnection
    if (active != null && usable(active) == true) return active.openConnection(url) as HttpURLConnection
    if (active != null && usable(active) == false) {
        val caps = runCatching { connectivity.getNetworkCapabilities(active) }.getOrNull()
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) {
            throw java.io.IOException("No se encontró una red física con internet para contactar el panel.")
        }
    }
    return url.openConnection() as HttpURLConnection
}

/** Actualización desde la propia app, usando el último Release de GitHub. */
object GzmUpdate {
    private const val REPO = "gerarvpn/GZM-APP"
    const val APK_URL = "https://github.com/$REPO/releases/latest/download/GZMNET.apk"
    private const val FILE = "GZMNET-update.apk"
    private const val PARTIAL_FILE = "$FILE.part"

    private class DownloadState {
        val progress = AtomicInteger(0)
        @Volatile var error: String? = null
    }

    private val nextDownloadId = AtomicLong(System.currentTimeMillis())
    private val downloads = ConcurrentHashMap<Long, DownloadState>()
    @Volatile private var latestFailure: String? = null

    fun latestFailure(): String? = latestFailure

    fun currentBuild(): Int = BuildConfig.VERSION_CODE % 1_000_000
    fun currentName(): String = BuildConfig.VERSION_NAME

    private fun openConnection(context: Context, url: URL): HttpURLConnection =
        gzmOpenPhysicalConnection(context, url)

    /** null = no se pudo consultar. Retries once over the best available physical network. */
    suspend fun latest(context: Context): GzmRelease? = withContext(Dispatchers.IO) {
        latestFailure = null
        var lastFailure = "GitHub no devolvió información de versión."
        for (attempt in 0..1) {
            var connection: HttpURLConnection? = null
            try {
                connection = openConnection(context.applicationContext, URL("https://api.github.com/repos/$REPO/releases/latest?gzm_attempt=$attempt"))
                connection.connectTimeout = 10000
                connection.readTimeout = 12000
                connection.useCaches = false
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                connection.setRequestProperty("User-Agent", "GZMNET-App")
                val code = connection.responseCode
                if (code != 200) {
                    lastFailure = "GitHub respondió HTTP $code."
                    if (attempt == 0) continue else break
                }
                val o = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val name = o.optString("name")
                val build = parseGzmReleaseBuild(name)
                if (build == null) {
                    lastFailure = "GitHub respondió, pero el nombre de la versión no contiene el número de build."
                    if (attempt == 0) continue else break
                }
                latestFailure = null
                return@withContext GzmRelease(build, name)
            } catch (e: Exception) {
                lastFailure = gzmUpdateFailureSummary(e)
                if (attempt == 1) break
            } finally {
                connection?.disconnect()
            }
        }
        latestFailure = lastFailure
        null
    }

    /**
     * Download inside GZMNET's own process instead of Android DownloadManager's system UID.
     * GZMNET's UID is deliberately excluded from its VPN so Xray's own upstream sockets do not
     * loop back into the TUN. DownloadManager uses a different UID, so its release download was
     * routed through that same tunnel and could stall/fail while the VPN was connected.
     */
    fun startDownload(ctx: Context): Long {
        val appContext = ctx.applicationContext
        val id = nextDownloadId.incrementAndGet()
        val state = DownloadState()
        downloads[id] = state
        thread(name = "gzm-update-download", isDaemon = true) {
            downloadApk(appContext, id, state)
        }
        return id
    }

    private fun downloadApk(context: Context, id: Long, state: DownloadState) {
        val target = File(context.cacheDir, FILE)
        val partial = File(context.cacheDir, PARTIAL_FILE)
        repeat(2) { attempt ->
            var connection: HttpURLConnection? = null
            try {
                // A fresh network-bound retry helps transient cellular handovers without
                // requiring the user to tap Download repeatedly.
                partial.delete()
                if (attempt == 0) target.delete()
                state.error = null
                state.progress.set(0)
                connection = openConnection(context, URL("$APK_URL?gzm_build=$id&attempt=$attempt"))
                connection.connectTimeout = 20000
                connection.readTimeout = 45000
                connection.instanceFollowRedirects = true
                connection.useCaches = false
                connection.setRequestProperty("User-Agent", "GZMNET-App")
                connection.setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) error("GitHub respondió HTTP $status al descargar el instalador.")
                val total = connection.contentLengthLong
                var received = 0L
                BufferedInputStream(connection.inputStream).use { input ->
                    FileOutputStream(partial).use { fileOutput ->
                        BufferedOutputStream(fileOutput).use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                output.write(buffer, 0, count)
                                received += count
                                if (total > 0) state.progress.set(((received * 100L) / total).toInt().coerceIn(0, 99))
                            }
                            output.flush()
                            fileOutput.fd.sync()
                        }
                    }
                }
                if (!partial.exists() || partial.length() != received || !isGzmUpdateDownloadComplete(received, total)) {
                    error("La descarga del APK llegó vacía o incompleta ($received de $total bytes).")
                }
                val apkHeader = ByteArray(4)
                partial.inputStream().use { input ->
                    if (input.read(apkHeader) != apkHeader.size || !isGzmApkZipHeader(apkHeader)) error("GitHub no devolvió un instalador APK válido.")
                }
                if (target.exists() && !target.delete()) error("No se pudo reemplazar el instalador anterior.")
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                state.error = null
                state.progress.set(100)
                return
            } catch (failure: Exception) {
                partial.delete()
                if (attempt == 0) {
                    state.error = null
                    state.progress.set(0)
                } else {
                    state.error = failure.message?.take(300) ?: failure.javaClass.simpleName
                    state.progress.set(-1)
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    /** Devuelve porcentaje 0..100, 100 = listo, -1 = falló, -2 = pendiente. */
    /** Rebuilds an in-memory completed-download handle after Activity/process recreation. */
    fun restoreCompletedDownload(ctx: Context): Long? {
        val apk = File(ctx.applicationContext.cacheDir, FILE)
        if (!apk.isFile || apk.length() < 4L) return null
        val header = ByteArray(4)
        val valid = runCatching { apk.inputStream().use { it.read(header) == header.size && isGzmApkZipHeader(header) } }.getOrDefault(false)
        if (!valid) return null
        val archive = runCatching { ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) }.getOrNull() ?: return null
        val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) (archive.longVersionCode % 1_000_000L).toInt() else archive.versionCode % 1_000_000
        if (!isGzmUpdateBuildNewer(build, currentBuild())) return null
        val id = nextDownloadId.incrementAndGet()
        downloads[id] = DownloadState().apply { progress.set(100) }
        return id
    }

    fun progress(ctx: Context, id: Long): Int = downloads[id]?.let { state ->
        if (state.error != null) -1 else state.progress.get()
    } ?: -1

    fun downloadError(id: Long): String? = downloads[id]?.error

    /** Called only after Android confirms GZMNET's package has been replaced. */
    fun cleanupAfterSuccessfulInstall(context: Context) {
        runCatching { File(context.applicationContext.cacheDir, FILE).delete() }
        runCatching { File(context.applicationContext.cacheDir, PARTIAL_FILE).delete() }
    }

    /** Remove interrupted download remnants, but never delete a completed APK awaiting install. */
    fun cleanupInterruptedDownload(context: Context) {
        runCatching { File(context.applicationContext.cacheDir, PARTIAL_FILE).delete() }
    }

    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** true = se abrió el instalador; false = falta permitir instalar apps de esta fuente. */
    fun install(ctx: Context, id: Long): Boolean {
        val state = downloads[id]
        val apk = File(ctx.applicationContext.cacheDir, FILE)
        if (state?.progress?.get() != 100 || !apk.isFile || apk.length() <= 0L) {
            throw IllegalStateException("No se encontró el APK completo. Descarga la actualización otra vez.")
        }
        if (!canInstall(ctx)) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.cache", apk)
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(installIntent)
        return true
    }
}
