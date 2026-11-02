package de.lulurobb.keypassmobile.platform.setup

import de.lulurobb.keypassmobile.core.sync.RemoteError
import de.lulurobb.keypassmobile.core.sync.RemoteException
import de.lulurobb.keypassmobile.core.sync.RemoteSource
import de.lulurobb.keypassmobile.core.sync.RemoteStat
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.session.ServerChange
import de.lulurobb.keypassmobile.platform.session.Session
import de.lulurobb.keypassmobile.platform.session.SourceSwitch
import de.lulurobb.keypassmobile.platform.session.SourceSwitchResult
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.SettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.platform.settings.TrustedServer
import de.lulurobb.keypassmobile.platform.smb.ServerNames
import de.lulurobb.keypassmobile.platform.smb.remoteErrorOf
import de.lulurobb.keypassmobile.platform.smb.smbPath
import de.lulurobb.keypassmobile.smb.FolderListing
import de.lulurobb.keypassmobile.smb.SmbException
import de.lulurobb.keypassmobile.smb.SmbServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.time.Clock
import java.util.UUID

/**
 * First setup and source change (DS-02, DS-04, DS-06): connect, browse
 * shares and folders, choose the file. Keeps one SMB connection and a copy
 * of the SMB password while browsing; [close] drops both. Create it with
 * KpmPlatform.startSourceSetup() and close it when leaving the flow.
 *
 * Source change: the new file goes through the normal candidate path. The
 * old offline copy stays on disk until the new file opened (SY-05), then the
 * new file replaces it. Until then it is not opened as the database of the new
 * source (SyncEngine.markSourceChanged). Locked (first setup), the file becomes
 * the candidate and the next typed-password unlock opens it. Unlocked (change
 * from the settings), it is opened right away with the key in memory if it
 * fits, otherwise kept as candidate for an unlock with its own master password.
 * The SMB password of the current source can be reused ([connectWithStoredPassword]).
 *
 * The connection outlives a cancelled [chooseFile] until its source change is
 * finished: [close] then waits for it, and [connect] and [connectWithStoredPassword]
 * wait before they replace the connection.
 */
