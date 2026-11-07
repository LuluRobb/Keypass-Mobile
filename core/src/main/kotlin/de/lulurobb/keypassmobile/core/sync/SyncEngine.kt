package de.lulurobb.keypassmobile.core.sync

import de.lulurobb.keypassmobile.model.Vault
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.PortUnreachableException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.Clock
import java.time.Instant

/**
 * Offline copy, candidate and synchronization with the share (SY-03 to SY-08, EN-06).
 *
 * Local files live in [directory]: the offline copy (always a complete,
 * encrypted KDBX that opened before, SY-01, SY-05), an optional candidate
 * (newer file from the background sync that could not be opened yet, SY-06),
 * a temporary download and a small state file with remote size and
 * modification time per file, last successful sync and last problem.
 * Nothing decrypted is ever written.
 *
 * Threading: all functions block; call them off the main thread, except
 * [status]. Construction reads the small state file. The UI refresh and the
 * background worker run in the same process and must share one instance per
 * directory (the state is cached in memory). Remote operations
 * ([refreshAfterUnlock], [backgroundSync]) are serialized among each other.
 * Local file changes are serialized with [unlock], which never waits for the
 * share (SY-03), only for a running file replacement.
 *
 * The password hash is only borrowed for the duration of a call; the engine
 * neither copies nor keeps it (SE-04).
 */
