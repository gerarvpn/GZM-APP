package com.gzmnet.app.gzm

import android.content.Context
import android.content.Intent
import android.os.Build
import com.gzmnet.app.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun isGzmDiagnosticErrorPhase(phase: String): Boolean {
    val normalized = phase.lowercase(Locale.ROOT)
    return normalized.startsWith("uncaught app error") || normalized in setOf(
        "xray startup", "vpn core startup", "queue vpn startup", "establish vpn interface",
        "vpn setup", "vpn tunnel setup", "vpn start failure", "vpn startup timeout",
        "conexión", "actualización", "descarga de actualización",
    )
}

/** Private, bounded log of GZMNET, Xray and VPN-tunnel errors only. */
object GzmDiagnostics {
    private const val FILE_NAME = "gzm_app_errors.log"
    private const val MAX_BYTES = 96 * 1024
    private const val MAX_ENTRIES = 120
    private val lock = Any()

    fun record(context: Context, phase: String, failure: Throwable) {
        if (!isGzmDiagnosticErrorPhase(phase)) return
        val frames = generateSequence(failure) { it.cause }.take(3).flatMap { throwable ->
            sequenceOf("${throwable.javaClass.simpleName}: ${redact(throwable.message.orEmpty())}") +
                throwable.stackTrace.asSequence().take(5).map { frame ->
                    "  at ${frame.className.substringAfterLast('.')}.${frame.methodName}(${frame.fileName ?: "?"}:${frame.lineNumber})"
                }
        }.toList()
        append(context, "[$phase] ${failure.javaClass.simpleName}: ${redact(failure.message.orEmpty())}\n${frames.joinToString("\n")}")
    }

    fun recordMessage(context: Context, phase: String, message: String) {
        if (!isGzmDiagnosticErrorPhase(phase)) return
        append(context, "[$phase] ${redact(message)}")
    }

    fun count(context: Context): Int = synchronized(lock) { errorEntries(context).size }

    fun report(context: Context): String = synchronized(lock) {
        val entries = errorEntries(context).joinToString("\n").trim()
        buildString {
            appendLine("GZMNET — informe de errores de la app")
            appendLine("App: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine("Dispositivo: ${redact("${Build.MANUFACTURER} ${Build.MODEL}")}")
            appendLine("Este informe excluye tokens, contraseñas y enlaces de servidores.")
            appendLine()
            append(if (entries.isBlank()) "No hay errores guardados." else entries)
        }
    }

    fun clear(context: Context) = synchronized(lock) { runCatching { file(context).delete() } }

    fun shareIntent(context: Context): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Errores de GZMNET")
            putExtra(Intent.EXTRA_TEXT, report(context) + "\n\nCreador: @gerargzm — https://t.me/gerargzm")
        },
        "Compartir errores de GZMNET con el creador",
    )

    private fun errorEntries(context: Context): List<String> = runCatching {
        val content = file(context).readText()
        Regex("(?m)(?=^--- )").split(content).filter { entry ->
            val phaseLine = entry.lineSequence().firstOrNull { it.startsWith("[") }.orEmpty()
            val phase = phaseLine.substringAfter("[").substringBefore("]", "")
            isGzmDiagnosticErrorPhase(phase)
        }
    }.getOrDefault(emptyList())

    private fun append(context: Context, text: String) = synchronized(lock) {
        runCatching {
            val f = file(context)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val next = (if (f.exists()) f.readText() else "") + "--- $timestamp ---\n" + text.take(2_000) + "\n"
            val bounded = next.takeLast(MAX_BYTES).lineSequence().toList().takeLast(MAX_ENTRIES * 8).joinToString("\n")
            f.writeText(bounded.takeLast(MAX_BYTES))
        }
    }

    private fun file(context: Context): File = File(context.applicationContext.filesDir, FILE_NAME)

    internal fun redact(input: String): String {
        var text = input.replace(Regex("(?i)\\bGZM(?:1|2)?\\.[A-Za-z0-9_-]+"), "GZM.[REDACTED]")
        text = text.replace(Regex("(?i)\\b(token|password|passwd|secret|authorization|api[_-]?key|uuid|private[_-]?key)\\s*[:=]\\s*[^\\s,;]+"), "$1=[REDACTED]")
        text = text.replace(Regex("(?i)(\"(?:token|password|passwd|secret|authorization|api[_-]?key|uuid|private[_-]?key)\"\\s*:\\s*\")[^\"]*(\")"), "$1[REDACTED]$2")
        text = text.replace(Regex("(?i)\\b(vless|vmess|trojan|ss|ssr|hysteria2?|tuic|wireguard)://\\S+"), "[SERVER_CONFIG_REDACTED]")
        text = text.replace(Regex("(?i)https?://\\S+"), "[URL_REDACTED]")
        return text.replace(Regex("[\\r\\n\\t]+"), " ").take(300)
    }
}
