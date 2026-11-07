package de.lulurobb.keypassmobile.platform.session

import de.lulurobb.keypassmobile.core.sync.SyncEngine
import de.lulurobb.keypassmobile.platform.saf.FakeDocuments
import de.lulurobb.keypassmobile.platform.settings.FileSource
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.MemorySettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SmbSource
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.platform.setup.DocumentSourceCleanup
import de.lulurobb.keypassmobile.platform.setup.SmbPasswords
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the process does once at its start: recovery, cleanup of lost grants, sync of a file source. */
@OptIn(ExperimentalCoroutinesApi::class)
class StartupWorkTest {

    @TempDir
    lateinit var dir: File

    private val t0: Instant = Instant.parse("2026-11-01T10:00:00Z")
    private val clock = MutableClock(t0)
    private val opener = FakeOpener()
    private val events = mutableListOf<String>()
    private lateinit var engine: SyncEngine

    private val smb = SmbSource("nas", 445, "", "kpm", "daten", listOf("old.kdbx"))
    private val fileA = FileSource("content://fake.documents/document/primary%3AA%2Fold.kdbx", "old.kdbx", "Interner Speicher / A")
    private val fileB = FileSource("content://fake.documents/document/primary%3AB%2Fnew.kdbx", "new.kdbx", "Interner Speicher / B")

    @BeforeEach
    fun setUp() {
        engine = SyncEngine(File(dir, "sync"), opener, clock)
    }

    private fun TestScope.session(
        settings: MemorySettingsRepository,
        journal: SwitchJournal = SwitchJournal.None,
        remote: FakeRemote? = null,
        cleanup: SourceCleanup = SourceCleanup {},
        onOpen: () -> Unit = {},
    ) = Session(
        engine = engine,
        hashStore = FakeHashStore(),
        settings = settings,
        remotes = {
            events += "open"
            onOpen()
            remote
        },
        effects = RecordingEffects(),
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        clock = clock,
        elapsedMillis = { testScheduler.currentTime },
        hasher = { String(it).toByteArray() },
        journal = journal,
        sourceCleanup = cleanup,
    )

    private suspend fun TestScope.startup(
        session: Session,
        cleanup: SourceCleanup,
        settings: MemorySettingsRepository,
        gate: CompletableDeferred<Unit> = CompletableDeferred(),
    ) = runStartupWork(session, cleanup, settings, StandardTestDispatcher(testScheduler), gate)

    private fun serving() = FakeRemote(kdbx("pw", "v1"), t0.minus(Duration.ofHours(1)))

    @Test
    fun `a file source is synced silently at every start`() = runTest {
        val settings = MemorySettingsRepository(KpmSettings(setupCompleted = true, source = fileA))
        val s = session(settings, remote = serving())

        startup(s, RecordingCleanup(), settings)

        assertEquals(listOf("open"), events)
        val report = s.lastSyncReport.value
        assertEquals(SyncTrigger.SCHEDULED, report?.trigger)
        assertIs<SyncResult.CandidateReady>(report?.result)
    }

    @Test
    fun `an SMB source is not synced at the start, the worker does that`() = runTest {
        val settings = MemorySettingsRepository(KpmSettings(setupCompleted = true, source = smb))
        val s = session(settings, remote = serving())

        startup(s, RecordingCleanup(), settings)

        assertTrue(events.isEmpty())
        assertNull(s.lastSyncReport.value)
    }

    @Test
    fun `a file source before the setup is completed is not synced`() = runTest {
        val settings = MemorySettingsRepository(KpmSettings(setupCompleted = false, source = fileA))
        val s = session(settings, remote = serving())

        startup(s, RecordingCleanup(), settings)

        assertTrue(events.isEmpty())
    }

    @Test
    fun `the grants that do not fit the saved source are released at every start`() = runTest {
        // The process died between the check of a picked file and its commit: its grant has no journal record.
        val docs = FakeDocuments()
        docs.persisted += listOf(fileA.uri, fileB.uri)
        val settings = MemorySettingsRepository(KpmSettings(setupCompleted = true, source = fileA))
        val s = session(settings, remote = serving())
        val cleanup = DocumentSourceCleanup(docs, settings, NoPasswords)

        startup(s, cleanup, settings)

        assertEquals(setOf(fileA.uri), docs.persisted, "only the grant of the saved file stays")
        // Idempotent: a second start changes nothing.
        startup(s, cleanup, settings)
        assertEquals(setOf(fileA.uri), docs.persisted)
    }

