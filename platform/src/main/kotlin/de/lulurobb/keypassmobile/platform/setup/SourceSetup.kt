package de.lulurobb.keypassmobile.platform.setup

import de.lulurobb.keypassmobile.core.sync.RemoteError
import de.lulurobb.keypassmobile.core.sync.RemoteException
import de.lulurobb.keypassmobile.core.sync.RemoteSource
import de.lulurobb.keypassmobile.core.sync.RemoteStat
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.keystore.SmbPasswordStore
import de.lulurobb.keypassmobile.platform.session.ServerChange
import de.lulurobb.keypassmobile.platform.session.Session
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.SettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.platform.settings.TrustedServer
import de.lulurobb.keypassmobile.platform.smb.ServerNames
import de.lulurobb.keypassmobile.platform.smb.remoteErrorOf
import de.lulurobb.keypassmobile.platform.smb.smbPath
import de.lulurobb.keypassmobile.smb.FolderItem
import de.lulurobb.keypassmobile.smb.SmbConnection
import de.lulurobb.keypassmobile.smb.SmbConnector
import de.lulurobb.keypassmobile.smb.SmbException
import de.lulurobb.keypassmobile.smb.SmbServer
import kotlinx.coroutines.CoroutineDispatcher
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
 * old offline copy stays usable until the new file opened (SY-05); nothing
 * is reset. Locked (first setup), the file becomes the candidate and the next
 * typed-password unlock opens it. Unlocked (change from the settings), it is
 * opened right away with the key in memory if it fits, otherwise kept as
 * candidate for an unlock with its own master password.
 */
class SourceSetup internal constructor(
    private val connector: SmbConnector,
    private val settings: SettingsRepository,
    private val smbPasswords: SmbPasswordStore,
    private val session: Session,
    private val clock: Clock,
    private val io: CoroutineDispatcher,
) : Closeable {

    private val mutex = Mutex()
    private var server: SmbServer? = null
    private var password: CharArray? = null
    private var guid: UUID? = null
    private var connection: SmbConnection? = null

    /**
     * Connects and lists the shares. The GUID of this connection is remembered
     * and stored with the source (DS-04). For the host of the current source
     * the remembered GUID must match; a different server yields
     * [ConnectError.OtherServer] before any login. To accept it, call again
     * with [acceptServerGuid]. [password] is copied; the caller wipes its array.
     */
    suspend fun connect(address: ServerAddress, password: CharArray, acceptServerGuid: UUID? = null): ConnectResult =
        mutex.withLock {
            withContext(io) {
                close()
                val server = try {
                    SmbServer(address.host.trim(), address.port, address.domain.trim(), address.user.trim())
                } catch (_: IllegalArgumentException) {
                    return@withContext ConnectResult.Failed(ConnectError.Unknown)
                }
                val current = settings.settings.value
                val expected = acceptServerGuid ?: rememberedGuidFor(current, server)
                val connected = try {
                    open(server, password, expected)
                } catch (e: SmbException) {
                    return@withContext ConnectResult.Failed(connectErrorOf(e, server, current))
                }
                this@SourceSetup.server = server
                this@SourceSetup.password = password.copyOf()
                this@SourceSetup.guid = connected.serverGuid
                connection = connected
                val shares = try {
                    connected.listShares().map { ShareEntry(it.name, it.comment) }
                } catch (_: SmbException) {
                    null
                }
                ConnectResult.Connected(
                    ServerInfo(
                        name = ServerNames.fromGuid(connected.serverGuid) ?: server.host,
                        guid = connected.serverGuid,
                        dialect = connected.dialect,
                        encrypted = connected.isEncrypted,
                    ),
                    shares,
                )
            }
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
     * nothing is saved.
     */
    suspend fun chooseFile(share: String, filePath: List<String>): ChooseResult = mutex.withLock {
        require(filePath.isNotEmpty()) { "filePath must name a file" }
        val server = this.server
        val password = this.password
        val guid = this.guid
        if (server == null || password == null || guid == null) return@withLock ChooseResult.Failed(SyncProblem.UNREACHABLE)
        val connection = try {
            withContext(io) { withConnection { it } }
        } catch (_: Exception) {
            return@withLock ChooseResult.Failed(SyncProblem.UNREACHABLE)
        }
        val remote = ConnectionRemote(connection, share, smbPath(filePath))
        val source = SourceConfig(server.host, server.port, server.domain, server.username, share, filePath)
        val before = settings.settings.value.source
        when (val result = session.syncWith(remote)) {
            is SyncResult.Failed -> ChooseResult.Failed(result.problem)
            SyncResult.NotConfigured -> ChooseResult.Failed(SyncProblem.REMOTE_ERROR)
            else -> {
                try {
                    withContext(io) { smbPasswords.save(password) }
                } catch (_: Exception) {
                    return@withLock ChooseResult.Failed(SyncProblem.LOCAL_STORAGE)
                }
                val now = clock.instant()
                settings.update { s ->
                    val trusted = s.trustedServer?.takeIf { it.guid == guid } ?: TrustedServer(guid, now)
                    s.copy(source = source, trustedServer = trusted, pendingServerGuid = null)
                }
                session.publishSync()
                ChooseResult.Saved(remote.size ?: 0L, fileChanged = !source.sameFileAs(before), sync = result)
            }
        }
    }

    /** Drops the connection and overwrites the password copy. */
    override fun close() {
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

    private fun open(server: SmbServer, password: CharArray, expected: UUID?): SmbConnection {
        val copy = password.copyOf()
        try {
            return connector.connect(server, copy, expected)
        } finally {
            copy.fill('\u0000')
        }
    }

    private class NotConnected : Exception()

    /** Runs [block] on the connection; reconnects once (same server GUID) if the connection dropped. */
    private fun <T> withConnection(block: (SmbConnection) -> T): T {
        val current = connection ?: reconnect()
        try {
            return block(current)
        } catch (e: SmbException) {
            if (e.reason != SmbException.Reason.UNREACHABLE && e.reason != SmbException.Reason.OTHER) throw e
        }
        return block(reconnect())
    }

    private fun reconnect(): SmbConnection {
        dropConnection()
        val server = server ?: throw NotConnected()
        val password = password ?: throw NotConnected()
        return open(server, password, guid).also { connection = it }
    }

    /** RemoteSource over the setup's connection; records the size for the UI. */
    private class ConnectionRemote(
        private val connection: SmbConnection,
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

        fun filterListing(items: List<FolderItem>): FolderResult.Listed {
            var hidden = 0
            val folders = ArrayList<String>()
            val files = ArrayList<KdbxFileEntry>()
            for (item in items) {
                when {
                    item.isFolder && isSystemFolder(item.name) -> hidden++
                    item.isFolder -> folders += item.name
                    else -> files += KdbxFileEntry(item.name, item.size, item.modified)
                }
            }
            return FolderResult.Listed(folders, files, hidden)
        }

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
