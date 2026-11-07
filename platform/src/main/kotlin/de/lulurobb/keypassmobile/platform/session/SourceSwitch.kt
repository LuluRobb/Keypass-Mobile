package de.lulurobb.keypassmobile.platform.session

import de.lulurobb.keypassmobile.core.sync.RemoteSource
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import java.time.Instant

/**
 * One source change (setup or settings), run by [Session.startSwitch] as a
 * unit: download, set the old copy aside, [stage], [commit]. Every function
 * runs on the IO dispatcher inside the session's scope, never in the caller's
 * coroutine.
 */
internal interface SourceSwitch {
    /** The new file as remote (the setup connection). Not closed by the session. */
    val remote: RemoteSource

    /** The new source, for the journal that undoes an interrupted change. */
    val target: SourceConfig

    /**
     * The new file or server differs from the current source. For a file target:
     * the current source is no file, or has another uri. A change between the two
     * source types always differs.
     */
    val differs: Boolean

    /**
     * D10: [Session.unlockedMark] taken when the change was started while the app was unlocked, null
     * when it was started locked (or the first run). A change with a mark is discarded
     * ([SourceSwitchResult.Discarded]) when the app has locked since: at the start of the switch and
     * again right before [stage], unless the new file has already replaced the old offline copy (then
     * the change completes as a locked change). Default: no mark.
     */
    val unlockedMark: Int? get() = null

    /** Store what must be stored with the new source (the SMB password; nothing for a file). Called right before [commit]. May throw. */
    fun stage()

    /** Save the new source. May throw. */
    fun commit()

    /** After any failure, if [stage] was called: restore what [stage] replaced. */
    fun rollback()

    /** Last call in every case: wipe copies of secrets. */
    fun finish()
}

/**
 * What the session cleans up after a source change (DS-15), without any UI message.
 * Called by [Session] with the new source after [SourceSwitch.commit] (under the sync
 * lock), and by the recovery of an interrupted change with the saved source. Must be
 * idempotent. A file source keeps only its own persisted read permission and drops the
 * SMB password and the remembered server; an SMB source keeps no file permission.
 * When it throws, the journal of the change stays and the next start repeats it.
 */
internal fun interface SourceCleanup {
    fun reconcile(source: SourceConfig?)
}

/**
 * The picked file is an older version of the database that is open now (DS-14).
 * [fileModified]: modification time of the picked file; [openModified]: modification
 * time of the file the open version was read from.
 */
internal data class OlderCheck(val fileModified: Instant, val openModified: Instant)

/** Result of [Session.switchSource]. */
internal sealed interface SourceSwitchResult {
    /**
     * The new source is saved; [sync] is the result of the download. [locked] is true
     * when the switch itself locked the session because the old copy was set aside
     * while it was not locked (the new file does not open with the key in memory,
     * DS-13). The UI then shows only the lock notice.
     */
    data class Saved(val sync: SyncResult, val locked: Boolean) : SourceSwitchResult

    /** Nothing changed: the previous source, password and offline copy are as before. */
    data class Failed(val problem: SyncProblem) : SourceSwitchResult

    /**
     * Nothing changed, like [Failed], but no problem to report: the change had an
     * [SourceSwitch.unlockedMark] and the app locked before the commit (D10).
     */
    data object Discarded : SourceSwitchResult
}
