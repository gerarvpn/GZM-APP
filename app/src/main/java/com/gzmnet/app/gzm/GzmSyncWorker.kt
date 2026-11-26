package com.gzmnet.app.gzm

import android.content.Context
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import com.gzmnet.app.AppConfig

object GzmWorkManagerRuntime {
    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            WorkManager.initialize(
                context.applicationContext,
                Configuration.Builder().setDefaultProcessName("${AppConfig.ANG_PACKAGE}:bg").build(),
            )
            initialized = true
        }
    }
}

/** Revisa el estado en segundo plano cuando hay internet; si el cliente fue desactivado, corta la VPN. */
class GzmPaymentReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        GzmCrypto.init(applicationContext)
        runCatching { GzmPaymentReminder.notifyIfDue(applicationContext, GzmPanel.loadStatus()?.expiry) }
        return Result.success()
    }
}

class GzmSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        GzmCrypto.init(applicationContext)
        if (!GzmPanel.hasToken()) return Result.success()
        val intervalHours = GzmPanel.loadStatus()?.configIntervalHours ?: 12
        val result = GzmPanel.sync(applicationContext, refreshConfig = GzmPanel.configRefreshDue(intervalHours))
        GzmPaymentReminder.notifyIfDue(applicationContext, result.status?.expiry ?: GzmPanel.loadStatus()?.expiry)
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            GzmWorkManagerRuntime.initialize(ctx)
            val req = PeriodicWorkRequestBuilder<GzmSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            val work = WorkManager.getInstance(ctx)
            work.enqueueUniquePeriodicWork("gzm_sync", ExistingPeriodicWorkPolicy.KEEP, req)
            // Payment notices use only the cached expiry date and remain available offline.
            val reminders = PeriodicWorkRequestBuilder<GzmPaymentReminderWorker>(15, TimeUnit.MINUTES).build()
            work.enqueueUniquePeriodicWork("gzm_payment_reminders", ExistingPeriodicWorkPolicy.KEEP, reminders)
            // Catch up promptly after the device has been off or offline past the configured interval.
            if (GzmPanel.hasToken() && GzmPanel.configRefreshDue(GzmPanel.loadStatus()?.configIntervalHours ?: 12)) {
                val catchUp = OneTimeWorkRequestBuilder<GzmSyncWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                work.enqueueUniqueWork("gzm_sync_catchup", ExistingWorkPolicy.KEEP, catchUp)
            }
        }
    }
}
