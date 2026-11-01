package de.lulurobb.keypassmobile.platform

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.lulurobb.keypassmobile.core.sync.BackgroundSyncResult
import de.lulurobb.keypassmobile.core.sync.KeySource
import de.lulurobb.keypassmobile.core.sync.SyncEngine
import de.lulurobb.keypassmobile.kdbx.PasswordKey
import de.lulurobb.keypassmobile.platform.kdbx.KdbxVaultOpener
import de.lulurobb.keypassmobile.platform.kdbx.NativeArgon2Engine
import de.lulurobb.keypassmobile.platform.keystore.DecryptPreparation
import de.lulurobb.keypassmobile.platform.keystore.KeySpecs
import de.lulurobb.keypassmobile.platform.keystore.KeystoreHashStore
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.platform.session.Session
import de.lulurobb.keypassmobile.platform.session.SessionEffects
import de.lulurobb.keypassmobile.platform.session.SessionState
import de.lulurobb.keypassmobile.platform.session.UnlockOffer
import de.lulurobb.keypassmobile.platform.session.UnlockOutcome
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.MemorySettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock

/**
 * The whole unlock chain on the device: real KDBX file, native Argon2, sync
 * engine, keystore hash store. The BiometricPrompt is replaced by decrypting
 * with a key that needs no authentication (same specification otherwise).
 */
@RunWith(AndroidJUnit4::class)
class SessionDeviceTest {

    private val dir = File(context.noBackupFilesDir, "test-session").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val alias = "test_session_hash"

    @After
    fun cleanUp() {
        scope.cancel()
        KeySpecs.deleteKeyQuietly(alias)
        dir.deleteRecursively()
    }

    @Test
    fun typedPasswordStoresHashAndStoredHashUnlocksAgain() = runBlocking {
        val engine = SyncEngine(File(dir, "sync"), KdbxVaultOpener(NativeArgon2Engine()))
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(LocalRemote(asset("argon2id-aes.kdbx"))))
        val hashStore = KeystoreHashStore(File(dir, "hash.bin"), strongBoxAvailable = false, authRequired = false, alias = alias)
        val settings = MemorySettingsRepository(KpmSettings(fingerprintEnabled = true))
        val session = Session(
            engine = engine,
            hashStore = hashStore,
            settings = settings,
            remotes = { null },
            effects = object : SessionEffects {
                override fun onUnlocked() = Unit

                override fun onLocked(reason: LockReason) = Unit
            },
            scope = scope,
            io = Dispatchers.IO,
            clock = Clock.systemUTC(),
            elapsedMillis = SystemClock::elapsedRealtime,
            hasher = PasswordKey::hash,
        )

        val started = SystemClock.elapsedRealtime()
        val first = session.unlockWithPassword(TEST_DB_PASSWORD.toCharArray())
        val unlockMillis = SystemClock.elapsedRealtime() - started
        assertTrue("$first", first is UnlockOutcome.Unlocked && first.newVersionOpened && first.fingerprintRenewedForDays == 7)
        val unlocked = session.state.value as SessionState.Unlocked
        assertEquals(3, unlocked.index.search("").size)
        assertTrue("unlock took $unlockMillis ms", unlockMillis < 10_000)

        session.storeJob?.join()
        assertTrue(hashStore.hasStoredValue())
        session.lock(LockReason.User)
        assertTrue(session.state.value is SessionState.Locked)
        assertEquals(UnlockOffer.Fingerprint, session.unlockOffer())

        // Stands in for the BiometricPrompt: the cipher decrypts once.
        val ready = hashStore.prepareDecrypt() as DecryptPreparation.Ready
        val hash = ready.cipher.doFinal(ready.ciphertext)
        val second = session.unlockWithStoredHash(hash)
        assertTrue("$second", second is UnlockOutcome.Unlocked)
        assertEquals(KeySource.FINGERPRINT, (session.state.value as SessionState.Unlocked).keySource)
        session.lock(LockReason.User)
    }
}
