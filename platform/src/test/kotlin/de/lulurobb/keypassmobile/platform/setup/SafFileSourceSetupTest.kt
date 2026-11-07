package de.lulurobb.keypassmobile.platform.setup

import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.sync.BackgroundSyncResult
import de.lulurobb.keypassmobile.core.sync.KeySource
import de.lulurobb.keypassmobile.core.sync.RemoteError
import de.lulurobb.keypassmobile.core.sync.RemoteException
import de.lulurobb.keypassmobile.core.sync.RemoteStat
import de.lulurobb.keypassmobile.core.sync.SyncEngine
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.core.sync.UnlockResult
import de.lulurobb.keypassmobile.platform.saf.FakeDocuments
import de.lulurobb.keypassmobile.platform.session.FakeHashStore
import de.lulurobb.keypassmobile.platform.session.FakeOpener
import de.lulurobb.keypassmobile.platform.session.FakeRemote
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.platform.session.MutableClock
import de.lulurobb.keypassmobile.platform.session.RecordingEffects
import de.lulurobb.keypassmobile.platform.session.RemoteHandle
import de.lulurobb.keypassmobile.platform.session.Session
import de.lulurobb.keypassmobile.platform.session.SessionState
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.session.kdbx
import de.lulurobb.keypassmobile.platform.settings.FileSource
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.MemorySettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SmbSource
import de.lulurobb.keypassmobile.platform.settings.TrustedServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [RemoteHandle] over [FakeDocuments], independent of the SAF remote's own implementation. */
private class DocsRemote(
    private val docs: FakeDocuments,
    private val uri: String,
    private val onDownload: () -> Unit = {},
) : RemoteHandle {
    var closed = 0

    override fun stat(): RemoteStat {
        val info = docs.query(uri) ?: throw RemoteException(RemoteError.NOT_FOUND)
        return RemoteStat(info.sizeBytes ?: docs.docs.getValue(uri).bytes.size.toLong(), info.lastModified ?: RemoteStat.UNKNOWN_MODIFIED)
    }

    override fun download(target: File) {
        onDownload()
        val bytes = try {
            docs.open(uri).use { it.readBytes() }
        } catch (_: java.io.FileNotFoundException) {
            throw RemoteException(RemoteError.NOT_FOUND)
        }
        target.writeBytes(bytes)
    }

    override fun close() {
        closed++
    }
}

private class FakeSmbPasswords : SmbPasswords {
    var stored: CharArray? = null
    var clears = 0

    override fun hasStoredValue() = stored != null

    override fun load(): CharArray? = stored?.copyOf()

    override fun save(password: CharArray) {
        stored = password.copyOf()
    }

