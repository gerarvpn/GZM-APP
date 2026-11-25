package com.gzmnet.app.gzm

import android.content.Context
import android.provider.Settings
import android.util.Base64
import com.gzmnet.app.AppConfig
import com.gzmnet.app.core.LauncherManager
import com.gzmnet.app.handler.AngConfigManager
import com.gzmnet.app.handler.MmkvManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal fun shouldRestoreGzmProfile(hasUsableSelectedProfile: Boolean, hasEncryptedConfigSnapshot: Boolean): Boolean =
    !hasUsableSelectedProfile && hasEncryptedConfigSnapshot

internal fun isGzmConfigRefreshDue(lastSyncAt: Long, now: Long, intervalHours: Int, hasSavedConfig: Boolean): Boolean {
    if (lastSyncAt <= 0L) return !hasSavedConfig
    val intervalMs = intervalHours.coerceIn(1, 1440) * 60L * 60L * 1000L
    return now - lastSyncAt >= intervalMs
}

data class GzmToken(val baseUrl: String, val sub: String)

data class GzmStatus(
    val state: String,
    val message: String,
    val name: String,
    val expiry: String,
    val limitBytes: Long,
    val usedBytes: Long,
    val upBytes: Long,
    val downBytes: Long,
    val online: Boolean,
    val title: String,
    val checkedAt: Long,
    val rateUp: Long = 0,
    val rateDown: Long = 0,
    val configIntervalHours: Int = 12,
) {
    val isActive get() = state == "active"

    fun toJson(): String = JSONObject()
        .put("state", state).put("message", message).put("name", name).put("expiry", expiry)
        .put("limit", limitBytes).put("used", usedBytes).put("up", upBytes).put("down", downBytes)
        .put("online", online).put("title", title).put("checked", checkedAt)
        .put("rate_up", rateUp).put("rate_down", rateDown).put("config_interval_hours", configIntervalHours).toString()

    companion object {
        fun fromJson(s: String): GzmStatus? = try {
            val o = JSONObject(s)
            GzmStatus(
                o.optString("state"), o.optString("message"), o.optString("name"), o.optString("expiry"),
                o.optLong("limit"), o.optLong("used"), o.optLong("up"), o.optLong("down"),
                o.optBoolean("online"), o.optString("title"), o.optLong("checked"),
                o.optLong("rate_up"), o.optLong("rate_down"), o.optInt("config_interval_hours", 12)
            )
        } catch (e: Exception) {
            null
        }
    }
}

/** Resultado de una sincronización: [offline] = no hubo respuesta definitiva del panel. */
data class GzmSyncResult(val status: GzmStatus?, val offline: Boolean, val configChanged: Boolean = false, val error: String? = null)

object GzmPanel {
    private const val F_TOKEN = "gzm_token.bin"
    private const val F_STATUS = "gzm_status.bin"
    private const val F_CONFIG = "gzm_cfg.bin"
    private const val F_HASH = "gzm_cfg_hash.bin"
    private const val F_CONFIG_SYNCED = "gzm_cfg_synced.bin"
    private const val F_RECONNECT_UPDATE = "gzm_reconnect_update.bin"

