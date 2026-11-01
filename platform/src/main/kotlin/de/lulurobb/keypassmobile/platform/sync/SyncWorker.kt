package de.lulurobb.keypassmobile.platform.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.lulurobb.keypassmobile.core.settings.BACKGROUND_SYNC_INTERVAL
import de.lulurobb.keypassmobile.platform.KpmPlatform
import java.util.concurrent.TimeUnit

/**
 * Periodic background sync (SY-06): every [BACKGROUND_SYNC_INTERVAL] with a
 * network connection. Locked it downloads a newer file as candidate; while
 * unlocked it runs the refresh with the key in memory (like SY-07). Problems
 * end up in the sync status; the next period simply tries again.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        KpmPlatform.get(applicationContext).runScheduledSync()
        return Result.success()
    }
}

/** Schedules [SyncWorker] once the setup is complete. */
internal object SyncScheduler {
    private const val WORK_NAME = "kpm-background-sync"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequest.Builder(
            SyncWorker::class.java,
            BACKGROUND_SYNC_INTERVAL.toMinutes(),
            TimeUnit.MINUTES,
        )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // KEEP: an existing schedule keeps its timing (called at every start).
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
