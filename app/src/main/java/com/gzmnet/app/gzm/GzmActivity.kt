package com.gzmnet.app.gzm
import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.VpnService
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gzmnet.app.AppConfig
import com.gzmnet.app.R
import com.gzmnet.app.core.LauncherManager
import com.gzmnet.app.helper.MessageHelper
import com.gzmnet.app.dto.ConnectionTestResult
import com.gzmnet.app.helper.MessageHelper.EXTRA_REQUEST_ID
import com.gzmnet.app.handler.MmkvManager
import com.gzmnet.app.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
internal fun isPanelOfflineAfterFailures(failures: Int): Boolean = failures >= 2

internal fun shouldSendGzmPing(force: Boolean, hasPending: Boolean, pendingAgeMs: Long, sinceLastRequestMs: Long): Boolean =
    (force || !hasPending || pendingAgeMs >= 25_000L) && (force || sinceLastRequestMs >= 10_000L)

internal const val GZM_STARTUP_TIMEOUT_MS = 90_000L
internal fun hasGzmStartupTimedOut(startedAtMs: Long, nowMs: Long): Boolean =
    nowMs - startedAtMs >= GZM_STARTUP_TIMEOUT_MS
internal fun isGzmConnectionProbeFailure(delayMillis: Long): Boolean = delayMillis < 0L

