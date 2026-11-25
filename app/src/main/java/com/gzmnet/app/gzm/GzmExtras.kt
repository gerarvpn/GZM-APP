package com.gzmnet.app.gzm

import android.app.ActivityManager
import android.app.DownloadManager
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
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.net.HttpURLConnection
import kotlin.concurrent.thread

object GzmSettings {
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
            if (!GzmPanel.hasToken() || !GzmPanel.hasConfig()) return
            if (!GzmGeoAssets.ensure(ctx)) {
                Log.e(TAG, "No se puede conectar: faltan geosite.dat/geoip.dat en los assets locales")
                return
            }
            val st = GzmPanel.loadStatus()
            if (st != null && !st.isActive) return
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
            val want = (wifiOpt && wifi) || (mobOpt && cell && !wifi)
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

/** Actualización desde la propia app, usando el último Release de GitHub. */
object GzmUpdate {
    private const val REPO = "gerarvpn/gzmnet-app"
    const val APK_URL = "https://github.com/$REPO/releases/latest/download/GZMNET.apk"
    private const val FILE = "GZMNET-update.apk"

    fun currentBuild(): Int = BuildConfig.VERSION_CODE % 1_000_000
    fun currentName(): String = BuildConfig.VERSION_NAME

    /** null = no se pudo consultar. */
    suspend fun latest(): GzmRelease? = withContext(Dispatchers.IO) {
        try {
            val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 12000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "GZMNET-App")
            if (c.responseCode != 200) return@withContext null
            val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val name = o.optString("name")
            val b = Regex("(\\d+)").findAll(name).lastOrNull()?.value?.toIntOrNull() ?: return@withContext null
            GzmRelease(b, name)
        } catch (e: Exception) {
            null
        }
    }

    fun startDownload(ctx: Context): Long {
        val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        File(dir, FILE).delete()
        val req = DownloadManager.Request(Uri.parse("$APK_URL?gzm_build=${System.currentTimeMillis()}"))
            .setTitle("GZMNET")
            .setDescription("Descargando actualización")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(ctx, android.os.Environment.DIRECTORY_DOWNLOADS, FILE)
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return dm.enqueue(req)
    }

    /** Devuelve porcentaje 0..100, 100 = listo, -1 = falló, -2 = pendiente sin datos. */
    fun progress(ctx: Context, id: Long): Int {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        dm.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) return -1
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            if (status == DownloadManager.STATUS_FAILED) return -1
            if (status == DownloadManager.STATUS_SUCCESSFUL) return 100
            val total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            return if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 99) else -2
        }
    }

    fun canInstall(ctx: Context): Boolean = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** true = se abrió el instalador; false = falta permitir instalar apps de esta fuente. */
    fun install(ctx: Context, id: Long): Boolean {
        val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val uri = dm.getUriForDownloadedFile(id)
            ?: throw IllegalStateException("No se encontró el APK descargado. Descarga la actualización otra vez.")
        if (!canInstall(ctx)) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(uri)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(installIntent)
        return true
    }
}
