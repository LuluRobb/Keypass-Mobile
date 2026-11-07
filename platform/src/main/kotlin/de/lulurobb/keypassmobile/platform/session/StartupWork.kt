package de.lulurobb.keypassmobile.platform.session

import de.lulurobb.keypassmobile.platform.settings.FileSource
import de.lulurobb.keypassmobile.platform.settings.SettingsRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * What the process does once at its start, in this order (called by KpmPlatform):
 *
 * 1. Finishes undoing a source change that a process death interrupted; unlocks wait for it.
 * 2. Releases what does not fit the saved source (DS-15). A permission taken for a picked file
 *    whose source was never saved (process death between the check and the commit) has no journal
 *    record, so the recovery cannot know it. Silent and idempotent; a failure is ignored, the next
 *    start or source change repeats it.
 *
 * [cleanupDone] is completed when steps 1 and 2 are over, whatever their outcome (also when the
 * recovery or the cleanup fails), so that nothing waiting for it can hang. A file pick that arrives
 * right after a restart waits for it before it takes its permission (SafFileSourceSetup.accept):
 * otherwise the cleanup could release the new permission, which is not the saved source's own yet.
 *
 * 3. A file on the phone is a cheap local read: it is checked at every start, silently, so that a
 *    moved file, a lost permission or a newer version shows up without waiting for the worker (SY-09).
 */
internal suspend fun runStartupWork(
    session: Session,
    cleanup: SourceCleanup,
    settings: SettingsRepository,
    io: CoroutineDispatcher,
    cleanupDone: CompletableDeferred<Unit> = CompletableDeferred(),
) {
    try {
        session.recoverInterruptedSwitch()
        try {
            withContext(io) { cleanup.reconcile(settings.settings.value.source) }
        } catch (_: Exception) {
            // Best effort, see above.
        }
    } finally {
        cleanupDone.complete(Unit)
    }
    val s = settings.settings.value
    if (s.setupCompleted && s.source is FileSource) session.syncNow(SyncTrigger.SCHEDULED)
}