    @Test
    fun `an SMB source keeps no file grant and the first run keeps none either`() = runTest {
        val docs = FakeDocuments()
        docs.persisted += fileB.uri
        val settings = MemorySettingsRepository(KpmSettings(setupCompleted = true, source = smb))
        startup(session(settings), DocumentSourceCleanup(docs, settings, NoPasswords), settings)
        assertTrue(docs.persisted.isEmpty())

        docs.persisted += fileB.uri
        val fresh = MemorySettingsRepository(KpmSettings(source = null))
        startup(session(fresh), DocumentSourceCleanup(docs, fresh, NoPasswords), fresh)
        assertTrue(docs.persisted.isEmpty())
    }

    @Test
    fun `the recovery runs first and the cleanup sees the source that is saved afterwards`() = runTest {
        val settings = MemorySettingsRepository(KpmSettings(setupCompleted = true, source = smb))
        val journal = object : SwitchJournal {
            var record: SwitchRecord? = SwitchRecord(fileB, differs = true, setAside = false, offlineReplaced = false, passwordTouched = false)

            override fun read(): SwitchRecord? = record

            override fun write(record: SwitchRecord?) {
                events += "journal"
                this.record = record
            }
        }
        val cleanup = object : SourceCleanup {
            val calls = mutableListOf<SourceConfig?>()

            override fun reconcile(source: SourceConfig?) {
                events += "reconcile"
                calls += source
            }
        }
        val s = session(settings, journal, cleanup = cleanup)

        startup(s, cleanup, settings)

        // The recovery reconciles once itself, then the start does it again: the order is journal clean-up first.
        assertEquals(listOf<SourceConfig?>(smb, smb), cleanup.calls)
        assertNull(journal.record)
        assertTrue(events.indexOf("reconcile") < events.lastIndexOf("reconcile"))
    }

    @Test
    fun `a failing cleanup does not stop the sync of a file source`() = runTest {
        val settings = MemorySettingsRepository(KpmSettings(setupCompleted = true, source = fileA))
        val s = session(settings, remote = serving())

        startup(s, RecordingCleanup().apply { failNext = 1 }, settings)

        assertEquals(listOf("open"), events)
    }

    @Test
    fun `the gate opens after the cleanup and before the sync`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val settings = MemorySettingsRepository(KpmSettings(setupCompleted = true, source = fileA))
        var openAtReconcile: Boolean? = null
        var openAtSync: Boolean? = null
        val cleanup = SourceCleanup { openAtReconcile = gate.isCompleted }
        val s = session(settings, remote = serving(), onOpen = { openAtSync = gate.isCompleted })

        startup(s, cleanup, settings, gate)

        assertEquals(false, openAtReconcile, "the pick still waits while the cleanup runs")
        assertEquals(true, openAtSync, "the sync of a file source does not hold the pick back")
        assertTrue(gate.isCompleted)
    }

    @Test
    fun `the gate opens when the cleanup fails or nothing is to do`() = runTest {
        val failing = CompletableDeferred<Unit>()
        val settings = MemorySettingsRepository(KpmSettings(setupCompleted = true, source = smb))
        startup(session(settings), RecordingCleanup().apply { failNext = 1 }, settings, failing)
        assertTrue(failing.isCompleted)

        val first = CompletableDeferred<Unit>()
        val fresh = MemorySettingsRepository(KpmSettings(source = null))
        startup(session(fresh), RecordingCleanup(), fresh, first)
        assertTrue(first.isCompleted, "first run, no source: still opens")
    }

    @Test
    fun `the gate opens when the journal cannot be read`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val settings = MemorySettingsRepository(KpmSettings(setupCompleted = true, source = smb))
        val broken = object : SwitchJournal {
            override fun read(): SwitchRecord? = throw java.io.IOException("unreadable")

            override fun write(record: SwitchRecord?) = Unit
        }

        startup(session(settings, broken), RecordingCleanup(), settings, gate)

        assertTrue(gate.isCompleted)
    }

    private object NoPasswords : SmbPasswords {
        override fun hasStoredValue() = false

        override fun load(): CharArray? = null

        override fun save(password: CharArray) = Unit

        override fun clear() = Unit
    }
}
