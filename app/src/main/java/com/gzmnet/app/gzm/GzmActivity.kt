package com.gzmnet.app.gzm
import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.VpnService
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.text.ClickableText
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
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
import com.gzmnet.app.handler.MmkvManager
import com.gzmnet.app.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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

internal const val GZM_STARTUP_TIMEOUT_MS = 90_000L
internal fun hasGzmStartupTimedOut(startedAtMs: Long, nowMs: Long): Boolean =
    nowMs - startedAtMs >= GZM_STARTUP_TIMEOUT_MS
internal fun gzmStartupTimeoutDetail(phase: String): String =
    "El túnel no confirmó el inicio en 90 segundos. Última fase: ${phase.ifBlank { "esperando respuesta del servicio VPN" }}."

internal fun gzmServerDisplayName(title: String?): String {
    val candidate = title?.trim().orEmpty()
    val normalized = candidate.lowercase(Locale.ROOT).trimEnd('.', '!', ' ')
    val generic = setOf("activo", "activa", "active", "inactivo", "inactiva", "inactive", "vencido", "vencida", "expired", "desactivado", "desactivada", "disabled")
    return candidate.takeIf { it.isNotEmpty() && normalized !in generic } ?: "GZMNET VPN"
}

internal fun gzmServerStatusLabel(state: String?): String = when (state?.trim()?.lowercase(Locale.ROOT)) {
    "active" -> "Activo"
    null, "" -> "Sin verificar"
    else -> "Inactivo"
}

internal fun gzmAccountNotice(message: String?, expiry: String?, today: java.time.LocalDate): String? {
    val genericStatusMessages = setOf("activo", "activa", "active", "inactivo", "inactiva", "inactive", "vencido", "vencida", "expired", "desactivado", "desactivada", "disabled", "cuenta activa", "cuenta está activa", "account active", "account is active", "sin cupo de datos", "límite de dispositivos", "limite de dispositivos", "sin servidores disponibles")
    val cleanMessages = listOfNotNull(message).map { it.trim() }
        .filter { it.isNotEmpty() && it.lowercase(Locale.ROOT).trimEnd('.', '!', ' ') !in genericStatusMessages }
    val expiryDate = expiry?.trim()?.take(10)?.let { raw ->
        runCatching { java.time.LocalDate.parse(raw) }.getOrNull()
            ?: runCatching { java.time.LocalDate.parse(raw, java.time.format.DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.US)) }.getOrNull()
    }
    val days = expiryDate?.let { java.time.temporal.ChronoUnit.DAYS.between(today, it) }
    val dueNotice = when {
        days == null -> null
        days < 0 -> "La suscripción venció el ${expiryDate?.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.getDefault())) ?: expiry?.trim().orEmpty()}."
        days == 0L -> "Tu pago o renovación vence hoy."
        days in 1L..3L -> "Tu pago o renovación vence en $days día${if (days == 1L) "" else "s"}."
        else -> null
    }
    return (cleanMessages + listOfNotNull(dueNotice)).joinToString("\n").ifBlank { null }
}

data class GzmUi(
val hasToken: Boolean = false,
val status: GzmStatus? = null,
val hasConfig: Boolean = false,
val assetsReady: Boolean = false,
val initialized: Boolean = false,
val stateLoading: Boolean = true,
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
) {
val canConnect: Boolean get() = canStartFromLocalConfig(hasToken, hasConfig, assetsReady)
}

internal fun canStartFromLocalConfig(hasToken: Boolean, hasConfig: Boolean, assetsReady: Boolean): Boolean =
    hasToken && hasConfig && assetsReady

internal fun shouldEnableGzmConnectAction(hasToken: Boolean, canConnect: Boolean, running: Boolean, starting: Boolean): Boolean =
    running || (!starting && (canConnect || hasToken))

internal fun isGzmDaemonReportedRunning(resultCode: Int): Boolean = resultCode == Activity.RESULT_OK
internal fun isGzmDaemonReportedStopped(resultCode: Int): Boolean = resultCode == Activity.RESULT_FIRST_USER

