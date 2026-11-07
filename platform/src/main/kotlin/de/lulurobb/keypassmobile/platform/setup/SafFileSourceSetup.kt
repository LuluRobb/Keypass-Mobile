package de.lulurobb.keypassmobile.platform.setup

import de.lulurobb.keypassmobile.kdbx.isKdbxSignature
import de.lulurobb.keypassmobile.platform.saf.DocumentAccess
import de.lulurobb.keypassmobile.platform.saf.SafRemoteSource
import de.lulurobb.keypassmobile.platform.session.RemoteHandle
import de.lulurobb.keypassmobile.platform.session.Session
import de.lulurobb.keypassmobile.platform.session.SourceCleanup
import de.lulurobb.keypassmobile.platform.session.SourceSwitch
import de.lulurobb.keypassmobile.platform.session.SourceSwitchResult
import de.lulurobb.keypassmobile.platform.settings.FileSource
import de.lulurobb.keypassmobile.platform.settings.SettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.net.URLDecoder

/**
 * [FileSourceSetup] over the Storage Access Framework (DS-08 to DS-15). The system
 * picker is launched by the UI; this object takes the persisted read permission,
 * runs the silent check, and runs the source change as one unit in the session
 * ([Session.startSwitch]), so the lock rule of DS-13 is the one of every source change.
 *
 * It remembers the permissions it took and has not saved yet; [close] gives them back.
 * The permission of the current file source is never given back here (re-picking the
 * same file keeps it). The permissions of the old source are released by
 * [DocumentSourceCleanup] after the new source is saved.
 *
 * @param remoteFor the remote of one document for the source change and the older-version
 *   check; the default reads through [documents].
 * @param startupDone completed when the cleanup of permissions at the start of the process is over
 *   (see runStartupWork); [accept] waits for it, so the cleanup never sees a permission taken by a
 *   pick that arrives right after a restart. Default: already completed.
 */
