package com.gzmnet.app.gzm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.gzmnet.app.R
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun gzmDaysUntilPayment(expiry: String?, today: LocalDate): Long? {
    val raw = expiry?.trim()?.take(10).orEmpty()
    val date = runCatching { LocalDate.parse(raw) }.getOrNull()
        ?: runCatching { LocalDate.parse(raw, DateTimeFormatter.ofPattern("dd/MM/uuuu", Locale.US)) }.getOrNull()
        ?: return null
    return java.time.temporal.ChronoUnit.DAYS.between(today, date)
}

internal fun gzmPaymentReminderText(days: Long): String? = when (days) {
    0L -> "Tu pago o renovación de GZMNET vence hoy."
    1L -> "Tu pago o renovación de GZMNET vence mañana."
    in 2L..3L -> "Tu pago o renovación de GZMNET vence en $days días."
    else -> null
}

/** Shows at most one reminder each day in the three-day window before renewal. */
object GzmPaymentReminder {
    private const val CHANNEL_ID = "gzmnet_payment_reminders"
    private const val NOTIFICATION_ID = 7601
    private const val PREFS = "gzmnet_payment_reminders"

    fun notifyIfDue(context: Context, expiry: String?) {
        val today = LocalDate.now()
        val days = gzmDaysUntilPayment(expiry, today) ?: return
        val message = gzmPaymentReminderText(days) ?: return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val marker = "${expiry?.take(10)}:$days"
        if (prefs.getString("last_notice", null) == marker) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val systemManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (systemManager.getNotificationChannel(CHANNEL_ID) == null) {
                systemManager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Avisos de renovación", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Avisos cuando se acerca el vencimiento de la suscripción GZMNET"
                    }
                )
            }
        }

        val openApp = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, GzmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("GZMNET · Renovación próxima")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
            prefs.edit().putString("last_notice", marker).apply()
        } catch (_: SecurityException) {
            // Notification permission may be revoked between the check and notify.
        }
    }
}
