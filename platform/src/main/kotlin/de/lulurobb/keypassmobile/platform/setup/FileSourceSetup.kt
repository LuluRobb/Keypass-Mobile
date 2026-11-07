package de.lulurobb.keypassmobile.platform.setup

import java.io.Closeable

/**
 * First setup and source change with a file on the phone (DS-08 to DS-15).
 * The system file picker itself is launched by the UI; this object checks the
 * result, holds the persisted read permission and runs the source change.
 * Create it with KpmPlatform.startFileSetup() and close it when leaving the
 * flow: [close] releases a permission that was taken but not saved.
 */
interface FileSourceSetup : Closeable {
    /**
     * Document URI where the picker should start (EXTRA_INITIAL_URI, DS-10). First
     * match wins: the folder of [rejectedUri] after an error; when [changing] and
     * the current source is a file, the folder of that file; the last picked
     * folder; the root of the internal storage. Suspends: a provider may need a
     * binder call for the folder, which runs off the main thread.
     */
    suspend fun initialFolder(rejectedUri: String?, changing: Boolean): String

    /**
     * Silent check of a picked file (DS-11, DS-12): takes the persisted read
     * permission, remembers the folder as last picked folder, reads the name and
     * the signature. Anything but [FileCheck.Ok] has already called [discard].
     */
    suspend fun accept(uri: String): FileCheck

    /**
     * DS-14: the picked [file] is older than the version that is open now, and it is
     * the same database. Null when no dialog is due (first run, locked, newer file,
     * other database, wrong key, any error).
     */
    suspend fun olderVersion(file: PickedFile): OlderVersion?

    /**
     * Makes [file] the source (DS-13). Discards the uri itself when the result is
     * [ChooseResult.Failed] or [ChooseResult.Discarded]. A setup created while the app was
     * unlocked yields [ChooseResult.Discarded] when the app has locked before the commit (D10).
     */
    suspend fun choose(file: PickedFile): ChooseResult

    /**
     * Releases the read permission taken for [uri] after a rejected or cancelled
     * pick. Never releases the uri of the current file source (re-picking the same file).
     */
    fun discard(uri: String)
}
