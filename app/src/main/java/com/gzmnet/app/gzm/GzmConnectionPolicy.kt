package com.gzmnet.app.gzm

import java.util.Locale

/** Native core pipe errors are lifecycle cancellations, not evidence that the selected server is bad. */
internal fun isGzmCorePipeClosed(error: String): Boolean {
    val normalized = error.lowercase(Locale.ROOT)
    return listOf("closed pipe", "broken pipe", "eof", "end of file", "pipe closed").any(normalized::contains)
}

internal fun shouldTryAlternateGzmPing(primaryFailure: String, coreRunning: Boolean): Boolean =
    coreRunning && !isGzmCorePipeClosed(primaryFailure)

internal fun gzmUpdateFailureSummary(error: Throwable): String = when (error) {
    is java.net.UnknownHostException -> "No se resolvió el servidor de actualización (DNS/red móvil)."
    is java.net.SocketTimeoutException -> "La red tardó demasiado al consultar GitHub."
    is java.net.ConnectException -> "No se pudo abrir conexión con GitHub desde la red disponible."
    else -> "${error.javaClass.simpleName}: ${error.message.orEmpty()}".take(180)
}