    // ---------- token ----------
    fun parseToken(raw: String): GzmToken? {
        val t = raw.trim()
        if (!(t.startsWith("GZM.") || t.startsWith("GZM1.") || t.startsWith("GZM2."))) return null
        return try {
            val json = String(Base64.decode(t.substringAfter('.'), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
            val o = JSONObject(json)
            val u = o.getString("u").trimEnd('/')
            val s = o.getString("t")
            if (!u.startsWith("https://") || s.isBlank()) null else GzmToken(u, s)
        } catch (e: Exception) {
            null
        }
    }

    fun saveToken(raw: String) = GzmCrypto.writeSecure(F_TOKEN, raw.trim().toByteArray())
    fun loadTokenRaw(): String? = GzmCrypto.readSecure(F_TOKEN)?.let { String(it, Charsets.UTF_8).trim().takeIf { raw -> parseToken(raw) != null } }
    fun loadToken(): GzmToken? = loadTokenRaw()?.let { parseToken(it) }
    fun hasToken() = loadToken() != null
    fun loadStatus(): GzmStatus? = GzmCrypto.readSecure(F_STATUS)?.let { GzmStatus.fromJson(String(it)) }
    private fun saveStatus(s: GzmStatus) = GzmCrypto.writeSecure(F_STATUS, s.toJson().toByteArray())
    fun hasConfig(): Boolean = GzmCrypto.readSecure(F_CONFIG) != null
    fun lastConfigSyncAt(): Long = GzmCrypto.readSecure(F_CONFIG_SYNCED)?.let { String(it).toLongOrNull() } ?: 0L
    fun configRefreshDue(intervalHours: Int = loadStatus()?.configIntervalHours ?: 12): Boolean =
        isGzmConfigRefreshDue(
            lastSyncAt = lastConfigSyncAt(),
            now = System.currentTimeMillis(),
            intervalHours = intervalHours,
            hasSavedConfig = hasConfig(),
        )

    fun clearAll(ctx: Context) {
        LauncherManager.stopService(ctx)
        listOf(F_TOKEN, F_STATUS, F_CONFIG, F_HASH, F_CONFIG_SYNCED, F_RECONNECT_UPDATE).forEach { GzmCrypto.deleteSecure(it) }
        runCatching { MmkvManager.removeAllServer() }
    }

    fun deviceName(ctx: Context): String {
        val pm = ctx.applicationContext.packageManager
        val uiMode = ctx.applicationContext.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_TYPE_MASK
        val tv = pm.hasSystemFeature("android.software.leanback") || pm.hasSystemFeature("android.hardware.type.television") || uiMode == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION || !pm.hasSystemFeature("android.hardware.touchscreen")
        val model = (android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).trim()
        return (if (tv) "GZMNET TV · $model" else model).take(60)
    }

    @Synchronized
    fun deviceId(ctx: Context): String {
        // Keep the legacy random ID whenever Keystore can decrypt it. If app data was restored
        // without its Keystore key, fall back to Android's app-scoped stable ID, not a new UUID
        // on every launch; this keeps a package update from consuming another device slot.
        GzmCrypto.readSecure("gzm_dev.bin")?.let { return String(it) }
        val androidId = Settings.Secure.getString(ctx.applicationContext.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        val seed = if (androidId.isNotBlank()) androidId else java.util.UUID.randomUUID().toString()
        val id = MessageDigest.getInstance("SHA-256").digest(("gzmnet-device|" + seed).toByteArray()).joinToString("") { "%02x".format(it) }
        runCatching { GzmCrypto.writeSecure("gzm_dev.bin", id.toByteArray()) }
        return id
    }

    private const val K_RECONNECT_AFTER_UPDATE = "gzm_reconnect_after_update"

    /** This is a non-secret control flag; store it in MMKV to avoid synchronous Keystore work on tap. */
    fun setReconnectAfterUpdate(enabled: Boolean) = runCatching {
        MmkvManager.encodeSettings(K_RECONNECT_AFTER_UPDATE, if (enabled) "1" else "0")
    }

    // Migrate the previous encrypted flag once; if it did not exist, preserve the old default.
    fun reconnectAfterUpdate(): Boolean {
        MmkvManager.decodeSettingsString(K_RECONNECT_AFTER_UPDATE)?.let { return it == "1" }
        val legacy = GzmCrypto.readSecure(F_RECONNECT_UPDATE)?.let { String(it) == "1" } ?: true
        MmkvManager.encodeSettings(K_RECONNECT_AFTER_UPDATE, if (legacy) "1" else "0")
        return legacy
    }


    // ---------- red ----------
    private fun post(ctx: Context, token: GzmToken, refreshConfig: Boolean): Pair<Int, String> {
        val conn = URL(token.baseUrl + "/api/app/sync").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("User-Agent", "GZMNET-App/1")
            conn.setRequestProperty("X-GZM-Device", deviceId(ctx))
            conn.outputStream.use {
                it.write(
                    JSONObject().put("token", token.sub).put("device", deviceId(ctx))
                        .put("name", deviceName(ctx))
                        .put("config_hash", GzmCrypto.readSecure(F_HASH)?.let { String(it) } ?: "")
                        .put("status_only", !refreshConfig)
                        .toString().toByteArray()
                )
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return code to body
        } finally {
            conn.disconnect()
        }
    }

    private fun decryptConfig(sub: String, c: JSONObject): List<String> {
        val key = MessageDigest.getInstance("SHA-256").digest(("gzmnet-app-v1|$sub").toByteArray())
        val iv = Base64.decode(c.getString("iv"), Base64.DEFAULT)
        val ct = Base64.decode(c.getString("ct"), Base64.DEFAULT)
        val tag = Base64.decode(c.getString("tag"), Base64.DEFAULT)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val plain = String(cipher.doFinal(ct + tag), Charsets.UTF_8)
        val arr: JSONArray = JSONObject(plain).getJSONArray("links")
        return (0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun storedConfigHash(): String? {
        GzmCrypto.readSecure(F_HASH)?.let { return String(it) }
        return GzmCrypto.readSecure(F_CONFIG)?.let { bytes ->
            runCatching {
                val arr = JSONArray(String(bytes))
                sha((0 until arr.length()).map { arr.getString(it) }.filter { it.isNotBlank() }.joinToString("\n"))
            }.getOrNull()
        }
    }

    /** Importa los enlaces al almacén (cifrado) y selecciona el perfil. */
    private fun importLinks(links: List<String>): Boolean {
        val (count, _) = AngConfigManager.importBatchConfig(links.joinToString("\n"), AppConfig.DEFAULT_SUBSCRIPTION_ID, false)
        if (count <= 0) return false
        val current = MmkvManager.getSelectServer()
        val available = MmkvManager.decodeServerList(AppConfig.DEFAULT_SUBSCRIPTION_ID)
        val selected = selectUsableGzmProfile(current, available) {
            MmkvManager.decodeServerConfig(it) != null
        } ?: return false
        if (selected != current) MmkvManager.setSelectServer(selected)
        return MmkvManager.decodeServerConfig(selected) != null
    }

    /** Si el almacén de perfiles falta o está corrupto pero la config cifrada existe, la restaura sin internet. */
    fun ensureProfile() {
        val available = MmkvManager.decodeServerList(AppConfig.DEFAULT_SUBSCRIPTION_ID)
        val current = MmkvManager.getSelectServer()
        val selected = selectUsableGzmProfile(current, available) {
            MmkvManager.decodeServerConfig(it) != null
        }
        if (selected != null) {
            if (selected != current) MmkvManager.setSelectServer(selected)
            return
        }
        val stored = GzmCrypto.readSecure(F_CONFIG)
        if (!shouldRestoreGzmProfile(hasUsableSelectedProfile = false, hasEncryptedConfigSnapshot = stored != null)) return
        stored ?: return
        val links = runCatching {
            val arr = JSONArray(String(stored))
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrNull() ?: return
        importLinks(links)
    }

    /**
     * Consulta al panel. Solo una respuesta definitiva del servidor cambia el estado;
     * sin internet o con error del servidor no se toca nada (la config guardada sigue valiendo).
     */
    suspend fun sync(ctx: Context, refreshConfig: Boolean = true): GzmSyncResult = withContext(Dispatchers.IO) {
        val token = loadToken() ?: return@withContext GzmSyncResult(null, offline = true)
        val (code, body) = try {
            post(ctx, token, refreshConfig)
        } catch (e: Exception) {
            val detail = (e.message ?: e.javaClass.simpleName).take(180)
            return@withContext GzmSyncResult(loadStatus(), offline = true, error = detail)
        }
        val o = try {
            JSONObject(body)
        } catch (e: Exception) {
            return@withContext GzmSyncResult(loadStatus(), offline = true, error = "El panel respondió con HTTP $code")
        }
        if (code == 429 || code >= 500) return@withContext GzmSyncResult(loadStatus(), offline = true, error = o.optString("detail", o.optString("error", "HTTP $code")).take(180))
        val state = o.optString("state", if (o.optBoolean("ok")) "active" else "")
        if (!o.optBoolean("ok") && state != "notfound") return@withContext GzmSyncResult(loadStatus(), offline = true, error = o.optString("detail", o.optString("error", "HTTP $code")).take(180))
        val status = GzmStatus(
            state = state,
            message = o.optString("message"),
            name = o.optString("name"),
            expiry = o.optString("expiry"),
            limitBytes = o.optLong("limit_bytes"),
            usedBytes = o.optLong("used_bytes"),
            upBytes = o.optLong("up_bytes"),
            downBytes = o.optLong("down_bytes"),
            online = o.optBoolean("online"),
            title = o.optString("title"),
            checkedAt = System.currentTimeMillis(),
            rateUp = o.optLong("rate_up"),
            rateDown = o.optLong("rate_down"),
            configIntervalHours = o.optInt("interval", 12).coerceIn(1, 1440),
        )
        if (state == "notfound") {
            // El token fue revocado o reiniciado desde el panel: se borra todo lo guardado.
            clearAll(ctx)
            return@withContext GzmSyncResult(status, offline = false)
        }
        saveStatus(status)
        var changed = false
        var syncError: String? = null
        if (status.isActive) {
            if (o.has("config")) {
                try {
                    val links = decryptConfig(token.sub, o.getJSONObject("config"))
                    if (links.isEmpty()) throw IllegalStateException("El panel envió una lista de servidores vacía")
                    val h = sha(links.joinToString("\n"))
                    val old = storedConfigHash()
                    if (h != old || MmkvManager.decodeServerList(AppConfig.DEFAULT_SUBSCRIPTION_ID).isEmpty()) {
                        val shouldRestart = old != null && GzmAuto.isRunning(ctx)
                        if (importLinks(links)) {
                            GzmCrypto.writeSecure(F_CONFIG, JSONArray(links).toString().toByteArray())
                            GzmCrypto.writeSecure(F_HASH, h.toByteArray())
                            changed = old != null && old != h
                            if (shouldRestart && changed) LauncherManager.restartService(ctx)
                        } else {
                            throw IllegalStateException("La app no pudo interpretar los servidores enviados por el panel")
                        }
                    }
                } catch (e: Exception) {
                    syncError = (e.message ?: "No se pudo descifrar o importar la suscripción").take(180)
                }
            } else if (!hasConfig()) {
                syncError = "El panel no envió servidores para esta cuenta. Revisa que el cliente tenga servidores activos y vuelve a sincronizar."
            }
        }
        // A manual/scheduled check counts even if no server config is currently available;
        // this prevents hammering a disabled or serverless account while state polling continues.
        if ((refreshConfig || o.optBoolean("config_changed")) && syncError == null) {
            GzmCrypto.writeSecure(F_CONFIG_SYNCED, System.currentTimeMillis().toString().toByteArray())
        }
        if (!status.isActive) {
            setReconnectAfterUpdate(false)
            LauncherManager.stopService(ctx)
        }
        GzmSyncResult(status, offline = false, configChanged = changed, error = syncError)
    }
}