internal fun shouldStartDeferredGzmConnect(hasToken: Boolean, hasConfig: Boolean, assetsReady: Boolean,
    activityVisible: Boolean, vpnPermissionGranted: Boolean, alreadyStarting: Boolean): Boolean =
    !alreadyStarting && activityVisible && vpnPermissionGranted && canStartFromLocalConfig(hasToken, hasConfig, assetsReady)

internal fun shouldResumeGzmVpn(
    resumeWasRequested: Boolean,
    activityVisible: Boolean,
    alreadyStarting: Boolean,
    hasToken: Boolean,
    hasConfig: Boolean,
    assetsReady: Boolean,
    vpnPermissionGranted: Boolean,
): Boolean = resumeWasRequested && activityVisible && !alreadyStarting &&
    canStartFromLocalConfig(hasToken, hasConfig, assetsReady) && vpnPermissionGranted

class GzmViewModel(app: Application) : AndroidViewModel(app) {
private val _ui = MutableStateFlow(GzmUi())
val ui: StateFlow<GzmUi> = _ui.asStateFlow()
private var downloadId = -1L
private var pendingInstallId = -1L
private var installLaunchInProgress = false
@Volatile private var startAttemptId = 0L
@Volatile private var startAttemptStartedAt = 0L
@Volatile private var startupPhaseForDiagnostic = ""
private val panelSyncMutex = Mutex()
private val updateActionMutex = Mutex()
@Volatile private var connectAfterPermission = false
@Volatile private var lastConfigAttemptAt = 0L
@Volatile private var panelFailureCount = 0
@Volatile private var activityVisible = false
@Volatile private var resumeWhenForegrounded = GzmPanel.resumeWhenForegrounded()
private val receiver = object : BroadcastReceiver() {
override fun onReceive(context: Context?, intent: Intent?) {
when (intent?.getIntExtra("key", 0)) {
AppConfig.MSG_GZM_START_PHASE -> {
val phase = intent?.getStringExtra("content").orEmpty()
if (_ui.value.starting && phase.isNotBlank()) startupPhaseForDiagnostic = phase
}
AppConfig.MSG_GZM_TRAFFIC_SAMPLE -> {
val parts = intent?.getStringExtra("content")?.split(',', limit = 2).orEmpty()
val up = parts.getOrNull(0)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
val down = parts.getOrNull(1)?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
_ui.update { it.copy(appRateUp = up, appRateDown = down) }
}
AppConfig.MSG_STATE_RUNNING -> markRunning()
AppConfig.MSG_STATE_START_SUCCESS -> {
// Ignore a late startup callback after the user cancelled or the startup watchdog expired.
if (_ui.value.starting) markRunning()
}
AppConfig.MSG_STATE_NOT_RUNNING -> {
if (!_ui.value.starting) {
startupPhaseForDiagnostic = ""
_ui.update { it.copy(running = false, appRateUp = 0, appRateDown = 0) }
resumeFromLocalProfileIfNeeded()
}
}
AppConfig.MSG_STATE_STOP_SUCCESS -> {
startupPhaseForDiagnostic = ""
_ui.update { it.copy(running = false, starting = false, appRateUp = 0, appRateDown = 0) }
}
AppConfig.MSG_STATE_START_FAILURE -> {
val detail = intent?.getStringExtra("content")?.trim().orEmpty()
val safeDetail = detail.ifBlank { "No se pudo conectar. Inténtalo otra vez." }
GzmDiagnostics.recordMessage(app, "VPN start failure", safeDetail)
startupPhaseForDiagnostic = ""
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
GzmGeoAssets.ensure(getApplication<Application>())
}
private fun reload() {
// Show the app shell first. Account crypto reads, profile migration and Xray assets all run off-main.
_ui.update { it.copy(initialized = true, syncing = true) }
viewModelScope.launch {
val cached = withContext(Dispatchers.IO) {
GzmUi(
hasToken = GzmPanel.hasToken(),
status = GzmPanel.loadStatus(),
hasConfig = GzmPanel.hasConfig(),
initialized = true,
stateLoading = false,
syncing = true,
).let(loadSettings())
}
_ui.update { current -> cached.copy(stateLoading = false, starting = current.starting, running = current.running, appRateUp = current.appRateUp, appRateDown = current.appRateDown) }
val restoredUpdateId = withContext(Dispatchers.IO) {
GzmSettings.initializeAppSettings(getApplication())
GzmSettings.ensureVpnMode()
GzmPanel.ensureProfile()
GzmUpdate.restoreCompletedDownload(getApplication())
}
val assetsReady = prepareGeoAssets()
_ui.update {
it.copy(
hasToken = GzmPanel.hasToken(), status = GzmPanel.loadStatus(),
hasConfig = GzmPanel.hasConfig(), assetsReady = assetsReady, initialized = true,
updAvailable = restoredUpdateId != null, pendingUpdateInstall = restoredUpdateId != null,
updText = if (restoredUpdateId != null) "Hay una actualización descargada lista para instalar." else it.updText,
error = if (assetsReady) it.error else "Faltan los datos de geolocalización de Xray. Actualiza la app e inténtalo otra vez."
).let(loadSettings())
}
if (restoredUpdateId != null) pendingInstallId = restoredUpdateId
refreshPaymentReminder()
tryStartDeferredConnect()
GzmAuto.apply(getApplication())
// Startup and connection use the encrypted local profile. Status refresh is deferred to
// the regular background poll; a panel request must never gate the first Connect action.
_ui.update { it.copy(syncing = false) }
}
}
private fun markRunning() {
// The reconnect flag was persisted before starting the service. Do not encrypt/write it again
// on the main thread here: Android TV Keystore calls added several seconds after Xray started.
startAttemptStartedAt = 0L
startAttemptId++
startupPhaseForDiagnostic = ""
resumeWhenForegrounded = true
GzmPanel.setResumeWhenForegrounded(true)
_ui.update { it.copy(running = true, starting = false, error = null) }
if (activityVisible) {
MessageHelper.sendMsg2Service(getApplication(), AppConfig.MSG_GZM_TRAFFIC_START, "")
}
}
fun beginPermission() {
startupPhaseForDiagnostic = ""
startAttemptId++
startAttemptStartedAt = 0L
_ui.update { it.copy(starting = true, running = false, error = null) }
}
fun beginStart() {
startupPhaseForDiagnostic = ""
val attempt = ++startAttemptId
val startedAt = SystemClock.elapsedRealtime()
startAttemptStartedAt = startedAt
_ui.update { it.copy(starting = true, running = false, error = null) }
// The daemon runs in a separate Android process. Reconcile its authoritative state while the
// screen is open so a missed startup broadcast cannot leave the control stuck until app reopen.
viewModelScope.launch {
    repeat(15) {
        delay(2_000)
        if (startAttemptId != attempt || !_ui.value.starting || !activityVisible) return@launch
        MessageHelper.sendMsg2ServiceForState(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "") { resultCode ->
            if (startAttemptId == attempt && _ui.value.starting && isGzmDaemonReportedRunning(resultCode)) markRunning()
        }
    }
}
viewModelScope.launch {
delay(GZM_STARTUP_TIMEOUT_MS)
if (startAttemptId == attempt && _ui.value.starting && hasGzmStartupTimedOut(startedAt, SystemClock.elapsedRealtime())) {
MessageHelper.sendMsg2Service(getApplication(), AppConfig.MSG_STATE_STOP, "")
val detail = gzmStartupTimeoutDetail(startupPhaseForDiagnostic)
GzmDiagnostics.recordMessage(getApplication(), "VPN startup timeout", detail)
startFailed("${detail} El perfil local se conservó; no es necesario actualizar los servidores para reintentar.")
}
}
}
fun startFailed(message: String) {
val startedAt = startAttemptStartedAt
if (startedAt > 0L) GzmDiagnostics.recordMessage(getApplication(), "VPN start failure", "El intento de conexión terminó tras ${SystemClock.elapsedRealtime() - startedAt} ms: $message")
startAttemptStartedAt = 0L
startAttemptId++
connectAfterPermission = false
GzmPanel.setReconnectAfterUpdate(false)
resumeWhenForegrounded = false
GzmPanel.setResumeWhenForegrounded(false)
startupPhaseForDiagnostic = ""
_ui.update { it.copy(starting = false, running = false, appRateUp = 0, appRateDown = 0, error = message) }
}
fun stopRequested() { connectAfterPermission = false; resumeWhenForegrounded = false; GzmPanel.setResumeWhenForegrounded(false); startAttemptId++; _ui.update { it.copy(starting = true, error = null) } }
fun stopFallback() { connectAfterPermission = false; resumeWhenForegrounded = false; GzmPanel.setResumeWhenForegrounded(false); startAttemptStartedAt = 0L; startAttemptId++; _ui.update { it.copy(running = false, starting = false, appRateUp = 0, appRateDown = 0) } }
fun refreshPaymentReminder() {
    viewModelScope.launch(Dispatchers.IO) {
        runCatching { GzmPaymentReminder.notifyIfDue(getApplication(), GzmPanel.loadStatus()?.expiry) }
    }
}
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
GzmPaymentReminder.notifyIfDue(getApplication(), r.status?.expiry)
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
tryStartDeferredConnect()
}
}
fun setActivityVisible(visible: Boolean) {
if (!visible) {
resumeWhenForegrounded = resumeWhenForegrounded || (_ui.value.running && !_ui.value.starting)
activityVisible = false
_ui.update { it.copy(appRateUp = 0, appRateDown = 0) }
MessageHelper.sendMsg2Service(getApplication(), AppConfig.MSG_GZM_TRAFFIC_STOP, "")
return
}
activityVisible = true
// Do not optimistically clear the connected state: Android may still show the VPN key and
// the daemon's ordered reply is the source of truth. Preserve the last state until that reply.
_ui.update { it.copy(appRateUp = 0, appRateDown = 0) }
tryStartDeferredConnect()
MessageHelper.sendMsg2ServiceForState(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "") { resultCode ->
    when {
        isGzmDaemonReportedRunning(resultCode) -> markRunning()
        isGzmDaemonReportedStopped(resultCode) -> {
            if (!_ui.value.starting) _ui.update { it.copy(running = false, appRateUp = 0, appRateDown = 0) }
            resumeFromLocalProfileIfNeeded()
        }
        else -> resumeFromLocalProfileIfNeeded()
    }
}
}

private fun resumeFromLocalProfileIfNeeded() {
    if (!resumeWhenForegrounded || !activityVisible) return
    viewModelScope.launch {
        val app = getApplication<Application>()
        val localState = withContext(Dispatchers.IO) {
            val permissionGranted = runCatching { VpnService.prepare(app) == null }.getOrDefault(false)
            Triple(GzmPanel.hasToken(), GzmPanel.hasConfig(), permissionGranted)
        }
        val current = _ui.value
        if (!shouldResumeGzmVpn(resumeWhenForegrounded, activityVisible, current.starting,
                localState.first, localState.second, current.assetsReady, localState.third)) return@launch
        resumeWhenForegrounded = false
        GzmPanel.setReconnectAfterUpdate(true)
        beginStart()
        val started = withContext(Dispatchers.IO) { LauncherManager.startGzmVpnServiceFromCachedProfile(app) }
        if (!started && _ui.value.starting) {
            startFailed("No se pudo reanudar la VPN desde el perfil guardado. Toca conectar para volver a intentarlo.")
        }
    }
}
fun deferConnectUntilReady() {
connectAfterPermission = true
_ui.update { it.copy(starting = false, running = false, error = "Permiso VPN concedido. Preparando el servidor guardado…") }
if (GzmPanel.hasToken()) refresh()
tryStartDeferredConnect()
}
fun cancelDeferredConnect() {
if (!connectAfterPermission) return
connectAfterPermission = false
_ui.update { it.copy(error = null) }
}
fun isConnectDeferred(): Boolean = connectAfterPermission
private fun tryStartDeferredConnect() {
val app = getApplication<Application>()
val current = _ui.value
val permissionGranted = runCatching { VpnService.prepare(app) == null }.getOrDefault(false)
if (!connectAfterPermission || !shouldStartDeferredGzmConnect(current.hasToken, current.hasConfig, current.assetsReady, activityVisible, permissionGranted, current.starting)) return
connectAfterPermission = false
GzmPanel.setReconnectAfterUpdate(true)
beginStart()
viewModelScope.launch {
val started = withContext(Dispatchers.IO) { LauncherManager.startGzmVpnServiceFromCachedProfile(app) }
if (!started && _ui.value.starting) startFailed("No se pudo iniciar la VPN con el servidor local. La configuración se conservó; vuelve a tocar conectar.")
}
}
fun onVpnPermissionGranted() {
if (_ui.value.canConnect) {
connectAfterPermission = false
GzmPanel.setReconnectAfterUpdate(true)
beginStart()
} else deferConnectUntilReady()
}
fun onNetworkAvailable() {
if (activityVisible) {
if (GzmPanel.hasToken() && GzmPanel.configRefreshDue(_ui.value.status?.configIntervalHours ?: 12)) refresh() else refreshLiveStatus()
}
}
fun refreshLiveStatus() {
if (!GzmPanel.hasToken()) return
viewModelScope.launch {
if (!panelSyncMutex.tryLock()) return@launch
try {
val r = GzmPanel.sync(getApplication(), refreshConfig = false)
GzmPaymentReminder.notifyIfDue(getApplication(), r.status?.expiry)
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
val resolvedToken = raw.trim()
val initialToken = GzmPanel.parseToken(resolvedToken)
if (initialToken == null) {
_ui.update { it.copy(error = "Token inválido. Verifica el token y vuelve a pegarlo.") }
return
}
_ui.update { it.copy(syncing = true, error = null) }
viewModelScope.launch {
if (!panelSyncMutex.tryLock()) {
_ui.update { it.copy(syncing = false, error = "Ya hay una consulta en curso. Vuelve a intentar en un momento.") }
return@launch
}
try {
lastConfigAttemptAt = System.currentTimeMillis()
// Capture old owner before replacing the token so another account cannot use its cached profile.
val previous = withContext(Dispatchers.IO) {
val hadConfig = GzmPanel.hasConfig()
Triple(GzmPanel.loadToken(), hadConfig, GzmPanel.loadStatus())
}
val sameAccount = sameGzmTokenAccount(previous.first, initialToken)
withContext(Dispatchers.IO) { GzmPanel.saveToken(resolvedToken) }
_ui.update { it.copy(
    hasToken = true,
    hasConfig = sameAccount && previous.second,
    status = if (sameAccount) previous.third else null,
    offline = false,
    initialized = true,
    error = null,
) }
tryStartDeferredConnect()
// Download the first config while local Xray assets are prepared off the UI thread.
val (r, assetsReady) = coroutineScope {
    val assets = async(Dispatchers.IO) { prepareGeoAssets() }
    val response = GzmPanel.sync(getApplication(), refreshConfig = true)
    response to assets.await()
}
GzmPaymentReminder.notifyIfDue(getApplication(), r.status?.expiry)
if (r.offline) panelFailureCount = (panelFailureCount + 1).coerceAtMost(5) else panelFailureCount = 0
// Never show the previous account's cached status after an offline token switch.
val st = if (sameAccount || !r.offline) r.status else null
when {
r.offline && st == null -> {
GzmSyncWorker.schedule(getApplication())
_ui.update { it.copy(syncing = false, hasToken = true, status = if (sameAccount) (GzmPanel.loadStatus() ?: previous.third) else null, hasConfig = sameAccount && GzmPanel.hasConfig(), assetsReady = assetsReady, initialized = true, offline = isPanelOfflineAfterFailures(panelFailureCount), error = if (assetsReady) null else "Faltan los datos locales para iniciar la VPN. Abre la app con internet para prepararlos.") }
}
st != null && st.state == "notfound" -> {
withContext(Dispatchers.IO) { GzmPanel.clearAll(getApplication()) }
connectAfterPermission = false
_ui.update { it.copy(syncing = false, hasToken = false, hasConfig = false, initialized = true, error = "El token no existe, fue revocado o fue reiniciado.") }
}
st != null && st.state == "device_limit" -> {
withContext(Dispatchers.IO) { GzmPanel.clearAll(getApplication()) }
connectAfterPermission = false
_ui.update { it.copy(syncing = false, hasToken = false, hasConfig = false, initialized = true, error = "Se alcanzó el límite de dispositivos de esta cuenta. Libera un espacio en el panel y vuelve a vincular la TV.") }
}
else -> {
GzmSyncWorker.schedule(getApplication())
_ui.update { it.copy(syncing = false, hasToken = true, status = st ?: if (sameAccount) GzmPanel.loadStatus() else null, offline = isPanelOfflineAfterFailures(panelFailureCount), hasConfig = GzmPanel.hasConfig(), assetsReady = assetsReady, initialized = true, error = if (!assetsReady) "Faltan los datos locales para iniciar la VPN. Abre la app con internet para prepararlos." else if (r.offline) null else r.error) }
}
}
tryStartDeferredConnect()
} catch (e: Exception) {
panelFailureCount = (panelFailureCount + 1).coerceAtMost(5)
_ui.update { it.copy(syncing = false, hasToken = GzmPanel.hasToken(), hasConfig = GzmPanel.hasConfig(), initialized = true, offline = isPanelOfflineAfterFailures(panelFailureCount), error = "No se pudo terminar la activación. El token y la configuración anterior se conservaron.") }
} finally { panelSyncMutex.unlock() }
}
}

fun logout() {
GzmPanel.setReconnectAfterUpdate(false)
resumeWhenForegrounded = false
GzmPanel.setResumeWhenForegrounded(false)
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
if (!updateActionMutex.tryLock()) return
_ui.update { it.copy(updBusy = true, updText = "Buscando…", updAvailable = false) }
viewModelScope.launch {
try {
val rel = GzmUpdate.latest(getApplication())
_ui.update {
when {
rel == null -> {
val reason = GzmUpdate.latestFailure()?.takeIf { it.isNotBlank() } ?: "GitHub no devolvió información de versión."
GzmDiagnostics.recordMessage(getApplication(), "Actualización", "No se pudo consultar GitHub: $reason")
it.copy(updBusy = false, updText = "No se pudo comprobar: $reason")
}
rel.build > GzmUpdate.currentBuild() -> it.copy(updBusy = false, updAvailable = true, updText = "Nueva versión disponible: ${rel.name}")
else -> it.copy(updBusy = false, updText = "Ya tienes la última versión")
}
}
} finally {
updateActionMutex.unlock()
_ui.update { it.copy(updBusy = false) }
}
}
}
fun downloadUpdate() {
if (!updateActionMutex.tryLock()) return
_ui.update { it.copy(updBusy = true, updProgress = 0, updText = "Descargando…", pendingUpdateInstall = false) }
viewModelScope.launch {
pendingInstallId = -1L
try {
val id = withContext(Dispatchers.IO) { GzmUpdate.startDownload(getApplication()) }
downloadId = id
while (true) {
delay(500)
val p = withContext(Dispatchers.IO) { GzmUpdate.progress(getApplication(), id) }
if (p == -1) {
val reason = GzmUpdate.downloadError(id) ?: "Error desconocido al descargar el instalador."
GzmDiagnostics.recordMessage(getApplication(), "Descarga de actualización", reason)
_ui.update { it.copy(updBusy = false, updProgress = -1, updText = "No se pudo descargar la actualización. Revisa internet y vuelve a intentarlo.") }
return@launch
}
_ui.update { it.copy(updProgress = if (p < 0) 0 else p) }
if (p == 100) break
}
pendingInstallId = id
_ui.update { it.copy(pendingUpdateInstall = true, updAvailable = true, updBusy = false, updProgress = -1, updText = "Descarga completa. Abriendo el instalador…") }
launchPendingInstall(askPermission = true)
} catch (e: Exception) {
pendingInstallId = -1L
GzmDiagnostics.record(getApplication(), "Descarga de actualización", e)
_ui.update { it.copy(updBusy = false, updProgress = -1, updText = "No se pudo descargar la actualización. Revisa internet y vuelve a intentarlo.") }
} finally {
updateActionMutex.unlock()
_ui.update { it.copy(updBusy = false) }
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
fun resumePendingUpdateInstall() {
if (pendingInstallId > 0L) { launchPendingInstall(askPermission = false); return }
viewModelScope.launch {
val restored = withContext(Dispatchers.IO) { GzmUpdate.restoreCompletedDownload(getApplication()) }
if (restored != null) {
pendingInstallId = restored
_ui.update { it.copy(updAvailable = true, pendingUpdateInstall = true, updText = "La actualización descargada sigue lista. Pulsa Instalar actualización para continuar.") }
}
}
}
override fun onCleared() {
runCatching { MessageHelper.sendMsg2Service(getApplication(), AppConfig.MSG_UNREGISTER_CLIENT, "") }
runCatching { getApplication<Application>().unregisterReceiver(receiver) }
}
}
@Composable
private fun ClickableNotice(message: String, modifier: Modifier = Modifier, color: Color, fontSize: androidx.compose.ui.unit.TextUnit) {
    val uriHandler = LocalUriHandler.current
    val annotated = remember(message) {
        buildAnnotatedString {
            val regex = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
            var cursor = 0
            regex.findAll(message).forEach { match ->
                append(message.substring(cursor, match.range.first))
                val raw = match.value
                val url = raw.trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}', '"', '\'')
                if (url.isNotEmpty()) {
                    pushStringAnnotation(tag = "URL", annotation = url)
                    pushStyle(SpanStyle(color = Blue, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium))
                    append(url)
                    pop()
                    pop()
                    append(raw.substring(url.length))
                } else append(raw)
                cursor = match.range.last + 1
            }
            append(message.substring(cursor))
        }
    }
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = TextStyle(color = color, fontSize = fontSize, textAlign = TextAlign.Center),
        onClick = { offset ->
            annotated.getStringAnnotations(tag = "URL", start = offset, end = offset)
                .firstOrNull()?.let { annotation -> runCatching { uriHandler.openUri(annotation.item) } }
        },
    )
}

class GzmActivity : ComponentActivity() {
private val vm: GzmViewModel by viewModels()
private var connectivityManager: ConnectivityManager? = null
private val networkCallback = object : ConnectivityManager.NetworkCallback() {
override fun onAvailable(network: Network) { runOnUiThread { vm.onNetworkAvailable() } }
}
private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
if (it.resultCode == RESULT_OK) {
if (vm.ui.value.canConnect) { vm.onVpnPermissionGranted(); startVpnNow() } else vm.deferConnectUntilReady()
} else vm.startFailed("Se necesita permiso de VPN para conectar.")
}
private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
if (granted) vm.refreshPaymentReminder()
}
override fun onCreate(savedInstanceState: Bundle?) {
super.onCreate(savedInstanceState)
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
setContent { GzmApp(vm, ::toggle, isTv) }
// Defer connectivity registration, cache cleanup and WorkManager setup until after the first frame.
window.decorView.post {
connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
runCatching { connectivityManager?.registerDefaultNetworkCallback(networkCallback) }
Thread({
runCatching { GzmUpdate.cleanupInterruptedDownload(applicationContext) }
if (GzmPanel.hasToken()) runCatching { GzmSyncWorker.schedule(applicationContext) }
}, "gzm-post-frame-init").apply { priority = Thread.NORM_PRIORITY - 1; start() }
if (Build.VERSION.SDK_INT >= 33) runCatching { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
}
override fun onResume() {
super.onResume()
vm.setActivityVisible(true)
vm.resumePendingUpdateInstall()
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
val ui = vm.ui.value
if (vm.isConnectDeferred()) { vm.cancelDeferredConnect(); return }
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
if (!ui.hasToken) return
// Request the VPN consent on the first explicit Connect tap, even if the first server list
// is still loading. After consent, the ViewModel starts automatically when local data is ready.
val intent = VpnService.prepare(this)
if (intent != null) { vm.beginPermission(); vpnPermission.launch(intent); return }
if (!canStartFromLocalConfig(ui.hasToken, ui.hasConfig, ui.assetsReady)) {
vm.deferConnectUntilReady()
return
}
GzmPanel.setReconnectAfterUpdate(true)
vm.beginStart()
startVpnNow()
}
private fun startVpnNow() {
val ok = runCatching { LauncherManager.startGzmVpnServiceFromCachedProfile(this) }.getOrElse {
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
PowerButton(running = ui.running, enabled = shouldEnableGzmConnectAction(ui.hasToken, ui.canConnect, ui.running, ui.starting), onClick = onToggle, starting = ui.starting)
if (!ui.hasToken) {
Spacer(Modifier.height(10.dp))
if (ui.stateLoading) {
Text("Cargando cuenta…", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
} else {
Text("Vincula una cuenta desde Ajustes para activar la VPN. Puedes explorar las opciones mientras tanto.", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center)
TextButton(onClick = onOpenSettings) { Text("Vincular cuenta", color = Blue) }
}
}
if (ui.running) {
Spacer(Modifier.height(10.dp))
Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Card2).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
Column(Modifier.fillMaxWidth()) {
Text("Tráfico actual", color = Muted, fontSize = 11.sp)
Text("↑ ${fmtRate(ui.appRateUp)}   ↓ ${fmtRate(ui.appRateDown)}", color = Green, fontSize = 15.sp, fontWeight = FontWeight.Bold)
}
}
}
Spacer(Modifier.height(18.dp))
Text(
when {
ui.running -> "Conectado"
ui.starting -> "Conectando…"
blocked -> st?.message.orEmpty()
st?.state == "no_servers" -> "Sin servidores disponibles"
!ui.assetsReady -> "Preparando datos de conexión…"
!ui.hasConfig -> "Sin configuración todavía"
else -> "Toca para conectar"
},
fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
color = if (ui.running) Green else if (blocked) Red else Color.White
)
val accountNotice = gzmAccountNotice(st?.message, st?.expiry, java.time.LocalDate.now())
if (!ui.offline && !ui.error.isNullOrBlank()) {
Spacer(Modifier.height(8.dp))
Text(ui.error, modifier = Modifier.fillMaxWidth(), color = Red, fontSize = 12.sp, textAlign = TextAlign.Center)
}
Spacer(Modifier.height(12.dp))
if (ui.hasToken) {
val serverStatus = gzmServerStatusLabel(st?.state)
val serverStatusColor = when (serverStatus) {
"Activo" -> Green
"Inactivo" -> Red
else -> Amber
}
Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Card2).padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
Column(Modifier.weight(1f)) {
Text("Servidor", color = Muted, fontSize = 11.sp)
Spacer(Modifier.height(3.dp))
Text(gzmServerDisplayName(st?.title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
}
Row(Modifier.clip(RoundedCornerShape(50)).background(serverStatusColor.copy(alpha = .14f)).padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
Box(Modifier.size(7.dp).clip(CircleShape).background(serverStatusColor))
Spacer(Modifier.width(6.dp))
Text(serverStatus, color = serverStatusColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
}
}
}
Spacer(Modifier.height(12.dp))
if (blocked) {
Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Red.copy(alpha = 0.16f)).padding(16.dp)) {
Text(st?.message.orEmpty(), color = Red, fontWeight = FontWeight.Bold)
Text("Contacta a tu proveedor para renovar o reactivar tu cuenta.", color = Muted, fontSize = 13.sp)
}
Spacer(Modifier.height(14.dp))
}
Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(18.dp)) {
Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Blue.copy(alpha = .14f)).padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
Text(st?.name?.ifBlank { "Cuenta GZMNET" } ?: "Cuenta GZMNET", color = Blue, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center)
if (!accountNotice.isNullOrBlank()) {
Spacer(Modifier.height(6.dp))
ClickableNotice(accountNotice, modifier = Modifier.fillMaxWidth(), color = Color.White, fontSize = 12.sp)
}
}
Spacer(Modifier.height(14.dp))
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
onClick = { when { ui.pendingUpdateInstall -> vm.retryPendingUpdateInstall(); ui.updAvailable -> vm.downloadUpdate(); else -> vm.checkUpdate() } }, enabled = !ui.updBusy,
modifier = Modifier.fillMaxWidth().height(48.dp).tvFocus(RoundedCornerShape(14.dp)), shape = RoundedCornerShape(14.dp),
colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.Black)
) { Text(if (ui.pendingUpdateInstall) "Instalar actualización" else "Descargar e instalar", fontWeight = FontWeight.Bold) }
} else {
Button(
onClick = { if (ui.pendingUpdateInstall) vm.retryPendingUpdateInstall() else vm.checkUpdate() }, enabled = !ui.updBusy,
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
