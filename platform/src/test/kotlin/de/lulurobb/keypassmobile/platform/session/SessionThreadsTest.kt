package de.lulurobb.keypassmobile.platform.session

import de.lulurobb.keypassmobile.core.index.EntryIndex
import de.lulurobb.keypassmobile.core.sync.BackgroundSyncResult
import de.lulurobb.keypassmobile.core.sync.KeySource
import de.lulurobb.keypassmobile.core.sync.RemoteSource
import de.lulurobb.keypassmobile.core.sync.SyncEngine
import de.lulurobb.keypassmobile.core.sync.UnlockResult
import de.lulurobb.keypassmobile.platform.settings.MemorySettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Real threads where the order of steps must be forced (virtual time cannot stop in the middle of a blocking call). */
class SessionThreadsTest {

    @TempDir
    lateinit var dir: File

    private val t0: Instant = Instant.parse("2026-11-01T10:00:00Z")

    private class NoSwitch(override val remote: RemoteSource) : SourceSwitch {
        override val target = SourceConfig("nas", 445, "", "kpm", "daten", listOf("new.kdbx"))
        override val differs = true

        override fun stage() = Unit

        override fun commit() = Unit

        override fun rollback() = Unit

        override fun finish() = Unit
    }

    @Test
    fun `a source change while an unlock is in progress locks and aborts the unlock`() {
        val clock = MutableClock(t0)
        val opener = FakeOpener()
        val engine = SyncEngine(File(dir, "sync"), opener, clock)
        val seed = FakeRemote(kdbx("old", "v1"), t0.minus(Duration.ofHours(1)))
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(seed))
        assertIs<UnlockResult.Opened>(engine.unlock("old".toByteArray(), KeySource.TYPED_PASSWORD)).vault.close()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val inIndexer = CountDownLatch(1)
        val release = CountDownLatch(1)
        val session = Session(
            engine = engine,
            hashStore = FakeHashStore(),
            settings = MemorySettingsRepository(),
            remotes = { null },
            effects = RecordingEffects(),
            scope = scope,
            io = Dispatchers.IO,
            clock = clock,
            elapsedMillis = System::nanoTime,
            hasher = { String(it).toByteArray() },
            // The vault is open and nothing is published yet: the state is Unlocking.
            indexer = { vault ->
                inIndexer.countDown()
                assertTrue(release.await(10, TimeUnit.SECONDS))
                EntryIndex(vault)
            },
        )
        try {
            runBlocking {
                val unlock = async(Dispatchers.Default) { session.unlockWithPassword("old".toCharArray()) }
                assertTrue(inIndexer.await(10, TimeUnit.SECONDS))
                assertEquals(SessionState.Unlocking, session.state.value)

                val newRemote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))
                assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady), session.switchSource(NoSwitch(newRemote)))
                // The old file must not appear after the source changed.
                assertEquals(LockReason.SourceChanged, assertIs<SessionState.Locked>(session.state.value).reason)

                release.countDown()
                assertEquals(UnlockOutcome.Aborted, unlock.await())
            }
            assertIs<SessionState.Locked>(session.state.value)
            assertTrue(opener.secrets.drop(1).first().closed, "the old vault that was opened for the unlock is closed")
            assertFalse(File(dir, "sync/offline.kdbx").exists())
            assertTrue(File(dir, "sync/offline-previous.kdbx").exists())
        } finally {
            release.countDown()
            scope.cancel()
        }
    }
}
