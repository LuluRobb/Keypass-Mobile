package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.sync.OpenedVersion
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.settings.FileSource
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.SmbSource
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import de.lulurobb.keypassmobile.platform.setup.OlderVersion
import de.lulurobb.keypassmobile.ui.common.SourceAction
import de.lulurobb.keypassmobile.ui.components.OfflineReason
import de.lulurobb.keypassmobile.ui.components.SyncState
import de.lulurobb.keypassmobile.ui.components.SyncStatus
import de.lulurobb.keypassmobile.ui.list.ListBanner
import de.lulurobb.keypassmobile.ui.list.SourceProblem
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SyncMappingTest {

    private val time = testTime()
    private val smb = SmbSource("nas.local", 445, "", "kpmtest", "daten", listOf("KeePass", "pw.kdbx"))
    private val file = FileSource("content://docs/doc/1", "Passwoerter.kdbx", "Interner Speicher / Syncthing")
    private val synced = Instant.parse("2026-11-01T10:00:00Z")
    private val syncedLocal = LocalDateTime.of(2026, 11, 1, 10, 0)

    private fun reasonOf(
        problem: SyncProblem? = null,
        serverChange: Boolean = false,
        rejected: Boolean = false,
        kind: SourceKind = SourceKind.SMB,
    ): OfflineReason? {
        val info = testSyncInfo(
            openedVersion = OpenedVersion.OFFLINE_COPY,
            lastProblem = problem,
            lastProblemAt = synced,
            serverChange = if (serverChange) testServerChange() else null,
        )
        val state = toSyncState(info, unlockedView(newVersionRejected = rejected), time, kind)
        return (state.status as SyncStatus.OfflineCopy).reason
    }

    @Test
    fun `version of the share open is current with the last successful sync`() {
        val state = toSyncState(testSyncInfo(), unlockedView(), time, SourceKind.SMB)
        assertEquals(SyncState(SyncStatus.Current(syncedLocal), syncing = false), state)
    }

    @Test
    fun `syncing flag is passed on`() {
        assertEquals(true, toSyncState(testSyncInfo(syncing = true), null, time, SourceKind.SMB).syncing)
        assertEquals(false, toSyncState(testSyncInfo(syncing = false), null, time, SourceKind.SMB).syncing)
    }

    @Test
    fun `offline copy carries the date of the last successful sync`() {
        val info = testSyncInfo(
            openedVersion = OpenedVersion.OFFLINE_COPY,
            lastProblem = SyncProblem.UNREACHABLE,
            lastProblemAt = Instant.parse("2026-11-02T07:00:00Z"),
        )
        assertEquals(
            SyncState(SyncStatus.OfflineCopy(syncedLocal, OfflineReason.ShareUnreachable)),
            toSyncState(info, unlockedView(), time, SourceKind.SMB),
        )
    }

    @Test
    fun `before the first unlock the offline copy is shown`() {
        val info = testSyncInfo(openedVersion = null)
        assertEquals(
            SyncStatus.OfflineCopy(syncedLocal, OfflineReason.ShareUnreachable),
            toSyncState(info, null, time, SourceKind.SMB).status,
        )
    }

    @Test
    fun `every offline reason is derived from the problem`() {
        assertEquals(OfflineReason.ShareUnreachable, reasonOf(problem = null))
        assertEquals(OfflineReason.ShareUnreachable, reasonOf(SyncProblem.UNREACHABLE))
        assertEquals(OfflineReason.OtherServer, reasonOf(SyncProblem.SERVER_CHANGED))
        assertEquals(OfflineReason.NewVersionRejected, reasonOf(SyncProblem.NEW_VERSION_REJECTED))
        assertEquals(OfflineReason.LoginFailed, reasonOf(SyncProblem.AUTH_FAILED))
        assertEquals(OfflineReason.FileNotFound, reasonOf(SyncProblem.NOT_FOUND))
        assertEquals(OfflineReason.NoAccess, reasonOf(SyncProblem.ACCESS_DENIED))
    }

    @Test
    fun `other problems count as share unreachable`() {
        for (problem in listOf(
            SyncProblem.REMOTE_ERROR,
            SyncProblem.DOWNLOAD_INCOMPLETE,
            SyncProblem.INVALID_FILE,
            SyncProblem.LOCAL_STORAGE,
        )) {
            assertEquals(OfflineReason.ShareUnreachable, reasonOf(problem), "$problem")
        }
    }

    @Test
    fun `pending server question and rejected version win over the problem`() {
        assertEquals(OfflineReason.OtherServer, reasonOf(SyncProblem.UNREACHABLE, serverChange = true))
        assertEquals(OfflineReason.NewVersionRejected, reasonOf(SyncProblem.UNREACHABLE, rejected = true))
        // A missing or refused source wins over a rejected version, as the list banner ranks them (UI-09).
        assertEquals(OfflineReason.LoginFailed, reasonOf(SyncProblem.AUTH_FAILED, rejected = true))
        assertEquals(OfflineReason.FileNotFound, reasonOf(SyncProblem.NOT_FOUND, rejected = true))
        assertEquals(OfflineReason.NoAccess, reasonOf(SyncProblem.ACCESS_DENIED, rejected = true))
        assertEquals(OfflineReason.OtherServer, reasonOf(rejected = true, serverChange = true))
    }

    @Test
    fun `a recorded problem turns the open share version into the offline copy with the last successful sync`() {
        for ((problem, reason) in listOf(
            SyncProblem.UNREACHABLE to OfflineReason.ShareUnreachable,
            SyncProblem.AUTH_FAILED to OfflineReason.LoginFailed,
            SyncProblem.NOT_FOUND to OfflineReason.FileNotFound,
            SyncProblem.ACCESS_DENIED to OfflineReason.NoAccess,
            SyncProblem.SERVER_CHANGED to OfflineReason.OtherServer,
            SyncProblem.REMOTE_ERROR to OfflineReason.ShareUnreachable,
        )) {
            val info = testSyncInfo(
                openedVersion = OpenedVersion.SHARE,
                lastProblem = problem,
                lastProblemAt = Instant.parse("2026-11-02T07:00:00Z"),
            )
            assertEquals(
                SyncState(SyncStatus.OfflineCopy(syncedLocal, reason)),
                toSyncState(info, unlockedView(), time, SourceKind.SMB),
                "$problem",
            )
        }
    }

    @Test
    fun `the open share version is current again once the problem is cleared by a successful sync`() {
        val failed = testSyncInfo(openedVersion = OpenedVersion.SHARE, lastProblem = SyncProblem.UNREACHABLE)
        assertEquals(
            SyncStatus.OfflineCopy(syncedLocal, OfflineReason.ShareUnreachable),
            toSyncState(failed, unlockedView(), time, SourceKind.SMB).status,
        )
        val recovered = testSyncInfo(
            openedVersion = OpenedVersion.SHARE,
            lastSuccessfulSync = Instant.parse("2026-11-02T08:00:00Z"),
            lastProblem = null,
        )
        assertEquals(
            SyncStatus.Current(LocalDateTime.of(2026, 11, 2, 8, 0)),
            toSyncState(recovered, unlockedView(), time, SourceKind.SMB).status,
        )
    }

    @Test
    fun `a running sync with a recorded problem keeps the offline copy, without a problem the share version stays current`() {
        val failed = testSyncInfo(openedVersion = OpenedVersion.SHARE, lastProblem = SyncProblem.UNREACHABLE, syncing = true)
        assertEquals(
            SyncState(SyncStatus.OfflineCopy(syncedLocal, OfflineReason.ShareUnreachable), syncing = true),
            toSyncState(failed, unlockedView(), time, SourceKind.SMB),
        )
        val clean = testSyncInfo(openedVersion = OpenedVersion.SHARE, syncing = true)
        assertEquals(
            SyncState(SyncStatus.Current(syncedLocal), syncing = true),
            toSyncState(clean, unlockedView(), time, SourceKind.SMB),
        )
    }

    @Test
    fun `a pending server question turns the open share version into the offline copy`() {
        val info = testSyncInfo(serverChange = testServerChange())
        assertEquals(
            SyncStatus.OfflineCopy(syncedLocal, OfflineReason.OtherServer),
            toSyncState(info, unlockedView(), time, SourceKind.SMB).status,
        )
    }

    @Test
    fun `instants are shown in the system zone`() {
        val berlin = testTime(zone = ZoneId.of("Europe/Berlin"))
        val summer = testSyncInfo(lastSuccessfulSync = Instant.parse("2026-07-01T10:00:00Z"))
        val winter = testSyncInfo(lastSuccessfulSync = Instant.parse("2026-11-01T10:00:00Z"))
        assertEquals(SyncStatus.Current(LocalDateTime.of(2026, 7, 1, 12, 0)), toSyncState(summer, null, berlin, SourceKind.SMB).status)
        assertEquals(SyncStatus.Current(LocalDateTime.of(2026, 11, 1, 11, 0)), toSyncState(winter, null, berlin, SourceKind.SMB).status)
    }

    @Test
    fun `the zone is read at every call`() {
        var zone: ZoneId = ZoneId.of("UTC")
        val moving = TimeSource(Clock.systemUTC()) { zone }
        val info = testSyncInfo()
        assertEquals(SyncStatus.Current(syncedLocal), toSyncState(info, null, moving, SourceKind.SMB).status)
        zone = ZoneId.of("Asia/Tokyo")
        assertEquals(SyncStatus.Current(syncedLocal.plusHours(9)), toSyncState(info, null, moving, SourceKind.SMB).status)
    }

    @Test
    fun `without a successful sync no date is shown, not even the last problem or now`() {
        val withProblem = testSyncInfo(
            openedVersion = OpenedVersion.OFFLINE_COPY,
            lastSuccessfulSync = null,
            lastProblem = SyncProblem.UNREACHABLE,
            lastProblemAt = Instant.parse("2026-10-30T18:30:00Z"),
        )
        assertEquals(
            SyncStatus.OfflineCopy(null, OfflineReason.ShareUnreachable),
            toSyncState(withProblem, null, time, SourceKind.SMB).status,
        )

        val nothing = testSyncInfo(openedVersion = OpenedVersion.OFFLINE_COPY, lastSuccessfulSync = null)
        assertEquals(
            SyncStatus.OfflineCopy(null, OfflineReason.ShareUnreachable),
            toSyncState(nothing, null, time, SourceKind.SMB).status,
        )

        assertEquals(SyncStatus.Current(null), toSyncState(testSyncInfo(lastSuccessfulSync = null), null, time, SourceKind.SMB).status)
    }

    @Test
    fun `list banner without a successful sync has no date`() {
        val info = testSyncInfo(
            openedVersion = OpenedVersion.OFFLINE_COPY,
            lastSuccessfulSync = null,
            lastProblemAt = Instant.parse("2026-10-30T18:30:00Z"),
        )
        assertEquals(
            ListBanner.NewVersionRejected(null),
            toListBanner(info, unlockedView(newVersionRejected = true), dismissed = false, time = time, source = smb),
        )
    }

    @Test
    fun `a running sync without a recorded problem shows no offline reason`() {
        val running = testSyncInfo(openedVersion = OpenedVersion.OFFLINE_COPY, syncing = true)
        val state = toSyncState(running, unlockedView(), time, SourceKind.SMB)
        assertEquals(SyncState(SyncStatus.OfflineCopy(syncedLocal, null), syncing = true), state)
        // Before the first unlock, too.
        assertNull((toSyncState(testSyncInfo(openedVersion = null, syncing = true), null, time, SourceKind.SMB).status as SyncStatus.OfflineCopy).reason)
    }

    @Test
    fun `a running sync keeps the recorded problem, a pending question and a rejected version`() {
        fun reason(problem: SyncProblem?, serverChange: Boolean = false, rejected: Boolean = false): OfflineReason? {
            val info = testSyncInfo(
                openedVersion = OpenedVersion.OFFLINE_COPY,
                lastProblem = problem,
                syncing = true,
                serverChange = if (serverChange) testServerChange() else null,
            )
            return (toSyncState(info, unlockedView(newVersionRejected = rejected), time, SourceKind.SMB).status as SyncStatus.OfflineCopy).reason
        }
        assertEquals(OfflineReason.ShareUnreachable, reason(SyncProblem.UNREACHABLE))
        assertEquals(OfflineReason.LoginFailed, reason(SyncProblem.AUTH_FAILED))
        assertEquals(OfflineReason.OtherServer, reason(problem = null, serverChange = true))
        assertEquals(OfflineReason.NewVersionRejected, reason(problem = null, rejected = true))
    }

    @Test
    fun `list banner shows the server question first`() {
        val info = testSyncInfo(serverChange = testServerChange(host = "10.0.0.5"))
        assertEquals(ListBanner.OtherServer("10.0.0.5"), toListBanner(info, unlockedView(), dismissed = false, time = time, source = smb))
        assertEquals(
            ListBanner.OtherServer("10.0.0.5"),
            toListBanner(info, unlockedView(newVersionRejected = true), dismissed = false, time = time, source = smb),
        )
        // The server question cannot be dismissed like the rejected-version banner.
        assertEquals(
            ListBanner.OtherServer("10.0.0.5"),
            toListBanner(info, unlockedView(newVersionRejected = true), dismissed = true, time = time, source = smb),
        )
    }

    @Test
    fun `list banner shows the rejected version until dismissed`() {
        val info = testSyncInfo(openedVersion = OpenedVersion.OFFLINE_COPY)
        val rejected = unlockedView(newVersionRejected = true)
        assertEquals(ListBanner.NewVersionRejected(syncedLocal), toListBanner(info, rejected, dismissed = false, time = time, source = smb))
        assertNull(toListBanner(info, rejected, dismissed = true, time = time, source = smb))
    }

    @Test
    fun `list banner is empty without a problem`() {
        assertNull(toListBanner(testSyncInfo(), unlockedView(), dismissed = false, time = time, source = smb))
    }

    @Test
    fun `other server info uses the remembered name`() {
        val remembered = UUID.fromString("74696572-6e61-2d6f-6e61-730000000000")
        val now = UUID.fromString("9b2f6c1e-3d4a-4e5f-8a7b-0c1d2e3f4a5b")
        val info = toOtherServerInfo(
            testServerChange(host = "10.0.0.5", rememberedGuid = remembered, rememberedName = "reitano-nas", newGuid = now, newName = "neu-nas"),
        )
        assertEquals("10.0.0.5", info.host)
        assertEquals("reitano-nas", info.rememberedName)
        assertEquals(remembered.toString(), info.rememberedGuid)
        assertEquals("neu-nas", info.currentName)
        assertEquals(now.toString(), info.currentGuid)
    }

    @Test
    fun `remembered name falls back to the GUID, then to the host`() {
        val guid = UUID.fromString("9b2f6c1e-3d4a-4e5f-8a7b-0c1d2e3f4a5b")
        val byGuid = toOtherServerInfo(testServerChange(rememberedGuid = guid, rememberedName = null))
        assertEquals(guid.toString(), byGuid.rememberedName)

        val byHost = toOtherServerInfo(testServerChange(host = "nas.local", rememberedGuid = null, rememberedName = null))
        assertEquals("nas.local", byHost.rememberedName)
        assertEquals("", byHost.rememberedGuid)
        assertNull(byHost.currentName)
    }

    // ---- file source ----

    @Test
    fun `file source offline reasons by problem`() {
        assertEquals(OfflineReason.LocalFileNotFound, reasonOf(SyncProblem.NOT_FOUND, kind = SourceKind.FILE))
        assertEquals(OfflineReason.LocalFileNoAccess, reasonOf(SyncProblem.ACCESS_DENIED, kind = SourceKind.FILE))
        // Other technical problems and the unknown state: "Offline-Kopie" without a reason (D6).
        for (problem in listOf(
            null,
            SyncProblem.REMOTE_ERROR,
            SyncProblem.DOWNLOAD_INCOMPLETE,
            SyncProblem.INVALID_FILE,
            SyncProblem.LOCAL_STORAGE,
            SyncProblem.UNREACHABLE,
        )) {
            assertEquals(OfflineReason.LocalFileProblem, reasonOf(problem, kind = SourceKind.FILE), "$problem")
        }
    }

    @Test
    fun `file source rejected version and source problem ranking`() {
        assertEquals(OfflineReason.NewVersionRejected, reasonOf(problem = null, rejected = true, kind = SourceKind.FILE))
        assertEquals(OfflineReason.NewVersionRejected, reasonOf(SyncProblem.NEW_VERSION_REJECTED, kind = SourceKind.FILE))
        assertEquals(OfflineReason.LocalFileNotFound, reasonOf(SyncProblem.NOT_FOUND, rejected = true, kind = SourceKind.FILE))
    }

    @Test
    fun `file source running sync without problem shows no reason, with a problem it keeps it`() {
        val running = testSyncInfo(openedVersion = OpenedVersion.OFFLINE_COPY, syncing = true)
        assertNull((toSyncState(running, unlockedView(), time, SourceKind.FILE).status as SyncStatus.OfflineCopy).reason)
        val withProblem = testSyncInfo(
            openedVersion = OpenedVersion.OFFLINE_COPY,
            lastProblem = SyncProblem.NOT_FOUND,
            syncing = true,
        )
        assertEquals(
            OfflineReason.LocalFileNotFound,
            (toSyncState(withProblem, unlockedView(), time, SourceKind.FILE).status as SyncStatus.OfflineCopy).reason,
        )
    }

    @Test
    fun `sync state carries the source kind`() {
        assertEquals(
            SyncState(SyncStatus.Current(syncedLocal), kind = SourceKind.FILE),
            toSyncState(testSyncInfo(), unlockedView(), time, SourceKind.FILE),
        )
        assertEquals(SourceKind.SMB, toSyncState(testSyncInfo(), unlockedView(), time, SourceKind.SMB).kind)
    }

    @Test
    fun `source kind of the settings`() {
        assertEquals(SourceKind.SMB, sourceKindOf(KpmSettings()))
        assertEquals(SourceKind.SMB, sourceKindOf(KpmSettings(source = smb)))
        assertEquals(SourceKind.FILE, sourceKindOf(KpmSettings(source = file)))
    }

    // ---- source problems and list banner ranking (UI-09) ----

    private fun problemInfo(problem: SyncProblem?) =
        testSyncInfo(openedVersion = OpenedVersion.OFFLINE_COPY, lastProblem = problem, lastProblemAt = synced)

    @Test
    fun `source problem of a file`() {
        assertEquals(SourceProblem.FILE_NOT_FOUND, sourceProblemOf(problemInfo(SyncProblem.NOT_FOUND), SourceKind.FILE))
        assertEquals(SourceProblem.FILE_NO_ACCESS, sourceProblemOf(problemInfo(SyncProblem.ACCESS_DENIED), SourceKind.FILE))
        for (problem in SyncProblem.entries - setOf(SyncProblem.NOT_FOUND, SyncProblem.ACCESS_DENIED)) {
            assertNull(sourceProblemOf(problemInfo(problem), SourceKind.FILE), "$problem")
        }
        assertNull(sourceProblemOf(problemInfo(null), SourceKind.FILE))
    }

    @Test
    fun `source problem of an SMB share`() {
        assertEquals(SourceProblem.SMB_NOT_FOUND, sourceProblemOf(problemInfo(SyncProblem.NOT_FOUND), SourceKind.SMB))
        assertEquals(SourceProblem.SMB_NO_ACCESS, sourceProblemOf(problemInfo(SyncProblem.ACCESS_DENIED), SourceKind.SMB))
        assertEquals(SourceProblem.SMB_LOGIN_FAILED, sourceProblemOf(problemInfo(SyncProblem.AUTH_FAILED), SourceKind.SMB))
        // Only unreachable is normal offline use: no banner. The same for the other technical problems.
        for (problem in SyncProblem.entries - setOf(SyncProblem.NOT_FOUND, SyncProblem.ACCESS_DENIED, SyncProblem.AUTH_FAILED)) {
            assertNull(sourceProblemOf(problemInfo(problem), SourceKind.SMB), "$problem")
        }
    }

    @Test
    fun `a pending server question is no source problem`() {
        val info = testSyncInfo(lastProblem = SyncProblem.AUTH_FAILED, serverChange = testServerChange())
        assertNull(sourceProblemOf(info, SourceKind.SMB))
    }

    @Test
    fun `source problems and their actions`() {
        assertEquals(SourceAction.RepickFile, SourceProblem.FILE_NOT_FOUND.action)
        assertEquals(SourceAction.RepickFile, SourceProblem.FILE_NO_ACCESS.action)
        assertEquals(SourceAction.EditSmb, SourceProblem.SMB_NOT_FOUND.action)
        assertEquals(SourceAction.EditSmb, SourceProblem.SMB_NO_ACCESS.action)
        assertEquals(SourceAction.EditSmb, SourceProblem.SMB_LOGIN_FAILED.action)
    }

    @Test
    fun `list banner for a missing file carries the copy date and no user`() {
        assertEquals(
            ListBanner.SourceMissing(SourceProblem.FILE_NOT_FOUND, syncedLocal, null),
            toListBanner(problemInfo(SyncProblem.NOT_FOUND), unlockedView(), dismissed = false, time = time, source = file),
        )
        assertEquals(
            ListBanner.SourceMissing(SourceProblem.FILE_NO_ACCESS, syncedLocal, null),
            toListBanner(problemInfo(SyncProblem.ACCESS_DENIED), unlockedView(), dismissed = false, time = time, source = file),
        )
    }

    @Test
    fun `list banner for SMB problems carries the user`() {
        assertEquals(
            ListBanner.SourceMissing(SourceProblem.SMB_NOT_FOUND, syncedLocal, "kpmtest"),
            toListBanner(problemInfo(SyncProblem.NOT_FOUND), unlockedView(), dismissed = false, time = time, source = smb),
        )
        assertEquals(
            ListBanner.SourceMissing(SourceProblem.SMB_NO_ACCESS, syncedLocal, "kpmtest"),
            toListBanner(problemInfo(SyncProblem.ACCESS_DENIED), unlockedView(), dismissed = false, time = time, source = smb),
        )
        assertEquals(
            ListBanner.SourceMissing(SourceProblem.SMB_LOGIN_FAILED, syncedLocal, "kpmtest"),
            toListBanner(problemInfo(SyncProblem.AUTH_FAILED), unlockedView(), dismissed = false, time = time, source = smb),
        )
    }

    @Test
    fun `a missing source ranks above the rejected version and cannot be dismissed`() {
        val rejected = unlockedView(newVersionRejected = true)
        val info = problemInfo(SyncProblem.NOT_FOUND)
        val expected = ListBanner.SourceMissing(SourceProblem.FILE_NOT_FOUND, syncedLocal, null)
        assertEquals(expected, toListBanner(info, rejected, dismissed = false, time = time, source = file))
        assertEquals(expected, toListBanner(info, rejected, dismissed = true, time = time, source = file))
    }

    @Test
    fun `the server question ranks above a missing source`() {
        val info = testSyncInfo(
            openedVersion = OpenedVersion.OFFLINE_COPY,
            lastProblem = SyncProblem.AUTH_FAILED,
            serverChange = testServerChange(host = "10.0.0.5"),
        )
        assertEquals(
            ListBanner.OtherServer("10.0.0.5"),
            toListBanner(info, unlockedView(), dismissed = false, time = time, source = smb),
        )
    }

    @Test
    fun `only unreachable and other technical problems show no banner`() {
        val technical = listOf(SyncProblem.UNREACHABLE, SyncProblem.REMOTE_ERROR, SyncProblem.INVALID_FILE, SyncProblem.LOCAL_STORAGE)
        for (problem in technical) {
            assertNull(toListBanner(problemInfo(problem), unlockedView(), dismissed = false, time = time, source = smb), "$problem")
            assertNull(toListBanner(problemInfo(problem), unlockedView(), dismissed = false, time = time, source = file), "$problem")
        }
    }

    @Test
    fun `the rejected version banner of a file source carries the kind`() {
        val info = testSyncInfo(openedVersion = OpenedVersion.OFFLINE_COPY)
        assertEquals(
            ListBanner.NewVersionRejected(syncedLocal, SourceKind.FILE),
            toListBanner(info, unlockedView(newVersionRejected = true), dismissed = false, time = time, source = file),
        )
    }

    @Test
    fun `a source problem banner without a successful sync has no date`() {
        val info = testSyncInfo(
            openedVersion = OpenedVersion.OFFLINE_COPY,
            lastSuccessfulSync = null,
            lastProblem = SyncProblem.NOT_FOUND,
        )
        assertEquals(
            ListBanner.SourceMissing(SourceProblem.FILE_NOT_FOUND, null, null),
            toListBanner(info, unlockedView(), dismissed = false, time = time, source = file),
        )
    }

    @Test
    fun `older version info maps the instants to local time`() {
        val older = OlderVersion(
            fileName = "Passwoerter.kdbx",
            fileModified = Instant.parse("2026-09-12T16:05:00Z"),
            openModified = Instant.parse("2026-10-31T20:38:00Z"),
        )
        val info = toOlderVersionInfo(older, time)
        assertEquals("Passwoerter.kdbx", info.fileName)
        assertEquals(LocalDateTime.of(2026, 9, 12, 16, 5), info.fileModified)
        assertEquals(LocalDateTime.of(2026, 10, 31, 20, 38), info.openModified)
    }
}
