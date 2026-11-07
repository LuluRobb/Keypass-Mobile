package de.lulurobb.keypassmobile.core.sync

import de.lulurobb.keypassmobile.core.MutableClock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.File
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {

    @TempDir
    lateinit var dir: File

    private val t0 = Instant.parse("2026-11-01T08:00:00Z")
    private val clock = MutableClock(t0)
    private val opener = FakeOpener()
    private val remote = FakeRemote(kdbx("pw1", "v1"), t0.minus(Duration.ofDays(1)))
    private lateinit var engine: SyncEngine

    @BeforeEach
    fun setUp() {
        engine = SyncEngine(dir, opener, clock)
    }

    private fun offline() = File(dir, "offline.kdbx")
    private fun candidate() = File(dir, "candidate.kdbx")
    private fun temp() = File(dir, "download.tmp")

    private fun unlock(password: String, source: KeySource = KeySource.TYPED_PASSWORD) =
        engine.unlock(key(password), source)

    private fun opened(result: UnlockResult): UnlockResult.Opened = assertIs<UnlockResult.Opened>(result)

    private fun refresh(password: String) = engine.refreshAfterUnlock(remote, key(password))

    /** Publishes a new version on the share, one minute newer than the current one. */
    private fun newVersion(content: String) = remote.publish(content, remote.modified.plusSeconds(60))

    /** First setup plus first unlock: the share's v1 (key pw1) is the offline copy. */
    private fun establish() {
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(remote))
        opened(unlock("pw1")).vault.close()
        assertEquals(kdbx("pw1", "v1"), offline().readText())
        assertFalse(candidate().exists())
    }

    // ---- unlock ----

    @Test
    fun unlockWithoutLocalFilesReportsNoDatabase() {
        assertEquals(UnlockResult.NoDatabase, unlock("pw1"))
        assertFalse(engine.status().hasLocalDatabase)
        assertEquals(0, remote.stats.get(), "unlock never contacts the share")
    }

    @Test
    fun firstSetupDownloadsCandidateAndFirstUnlockPromotesIt() {
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(remote))
        var status = engine.status()
        assertTrue(status.hasLocalDatabase)
        assertEquals(CandidateState.PENDING, status.candidate)
        assertEquals(t0, status.lastSuccessfulSync)
        assertNull(status.openedVersion)
        assertFalse(offline().exists())

        clock.advance(Duration.ofHours(1))
        val result = opened(unlock("pw1"))
        assertEquals("v1", result.vault.rootGroupName)
        assertTrue(result.newVersionOpened)
        assertNull(result.candidateRejected)
        assertEquals(kdbx("pw1", "v1"), offline().readText())
        assertFalse(candidate().exists())
        assertEquals(OpenedVersion.OFFLINE_COPY, engine.status().openedVersion)
        assertEquals(1, remote.stats.get(), "unlock never contacts the share")

        // The promoted candidate keeps its metadata, so the refresh needs no download.
        assertEquals(RefreshResult.UpToDate, refresh("pw1"))
        assertEquals(1, remote.downloads.get())
        status = engine.status()
        assertEquals(OpenedVersion.SHARE, status.openedVersion)
        assertEquals(clock.now, status.lastSuccessfulSync)
        assertEquals(CandidateState.NONE, status.candidate)
        assertNull(status.lastProblem)
    }

    @Test
    fun wrongKeyIsReportedWithItsSource() {
        establish()
        assertEquals(UnlockResult.WrongKey(KeySource.TYPED_PASSWORD), unlock("nope"))
        assertEquals(UnlockResult.WrongKey(KeySource.FINGERPRINT), unlock("nope", KeySource.FINGERPRINT))
        assertTrue(offline().exists())
        val result = opened(unlock("pw1", KeySource.FINGERPRINT))
        assertEquals("v1", result.vault.rootGroupName)
        assertFalse(result.newVersionOpened)
        assertNull(result.candidateRejected)
    }

    @Test
    fun candidateWithWrongKeyIsKeptAndOfflineCopyOpened() {
        establish()
        newVersion(kdbx("pw2", "v2"))
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(remote))

        // Master password changed on the PC; the fingerprint still releases the old hash.
        val first = opened(unlock("pw1", KeySource.FINGERPRINT))
        assertEquals("v1", first.vault.rootGroupName)
        assertEquals(CandidateRejection.WRONG_KEY, first.candidateRejected)
        assertFalse(first.newVersionOpened)
        assertTrue(candidate().exists())
        val status = engine.status()
        assertEquals(CandidateState.REJECTED, status.candidate)
        assertEquals(SyncProblem.NEW_VERSION_REJECTED, status.lastProblem)

        // Lock, then unlock with the new password typed in.
        val second = opened(unlock("pw2"))
        assertEquals("v2", second.vault.rootGroupName)
        assertTrue(second.newVersionOpened)
        assertNull(second.candidateRejected)
        assertEquals(kdbx("pw2", "v2"), offline().readText())
        assertFalse(candidate().exists())
        assertEquals(CandidateState.NONE, engine.status().candidate)
        assertEquals(RefreshResult.UpToDate, refresh("pw2"))
        assertEquals(2, remote.downloads.get())
    }

    @Test
    fun corruptCandidateIsDeletedAndOfflineCopyOpened() {
        establish()
        newVersion("KDBX|damaged")
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(remote))

        val result = opened(unlock("pw1"))
        assertEquals("v1", result.vault.rootGroupName)
        assertEquals(CandidateRejection.CORRUPT, result.candidateRejected)
        assertFalse(candidate().exists())
        val status = engine.status()
        assertEquals(CandidateState.NONE, status.candidate)
        assertEquals(SyncProblem.INVALID_FILE, status.lastProblem)
    }

    @Test
    fun keyFittingNeitherFileIsWrongKeyAndDoesNotFlagTheCandidate() {
        establish()
        newVersion(kdbx("pw2", "v2"))
        engine.backgroundSync(remote)

        assertEquals(UnlockResult.WrongKey(KeySource.TYPED_PASSWORD), unlock("typo"))
        assertTrue(candidate().exists())
        assertTrue(offline().exists())
        val status = engine.status()
        assertEquals(CandidateState.PENDING, status.candidate)
        assertNull(status.lastProblem)
    }

    @Test
    fun corruptCandidateAndWrongKeyForOfflineCopyIsWrongKey() {
        establish()
        newVersion("KDBX|damaged")
        engine.backgroundSync(remote)

        assertEquals(UnlockResult.WrongKey(KeySource.FINGERPRINT), unlock("typo", KeySource.FINGERPRINT))
        assertFalse(candidate().exists())
        assertEquals(kdbx("pw1", "v1"), offline().readText())
    }

    @Test
    fun corruptCandidateWithoutOfflineCopyIsCorrupt() {
        remote.publish("KDBX|damaged", t0)
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(remote))
        assertEquals(UnlockResult.Corrupt, unlock("pw1"))
        assertFalse(candidate().exists())
        assertEquals(UnlockResult.NoDatabase, unlock("pw1"))
    }

    @Test
    fun wrongKeyCandidateWithoutOfflineCopyIsWrongKeyAndKept() {
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(remote))
        assertEquals(UnlockResult.WrongKey(KeySource.FINGERPRINT), unlock("pw2", KeySource.FINGERPRINT))
        assertTrue(candidate().exists())
        assertEquals(CandidateState.PENDING, engine.status().candidate)
        assertEquals("v1", opened(unlock("pw1")).vault.rootGroupName)
    }

    @Test
    fun damagedOfflineCopyIsKeptButFetchedAgain() {
        establish()
        // Same size, so only opening reveals the damage.
        offline().writeText("X".repeat(kdbx("pw1", "v1").length))
        assertEquals(UnlockResult.Corrupt, unlock("pw1"))
        assertTrue(offline().exists())

        // The share did not change, but the damaged copy lost its metadata.
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(remote))
        assertTrue(opened(unlock("pw1")).newVersionOpened)
        assertEquals(RefreshResult.UpToDate, refresh("pw1"))
    }

    @Test
    fun candidateWithoutMetadataIsOpenedAndRefreshLearnsTheRemoteState() {
        // E.g. the process died after moving the candidate into place but before writing the state.
        candidate().writeText(kdbx("pw1", "v1"))
        assertTrue(opened(unlock("pw1")).newVersionOpened)
        assertEquals("v1", assertIs<RefreshResult.Updated>(refresh("pw1")).vault.rootGroupName)
        assertEquals(RefreshResult.UpToDate, refresh("pw1"))
        assertEquals(1, remote.downloads.get())
    }

    // ---- refresh after unlock ----

    @Test
    fun refreshDownloadsOpensAndPromotesNewVersion() {
        establish()
        opened(unlock("pw1"))
        newVersion(kdbx("pw1", "v2 with more entries"))
        clock.advance(Duration.ofHours(1))

        val updated = assertIs<RefreshResult.Updated>(refresh("pw1"))
        assertEquals("v2 with more entries", updated.vault.rootGroupName)
        assertEquals(kdbx("pw1", "v2 with more entries"), offline().readText())
        assertFalse(temp().exists())
        assertFalse(candidate().exists())
        val status = engine.status()
        assertEquals(OpenedVersion.SHARE, status.openedVersion)
        assertEquals(clock.now, status.lastSuccessfulSync)
        assertNull(status.lastProblem)

        assertEquals(RefreshResult.UpToDate, refresh("pw1"))
        assertEquals(2, remote.downloads.get())
    }

    @Test
    fun refreshKeepsVersionWithOtherKeyAsCandidate() {
        establish()
        opened(unlock("pw1"))
        newVersion(kdbx("pw2", "v2"))

        assertEquals(RefreshResult.NewVersionRejected, refresh("pw1"))
        assertEquals(kdbx("pw1", "v1"), offline().readText())
        assertEquals(kdbx("pw2", "v2"), candidate().readText())
        assertFalse(temp().exists())
        var status = engine.status()
        assertEquals(CandidateState.REJECTED, status.candidate)
        assertEquals(SyncProblem.NEW_VERSION_REJECTED, status.lastProblem)
        assertEquals(OpenedVersion.OFFLINE_COPY, status.openedVersion)

        // Neither a second refresh nor the background sync downloads the same file again.
        val downloads = remote.downloads.get()
        assertEquals(RefreshResult.NewVersionRejected, refresh("pw1"))
        assertEquals(BackgroundSyncResult.CandidateAlreadyPresent, engine.backgroundSync(remote))
        assertEquals(downloads, remote.downloads.get())
        status = engine.status()
        assertEquals(CandidateState.REJECTED, status.candidate)
        assertNull(status.lastProblem)

        // Unlocking with the new password opens the new version.
        val result = opened(unlock("pw2"))
        assertEquals("v2", result.vault.rootGroupName)
        assertTrue(result.newVersionOpened)
    }

    @Test
    fun refreshRejectsDamagedDownload() {
        establish()
        opened(unlock("pw1"))
        newVersion("KDBX|damaged")

        assertEquals(RefreshResult.Failed(SyncProblem.INVALID_FILE), refresh("pw1"))
        assertEquals(kdbx("pw1", "v1"), offline().readText())
        assertFalse(candidate().exists())
        assertFalse(temp().exists())
        assertEquals(SyncProblem.INVALID_FILE, engine.status().lastProblem)
    }

    @Test
    fun refreshClosesNewVaultWhenItCannotReplaceTheOfflineCopy() {
        establish()
        opened(unlock("pw1"))
        // Make the offline copy impossible to replace.
        offline().delete()
        offline().mkdir()
        File(offline(), "blocker").writeText("x")
        newVersion(kdbx("pw1", "v2"))

        assertEquals(RefreshResult.Failed(SyncProblem.LOCAL_STORAGE), refresh("pw1"))
        assertTrue(opener.secrets.last().closed, "the discarded vault is closed")
        assertFalse(temp().exists())
    }

    @Test
    fun refreshReportsLocalStorageProblemWhenCandidateCannotBeStored() {
        establish()
        opened(unlock("pw1"))
        candidate().mkdir()
        File(candidate(), "blocker").writeText("x")
        newVersion(kdbx("pw2", "v2"))

        assertEquals(RefreshResult.Failed(SyncProblem.LOCAL_STORAGE), refresh("pw1"))
        assertEquals(kdbx("pw1", "v1"), offline().readText())
        assertFalse(temp().exists())
    }

    // ---- errors and interrupted downloads ----

    @ParameterizedTest
    @EnumSource(RemoteError::class)
    fun remoteErrorsAreReportedAndKeepLocalFiles(error: RemoteError) {
        establish()
        val expected = when (error) {
            RemoteError.UNREACHABLE -> SyncProblem.UNREACHABLE
            RemoteError.SERVER_CHANGED -> SyncProblem.SERVER_CHANGED
            RemoteError.AUTH_FAILED -> SyncProblem.AUTH_FAILED
            RemoteError.NOT_FOUND -> SyncProblem.NOT_FOUND
            RemoteError.ACCESS_DENIED -> SyncProblem.ACCESS_DENIED
            RemoteError.OTHER -> SyncProblem.REMOTE_ERROR
        }
        remote.statError = RemoteException(error)
        clock.advance(Duration.ofMinutes(5))

        assertEquals(RefreshResult.Failed(expected), refresh("pw1"))
        assertEquals(BackgroundSyncResult.Failed(expected), engine.backgroundSync(remote))
        val status = engine.status()
        assertEquals(expected, status.lastProblem)
        assertEquals(clock.now, status.lastProblemAt)
        assertEquals(t0, status.lastSuccessfulSync)
        assertEquals(kdbx("pw1", "v1"), offline().readText())

        // The next successful sync clears the problem.
        remote.statError = null
        assertEquals(RefreshResult.UpToDate, refresh("pw1"))
        assertNull(engine.status().lastProblem)
    }

    @Test
    fun otherExceptionsFromTheShareAreMapped() {
        establish()
        remote.statError = SocketTimeoutException("timed out")
        assertEquals(RefreshResult.Failed(SyncProblem.UNREACHABLE), refresh("pw1"))
        remote.statError = ConnectException("refused")
        assertEquals(BackgroundSyncResult.Failed(SyncProblem.UNREACHABLE), engine.backgroundSync(remote))
        remote.statError = IllegalStateException("library bug")
        assertEquals(RefreshResult.Failed(SyncProblem.REMOTE_ERROR), refresh("pw1"))
    }

    @Test
    fun interruptedDownloadKeepsOfflineCopyAndCandidate() {
        establish()
        opened(unlock("pw1"))
        newVersion(kdbx("pw1", "v2"))
        remote.partialBytes = 4
        remote.downloadError = RemoteException(RemoteError.UNREACHABLE)

        assertEquals(RefreshResult.Failed(SyncProblem.UNREACHABLE), refresh("pw1"))
        assertEquals(kdbx("pw1", "v1"), offline().readText())
        assertFalse(temp().exists())
        assertFalse(candidate().exists())

        // A candidate survives an interrupted background download as well.
        remote.partialBytes = null
        remote.downloadError = null
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(remote))
        newVersion(kdbx("pw1", "v3"))
        remote.partialBytes = 6
        remote.downloadError = SocketTimeoutException("timed out")
        assertEquals(BackgroundSyncResult.Failed(SyncProblem.UNREACHABLE), engine.backgroundSync(remote))
        assertEquals(kdbx("pw1", "v2"), candidate().readText())
        assertFalse(temp().exists())

        // The next attempt works and makes the candidate obsolete.
        remote.partialBytes = null
        remote.downloadError = null
        assertEquals("v3", assertIs<RefreshResult.Updated>(refresh("pw1")).vault.rootGroupName)
        assertFalse(candidate().exists())
    }

    @Test
    fun truncatedDownloadWithoutErrorIsRejected() {
        establish()
        newVersion(kdbx("pw1", "v2"))
        remote.partialBytes = 5

        assertEquals(RefreshResult.Failed(SyncProblem.DOWNLOAD_INCOMPLETE), refresh("pw1"))
        assertEquals(BackgroundSyncResult.Failed(SyncProblem.DOWNLOAD_INCOMPLETE), engine.backgroundSync(remote))
        assertFalse(temp().exists())
        assertFalse(candidate().exists())
        assertEquals(kdbx("pw1", "v1"), offline().readText())
    }

    @Test
    fun fileChangedDuringDownloadIsRejectedAndFetchedNextTime() {
        establish()
        newVersion(kdbx("pw1", "v2"))
        remote.onDownload = { remote.content = kdbx("pw1", "v3 saved while downloading") }

        assertEquals(RefreshResult.Failed(SyncProblem.DOWNLOAD_INCOMPLETE), refresh("pw1"))
        remote.onDownload = null
        assertEquals("v3 saved while downloading", assertIs<RefreshResult.Updated>(refresh("pw1")).vault.rootGroupName)
    }

    @Test
    fun interruptedThreadKeepsItsInterruptFlag() {
        establish()
        newVersion(kdbx("pw1", "v2"))
        remote.downloadError = InterruptedException()
        try {
            assertEquals(BackgroundSyncResult.Failed(SyncProblem.REMOTE_ERROR), engine.backgroundSync(remote))
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertFalse(temp().exists())
    }

    @Test
    fun leftoverTempFileIsRemoved() {
        establish()
        temp().writeText("half a file from a killed process")
        remote.statError = RemoteException(RemoteError.UNREACHABLE)
        engine.backgroundSync(remote)
        assertFalse(temp().exists())
    }

    // ---- background sync ----

    @Test
    fun backgroundSyncSkipsDownloadsWhenNothingChanged() {
        establish()
        assertEquals(BackgroundSyncResult.UpToDate, engine.backgroundSync(remote))
        newVersion(kdbx("pw1", "v2"))
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(remote))
        clock.advance(Duration.ofHours(4))
        assertEquals(BackgroundSyncResult.CandidateAlreadyPresent, engine.backgroundSync(remote))
        assertEquals(2, remote.downloads.get())
        assertEquals(clock.now, engine.status().lastSuccessfulSync)
    }

    @Test
    fun backgroundSyncReplacesOlderCandidateAndResetsRejection() {
        establish()
        newVersion(kdbx("pw2", "v2"))
        engine.backgroundSync(remote)
        opened(unlock("pw1"))
        assertEquals(CandidateState.REJECTED, engine.status().candidate)

        newVersion(kdbx("pw2", "v3"))
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(remote))
        assertEquals(kdbx("pw2", "v3"), candidate().readText())
        assertEquals(CandidateState.PENDING, engine.status().candidate)
    }

    @Test
    fun backgroundSyncRejectsFileThatIsNoKdbx() {
        establish()
        newVersion(kdbx("pw1", "v2"))
        engine.backgroundSync(remote)
        newVersion("<html>Wartungsarbeiten</html>")

        assertEquals(BackgroundSyncResult.Failed(SyncProblem.INVALID_FILE), engine.backgroundSync(remote))
        assertEquals(kdbx("pw1", "v2"), candidate().readText())
        assertFalse(temp().exists())
    }

    @Test
    fun staleCandidateIsDeletedWhenTheShareMatchesTheOfflineCopy() {
        establish()
        val original = remote.content to remote.modified
        newVersion(kdbx("pw1", "v2"))
        engine.backgroundSync(remote)
        // The share went back to the old file (e.g. restored from a backup).
        remote.publish(original.first, original.second)
        assertEquals(BackgroundSyncResult.UpToDate, engine.backgroundSync(remote))
        assertFalse(candidate().exists())

        // Same in the refresh after unlock, with a rejected candidate.
        newVersion(kdbx("pw2", "v2"))
        engine.backgroundSync(remote)
        opened(unlock("pw1"))
        remote.publish(original.first, original.second)
        assertEquals(RefreshResult.UpToDate, refresh("pw1"))
        assertFalse(candidate().exists())
        assertEquals(CandidateState.NONE, engine.status().candidate)
    }

    @Test
    fun openedVersionFallsBackWhenTheShareChanges() {
        establish()
        opened(unlock("pw1"))
        assertEquals(OpenedVersion.OFFLINE_COPY, engine.status().openedVersion)
        refresh("pw1")
        assertEquals(OpenedVersion.SHARE, engine.status().openedVersion)
        newVersion(kdbx("pw1", "v2"))
        engine.backgroundSync(remote)
        assertEquals(OpenedVersion.OFFLINE_COPY, engine.status().openedVersion)
    }

    @Test
    fun aLaterRefreshThatFindsTheOfflineCopyCurrentConfirmsTheOpenedVersion() {
        establish()
        opened(unlock("pw1"))
        // The share was unreachable right after the unlock.
        remote.statError = RemoteException(RemoteError.UNREACHABLE)
        clock.advance(Duration.ofMinutes(5))
        assertEquals(RefreshResult.Failed(SyncProblem.UNREACHABLE), refresh("pw1"))
        assertEquals(OpenedVersion.OFFLINE_COPY, engine.status().openedVersion)

        remote.statError = null
        clock.advance(Duration.ofMinutes(5))
        assertEquals(RefreshResult.UpToDate, refresh("pw1"))

        val status = engine.status()
        assertEquals(OpenedVersion.SHARE, status.openedVersion)
        assertNull(status.lastProblem)
        assertEquals(clock.now, status.lastSuccessfulSync)
    }

    @Test
    fun aRejectedNewVersionNeverConfirmsTheOpenedVersion() {
        establish()
        opened(unlock("pw1"))
        newVersion(kdbx("pw2", "v2"))

        assertEquals(RefreshResult.NewVersionRejected, refresh("pw1"))
        assertEquals(RefreshResult.NewVersionRejected, refresh("pw1"))

        assertEquals(OpenedVersion.OFFLINE_COPY, engine.status().openedVersion)
        assertEquals(SyncProblem.NEW_VERSION_REJECTED, engine.status().lastProblem)
    }

    @Test
    fun theBackgroundSyncWithoutAKeyConfirmsNoOpenedVersion() {
        establish()
        opened(unlock("pw1"))
        clock.advance(Duration.ofMinutes(5))

        assertEquals(BackgroundSyncResult.UpToDate, engine.backgroundSync(remote))

        assertEquals(OpenedVersion.OFFLINE_COPY, engine.status().openedVersion)
        assertEquals(clock.now, engine.status().lastSuccessfulSync)
    }

    // ---- persistence ----

    @Test
    fun stateSurvivesRestart() {
        establish()
        clock.advance(Duration.ofMinutes(10))
        assertEquals(RefreshResult.UpToDate, refresh("pw1"))

        val restarted = SyncEngine(dir, opener, clock)
        val status = restarted.status()
        assertEquals(clock.now, status.lastSuccessfulSync)
        assertNull(status.openedVersion)
        assertTrue(status.hasLocalDatabase)
        val downloads = remote.downloads.get()
        assertEquals(RefreshResult.UpToDate, restarted.refreshAfterUnlock(remote, key("pw1")))
        assertEquals(downloads, remote.downloads.get())

        newVersion(kdbx("pw2", "v2"))
        restarted.backgroundSync(remote)
        opened(restarted.unlock(key("pw1"), KeySource.FINGERPRINT))
        remote.statError = RemoteException(RemoteError.UNREACHABLE)
        restarted.backgroundSync(remote)

        val third = SyncEngine(dir, opener, clock).status()
        assertEquals(CandidateState.REJECTED, third.candidate)
        assertEquals(SyncProblem.UNREACHABLE, third.lastProblem)
        assertEquals(clock.now, third.lastProblemAt)

        // Only metadata, no key material in the state file (SE-07).
        val stateText = File(dir, "sync-state.properties").readText()
        assertFalse("pw1" in stateText || "pw2" in stateText)
    }

    @Test
    fun offlineMetadataIsIgnoredWhenTheFileSizeDiffers() {
        establish()
        offline().writeText(kdbx("pw1", "replaced by hand"))
        assertEquals("v1", assertIs<RefreshResult.Updated>(refresh("pw1")).vault.rootGroupName)
        assertEquals(2, remote.downloads.get())
    }

    @Test
    fun resetDeletesAllLocalFiles() {
        establish()
        newVersion(kdbx("pw2", "v2"))
        engine.backgroundSync(remote)

        engine.reset()
        assertFalse(offline().exists())
        assertFalse(candidate().exists())
        val status = engine.status()
        assertFalse(status.hasLocalDatabase)
        assertNull(status.lastSuccessfulSync)
        assertEquals(CandidateState.NONE, status.candidate)
        assertEquals(UnlockResult.NoDatabase, unlock("pw1"))
        assertEquals(UnlockResult.NoDatabase, SyncEngine(dir, opener, clock).unlock(key("pw1"), KeySource.TYPED_PASSWORD))
    }

    // ---- modification times (EN-07, DS-14) ----

    @Test
    fun localAndOpenedModifiedFollowSetupUnlockRefreshAndReset() {
        val m1 = remote.modified
        assertNull(engine.status().localModified)
        assertNull(engine.status().openedModified)

        engine.backgroundSync(remote)
        assertEquals(m1, engine.status().localModified, "pending candidate")
        assertNull(engine.status().openedModified)

        opened(unlock("pw1")).vault.close()
        assertEquals(m1, engine.status().localModified)
        assertEquals(m1, engine.status().openedModified)

        newVersion(kdbx("pw1", "v2"))
        val m2 = remote.modified
        assertIs<RefreshResult.Updated>(refresh("pw1"))
        assertEquals(m2, engine.status().localModified)
        assertEquals(m2, engine.status().openedModified)

        engine.reset()
        assertNull(engine.status().localModified)
        assertNull(engine.status().openedModified)
    }

    @Test
    fun localModifiedPrefersPendingCandidateThenOfflineCopyThenRejectedCandidate() {
        establish()
        val m1 = remote.modified
        assertEquals(m1, engine.status().localModified)

        newVersion(kdbx("pw1", "v2"))
        val m2 = remote.modified
        engine.backgroundSync(remote)
        assertEquals(CandidateState.PENDING, engine.status().candidate)
        assertEquals(m2, engine.status().localModified)

        // Other key: the candidate is rejected, the offline copy is what the next unlock opens first.
        newVersion(kdbx("pw2", "v3"))
        engine.backgroundSync(remote)
        opened(unlock("pw1")).vault.close()
        assertEquals(CandidateState.REJECTED, engine.status().candidate)
        assertEquals(m1, engine.status().localModified)
        assertEquals(m1, engine.status().openedModified)
    }

    @Test
    fun localModifiedFallsBackToARejectedCandidateWithoutOfflineCopy() {
        engine.backgroundSync(remote)
        val m1 = remote.modified
        assertEquals(UnlockResult.WrongKey(KeySource.TYPED_PASSWORD), unlock("other"))
        assertEquals(CandidateState.PENDING, engine.status().candidate)
        assertEquals(m1, engine.status().localModified)
    }

    @Test
    fun unknownModificationTimeCountsAsNull() {
        remote.modified = RemoteStat.UNKNOWN_MODIFIED
        engine.backgroundSync(remote)
        assertNull(engine.status().localModified)
        opened(unlock("pw1")).vault.close()
        assertNull(engine.status().localModified)
        assertNull(engine.status().openedModified)
        assertEquals(RefreshResult.UpToDate, refresh("pw1"))
    }

    @Test
    fun openedModifiedStaysWhenALaterRefreshOnlyFindsANewerVersionItCannotOpen() {
        establish()
        val m1 = remote.modified
        newVersion(kdbx("pw2", "v2"))
        assertEquals(RefreshResult.NewVersionRejected, refresh("pw1"))
        assertEquals(m1, engine.status().openedModified)
    }

    // ---- probeOpen (DS-14) ----

    private fun probe() = File(dir, "probe.tmp")

    @Test
    fun probeOpenReturnsStatAndRootGroupUuidAndChangesNothing() {
        remote.publish(kdbx("pw1", "v1", db = "dbA"), remote.modified)
        engine.backgroundSync(remote)
        opened(unlock("pw1")).vault.close()
        val opened = opener.secrets.size
        val before = engine.status()
        val offlineBefore = offline().readText()

        remote.publish(kdbx("pw1", "other-label", db = "dbA"), remote.modified.minusSeconds(3600))
        val result = assertIs<ProbeResult.Opened>(engine.probeOpen(remote, key("pw1")))
        assertEquals(remote.modified, result.stat.modified)
        assertEquals(UUID.nameUUIDFromBytes("dbA".toByteArray()), result.rootGroupUuid)

        assertEquals(opened + 1, opener.secrets.size)
        assertTrue(opener.secrets.last().closed, "the probe vault is closed")
        assertFalse(probe().exists())
        assertEquals(offlineBefore, offline().readText())
        assertEquals(before, engine.status())
    }

    @Test
    fun aProbeLeftByACrashIsDeletedWhenTheEngineStarts() {
        probe().writeBytes(kdbx("pw1", "v1").toByteArray())
        engine.backgroundSync(remote)

        // A new process: a new engine on the same directory.
        engine = SyncEngine(dir, opener, clock)

        assertFalse(probe().exists(), "no leftover download of a probe")
        assertTrue(candidate().exists(), "the real files are untouched")
    }

    @Test
    fun resetDeletesALeftoverProbe() {
        establish()
        probe().writeBytes(ByteArray(10))

        engine.reset()

        assertFalse(probe().exists())
        assertFalse(offline().exists())
    }

    @Test
    fun probeOpenWithAnotherKeyIsWrongKeyAndRecordsNothing() {
        establish()
        newVersion(kdbx("pw2", "v2"))
        val before = engine.status()
        assertEquals(ProbeResult.WrongKey, engine.probeOpen(remote, key("pw1")))
        assertFalse(probe().exists())
        assertEquals(before, engine.status())
        assertFalse(candidate().exists())
    }

    @Test
    fun probeOpenOfADamagedFileFailsWithInvalidFile() {
        establish()
        newVersion("KDBX|broken")
        assertEquals(ProbeResult.Failed(SyncProblem.INVALID_FILE), engine.probeOpen(remote, key("pw1")))
        assertFalse(probe().exists())
        assertNull(engine.status().lastProblem)
    }

    @Test
    fun probeOpenReportsStatErrorsWithoutRecordingThem() {
        establish()
        remote.statError = RemoteException(RemoteError.NOT_FOUND)
        assertEquals(ProbeResult.Failed(SyncProblem.NOT_FOUND), engine.probeOpen(remote, key("pw1")))
        assertEquals(0, remote.downloads.get() - 1, "no download after a failed stat")
        assertNull(engine.status().lastProblem)
        assertFalse(probe().exists())
    }

    @Test
    fun probeOpenRejectsASizeMismatch() {
        establish()
        newVersion(kdbx("pw1", "v2"))
        remote.partialBytes = 5
        assertEquals(ProbeResult.Failed(SyncProblem.DOWNLOAD_INCOMPLETE), engine.probeOpen(remote, key("pw1")))
        assertFalse(probe().exists())
        assertNull(engine.status().lastProblem)
    }

    @Test
    fun probeOpenReportsDownloadErrorsAndRemovesALeftoverProbeFile() {
        establish()
        probe().writeText("leftover")
        remote.downloadError = RemoteException(RemoteError.ACCESS_DENIED)
        assertEquals(ProbeResult.Failed(SyncProblem.ACCESS_DENIED), engine.probeOpen(remote, key("pw1")))
        assertFalse(probe().exists())
    }

    @Test
    fun probeOpenWorksWithoutAnyLocalDatabase() {
        val result = assertIs<ProbeResult.Opened>(engine.probeOpen(remote, key("pw1")))
        assertNull(result.rootGroupUuid, "a vault without root uuid gives null")
        assertFalse(engine.status().hasLocalDatabase)
        assertFalse(probe().exists())
        assertFalse(candidate().exists())
    }

    // ---- source change ----

    private fun previous() = File(dir, "offline-previous.kdbx")

    /** Offline copy of the first file (pw1, v1); the source points to another file that waits as candidate and the old copy is set aside. */
    private fun changeSource(newContent: String) {
        establish()
        remote.publish(newContent, t0.minus(Duration.ofDays(2)))
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(remote))
        assertTrue(engine.markSourceChanged())
    }

    @Test
    fun previousSourceCopyStaysOnDiskButIsNotOpenedUntilTheNewFileOpened() {
        changeSource(kdbx("pw2", "other"))

        assertEquals(kdbx("pw1", "v1"), previous().readText(), "old copy stays until the new file opened")
        assertFalse(offline().exists())
        // The old key does not show the old file as the new source's database.
        assertEquals(UnlockResult.WrongKey(KeySource.FINGERPRINT), unlock("pw1", KeySource.FINGERPRINT))
        assertTrue(previous().exists())
        assertEquals(CandidateState.PENDING, engine.status().candidate)

        // The new file opens: it becomes the offline copy and the old copy is deleted.
        val result = opened(unlock("pw2"))
        assertEquals("other", result.vault.rootGroupName)
        assertTrue(result.newVersionOpened)
        assertEquals(kdbx("pw2", "other"), offline().readText())
        assertFalse(previous().exists())
        assertFalse(candidate().exists())
        assertEquals(RefreshResult.UpToDate, refresh("pw2"))
        assertEquals("other", opened(SyncEngine(dir, opener, clock).unlock(key("pw2"), KeySource.TYPED_PASSWORD)).vault.rootGroupName)
    }

    @Test
    fun withoutACandidateTheOfflineCopyIsNeverSetAside() {
        establish()
        // Nothing waits as candidate (e.g. the new file already replaced the offline copy): renaming would lose the database.
        assertFalse(engine.markSourceChanged())
        assertTrue(offline().exists())
        assertFalse(previous().exists())
        assertEquals("v1", opened(unlock("pw1")).vault.rootGroupName)
    }

    @Test
    fun previousSourceCopyIsNotCountedAsLocalDatabaseAndSurvivesARestart() {
        changeSource(kdbx("pw2", "other"))
        // Only the candidate counts. Nothing depends on the state file: without it the old copy is still not opened.
        File(dir, "sync-state.properties").delete()
        val restarted = SyncEngine(dir, opener, clock)
        assertEquals(UnlockResult.WrongKey(KeySource.TYPED_PASSWORD), restarted.unlock(key("pw1"), KeySource.TYPED_PASSWORD))
        assertTrue(previous().exists())
        candidate().delete()
        assertFalse(SyncEngine(dir, opener, clock).status().hasLocalDatabase)
        assertEquals(UnlockResult.NoDatabase, SyncEngine(dir, opener, clock).unlock(key("pw1"), KeySource.TYPED_PASSWORD))
    }

    @Test
    fun previousSourceCopyIsNeverTakenForTheNewFile() {
        changeSource(kdbx("pw2", "other"))
        // The share serves the old file's size and time again: not "up to date", the old copy is gone from the offline slot.
        remote.publish(kdbx("pw1", "v1"), t0.minus(Duration.ofDays(1)))
        assertFalse(engine.backgroundSync(remote) is BackgroundSyncResult.UpToDate)
    }

    @Test
    fun refreshWithTheKeyOfTheNewFileReplacesAndRemovesThePreviousSourceCopy() {
        establish()
        remote.publish(kdbx("pw1", "other"), t0.minus(Duration.ofDays(2)))
        assertEquals("other", assertIs<RefreshResult.Updated>(refresh("pw1")).vault.rootGroupName)
        // Replaced in place: no candidate, nothing to set aside, nothing lost.
        assertFalse(engine.markSourceChanged())
        assertEquals(kdbx("pw1", "other"), offline().readText())
        assertFalse(previous().exists())
        assertEquals("other", opened(unlock("pw1")).vault.rootGroupName)
    }

    @Test
    fun changingTheSourceTwiceKeepsTheFirstPreviousCopy() {
        changeSource(kdbx("pw2", "other"))
        assertFalse(engine.markSourceChanged())
        assertEquals(kdbx("pw1", "v1"), previous().readText())
        assertEquals("other", opened(unlock("pw2")).vault.rootGroupName)
        assertFalse(previous().exists())
    }

    @Test
    fun undoSourceChangedBringsTheOldCopyBack() {
        changeSource(kdbx("pw2", "other"))
        engine.undoSourceChanged()
        engine.discardCandidate()
        assertEquals(kdbx("pw1", "v1"), offline().readText())
        assertFalse(previous().exists())
        assertFalse(candidate().exists())
        assertEquals("v1", opened(unlock("pw1")).vault.rootGroupName)
        // Undo without anything set aside does nothing.
        engine.undoSourceChanged()
        assertEquals("v1", opened(unlock("pw1")).vault.rootGroupName)
    }

    @Test
    fun undoSourceChangedBringsBackTheSyncStateOfTheOldCopy() {
        establish()
        val before = engine.status()
        remote.publish(kdbx("pw2", "other"), t0.minus(Duration.ofDays(2)))
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(remote))
        assertTrue(engine.markSourceChanged())

        engine.undoSourceChanged()
        engine.discardCandidate()

        val after = engine.status()
        assertEquals(before.localModified, after.localModified, "the metadata of the old copy is back")
        assertEquals(CandidateState.NONE, after.candidate)
        assertTrue(after.hasLocalDatabase)
        assertEquals(kdbx("pw1", "v1"), offline().readText())
    }

    @Test
    fun aSetAsideMetadataDoesNotSurviveTheDeletionOfItsCopy() {
        changeSource(kdbx("pw2", "other"))
        // The new file opens: the set-aside copy is deleted. A later undo must not bring its metadata back.
        opened(unlock("pw2")).vault.close()
        assertFalse(previous().exists())
        val before = engine.status().localModified

        engine.undoSourceChanged()

        assertEquals(before, engine.status().localModified)
        assertEquals(kdbx("pw2", "other"), offline().readText())
    }

    @Test
    fun resetAlsoDeletesThePreviousSourceCopy() {
        changeSource(kdbx("pw2", "other"))
        engine.reset()
        assertFalse(previous().exists())
    }

    @Test
    fun beforeStoreFailureKeepsTheOfflineCopyAndReportsInvalidFile() {
        establish()
        newVersion(kdbx("pw1", "v2"))
        val result = engine.refreshAfterUnlock(remote, key("pw1")) { throw IllegalStateException("index") }
        assertEquals(RefreshResult.Failed(SyncProblem.INVALID_FILE), result)
        assertEquals(kdbx("pw1", "v1"), offline().readText())
        assertFalse(temp().exists())
        assertEquals(SyncProblem.INVALID_FILE, engine.status().lastProblem)
        assertTrue(opener.secrets.last().closed, "the vault that could not be prepared is closed")
        // The next refresh retries.
        assertEquals("v2", assertIs<RefreshResult.Updated>(refresh("pw1")).vault.rootGroupName)
    }

    // ---- concurrency ----

    @Test
    fun unlockDoesNotWaitForARunningDownload() {
        establish()
        newVersion(kdbx("pw1", "v2"))
        val inDownload = CountDownLatch(1)
        val release = CountDownLatch(1)
        remote.onDownload = {
            inDownload.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val sync = thread { engine.backgroundSync(remote) }
        try {
            assertTrue(inDownload.await(5, TimeUnit.SECONDS))
            val start = System.nanoTime()
            val result = opened(unlock("pw1"))
            val millis = (System.nanoTime() - start) / 1_000_000
            assertEquals("v1", result.vault.rootGroupName)
            assertTrue(millis < 2000, "unlock took $millis ms while a download was running")
        } finally {
            release.countDown()
            sync.join(10_000)
        }
        assertEquals(kdbx("pw1", "v2"), candidate().readText())
    }

    @Test
    fun concurrentSyncsAreSerialized() {
        establish()
        newVersion(kdbx("pw1", "v2"))
        val downloadsBefore = remote.downloads.get()
        val inDownload = CountDownLatch(1)
        val release = CountDownLatch(1)
        remote.onDownload = {
            inDownload.countDown()
            release.await(10, TimeUnit.SECONDS)
        }
        val results = ConcurrentLinkedQueue<BackgroundSyncResult>()
        val first = thread { results += engine.backgroundSync(remote) }
        assertTrue(inDownload.await(5, TimeUnit.SECONDS))
        val second = thread { results += engine.backgroundSync(remote) }
        Thread.sleep(200)
        release.countDown()
        first.join(10_000)
        second.join(10_000)

        assertEquals(
            setOf(BackgroundSyncResult.CandidateDownloaded, BackgroundSyncResult.CandidateAlreadyPresent),
            results.toSet(),
        )
        assertEquals(downloadsBefore + 1, remote.downloads.get())
    }

    @Test
    fun candidateIsNotReplacedWhileUnlockOpensIt() {
        establish()
        newVersion(kdbx("pw1", "v2"))
        assertEquals(BackgroundSyncResult.CandidateDownloaded, engine.backgroundSync(remote))

        val opening = CountDownLatch(1)
        val release = CountDownLatch(1)
        opener.beforeOpen = { file ->
            if (file.name == "candidate.kdbx" && opening.count > 0) {
                opening.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
        }
        var unlockResult: UnlockResult? = null
        val unlocker = thread { unlockResult = unlock("pw1") }
        assertTrue(opening.await(5, TimeUnit.SECONDS))

        newVersion(kdbx("pw1", "v3"))
        var syncResult: BackgroundSyncResult? = null
        val syncer = thread { syncResult = engine.backgroundSync(remote) }
        // Give the sync time to download v3 and wait for the file lock.
        Thread.sleep(300)
        release.countDown()
        unlocker.join(10_000)
        syncer.join(10_000)

        val result = opened(unlockResult!!)
        assertEquals("v2", result.vault.rootGroupName)
        assertTrue(result.newVersionOpened)
        assertEquals(BackgroundSyncResult.CandidateDownloaded, syncResult)
        assertEquals(kdbx("pw1", "v2"), offline().readText())
        assertEquals(kdbx("pw1", "v3"), candidate().readText())
    }
}
