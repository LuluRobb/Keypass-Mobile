package de.lulurobb.keypassmobile.platform.session

import de.lulurobb.keypassmobile.core.sync.RemoteSource
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.settings.SourceConfig

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

    /** The new file or server differs from the current source. */
    val differs: Boolean

    /** Store what must be stored with the new source (the SMB password). Called right before [commit]. May throw. */
    fun stage()

    /** Save the new source. May throw. */
    fun commit()

    /** After any failure, if [stage] was called: restore what [stage] replaced. */
    fun rollback()

    /** Last call in every case: wipe copies of secrets. */
    fun finish()
}

/** Result of [Session.switchSource]. */
internal sealed interface SourceSwitchResult {
    /** The new source is saved; [sync] is the result of the download. */
    data class Saved(val sync: SyncResult) : SourceSwitchResult

    /** Nothing changed: the previous source, password and offline copy are as before. */
    data class Failed(val problem: SyncProblem) : SourceSwitchResult
}
