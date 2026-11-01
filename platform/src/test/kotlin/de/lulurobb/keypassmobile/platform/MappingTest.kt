package de.lulurobb.keypassmobile.platform

import de.lulurobb.keypassmobile.core.sync.OpenFailure
import de.lulurobb.keypassmobile.core.sync.RemoteError
import de.lulurobb.keypassmobile.kdbx.KdbxOpenException
import de.lulurobb.keypassmobile.platform.clipboard.ClipboardGuard
import de.lulurobb.keypassmobile.platform.kdbx.KdbxVaultOpener
import de.lulurobb.keypassmobile.platform.keystore.SmbPasswordStore
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.platform.settings.TrustedServer
import de.lulurobb.keypassmobile.platform.setup.BrowseError
import de.lulurobb.keypassmobile.platform.setup.ConnectError
import de.lulurobb.keypassmobile.platform.setup.SourceSetup
import de.lulurobb.keypassmobile.platform.smb.ServerNames
import de.lulurobb.keypassmobile.platform.smb.remoteErrorOf
import de.lulurobb.keypassmobile.platform.smb.smbPath
import de.lulurobb.keypassmobile.smb.FolderItem
import de.lulurobb.keypassmobile.smb.SmbException
import de.lulurobb.keypassmobile.smb.SmbServer
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MappingTest {

    private val reitanoGuid = UUID.fromString("74696572-6e61-2d6f-6e61-730000000000")

    @Test
    fun `Samba GUID decodes to the NetBIOS name`() {
        assertEquals("reitano-nas", ServerNames.fromGuid(reitanoGuid))
    }

    @Test
    fun `random or non ASCII GUIDs have no name`() {
        assertNull(ServerNames.fromGuid(UUID.fromString("9b2f6c1e-3d4a-4e5f-8a7b-0c1d2e3f4a5b")))
        assertNull(ServerNames.fromGuid(UUID(0, 0)))
        assertNull(ServerNames.fromGuid(UUID.fromString("00000000-0000-0000-0000-000000000001")))
    }

    @Test
    fun `SMB failures map to sync engine errors`() {
        assertEquals(RemoteError.UNREACHABLE, remoteErrorOf(SmbException.Reason.UNREACHABLE))
        assertEquals(RemoteError.AUTH_FAILED, remoteErrorOf(SmbException.Reason.AUTH_FAILED))
        assertEquals(RemoteError.SERVER_CHANGED, remoteErrorOf(SmbException.Reason.SERVER_CHANGED))
        assertEquals(RemoteError.NOT_FOUND, remoteErrorOf(SmbException.Reason.SHARE_NOT_FOUND))
        assertEquals(RemoteError.NOT_FOUND, remoteErrorOf(SmbException.Reason.FILE_NOT_FOUND))
        assertEquals(RemoteError.ACCESS_DENIED, remoteErrorOf(SmbException.Reason.ACCESS_DENIED))
        assertEquals(RemoteError.OTHER, remoteErrorOf(SmbException.Reason.PROTOCOL))
        assertEquals(RemoteError.OTHER, remoteErrorOf(SmbException.Reason.OTHER))
        assertEquals("KeePass/db.kdbx", smbPath(listOf("KeePass", "db.kdbx")))
    }

    @Test
    fun `KDBX failures map to open failures`() {
        assertEquals(OpenFailure.WRONG_KEY, KdbxVaultOpener.failureOf(KdbxOpenException.Reason.WRONG_KEY))
        assertEquals(OpenFailure.CORRUPT, KdbxVaultOpener.failureOf(KdbxOpenException.Reason.CORRUPT))
        assertEquals(OpenFailure.CORRUPT, KdbxVaultOpener.failureOf(KdbxOpenException.Reason.UNSUPPORTED))
        assertEquals(OpenFailure.CORRUPT, KdbxVaultOpener.failureOf(KdbxOpenException.Reason.OTHER))
    }

    @Test
    fun `system folders are hidden and counted`() {
        val now = Instant.parse("2026-11-01T10:00:00Z")
        fun folder(name: String) = FolderItem(name, isFolder = true, size = 0, modified = now)
        val listing = SourceSetup.filterListing(
            listOf(
                folder("#recycle"), folder("@eaDir"), folder("\$RECYCLE.BIN"), folder(".snapshots"),
                folder("@Recently-Snapshot"), folder("System Volume Information"), folder("#snapshot"),
                folder("KeePass"), folder("Ärzte"), folder("#Projekte"),
                FolderItem("Passwords.kdbx", isFolder = false, size = 265_530, modified = now),
            ),
        )
        assertEquals(listOf("KeePass", "Ärzte", "#Projekte"), listing.folders)
        assertEquals(7, listing.hiddenCount)
        assertEquals(1, listing.files.size)
        assertEquals(265_530, listing.files[0].sizeBytes)
    }

    @Test
    fun `remembered GUID applies only to the host of the current source`() {
        val guid = UUID.randomUUID()
        val settings = KpmSettings(
            source = SourceConfig("192.168.178.20", 445, "", "kpm", "daten", listOf("db.kdbx")),
            trustedServer = TrustedServer(guid, Instant.EPOCH),
        )
        assertEquals(guid, SourceSetup.rememberedGuidFor(settings, SmbServer("192.168.178.20", username = "x")))
        assertNull(SourceSetup.rememberedGuidFor(settings, SmbServer("192.168.178.21", username = "x")))
        assertNull(SourceSetup.rememberedGuidFor(settings, SmbServer("192.168.178.20", port = 4450, username = "x")))
        assertNull(SourceSetup.rememberedGuidFor(KpmSettings(), SmbServer("192.168.178.20", username = "x")))
    }

    @Test
    fun `connect errors for the server screen`() {
        val server = SmbServer("nas", username = "kpm")
        val remembered = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val settings = KpmSettings(trustedServer = TrustedServer(remembered, Instant.EPOCH))
        fun map(reason: SmbException.Reason, guid: UUID? = null) =
            SourceSetup.connectErrorOf(SmbException(reason, "x", serverGuid = guid), server, settings)

        assertEquals(ConnectError.Unreachable, map(SmbException.Reason.UNREACHABLE))
        assertEquals(ConnectError.LoginFailed, map(SmbException.Reason.AUTH_FAILED))
        assertEquals(ConnectError.Unsupported, map(SmbException.Reason.PROTOCOL))
        assertEquals(ConnectError.Unknown, map(SmbException.Reason.OTHER))
        val other = assertIs<ConnectError.OtherServer>(map(SmbException.Reason.SERVER_CHANGED, reitanoGuid))
        assertEquals("nas", other.change.host)
        assertEquals(remembered, other.change.rememberedGuid)
        assertEquals(reitanoGuid, other.change.newGuid)
        assertEquals("reitano-nas", other.change.newName)
    }

    @Test
    fun `browse errors`() {
        assertEquals(BrowseError.SHARE_NOT_FOUND, SourceSetup.browseErrorOf(SmbException.Reason.SHARE_NOT_FOUND))
        assertEquals(BrowseError.NOT_FOUND, SourceSetup.browseErrorOf(SmbException.Reason.FILE_NOT_FOUND))
        assertEquals(BrowseError.ACCESS_DENIED, SourceSetup.browseErrorOf(SmbException.Reason.ACCESS_DENIED))
        assertEquals(BrowseError.UNREACHABLE, SourceSetup.browseErrorOf(SmbException.Reason.UNREACHABLE))
        assertEquals(BrowseError.OTHER, SourceSetup.browseErrorOf(SmbException.Reason.PROTOCOL))
    }

    @Test
    fun `source identity is case-insensitive and keeps the folder of the file`() {
        val a = SourceConfig("NAS", 445, "", "kpm", "Daten", listOf("KeePass", "Passwords.kdbx"))
        val b = SourceConfig("nas", 445, "", "other", "daten", listOf("keepass", "passwords.KDBX"))
        assertTrue(a.sameFileAs(b))
        assertFalse(a.sameFileAs(b.copy(filePath = listOf("Passwords.kdbx"))))
        assertFalse(a.sameFileAs(null))
        assertEquals(listOf("KeePass"), a.folderPath)
        assertEquals("Passwords.kdbx", a.fileName)
        assertEquals("\\\\NAS\\Daten\\KeePass\\Passwords.kdbx", a.unc)
    }

    @Test
    fun `clipboard is cleared when unreadable or ours, foreign content stays`() {
        val label = "KeyPass Mobile"
        // In the background the clip cannot be read: clear to be safe.
        assertTrue(ClipboardGuard.shouldClear(readable = false, clipLabel = null, clipTimestamp = 0, ourLabel = label, ourTimestamp = 42))
        // Our clip.
        assertTrue(ClipboardGuard.shouldClear(readable = true, clipLabel = label, clipTimestamp = 42, ourLabel = label, ourTimestamp = 42))
        assertTrue(ClipboardGuard.shouldClear(readable = true, clipLabel = label, clipTimestamp = 42, ourLabel = label, ourTimestamp = null))
        // Copied by another app, or a newer copy of our own label (e.g. the next entry).
        assertFalse(ClipboardGuard.shouldClear(readable = true, clipLabel = "other", clipTimestamp = 50, ourLabel = label, ourTimestamp = 42))
        assertFalse(ClipboardGuard.shouldClear(readable = true, clipLabel = label, clipTimestamp = 50, ourLabel = label, ourTimestamp = 42))
    }

    @Test
    fun `SMB password encoding round trip without String`() {
        // Umlauts and a surrogate pair (4 UTF-8 bytes).
        val text = "Prüfung-Äß-2026 😀"
        val password = text.toCharArray()
        val bytes = SmbPasswordStore.utf8(password)
        assertContentEquals(text.toByteArray(Charsets.UTF_8), bytes)
        assertContentEquals(password, SmbPasswordStore.fromUtf8(bytes))
        assertEquals(0, SmbPasswordStore.utf8(CharArray(0)).size)
    }
}