internal class SafFileSourceSetup(
    private val documents: DocumentAccess,
    private val settings: SettingsRepository,
    private val session: Session,
    private val io: CoroutineDispatcher,
    private val remoteFor: (String) -> RemoteHandle = { SafRemoteSource(documents, it) },
    private val startupDone: Deferred<Unit> = CompletableDeferred(Unit),
) : FileSourceSetup {

    private val lock = Any()

    /** Permissions taken by [accept] and not saved yet. Guarded by [lock]. */
    private val pending = LinkedHashSet<String>()

    /** The source change of the last [choose]; it may outlive a cancelled caller. */
    @Volatile
    private var switchJob: Job? = null

    /** D10: [Session.unlockedMark] at the start of this change; null when it started locked or in the first run. */
    private val startedUnlocked: Int? = session.unlockedMark()

    // Some providers need a binder call for the parent folder: not on the main thread.
    override suspend fun initialFolder(rejectedUri: String?, changing: Boolean): String = withContext(io) {
        if (rejectedUri != null) folderOf(rejectedUri)?.let { return@withContext it }
        val s = settings.settings.value
        if (changing) {
            val current = s.source as? FileSource
            if (current != null) folderOf(current.uri)?.let { return@withContext it }
        }
        s.lastPickedFolder ?: documents.internalStorageRoot()
    }

    private fun folderOf(uri: String): String? = try {
        documents.folderOf(uri)
    } catch (_: Exception) {
        null
    }

    override suspend fun accept(uri: String): FileCheck {
        startupDone.await()
        return withContext(io) { acceptOnIo(uri) }
    }

    private fun acceptOnIo(uri: String): FileCheck {
        val check = check(uri)
        if (check !is FileCheck.Ok) discard(uri)
        return check
    }

    private fun check(uri: String): FileCheck {
        // 1. The temporary grant of the picker is still valid now; make it lasting.
        try {
            documents.takeReadPermission(uri)
            synchronized(lock) { pending += uri }
        } catch (_: Exception) {
            remember(uri)
            return FileCheck.NoLastingAccess(nameOf(uri) ?: fallbackName(uri))
        }
        // 2. DS-10: every pick, accepted or not, is where the next picker starts.
        remember(uri)
        // 3. Does it exist and can it be queried?
        val info = try {
            documents.query(uri)
        } catch (_: Exception) {
            null
        } ?: return FileCheck.Unreadable(fallbackName(uri))
        val name = info.displayName?.takeIf { it.isNotBlank() } ?: fallbackName(uri)
        // 4. The first bytes.
        val head = try {
            documents.open(uri).use { it.readNBytes(SIGNATURE_BYTES) }
        } catch (_: Exception) {
            // IOException (also not found) or SecurityException.
            return FileCheck.Unreadable(name)
        }
        // 5. Only the signatures: no key is needed to tell a database from any other file.
        if (!isKdbxSignature(head)) return FileCheck.NotKdbx(name)
        // 6.
        val place = try {
            documents.placeOf(uri)
        } catch (_: Exception) {
            null
        }
        return FileCheck.Ok(PickedFile(uri, name, info.sizeBytes, info.lastModified, place))
    }

    /** Stores the folder of [uri] as the start of the next pick. */
    private fun remember(uri: String) {
        val folder = folderOf(uri) ?: return
        try {
            settings.update { it.copy(lastPickedFolder = folder) }
        } catch (_: Exception) {
            // Only a convenience; the start of the next pick falls back to an older place.
        }
    }

    /** The name the provider reports, null when it does not (or access is missing). */
    private fun nameOf(uri: String): String? = try {
        documents.query(uri)?.displayName?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    override suspend fun olderVersion(file: PickedFile): OlderVersion? {
        val remote = remoteFor(file.uri)
        try {
            val check = session.olderVersionCheck(remote) ?: return null
            return OlderVersion(file.displayName, check.fileModified, check.openModified)
        } finally {
            remote.close()
        }
    }

    override suspend fun choose(file: PickedFile): ChooseResult {
        val source = FileSource(file.uri, file.displayName, file.place)
        val current = settings.settings.value.source
        val fileChanged = !(current is FileSource && current.uri == file.uri)
        val remote = remoteFor(file.uri)
        val change = object : SourceSwitch {
            override val remote: RemoteHandle = remote
            override val target: SourceConfig = source
            override val differs: Boolean = fileChanged
            override val unlockedMark: Int? = startedUnlocked

            // Nothing to store: the permission is taken already, and a file has no password.
            override fun stage() = Unit

            override fun commit() {
                // The server identity belongs to the old SMB source (DS-04); DocumentSourceCleanup repeats this.
                settings.update { it.copy(source = source, trustedServer = null, pendingServerGuid = null) }
            }

            override fun rollback() = Unit

            override fun finish() = remote.close()
        }
        val job = session.startSwitch(change)
        switchJob = job
        return when (val switched = job.await()) {
            is SourceSwitchResult.Failed -> {
                discard(file.uri)
                ChooseResult.Failed(switched.problem)
            }
            // D10: the app locked before the commit. The grant of the new file goes back (never the current source's).
            SourceSwitchResult.Discarded -> {
                discard(file.uri)
                ChooseResult.Discarded
            }
            is SourceSwitchResult.Saved -> {
                synchronized(lock) { pending -= file.uri }
                ChooseResult.Saved(file.sizeBytes ?: 0L, fileChanged, switched.sync, lockedForNewDatabase = switched.locked)
            }
        }
    }

    override fun discard(uri: String) {
        synchronized(lock) { pending -= uri }
        val current = settings.settings.value.source
        if (current is FileSource && current.uri == uri) return
        documents.releaseReadPermission(uri)
    }

    /**
     * Gives back the permissions taken and not saved. While a source change of [choose]
     * is still running (its caller was cancelled) this happens when it has finished: if it
     * saved the file, [discard] keeps that permission because it belongs to the current source.
     */
    override fun close() {
        val job = switchJob
        if (job != null && job.isActive) {
            job.invokeOnCompletion { discardPending() }
        } else {
            discardPending()
        }
    }

    private fun discardPending() {
        val uris = synchronized(lock) { pending.toList() }
        for (uri in uris) {
            try {
                discard(uri)
            } catch (_: Exception) {
                // Best effort; the next successful source change releases every foreign permission.
            }
        }
    }

    internal companion object {
        /** Signature, version and the rest of the fixed start that [isKdbxSignature] looks at. */
        const val SIGNATURE_BYTES = 12

        /**
         * Name for a message when the provider gives none: the last segment of the document ID,
         * after "/" or ":" (e.g. "primary:Syncthing/KeePass/Pass.kdbx" gives "Pass.kdbx").
         */
        fun fallbackName(uri: String): String {
            val path = uri.substringBefore('?').substringBefore('#').trimEnd('/')
            val last = path.substringAfterLast('/')
            val decoded = try {
                URLDecoder.decode(last.replace("+", "%2B"), "UTF-8")
            } catch (_: IllegalArgumentException) {
                last
            }
            val name = decoded.substringAfterLast('/').substringAfterLast(':')
            return name.ifEmpty { decoded.ifEmpty { uri } }
        }
    }
}

/**
 * DS-15: what must fit the saved source. A file source keeps exactly its own persisted read
 * permission, and no SMB password and no remembered server; an SMB source keeps no file
 * permission. Silent and idempotent, run by the session after every source change and by the
 * recovery of an interrupted one. A source of null (none saved) releases every permission.
 */
internal class DocumentSourceCleanup(
    private val documents: DocumentAccess,
    private val settings: SettingsRepository,
    private val smbPasswords: SmbPasswords,
) : SourceCleanup {
    override fun reconcile(source: SourceConfig?) {
        val keep = (source as? FileSource)?.uri
        for (uri in documents.persistedReadUris()) {
            if (uri != keep) documents.releaseReadPermission(uri)
        }
        if (source is FileSource) {
            smbPasswords.clear()
            settings.update { s ->
                if (s.trustedServer == null && s.pendingServerGuid == null) s else s.copy(trustedServer = null, pendingServerGuid = null)
            }
        }
    }
}