    override fun clear() {
        stored = null
        clears++
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SafFileSourceSetupTest {

    @TempDir
    lateinit var dir: File

    private val t0: Instant = Instant.parse("2026-11-01T10:00:00Z")
    private val clock = MutableClock(t0)
    private val opener = FakeOpener()
    private val docs = FakeDocuments()
    private val passwords = FakeSmbPasswords()
    private val guid = UUID.fromString("74696572-6e61-2d6f-6e61-730000000000")
    private val smbSource = SmbSource("nas", 445, "", "kpm", "daten", listOf("old.kdbx"))
    private lateinit var engine: SyncEngine
    private lateinit var settings: MemorySettingsRepository

    private val uriA = "content://fake.documents/document/primary%3ASyncthing%2FKeePass%2FPasswoerter.kdbx"
    private val uriB = "content://fake.documents/document/primary%3ADownload%2FPasswoerter.kdbx"
    private val folderA = "content://fake.documents/document/primary%3ASyncthing%2FKeePass"
    private val folderB = "content://fake.documents/document/primary%3ADownload"
    private val stale = "content://fake.documents/document/primary%3AAlt%2Falt.kdbx"
    private val seedModified = t0.minus(Duration.ofHours(1))
    private val header = byteArrayOf(0x03, 0xD9.toByte(), 0xA2.toByte(), 0x9A.toByte(), 0x67, 0xFB.toByte(), 0x4B, 0xB5.toByte(), 0, 0, 4, 0)

    @BeforeEach
    fun setUp() {
        engine = SyncEngine(File(dir, "sync"), opener, clock)
        settings = MemorySettingsRepository(KpmSettings(source = smbSource, trustedServer = TrustedServer(guid, t0)))
    }

    private fun TestScope.session() = Session(
        engine = engine,
        hashStore = FakeHashStore(),
        settings = settings,
        remotes = { null },
        effects = RecordingEffects(),
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        clock = clock,
        elapsedMillis = { testScheduler.currentTime },
        hasher = { String(it).toByteArray() },
        sourceCleanup = DocumentSourceCleanup(docs, settings, passwords),
    )

    private val remotes = mutableListOf<DocsRemote>()

    /** Called at the start of every download of a [DocsRemote], e.g. to cancel the caller in the middle of the change. */
    private var duringDownload: (() -> Unit)? = null

    private fun TestScope.setup(session: Session) = SafFileSourceSetup(
        docs, settings, session, StandardTestDispatcher(testScheduler),
        remoteFor = { DocsRemote(docs, it) { duringDownload?.invoke() }.also(remotes::add) },
    )

    /** The offline copy of the old file (key "old", database "db1") exists, like after a normal first run. */
    private fun seedOffline(db: String? = "db1") {
        val r = FakeRemote(kdbx("old", "v1", db), seedModified)
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(r))
        assertIs<UnlockResult.Opened>(engine.unlock("old".toByteArray(), KeySource.TYPED_PASSWORD)).vault.close()
    }

    private fun putFile(uri: String, content: String, modified: Instant?, name: String? = "Passwoerter.kdbx", folder: String? = null, place: String? = null) =
        docs.put(uri, content.toByteArray(), name, modified, place = place, folder = folder)

    private fun picked(uri: String, name: String = "Passwoerter.kdbx", place: String? = "Interner Speicher / Syncthing / KeePass") =
        PickedFile(uri, name, null, null, place)

    // ---- accept ----

    @Test
    fun `accept without a lasting permission gives NoLastingAccess and keeps nothing`() = runTest {
        docs.put(uriA, header, name = "Passwoerter.kdbx", folder = folderA)
        docs.takeErrors[uriA] = SecurityException("no persistable permission")
        val setup = setup(session())

        assertEquals(FileCheck.NoLastingAccess("Passwoerter.kdbx"), setup.accept(uriA))

        assertTrue(docs.persisted.isEmpty())
        // The pick still counts for the start of the next picker.
        assertEquals(folderA, settings.settings.value.lastPickedFolder)
        setup.close()
    }

    @Test
    fun `accept of a missing or unreadable file gives Unreadable and releases the permission`() = runTest {
        val setup = setup(session())

        // Not found: the name comes from the last segment of the document id.
        assertEquals(FileCheck.Unreadable("Passwoerter.kdbx"), setup.accept(uriA))
        assertTrue(docs.persisted.isEmpty())
        assertEquals("release $uriA", docs.log.last())

        // No access when querying.
        docs.put(uriB, header, name = "Passwoerter.kdbx")
        docs.queryErrors[uriB] = SecurityException("denied")
        assertEquals(FileCheck.Unreadable("Passwoerter.kdbx"), setup.accept(uriB))
        assertTrue(docs.persisted.isEmpty())

        // The read fails: the provider's name is used.
        docs.queryErrors.clear()
        docs.openErrors[uriB] = IOException("disk")
        assertEquals(FileCheck.Unreadable("Passwoerter.kdbx"), setup.accept(uriB))
        docs.openErrors[uriB] = SecurityException("denied")
        assertEquals(FileCheck.Unreadable("Passwoerter.kdbx"), setup.accept(uriB))
        assertTrue(docs.persisted.isEmpty())
        setup.close()
    }

    @Test
    fun `accept of a file that is no database gives NotKdbx and releases the permission`() = runTest {
        docs.put(uriA, "Das ist eine Textdatei.".toByteArray(), name = "liesmich.txt", folder = folderA)
        docs.put(uriB, ByteArray(3), name = "kurz.kdbx", folder = folderB)
        val setup = setup(session())

        assertEquals(FileCheck.NotKdbx("liesmich.txt"), setup.accept(uriA))
        assertTrue(docs.persisted.isEmpty())
        assertEquals(listOf("take $uriA", "release $uriA"), docs.log)
        assertEquals(folderA, settings.settings.value.lastPickedFolder, "also a rejected pick is where the next one starts")

        assertEquals(FileCheck.NotKdbx("kurz.kdbx"), setup.accept(uriB))
        assertTrue(docs.persisted.isEmpty())
        setup.close()
    }

    @Test
    fun `accept of a database gives Ok with place, size and time and keeps the permission`() = runTest {
        docs.put(uriA, header, name = "Passwoerter.kdbx", modified = seedModified, place = "Interner Speicher / Syncthing / KeePass", folder = folderA)
        val setup = setup(session())

        val ok = assertIs<FileCheck.Ok>(setup.accept(uriA))

        assertEquals(PickedFile(uriA, "Passwoerter.kdbx", header.size.toLong(), seedModified, "Interner Speicher / Syncthing / KeePass"), ok.file)
        assertEquals(setOf(uriA), docs.persisted)
        assertEquals(folderA, settings.settings.value.lastPickedFolder)
        setup.close()
    }

    @Test
    fun `accept without a provider name falls back to the last segment of the document id`() = runTest {
        docs.put(uriA, header, name = null, folder = folderA)
        val setup = setup(session())
        assertEquals("Passwoerter.kdbx", assertIs<FileCheck.Ok>(setup.accept(uriA)).file.displayName)
        setup.close()
    }

    @Test
    fun `a failed check of the current file keeps its permission`() = runTest {
        settings.update { it.copy(source = FileSource(uriA, "Passwoerter.kdbx", null)) }
        docs.persisted += uriA
        docs.put(uriA, "kein KDBX".toByteArray(), name = "Passwoerter.kdbx")
        val setup = setup(session())

        assertIs<FileCheck.NotKdbx>(setup.accept(uriA))

        assertEquals(setOf(uriA), docs.persisted, "the file source still needs it")
        setup.close()
    }

    // ---- initialFolder ----

    @Test
    fun `the picker starts where DS-10 says, first match wins`() = runTest {
        docs.put(uriA, folder = folderA)
        docs.put(uriB, folder = folderB)
        val setup = setup(session())
        val root = docs.internalStorageRoot()

        // First setup: the internal storage.
        settings.update { it.copy(source = null) }
        assertEquals(root, setup.initialFolder(null, changing = false))
        // SMB to file: the last picked folder.
        settings.update { it.copy(source = smbSource, lastPickedFolder = folderB) }
        assertEquals(folderB, setup.initialFolder(null, changing = true))
        // File source and changing: the folder of the current file, before the last picked one.
        settings.update { it.copy(source = FileSource(uriA, "Passwoerter.kdbx", null)) }
        assertEquals(folderA, setup.initialFolder(null, changing = true))
        // Not changing (first run) ignores the current source.
        assertEquals(folderB, setup.initialFolder(null, changing = false))
        // After an error: the folder of the rejected file, before everything else.
        assertEquals(folderB, setup.initialFolder(uriB, changing = true))
        // An unknown folder of the rejected file falls through to the next place.
        assertEquals(folderA, setup.initialFolder("content://fake.documents/document/unbekannt", changing = true))
        // Nothing known at all.
        settings.update { it.copy(source = FileSource("content://fake.documents/document/weg", "x.kdbx", null), lastPickedFolder = null) }
        assertEquals(root, setup.initialFolder(null, changing = true))
        setup.close()
    }

    // ---- choose ----

    @Test
    fun `SMB to file clears the SMB password and server identity and releases the other permissions`() = runTest {
        seedOffline()
        passwords.stored = "smb-pw".toCharArray()
        settings.update { it.copy(pendingServerGuid = UUID.randomUUID()) }
        docs.persisted += stale
        putFile(uriA, kdbx("old", "v1", "db1"), seedModified, folder = folderA)
        docs.takeReadPermission(uriA)
        val s = session()
        val setup = setup(s)

        val saved = assertIs<ChooseResult.Saved>(setup.choose(picked(uriA)))

        assertTrue(saved.fileChanged, "a type switch always differs")
        assertFalse(saved.lockedForNewDatabase)
        assertEquals(FileSource(uriA, "Passwoerter.kdbx", "Interner Speicher / Syncthing / KeePass"), settings.settings.value.source)
        assertNull(settings.settings.value.trustedServer)
        assertNull(settings.settings.value.pendingServerGuid)
        assertNull(passwords.stored)
        assertEquals(setOf(uriA), docs.persisted)
        assertEquals(1, remotes.size)
        assertEquals(1, remotes.single().closed, "the remote is closed")
        setup.close()
        assertEquals(setOf(uriA), docs.persisted, "close keeps the permission of the saved source")
    }

    @Test
    fun `file to file with the key that does not open the new file locks and says so`() = runTest {
        seedOffline()
        settings.update { it.copy(source = FileSource(uriA, "Passwoerter.kdbx", null), trustedServer = null) }
        docs.persisted += uriA
        putFile(uriB, kdbx("new", "other", "db2"), t0.minus(Duration.ofDays(2)))
        docs.takeReadPermission(uriB)
        val s = session()
        s.unlockWithPassword("old".toCharArray())
        runCurrent()
        val setup = setup(s)

        val saved = assertIs<ChooseResult.Saved>(setup.choose(picked(uriB)))

        assertTrue(saved.lockedForNewDatabase)
        assertEquals(SyncResult.NewVersionRejected, saved.sync)
        assertEquals(LockReason.SourceChanged, assertIs<SessionState.Locked>(s.state.value).reason)
        assertEquals(setOf(uriB), docs.persisted, "the grant of the old file is released")
        setup.close()
    }

    @Test
    fun `file to file with the key that opens the new file does not lock`() = runTest {
        seedOffline()
        settings.update { it.copy(source = FileSource(uriA, "Passwoerter.kdbx", null), trustedServer = null) }
        docs.persisted += uriA
        putFile(uriB, kdbx("old", "v2", "db2"), t0.minus(Duration.ofDays(2)))
        docs.takeReadPermission(uriB)
        val s = session()
        s.unlockWithPassword("old".toCharArray())
        runCurrent()
        val setup = setup(s)

        val saved = assertIs<ChooseResult.Saved>(setup.choose(picked(uriB)))

        assertFalse(saved.lockedForNewDatabase)
        assertEquals(SyncResult.Updated, saved.sync)
        assertIs<SessionState.Unlocked>(s.state.value)
        assertEquals(setOf(uriB), docs.persisted)
        setup.close()
    }

    @Test
    fun `picking the same file again does not differ and keeps its permission`() = runTest {
        seedOffline()
        settings.update { it.copy(source = FileSource(uriA, "Passwoerter.kdbx", "alt"), trustedServer = null) }
        docs.persisted += uriA
        putFile(uriA, kdbx("old", "v1", "db1"), seedModified)
        val setup = setup(session())

        // Taking the permission again is idempotent; a rejected re-pick must not drop it either.
        docs.takeReadPermission(uriA)
        val saved = assertIs<ChooseResult.Saved>(setup.choose(picked(uriA)))

        assertFalse(saved.fileChanged)
        assertEquals(SyncResult.UpToDate, saved.sync)
        assertEquals(setOf(uriA), docs.persisted)
        assertEquals("Interner Speicher / Syncthing / KeePass", (settings.settings.value.source as FileSource).place, "the place is refreshed")
        setup.close()
        assertEquals(setOf(uriA), docs.persisted)
    }

    @Test
    fun `a failed change releases the new permission and changes nothing else`() = runTest {
        seedOffline()
        passwords.stored = "smb-pw".toCharArray()
        docs.persisted += stale
        docs.takeReadPermission(uriA)
        val s = session()
        val setup = setup(s)

        // The file is gone: the download fails.
        val failed = assertIs<ChooseResult.Failed>(setup.choose(picked(uriA)))

        assertEquals(SyncProblem.NOT_FOUND, failed.problem)
        assertEquals(smbSource, settings.settings.value.source)
        assertEquals("smb-pw", String(passwords.stored!!))
        assertEquals(setOf(stale), docs.persisted, "only the new permission is released, the cleanup did not run")
        assertEquals(TrustedServer(guid, t0), settings.settings.value.trustedServer)
        setup.close()
    }

    // ---- D10: started unlocked, locked before the commit ----

    @Test
    fun `D10 a lock after the setup started discards the change and releases the new permission`() = runTest {
        seedOffline()
        passwords.stored = "smb-pw".toCharArray()
        docs.persisted += stale
        putFile(uriA, kdbx("old", "v1", "db1"), seedModified, folder = folderA)
        val s = session()
        s.unlockWithPassword("old".toCharArray())
        runCurrent()
        docs.takeReadPermission(uriA)
        val setup = setup(s)
        // The auto lock hits after the check, before the commit.
        s.lock(LockReason.Idle(IdleTimeout.SECONDS_30))

        assertEquals(ChooseResult.Discarded, setup.choose(picked(uriA)))

        assertEquals(smbSource, settings.settings.value.source, "no settings change")
        assertEquals("smb-pw", String(passwords.stored!!))
        assertEquals(setOf(stale), docs.persisted, "the grant of the new file is released, the cleanup did not run")
        assertEquals(1, remotes.single().closed)
        setup.close()
        assertEquals(setOf(stale), docs.persisted)
    }

    @Test
    fun `D10 a lock while the new file downloads discards the change`() = runTest {
        seedOffline()
        putFile(uriA, kdbx("new", "other", "db2"), t0.minus(Duration.ofDays(2)), folder = folderA)
        docs.takeReadPermission(uriA)
        val s = session()
        s.unlockWithPassword("old".toCharArray())
        runCurrent()
        val setup = setup(s)
        duringDownload = { s.lock(LockReason.User) }

        assertEquals(ChooseResult.Discarded, setup.choose(picked(uriA)))

        assertEquals(smbSource, settings.settings.value.source)
        assertTrue(docs.persisted.isEmpty())
        assertTrue(File(dir, "sync/offline.kdbx").exists(), "the old copy is back")
        assertFalse(File(dir, "sync/offline-previous.kdbx").exists())
        assertFalse(File(dir, "sync/candidate.kdbx").exists())
        setup.close()
    }

    @Test
    fun `D10 a discarded re-pick of the current file keeps its permission`() = runTest {
        seedOffline()
        settings.update { it.copy(source = FileSource(uriA, "Passwoerter.kdbx", null), trustedServer = null) }
        docs.persisted += uriA
        putFile(uriA, kdbx("old", "v1", "db1"), seedModified)
        val s = session()
        s.unlockWithPassword("old".toCharArray())
        runCurrent()
        val setup = setup(s)
        s.lock(LockReason.User)

        assertEquals(ChooseResult.Discarded, setup.choose(picked(uriA)))

        assertEquals(setOf(uriA), docs.persisted, "the file source still needs it")
        setup.close()
    }

    @Test
    fun `D10 a change started locked is applied as a locked change`() = runTest {
        seedOffline()
        putFile(uriA, kdbx("old", "v1", "db1"), seedModified, folder = folderA)
        docs.takeReadPermission(uriA)
        val s = session()
        val setup = setup(s)

        val saved = assertIs<ChooseResult.Saved>(setup.choose(picked(uriA)))

        assertFalse(saved.lockedForNewDatabase)
        assertEquals(FileSource(uriA, "Passwoerter.kdbx", "Interner Speicher / Syncthing / KeePass"), settings.settings.value.source)
        setup.close()
    }

    @Test
    fun `the start folder is computed through the io dispatcher`() = runTest {
        docs.put(uriA, folder = folderA)
        val s = session()
        val setup = setup(s)
        val answer = backgroundScope.async { setup.initialFolder(uriA, changing = true) }

        // The io dispatcher of the setup is a test dispatcher: nothing happens before it runs.
        assertFalse(answer.isCompleted)
        runCurrent()
        assertEquals(folderA, answer.await())
        setup.close()
    }

    // ---- the start-up cleanup of permissions ----

    @Test
    fun `accept waits for the start-up cleanup, which then cannot release the new permission`() = runTest {
        // First run, nothing saved: the cleanup releases every permission that is not the saved source's own.
        settings.update { it.copy(source = null) }
        docs.put(uriA, header, name = "Passwoerter.kdbx", folder = folderA)
        val gate = CompletableDeferred<Unit>()
        val setup = SafFileSourceSetup(docs, settings, session(), StandardTestDispatcher(testScheduler), startupDone = gate)

        val answer = backgroundScope.async { setup.accept(uriA) }
        runCurrent()
        assertFalse(answer.isCompleted, "the pick waits")
        assertTrue(docs.log.isEmpty(), "no permission is taken yet")

        // The cleanup runs while the pick waits, then the gate opens.
        DocumentSourceCleanup(docs, settings, passwords).reconcile(settings.settings.value.source)
        gate.complete(Unit)
        runCurrent()

        assertIs<FileCheck.Ok>(answer.await())
        assertEquals(setOf(uriA), docs.persisted, "the new permission survived the cleanup")
        setup.close()
    }

    @Test
    fun `accept does not wait when the cleanup is already over`() = runTest {
        docs.put(uriA, header, name = "Passwoerter.kdbx", folder = folderA)
        val setup = SafFileSourceSetup(docs, settings, session(), StandardTestDispatcher(testScheduler), startupDone = CompletableDeferred(Unit))

        val answer = backgroundScope.async { setup.accept(uriA) }
        runCurrent()

        assertTrue(answer.isCompleted)
        assertIs<FileCheck.Ok>(answer.await())
        setup.close()
    }

    // ---- discard and close ----

    @Test
    fun `discard never releases the uri of the current file source`() = runTest {
        settings.update { it.copy(source = FileSource(uriA, "Passwoerter.kdbx", null)) }
        docs.persisted += listOf(uriA, uriB)
        val setup = setup(session())

        setup.discard(uriA)
        assertEquals(setOf(uriA, uriB), docs.persisted)
        setup.discard(uriB)
        assertEquals(setOf(uriA), docs.persisted)
        setup.close()
    }

    @Test
    fun `close discards a picked file that was not saved`() = runTest {
        docs.put(uriA, header, name = "Passwoerter.kdbx", folder = folderA)
        val setup = setup(session())
        assertIs<FileCheck.Ok>(setup.accept(uriA))
        assertEquals(setOf(uriA), docs.persisted)

        setup.close()

        assertTrue(docs.persisted.isEmpty())
    }

    @Test
    fun `a cancelled caller does not lose the permission of a change that finishes`() = runTest {
        seedOffline()
        docs.put(uriA, header, name = "Passwoerter.kdbx", folder = folderA, place = "Interner Speicher / Syncthing / KeePass")
        val s = session()
        val setup = setup(s)
        val file = assertIs<FileCheck.Ok>(setup.accept(uriA)).file
        // The content the engine reads (the fake format), then the screen goes away in the middle of the change.
        docs.docs.getValue(uriA).apply {
            bytes = kdbx("old", "v1", "db1").toByteArray()
            // Another time than the offline copy, so that the change downloads.
            modified = seedModified.minus(Duration.ofHours(1))
        }
        lateinit var caller: kotlinx.coroutines.Job
        duringDownload = {
            caller.cancel()
            setup.close()
            assertEquals(setOf(uriA), docs.persisted, "close waits for the running change")
        }
        caller = launch { setup.choose(file) }
        caller.join()
        runCurrent()

        assertTrue(caller.isCancelled)
        assertEquals(FileSource(uriA, "Passwoerter.kdbx", "Interner Speicher / Syncthing / KeePass"), settings.settings.value.source)
        assertEquals(setOf(uriA), docs.persisted, "the saved source keeps its permission")
    }

    // ---- the older version check ----

    @Test
    fun `olderVersion reports an older file of the same database and nothing for others`() = runTest {
        seedOffline()
        val s = session()
        s.unlockWithPassword("old".toCharArray())
        runCurrent()
        val setup = setup(s)

        putFile(uriA, kdbx("old", "v0", "db1"), seedModified.minus(Duration.ofDays(30)))
        assertEquals(
            OlderVersion("Passwoerter.kdbx", seedModified.minus(Duration.ofDays(30)), seedModified),
            setup.olderVersion(picked(uriA)),
        )
        assertTrue(remotes.all { it.closed == 1 })

        putFile(uriB, kdbx("old", "v2", "db1"), seedModified.plus(Duration.ofDays(1)))
        assertNull(setup.olderVersion(picked(uriB)), "newer")
        putFile(uriB, kdbx("old", "v0", "db9"), seedModified.minus(Duration.ofDays(30)))
        assertNull(setup.olderVersion(picked(uriB)), "other database")
        setup.close()
    }

    @Test
    fun `olderVersion is null in the first run`() = runTest {
        seedOffline()
        val setup = setup(session())
        putFile(uriA, kdbx("old", "v0", "db1"), seedModified.minus(Duration.ofDays(30)))
        assertNull(setup.olderVersion(picked(uriA)))
        setup.close()
    }

    // ---- names and the cleanup ----

    @Test
    fun `the fallback name is the last segment of the document id`() {
        val f = { uri: String -> SafFileSourceSetup.fallbackName(uri) }
        assertEquals("Passwoerter.kdbx", f(uriA))
        assertEquals("a b.kdbx", f("content://x/document/primary%3AA%2Fa%20b.kdbx"))
        assertEquals("plus+ist.kdbx", f("content://x/document/raw%3A%2Fsdcard%2Fplus%2Bist.kdbx"))
        assertEquals("x.kdbx", f("content://x/document/x.kdbx"))
        assertEquals("1234", f("content://x/document/msf%3A1234"))
    }

    @Test
    fun `the cleanup of a file source keeps only its own permission and drops the SMB leftovers`() {
        val cleanup = DocumentSourceCleanup(docs, settings, passwords)
        passwords.stored = "smb-pw".toCharArray()
        settings.update { it.copy(pendingServerGuid = UUID.randomUUID()) }
        docs.persisted += listOf(uriA, uriB, stale)

        cleanup.reconcile(FileSource(uriA, "Passwoerter.kdbx", null))

        assertEquals(setOf(uriA), docs.persisted)
        assertNull(passwords.stored)
        assertNull(settings.settings.value.trustedServer)
        assertNull(settings.settings.value.pendingServerGuid)
        // Idempotent.
        cleanup.reconcile(FileSource(uriA, "Passwoerter.kdbx", null))
        assertEquals(setOf(uriA), docs.persisted)
    }

    @Test
    fun `the cleanup of an SMB source releases every permission and keeps the SMB state`() {
        val cleanup = DocumentSourceCleanup(docs, settings, passwords)
        passwords.stored = "smb-pw".toCharArray()
        docs.persisted += listOf(uriA, stale)

        cleanup.reconcile(smbSource)

        assertTrue(docs.persisted.isEmpty())
        assertEquals("smb-pw", String(passwords.stored!!))
        assertEquals(TrustedServer(guid, t0), settings.settings.value.trustedServer)
        cleanup.reconcile(null)
        assertTrue(docs.persisted.isEmpty())
    }
}