data class GzmUi(
val hasToken: Boolean = false,
val status: GzmStatus? = null,
val hasConfig: Boolean = false,
val assetsReady: Boolean = false,
val initialized: Boolean = false,
val running: Boolean = false,
val starting: Boolean = false,
val syncing: Boolean = false,
val offline: Boolean = false,
val error: String? = null,
val autoWifi: Boolean = false,
val autoMobile: Boolean = false,
val proxyOn: Boolean = false,
val proxyPort: String = "10808",
val proxyUser: String = "",
val proxyPass: String = "",
val proxyMsg: String? = null,
val updText: String = "",
val updBusy: Boolean = false,
val updAvailable: Boolean = false,
val updProgress: Int = -1,
val pendingUpdateInstall: Boolean = false,
val appRateUp: Long = 0,
val appRateDown: Long = 0,
val serverPingMs: Long? = null,
) {
val canConnect: Boolean get() = hasToken && hasConfig && assetsReady && (offline || status == null || status.isActive)
}
class GzmViewModel(app: Application) : AndroidViewModel(app) {
private val _ui = MutableStateFlow(GzmUi())
val ui: StateFlow<GzmUi> = _ui.asStateFlow()
private var downloadId = -1L
private var pendingInstallId = -1L
private var installLaunchInProgress = false
@Volatile private var startAttemptId = 0L
private val panelSyncMutex = Mutex()
@Volatile private var lastConfigAttemptAt = 0L
@Volatile private var panelFailureCount = 0
@Volatile private var pingPendingId = ""
@Volatile private var lastPingRequestAt = 0L
@Volatile private var activityVisible = false
private val receiver = object : BroadcastReceiver() {
override fun onReceive(context: Context?, intent: Intent?) {
when (intent?.getIntExtra("key", 0)) {
AppConfig.MSG_GZM_TRAFFIC_SAMPLE -> {
val parts = intent?.getStringExtra("content")?.split(',', limit = 2).orEmpty()
val up = parts.getOrNull(0)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
val down = parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
_ui.update { it.copy(appRateUp = up, appRateDown = down) }
}
AppConfig.MSG_MEASURE_DELAY_RESULT -> {
val requestId = intent?.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
val result = intent?.getSerializableExtra("content") as? ConnectionTestResult
if (requestId == pingPendingId && result != null) {
pingPendingId = ""
if (isGzmConnectionProbeFailure(result.delayMillis)) {
GzmDiagnostics.recordMessage(app, "Conexión", "El túnel arrancó, pero la prueba del servidor no respondió: ${result.errorMessage}")
_ui.update { it.copy(serverPingMs = null, error = "La VPN está activa, pero el servidor no respondió a la prueba. Revisa la red o actualiza los servidores.") }
} else {
_ui.update { it.copy(serverPingMs = result.delayMillis, error = if (it.error?.startsWith("La VPN está activa") == true) null else it.error) }
}
}
}
AppConfig.MSG_MEASURE_DELAY_CANCEL -> {
if (intent?.getStringExtra(EXTRA_REQUEST_ID).orEmpty() == pingPendingId) pingPendingId = ""
}
AppConfig.MSG_STATE_RUNNING -> markRunning()
AppConfig.MSG_STATE_START_SUCCESS -> {
// Ignore a late startup callback after the user cancelled or the startup watchdog expired.
if (_ui.value.starting) markRunning()
}
AppConfig.MSG_STATE_NOT_RUNNING, AppConfig.MSG_STATE_STOP_SUCCESS -> {
_ui.update { it.copy(running = false, starting = false, appRateUp = 0, appRateDown = 0) }
}
AppConfig.MSG_STATE_START_FAILURE -> {
val detail = intent?.getStringExtra("content")?.trim().orEmpty()
val safeDetail = detail.ifBlank { "No se pudo conectar. Inténtalo otra vez." }
GzmDiagnostics.recordMessage(app, "VPN start failure", safeDetail)
_ui.update { it.copy(running = false, starting = false, error = safeDetail) }
}
}
}
}
init {
ContextCompat.registerReceiver(
app, receiver, IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY), Utils.receiverFlags()
)
MessageHelper.sendMsg2Service(app, AppConfig.MSG_REGISTER_CLIENT, "")
reload()
viewModelScope.launch {
delay(2_000)
while (true) {
val current = _ui.value
val interval = if (!activityVisible) 5 * 60_000L else if (panelFailureCount > 0) (30_000L shl (panelFailureCount - 1).coerceAtMost(3)).coerceAtMost(240_000L) else if (current.running) 30_000L else 120_000L
delay(interval)
if (activityVisible && GzmPanel.hasToken()) {
val now = System.currentTimeMillis()
val retryWindow = if (panelFailureCount > 0) 30_000L else 60_000L
if (GzmPanel.configRefreshDue(_ui.value.status?.configIntervalHours ?: 12) && now - lastConfigAttemptAt >= retryWindow) refresh() else refreshLiveStatus()
}
}
}
viewModelScope.launch {
while (true) {
delay(10_000L)
if (activityVisible && _ui.value.running) requestServerPingIfDue()
}
}
}
private fun loadSettings(): GzmUi.() -> GzmUi = {
copy(
autoWifi = GzmSettings.bool(GzmSettings.K_AUTO_WIFI),
autoMobile = GzmSettings.bool(GzmSettings.K_AUTO_MOBILE),
proxyOn = GzmSettings.proxySharing(),
proxyPort = GzmSettings.proxyPort(),
proxyUser = GzmSettings.proxyUser(),
proxyPass = GzmSettings.proxyPass(),
)
}
private suspend fun prepareGeoAssets(): Boolean = withContext(Dispatchers.IO) {
val startedAt = SystemClock.elapsedRealtime()
val ready = GzmGeoAssets.ensure(getApplication<Application>())
val elapsed = SystemClock.elapsedRealtime() - startedAt
if (elapsed >= 5_000L) GzmDiagnostics.recordMessage(getApplication(), "Rendimiento", "Preparar los datos locales de Xray tardó ${elapsed} ms.")
ready
}
private fun reload() {
// Render the cached account and connection screen immediately. Large Xray geo files and
// profile recovery are prepared in the background so opening GZMNET never waits on file I/O.
_ui.update {
it.copy(
    hasToken = GzmPanel.hasToken(),
    status = GzmPanel.loadStatus(),
    hasConfig = GzmPanel.hasConfig(),
    initialized = true,
).let(loadSettings())
}
viewModelScope.launch {
val profileStartedAt = SystemClock.elapsedRealtime()
withContext(Dispatchers.IO) { GzmSettings.ensureVpnMode() }
withContext(Dispatchers.IO) { GzmPanel.ensureProfile() }
val profileElapsed = SystemClock.elapsedRealtime() - profileStartedAt
if (profileElapsed >= 5_000L) GzmDiagnostics.recordMessage(getApplication(), "Rendimiento", "Recuperar el perfil guardado tardó ${profileElapsed} ms.")
val assetsReady = prepareGeoAssets()
_ui.update {
it.copy(
hasToken = GzmPanel.hasToken(), status = GzmPanel.loadStatus(),
hasConfig = GzmPanel.hasConfig(), assetsReady = assetsReady, initialized = true,
error = if (assetsReady) it.error else "Faltan los datos de geolocalización de Xray. Actualiza la app e inténtalo otra vez."
).let(loadSettings())
}
GzmAuto.apply(getApplication())
if (GzmPanel.hasToken()) refreshLiveStatus()
}
}
private fun markRunning() {
startAttemptId++
GzmPanel.setReconnectAfterUpdate(true)
_ui.update { it.copy(running = true, starting = false, error = null) }
if (activityVisible) {
MessageHelper.sendMsg2Service(getApplication(), AppConfig.MSG_GZM_TRAFFIC_START, "")
requestServerPingIfDue(force = true)
}
}
fun beginStart() {
val attempt = ++startAttemptId
val startedAt = SystemClock.elapsedRealtime()
_ui.update { it.copy(starting = true, running = false, error = null) }
viewModelScope.launch {
delay(GZM_STARTUP_TIMEOUT_MS)
if (startAttemptId == attempt && _ui.value.starting && hasGzmStartupTimedOut(startedAt, SystemClock.elapsedRealtime())) {
MessageHelper.sendMsg2Service(getApplication(), AppConfig.MSG_STATE_STOP, "")
GzmDiagnostics.recordMessage(getApplication(), "VPN startup timeout", "El túnel no confirmó el inicio en 90 segundos.")
startFailed("La VPN no logró arrancar en 90 segundos. Se canceló el intento; revisa internet y actualiza los servidores antes de probar otra vez.")
}
}
}
fun startFailed(message: String) { startAttemptId++; GzmPanel.setReconnectAfterUpdate(false); _ui.update { it.copy(starting = false, running = false, appRateUp = 0, appRateDown = 0, error = message) } }
fun stopRequested() { startAttemptId++; _ui.update { it.copy(starting = true, error = null) } }
fun stopFallback() { startAttemptId++; _ui.update { it.copy(running = false, starting = false, appRateUp = 0, appRateDown = 0) } }
fun refresh() {
if (!GzmPanel.hasToken()) return
viewModelScope.launch {
if (!panelSyncMutex.tryLock()) return@launch
try {
lastConfigAttemptAt = System.currentTimeMillis()
_ui.update { it.copy(syncing = true) }
val assetsReady = prepareGeoAssets()
if (!assetsReady) {
_ui.update { it.copy(syncing = false, assetsReady = false, error = "Faltan los datos de geolocalización de Xray. Actualiza la app e inténtalo otra vez.") }
return@launch
}
val r = GzmPanel.sync(getApplication(), refreshConfig = true)
if (r.offline) panelFailureCount = (panelFailureCount + 1).coerceAtMost(5) else panelFailureCount = 0
val stillHas = GzmPanel.hasToken()
_ui.update {
it.copy(
syncing = false,
offline = isPanelOfflineAfterFailures(panelFailureCount),
status = if (stillHas) (r.status ?: it.status) else null,
hasConfig = GzmPanel.hasConfig(),
assetsReady = assetsReady,
hasToken = stillHas,
error = if (r.offline) null else if (!stillHas) "Tu acceso fue revocado o reiniciado desde el panel." else (r.error ?: it.error),
)
}
} catch (e: Exception) {
panelFailureCount = (panelFailureCount + 1).coerceAtMost(5)
_ui.update { it.copy(syncing = false, offline = isPanelOfflineAfterFailures(panelFailureCount), error = null) }
} finally {
panelSyncMutex.unlock()
}
}
}
fun setActivityVisible(visible: Boolean) {
activityVisible = visible
_ui.update { if (visible) it else it.copy(appRateUp = 0, appRateDown = 0) }
MessageHelper.sendMsg2Service(getApplication(), if (visible) AppConfig.MSG_GZM_TRAFFIC_START else AppConfig.MSG_GZM_TRAFFIC_STOP, "")
if (visible && _ui.value.running) requestServerPingIfDue(force = true)
}
fun onNetworkAvailable() {
if (activityVisible) {
if (GzmPanel.hasToken() && GzmPanel.configRefreshDue(_ui.value.status?.configIntervalHours ?: 12)) refresh() else refreshLiveStatus()
}
}
private fun requestServerPingIfDue(force: Boolean = false) {
if (!activityVisible || !_ui.value.running) return
val now = System.currentTimeMillis()
val hasPending = pingPendingId.isNotEmpty()
val pendingAge = if (hasPending) now - lastPingRequestAt else Long.MAX_VALUE
val sinceLastRequest = if (lastPingRequestAt == 0L) Long.MAX_VALUE else now - lastPingRequestAt
if (!shouldSendGzmPing(force, hasPending, pendingAge, sinceLastRequest)) return
// A forced refresh or stale daemon response replaces the old request; late replies are ignored by ID.
pingPendingId = ""
val id = java.util.UUID.randomUUID().toString()
pingPendingId = id
lastPingRequestAt = now
runCatching { MessageHelper.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY, id) }
}
fun refreshLiveStatus() {
if (!GzmPanel.hasToken()) return
viewModelScope.launch {
if (!panelSyncMutex.tryLock()) return@launch
try {
val r = GzmPanel.sync(getApplication(), refreshConfig = false)
if (r.offline) panelFailureCount = (panelFailureCount + 1).coerceAtMost(5) else panelFailureCount = 0
val stillHas = GzmPanel.hasToken()
_ui.update { current ->
current.copy(
offline = isPanelOfflineAfterFailures(panelFailureCount),
status = if (stillHas) (r.status ?: current.status) else null,
hasConfig = GzmPanel.hasConfig(),
hasToken = stillHas,
error = if (!stillHas) "Tu acceso fue revocado o reiniciado desde el panel." else null,
)
}
} catch (_: Exception) {
panelFailureCount = (panelFailureCount + 1).coerceAtMost(5)
_ui.update { it.copy(offline = isPanelOfflineAfterFailures(panelFailureCount)) }
} finally {
panelSyncMutex.unlock()
}
}
}
fun activate(raw: String) {
val initialToken = GzmPanel.parseToken(raw)
if (initialToken == null) {
_ui.update { it.copy(error = "Token inválido. Verifica el token y vuelve a pegarlo.") }
return
}
viewModelScope.launch {
if (!panelSyncMutex.tryLock()) return@launch
try {
lastConfigAttemptAt = System.currentTimeMillis()
_ui.update { it.copy(syncing = true, error = null) }
val assetsReady = prepareGeoAssets()
if (!assetsReady) {
_ui.update { it.copy(syncing = false, assetsReady = false, error = "Faltan los datos de geolocalización de Xray. Reinstala la app actualizada e inténtalo otra vez.") }
return@launch
}
val resolvedToken = raw
withContext(Dispatchers.IO) { GzmPanel.saveToken(resolvedToken) }
val r = GzmPanel.sync(getApplication(), refreshConfig = true)
if (r.offline) panelFailureCount = (panelFailureCount + 1).coerceAtMost(5) else panelFailureCount = 0
val st = r.status
when {
r.offline && st == null -> {
GzmSyncWorker.schedule(getApplication())
_ui.update { it.copy(syncing = false, hasToken = true, status = GzmPanel.loadStatus(), hasConfig = GzmPanel.hasConfig(), assetsReady = assetsReady, initialized = true, offline = isPanelOfflineAfterFailures(panelFailureCount), error = null) }
}
st != null && st.state == "notfound" -> {
withContext(Dispatchers.IO) { GzmPanel.clearAll(getApplication()) }
_ui.update { it.copy(syncing = false, hasToken = false, initialized = true, error = "El token no existe, fue revocado o fue reiniciado.") }
}
st != null && st.state == "device_limit" -> {
withContext(Dispatchers.IO) { GzmPanel.clearAll(getApplication()) }
_ui.update { it.copy(syncing = false, hasToken = false, hasConfig = false, initialized = true, error = "Se alcanzó el límite de dispositivos de esta cuenta. Libera un espacio en el panel y vuelve a vincular la TV.") }
}
else -> {
GzmSyncWorker.schedule(getApplication())
_ui.update {
it.copy(syncing = false, hasToken = true, status = st ?: GzmPanel.loadStatus(), offline = isPanelOfflineAfterFailures(panelFailureCount), hasConfig = GzmPanel.hasConfig(), assetsReady = assetsReady, initialized = true, error = if (r.offline) null else r.error)
}
}
}
} catch (e: Exception) {
panelFailureCount = (panelFailureCount + 1).coerceAtMost(5)
_ui.update { it.copy(syncing = false, hasToken = GzmPanel.hasToken(), hasConfig = GzmPanel.hasConfig(), initialized = true, offline = isPanelOfflineAfterFailures(panelFailureCount), error = null) }
} finally {
panelSyncMutex.unlock()
}
}
}
fun logout() {
GzmPanel.setReconnectAfterUpdate(false)
// Stop now and clear the UI state optimistically; do not wait for the service broadcast,
// which can be lost while an updated process is reconnecting.
runCatching { LauncherManager.stopService(getApplication()) }
_ui.update { it.copy(running = false, starting = false, appRateUp = 0, appRateDown = 0, syncing = true, error = null) }
viewModelScope.launch {
withContext(Dispatchers.IO) { GzmPanel.clearAll(getApplication()) }
_ui.update { GzmUi(initialized = true, assetsReady = it.assetsReady, syncing = false).let(loadSettings()) }
}
}
fun setAutoWifi(on: Boolean) = safe {
GzmSettings.setBool(GzmSettings.K_AUTO_WIFI, on)
GzmAuto.apply(getApplication())
_ui.update { it.copy(autoWifi = on) }
}
fun setAutoMobile(on: Boolean) = safe {
GzmSettings.setBool(GzmSettings.K_AUTO_MOBILE, on)
GzmAuto.apply(getApplication())
_ui.update { it.copy(autoMobile = on) }
}
fun setProxy(on: Boolean) = safe {
GzmSettings.setProxySharing(getApplication(), on)
_ui.update { it.copy(proxyOn = on, proxyMsg = null) }
}
fun saveProxy(port: String, user: String, pass: String) = safe {
val err = GzmSettings.saveProxy(getApplication(), port, user, pass)
_ui.update {
if (err != null) it.copy(proxyMsg = err)
else it.copy(proxyPort = port.trim(), proxyUser = user.trim(), proxyPass = pass.trim(), proxyMsg = "Guardado")
}
}
private fun safe(block: () -> Unit) {
try {
block()
} catch (e: Exception) {
_ui.update { it.copy(proxyMsg = "No se pudo aplicar el cambio") }
}
}
fun checkUpdate() {
if (_ui.value.updBusy) return
viewModelScope.launch {
_ui.update { it.copy(updBusy = true, updText = "Buscando…", updAvailable = false) }
val rel = GzmUpdate.latest()
_ui.update {
when {
rel == null -> it.copy(updBusy = false, updText = "No se pudo comprobar. Revisa tu internet.")
rel.build > GzmUpdate.currentBuild() -> it.copy(updBusy = false, updAvailable = true, updText = "Nueva versión disponible: ${rel.name}")
else -> it.copy(updBusy = false, updText = "Ya tienes la última versión")
}
}
}
}
fun downloadUpdate() {
if (_ui.value.updBusy) return
viewModelScope.launch {
_ui.update { it.copy(updBusy = true, updProgress = 0, updText = "Descargando…", pendingUpdateInstall = false) }
pendingInstallId = -1L
try {
val id = withContext(Dispatchers.IO) { GzmUpdate.startDownload(getApplication()) }
downloadId = id
while (true) {
delay(500)
val p = withContext(Dispatchers.IO) { GzmUpdate.progress(getApplication(), id) }
if (p == -1) {
_ui.update { it.copy(updBusy = false, updProgress = -1, updText = "La descarga falló. Inténtalo otra vez.") }
return@launch
}
_ui.update { it.copy(updProgress = if (p < 0) 0 else p) }
if (p == 100) break
}
pendingInstallId = id
_ui.update { it.copy(pendingUpdateInstall = true, updBusy = false, updProgress = -1, updText = "Descarga completa. Abriendo el instalador…") }
launchPendingInstall(askPermission = true)
} catch (e: Exception) {
pendingInstallId = -1L
_ui.update { it.copy(updBusy = false, updProgress = -1, updText = e.message ?: "No se pudo descargar la actualización.") }
}
}
}
private fun launchPendingInstall(askPermission: Boolean) {
val id = pendingInstallId
if (id <= 0L || installLaunchInProgress) return
viewModelScope.launch {
installLaunchInProgress = true
try {
if (askPermission || GzmUpdate.canInstall(getApplication())) {
val opened = withContext(Dispatchers.IO) { GzmUpdate.install(getApplication(), id) }
if (opened) {
pendingInstallId = -1L
_ui.update { it.copy(pendingUpdateInstall = false, updText = "Confirma la instalación de GZMNET.") }
} else {
_ui.update { it.copy(pendingUpdateInstall = true, updText = "Permite instalar apps desde GZMNET. Al volver, abriré este mismo archivo automáticamente.") }
}
}
} catch (e: Exception) {
val message = e.message ?: "No se pudo abrir el instalador. Vuelve a descargar la actualización."
if (message.contains("No se encontró el APK descargado", ignoreCase = true)) {
pendingInstallId = -1L
_ui.update { it.copy(pendingUpdateInstall = false, updText = message) }
} else {
_ui.update { it.copy(pendingUpdateInstall = true, updText = message) }
}
} finally { installLaunchInProgress = false }
}
}
fun retryPendingUpdateInstall() = launchPendingInstall(askPermission = true)
fun resumePendingUpdateInstall() = launchPendingInstall(askPermission = false)
override fun onCleared() {
runCatching { MessageHelper.sendMsg2Service(getApplication(), AppConfig.MSG_UNREGISTER_CLIENT, "") }
runCatching { getApplication<Application>().unregisterReceiver(receiver) }
}
}
class GzmActivity : ComponentActivity() {
private val vm: GzmViewModel by viewModels()
private var connectivityManager: ConnectivityManager? = null
private val networkCallback = object : ConnectivityManager.NetworkCallback() {
override fun onAvailable(network: Network) { runOnUiThread { vm.onNetworkAvailable() } }
}
private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
if (it.resultCode == RESULT_OK) startVpnNow() else vm.startFailed("Se necesita permiso de VPN para conectar.")
}
private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
val uiMode = resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK
val isTv = packageManager.hasSystemFeature("android.software.leanback") ||
packageManager.hasSystemFeature("android.hardware.type.television") ||
uiMode == Configuration.UI_MODE_TYPE_TELEVISION ||
!packageManager.hasSystemFeature("android.hardware.touchscreen")
if (isTv) {
WindowCompat.setDecorFitsSystemWindows(window, false)
WindowInsetsControllerCompat(window, window.decorView).let { controller ->
controller.hide(WindowInsetsCompat.Type.systemBars())
controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
}
}
if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
if (GzmPanel.hasToken()) GzmSyncWorker.schedule(this)
connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
runCatching { connectivityManager?.registerDefaultNetworkCallback(networkCallback) }
setContent { GzmApp(vm, ::toggle, isTv) }
}
override fun onResume() {
super.onResume()
vm.setActivityVisible(true)
vm.resumePendingUpdateInstall()
vm.refreshLiveStatus()
}
override fun onPause() {
vm.setActivityVisible(false)
super.onPause()
}
override fun onDestroy() {
runCatching { connectivityManager?.unregisterNetworkCallback(networkCallback) }
connectivityManager = null
super.onDestroy()
}
private fun toggle() {
runCatching { GzmSettings.ensureVpnMode() }
val ui = vm.ui.value
if (ui.starting) {
GzmPanel.setReconnectAfterUpdate(false)
LauncherManager.stopService(this)
vm.stopFallback()
return
}
if (ui.running) {
GzmPanel.setReconnectAfterUpdate(false)
// Do not leave the power control disabled while waiting for an optional service broadcast.
// Android can keep the UI process alive across an APK update while the old VPN service exits.
vm.stopRequested()
LauncherManager.stopService(this)
Handler(Looper.getMainLooper()).postDelayed({
if (vm.ui.value.starting) vm.stopFallback()
}, 1200)
return
}
if (!ui.assetsReady || !ui.hasConfig) {
vm.startFailed(ui.error ?: "La cuenta todavía se está preparando. Espera un momento y vuelve a tocar conectar.")
if (GzmPanel.hasToken()) vm.refresh()
return
}
runCatching { GzmPanel.ensureProfile() }
if (!GzmPanel.hasConfig()) {
vm.startFailed("No hay servidores guardados para esta cuenta. Comprueba internet y pulsa Actualizar.")
vm.refresh()
return
}
if (!ui.offline && ui.status != null && !ui.status.isActive) {
vm.startFailed(ui.status.message.ifBlank { "La cuenta no está activa." })
return
}
GzmPanel.setReconnectAfterUpdate(true)
vm.beginStart()
val intent = VpnService.prepare(this)
if (intent == null) startVpnNow() else vpnPermission.launch(intent)
}
private fun startVpnNow() {
val ok = runCatching { LauncherManager.startServiceFromToggle(this) }.getOrElse {
vm.startFailed("No se pudo iniciar. Inténtalo otra vez."); false
}
if (!ok) {
vm.startFailed(if (GzmPanel.hasConfig() && MmkvManager.getSelectServer().isNullOrEmpty()) "No hay un servidor seleccionado en este dispositivo. Abre Ajustes, actualiza los servidores y vuelve a intentar." else "Android no pudo iniciar el servicio VPN. Comprueba el permiso de VPN y vuelve a intentar; tu cuenta y configuración se conservaron.")
}
}
}
private val Bg = Color(0xFF1B1C1F)
private val Card = Color(0xFF26272B)
private val Card2 = Color(0xFF303136)
private val Green = Color(0xFF22E07A)
private val Blue = Color(0xFF3D8BFF)
private val Red = Color(0xFFFF5468)
private val Amber = Color(0xFFFFC233)
private val Muted = Color(0xFFB4B8C2)
@Composable
private fun Modifier.tvFocus(shape: Shape): Modifier {
var focused by remember { mutableStateOf(false) }
return this
.onFocusChanged { focused = it.isFocused }
.border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
}
@Composable
fun GzmApp(vm: GzmViewModel, onToggle: () -> Unit, isTv: Boolean = false, onActivate: (String) -> Unit = vm::activate) {
val ui by vm.ui.collectAsState()
MaterialTheme(
colorScheme = darkColorScheme(primary = Blue, background = Bg, surface = Card, onSurface = Color.White, onBackground = Color.White)
) {
Surface(Modifier.fillMaxSize(), color = Bg) {
Box((if (isTv) Modifier else Modifier.systemBarsPadding()).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
Box((if (isTv) Modifier.fillMaxSize() else Modifier.widthIn(max = 560.dp).fillMaxSize())) {
when {
!ui.initialized -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) { Logo(82); Spacer(Modifier.height(14.dp)); Text("Preparando GZMNET…", color = Muted) }
else -> Shell(ui, vm, onToggle, isTv, onActivate)
}
}
}
}
}
}
@Composable
private fun Logo(size: Int) {
Image(painter = painterResource(R.drawable.gzm_logo), contentDescription = "GZMNET", modifier = Modifier.size(size.dp))
}
@Composable
private fun Shell(ui: GzmUi, vm: GzmViewModel, onToggle: () -> Unit, isTv: Boolean, onActivate: (String) -> Unit) {
var tab by remember { mutableStateOf(0) }
Box(Modifier.fillMaxSize()) {
Column(Modifier.fillMaxSize()) {
Box(Modifier.weight(1f)) {
if (tab == 0) HomeScreen(ui, onToggle, vm::refresh, onOpenSettings = { tab = 1 }) else SettingsScreen(ui, vm, isTv, onActivate)
}
Row(
Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(20.dp)).background(Card).padding(6.dp),
horizontalArrangement = Arrangement.spacedBy(6.dp)
) {
TabItem("Inicio", tab == 0, Modifier.weight(1f)) { tab = 0 }
TabItem("Ajustes", tab == 1, Modifier.weight(1f)) { tab = 1 }
}
}
if (ui.offline) OfflineDot(Modifier.align(Alignment.TopStart).padding(start = 8.dp, top = 8.dp))
}
}
@Composable
private fun OfflineDot(modifier: Modifier = Modifier) {
Box(modifier.size(10.dp).clip(CircleShape).background(Amber))
}
@Composable
private fun CreatorLink(modifier: Modifier = Modifier) {
val uriHandler = LocalUriHandler.current
Row(modifier.padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp)).tvFocus(RoundedCornerShape(12.dp)).clickable { runCatching { uriHandler.openUri("https://t.me/gerargzm") } }.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
Box(Modifier.size(22.dp).clip(CircleShape).background(Color(0xFF229ED9)), contentAlignment = Alignment.Center) {
Canvas(Modifier.size(13.dp)) {
val p = androidx.compose.ui.graphics.Path().apply {
moveTo(size.width * .05f, size.height * .43f); lineTo(size.width * .92f, size.height * .08f)
lineTo(size.width * .70f, size.height * .92f); lineTo(size.width * .48f, size.height * .62f)
lineTo(size.width * .35f, size.height * .75f); lineTo(size.width * .37f, size.height * .55f)
close()
}
drawPath(p, Color.White)
}
}
Text("  @gerargzm", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}
}
@Composable
private fun TabItem(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
val shape = RoundedCornerShape(16.dp)
Box(
modifier.height(48.dp).tvFocus(shape).clip(shape).background(if (selected) Blue.copy(alpha = 0.22f) else Color.Transparent).clickable(onClick = onClick),
contentAlignment = Alignment.Center
) { Text(label, color = if (selected) Color.White else Muted, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium) }
}
@Composable
private fun HomeScreen(ui: GzmUi, onToggle: () -> Unit, onRefresh: () -> Unit, onOpenSettings: () -> Unit) {
val st = ui.status
val blocked = !ui.offline && st != null && !st.isActive && st.state != "no_servers"
Column(
Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState()),
horizontalAlignment = Alignment.CenterHorizontally
) {
Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
Logo(38)
Spacer(Modifier.size(10.dp))
Text("GZMNET", fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp, modifier = Modifier.weight(1f))
TextButton(onClick = onRefresh, modifier = Modifier.tvFocus(RoundedCornerShape(12.dp))) { Text(if (ui.syncing) "…" else "Actualizar servidores", color = Blue) }
}
Spacer(Modifier.height(20.dp))
PowerButton(running = ui.running, enabled = ui.canConnect || ui.running, onClick = onToggle, starting = ui.starting)
if (!ui.hasToken) {
Spacer(Modifier.height(10.dp))
Text("Vincula una cuenta desde Ajustes para activar la VPN. Puedes explorar las opciones mientras tanto.", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
TextButton(onClick = onOpenSettings) { Text("Vincular cuenta", color = Blue) }
}
if (ui.running) {
Spacer(Modifier.height(10.dp))
Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card2).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
Column(Modifier.weight(1f)) {
Text("Tráfico actual", color = Muted, fontSize = 11.sp)
Text("↑ ${fmtRate(ui.appRateUp)}   ↓ ${fmtRate(ui.appRateDown)}", color = Green, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}
Text(ui.serverPingMs?.let { "Ping del servidor · ${it} ms" } ?: "Ping del servidor · —", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
}
}
Spacer(Modifier.height(18.dp))
Text(
when {
ui.running -> "Conectado"
ui.starting -> "Conectando · toca para cancelar"
blocked -> st?.message.orEmpty()
st?.state == "no_servers" -> "Sin servidores disponibles"
!ui.assetsReady -> "Preparando datos de conexión…"
!ui.hasConfig -> "Sin configuración todavía"
else -> "Toca para conectar"
},
fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
color = if (ui.running) Green else if (blocked) Red else Color.White
)
if (!ui.offline && !ui.error.isNullOrBlank()) {
Spacer(Modifier.height(8.dp))
Text(ui.error, modifier = Modifier.fillMaxWidth(), color = Red, fontSize = 12.sp, textAlign = TextAlign.Center)
}
Spacer(Modifier.height(24.dp))
if (blocked) {
Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Red.copy(alpha = 0.16f)).padding(16.dp)) {
Text(st?.message.orEmpty(), color = Red, fontWeight = FontWeight.Bold)
Text("Contacta a tu proveedor para renovar o reactivar tu cuenta.", color = Muted, fontSize = 13.sp)
}
Spacer(Modifier.height(14.dp))
}
Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(18.dp)) {
val (label, color) = when (st?.state) {
"active" -> "Activo" to Green
"expired" -> "Vencido" to Red
"disabled" -> "Desactivado" to Red
"exhausted" -> "Sin cupo de datos" to Red
"device_limit" -> "Límite de dispositivos" to Red
"no_servers" -> "Sin servidores disponibles" to Amber
else -> "Sin verificar" to Amber
}
Row(verticalAlignment = Alignment.CenterVertically) {
Box(Modifier.size(10.dp).clip(CircleShape).background(color))
Spacer(Modifier.size(8.dp))
Text(label, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.weight(1f))

}
Spacer(Modifier.height(14.dp))
InfoRow("Cuenta", st?.name?.ifBlank { "—" } ?: "—")
InfoRow("Vence", st?.expiry?.ifBlank { "Sin vencimiento" } ?: "—")
Spacer(Modifier.height(10.dp))
val limit = st?.limitBytes ?: 0L
val used = st?.usedBytes ?: 0L
if (limit > 0) {
LinearProgressIndicator(
progress = { (used.toFloat() / limit.toFloat()).coerceIn(0f, 1f) },
modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
color = if (used >= limit * 0.85) Amber else Blue, trackColor = Card2
)
Spacer(Modifier.height(6.dp))
Text("${fmtBytes(used)} de ${fmtBytes(limit)}", color = Muted, fontSize = 12.sp)
} else {
InfoRow("Consumo", "${fmtBytes(used)} · ilimitado")
}
Spacer(Modifier.height(10.dp))
InfoRow("Consumo acumulado ↑ / ↓", "${fmtBytes(st?.upBytes ?: 0)} / ${fmtBytes(st?.downBytes ?: 0)}")
}
Spacer(Modifier.height(12.dp))
Text(
st?.let { "Actualizado ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it.checkedAt))}" } ?: "",
color = Muted, fontSize = 11.sp
)
Spacer(Modifier.height(16.dp))
}
}
@Composable
private fun SettingsScreen(ui: GzmUi, vm: GzmViewModel, isTv: Boolean, onActivate: (String) -> Unit) {
var activationText by remember { mutableStateOf("") }
val context = LocalContext.current
var port by remember(ui.proxyPort) { mutableStateOf(ui.proxyPort) }
var user by remember(ui.proxyUser) { mutableStateOf(ui.proxyUser) }
var pass by remember(ui.proxyPass) { mutableStateOf(ui.proxyPass) }
var confirmDeactivate by remember { mutableStateOf(false) }
val ip = remember(ui.proxyOn) { GzmSettings.localIp() }
Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
Text("Ajustes", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 14.dp))
SectionCard("Cuenta") {
if (ui.hasToken) {
Row(verticalAlignment = Alignment.CenterVertically) {
Box(Modifier.size(42.dp).clip(CircleShape).background(Blue.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
Canvas(Modifier.size(22.dp)) {
val cx = size.width / 2f
drawCircle(Blue, radius = size.minDimension * .18f, center = Offset(cx, size.height * .30f))
drawArc(Blue, 200f, 140f, false, topLeft = Offset(size.width * .18f, size.height * .43f), size = Size(size.width * .64f, size.height * .50f), style = Stroke(width = size.minDimension * .12f, cap = StrokeCap.Round))
}
}
Spacer(Modifier.size(12.dp))
Column(Modifier.weight(1f)) {
Text(ui.status?.name?.ifBlank { "Cuenta GZMNET" } ?: "Cuenta GZMNET", fontWeight = FontWeight.SemiBold)
Text("Token y servidores guardados en este dispositivo", color = Muted, fontSize = 11.sp)
}
}
Spacer(Modifier.height(12.dp))
Spacer(Modifier.height(12.dp))
Button(onClick = { confirmDeactivate = true }, modifier = Modifier.fillMaxWidth().height(48.dp).tvFocus(RoundedCornerShape(14.dp)), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = Red.copy(alpha = .16f), contentColor = Red)) { Text("Desactivar cuenta", fontWeight = FontWeight.Bold) }
if (confirmDeactivate) AlertDialog(
onDismissRequest = { confirmDeactivate = false },
title = { Text("¿Deseas desactivar esta cuenta?") },
text = { Text("Se desconectará la VPN y se eliminarán de este dispositivo el token y los servidores guardados. Luego tendrás que volver a activar la cuenta con tu token.") },
confirmButton = { TextButton(onClick = { confirmDeactivate = false; vm.logout() }) { Text("Desactivar", color = Red) } },
dismissButton = { TextButton(onClick = { confirmDeactivate = false }) { Text("Cancelar") } },
)
} else {
Text("Explora los ajustes libremente. Para conectar la VPN, primero vincula una cuenta.", color = Muted, fontSize = 12.sp)
Spacer(Modifier.height(10.dp))
Text(if (isTv) "Pega el token de tu proveedor para vincular esta TV." else "Pega el token de tu proveedor para vincular la cuenta.", color = Muted, fontSize = 12.sp)
Spacer(Modifier.height(8.dp))
OutlinedTextField(value = activationText, onValueChange = { activationText = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Token de acceso") }, minLines = 2, maxLines = 4)
Spacer(Modifier.height(6.dp))
OutlinedButton(onClick = { val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager; val clip = clipboard?.primaryClip; if (clip != null && clip.itemCount > 0) activationText = clip.getItemAt(0).coerceToText(context).toString().trim() }, modifier = Modifier.fillMaxWidth().height(44.dp).tvFocus(RoundedCornerShape(12.dp))) { Text("Pegar token") }
Spacer(Modifier.height(8.dp))
Button(onClick = { onActivate(activationText.trim()) }, enabled = activationText.isNotBlank() && !ui.syncing, modifier = Modifier.fillMaxWidth().height(48.dp).tvFocus(RoundedCornerShape(14.dp))) { Text(if (ui.syncing) "Activando…" else "Activar cuenta") }
if (!ui.error.isNullOrBlank()) Text(ui.error.orEmpty(), color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp), textAlign = TextAlign.Center)
}
}
Spacer(Modifier.height(14.dp))

SectionCard("Conexión automática") {
SwitchRow("Conectar al detectar WiFi", "Se activa solo cuando el teléfono se une a una red WiFi", ui.autoWifi, vm::setAutoWifi)
Spacer(Modifier.height(6.dp))
SwitchRow("Conectar con datos móviles", "Se activa solo cuando usas datos móviles", ui.autoMobile, vm::setAutoMobile)
Spacer(Modifier.height(6.dp))
Text("Si Android bloquea el inicio en segundo plano, abre la app una vez después de cambiar de red.", color = Muted, fontSize = 11.sp)
}
Spacer(Modifier.height(14.dp))
SectionCard("Compartir por proxy") {
SwitchRow("Compartir proxy HTTP y SOCKS5", "Conecta el otro dispositivo usando la IP y el puerto indicados abajo.", ui.proxyOn, vm::setProxy)
if (ui.proxyOn) {
Spacer(Modifier.height(10.dp))
Text("Configura en el otro dispositivo:", color = Muted, fontSize = 12.sp)
Text("$ip : ${ui.proxyPort}", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = Green)
Spacer(Modifier.height(10.dp))
OutlinedTextField(value = port, onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) }, label = { Text("Puerto") }, singleLine = true, modifier = Modifier.fillMaxWidth())
Spacer(Modifier.height(8.dp))
OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("Usuario (opcional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
Spacer(Modifier.height(8.dp))
OutlinedTextField(
value = pass, onValueChange = { pass = it }, label = { Text("Contraseña (opcional)") }, singleLine = true,
visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
)
Spacer(Modifier.height(10.dp))
Button(
onClick = { vm.saveProxy(port, user, pass) },
modifier = Modifier.fillMaxWidth().height(48.dp).tvFocus(RoundedCornerShape(14.dp)), shape = RoundedCornerShape(14.dp)
) { Text("Guardar proxy") }
ui.proxyMsg?.let { Text(it, color = if (it == "Guardado") Green else Red, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp)) }
Spacer(Modifier.height(6.dp))
Text("Sin usuario ni contraseña, cualquiera en tu red podría usar este proxy. Se recomienda poner una contraseña.", color = Amber, fontSize = 11.sp)
}
}
Spacer(Modifier.height(14.dp))
SectionCard("Actualizaciones") {
InfoRow("Versión instalada", GzmUpdate.currentName())
if (ui.updText.isNotBlank()) Text(ui.updText, color = if (ui.updAvailable) Green else Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
if (ui.updProgress in 0..99) {
Spacer(Modifier.height(8.dp))
LinearProgressIndicator(
progress = { ui.updProgress / 100f },
modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)), color = Blue, trackColor = Card2
)
}
Spacer(Modifier.height(10.dp))
if (ui.updAvailable) {
Button(
onClick = { if (ui.pendingUpdateInstall) vm.retryPendingUpdateInstall() else vm.downloadUpdate() }, enabled = !ui.updBusy,
modifier = Modifier.fillMaxWidth().height(48.dp).tvFocus(RoundedCornerShape(14.dp)), shape = RoundedCornerShape(14.dp),
colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black)
) { Text(if (ui.pendingUpdateInstall) "Instalar actualización" else "Descargar e instalar", fontWeight = FontWeight.Bold) }
} else {
Button(
onClick = { vm.checkUpdate() }, enabled = !ui.updBusy,
modifier = Modifier.fillMaxWidth().height(48.dp).tvFocus(RoundedCornerShape(14.dp)), shape = RoundedCornerShape(14.dp)
) { Text(if (ui.updBusy) "Buscando…" else if (ui.pendingUpdateInstall) "Instalar actualización" else "Buscar actualización") }
}
}
Spacer(Modifier.height(14.dp))
SectionCard("Diagnóstico") {
val errors = remember(ui.error) { mutableStateOf(GzmDiagnostics.count(context)) }
Text("${errors.value} errores de GZMNET guardados en este dispositivo. No se envían solos.", color = Muted, fontSize = 12.sp)
Spacer(Modifier.height(8.dp))
Button(
onClick = {
val report = GzmDiagnostics.report(context)
val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
clipboard?.setPrimaryClip(ClipData.newPlainText("Errores de GZMNET", report))
}, modifier = Modifier.fillMaxWidth().height(46.dp).tvFocus(RoundedCornerShape(14.dp)), shape = RoundedCornerShape(14.dp)
) { Text("Copiar errores") }
Spacer(Modifier.height(8.dp))
OutlinedButton(
onClick = { context.startActivity(GzmDiagnostics.shareIntent(context)) },
modifier = Modifier.fillMaxWidth().height(46.dp).tvFocus(RoundedCornerShape(14.dp))
) { Text("Compartir con el creador") }
Spacer(Modifier.height(8.dp))
TextButton(onClick = { GzmDiagnostics.clear(context); errors.value = 0 }) { Text("Borrar registro", color = Muted) }
}
Spacer(Modifier.height(20.dp))
Spacer(Modifier.height(14.dp))
CreatorLink(Modifier.align(Alignment.CenterHorizontally))
Spacer(Modifier.height(18.dp))
}
}
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(18.dp)) {
Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 10.dp))
content()
}
}
@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
val shape = RoundedCornerShape(14.dp)
Row(
Modifier.fillMaxWidth().tvFocus(shape).clip(shape).clickable { onChange(!checked) }.padding(vertical = 6.dp, horizontal = 4.dp),
verticalAlignment = Alignment.CenterVertically
) {
Column(Modifier.weight(1f)) {
Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp)
Text(subtitle, color = Muted, fontSize = 11.sp)
}
Switch(
checked = checked, onCheckedChange = null,
colors = SwitchDefaults.colors(checkedTrackColor = Green, checkedThumbColor = Color.Black, uncheckedTrackColor = Card2)
)
}
}
@Composable
private fun InfoRow(k: String, v: String) {
Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
Text(k, color = Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
Text(v, fontSize = 13.sp, fontWeight = FontWeight.Medium)
}
}
@Composable
private fun PowerButton(running: Boolean, enabled: Boolean, onClick: () -> Unit, starting: Boolean = false) {
val accent = if (running) Green else Color.White
Box(contentAlignment = Alignment.Center) {
Box(
Modifier.size(250.dp).background(
Brush.radialGradient(listOf((if (running) Green else Blue).copy(alpha = 0.28f), Color.Transparent)), CircleShape
)
)
Box(
Modifier.size(190.dp).alpha(if (enabled || starting) 1f else 0.4f).tvFocus(CircleShape).clip(CircleShape)
.background(Card).clickable(enabled = enabled || starting, onClick = onClick),
contentAlignment = Alignment.Center
) {
Canvas(Modifier.size(190.dp)) {
drawCircle(if (running) Green else Color(0xFF3A3B41), radius = size.minDimension / 2 - 4.dp.toPx(), style = Stroke(width = 4.dp.toPx()))
val c = Offset(size.width / 2, size.height / 2)
val r = 34.dp.toPx()
val w = 7.dp.toPx()
drawArc(
color = accent, startAngle = -60f, sweepAngle = 300f, useCenter = false,
topLeft = Offset(c.x - r, c.y - r + 4.dp.toPx()), size = Size(r * 2, r * 2),
style = Stroke(width = w, cap = StrokeCap.Round)
)
drawLine(color = accent, start = Offset(c.x, c.y - r - 6.dp.toPx()), end = Offset(c.x, c.y + 2.dp.toPx()), strokeWidth = w, cap = StrokeCap.Round)
}
}
}
}
private fun fmtRate(bytesPerSecond: Long): String = "${fmtBytes(bytesPerSecond.coerceAtLeast(0))}/s"
private fun fmtBytes(b: Long): String {
if (b < 1024) return "$b B"
val u = arrayOf("KB", "MB", "GB", "TB")
var v = b.toDouble() / 1024
var i = 0
while (v >= 1024 && i < u.size - 1) { v /= 1024; i++ }
return String.format(Locale.US, "%.2f %s", v, u[i])
}
