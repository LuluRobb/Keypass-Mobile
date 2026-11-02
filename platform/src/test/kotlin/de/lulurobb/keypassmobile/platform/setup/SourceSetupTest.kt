package de.lulurobb.keypassmobile.platform.setup

import de.lulurobb.keypassmobile.core.sync.BackgroundSyncResult
import de.lulurobb.keypassmobile.core.sync.KeySource
import de.lulurobb.keypassmobile.core.sync.SyncEngine
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.core.sync.UnlockResult
import de.lulurobb.keypassmobile.platform.session.FakeHashStore
import de.lulurobb.keypassmobile.platform.session.FakeOpener
import de.lulurobb.keypassmobile.platform.session.FakeRemote
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.platform.session.MutableClock
import de.lulurobb.keypassmobile.platform.session.RecordingEffects
import de.lulurobb.keypassmobile.platform.session.Session
import de.lulurobb.keypassmobile.platform.session.SessionState
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.session.kdbx
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.MemorySettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.platform.settings.TrustedServer
import de.lulurobb.keypassmobile.smb.FileStat
import de.lulurobb.keypassmobile.smb.FolderListing
import de.lulurobb.keypassmobile.smb.ShareInfo
import de.lulurobb.keypassmobile.smb.SmbException
import de.lulurobb.keypassmobile.smb.SmbServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeConnection(
    override val serverGuid: UUID,
    var content: String,
    var modified: Instant,
) : SetupConnection {
    override val dialect = "SMB 3.1.1"
    override val isEncrypted = true

    var statError: SmbException? = null

    /** The next stat fails once, like a connection that went stale. */
    var failStatOnce: SmbException? = null
    var closed = false

    /** Called at the start of every download, e.g. to cancel the caller in the middle of the change. */
    var onDownload: (() -> Unit)? = null

    override fun listShares() = listOf(ShareInfo("daten", ""))

    override fun listFolder(share: String, path: String) = FolderListing(emptyList(), 0)

    override fun stat(share: String, path: String): FileStat {
        failStatOnce?.let {
            failStatOnce = null
            throw it
        }
        statError?.let { throw it }
        return FileStat(content.toByteArray().size.toLong(), modified)
    }

    override fun download(share: String, path: String, target: File): FileStat {
        onDownload?.invoke()
        target.writeBytes(content.toByteArray())
        return stat(share, path)
    }

    override fun close() {
        closed = true
    }
}

private class ConnectCall(val host: String, val password: String, val expectedGuid: UUID?)

private class FakeConnector(private val connection: FakeConnection) : SetupConnector {
    val calls = mutableListOf<ConnectCall>()
    var failure: SmbException? = null

    override fun connect(server: SmbServer, password: CharArray, expectedGuid: UUID?): SetupConnection {
        // Recorded as it is now: the caller wipes the array after the call.
        calls += ConnectCall(server.host, String(password), expectedGuid)
        failure?.let { throw it }
        return connection
    }
}

private class FlakySettings(private val inner: SettingsRepository) : SettingsRepository {
    override val settings = inner.settings
    var failUpdates = false

    override fun update(transform: (KpmSettings) -> KpmSettings): KpmSettings {
        if (failUpdates) throw IllegalStateException("disk full")
        return inner.update(transform)
    }
}

private class FakePasswords : SmbPasswords {
    var stored: CharArray? = null

    /** The n-th call of [save] fails (counting from 1). */
    var failSaveCall: Int? = null
    private var saveCalls = 0

    /** Every array handed out by [load], to check that the caller wipes it. */
    val loaded = mutableListOf<CharArray>()

    override fun hasStoredValue() = stored != null

    override fun load(): CharArray? = stored?.copyOf()?.also { loaded += it }

    override fun save(password: CharArray) {
        saveCalls++
        if (saveCalls == failSaveCall) throw IllegalStateException("keystore unavailable")
        stored = password.copyOf()
    }

    override fun clear() {
        stored = null
    }

