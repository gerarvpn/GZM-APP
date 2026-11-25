package com.gzmnet.app.gzm

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Revisa el estado en segundo plano cuando hay internet; si el cliente fue desactivado, corta la VPN. */
class GzmSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        GzmCrypto.init(applicationContext)
        if (!GzmPanel.hasToken()) return Result.success()
        val intervalHours = GzmPanel.loadStatus()?.configIntervalHours ?: 12
        GzmPanel.sync(applicationContext, refreshConfig = GzmPanel.configRefreshDue(intervalHours))
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<GzmSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("gzm_sync", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