class SyncEngine(
    directory: File,
    private val opener: VaultOpener,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val store = LocalStore(directory)

    /** Guards local files and [state]. Acquired after [remoteLock], never before. */
    private val fileLock = Any()

    /** Serializes network syncs and the temporary download file. */
    private val remoteLock = Any()

    @Volatile
    private var state: SyncState = store.loadState()

    @Volatile
    private var openedVersion: OpenedVersion? = null

    /**
     * The vault of the last unlock is the file that is the offline copy on disk. False when the
     * candidate opened but could not be moved into place: the vault is then newer than the
     * offline copy, and a sync that finds the offline copy current confirms nothing about it.
     */
    @Volatile
    private var openedIsOffline = true

    /** Remote metadata of the file the last unlock or refresh opened; null when unknown (DS-14). */
    @Volatile
    private var openedMeta: FileMeta? = null

    /**
     * Remote metadata of the offline copy that [markSourceChanged] set aside, in memory only, so that
     * [undoSourceChanged] can bring back the sync state with the file. Null after a restart: the next
     * refresh then downloads once more (the recovery of an interrupted change).
     */
    private var setAsideMeta: FileMeta? = null

    /**
     * Opens the local database without contacting the share (SY-03).
     *
     * The candidate is tried first. If it opens, it replaces the offline copy.
     * If it does not open (wrong key or damaged), the offline copy is opened
     * with the same key and [UnlockResult.Opened.candidateRejected] tells the
     * UI to show a hint (EN-06). A candidate with a wrong key is kept, because
     * a newly typed password may open it later; a damaged one is deleted.
     * The first setup downloads into the candidate slot (see [backgroundSync]),
     * so the first unlock promotes it.
     */
    fun unlock(passwordHash: ByteArray, keySource: KeySource): UnlockResult = synchronized(fileLock) {
        var candidateFailure: OpenFailure? = null
        if (store.candidate.isFile) {
            when (val attempt = tryOpen(store.candidate, passwordHash)) {
                is Attempt.Opened -> {
                    val candidateMeta = store.validMeta(store.candidate, state.candidate)
                    openedIsOffline = promoteCandidate()
                    openedMeta = candidateMeta
                    openedVersion = OpenedVersion.OFFLINE_COPY
                    return UnlockResult.Opened(attempt.vault, newVersionOpened = true, candidateRejected = null)
                }
                is Attempt.Failed -> {
                    candidateFailure = attempt.failure
                    if (attempt.failure == OpenFailure.CORRUPT) {
                        // A damaged candidate never becomes usable; the next sync downloads again.
                        if (deleteCandidate()) commit(state.withProblem(SyncProblem.INVALID_FILE, now()))
                    }
                }
            }
        }

        if (!store.offline.isFile) {
            return when (candidateFailure) {
                null -> UnlockResult.NoDatabase
                OpenFailure.WRONG_KEY -> UnlockResult.WrongKey(keySource)
                OpenFailure.CORRUPT -> UnlockResult.Corrupt
            }
        }

        when (val attempt = tryOpen(store.offline, passwordHash)) {
            is Attempt.Opened -> {
                if (candidateFailure == OpenFailure.WRONG_KEY) {
                    // The key fits the offline copy but not the newer file:
                    // the master password was changed on the PC.
                    commit(state.copy(candidateRejected = true).withProblem(SyncProblem.NEW_VERSION_REJECTED, now()))
                }
                openedIsOffline = true
                openedMeta = store.validMeta(store.offline, state.offline)
                openedVersion = OpenedVersion.OFFLINE_COPY
                val rejection = when (candidateFailure) {
                    null -> null
                    OpenFailure.WRONG_KEY -> CandidateRejection.WRONG_KEY
                    OpenFailure.CORRUPT -> CandidateRejection.CORRUPT
                }
                return UnlockResult.Opened(attempt.vault, newVersionOpened = false, candidateRejected = rejection)
            }
            is Attempt.Failed -> {
                if (attempt.failure == OpenFailure.CORRUPT) {
                    // Keep the file, but forget its metadata so the next sync fetches it again.
                    commit(state.copy(offline = null))
                }
                return if (attempt.failure == OpenFailure.WRONG_KEY || candidateFailure == OpenFailure.WRONG_KEY) {
                    UnlockResult.WrongKey(keySource)
                } else {
                    UnlockResult.Corrupt
                }
            }
        }
    }

    /**
     * Checks the share after an unlock and on "Jetzt synchronisieren" while
     * unlocked (SY-04, SY-07). Compares size and modification time with the
     * offline copy. If they differ, downloads to a temporary file and opens it
     * with the key in memory; only then it replaces the offline copy (SY-05).
     * A version that the key does not open is kept as candidate. Errors are
     * returned as status; the offline copy is never lost.
     *
     * [beforeStore] runs on the opened new version before it replaces the offline
     * copy (e.g. to build the entry index). If it throws, the new file is dropped,
     * the offline copy stays and the result is [RefreshResult.Failed] with
     * [SyncProblem.INVALID_FILE]; the caller then owns nothing.
     */
    fun refreshAfterUnlock(
        remote: RemoteSource,
        passwordHash: ByteArray,
        beforeStore: (Vault) -> Unit = {},
    ): RefreshResult = synchronized(remoteLock) {
        store.deleteQuietly(store.download)
        val stat = try {
            remote.stat()
        } catch (e: Exception) {
            return RefreshResult.Failed(recordProblem(problemOf(e)))
        }

        val current = state
        if (store.validMeta(store.offline, current.offline)?.matches(stat) == true) {
            markUpToDate()
            // Whatever the unlock opened (the offline copy, even if the share was unreachable then):
            // the share still has this very file. Not for a vault that differs from the offline copy.
            if (openedIsOffline) openedVersion = OpenedVersion.SHARE
            return RefreshResult.UpToDate
        }
        markOpenedVersionOutdated()

        if (current.candidateRejected && store.validMeta(store.candidate, current.candidate)?.matches(stat) == true) {
            // Every unlock tries the candidate first, so this key already failed on this very file.
            recordProblem(SyncProblem.NEW_VERSION_REJECTED)
            return RefreshResult.NewVersionRejected
        }

        downloadToTemp(remote, stat)?.let { return RefreshResult.Failed(recordProblem(it)) }

        when (val attempt = tryOpen(store.download, passwordHash)) {
            is Attempt.Opened -> {
                try {
                    beforeStore(attempt.vault)
                } catch (e: Exception) {
                    attempt.vault.close()
                    store.deleteQuietly(store.download)
                    return RefreshResult.Failed(recordProblem(SyncProblem.INVALID_FILE))
                }
                val stored = synchronized(fileLock) {
                    try {
                        // The candidate is older than the file just downloaded.
                        store.delete(store.candidate)
                        store.moveAtomically(store.download, store.offline)
                        // The new file is in place: the offline copy of a previous source is obsolete.
                        store.deleteQuietly(store.previousOffline)
                        setAsideMeta = null
                        commit(state.copy(offline = FileMeta.of(stat, now())).withoutCandidate().withSuccess(now()))
                        true
                    } catch (e: IOException) {
                        if (!store.candidate.exists()) commit(state.withoutCandidate())
                        false
                    }
                }
                if (!stored) {
                    attempt.vault.close()
                    store.deleteQuietly(store.download)
                    return RefreshResult.Failed(recordProblem(SyncProblem.LOCAL_STORAGE))
                }
                openedIsOffline = true
                openedMeta = FileMeta.of(stat, now())
                openedVersion = OpenedVersion.SHARE
                return RefreshResult.Updated(attempt.vault)
            }
            is Attempt.Failed -> when (attempt.failure) {
                OpenFailure.WRONG_KEY -> {
                    val stored = synchronized(fileLock) {
                        try {
                            store.moveAtomically(store.download, store.candidate)
                            commit(
                                state.copy(candidate = FileMeta.of(stat, now()), candidateRejected = true)
                                    .withProblem(SyncProblem.NEW_VERSION_REJECTED, now()),
                            )
                            true
                        } catch (e: IOException) {
                            false
                        }
                    }
                    if (!stored) {
                        store.deleteQuietly(store.download)
                        return RefreshResult.Failed(recordProblem(SyncProblem.LOCAL_STORAGE))
                    }
                    return RefreshResult.NewVersionRejected
                }
                OpenFailure.CORRUPT -> {
                    store.deleteQuietly(store.download)
                    return RefreshResult.Failed(recordProblem(SyncProblem.INVALID_FILE))
                }
            }
        }
    }

    /**
     * Periodic sync without a key (SY-06), and "Jetzt synchronisieren" while
     * locked (SY-07). If the share has a version that differs from both the
     * offline copy and the candidate, it is downloaded, checked to look like
     * a KDBX file and stored as the new candidate for the next unlock.
     *
     * Also used for the first setup: without local files the download becomes
     * the candidate and the first unlock promotes it to the offline copy.
     */
    fun backgroundSync(remote: RemoteSource): BackgroundSyncResult = synchronized(remoteLock) {
        store.deleteQuietly(store.download)
        val stat = try {
            remote.stat()
        } catch (e: Exception) {
            return BackgroundSyncResult.Failed(recordProblem(problemOf(e)))
        }

        val current = state
        if (store.validMeta(store.offline, current.offline)?.matches(stat) == true) {
            markUpToDate()
            return BackgroundSyncResult.UpToDate
        }
        markOpenedVersionOutdated()

        if (store.validMeta(store.candidate, current.candidate)?.matches(stat) == true) {
            synchronized(fileLock) { commit(state.withSuccess(now())) }
            return BackgroundSyncResult.CandidateAlreadyPresent
        }

        downloadToTemp(remote, stat)?.let { return BackgroundSyncResult.Failed(recordProblem(it)) }

        val looksValid = try {
            opener.looksLikeKdbx(store.download)
        } catch (e: Exception) {
            false
        }
        if (!looksValid) {
            store.deleteQuietly(store.download)
            return BackgroundSyncResult.Failed(recordProblem(SyncProblem.INVALID_FILE))
        }

        val stored = synchronized(fileLock) {
            try {
                store.moveAtomically(store.download, store.candidate)
                commit(state.copy(candidate = FileMeta.of(stat, now()), candidateRejected = false).withSuccess(now()))
                true
            } catch (e: IOException) {
                false
            }
        }
        if (!stored) {
            store.deleteQuietly(store.download)
            return BackgroundSyncResult.Failed(recordProblem(SyncProblem.LOCAL_STORAGE))
        }
        return BackgroundSyncResult.CandidateDownloaded
    }

    /** Current sync status (SY-08). Cheap, does not block; safe on the main thread. */
    fun status(): SyncStatus {
        val current = state
        val hasCandidate = store.candidate.isFile
        val candidateMeta = if (hasCandidate) current.candidate else null
        val offlineMeta = if (store.offline.isFile) current.offline else null
        val pendingMeta = candidateMeta?.takeIf { !current.candidateRejected }
        val localMeta = pendingMeta ?: offlineMeta ?: candidateMeta
        return SyncStatus(
            hasLocalDatabase = store.offline.isFile || hasCandidate,
            openedVersion = openedVersion,
            lastSuccessfulSync = current.lastSuccessfulSync,
            lastProblem = current.lastProblem,
            lastProblemAt = current.lastProblemAt,
            candidate = when {
                !hasCandidate -> CandidateState.NONE
                current.candidateRejected -> CandidateState.REJECTED
                else -> CandidateState.PENDING
            },
            localModified = localMeta?.remoteModified?.takeIf { it != RemoteStat.UNKNOWN_MODIFIED },
            openedModified = openedMeta?.remoteModified?.takeIf { it != RemoteStat.UNKNOWN_MODIFIED },
        )
    }

    /**
     * Checks whether [remote] opens with [passwordHash] without touching any
     * state: stat, download into a probe file, size check, open, read the root
     * group UUID, close the vault and delete the file. No problem is recorded,
     * the offline copy, candidate and sync state stay as they are. Used to
     * compare a picked file with the open database (DS-14). Waits for a running
     * sync and costs a full download and key derivation.
     */
    fun probeOpen(remote: RemoteSource, passwordHash: ByteArray): ProbeResult = synchronized(remoteLock) {
        store.deleteQuietly(store.probe)
        try {
            val stat = try {
                remote.stat()
            } catch (e: Exception) {
                return ProbeResult.Failed(problemOf(e))
            }
            downloadToTemp(remote, stat, store.probe)?.let { return ProbeResult.Failed(it) }
            when (val attempt = tryOpen(store.probe, passwordHash)) {
                is Attempt.Opened -> {
                    val uuid = try {
                        attempt.vault.rootGroupUuid
                    } finally {
                        attempt.vault.close()
                    }
                    return ProbeResult.Opened(stat, uuid)
                }
                is Attempt.Failed -> return when (attempt.failure) {
                    OpenFailure.WRONG_KEY -> ProbeResult.WrongKey
                    OpenFailure.CORRUPT -> ProbeResult.Failed(SyncProblem.INVALID_FILE)
                }
            }
        } finally {
            store.deleteQuietly(store.probe)
        }
    }

    /**
     * The source now points to another file or server (settings change) and its
     * file waits as candidate. Call it after the download and before saving the
     * new source. The offline copy of the previous source is renamed
     * (atomically) to a file that is never opened: until the new file opened
     * (SY-05, then it is deleted) [unlock] cannot show the old file as the
     * database of the new source, whatever happens to the state file. Its remote
     * metadata is dropped as well. Waits for a running sync.
     *
     * Acts only when there is both an offline copy and a candidate. Without a
     * candidate the offline copy already is (or never was) the new file, and
     * renaming it would lose the database.
     *
     * @return true when the offline copy was set aside.
     * @throws IOException when the copy cannot be renamed; the source must not be changed then.
     */
    fun markSourceChanged(): Boolean {
        synchronized(remoteLock) {
            synchronized(fileLock) {
                if (!store.offline.isFile || !store.candidate.isFile) return false
                store.moveAtomically(store.offline, store.previousOffline)
                setAsideMeta = state.offline
                commit(state.copy(offline = null))
                return true
            }
        }
    }

    /**
     * Takes back [markSourceChanged] when the source change failed afterwards:
     * the set-aside copy becomes the offline copy again, with its remote metadata when
     * this process set it aside (after a restart the metadata is gone, so the next
     * refresh downloads once more).
     */
    fun undoSourceChanged() {
        synchronized(remoteLock) {
            synchronized(fileLock) {
                if (!store.previousOffline.isFile || store.offline.isFile) return
                store.moveAtomically(store.previousOffline, store.offline)
                val meta = setAsideMeta
                setAsideMeta = null
                if (meta != null && state.offline == null) commit(state.copy(offline = meta))
            }
        }
    }

    /**
     * Puts back the outcome of the last sync (time of the last success, last problem and its time) that
     * a discarded source change overwrote with the result of the foreign file. Nothing else changes.
     */
    fun restoreSyncOutcome(lastSuccessfulSync: Instant?, lastProblem: SyncProblem?, lastProblemAt: Instant?) {
        synchronized(remoteLock) {
            synchronized(fileLock) {
                commit(state.copy(lastSuccessfulSync = lastSuccessfulSync, lastProblem = lastProblem, lastProblemAt = lastProblemAt))
            }
        }
    }

    /** Deletes the candidate, e.g. the file of a source change that failed afterwards. Never throws. */
    fun discardCandidate() {
        synchronized(remoteLock) {
            synchronized(fileLock) {
                if (store.candidate.exists()) deleteCandidate()
            }
        }
    }

    /**
     * Deletes the offline copy, the candidate and all sync state, e.g. when
     * the source is changed to a different database. Waits for a running sync.
     *
     * @throws IOException when a file cannot be deleted.
     */
    fun reset() = synchronized(remoteLock) {
        synchronized(fileLock) {
            store.delete(store.download)
            store.delete(store.probe)
            store.delete(store.candidate)
            store.delete(store.offline)
            store.delete(store.previousOffline)
            setAsideMeta = null
            commit(SyncState())
            openedVersion = null
            openedIsOffline = true
            openedMeta = null
        }
    }

    // ---- internals ----

    private sealed interface Attempt {
        class Opened(val vault: Vault) : Attempt
        class Failed(val failure: OpenFailure) : Attempt
    }

    private fun tryOpen(file: File, passwordHash: ByteArray): Attempt = try {
        Attempt.Opened(opener.open(file, passwordHash))
    } catch (e: VaultOpenException) {
        Attempt.Failed(e.failure)
    } catch (e: Exception) {
        Attempt.Failed(OpenFailure.CORRUPT)
    }

    /** Candidate opened: it becomes the offline copy; false if it could not be moved. Caller holds [fileLock]. */
    private fun promoteCandidate(): Boolean {
        val meta = store.validMeta(store.candidate, state.candidate)
        try {
            store.moveAtomically(store.candidate, store.offline)
        } catch (e: IOException) {
            // The vault is open anyway; the candidate is tried again at the next unlock.
            commit(state.withProblem(SyncProblem.LOCAL_STORAGE, now()))
            return false
        }
        // Without metadata the next refresh downloads once more to learn the remote stat.
        // The new file is in place: the offline copy of a previous source is obsolete.
        store.deleteQuietly(store.previousOffline)
        setAsideMeta = null
        commit(state.copy(offline = meta).withoutCandidate())
        return true
    }

    /** Deletes the candidate; true when it is gone. Caller holds [fileLock]. */
    private fun deleteCandidate(): Boolean {
        try {
            store.delete(store.candidate)
        } catch (e: IOException) {
            return false
        }
        commit(state.withoutCandidate())
        return true
    }

    /** The offline copy equals the share: a candidate is stale (the share went back to this version). */
    private fun markUpToDate() = synchronized(fileLock) {
        if (store.candidate.exists()) deleteCandidate()
        commit(state.withSuccess(now()))
    }

    private fun markOpenedVersionOutdated() {
        if (openedVersion == OpenedVersion.SHARE) openedVersion = OpenedVersion.OFFLINE_COPY
    }

    /** Downloads into the temporary file; returns the problem or null on success. Caller holds [remoteLock]. */
    private fun downloadToTemp(remote: RemoteSource, stat: RemoteStat, target: File = store.download): SyncProblem? {
        try {
            remote.download(target)
        } catch (e: Exception) {
            store.deleteQuietly(target)
            return problemOf(e)
        }
        // Catches downloads that ended early without an error, and files changed while downloading.
        if (!target.isFile || target.length() != stat.size) {
            store.deleteQuietly(target)
            return SyncProblem.DOWNLOAD_INCOMPLETE
        }
        try {
            store.syncFile(target)
        } catch (e: IOException) {
            store.deleteQuietly(target)
            return SyncProblem.LOCAL_STORAGE
        }
        return null
    }

    private fun recordProblem(problem: SyncProblem): SyncProblem {
        synchronized(fileLock) { commit(state.withProblem(problem, now())) }
        return problem
    }

    /** Updates the state in memory and on disk (best effort). Caller holds [fileLock]. */
    private fun commit(next: SyncState) {
        state = next
        try {
            store.saveState(next)
        } catch (e: IOException) {
            // Stale metadata on disk only causes one more download later.
        }
    }

    private fun now(): Instant = clock.instant()

    private fun problemOf(e: Exception): SyncProblem {
        if (e is InterruptedException) Thread.currentThread().interrupt()
        return when (e) {
            is RemoteException -> when (e.error) {
                RemoteError.UNREACHABLE -> SyncProblem.UNREACHABLE
                RemoteError.SERVER_CHANGED -> SyncProblem.SERVER_CHANGED
                RemoteError.AUTH_FAILED -> SyncProblem.AUTH_FAILED
                RemoteError.NOT_FOUND -> SyncProblem.NOT_FOUND
                RemoteError.ACCESS_DENIED -> SyncProblem.ACCESS_DENIED
                RemoteError.OTHER -> SyncProblem.REMOTE_ERROR
            }
            is SocketTimeoutException, is ConnectException, is NoRouteToHostException,
            is UnknownHostException, is PortUnreachableException,
            -> SyncProblem.UNREACHABLE
            else -> SyncProblem.REMOTE_ERROR
        }
    }
}