    fun storedText(): String? = stored?.let { String(it) }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SourceSetupTest {

    @TempDir
    lateinit var dir: File

    private val t0: Instant = Instant.parse("2026-11-01T10:00:00Z")
    private val clock = MutableClock(t0)
    private val opener = FakeOpener()
    private val effects = RecordingEffects()
    private val guid = UUID.fromString("74696572-6e61-2d6f-6e61-730000000000")
    private val oldSource = SourceConfig("nas", 445, "", "kpm", "daten", listOf("old.kdbx"))
    private val address = ServerAddress(host = "nas", user = "kpm")
    private val connection = FakeConnection(guid, kdbx("old", "v1"), t0.minus(Duration.ofHours(1)))
    private val connector = FakeConnector(connection)
    private val passwords = FakePasswords()
    private lateinit var engine: SyncEngine
    private lateinit var settings: MemorySettingsRepository

    private fun syncDir() = File(dir, "sync")

    @BeforeEach
    fun setUp() {
        engine = SyncEngine(syncDir(), opener, clock)
        settings = MemorySettingsRepository(KpmSettings(source = oldSource, trustedServer = TrustedServer(guid, t0)))
    }

    /** The offline copy of the old file (key "old") exists, like after a normal first run. */
    private fun seedOffline() {
        val r = FakeRemote(kdbx("old", "v1"), t0.minus(Duration.ofHours(1)))
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(r))
        val opened = engine.unlock("old".toByteArray(), KeySource.TYPED_PASSWORD)
        assertIs<UnlockResult.Opened>(opened).vault.close()
    }

    private fun TestScope.session(settings: SettingsRepository = this@SourceSetupTest.settings) = Session(
        engine = engine,
        hashStore = FakeHashStore(),
        settings = settings,
        remotes = { null },
        effects = effects,
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        clock = clock,
        elapsedMillis = { testScheduler.currentTime },
        hasher = { String(it).toByteArray() },
    )

    private fun TestScope.setup(session: Session) =
        SourceSetup(connector, settings, passwords, session, clock, StandardTestDispatcher(testScheduler))

    private fun previous() = File(syncDir(), "offline-previous.kdbx")

    private fun candidate() = File(syncDir(), "candidate.kdbx")

    private fun offline() = File(syncDir(), "offline.kdbx")

    // ---- connectWithStoredPassword ----

    @Test
    fun `stored password is offered only for the account of the current source`() = runTest {
        passwords.stored = "secret".toCharArray()
        val setup = setup(session())
        assertTrue(setup.canUseStoredPassword(address))
        assertTrue(setup.canUseStoredPassword(address.copy(host = "NAS", user = "KPM")))
        for (other in listOf(
            address.copy(host = "other-nas"),
            address.copy(user = "admin"),
            address.copy(port = 4450),
            address.copy(domain = "WORKGROUP"),
        )) {
            assertFalse(setup.canUseStoredPassword(other), "$other")
            assertEquals(ConnectResult.Failed(ConnectError.PasswordRequired), setup.connectWithStoredPassword(other))
        }
        assertTrue(connector.calls.isEmpty(), "nothing was sent to any server")
        assertTrue(passwords.loaded.isEmpty(), "the stored password was not even read")
    }

    @Test
    fun `without a stored password the password is required and nothing is sent`() = runTest {
        val setup = setup(session())
        assertFalse(setup.canUseStoredPassword(address))
        assertEquals(ConnectResult.Failed(ConnectError.PasswordRequired), setup.connectWithStoredPassword(address))
        assertTrue(connector.calls.isEmpty())
    }

    @Test
    fun `stored password connects and its copy is wiped`() = runTest {
        passwords.stored = "secret".toCharArray()
        val setup = setup(session())
        val result = assertIs<ConnectResult.Connected>(setup.connectWithStoredPassword(address))
        assertEquals("reitano-nas", result.server.name)
        assertEquals(listOf("secret"), connector.calls.map { it.password })
        assertEquals(guid, connector.calls.single().expectedGuid, "the remembered server identity is still checked")
        assertTrue(passwords.loaded.isNotEmpty() && passwords.loaded.all { c -> c.all { it == '\u0000' } }, "loaded copy wiped")
        setup.close()
    }

    @Test
    fun `a stale stored password gives LoginFailed and the typed one still works`() = runTest {
        passwords.stored = "stale".toCharArray()
        val setup = setup(session())
        connector.failure = SmbException(SmbException.Reason.AUTH_FAILED, "rejected")
        assertEquals(ConnectResult.Failed(ConnectError.LoginFailed), setup.connectWithStoredPassword(address))
        assertTrue(passwords.loaded.all { c -> c.all { it == '\u0000' } })

        connector.failure = null
        assertIs<ConnectResult.Connected>(setup.connect(address, "typed".toCharArray()))
        assertEquals(listOf("stale", "typed"), connector.calls.map { it.password })
        setup.close()
    }