class SourceSetup internal constructor(
    private val connector: SetupConnector,
    private val settings: SettingsRepository,
    private val smbPasswords: SmbPasswords,
    private val session: Session,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
) : Closeable {

    private val mutex = Mutex()
    private var server: SmbServer? = null
    private var password: CharArray? = null
    private var guid: UUID? = null
    private var connection: SetupConnection? = null

    /** The source change of the last [chooseFile]; it may outlive a cancelled caller. */
    @Volatile
    private var switchJob: Job? = null

    /** [close] was called while [switchJob] ran: do it when the job is done. Guarded by [closeLock]. */
    private var closeAfterSwitch = false
    private val closeLock = Any()

    /** Waits for a source change that a cancelled [chooseFile] left running, then forgets a deferred close. */
    private suspend fun awaitSwitch() {
        switchJob?.join()
        synchronized(closeLock) { closeAfterSwitch = false }
    }

    /**
     * Connects and lists the shares. The GUID of this connection is remembered
     * and stored with the source (DS-04). For the host of the current source
     * the remembered GUID must match; a different server yields
     * [ConnectError.OtherServer] before any login. To accept it, call again
     * with [acceptServerGuid]. [password] is copied; the caller wipes its array.
     */
    suspend fun connect(address: ServerAddress, password: CharArray, acceptServerGuid: UUID? = null): ConnectResult =
        mutex.withLock {
            awaitSwitch()
            withContext(io) { connectLocked(address, password, acceptServerGuid) }
        }

    /**
     * True when [connectWithStoredPassword] can be used for [address]: a SMB
     * password is stored and host, port, user and domain are those of the
     * current source (source change from the settings). Otherwise the password field
     * must be shown.
     */
    fun canUseStoredPassword(address: ServerAddress): Boolean =
        storedPasswordApplies(settings.settings.value.source, address) && smbPasswords.hasStoredValue()

    /**
     * Like [connect], but with the SMB password stored for the current source
     * (DS-03), so a source change does not ask for it again. Only when
     * [canUseStoredPassword]; otherwise (or when the stored password cannot be
     * read) the result is [ConnectError.PasswordRequired] and nothing was sent
     * to the server. A stored password the server rejects yields
     * [ConnectError.LoginFailed], then the UI asks for the password.
     */
    suspend fun connectWithStoredPassword(address: ServerAddress, acceptServerGuid: UUID? = null): ConnectResult =
        mutex.withLock {
            awaitSwitch()
            withContext(io) {
                if (!storedPasswordApplies(settings.settings.value.source, address)) {
                    return@withContext ConnectResult.Failed(ConnectError.PasswordRequired)
                }
                val stored = smbPasswords.load() ?: return@withContext ConnectResult.Failed(ConnectError.PasswordRequired)
                try {
                    connectLocked(address, stored, acceptServerGuid)
                } finally {
                    stored.fill('\u0000')
                }
            }
        }

    /** Caller holds [mutex] and runs on [io]. */
    private fun connectLocked(address: ServerAddress, password: CharArray, acceptServerGuid: UUID?): ConnectResult {
        closeNow()
        val server = try {
            SmbServer(address.host.trim(), address.port, address.domain.trim(), address.user.trim())
        } catch (_: IllegalArgumentException) {
            return ConnectResult.Failed(ConnectError.Unknown)
        }
        val current = settings.settings.value
        val expected = acceptServerGuid ?: rememberedGuidFor(current, server)
        val connected = try {
            open(server, password, expected)
        } catch (e: SmbException) {
            return ConnectResult.Failed(connectErrorOf(e, server, current))
        }
        this.server = server
        this.password = password.copyOf()
        this.guid = connected.serverGuid
        connection = connected
        val shares = try {
            connected.listShares().map { ShareEntry(it.name, it.comment) }
        } catch (_: SmbException) {
            null
        }
        return ConnectResult.Connected(
            ServerInfo(
                name = ServerNames.fromGuid(connected.serverGuid) ?: server.host,
                guid = connected.serverGuid,
                dialect = connected.dialect,
                encrypted = connected.isEncrypted,
            ),
            shares,
        )
    }

    /**
     * Lists [path] (folders below [share]) without system folders. A dropped
     * connection is re-established once.
     */
    suspend fun listFolder(share: String, path: List<String>): FolderResult = mutex.withLock {
        withContext(io) {
            val items = try {
                withConnection { it.listFolder(share, smbPath(path)) }
            } catch (e: SmbException) {
                return@withContext FolderResult.Failed(browseErrorOf(e.reason))
            } catch (_: NotConnected) {
                return@withContext FolderResult.Failed(BrowseError.NOT_CONNECTED)
            }
            filterListing(items)
        }
    }

    /**
     * Saves [share] and [filePath] (folders and file name) as source together
     * with the SMB password (keystore, DS-03) and the server GUID, after the
     * file was downloaded successfully through the open connection. On failure
     * nothing is saved and nothing is left behind: the previous password,
     * offline copy and source are as before.
     *
     * The whole change runs as one unit in the session ([Session.startSwitch]):
     * it finishes even when the caller is cancelled, no other sync runs in
     * between and no unlock can start meanwhile. The new SMB password is stored
     * only right before the source is saved. When the file or server differs from
     * the current source and the new file waits as candidate, the old offline copy
     * is set aside before. If a vault was open (or opening), the session is locked
     * then ([LockReason.SourceChanged]) because it is the old file: the session
     * state turns Locked before this returns.
     *
     * A change that fails restores the previous password (or clears it if that is
     * not possible, so it is asked for again), the old offline copy and the old
     * source, and deletes the new file's candidate. One case cannot be restored
     * exactly: unlocked, the new file may open with the key in memory and replace
     * the offline copy right away. If the change fails after that, the offline copy
     * is deleted and the session locked with [LockReason.SourceChanged], so the old
     * source never shows the new file; the next sync downloads the old file again.
     * A process death in the middle is undone at the next start (see
     * Session.recoverInterruptedSwitch).
     */
    suspend fun chooseFile(share: String, filePath: List<String>): ChooseResult = mutex.withLock {
        require(filePath.isNotEmpty()) { "filePath must name a file" }
        val server = this.server
        val password = this.password
        val guid = this.guid
        if (server == null || password == null || guid == null) return@withLock ChooseResult.Failed(SyncProblem.UNREACHABLE)
        val smbFile = smbPath(filePath)
        val connection = try {
            // A connection that went stale while browsing is replaced once (withConnection) instead of failing the choice.
            withContext(io) {
                withConnection { c ->
                    try {
                        c.stat(share, smbFile)
                    } catch (e: SmbException) {
                        // Anything but a dead connection is reported by the download itself.
                        if (e.reason == SmbException.Reason.UNREACHABLE || e.reason == SmbException.Reason.OTHER) throw e
                    }
                    c
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return@withLock ChooseResult.Failed(SyncProblem.UNREACHABLE)
        }
        val connectionRemote = ConnectionRemote(connection, share, smbFile)
        val source = SourceConfig(server.host, server.port, server.domain, server.username, share, filePath)
        val fileChanged = !source.sameFileAs(settings.settings.value.source)
        // Own copies: the switch may outlive this call (cancelled caller) and close() wipes ours.
        val newPassword = password.copyOf()
        var previousPassword: CharArray? = null
        var replacing = false
        val change = object : SourceSwitch {
            override val remote: RemoteSource = connectionRemote
            override val target: SourceConfig = source
            override val differs: Boolean = fileChanged

            override fun stage() {
                previousPassword = smbPasswords.load()
                replacing = true
                smbPasswords.save(newPassword)
            }

            override fun commit() {
                val now = clock.instant()
                settings.update { s ->
                    val trusted = s.trustedServer?.takeIf { it.guid == guid } ?: TrustedServer(guid, now)
                    s.copy(source = source, trustedServer = trusted, pendingServerGuid = null)
                }
            }

            override fun rollback() {
                // Nothing was replaced if saving was never reached.
                if (!replacing) return
                // Never leave the new server's password next to the old source (DS-04): if the old one cannot be put back, clear.
                val previous = previousPassword
                try {
                    if (previous != null) smbPasswords.save(previous) else smbPasswords.clear()
                } catch (_: Exception) {
                    smbPasswords.clear()
                }
            }

            override fun finish() {
                newPassword.fill('\u0000')
                previousPassword?.fill('\u0000')
            }
        }
        val job = session.startSwitch(change)
        switchJob = job
        when (val switched = job.await()) {
            is SourceSwitchResult.Failed -> ChooseResult.Failed(switched.problem)
            is SourceSwitchResult.Saved -> ChooseResult.Saved(connectionRemote.size ?: 0L, fileChanged, switched.sync)
        }
    }

    /**
     * Drops the connection and overwrites the password copy. While a source change
     * of [chooseFile] is still running (its caller was cancelled), this happens when
     * it has finished, so the connection is never pulled away under it.
     */
    override fun close() {
        val job = switchJob
        if (job != null && job.isActive) {
            synchronized(closeLock) { closeAfterSwitch = true }
            job.invokeOnCompletion {
                synchronized(closeLock) {
                    if (closeAfterSwitch) {
                        closeAfterSwitch = false
                        closeNow()
                    }
                }
            }
            return
        }
        closeNow()
    }

    private fun closeNow() {
        dropConnection()
        password?.fill('\u0000')
        password = null
        server = null
        guid = null
    }

    private fun dropConnection() {
        connection?.close()
        connection = null
    }

    private fun open(server: SmbServer, password: CharArray, expected: UUID?): SetupConnection {
        val copy = password.copyOf()
        try {
            return connector.connect(server, copy, expected)
        } finally {
            copy.fill('\u0000')
        }
    }

    private class NotConnected : Exception()

    /** Runs [block] on the connection; reconnects once (same server GUID) if the connection dropped. */
    private fun <T> withConnection(block: (SetupConnection) -> T): T {
        val current = connection ?: reconnect()
        try {
            return block(current)
        } catch (e: SmbException) {
            if (e.reason != SmbException.Reason.UNREACHABLE && e.reason != SmbException.Reason.OTHER) throw e
        }
        return block(reconnect())
    }

    private fun reconnect(): SetupConnection {
        dropConnection()
        val server = server ?: throw NotConnected()
        val password = password ?: throw NotConnected()
        return open(server, password, guid).also { connection = it }
    }

    /** RemoteSource over the setup's connection; records the size for the UI. */
    private class ConnectionRemote(
        private val connection: SetupConnection,
        private val share: String,
        private val path: String,
    ) : RemoteSource {
        @Volatile
        var size: Long? = null

        override fun stat(): RemoteStat = call {
            connection.stat(share, path).let {
                size = it.size
                RemoteStat(it.size, it.modified)
            }
        }

        override fun download(target: File) {
            call { connection.download(share, path, target) }
        }

        private inline fun <T> call(block: () -> T): T = try {
            block()
        } catch (e: SmbException) {
            throw RemoteException(remoteErrorOf(e.reason), "SMB ${e.reason}", e)
        }
    }

    internal companion object {
        /** Folder names that are never shown (NAS and Windows system folders), compared case-insensitively. */
        private val SYSTEM_FOLDERS = setOf("#recycle", "#snapshot", "@eadir", "\$recycle.bin", "system volume information")

        fun isSystemFolder(name: String): Boolean {
            val n = name.trim()
            return n.isEmpty() || n.lowercase() in SYSTEM_FOLDERS || n[0] == '.' || n[0] == '$' || n[0] == '@'
        }

        /**
         * System folders are left out without being counted; [FolderResult.Listed.hiddenCount]
         * is the number of files that are no .kdbx files (the SMB layer counts them).
         */
        fun filterListing(listing: FolderListing): FolderResult.Listed {
            val folders = ArrayList<String>()
            val files = ArrayList<KdbxFileEntry>()
            for (item in listing.items) {
                when {
                    item.isFolder && isSystemFolder(item.name) -> Unit
                    item.isFolder -> folders += item.name
                    else -> files += KdbxFileEntry(item.name, item.size, item.modified)
                }
            }
            return FolderResult.Listed(folders, files, listing.otherFileCount)
        }

        /**
         * The stored SMB password belongs to the account (host, port, user, domain) of
         * the current source. Another port is another service: no password goes there
         * without the user typing it (DS-04).
         */
        fun storedPasswordApplies(source: SourceConfig?, address: ServerAddress): Boolean =
            source != null &&
                source.host.equals(address.host.trim(), ignoreCase = true) &&
                source.port == address.port &&
                source.user.equals(address.user.trim(), ignoreCase = true) &&
                source.domain.equals(address.domain.trim(), ignoreCase = true)

        /** The remembered GUID applies only to the host and port of the current source. */
        fun rememberedGuidFor(current: KpmSettings, server: SmbServer): UUID? {
            val source = current.source ?: return null
            if (!source.sameServerAs(server.host, server.port)) return null
            return current.trustedServer?.guid
        }

        fun connectErrorOf(e: SmbException, server: SmbServer, current: KpmSettings): ConnectError = when (e.reason) {
            SmbException.Reason.UNREACHABLE -> ConnectError.Unreachable
            SmbException.Reason.AUTH_FAILED -> ConnectError.LoginFailed
            SmbException.Reason.SERVER_CHANGED -> {
                val newGuid = e.serverGuid
                if (newGuid == null) {
                    ConnectError.Unknown
                } else {
                    val remembered = current.trustedServer?.guid
                    ConnectError.OtherServer(
                        ServerChange(
                            host = server.host,
                            rememberedGuid = remembered,
                            rememberedName = remembered?.let(ServerNames::fromGuid),
                            newGuid = newGuid,
                            newName = ServerNames.fromGuid(newGuid),
                        ),
                    )
                }
            }
            SmbException.Reason.PROTOCOL -> ConnectError.Unsupported
            else -> ConnectError.Unknown
        }

        fun browseErrorOf(reason: SmbException.Reason): BrowseError = when (remoteErrorOf(reason)) {
            RemoteError.NOT_FOUND -> if (reason == SmbException.Reason.SHARE_NOT_FOUND) BrowseError.SHARE_NOT_FOUND else BrowseError.NOT_FOUND
            RemoteError.ACCESS_DENIED -> BrowseError.ACCESS_DENIED
            RemoteError.UNREACHABLE -> BrowseError.UNREACHABLE
            else -> BrowseError.OTHER
        }
    }
}
