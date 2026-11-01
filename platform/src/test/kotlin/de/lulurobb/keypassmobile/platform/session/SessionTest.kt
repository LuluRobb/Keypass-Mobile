package de.lulurobb.keypassmobile.platform.session

import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.settings.PasswordInterval
import de.lulurobb.keypassmobile.core.sync.BackgroundSyncResult
import de.lulurobb.keypassmobile.core.sync.CandidateRejection
import de.lulurobb.keypassmobile.core.sync.KeySource
import de.lulurobb.keypassmobile.core.sync.OpenedVersion
import de.lulurobb.keypassmobile.core.sync.RemoteError
import de.lulurobb.keypassmobile.core.sync.RemoteException
import de.lulurobb.keypassmobile.core.sync.SyncEngine
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.core.sync.UnlockResult
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.MemorySettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SettingsRepository
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.platform.settings.TrustedServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
    private lateinit var engine: SyncEngine

    @BeforeEach
    fun setUp() {
        engine = SyncEngine(File(dir, "sync"), opener, clock)
    }

    private fun TestScope.session(
        settings: SettingsRepository = MemorySettingsRepository(),
        elapsed: (() -> Long)? = null,
    ) = Session(
        engine = engine,
        hashStore = hashes,
        settings = settings,
        remotes = { remote },
        effects = effects,
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        clock = clock,
        elapsedMillis = elapsed ?: { testScheduler.currentTime },
        hasher = { String(it).toByteArray() },
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
        assertIs<UnlockOutcome.Unlocked>(s.unlockWithPassword("pw".toCharArray()))
        runCurrent()
        assertIs<SessionState.Unlocked>(s.state.value)
        assertNull(hashes.stored)
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.NOT_SET_UP), s.unlockOffer())
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
    fun `due password deletes the stored value`() = runTest {
        hashes.stored = ByteArray(32)
        val s = session(fingerprintSettings(entry = t0.minus(Duration.ofDays(7))))
        assertEquals(UnlockOffer.Password(PasswordRequiredReason.DUE), s.unlockOffer())
        runCurrent()
        assertNull(hashes.stored)
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
        remote!!.onDownload = { s.lock(LockReason.User) }
        s.unlockWithPassword("pw".toCharArray())
        runCurrent()

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