    // ---- chooseFile and the source change ----

    private suspend fun TestScope.choose(setup: SourceSetup, file: String): ChooseResult {
        assertIs<ConnectResult.Connected>(setup.connect(address, "smb-pw".toCharArray()))
        return setup.chooseFile("daten", listOf(file))
    }

    @Test
    fun `choosing the same file again changes nothing`() = runTest {
        seedOffline()
        val setup = setup(session())
        val saved = assertIs<ChooseResult.Saved>(choose(setup, "old.kdbx"))
        assertFalse(saved.fileChanged)
        assertEquals(SyncResult.UpToDate, saved.sync)
        assertFalse(previous().exists())
        assertTrue(offline().exists())
        setup.close()
    }

    @Test
    fun `another file locked waits as candidate and the old copy is set aside before the source is saved`() = runTest {
        seedOffline()
        connection.content = kdbx("new", "other")
        connection.modified = t0.minus(Duration.ofDays(2))
        val s = session()
        val setup = setup(s)
        val saved = assertIs<ChooseResult.Saved>(choose(setup, "new.kdbx"))

        assertTrue(saved.fileChanged)
        assertEquals(SyncResult.CandidateReady, saved.sync)
        assertTrue(previous().exists())
        assertFalse(offline().exists())
        assertEquals(listOf("new.kdbx"), settings.settings.value.source!!.filePath)
        assertEquals("smb-pw", String(passwords.stored!!))
        assertTrue(s.sync.value.status.hasLocalDatabase, "the candidate")
        setup.close()
    }

    @Test
    fun `another file with the same master password replaces the copy at once while unlocked`() = runTest {
        seedOffline()
        connection.content = kdbx("old", "other")
        connection.modified = t0.minus(Duration.ofDays(2))
        val s = session()
        s.unlockWithPassword("old".toCharArray())
        runCurrent()
        val setup = setup(s)
        val saved = assertIs<ChooseResult.Saved>(choose(setup, "new.kdbx"))

        assertEquals(SyncResult.Updated, saved.sync)
        assertFalse(previous().exists(), "the new file already is the offline copy")
        assertEquals(kdbx("old", "other"), offline().readText())
        assertEquals("other", assertIs<SessionState.Unlocked>(s.state.value).vault.rootGroupName)
        setup.close()
    }

    @Test
    fun `another file with another master password locks an unlocked session`() = runTest {
        seedOffline()
        connection.content = kdbx("new", "other")
        connection.modified = t0.minus(Duration.ofDays(2))
        val s = session()
        s.unlockWithPassword("old".toCharArray())
        runCurrent()
        val setup = setup(s)
        val saved = assertIs<ChooseResult.Saved>(choose(setup, "new.kdbx"))

        assertEquals(SyncResult.NewVersionRejected, saved.sync)
        assertEquals(LockReason.SourceChanged, assertIs<SessionState.Locked>(s.state.value).reason)
        assertTrue(previous().exists())
        assertEquals(listOf("new.kdbx"), settings.settings.value.source!!.filePath)
        setup.close()
    }

    @Test
    fun `a failed download saves nothing and keeps the old copy`() = runTest {
        seedOffline()
        passwords.stored = "old-pw".toCharArray()
        connection.statError = SmbException(SmbException.Reason.UNREACHABLE, "down")
        val setup = setup(session())
        assertEquals(ChooseResult.Failed(SyncProblem.UNREACHABLE), choose(setup, "new.kdbx"))
        assertFalse(previous().exists())
        assertTrue(offline().exists())
        assertEquals(oldSource, settings.settings.value.source)
        assertEquals("old-pw", passwords.storedText(), "the password of the old source is back")
        setup.close()
    }

    @Test
    fun `a keystore failure saves nothing and keeps the old copy`() = runTest {
        seedOffline()
        connection.content = kdbx("new", "other")
        connection.modified = t0.minus(Duration.ofDays(2))
        passwords.stored = "old-pw".toCharArray()
        passwords.failSaveCall = 1
        val setup = setup(session())
        assertEquals(ChooseResult.Failed(SyncProblem.LOCAL_STORAGE), choose(setup, "new.kdbx"))
        assertFalse(previous().exists())
        assertFalse(candidate().exists())
        assertTrue(offline().exists())
        assertEquals(oldSource, settings.settings.value.source)
        assertEquals("old-pw", passwords.storedText())
        setup.close()
    }

