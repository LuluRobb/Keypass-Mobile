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
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import java.util.concurrent.TimeUnit

/**
 * Periodic background sync (SY-06): every [BACKGROUND_SYNC_INTERVAL], with a
 * network connection for an SMB source and without one for a file on the phone. Locked it downloads a newer file as candidate; while
 * unlocked it runs the refresh with the key in memory (like SY-07). Problems
 * end up in the sync status; the next period simply tries again.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        KpmPlatform.get(applicationContext).runScheduledSync()
        return Result.success()
    }
}

/** SMB needs a network connection for the background sync, a file on the phone does not (SY-06). */
internal fun needsNetwork(source: SourceConfig?): Boolean = source?.kind != SourceKind.FILE

/** The constraints of [SyncWorker]: [NetworkType.CONNECTED] for an SMB source, [NetworkType.NOT_REQUIRED] for a file. */
internal fun syncConstraints(requiresNetwork: Boolean): Constraints =
    Constraints.Builder()
        .setRequiredNetworkType(if (requiresNetwork) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
        .build()

/** Schedules [SyncWorker] once the setup is complete. */
internal object SyncScheduler {
    private const val WORK_NAME = "kpm-background-sync"

    /**
     * [requiresNetwork]: an SMB source needs a connection ([NetworkType.CONNECTED]); a file on the
     * phone needs none (SY-06). Called at every start and when the source type changes.
     */
    fun schedule(context: Context, requiresNetwork: Boolean) {
        val request = PeriodicWorkRequest.Builder(
            SyncWorker::class.java,
            BACKGROUND_SYNC_INTERVAL.toMinutes(),
            TimeUnit.MINUTES,
        )
            .setConstraints(syncConstraints(requiresNetwork))
            .build()
        // UPDATE: an existing schedule keeps its timing and takes over the new constraints.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
