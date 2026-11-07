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
import de.lulurobb.keypassmobile.core.sync.RemoteStat
import de.lulurobb.keypassmobile.core.sync.SyncEngine
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.core.sync.UnlockResult
import de.lulurobb.keypassmobile.model.Vault
import de.lulurobb.keypassmobile.platform.settings.FileSource
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.MemorySettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.platform.settings.SmbSource
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
import kotlin.test.assertSame
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
        cleanup: SourceCleanup = SourceCleanup {},
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
        sourceCleanup = cleanup,
    )

    /** Offline copy that opens with [key]; the remote serves the same version. */
    private fun seedOffline(key: String, label: String, db: String? = null, modified: Instant = t0.minus(Duration.ofHours(1))) {
        val r = FakeRemote(kdbx(key, label, db), modified)
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
    fun `a later sync that finds the offline copy current confirms the opened version`() = runTest {
        seedOffline("pw", "v1")
        remote!!.statError = RemoteException(RemoteError.UNREACHABLE)
        val s = session()
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        assertEquals(OpenedVersion.OFFLINE_COPY, s.sync.value.status.openedVersion)
        assertEquals(SyncProblem.UNREACHABLE, s.sync.value.status.lastProblem)

        remote!!.statError = null
        clock.advance(Duration.ofMinutes(5))
        assertEquals(SyncResult.UpToDate, s.syncNow())

        val status = s.sync.value.status
        assertEquals(OpenedVersion.SHARE, status.openedVersion)
        assertNull(status.lastProblem)
        assertEquals(clock.now, status.lastSuccessfulSync)
        // The scheduled run while unlocked confirms it as well.
        assertEquals(SyncResult.UpToDate, s.syncNow(SyncTrigger.SCHEDULED))
        assertEquals(OpenedVersion.SHARE, s.sync.value.status.openedVersion)
    }

    @Test
    fun `a sync after a rejected new version does not confirm the opened version`() = runTest {
        seedOffline("pw", "v1")
        remote!!.publish(kdbx("new", "v2"), t0)
        val s = session()
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        assertTrue(assertIs<SessionState.Unlocked>(s.state.value).newVersionRejected)

        assertEquals(SyncResult.NewVersionRejected, s.syncNow())

        assertEquals(OpenedVersion.OFFLINE_COPY, s.sync.value.status.openedVersion)
        assertEquals(SyncProblem.NEW_VERSION_REJECTED, s.sync.value.status.lastProblem)
    }

    @Test
    fun `a sync while locked confirms nothing`() = runTest {
        seedOffline("pw", "v1")
        remote!!.statError = RemoteException(RemoteError.UNREACHABLE)
        val s = session()
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        s.lock(LockReason.User)
        remote!!.statError = null

        assertEquals(SyncResult.UpToDate, s.syncNow())

        assertEquals(OpenedVersion.OFFLINE_COPY, s.sync.value.status.openedVersion)
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

        assertEquals(SourceSwitchResult.Saved(SyncResult.NewVersionRejected, locked = true), s.switchSource(StubSwitch(remote!!, events)))

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
        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady, locked = false), s.switchSource(StubSwitch(remote!!, events)))
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

        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady, locked = false), result)
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
        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady, locked = false), s.switchSource(StubSwitch(newRemote, events)))
        assertEquals(listOf("download", "stage", "commit", "finish"), events)
    }

    @Test
    fun `the journal follows the steps and is empty afterwards, the report comes only on success`() = runTest {
        seedOffline("old", "v1")
        val journal = MemoryJournal()
        val s = session(journal = journal)
        val reports = collectReports(s)
        val newRemote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))

        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady, locked = false), s.switchSource(StubSwitch(newRemote, events)))

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

        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady, locked = false), s.switchSource(StubSwitch(newRemote, events)))
        runCurrent()

        assertEquals(UnlockOutcome.Busy, outcome)
        // Afterwards unlocking works again (with the new file's password).
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("new".toCharArray()))
    }

    // ---- restart in the middle of a source change ----

    private val oldSource = SmbSource("nas", 445, "", "kpm", "daten", listOf("old.kdbx"))
    private val newSource = SmbSource("nas", 445, "", "kpm", "daten", listOf("new.kdbx"))

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

        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady, locked = false), s.switchSource(StubSwitch(remote!!, events)))

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
        var storedWhenLocked: ByteArray? = ByteArray(1)
        val recording = object : SessionEffects {
            override fun onUnlocked() = Unit

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
        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady, locked = false), s.switchSource(StubSwitch(remote!!, events)))
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

        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady, locked = false), s.switchSource(stub))
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
                source = SmbSource("192.168.178.20", 445, "", "kpm", "daten", listOf("KeePass", "db.kdbx")),
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

    // ---- the lock rule for all four paths between SMB and file (DS-13) ----

    private val smbA = SmbSource("nas", 445, "", "kpm", "daten", listOf("old.kdbx"))
    private val smbB = SmbSource("nas", 445, "", "kpm", "daten", listOf("new.kdbx"))
    private val fileA = FileSource("content://docs/document/primary%3AA%2Fold.kdbx", "old.kdbx", "Interner Speicher / A")
    private val fileB = FileSource("content://docs/document/primary%3AB%2Fnew.kdbx", "new.kdbx", "Interner Speicher / B")

    /** The four paths: SMB to SMB, SMB to file, file to SMB, file to file. */
    private val paths: List<Triple<String, SourceConfig, SourceConfig>> = listOf(
        Triple("smb to smb", smbA, smbB),
        Triple("smb to file", smbA, fileB),
        Triple("file to smb", fileA, smbB),
        Triple("file to file", fileA, fileB),
    )

    /** Runs [block] once per path with a fresh sync directory; names the path when it fails. */
    private suspend fun TestScope.eachPath(block: suspend TestScope.(index: Int, from: SourceConfig, to: SourceConfig) -> Unit) {
        paths.forEachIndexed { i, (name, from, to) ->
            engine = SyncEngine(File(dir, "sync-path-$i"), opener, clock)
            remote = null
            events.clear()
            hashes.stored = null
            try {
                block(i, from, to)
            } catch (e: AssertionError) {
                throw AssertionError("$name: ${e.message}", e)
            }
        }
    }

    /** Same rule as the setup classes: a file target differs unless it is the same uri, an SMB target always differs here. */
    private fun differs(from: SourceConfig, to: SourceConfig) = !(to is FileSource && from is FileSource && from.uri == to.uri)

    @Test
    fun `all four paths unlocked with the key that opens the new file replace the vault without a lock`() = runTest {
        eachPath { _, from, to ->
            seedOffline("pw", "v1")
            val settings = MemorySettingsRepository(KpmSettings(source = from))
            val cleanup = RecordingCleanup()
            val s = session(settings, cleanup = cleanup)
            s.unlockWithPassword("pw".toCharArray())
            runCurrent()
            val newRemote = FakeRemote(kdbx("pw", "other"), t0.minus(Duration.ofDays(2)))

            val result = s.switchSource(StubSwitch(newRemote, events, differs(from, to), to))

            assertEquals(SourceSwitchResult.Saved(SyncResult.Updated, locked = false), result)
            assertEquals("other", assertIs<SessionState.Unlocked>(s.state.value).vault.rootGroupName)
            assertEquals(listOf<SourceConfig?>(to), cleanup.calls)
            s.lock(LockReason.User)
        }
    }

    @Test
    fun `all four paths unlocked with a key that does not open the new file lock with SourceChanged`() = runTest {
        eachPath { i, from, to ->
            seedOffline("old", "v1")
            val settings = MemorySettingsRepository(KpmSettings(source = from))
            val cleanup = RecordingCleanup()
            val s = session(settings, cleanup = cleanup)
            s.unlockWithPassword("old".toCharArray())
            runCurrent()
            val newRemote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))

            val result = s.switchSource(StubSwitch(newRemote, events, differs(from, to), to))

            assertEquals(SourceSwitchResult.Saved(SyncResult.NewVersionRejected, locked = true), result)
            assertEquals(LockReason.SourceChanged, assertIs<SessionState.Locked>(s.state.value).reason)
            assertTrue(File(dir, "sync-path-$i/offline-previous.kdbx").exists(), "the old copy is set aside")
            assertEquals(listOf<SourceConfig?>(to), cleanup.calls)
            // The old password no longer opens anything, the one of the new file does.
            assertEquals(UnlockOutcome.WrongPassword, s.unlockWithPassword("old".toCharArray()))
            assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("new".toCharArray()))
            s.lock(LockReason.User)
        }
    }

    @Test
    fun `all four paths while locked stay locked and the new file waits as candidate`() = runTest {
        eachPath { _, from, to ->
            seedOffline("old", "v1")
            val settings = MemorySettingsRepository(KpmSettings(source = from))
            val cleanup = RecordingCleanup()
            val s = session(settings, cleanup = cleanup)
            val newRemote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))

            val result = s.switchSource(StubSwitch(newRemote, events, differs(from, to), to))

            assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady, locked = false), result)
            assertNull(assertIs<SessionState.Locked>(s.state.value).reason, "the switch itself did not lock")
            assertEquals(CandidateState.PENDING, s.sync.value.status.candidate)
            assertEquals(listOf<SourceConfig?>(to), cleanup.calls)
        }
    }

    @Test
    fun `the cleanup runs after the commit and never when the switch fails`() = runTest {
        seedOffline("old", "v1")
        val cleanup = RecordingCleanup(events)
        val s = session(cleanup = cleanup)
        val newRemote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))

        assertIs<SourceSwitchResult.Saved>(s.switchSource(StubSwitch(newRemote, events, target = fileB)))
        assertEquals(listOf("stage", "commit", "reconcile", "finish"), events)

        events.clear()
        cleanup.calls.clear()
        val failing = StubSwitch(FakeRemote(kdbx("third", "x"), t0.minus(Duration.ofDays(3))), events, target = fileA).apply { failCommit = true }
        assertIs<SourceSwitchResult.Failed>(s.switchSource(failing))
        assertTrue(cleanup.calls.isEmpty(), "a failed switch cleans nothing")
        assertEquals(listOf("stage", "rollback", "finish"), events)

        // A download failure never reaches the commit either.
        events.clear()
        val down = FakeRemote(kdbx("x", "y"), t0).apply { statError = RemoteException(RemoteError.UNREACHABLE) }
        assertIs<SourceSwitchResult.Failed>(s.switchSource(StubSwitch(down, events, target = fileA)))
        assertTrue(cleanup.calls.isEmpty())
    }

    @Test
    fun `a failing cleanup keeps the journal and the restart repeats it`() = runTest {
        seedOffline("old", "v1")
        val journal = MemoryJournal()
        val settings = MemorySettingsRepository(KpmSettings(source = fileA))
        val cleanup = RecordingCleanup().apply { failNext = 1 }
        val s = session(settings, journal = journal, cleanup = cleanup)
        val newRemote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))
        val stub = StubSwitch(newRemote, events, target = fileB).apply { onCommit = { settings.update { it.copy(source = fileB) } } }

        // The change itself is saved; only the cleanup is open.
        assertIs<SourceSwitchResult.Saved>(s.switchSource(stub))
        val record = journal.record
        assertEquals(fileB, record?.target, "the record stays")

        val restartedCleanup = RecordingCleanup()
        val restartedJournal = MemoryJournal(record)
        val restarted = session(settings, journal = restartedJournal, cleanup = restartedCleanup)
        restarted.recoverInterruptedSwitch()

        assertEquals(listOf<SourceConfig?>(fileB), restartedCleanup.calls)
        assertNull(restartedJournal.record)
        // The saved change was left alone: the candidate of the new file is still there.
        assertTrue(File(dir, "sync/candidate.kdbx").exists())
    }

    @Test
    fun `recovery of an uncommitted file target undoes the engine steps and reconciles with the old source`() = runTest {
        val journal = MemoryJournal(crashedAfterSetAside().copy(target = fileB))
        val cleanup = RecordingCleanup()
        val s = session(MemorySettingsRepository(KpmSettings(source = smbA)), journal = journal, cleanup = cleanup)

        s.recoverInterruptedSwitch()

        // The grant of the file that was not saved is released by the reconcile with the old source.
        assertEquals(listOf<SourceConfig?>(smbA), cleanup.calls)
        assertNull(journal.record)
        assertEquals(kdbx("old", "v1"), File(dir, "sync/offline.kdbx").readText())
        assertFalse(File(dir, "sync/candidate.kdbx").exists())
    }

    @Test
    fun `recovery of a committed file target finishes the cleanup for the new source`() = runTest {
        val record = crashedAfterSetAside().copy(target = fileB)
        hashes.stored = "old".toByteArray()
        val journal = MemoryJournal(record)
        val cleanup = RecordingCleanup()
        val s = session(MemorySettingsRepository(KpmSettings(source = fileB, fingerprintEnabled = true)), journal = journal, cleanup = cleanup)

        s.recoverInterruptedSwitch()

        assertEquals(listOf<SourceConfig?>(fileB), cleanup.calls)
        assertNull(hashes.stored)
        assertNull(journal.record)
        assertTrue(File(dir, "sync/candidate.kdbx").exists(), "the finished change is kept")
    }

    @Test
    fun `a failing cleanup in the recovery keeps the journal of a finished change only`() = runTest {
        val committed = MemoryJournal(crashedAfterSetAside().copy(target = fileB))
        val s1 = session(MemorySettingsRepository(KpmSettings(source = fileB)), journal = committed, cleanup = RecordingCleanup().apply { failNext = 1 })
        s1.recoverInterruptedSwitch()
        assertEquals(fileB, committed.record?.target, "repeated at the next start")

        val uncommitted = MemoryJournal(SwitchRecord(fileB, differs = true, setAside = false, offlineReplaced = false, passwordTouched = false))
        val s2 = session(MemorySettingsRepository(KpmSettings(source = fileA)), journal = uncommitted, cleanup = RecordingCleanup().apply { failNext = 1 })
        s2.recoverInterruptedSwitch()
        assertNull(uncommitted.record, "an undone change must never be replayed")
    }

    // ---- D10: a change started while unlocked is discarded when the app locks before the commit ----

    @Test
    fun `unlockedMark is the lock generation while unlocked and null otherwise`() = runTest {
        seedOffline("pw", "v1")
        val s = session()
        assertNull(s.unlockedMark())
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        val first = s.unlockedMark()
        assertTrue(first != null)
        s.lock(LockReason.User)
        assertNull(s.unlockedMark())
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        assertTrue(s.unlockedMark() != first, "a new unlock is another generation")
    }

    @Test
    fun `D10 a lock before the switch starts discards it and touches nothing`() = runTest {
        for (target in listOf(smbB, fileB)) {
            events.clear()
            engine = SyncEngine(File(dir, "sync-${target.kind}"), opener, clock)
            seedOffline("pw", "v1")
            val settings = MemorySettingsRepository(KpmSettings(source = smbA))
            val journal = MemoryJournal()
            val cleanup = RecordingCleanup()
            val s = session(settings, journal = journal, cleanup = cleanup)
            s.unlockWithPassword("pw".toCharArray())
            runCurrent()
            val mark = s.unlockedMark()
            s.lock(LockReason.Idle(IdleTimeout.SECONDS_30))
            events.clear()
            val newRemote = FakeRemote(kdbx("pw", "other"), t0.minus(Duration.ofDays(2)))
            var downloads = 0
            newRemote.onDownload = { downloads++ }

            val result = s.switchSource(StubSwitch(newRemote, events, differs = true, target = target, unlockedMark = mark))

            assertEquals(SourceSwitchResult.Discarded, result, "${target.kind}")
            assertEquals(listOf("finish"), events, "neither stage nor commit")
            assertEquals(0, downloads)
            assertTrue(journal.writes.all { it == null }, "nothing was journaled")
            assertEquals(smbA, settings.settings.value.source)
            assertTrue(cleanup.calls.isEmpty())
            assertEquals(kdbx("pw", "v1"), File(dir, "sync-${target.kind}/offline.kdbx").readText())
        }
    }

    @Test
    fun `D10 a lock during the download discards the switch and puts everything back`() = runTest {
        seedOffline("old", "v1")
        val settings = MemorySettingsRepository(KpmSettings(source = smbA))
        val cleanup = RecordingCleanup()
        val s = session(settings, cleanup = cleanup)
        s.unlockWithPassword("old".toCharArray())
        runCurrent()
        val mark = s.unlockedMark()
        // Another database with another password: it ends as a candidate; the lock hits while it downloads.
        val newRemote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))
        newRemote.onDownload = { s.lock(LockReason.User) }
        events.clear()

        val result = s.switchSource(StubSwitch(newRemote, events, differs = true, target = fileB, unlockedMark = mark))

        assertEquals(SourceSwitchResult.Discarded, result)
        assertEquals(listOf("finish"), events)
        assertEquals(smbA, settings.settings.value.source)
        assertTrue(cleanup.calls.isEmpty())
        assertEquals(kdbx("old", "v1"), File(dir, "sync/offline.kdbx").readText(), "the old copy is back")
        assertFalse(File(dir, "sync/offline-previous.kdbx").exists())
        assertFalse(File(dir, "sync/candidate.kdbx").exists(), "the foreign file is gone")
        // The old database still unlocks, no foreign file is waiting.
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("old".toCharArray()))
        assertEquals("v1", assertIs<SessionState.Unlocked>(s.state.value).vault.rootGroupName)
    }

    @Test
    fun `D10 a lock and a new unlock in between still discards the switch`() = runTest {
        seedOffline("pw", "v1")
        val settings = MemorySettingsRepository(KpmSettings(source = smbA))
        val s = session(settings)
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        val mark = s.unlockedMark()
        s.lock(LockReason.User)
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        assertIs<SessionState.Unlocked>(s.state.value)

        val result = s.switchSource(StubSwitch(FakeRemote(kdbx("pw", "other"), t0), events, true, fileB, mark))

        assertEquals(SourceSwitchResult.Discarded, result)
        assertEquals(smbA, settings.settings.value.source)
    }

    @Test
    fun `D10 a change that stays unlocked is applied, and a change started locked is applied`() = runTest {
        seedOffline("pw", "v1")
        val settings = MemorySettingsRepository(KpmSettings(source = smbA))
        val s = session(settings)
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        val mark = s.unlockedMark()
        events.clear()
        val unlockedResult = s.switchSource(
            StubSwitch(FakeRemote(kdbx("pw", "other"), t0.minus(Duration.ofDays(2))), events, true, fileB, mark),
        )
        assertEquals(SourceSwitchResult.Saved(SyncResult.Updated, locked = false), unlockedResult)
        assertEquals(listOf("stage", "commit", "finish"), events)

        // Started locked (no mark): applied although the app is locked, like before.
        events.clear()
        s.lock(LockReason.User)
        val lockedResult = s.switchSource(
            StubSwitch(FakeRemote(kdbx("pw", "third"), t0.minus(Duration.ofDays(3))), events, true, fileA, null),
        )
        assertIs<SourceSwitchResult.Saved>(lockedResult)
        assertEquals(listOf("stage", "commit", "finish"), events)
    }

    // ---- D10: the point of no return is the new file replacing the old offline copy ----

    @Test
    fun `D10 a discard before the replacement leaves the old offline copy and sync state unchanged`() = runTest {
        seedOffline("old", "v1")
        val settings = MemorySettingsRepository(KpmSettings(source = smbA))
        val s = session(settings)
        s.unlockWithPassword("old".toCharArray())
        runCurrent()
        val mark = s.unlockedMark()
        val before = s.sync.value.status
        // Another database with another password becomes a candidate; the lock hits while it downloads.
        val newRemote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))
        newRemote.onDownload = { s.lock(LockReason.User) }

        assertEquals(SourceSwitchResult.Discarded, s.switchSource(StubSwitch(newRemote, events, true, fileB, mark)))

        val after = s.sync.value.status
        assertEquals(kdbx("old", "v1"), File(dir, "sync/offline.kdbx").readText())
        assertTrue(after.hasLocalDatabase)
        assertEquals(before.localModified, after.localModified)
        assertEquals(CandidateState.NONE, after.candidate)
        assertFalse(File(dir, "sync/offline-previous.kdbx").exists())
        assertFalse(File(dir, "sync/candidate.kdbx").exists())
        assertEquals(smbA, settings.settings.value.source)
        assertEquals(before.openedModified, after.openedModified)
        assertEquals(before.lastSuccessfulSync, after.lastSuccessfulSync)
        assertEquals(before.lastProblem, after.lastProblem, "the rejected foreign file is not the old source's problem")
        // The old database opens as before.
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("old".toCharArray()))
        assertEquals("v1", assertIs<SessionState.Unlocked>(s.state.value).vault.rootGroupName)
    }

    @Test
    fun `D10 a discarded re-pick of the same file does not bring back the old access problem`() = runTest {
        seedOffline("pw", "v1")
        // The file was unreadable: the last sync recorded ACCESS_DENIED.
        remote!!.statError = RemoteException(RemoteError.ACCESS_DENIED)
        val settings = MemorySettingsRepository(KpmSettings(source = fileA))
        val s = session(settings)
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        assertEquals(SyncProblem.ACCESS_DENIED, s.sync.value.status.lastProblem)
        val mark = s.unlockedMark()
        // Picked again from the banner: readable now (a newer version of the same key), the lock hits before the last check.
        val again = FakeRemote(kdbx("pw", "v2"), t0)
        again.onDownload = { s.lock(LockReason.User) }

        val result = s.switchSource(StubSwitch(again, events, differs = false, target = fileA, unlockedMark = mark))

        assertEquals(SourceSwitchResult.Discarded, result)
        assertNull(s.sync.value.status.lastProblem, "the file is readable again: the old problem stays gone")
    }

    @Test
    fun `D10 a lock after the replacement completes the change as a locked change`() = runTest {
        seedOffline("pw", "v1")
        val settings = MemorySettingsRepository(KpmSettings(source = smbA))
        val journal = MemoryJournal()
        val cleanup = RecordingCleanup()
        val s = session(settings, journal = journal, cleanup = cleanup)
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()
        val mark = s.unlockedMark()
        // The same master password opens the new file: it replaces the offline copy in the download step.
        // The lock hits during that step; a rollback now would delete the old source's copy.
        val newRemote = FakeRemote(kdbx("pw", "other"), t0.minus(Duration.ofDays(2)))
        newRemote.onDownload = { s.lock(LockReason.User) }
        events.clear()

        val result = s.switchSource(StubSwitch(newRemote, events, true, fileB, mark))

        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady, locked = false), result)
        assertEquals(listOf("stage", "commit", "finish"), events)
        assertEquals(listOf<SourceConfig?>(fileB), cleanup.calls, "the source was committed and cleaned up")
        assertNull(journal.record)
        assertIs<SessionState.Locked>(s.state.value)
        // Consistent: the offline copy is the new file (not reset), nothing is left over.
        assertEquals(kdbx("pw", "other"), File(dir, "sync/offline.kdbx").readText())
        assertFalse(File(dir, "sync/offline-previous.kdbx").exists())
        assertFalse(File(dir, "sync/candidate.kdbx").exists())
        assertTrue(s.sync.value.status.hasLocalDatabase)
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("pw".toCharArray()))
        assertEquals("other", assertIs<SessionState.Unlocked>(s.state.value).vault.rootGroupName)
    }

    @Test
    fun `D10 a lock after the last check completes the switch`() = runTest {
        seedOffline("old", "v1")
        val settings = MemorySettingsRepository(KpmSettings(source = fileA))
        lateinit var s: Session
        val journal = object : SwitchJournal {
            var record: SwitchRecord? = null

            override fun read(): SwitchRecord? = record

            override fun write(record: SwitchRecord?) {
                this.record = record
                // The record of an SMB target with passwordTouched is written after the last check, right before stage().
                if (record?.passwordTouched == true) s.lock(LockReason.User)
            }
        }
        s = session(settings, journal = journal)
        s.unlockWithPassword("old".toCharArray())
        runCurrent()
        val mark = s.unlockedMark()
        val newRemote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))
        events.clear()

        val result = s.switchSource(StubSwitch(newRemote, events, true, smbB, mark))

        assertIs<SourceSwitchResult.Saved>(result)
        assertEquals(listOf("stage", "commit", "finish"), events)
        assertIs<SessionState.Locked>(s.state.value)
        // The old copy is set aside and the new file waits as candidate: the state of every locked change.
        assertTrue(File(dir, "sync/offline-previous.kdbx").exists())
        assertTrue(File(dir, "sync/candidate.kdbx").exists())
        assertNull(journal.record)
    }

    // ---- DS-15 recovery: the SMB password is cleared only when a switch may have touched it ----

    @Test
    fun `a switch to a file never marks the SMB password as touched, a switch to SMB does`() = runTest {
        seedOffline("pw", "v1")
        val toFile = MemoryJournal()
        val s1 = session(MemorySettingsRepository(KpmSettings(source = smbA)), journal = toFile)
        assertIs<SourceSwitchResult.Saved>(s1.switchSource(StubSwitch(FakeRemote(kdbx("pw", "v1"), t0), events, true, fileB)))
        assertTrue(toFile.writes.isNotEmpty())
        assertTrue(toFile.writes.all { it?.passwordTouched != true }, "stage() of a file stores nothing")

        events.clear()
        val toSmb = MemoryJournal()
        val s2 = session(MemorySettingsRepository(KpmSettings(source = fileB)), journal = toSmb)
        assertIs<SourceSwitchResult.Saved>(s2.switchSource(StubSwitch(FakeRemote(kdbx("pw", "v1"), t0), events, true, smbB)))
        assertTrue(toSmb.writes.any { it?.passwordTouched == true }, "stage() of SMB replaces the password")
    }

    @Test
    fun `recovery of an SMB to file switch that died before the commit keeps the SMB password`() = runTest {
        var cleared = 0
        val journal = MemoryJournal(SwitchRecord(fileB, differs = true, setAside = false, offlineReplaced = false, passwordTouched = false))
        val s = session(MemorySettingsRepository(KpmSettings(source = smbA)), journal = journal, clearPassword = { cleared++ })

        s.recoverInterruptedSwitch()

        assertEquals(0, cleared, "the old SMB source is still the saved one and keeps its password")
        assertNull(journal.record)
    }

    @Test
    fun `recovery of a file to SMB switch that died after the password was replaced clears it`() = runTest {
        var cleared = 0
        val journal = MemoryJournal(SwitchRecord(smbB, differs = true, setAside = false, offlineReplaced = false, passwordTouched = true))
        val s = session(MemorySettingsRepository(KpmSettings(source = fileA)), journal = journal, clearPassword = { cleared++ })

        s.recoverInterruptedSwitch()

        assertEquals(1, cleared)
        assertNull(journal.record)
    }

    // ---- a switch with differs = false is a re-pick of the same file ----

    @Test
    fun `a switch to the same file leaves the offline copy, the candidate and the unlock alone`() = runTest {
        seedOffline("pw", "v1")
        val journal = MemoryJournal()
        val s = session(MemorySettingsRepository(KpmSettings(source = fileA)), journal = journal)
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()

        val result = s.switchSource(StubSwitch(remote!!, events, differs = false, target = fileA))

        assertEquals(SourceSwitchResult.Saved(SyncResult.UpToDate, locked = false), result)
        assertIs<SessionState.Unlocked>(s.state.value, "no lock for the same file")
        assertTrue(s.sync.value.status.hasLocalDatabase)
        assertEquals(kdbx("pw", "v1"), File(dir, "sync/offline.kdbx").readText())
        assertFalse(File(dir, "sync/offline-previous.kdbx").exists(), "nothing is set aside")
        assertTrue(journal.writes.filterNotNull().all { !it.differs && !it.setAside })
    }

    @Test
    fun `a switch to another file sets the old copy aside, which is what differs asks for`() = runTest {
        seedOffline("pw", "v1")
        val journal = MemoryJournal()
        val s = session(MemorySettingsRepository(KpmSettings(source = fileA)), journal = journal)
        remote = FakeRemote(kdbx("new", "other"), t0.minus(Duration.ofDays(2)))

        val result = s.switchSource(StubSwitch(remote!!, events, differs = true, target = fileB))

        assertEquals(SourceSwitchResult.Saved(SyncResult.CandidateReady, locked = false), result)
        assertTrue(File(dir, "sync/offline-previous.kdbx").exists())
        assertTrue(journal.writes.filterNotNull().any { it.differs && it.setAside })
    }

    // ---- E: the rejected-version banner ends when the share has the open version again ----

    @Test
    fun `a sync that finds the open version on the share again ends the rejected banner`() = runTest {
        for (source in listOf<SourceConfig>(smbA, fileA)) {
            engine = SyncEngine(File(dir, "sync-e-${source.kind}"), opener, clock)
            seedOffline("pw", "v1")
            val original = remote!!.content
            val originalModified = remote!!.modified
            remote!!.publish(kdbx("new", "v2"), t0)
            val s = session(MemorySettingsRepository(KpmSettings(source = source)))
            s.unlockWithPassword("pw".toCharArray())
            runCurrent()
            assertTrue(assertIs<SessionState.Unlocked>(s.state.value).newVersionRejected, "${source.kind}")
            assertEquals(CandidateState.REJECTED, s.sync.value.status.candidate)

            // The share is set back to the version that is open.
            remote!!.publish(original, originalModified)
            assertEquals(SyncResult.UpToDate, s.syncNow())

            val unlocked = assertIs<SessionState.Unlocked>(s.state.value)
            assertFalse(unlocked.newVersionRejected, "${source.kind}: the banner is gone")
            assertEquals("v1", unlocked.vault.rootGroupName)
            assertEquals(CandidateState.NONE, s.sync.value.status.candidate)
            assertEquals(OpenedVersion.SHARE, s.sync.value.status.openedVersion)
            assertNull(s.sync.value.status.lastProblem)
        }
    }

    @Test
    fun `a sync that still finds the rejected file keeps the banner`() = runTest {
        seedOffline("pw", "v1")
        remote!!.publish(kdbx("new", "v2"), t0)
        val s = session()
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()

        assertEquals(SyncResult.NewVersionRejected, s.syncNow())

        assertTrue(assertIs<SessionState.Unlocked>(s.state.value).newVersionRejected)
    }

    // ---- DS-14: the picked file is an older version of the open database ----

    private val olderBy = Duration.ofHours(4)

    /** The seed has this modification time (one hour before t0), so it is the time of the open version. */
    private val openModified: Instant = t0.minus(Duration.ofHours(1))

    private class CountingRemote(content: String, modified: Instant) {
        val remote = FakeRemote(content, modified)
        var downloads = 0

        init {
            remote.onDownload = { downloads++ }
        }
    }

    /** Unlocked on database "db1" (key "pw") whose file has the time [openModified]. */
    private suspend fun TestScope.unlockedOnDb1(): Session {
        seedOffline("pw", "v1", db = "db1")
        val s = session()
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("pw".toCharArray()))
        runCurrent()
        assertEquals(openModified, engine.status().openedModified)
        return s
    }

    @Test
    fun `an older file of the same database is reported with both times and changes no state`() = runTest {
        val s = unlockedOnDb1()
        val state = s.state.value
        val before = engine.status()
        val picked = CountingRemote(kdbx("pw", "v0", "db1"), openModified.minus(olderBy))

        val check = s.olderVersionCheck(picked.remote)

        assertEquals(OlderCheck(fileModified = openModified.minus(olderBy), openModified = openModified), check)
        assertEquals(1, picked.downloads)
        assertSame(state, s.state.value, "the open vault is untouched")
        assertEquals(before, engine.status(), "no problem recorded, no candidate")
        assertFalse(File(dir, "sync/probe.tmp").exists())
        assertTrue(opener.hashes.last().isWiped(), "the copy of the hash is wiped")
    }

    @Test
    fun `a newer or equally old file is no older version and is not downloaded`() = runTest {
        val s = unlockedOnDb1()
        val newer = CountingRemote(kdbx("pw", "v2", "db1"), openModified.plus(Duration.ofHours(1)))
        assertNull(s.olderVersionCheck(newer.remote))
        assertEquals(0, newer.downloads)
        val same = CountingRemote(kdbx("pw", "v1", "db1"), openModified)
        assertNull(s.olderVersionCheck(same.remote))
        assertEquals(0, same.downloads)
    }

    @Test
    fun `another database or another key gives no dialog so that the lock rule applies`() = runTest {
        val s = unlockedOnDb1()
        val otherDb = CountingRemote(kdbx("pw", "v0", "db2"), openModified.minus(olderBy))
        assertNull(s.olderVersionCheck(otherDb.remote))
        assertEquals(1, otherDb.downloads, "it had to be opened to compare")
        val wrongKey = CountingRemote(kdbx("other", "v0", "db1"), openModified.minus(olderBy))
        assertNull(s.olderVersionCheck(wrongKey.remote))
        val noUuid = CountingRemote(kdbx("pw", "v0"), openModified.minus(olderBy))
        assertNull(s.olderVersionCheck(noUuid.remote), "a file without a root uuid cannot be matched")
        val damaged = CountingRemote("KDBX|broken", openModified.minus(olderBy))
        assertNull(s.olderVersionCheck(damaged.remote))
        assertFalse(File(dir, "sync/probe.tmp").exists())
        assertNull(engine.status().lastProblem, "the probe records no problem")
        // Every copy of the hash that reached the opener is wiped, except the one the session itself holds.
        assertTrue(opener.hashes.drop(2).all { it.isWiped() })
    }

    @Test
    fun `locked or never unlocked there is no check and no download`() = runTest {
        seedOffline("pw", "v1", db = "db1")
        val s = session()
        val picked = CountingRemote(kdbx("pw", "v0", "db1"), openModified.minus(olderBy))
        assertNull(s.olderVersionCheck(picked.remote), "first run or locked")
        assertEquals(0, picked.downloads)
    }

    @Test
    fun `unknown modification times give no check`() = runTest {
        // The open version has no known time.
        seedOffline("pw", "v1", db = "db1", modified = RemoteStat.UNKNOWN_MODIFIED)
        val s = session()
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("pw".toCharArray()))
        runCurrent()
        assertNull(engine.status().openedModified)
        val picked = CountingRemote(kdbx("pw", "v0", "db1"), openModified.minus(olderBy))
        assertNull(s.olderVersionCheck(picked.remote))
        assertEquals(0, picked.downloads)
        s.lock(LockReason.User)

        // The picked file has no known time.
        engine = SyncEngine(File(dir, "sync2"), opener, clock)
        val s2 = unlockedOnDb1()
        val unknown = CountingRemote(kdbx("pw", "v0", "db1"), RemoteStat.UNKNOWN_MODIFIED)
        assertNull(s2.olderVersionCheck(unknown.remote))
        assertEquals(0, unknown.downloads)
    }

    @Test
    fun `a failing stat or download gives no check`() = runTest {
        val s = unlockedOnDb1()
        val noStat = CountingRemote(kdbx("pw", "v0", "db1"), openModified.minus(olderBy))
        noStat.remote.statError = RemoteException(RemoteError.UNREACHABLE)
        assertNull(s.olderVersionCheck(noStat.remote))
        val noDownload = CountingRemote(kdbx("pw", "v0", "db1"), openModified.minus(olderBy))
        noDownload.remote.onDownload = { throw RemoteException(RemoteError.NOT_FOUND) }
        assertNull(s.olderVersionCheck(noDownload.remote))
        assertFalse(File(dir, "sync/probe.tmp").exists())
        assertNull(engine.status().lastProblem)
    }

    @Test
    fun `a lock during the probe gives no check and the hash copy is wiped`() = runTest {
        val s = unlockedOnDb1()
        val picked = CountingRemote(kdbx("pw", "v0", "db1"), openModified.minus(olderBy))
        picked.remote.onDownload = { s.lock(LockReason.User) }

        assertNull(s.olderVersionCheck(picked.remote))

        assertEquals(LockReason.User, assertIs<SessionState.Locked>(s.state.value).reason)
        assertTrue(opener.hashes.last().isWiped())
    }
}
