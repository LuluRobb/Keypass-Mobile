package de.lulurobb.keypassmobile.platform.setup

import de.lulurobb.keypassmobile.smb.FileStat
import de.lulurobb.keypassmobile.smb.FolderListing
import de.lulurobb.keypassmobile.smb.ShareInfo
import de.lulurobb.keypassmobile.smb.SmbConnection
import de.lulurobb.keypassmobile.smb.SmbConnector
import de.lulurobb.keypassmobile.smb.SmbServer
import java.io.Closeable
import java.io.File
import java.util.UUID

/** The SMB connection as [SourceSetup] uses it; a seam so the setup can be tested without a server. */
internal interface SetupConnection : Closeable {
    val serverGuid: UUID
    val dialect: String
    val isEncrypted: Boolean

    fun listShares(): List<ShareInfo>

    fun listFolder(share: String, path: String): FolderListing

    fun stat(share: String, path: String): FileStat

    fun download(share: String, path: String, target: File): FileStat
}

/** Connects like [SmbConnector.connect]. */
internal fun interface SetupConnector {
    fun connect(server: SmbServer, password: CharArray, expectedGuid: UUID?): SetupConnection
}

/** The stored SMB password (DS-03) as [SourceSetup] uses it. */
internal interface SmbPasswords {
    fun hasStoredValue(): Boolean

    /** The caller wipes the returned array. Null when nothing is stored. */
    fun load(): CharArray?

    /** [password] is not modified; the caller wipes it. */
    fun save(password: CharArray)

    /** Deletes the stored password. */
    fun clear()
}

/** [SetupConnector] over the real SMB module. */
internal fun SmbConnector.forSetup(): SetupConnector =
    SetupConnector { server, password, expectedGuid -> SmbSetupConnection(connect(server, password, expectedGuid)) }

private class SmbSetupConnection(private val connection: SmbConnection) : SetupConnection {
    override val serverGuid: UUID get() = connection.serverGuid
    override val dialect: String get() = connection.dialect
    override val isEncrypted: Boolean get() = connection.isEncrypted

    override fun listShares(): List<ShareInfo> = connection.listShares()

    override fun listFolder(share: String, path: String): FolderListing = connection.listFolderWithCount(share, path)

    override fun stat(share: String, path: String): FileStat = connection.stat(share, path)

    override fun download(share: String, path: String, target: File): FileStat = connection.download(share, path, target)

    override fun close() = connection.close()
}
