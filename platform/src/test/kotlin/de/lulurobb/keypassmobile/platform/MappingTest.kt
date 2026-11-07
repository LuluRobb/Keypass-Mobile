package de.lulurobb.keypassmobile.platform

import de.lulurobb.keypassmobile.core.sync.OpenFailure
import de.lulurobb.keypassmobile.core.sync.RemoteError
import de.lulurobb.keypassmobile.kdbx.KdbxOpenException
import de.lulurobb.keypassmobile.platform.kdbx.KdbxVaultOpener
import de.lulurobb.keypassmobile.platform.keystore.SmbPasswordStore
import de.lulurobb.keypassmobile.platform.settings.FileSource
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.PrefsSettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SmbSource
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import de.lulurobb.keypassmobile.platform.settings.TrustedServer
import de.lulurobb.keypassmobile.platform.setup.BrowseError
import de.lulurobb.keypassmobile.platform.setup.ConnectError
import de.lulurobb.keypassmobile.platform.setup.SourceSetup
import de.lulurobb.keypassmobile.platform.smb.ServerNames
import de.lulurobb.keypassmobile.platform.smb.remoteErrorOf
import de.lulurobb.keypassmobile.platform.smb.smbPath
import de.lulurobb.keypassmobile.smb.FolderItem
import de.lulurobb.keypassmobile.smb.FolderListing
import de.lulurobb.keypassmobile.smb.SmbException
import de.lulurobb.keypassmobile.smb.SmbServer
import android.content.SharedPreferences
import de.lulurobb.keypassmobile.core.settings.IdleTimeout
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
    fun `system folders are hidden without being counted, other files are counted`() {
        val now = Instant.parse("2026-11-01T10:00:00Z")
        fun folder(name: String) = FolderItem(name, isFolder = true, size = 0, modified = now)
        val listing = SourceSetup.filterListing(
            FolderListing(
                listOf(
                    folder("#recycle"), folder("@eaDir"), folder("\$RECYCLE.BIN"), folder(".snapshots"),
                    folder("@Recently-Snapshot"), folder("System Volume Information"), folder("#snapshot"),
                    folder("KeePass"), folder("Ärzte"), folder("#Projekte"),
                    FolderItem("Passwords.kdbx", isFolder = false, size = 265_530, modified = now),
                ),
                otherFileCount = 3,
            ),
        )
        assertEquals(listOf("KeePass", "Ärzte", "#Projekte"), listing.folders)
        // The 7 system folders are not counted; the 3 other files are.
        assertEquals(3, listing.hiddenCount)
        assertEquals(1, listing.files.size)
        assertEquals(265_530, listing.files[0].sizeBytes)
    }

    @Test
    fun `remembered GUID applies only to the host of the current source`() {
        val guid = UUID.randomUUID()
        val settings = KpmSettings(
            source = SmbSource("192.168.178.20", 445, "", "kpm", "daten", listOf("db.kdbx")),
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
        val a = SmbSource("NAS", 445, "", "kpm", "Daten", listOf("KeePass", "Passwords.kdbx"))
        val b = SmbSource("nas", 445, "", "other", "daten", listOf("keepass", "passwords.KDBX"))
        assertTrue(a.sameFileAs(b))
        assertFalse(a.sameFileAs(b.copy(filePath = listOf("Passwords.kdbx"))))
        assertFalse(a.sameFileAs(null))
        assertEquals(listOf("KeePass"), a.folderPath)
        assertEquals("Passwords.kdbx", a.fileName)
        assertEquals("\\\\NAS\\Daten\\KeePass\\Passwords.kdbx", a.unc)
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

    // ---- persisted source (DS-08), migration from version 1 ----

    private val smb = SmbSource("192.168.178.20", 4450, "WORK", "kpm", "daten", listOf("Ärzte", "db.kdbx"))
    private val file = FileSource("content://com.example.docs/document/primary%3ASync%2Fdb.kdbx", "db.kdbx", "Interner Speicher / Sync")

    /** Prefs as an installation before the file source wrote them: version 1, SMB keys, no source.type. */
    private fun oldPrefs() = FakePrefs().apply {
        values["version"] = 1
        values["source.host"] = "192.168.178.20"
        values["source.port"] = 4450
        values["source.domain"] = "WORK"
        values["source.user"] = "kpm"
        values["source.share"] = "daten"
        values["source.path"] = "Ärzte/db.kdbx"
        values["setup.completed"] = true
        values["unlock.fingerprint"] = true
        values["server.guid"] = reitanoGuid.toString()
        values["server.since"] = 1_000L
    }

    private fun roundTrip(settings: KpmSettings): KpmSettings {
        val prefs = FakePrefs()
        PrefsSettingsRepository.write(prefs.edit(), settings).apply()
        return PrefsSettingsRepository.read(prefs)
    }

    @Test
    fun `old prefs without a source type read as an SMB source`() {
        val read = PrefsSettingsRepository.read(oldPrefs())
        assertEquals(smb, read.source)
        assertEquals(SourceKind.SMB, read.source!!.kind)
        assertTrue(read.setupCompleted)
        assertTrue(read.fingerprintEnabled)
        assertEquals(reitanoGuid, read.trustedServer!!.guid)
        assertNull(read.lastPickedFolder)
    }

    @Test
    fun `reading old prefs rewrites nothing, the next update writes version 2 with the type`() {
        val prefs = oldPrefs()
        val repository = PrefsSettingsRepository(prefs)
        assertEquals(smb, repository.settings.value.source)
        assertEquals(1, prefs.values["version"])
        assertFalse(prefs.values.containsKey("source.type"))

        repository.update { it.copy(idleTimeout = IdleTimeout.entries.last()) }
        assertEquals(2, prefs.values["version"])
        assertEquals("smb", prefs.values["source.type"])
        assertEquals(smb, PrefsSettingsRepository.read(prefs).source)
    }

    @Test
    fun `SMB source round trip`() {
        assertEquals(smb, roundTrip(KpmSettings(source = smb)).source)
    }

    @Test
    fun `file source round trip with and without a place`() {
        assertEquals(file, roundTrip(KpmSettings(source = file)).source)
        val noPlace = file.copy(place = null)
        assertEquals(noPlace, roundTrip(KpmSettings(source = noPlace)).source)
        assertEquals(SourceKind.FILE, noPlace.kind)
        assertEquals("db.kdbx", noPlace.fileName)
    }

    @Test
    fun `no source leaves no source keys`() {
        val prefs = FakePrefs()
        PrefsSettingsRepository.write(prefs.edit(), KpmSettings(source = smb)).apply()
        PrefsSettingsRepository.write(prefs.edit(), KpmSettings(source = null)).apply()
        assertNull(PrefsSettingsRepository.read(prefs).source)
        assertTrue(prefs.values.keys.none { it.startsWith("source.") })
    }

    @Test
    fun `writing one kind of source removes the keys of the other kind`() {
        val prefs = FakePrefs()
        PrefsSettingsRepository.write(prefs.edit(), KpmSettings(source = smb)).apply()
        PrefsSettingsRepository.write(prefs.edit(), KpmSettings(source = file)).apply()
        assertEquals("file", prefs.values["source.type"])
        assertEquals(
            setOf("source.type", "source.uri", "source.name", "source.place"),
            prefs.values.keys.filter { it.startsWith("source.") }.toSet(),
        )
        assertEquals(file, PrefsSettingsRepository.read(prefs).source)

        PrefsSettingsRepository.write(prefs.edit(), KpmSettings(source = smb)).apply()
        assertEquals("smb", prefs.values["source.type"])
        assertEquals(
            setOf("source.type", "source.host", "source.port", "source.domain", "source.user", "source.share", "source.path"),
            prefs.values.keys.filter { it.startsWith("source.") }.toSet(),
        )
        assertEquals(smb, PrefsSettingsRepository.read(prefs).source)
    }

    @Test
    fun `an unknown source type reads as no source`() {
        val prefs = oldPrefs()
        prefs.values["source.type"] = "ftp"
        assertNull(PrefsSettingsRepository.read(prefs).source)
    }

    @Test
    fun `a file source without uri or name reads as no source`() {
        val prefs = FakePrefs()
        prefs.values["source.type"] = "file"
        prefs.values["source.name"] = "db.kdbx"
        assertNull(PrefsSettingsRepository.read(prefs).source)
        prefs.values["source.uri"] = "content://x/document/1"
        prefs.values.remove("source.name")
        assertNull(PrefsSettingsRepository.read(prefs).source)
    }

    @Test
    fun `last picked folder round trip and removal`() {
        val folder = "content://com.android.externalstorage.documents/document/primary%3ASync"
        val prefs = FakePrefs()
        PrefsSettingsRepository.write(prefs.edit(), KpmSettings(lastPickedFolder = folder)).apply()
        assertEquals(folder, prefs.values["picker.lastFolder"])
        assertEquals(folder, PrefsSettingsRepository.read(prefs).lastPickedFolder)
        PrefsSettingsRepository.write(prefs.edit(), KpmSettings(lastPickedFolder = null)).apply()
        assertFalse(prefs.values.containsKey("picker.lastFolder"))
        assertNull(PrefsSettingsRepository.read(prefs).lastPickedFolder)
    }

    @Test
    fun `an SMB source never matches a file source`() {
        val other: SourceConfig = file
        assertFalse(smb.sameFileAs(other))
        assertTrue(smb.sameFileAs(smb.copy(user = "someone")))
    }
}

/** In-memory [SharedPreferences] for JVM tests: [values] is the file content. */
internal class FakePrefs : SharedPreferences {
    val values = LinkedHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = LinkedHashMap(values)

    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue

    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue

    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue

    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue

    override fun contains(key: String): Boolean = values.containsKey(key)

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val ops = ArrayList<() -> Unit>()

        private fun op(block: () -> Unit): SharedPreferences.Editor = apply { ops += block }

        override fun putString(key: String, value: String?) = op { if (value == null) values.remove(key) else values[key] = value }

        override fun putStringSet(key: String, value: MutableSet<String>?) =
            op { if (value == null) values.remove(key) else values[key] = value.toSet() }

        override fun putInt(key: String, value: Int) = op { values[key] = value }

        override fun putLong(key: String, value: Long) = op { values[key] = value }

        override fun putFloat(key: String, value: Float) = op { values[key] = value }

        override fun putBoolean(key: String, value: Boolean) = op { values[key] = value }

        override fun remove(key: String) = op { values.remove(key) }

        override fun clear() = op { values.clear() }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() = ops.forEach { it() }
    }
}
