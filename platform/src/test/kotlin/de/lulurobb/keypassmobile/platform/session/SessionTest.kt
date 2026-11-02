package de.lulurobb.keypassmobile.platform.session

import de.lulurobb.keypassmobile.core.index.EntryIndex
import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.settings.PasswordInterval
import de.lulurobb.keypassmobile.core.sync.BackgroundSyncResult
import de.lulurobb.keypassmobile.core.sync.CandidateRejection
import de.lulurobb.keypassmobile.core.sync.CandidateState
import de.lulurobb.keypassmobile.core.sync.KeySource
import de.lulurobb.keypassmobile.core.sync.OpenedVersion
import de.lulurobb.keypassmobile.core.sync.RemoteError
import de.lulurobb.keypassmobile.core.sync.RemoteException
import de.lulurobb.keypassmobile.core.sync.SyncEngine
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.core.sync.UnlockResult
import de.lulurobb.keypassmobile.model.Vault
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.MemorySettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.platform.settings.TrustedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SessionTest {

    @TempDir
    lateinit var dir: File

    private val t0: Instant = Instant.parse("2026-11-01T10:00:00Z")
    private val clock = MutableClock(t0)
    private val opener = FakeOpener()
    private val hashes = FakeHashStore()
    private val effects = RecordingEffects()
    private var remote: FakeRemote? = null

    /** Order of what the session asked for, e.g. "open" (the configured remote) and the steps of a [StubSwitch]. */
    private val events = mutableListOf<String>()
    private lateinit var engine: SyncEngine

    @BeforeEach
    fun setUp() {
        engine = SyncEngine(File(dir, "sync"), opener, clock)
    }

    private fun TestScope.session(
        settings: SettingsRepository = MemorySettingsRepository(),
        elapsed: (() -> Long)? = null,
        effects: SessionEffects = this@SessionTest.effects,
        indexer: (Vault) -> EntryIndex = ::EntryIndex,
        scope: CoroutineScope = backgroundScope,
        journal: SwitchJournal = SwitchJournal.None,
        clearPassword: () -> Unit = {},
    ) = Session(
        engine = engine,
        hashStore = hashes,
        settings = settings,
        remotes = {
            events += "open"
            remote
        },
        effects = effects,
        scope = scope,
        io = StandardTestDispatcher(testScheduler),
        clock = clock,
        elapsedMillis = elapsed ?: { testScheduler.currentTime },
        hasher = { String(it).toByteArray() },
        indexer = indexer,
        journal = journal,
        clearSmbPassword = clearPassword,
    )

    /** Offline copy that opens with [key]; the remote serves the same version. */
    private fun seedOffline(key: String, label: String) {
        val r = FakeRemote(kdbx(key, label), t0.minus(Duration.ofHours(1)))
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(r))
        val opened = assertIs<UnlockResult.Opened>(engine.unlock(key.toByteArray(), KeySource.TYPED_PASSWORD))
        opened.vault.close()
        remote = r
    }

    private fun fingerprintSettings(entry: Instant = t0, atEntry: PasswordInterval = PasswordInterval.SEVEN_DAYS) =
        MemorySettingsRepository(
            KpmSettings(fingerprintEnabled = true, lastPasswordEntry = entry, intervalAtEntry = atEntry),
        )

    private fun ByteArray.isWiped() = all { it == 0.toByte() }

    // ---- typed password ----

    @Test
    fun `typed password unlocks, records the entry and stores the hash for the fingerprint`() = runTest {
        seedOffline("pw", "v1")
        val settings = MemorySettingsRepository(KpmSettings(fingerprintEnabled = true))
        val s = session(settings)
        val password = "pw".toCharArray()

        val outcome = assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword(password))
        runCurrent()

        assertEquals(7, outcome.fingerprintRenewedForDays)
        assertFalse(outcome.fingerprintStoreFailed)
        assertFalse(outcome.newVersionOpened)
        assertNull(outcome.candidateRejected)
        assertTrue(password.all { it == '\u0000' }, "typed password must be wiped")
        val unlocked = assertIs<SessionState.Unlocked>(s.state.value)
        assertEquals("v1", unlocked.vault.rootGroupName)
        assertEquals(KeySource.TYPED_PASSWORD, unlocked.keySource)
        assertEquals(t0, settings.settings.value.lastPasswordEntry)
        assertEquals(PasswordInterval.SEVEN_DAYS, settings.settings.value.intervalAtEntry)
        assertContentEquals("pw".toByteArray(), hashes.stored)
        assertEquals(t0.plus(Duration.ofDays(7)), hashes.validityEnd)
        assertEquals(1, effects.unlocked)
    }

    @Test
    fun `typed password with fingerprint off stores nothing`() = runTest {
        seedOffline("pw", "v1")
        val s = session()
        val outcome = assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("pw".toCharArray()))
        runCurrent()
        assertNull(outcome.fingerprintRenewedForDays)
        assertFalse(outcome.fingerprintStoreFailed)
        assertNull(hashes.stored)
    }

    @Test
    fun `setup step 3 switches the fingerprint on with the unlock`() = runTest {
        seedOffline("pw", "v1")
        val settings = MemorySettingsRepository()
        val s = session(settings)
        val outcome = assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("pw".toCharArray(), enableFingerprint = true))
        runCurrent()
        assertEquals(7, outcome.fingerprintRenewedForDays)
        assertTrue(settings.settings.value.fingerprintEnabled)
        assertContentEquals("pw".toByteArray(), hashes.stored)
    }

    @Test
    fun `failing to store the hash still unlocks`() = runTest {
        seedOffline("pw", "v1")
        hashes.failStore = true
        val s = session(MemorySettingsRepository(KpmSettings(fingerprintEnabled = true)))
        val outcome = assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("pw".toCharArray()))
        runCurrent()
        assertIs<SessionState.Unlocked>(s.state.value)
        assertNull(hashes.stored)
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.NOT_SET_UP), s.unlockOffer())
        // No false success: the outcome does not claim the fingerprint is active.
        assertNull(outcome.fingerprintRenewedForDays)
        assertTrue(outcome.fingerprintStoreFailed)
        assertFalse(s.storeJob!!.await())
    }

    @Test
    fun `outcome waits for the store of the hash and reports its success`() = runTest {
        seedOffline("pw", "v1")
        val s = session(MemorySettingsRepository(KpmSettings(fingerprintEnabled = true)))
        val outcome = assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("pw".toCharArray()))
        // No runCurrent(): the value is stored by the time the outcome is there.
        assertContentEquals("pw".toByteArray(), hashes.stored)
        assertEquals(7, outcome.fingerprintRenewedForDays)
        assertFalse(outcome.fingerprintStoreFailed)
    }

    @Test
    fun `wrong password stays locked and wipes the hash`() = runTest {
        seedOffline("pw", "v1")
        val s = session()
        val password = "nope".toCharArray()
        assertEquals(UnlockOutcome.WrongPassword, s.unlockWithPassword(password))
        assertTrue(password.all { it == '\u0000' })
        assertTrue(opener.hashes.last().isWiped())
        assertNull(assertIs<SessionState.Locked>(s.state.value).reason)
        assertEquals(0, effects.unlocked)
    }

    @Test
    fun `no local file is reported as NoDatabase`() = runTest {
        val s = session()
        assertEquals(UnlockOutcome.NoDatabase, s.unlockWithPassword("pw".toCharArray()))
        assertIs<SessionState.Locked>(s.state.value)
    }

    @Test
    fun `damaged file is reported as Corrupt`() = runTest {
        remote = FakeRemote("KDBX|broken", t0)
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(remote!!))
        val s = session()
        assertEquals(UnlockOutcome.Corrupt, s.unlockWithPassword("pw".toCharArray()))
    }

    @Test
    fun `unlock while unlocked returns Unlocked and wipes the password`() = runTest {
        seedOffline("pw", "v1")
        val s = session()
        s.unlockWithPassword("pw".toCharArray())
        val again = "pw".toCharArray()
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword(again))
        assertTrue(again.all { it == '\u0000' })
    }

    // ---- fingerprint ----

    @Test
    fun `stored hash unlocks without recording a password entry`() = runTest {
        seedOffline("pw", "v1")
        hashes.stored = "pw".toByteArray()
        val settings = fingerprintSettings(entry = t0.minus(Duration.ofDays(2)))
        val s = session(settings)
        val outcome = assertIs<UnlockOutcome.Unlocked>(s.unlockWithStoredHash("pw".toByteArray()))
        assertNull(outcome.fingerprintRenewedForDays)
        assertEquals(KeySource.FINGERPRINT, assertIs<SessionState.Unlocked>(s.state.value).keySource)
        assertEquals(t0.minus(Duration.ofDays(2)), settings.settings.value.lastPasswordEntry)
    }

    @Test
    fun `stored hash that no longer opens asks for the password and deletes the value`() = runTest {
        seedOffline("pw", "v1")
        hashes.stored = "old".toByteArray()
        val s = session(fingerprintSettings())
        val hash = "old".toByteArray()
        assertEquals(
            UnlockOutcome.PasswordRequired(PasswordRequiredReason.STORED_VALUE_REJECTED),
            s.unlockWithStoredHash(hash),
        )
        assertTrue(hash.isWiped())
        assertNull(hashes.stored)
        assertIs<SessionState.Locked>(s.state.value)
    }

    @Test
    fun `unlock offer follows switch, stored value and interval`() = runTest {
        // Fingerprint off.
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.NOT_SET_UP), session().unlockOffer())

        // On, but nothing stored.
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.NOT_SET_UP), session(fingerprintSettings()).unlockOffer())

        // On, stored, entry within the interval.
        hashes.stored = ByteArray(32)
        val fresh = session(fingerprintSettings(entry = t0.minus(Duration.ofDays(6))))
        assertEquals(UnlockOffer.Fingerprint, fresh.unlockOffer())
        assertTrue(fresh.isFingerprintUsable())
    }

    @Test
    fun `due password deletes the stored value and keeps saying why`() = runTest {
        hashes.stored = ByteArray(32)
        val s = session(fingerprintSettings(entry = t0.minus(Duration.ofDays(7))))
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.DUE), s.unlockOffer())
        // Deleted when the offer is there, no runCurrent() needed.
        assertNull(hashes.stored)
        assertEquals(1, hashes.clears)
        // The reason survives the deletion until the next typed password.
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.DUE), s.unlockOffer())
        assertFalse(s.isFingerprintUsable())
        assertEquals(1, hashes.clears)
    }

    @Test
    fun `a stale entry without a stored value is not reported as due`() = runTest {
        // The fingerprint was switched on long after the last entry; nothing was ever stored.
        val s = session(fingerprintSettings(entry = t0.minus(Duration.ofDays(30))))
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.NOT_SET_UP), s.unlockOffer())
        assertFalse(s.expireFingerprint())
    }

    @Test
    fun `the expired notice is persisted and ends with the next typed password`() = runTest {
        seedOffline("pw", "v1")
        hashes.stored = ByteArray(32)
        val settings = fingerprintSettings(entry = t0.minus(Duration.ofDays(8)))
        val s = session(settings)
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.DUE), s.unlockOffer())
        assertTrue(settings.settings.value.fingerprintExpired)
        // The fingerprint path itself (KpmPlatform) deletes through the same function.
        s.clearStoredHash(expired = true)
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.DUE), s.unlockOffer())

        s.unlockWithPassword("pw".toCharArray())
        assertFalse(settings.settings.value.fingerprintExpired)
        assertContentEquals("pw".toByteArray(), hashes.stored)
    }

    @Test
    fun `expiry cleanup never deletes a hash that a typed password stored meanwhile`() = runTest {
        seedOffline("pw", "v1")
        hashes.stored = "old".toByteArray()
        val s = session(fingerprintSettings(entry = t0.minus(Duration.ofDays(8))))
        // The typed password renews the entry time and stores the new hash.
        s.unlockWithPassword("pw".toCharArray())
        assertContentEquals("pw".toByteArray(), hashes.stored)
        // A cleanup that still looks at the old situation decides again inside the lock: nothing to delete.
        assertFalse(s.expireFingerprint())
        assertContentEquals("pw".toByteArray(), hashes.stored)
        assertEquals(0, hashes.clears)
    }

    @Test
    fun `fingerprint queries have no side effects, the expiry cleanup is explicit`() = runTest {
        hashes.stored = ByteArray(32)
        val s = session(fingerprintSettings(entry = t0.minus(Duration.ofDays(7))))

        assertFalse(s.isFingerprintUsable())
        assertEquals(PasswordRequiredReason.DUE, s.fingerprintBlocker())
        runCurrent()
        assertTrue(hashes.stored != null, "queries must not delete the stored value")
        assertEquals(0, hashes.clears)

        assertTrue(s.expireFingerprint())
        assertNull(hashes.stored)
        assertEquals(1, hashes.clears)
        // Nothing stored any more: nothing to expire.
        assertFalse(s.expireFingerprint())
    }

    @Test
    fun `expiry cleanup leaves a valid value alone`() = runTest {
        hashes.stored = ByteArray(32)
        val fresh = session(fingerprintSettings(entry = t0.minus(Duration.ofDays(6))))
        assertFalse(fresh.expireFingerprint())
        runCurrent()
        assertTrue(hashes.stored != null)
        assertEquals(0, hashes.clears)
    }

    @Test
    fun `shorter interval applies at once, longer one only from the next entry`() = runTest {
        hashes.stored = ByteArray(32)
        val shorter = MemorySettingsRepository(
            KpmSettings(
                fingerprintEnabled = true,
                lastPasswordEntry = t0.minus(Duration.ofDays(2)),
                intervalAtEntry = PasswordInterval.SEVEN_DAYS,
                passwordInterval = PasswordInterval.ONE_DAY,
            ),
        )
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.DUE), session(shorter).unlockOffer())

        hashes.stored = ByteArray(32)
        val longer = MemorySettingsRepository(
            KpmSettings(
                fingerprintEnabled = true,
                lastPasswordEntry = t0.minus(Duration.ofDays(8)),
                intervalAtEntry = PasswordInterval.SEVEN_DAYS,
                passwordInterval = PasswordInterval.FOURTEEN_DAYS,
            ),
        )
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.DUE), session(longer).unlockOffer())
    }

    @Test
    fun `clock set back before the last entry makes the password due`() = runTest {
        hashes.stored = ByteArray(32)
        val s = session(fingerprintSettings(entry = t0.plus(Duration.ofHours(1))))
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.DUE), s.unlockOffer())
    }

    @Test
    fun `switching the fingerprint on while unlocked stores with the validity of the last entry`() = runTest {
        seedOffline("pw", "v1")
        val settings = MemorySettingsRepository()
        val s = session(settings)
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        assertNull(hashes.stored)
        clock.advance(Duration.ofDays(2))

        assertEquals(FingerprintSetupResult.STORED, s.setFingerprintEnabled(true))
        assertContentEquals("pw".toByteArray(), hashes.stored)
        assertEquals(t0.plus(Duration.ofDays(7)), hashes.validityEnd)

        assertEquals(FingerprintSetupResult.DISABLED, s.setFingerprintEnabled(false))
        assertNull(hashes.stored)
        assertFalse(settings.settings.value.fingerprintEnabled)
    }

    @Test
    fun `switching the fingerprint on while locked waits for the next password`() = runTest {
        val s = session()
        assertEquals(FingerprintSetupResult.ON_NEXT_PASSWORD, s.setFingerprintEnabled(true))
        assertNull(hashes.stored)
    }

    // ---- lock and idle ----

    @Test
    fun `lock closes the vault, wipes the hash and tells the effects`() = runTest {
        seedOffline("pw", "v1")
        val s = session()
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        val sessionHash = opener.hashes.last()
        assertFalse(sessionHash.isWiped())

        s.lock(LockReason.User)

        assertTrue(sessionHash.isWiped())
        assertTrue(opener.secrets.last().closed)
        assertEquals(LockReason.User, assertIs<SessionState.Locked>(s.state.value).reason)
        assertEquals(listOf<LockReason>(LockReason.User), effects.locks)
        // Locking twice keeps the first reason.
        s.lock(LockReason.TaskRemoved)
        assertEquals(LockReason.User, assertIs<SessionState.Locked>(s.state.value).reason)
        assertEquals(1, effects.locks.size)
    }

    @Test
    fun `refused foreground service locks right after the unlock`() = runTest {
        seedOffline("pw", "v1")
        lateinit var s: Session
        // As KpmPlatform does when UnlockedService.start() returns false.
        val refusing = object : SessionEffects {
            override fun onUnlocked() = s.lock(LockReason.ServiceRefused)

            override fun onLocked(reason: LockReason) = effects.onLocked(reason)
        }
        s = session(effects = refusing)
        val reports = collectReports(s)
        // The outcome does not claim Unlocked: the session is locked already.
        assertEquals(UnlockOutcome.Aborted, s.unlockWithPassword("pw".toCharArray()))
        runCurrent()

        assertTrue(reports.isEmpty(), "no refresh after the lock")
        assertEquals(0, remote!!.closed, "the share was not contacted")
        assertEquals(LockReason.ServiceRefused, assertIs<SessionState.Locked>(s.state.value).reason)
        assertEquals(listOf<LockReason>(LockReason.ServiceRefused), effects.locks)
        assertTrue(opener.secrets.all { it.closed }, "every vault closed")
        assertTrue(opener.hashes.drop(1).all { it.isWiped() }, "session hash wiped")
    }

    @Test
    fun `idle lock after the timeout, activity postpones it`() = runTest {
        seedOffline("pw", "v1")
        val s = session(MemorySettingsRepository(KpmSettings(idleTimeout = IdleTimeout.SECONDS_30)))
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()

        advanceTimeBy(20_000)
        runCurrent()
        s.touch()
        advanceTimeBy(25_000)
        runCurrent()
        assertIs<SessionState.Unlocked>(s.state.value)

        advanceTimeBy(10_000)
        runCurrent()
        val locked = assertIs<SessionState.Locked>(s.state.value)
        assertEquals(LockReason.Idle(IdleTimeout.SECONDS_30), locked.reason)
        assertTrue(opener.secrets.last().closed)
    }

    @Test
    fun `checkIdle locks when the elapsed clock moved in deep sleep`() = runTest {
        seedOffline("pw", "v1")
        var elapsed = 1_000L
        val s = session(elapsed = { elapsed })
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        assertFalse(s.checkIdle())
        elapsed += IdleTimeout.DEFAULT.duration.toMillis()
        assertNull(s.unlockedOrNull())
        assertEquals(LockReason.Idle(IdleTimeout.DEFAULT), assertIs<SessionState.Locked>(s.state.value).reason)
    }

    @Test
    fun `lock during the unlock discards the opened vault`() = runTest {
        seedOffline("pw", "v1")
        val s = session()
        opener.beforeOpen = { s.lock(LockReason.TaskRemoved) }
        assertEquals(UnlockOutcome.Aborted, s.unlockWithPassword("pw".toCharArray()))
        assertEquals(LockReason.TaskRemoved, assertIs<SessionState.Locked>(s.state.value).reason)
        assertTrue(opener.secrets.last().closed)
        assertTrue(opener.hashes.last().isWiped())
        assertEquals(0, effects.unlocked)
    }

    // ---- sync ----

    @Test
    fun `refresh after unlock shows the newer version and closes the old vault`() = runTest {
        seedOffline("pw", "v1")
        remote!!.publish(kdbx("pw", "v2"), t0)
        val s = session()
        s.unlockWithPassword("pw".toCharArray())
        val first = assertIs<SessionState.Unlocked>(s.state.value)
        assertEquals("v1", first.vault.rootGroupName)

        runCurrent()

        val second = assertIs<SessionState.Unlocked>(s.state.value)
        assertEquals("v2", second.vault.rootGroupName)
        assertEquals(1, second.version)
        assertTrue(opener.secrets[1].closed, "previous vault closed")
        assertFalse(opener.secrets[2].closed)
        assertEquals(OpenedVersion.SHARE, s.sync.value.status.openedVersion)
        assertFalse(s.sync.value.syncing)
        // The hash copies of the refresh are wiped; only the session's own hash is intact.
        assertTrue(opener.hashes.drop(2).all { it.isWiped() })
        assertTrue(remote!!.closed > 0)
    }

    @Test
    fun `newer version with another password sets the banner`() = runTest {
        seedOffline("pw", "v1")
        remote!!.publish(kdbx("new", "v2"), t0)
        val s = session()
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        val unlocked = assertIs<SessionState.Unlocked>(s.state.value)
        assertTrue(unlocked.newVersionRejected)
        assertEquals("v1", unlocked.vault.rootGroupName)
        assertEquals(SyncProblem.NEW_VERSION_REJECTED, s.sync.value.status.lastProblem)

        // The new password opens the kept candidate.
        s.lock(LockReason.User)
        val outcome = assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("new".toCharArray()))
        assertTrue(outcome.newVersionOpened)
        assertFalse(assertIs<SessionState.Unlocked>(s.state.value).newVersionRejected)
    }

    @Test
    fun `candidate rejected at unlock is reported`() = runTest {
        seedOffline("old", "v1")
        remote!!.publish(kdbx("new", "v2"), t0)
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(remote!!))
        val s = session()
        val outcome = assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("old".toCharArray()))
        assertEquals(CandidateRejection.WRONG_KEY, outcome.candidateRejected)
        assertTrue(assertIs<SessionState.Unlocked>(s.state.value).newVersionRejected)
    }

    @Test
    fun `lock during the refresh closes the downloaded vault`() = runTest {
        seedOffline("pw", "v1")
        remote!!.publish(kdbx("pw", "v2"), t0)
        val s = session()
        val reports = collectReports(s)
        remote!!.onDownload = { s.lock(LockReason.User) }
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()

        // Not Updated: nothing is shown, the newer version waits for the next unlock.
        assertEquals(listOf(SyncTrigger.AFTER_UNLOCK to SyncResult.CandidateReady), reports.pairs())
        assertIs<SessionState.Locked>(s.state.value)
        assertEquals(3, opener.secrets.size, "seed, unlock and refresh opened a vault each")
        assertTrue(opener.secrets.all { it.closed }, "every vault closed")
        assertTrue(opener.hashes.drop(1).all { it.isWiped() }, "session hash and refresh copy wiped")

        // The newer file replaced the offline copy anyway (it opened).
        remote!!.onDownload = null
        s.unlockWithPassword("pw".toCharArray())
        assertEquals("v2", assertIs<SessionState.Unlocked>(s.state.value).vault.rootGroupName)
    }

    @Test
    fun `sync now while locked downloads a candidate for the next unlock`() = runTest {
        remote = FakeRemote(kdbx("pw", "v1"), t0)
        val s = session()
        assertEquals(SyncResult.CandidateReady, s.syncNow())
        assertTrue(s.sync.value.status.hasLocalDatabase)
        val outcome = assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("pw".toCharArray()))
        assertTrue(outcome.newVersionOpened)
    }

    private fun List<SyncReport>.pairs() = map { it.trigger to it.result }

    /** Collects [Session.syncReports] from now on; the collector starts eagerly. */
    private fun TestScope.collectReports(s: Session): List<SyncReport> {
        val reports = mutableListOf<SyncReport>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.syncReports.toList(reports) }
        return reports
    }

    @Test
    fun `the refresh after unlock is reported with its result`() = runTest {
        seedOffline("pw", "v1")
        remote!!.publish(kdbx("pw", "v2"), t0)
        val s = session()
        val reports = collectReports(s)
        s.unlockWithPassword("pw".toCharArray())
        assertTrue(reports.isEmpty(), "the refresh runs in the background")
        runCurrent()
        assertEquals(listOf(SyncTrigger.AFTER_UNLOCK to SyncResult.Updated), reports.pairs())
        assertEquals(reports.last(), s.lastSyncReport.value)
    }

    @Test
    fun `the refresh after unlock reports failure, up to date and rejection`() = runTest {
        seedOffline("pw", "v1")
        val s = session()
        val reports = collectReports(s)

        remote!!.statError = RemoteException(RemoteError.UNREACHABLE)
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        s.lock(LockReason.User)

        remote!!.statError = null
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        s.lock(LockReason.User)

        remote!!.publish(kdbx("new", "v2"), t0)
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()

        assertEquals(
            listOf(
                SyncTrigger.AFTER_UNLOCK to SyncResult.Failed(SyncProblem.UNREACHABLE),
                SyncTrigger.AFTER_UNLOCK to SyncResult.UpToDate,
                SyncTrigger.AFTER_UNLOCK to SyncResult.NewVersionRejected,
            ),
            reports.pairs(),
        )
    }

    @Test
    fun `manual, scheduled and setup runs are reported with their trigger`() = runTest {
        remote = FakeRemote(kdbx("pw", "v1"), t0)
        val s = session()
        val reports = collectReports(s)

        assertEquals(SyncResult.CandidateReady, s.syncNow())
        assertEquals(SyncResult.CandidateReady, s.syncWith(remote!!))
        assertEquals(SyncResult.CandidateReady, s.syncNow(SyncTrigger.SCHEDULED))

        assertEquals(
            listOf(
                SyncTrigger.REQUESTED to SyncResult.CandidateReady,
                SyncTrigger.SETUP to SyncResult.CandidateReady,
                SyncTrigger.SCHEDULED to SyncResult.CandidateReady,
            ),
            reports.pairs(),
        )
        // Equal results of different runs differ by id; the state holds the newest.
        assertEquals(listOf(1L, 2L, 3L), reports.map { it.id })
        assertEquals(reports.last(), s.lastSyncReport.value)
    }

    @Test
    fun `a report that ended before anyone collected stays in the state`() = runTest {
        remote = FakeRemote(kdbx("pw", "v1"), t0)
        val s = session()
        assertNull(s.lastSyncReport.value)
        s.syncNow()
        val late = collectReports(s)
        runCurrent()
        assertTrue(late.isEmpty(), "the event flow does not replay")
        assertEquals(SyncReport(1L, SyncTrigger.REQUESTED, SyncResult.CandidateReady), s.lastSyncReport.value)
    }

    @Test
    fun `effects hear about the lock while the session is still unlocked`() = runTest {
        seedOffline("pw", "v1")
        lateinit var s: Session
        var stateAtLocking: SessionState? = null
        val watching = object : SessionEffects {
            override fun onUnlocked() = Unit

            override fun onLocking() {
                stateAtLocking = s.state.value
            }

            override fun onLocked(reason: LockReason) = Unit
        }
        s = session(effects = watching)
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        s.lock(LockReason.User)
        assertIs<SessionState.Unlocked>(stateAtLocking)
        assertIs<SessionState.Locked>(s.state.value)
    }

    @Test
    fun `an unlock that fails after opening the vault closes it and leaves no Unlocking state`() = runTest {
        seedOffline("pw", "v1")
        val failing = object : SettingsRepository {
            private val inner = MemorySettingsRepository()
            override val settings = inner.settings
            var fail = true

            override fun update(transform: (KpmSettings) -> KpmSettings): KpmSettings {
                if (fail) throw IllegalStateException("disk full")
                return inner.update(transform)
            }
        }
        // Like the platform's scope: a failing unlock reaches the caller only, not the test's error handler.
        val s = session(failing, scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob()))
        val password = "pw".toCharArray()
        assertFailsWith<IllegalStateException> { s.unlockWithPassword(password) }

        assertTrue(password.all { it == '\u0000' })
        assertIs<SessionState.Locked>(s.state.value)
        assertTrue(opener.secrets.last().closed, "the opened vault is closed")
        assertTrue(opener.hashes.last().isWiped(), "the hash is wiped")
        assertEquals(0, effects.unlocked)

        // The session is usable again.
        failing.fail = false
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("pw".toCharArray()))
    }

    @Test
    fun `a failing indexer at the refresh is no Updated and keeps the old vault`() = runTest {
        seedOffline("pw", "v1")
        remote!!.publish(kdbx("pw", "v2"), t0)
        var calls = 0
        val s = session(indexer = { vault -> if (calls++ == 0) EntryIndex(vault) else throw IllegalStateException("index") })
        val reports = collectReports(s)
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()

        assertEquals(listOf(SyncTrigger.AFTER_UNLOCK to SyncResult.Failed(SyncProblem.INVALID_FILE)), reports.pairs())
        val unlocked = assertIs<SessionState.Unlocked>(s.state.value)
        assertEquals("v1", unlocked.vault.rootGroupName)
        assertEquals(0, unlocked.version)
        assertTrue(opener.secrets[2].closed, "the vault that could not be shown is closed")
        assertFalse(opener.secrets[1].closed)
        // The old offline copy was not replaced, and the problem is visible.
        assertEquals(SyncProblem.INVALID_FILE, s.sync.value.status.lastProblem)
        assertEquals(kdbx("pw", "v1"), File(dir, "sync/offline.kdbx").readText())
    }

    @Test
    fun `source change while unlocked with another password locks the session`() = runTest {
        seedOffline("old", "v1")
        val s = session()
        s.unlockWithPassword("old".toCharArray())
        runCurrent()
        remote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))

        assertEquals(SourceSwitchResult.Saved(SyncResult.NewVersionRejected), s.switchSource(StubSwitch(remote!!, events)))

        assertEquals(LockReason.SourceChanged, assertIs<SessionState.Locked>(s.state.value).reason)
        assertTrue(opener.secrets[1].closed, "the old vault is closed")
        assertTrue(File(dir, "sync/offline-previous.kdbx").exists())
        assertFalse(File(dir, "sync/offline.kdbx").exists())
        // The old password no longer opens anything, the new one opens the new file.
        assertEquals(UnlockOutcome.WrongPassword, s.unlockWithPassword("old".toCharArray()))
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("new".toCharArray()))
        assertEquals("other", assertIs<SessionState.Unlocked>(s.state.value).vault.rootGroupName)
        runCurrent()
        assertFalse(File(dir, "sync/offline-previous.kdbx").exists())
    }


    @Test
    fun `source change keeps the old copy but does not open it as the new database`() = runTest {
        seedOffline("old", "v1")
        val s = session()
        // The new source: another file with another master password, downloaded as candidate (locked).
        remote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))
        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady), s.switchSource(StubSwitch(remote!!, events)))
        assertEquals(listOf("stage", "commit", "finish"), events)
        assertTrue(s.sync.value.status.hasLocalDatabase, "the candidate of the new source")

        // Fingerprint with the old hash: rejected, the stored value is deleted, the old file is not shown.
        hashes.stored = "old".toByteArray()
        assertEquals(
            UnlockOutcome.PasswordRequired(PasswordRequiredReason.STORED_VALUE_REJECTED),
            s.unlockWithStoredHash("old".toByteArray()),
        )
        assertNull(hashes.stored)
        assertIs<SessionState.Locked>(s.state.value)
        assertEquals(UnlockOutcome.WrongPassword, s.unlockWithPassword("old".toCharArray()))

        // The new file opens and replaces the old copy.
        val outcome = assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("new".toCharArray()))
        assertTrue(outcome.newVersionOpened)
        assertEquals("other", assertIs<SessionState.Unlocked>(s.state.value).vault.rootGroupName)
        runCurrent()
        s.lock(LockReason.User)
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("new".toCharArray()))
        assertEquals("other", assertIs<SessionState.Unlocked>(s.state.value).vault.rootGroupName)
    }

    @Test
    fun `locked while the new file replaced the offline copy, nothing is set aside`() = runTest {
        seedOffline("pw", "v1")
        val s = session()
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        val newRemote = FakeRemote(kdbx("pw", "other"), t0.minus(Duration.ofDays(2)))
        // The session is locked during the download: the engine opens and stores the new file anyway.
        newRemote.onDownload = { s.lock(LockReason.User) }

        val result = s.switchSource(StubSwitch(newRemote, events))

        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady), result)
        assertEquals(kdbx("pw", "other"), File(dir, "sync/offline.kdbx").readText(), "the new file is the offline copy")
        assertFalse(File(dir, "sync/offline-previous.kdbx").exists())
        assertEquals(LockReason.User, assertIs<SessionState.Locked>(s.state.value).reason)
        // No database was lost: the next unlock opens the new file.
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("pw".toCharArray()))
        assertEquals("other", assertIs<SessionState.Unlocked>(s.state.value).vault.rootGroupName)
    }

    @Test
    fun `a failed switch restores everything and deletes the foreign candidate`() = runTest {
        seedOffline("old", "v1")
        val s = session()
        val newRemote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))
        val stub = StubSwitch(newRemote, events).apply { failCommit = true }

        assertEquals(SourceSwitchResult.Failed(SyncProblem.LOCAL_STORAGE), s.switchSource(stub))

        assertEquals(listOf("stage", "rollback", "finish"), events)
        assertEquals(kdbx("old", "v1"), File(dir, "sync/offline.kdbx").readText(), "the old copy is back")
        assertFalse(File(dir, "sync/offline-previous.kdbx").exists())
        assertFalse(File(dir, "sync/candidate.kdbx").exists(), "the foreign file is gone")
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("old".toCharArray()))
    }

    @Test
    fun `a failing stage changes nothing`() = runTest {
        seedOffline("old", "v1")
        val s = session()
        val stub = StubSwitch(FakeRemote(kdbx("new", "other"), t0), events).apply { failStage = true }
        assertEquals(SourceSwitchResult.Failed(SyncProblem.LOCAL_STORAGE), s.switchSource(stub))
        // Stage may have replaced something before it threw: rollback runs; the old copy is back, the foreign file gone.
        assertEquals(listOf("rollback", "finish"), events)
        assertFalse(File(dir, "sync/candidate.kdbx").exists())
        assertFalse(File(dir, "sync/offline-previous.kdbx").exists())
        assertEquals(kdbx("old", "v1"), File(dir, "sync/offline.kdbx").readText())
    }

    @Test
    fun `the new password is stored only after the download, right before the commit`() = runTest {
        seedOffline("old", "v1")
        val s = session()
        val newRemote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))
        newRemote.onDownload = { events += "download" }
        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady), s.switchSource(StubSwitch(newRemote, events)))
        assertEquals(listOf("download", "stage", "commit", "finish"), events)
    }

    @Test
    fun `the journal follows the steps and is empty afterwards, the report comes only on success`() = runTest {
        seedOffline("old", "v1")
        val journal = MemoryJournal()
        val s = session(journal = journal)
        val reports = collectReports(s)
        val newRemote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))

        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady), s.switchSource(StubSwitch(newRemote, events)))

        assertNull(journal.record)
        assertTrue(journal.writes.first()!!.let { !it.setAside && !it.passwordTouched })
        assertTrue(journal.writes.any { it != null && it.setAside && it.passwordTouched })
        assertEquals(listOf(SyncTrigger.SETUP to SyncResult.CandidateReady), reports.pairs())

        // A failed change reports nothing.
        val failing = StubSwitch(FakeRemote(kdbx("third", "x"), t0.minus(Duration.ofDays(3))), events).apply { failCommit = true }
        assertEquals(SourceSwitchResult.Failed(SyncProblem.LOCAL_STORAGE), s.switchSource(failing))
        assertEquals(1, reports.size)
        assertNull(journal.record)
    }

    @Test
    fun `unlocked with the same password a failing commit locks and deletes the offline copy that the new file replaced`() = runTest {
        seedOffline("pw", "v1")
        val s = session()
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        val reports = collectReports(s)
        val newRemote = FakeRemote(kdbx("pw", "other"), t0.minus(Duration.ofDays(2)))
        val stub = StubSwitch(newRemote, events).apply { failCommit = true }

        assertEquals(SourceSwitchResult.Failed(SyncProblem.LOCAL_STORAGE), s.switchSource(stub))

        // The old source must never show the new file: locked, and the replaced copy is gone.
        assertEquals(LockReason.SourceChanged, assertIs<SessionState.Locked>(s.state.value).reason)
        assertFalse(s.sync.value.status.hasLocalDatabase)
        assertTrue(reports.isEmpty())
        assertEquals(UnlockOutcome.NoDatabase, s.unlockWithPassword("pw".toCharArray()))
        assertEquals(listOf("stage", "rollback", "finish"), events.filter { it != "open" })
    }

    @Test
    fun `an unlock cannot start while a source change runs`() = runTest {
        seedOffline("old", "v1")
        val s = session()
        var outcome: UnlockOutcome? = null
        val newRemote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))
        newRemote.onDownload = { backgroundScope.launch { outcome = s.unlockWithPassword("old".toCharArray()) } }

        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady), s.switchSource(StubSwitch(newRemote, events)))
        runCurrent()

        assertEquals(UnlockOutcome.Busy, outcome)
        // Afterwards unlocking works again (with the new file's password).
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("new".toCharArray()))
    }

    // ---- restart in the middle of a source change ----

    private val oldSource = SourceConfig("nas", 445, "", "kpm", "daten", listOf("old.kdbx"))
    private val newSource = SourceConfig("nas", 445, "", "kpm", "daten", listOf("new.kdbx"))

    /** The old process died after the new file waits as candidate and the old copy was set aside. */
    private fun crashedAfterSetAside(): SwitchRecord {
        seedOffline("old", "v1")
        val r = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(r))
        assertTrue(engine.markSourceChanged())
        // A new process: new engine on the same files.
        engine = SyncEngine(File(dir, "sync"), opener, clock)
        return SwitchRecord(newSource, differs = true, setAside = true, offlineReplaced = false, passwordTouched = false)
    }

    @Test
    fun `restart after a crash before the commit puts the old copy back and drops the foreign file`() = runTest {
        val journal = MemoryJournal(crashedAfterSetAside())
        var cleared = 0
        val s = session(MemorySettingsRepository(KpmSettings(source = oldSource)), journal = journal, clearPassword = { cleared++ })

        // Until the recovery is done, no unlock.
        assertEquals(UnlockOutcome.Busy, s.unlockWithPassword("old".toCharArray()))
        s.recoverInterruptedSwitch()

        assertNull(journal.record)
        assertEquals(kdbx("old", "v1"), File(dir, "sync/offline.kdbx").readText())
        assertFalse(File(dir, "sync/offline-previous.kdbx").exists())
        assertFalse(File(dir, "sync/candidate.kdbx").exists())
        assertEquals(0, cleared, "the password was not touched yet")
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("old".toCharArray()))
    }

    @Test
    fun `restart after the password may have been replaced clears it so the user is asked`() = runTest {
        val journal = MemoryJournal(crashedAfterSetAside().copy(passwordTouched = true))
        var cleared = 0
        val s = session(MemorySettingsRepository(KpmSettings(source = oldSource)), journal = journal, clearPassword = { cleared++ })
        s.recoverInterruptedSwitch()
        assertEquals(1, cleared)
        assertNull(journal.record)
        assertEquals(kdbx("old", "v1"), File(dir, "sync/offline.kdbx").readText())
    }

    @Test
    fun `restart after the source was saved leaves the finished change alone`() = runTest {
        val journal = MemoryJournal(crashedAfterSetAside().copy(passwordTouched = true))
        var cleared = 0
        val s = session(MemorySettingsRepository(KpmSettings(source = newSource)), journal = journal, clearPassword = { cleared++ })
        s.recoverInterruptedSwitch()
        assertEquals(0, cleared)
        assertNull(journal.record)
        assertTrue(File(dir, "sync/candidate.kdbx").exists())
        assertFalse(File(dir, "sync/offline.kdbx").exists())
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("new".toCharArray()))
    }

    @Test
    fun `restart after the new file replaced the offline copy deletes it`() = runTest {
        seedOffline("pw", "v1")
        val journal = MemoryJournal(SwitchRecord(newSource, differs = true, setAside = false, offlineReplaced = true, passwordTouched = false))
        val s = session(MemorySettingsRepository(KpmSettings(source = oldSource)), journal = journal)
        s.recoverInterruptedSwitch()
        assertFalse(s.sync.value.status.hasLocalDatabase)
        assertNull(journal.record)
    }

    @Test
    fun `a switch without an interrupted record needs no recovery`() = runTest {
        seedOffline("pw", "v1")
        val s = session(journal = MemoryJournal())
        s.recoverInterruptedSwitch()
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("pw".toCharArray()))
    }

    // ---- the stored fingerprint value and a source change to another file ----

    @Test
    fun `a switch to another file deletes the stored value, the unlock offers only the password and stores it again`() = runTest {
        seedOffline("old", "v1")
        hashes.stored = "old".toByteArray()
        // An earlier expiry notice must not be shown for the new file either.
        val settings = MemorySettingsRepository(
            KpmSettings(fingerprintEnabled = true, lastPasswordEntry = t0, intervalAtEntry = PasswordInterval.SEVEN_DAYS, fingerprintExpired = true),
        )
        val s = session(settings)
        remote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))

        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady), s.switchSource(StubSwitch(remote!!, events)))

        assertNull(hashes.stored)
        assertEquals(1, hashes.clears)
        assertFalse(settings.settings.value.fingerprintExpired)
        // Not "Frist abgelaufen" and no rejected fingerprint attempt: plain password mode.
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.NOT_SET_UP), s.unlockOffer())
        assertFalse(s.isFingerprintUsable())
        assertTrue(settings.settings.value.fingerprintEnabled, "the switch itself stays on")

        // The typed password of the new file stores the fingerprint again.
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("new".toCharArray()))
        assertContentEquals("new".toByteArray(), hashes.stored)
        s.lock(LockReason.User)
        assertEquals(UnlockOffer.Fingerprint, s.unlockOffer())
    }

    @Test
    fun `the stored value is gone before the session says Locked`() = runTest {
        seedOffline("old", "v1")
        hashes.stored = "old".toByteArray()
        var storedWhenLocking: ByteArray? = ByteArray(1)
        var storedWhenLocked: ByteArray? = ByteArray(1)
        val recording = object : SessionEffects {
            override fun onUnlocked() = Unit

            override fun onLocking() {
                storedWhenLocking = hashes.stored
            }

            override fun onLocked(reason: LockReason) {
                storedWhenLocked = hashes.stored
            }
        }
        val s = session(fingerprintSettings(), effects = recording)
        s.unlockWithPassword("old".toCharArray())
        runCurrent()
        hashes.stored = "old".toByteArray()
        remote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))

        s.switchSource(StubSwitch(remote!!, events))

        // The unlock screen that follows the lock asks unlockOffer(): it must already see no stored value.
        assertNull(storedWhenLocking)
        assertNull(storedWhenLocked)
        assertEquals(LockReason.SourceChanged, assertIs<SessionState.Locked>(s.state.value).reason)
    }

    @Test
    fun `a switch that keeps the file or fails keeps the stored value`() = runTest {
        seedOffline("old", "v1")
        hashes.stored = "old".toByteArray()
        val s = session(fingerprintSettings())

        // Same file (e.g. only the credentials changed): the master password is the same.
        val same = FakeRemote(kdbx("old", "v1"), t0.minus(Duration.ofHours(1)))
        assertIs<SourceSwitchResult.Saved>(s.switchSource(StubSwitch(same, events, differs = false)))
        assertContentEquals("old".toByteArray(), hashes.stored)

        // A failed change to another file: nothing changed, the fingerprint still fits.
        val other = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))
        assertIs<SourceSwitchResult.Failed>(s.switchSource(StubSwitch(other, events).apply { failCommit = true }))
        assertContentEquals("old".toByteArray(), hashes.stored)
        assertEquals(0, hashes.clears)
        assertEquals(UnlockOffer.Fingerprint, s.unlockOffer())
    }

    @Test
    fun `a failing deletion keeps the journal so that the restart repeats it`() = runTest {
        seedOffline("old", "v1")
        hashes.stored = "old".toByteArray()
        val journal = MemoryJournal()
        val settings = MemorySettingsRepository(KpmSettings(source = newSource, fingerprintEnabled = true))
        val s = session(settings, journal = journal)
        hashes.onClear = { throw IllegalStateException("keystore") }
        remote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))

        // The change itself is saved; only the cleanup is open.
        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady), s.switchSource(StubSwitch(remote!!, events)))
        val record = journal.record
        assertTrue(record != null && record.differs, "the record stays")

        // Next start: the source is saved, so the change is kept and only the old value is deleted.
        hashes.onClear = null
        val restarted = session(settings, journal = MemoryJournal(record))
        restarted.recoverInterruptedSwitch()
        assertNull(hashes.stored)
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.NOT_SET_UP), restarted.unlockOffer())
    }

    @Test
    fun `restart after the source was saved but before the stored value was deleted deletes it`() = runTest {
        val record = crashedAfterSetAside().copy(passwordTouched = true)
        hashes.stored = "old".toByteArray()
        val journal = MemoryJournal(record)
        val settings = MemorySettingsRepository(KpmSettings(source = newSource, fingerprintEnabled = true, fingerprintExpired = true))
        var cleared = 0
        val s = session(settings, journal = journal, clearPassword = { cleared++ })

        s.recoverInterruptedSwitch()

        assertNull(hashes.stored)
        assertFalse(settings.settings.value.fingerprintExpired)
        assertEquals(0, cleared, "the SMB password belongs to the saved source")
        assertNull(journal.record)
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.NOT_SET_UP), s.unlockOffer())
        // Running it again changes nothing (idempotent).
        s.recoverInterruptedSwitch()
        assertNull(hashes.stored)
    }

    @Test
    fun `restart after a saved change of the credentials only keeps the stored value`() = runTest {
        val record = crashedAfterSetAside().copy(differs = false, setAside = false)
        hashes.stored = "old".toByteArray()
        val s = session(MemorySettingsRepository(KpmSettings(source = newSource, fingerprintEnabled = true)), journal = MemoryJournal(record))
        s.recoverInterruptedSwitch()
        assertContentEquals("old".toByteArray(), hashes.stored)
    }

    @Test
    fun `restart before the commit keeps the stored value of the old file`() = runTest {
        val record = crashedAfterSetAside()
        hashes.stored = "old".toByteArray()
        val s = session(MemorySettingsRepository(KpmSettings(source = oldSource, fingerprintEnabled = true)), journal = MemoryJournal(record))
        s.recoverInterruptedSwitch()
        assertContentEquals("old".toByteArray(), hashes.stored)
        assertEquals(kdbx("old", "v1"), File(dir, "sync/offline.kdbx").readText())
    }

    @Test
    fun `a typed password during the cleanup keeps the notice from outliving it`() = runTest {
        hashes.stored = ByteArray(32)
        val settings = fingerprintSettings(entry = t0.minus(Duration.ofDays(8)))
        val s = session(settings)
        // A typed password renews the entry while the cleanup deletes the old value.
        hashes.onClear = { settings.update { it.copy(lastPasswordEntry = t0, fingerprintExpired = false) } }
        assertTrue(s.expireFingerprint())
        assertFalse(settings.settings.value.fingerprintExpired)
    }

    @Test
    fun `no other sync reads the old source while the source is switched`() = runTest {
        seedOffline("pw", "v1")
        val s = session()
        val newRemote = FakeRemote(kdbx("pw2", "other"), t0.minus(Duration.ofDays(2)))
        // A scheduled sync wants to start in the middle of the switch.
        newRemote.onDownload = { backgroundScope.launch { s.syncNow(SyncTrigger.SCHEDULED) } }
        val stub = StubSwitch(newRemote, events).apply { onCommit = { this@SessionTest.remote = newRemote } }

        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady), s.switchSource(stub))
        runCurrent()

        // The other sync ran after the commit, so it used the new source and kept the new candidate.
        assertEquals(listOf("stage", "commit", "finish", "open"), events)
        assertEquals(CandidateState.PENDING, s.sync.value.status.candidate)
        assertTrue(File(dir, "sync/candidate.kdbx").exists())
    }

    @Test
    fun `lock proceeds even if the effects throw`() = runTest {
        seedOffline("pw", "v1")
        val throwing = object : SessionEffects {
            override fun onUnlocked() = Unit

            override fun onLocking() = throw IllegalStateException("clipboard")

            override fun onLocked(reason: LockReason) = throw IllegalStateException("service")
        }
        val s = session(effects = throwing)
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        s.lock(LockReason.User)
        assertEquals(LockReason.User, assertIs<SessionState.Locked>(s.state.value).reason)
        assertTrue(opener.secrets.last().closed)
        assertTrue(opener.hashes.last().isWiped())
    }

    @Test
    fun `locked while the unlock waits for the fingerprint store the outcome is Aborted`() = runTest {
        seedOffline("pw", "v1")
        lateinit var s: Session
        hashes.onStore = { s.lock(LockReason.User) }
        s = session(MemorySettingsRepository(KpmSettings(fingerprintEnabled = true)))
        val reports = collectReports(s)

        assertEquals(UnlockOutcome.Aborted, s.unlockWithPassword("pw".toCharArray()))

        assertEquals(LockReason.User, assertIs<SessionState.Locked>(s.state.value).reason)
        runCurrent()
        assertTrue(opener.secrets.all { it.closed })
        // The refresh that was already started ran locked: no vault was shown.
        assertTrue(reports.all { it.result != SyncResult.Updated })
    }

    @Test
    fun `source change while unlocked with the same password replaces the old copy at once`() = runTest {
        seedOffline("pw", "v1")
        val s = session()
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        remote = FakeRemote(kdbx("pw", "other"), t0.minus(Duration.ofDays(2)))
        assertEquals(SyncResult.Updated, s.syncWith(remote!!))
        assertEquals("other", assertIs<SessionState.Unlocked>(s.state.value).vault.rootGroupName)
        s.lock(LockReason.User)
        s.unlockWithPassword("pw".toCharArray())
        assertEquals("other", assertIs<SessionState.Unlocked>(s.state.value).vault.rootGroupName)
    }

    @Test
    fun `sync failures and missing source are reported`() = runTest {
        val s = session()
        assertEquals(SyncResult.NotConfigured, s.syncNow())
        remote = FakeRemote(kdbx("pw", "v1"), t0).apply { statError = RemoteException(RemoteError.UNREACHABLE) }
        assertEquals(SyncResult.Failed(SyncProblem.UNREACHABLE), s.syncNow())
        assertEquals(SyncProblem.UNREACHABLE, s.sync.value.status.lastProblem)
    }

    @Test
    fun `pending server change is exposed and can be trusted`() = runTest {
        val reitano = UUID.fromString("74696572-6e61-2d6f-6e61-730000000000")
        val remembered = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val settings = MemorySettingsRepository(
            KpmSettings(
                source = SourceConfig("192.168.178.20", 445, "", "kpm", "daten", listOf("KeePass", "db.kdbx")),
                trustedServer = TrustedServer(remembered, t0.minus(Duration.ofDays(30))),
                pendingServerGuid = reitano,
            ),
        )
        val s = session(settings)
        val change = s.sync.value.serverChange!!
        assertEquals("192.168.178.20", change.host)
        assertEquals(remembered, change.rememberedGuid)
        assertNull(change.rememberedName)
        assertEquals(reitano, change.newGuid)
        assertEquals("reitano-nas", change.newName)

        assertEquals(SyncResult.NotConfigured, s.trustPendingServer())
        assertEquals(TrustedServer(reitano, t0), settings.settings.value.trustedServer)
        assertNull(settings.settings.value.pendingServerGuid)
        assertNull(s.sync.value.serverChange)
    }
}

/** A [SourceSwitch] that only records what the session asked for. */
private class StubSwitch(
    override val remote: de.lulurobb.keypassmobile.core.sync.RemoteSource,
    private val events: MutableList<String>,
    override val differs: Boolean = true,
) : SourceSwitch {
    override val target = SourceConfig("nas", 445, "", "kpm", "daten", listOf("new.kdbx"))

    var failCommit = false
    var failStage = false
    var onCommit: (() -> Unit)? = null

    override fun stage() {
        if (failStage) throw IllegalStateException("keystore")
        events += "stage"
    }

    override fun commit() {
        if (failCommit) throw IllegalStateException("settings")
        onCommit?.invoke()
        events += "commit"
    }

    override fun rollback() {
        events += "rollback"
    }

    override fun finish() {
        events += "finish"
    }
}