    @Test
    fun `when the old copy cannot be set aside the source stays unchanged`() = runTest {
        seedOffline()
        connection.content = kdbx("new", "other")
        connection.modified = t0.minus(Duration.ofDays(2))
        // A non-empty directory in the way makes the rename fail.
        File(previous(), "block").apply { parentFile.mkdirs(); writeText("x") }
        passwords.stored = "old-pw".toCharArray()
        val setup = setup(session())
        assertEquals(ChooseResult.Failed(SyncProblem.LOCAL_STORAGE), choose(setup, "new.kdbx"))
        assertEquals(oldSource, settings.settings.value.source)
        assertTrue(offline().exists())
        assertFalse(candidate().exists(), "the foreign file must not be promoted for the old source")
        // The old server must never get the new server's password (DS-04).
        assertEquals("old-pw", passwords.storedText())
        assertFalse(setup.canUseStoredPassword(address.copy(host = "other-nas")))
        setup.close()
    }

    @Test
    fun `the new password is stored only after the download`() = runTest {
        seedOffline()
        connection.content = kdbx("new", "other")
        connection.modified = t0.minus(Duration.ofDays(2))
        passwords.stored = "old-pw".toCharArray()
        var duringDownload: String? = null
        connection.onDownload = { duringDownload = passwords.storedText() }
        val setup = setup(session())
        assertIs<ChooseResult.Saved>(choose(setup, "new.kdbx"))
        assertEquals("old-pw", duringDownload)
        assertEquals("smb-pw", passwords.storedText())
        setup.close()
    }

    @Test
    fun `if the old password cannot be put back it is cleared and asked for again`() = runTest {
        seedOffline()
        connection.content = kdbx("new", "other")
        connection.modified = t0.minus(Duration.ofDays(2))
        passwords.stored = "old-pw".toCharArray()
        // Save #1 stores the new password, save #2 (the restore) fails.
        passwords.failSaveCall = 2
        val flaky = FlakySettings(settings)
        val setup = SourceSetup(connector, flaky, passwords, run { session(flaky) }, clock, StandardTestDispatcher(testScheduler))
        flaky.failUpdates = true

        assertEquals(ChooseResult.Failed(SyncProblem.LOCAL_STORAGE), choose(setup, "new.kdbx"))

        assertNull(passwords.stored, "neither the new server's nor a stale password stays")
        assertEquals(oldSource, settings.settings.value.source)
        assertTrue(offline().exists())
        assertFalse(candidate().exists())
        setup.close()
    }

    @Test
    fun `a stale connection is replaced once instead of failing the choice`() = runTest {
        seedOffline()
        val setup = setup(session())
        assertIs<ConnectResult.Connected>(setup.connect(address, "smb-pw".toCharArray()))
        // The connection went stale while browsing.
        connection.failStatOnce = SmbException(SmbException.Reason.UNREACHABLE, "gone")
        val saved = assertIs<ChooseResult.Saved>(setup.chooseFile("daten", listOf("old.kdbx")))
        assertFalse(saved.fileChanged)
        assertEquals(2, connector.calls.size, "reconnected once")
        setup.close()
    }

    @Test
    fun `a cancelled caller does not leave a half finished source change`() = runTest {
        seedOffline()
        connection.content = kdbx("new", "other")
        connection.modified = t0.minus(Duration.ofDays(2))
        passwords.stored = "old-pw".toCharArray()
        val s = session()
        s.unlockWithPassword("old".toCharArray())
        runCurrent()
        val setup = setup(s)
        assertIs<ConnectResult.Connected>(setup.connect(address, "smb-pw".toCharArray()))

        lateinit var caller: Job
        // The screen goes away in the middle of the download and closes the setup.
        connection.onDownload = {
            caller.cancel()
            setup.close()
            assertFalse(connection.closed, "the connection is not pulled away under the running change")
        }
        caller = launch { setup.chooseFile("daten", listOf("new.kdbx")) }
        caller.join()
        runCurrent()

        assertTrue(caller.isCancelled)
        assertTrue(connection.closed, "closed when the change is finished")
        // The change was finished anyway: source saved, old vault locked, password and copy consistent.
        assertEquals(listOf("new.kdbx"), settings.settings.value.source!!.filePath)
        assertEquals("smb-pw", passwords.storedText())
        assertEquals(LockReason.SourceChanged, assertIs<SessionState.Locked>(s.state.value).reason)
        assertTrue(previous().exists())
        setup.close()
    }
}
