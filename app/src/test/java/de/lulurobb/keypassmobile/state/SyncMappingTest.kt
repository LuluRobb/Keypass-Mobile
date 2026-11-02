package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.sync.OpenedVersion
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.ui.components.OfflineReason
import de.lulurobb.keypassmobile.ui.components.SyncState
import de.lulurobb.keypassmobile.ui.components.SyncStatus
import de.lulurobb.keypassmobile.ui.list.ListBanner
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
    private val synced = Instant.parse("2026-11-01T10:00:00Z")
    private val syncedLocal = LocalDateTime.of(2026, 11, 1, 10, 0)

    private fun reasonOf(
        problem: SyncProblem? = null,
        serverChange: Boolean = false,
        rejected: Boolean = false,
    ): OfflineReason? {
        val info = testSyncInfo(
            openedVersion = OpenedVersion.OFFLINE_COPY,
            lastProblem = problem,
            lastProblemAt = synced,
            serverChange = if (serverChange) testServerChange() else null,
        )
        val state = toSyncState(info, unlockedView(newVersionRejected = rejected), time)
        return (state.status as SyncStatus.OfflineCopy).reason
    }

    @Test
    fun `version of the share open is current with the last successful sync`() {
        val state = toSyncState(testSyncInfo(), unlockedView(), time)
        assertEquals(SyncState(SyncStatus.Current(syncedLocal), syncing = false), state)
    }

    @Test
    fun `syncing flag is passed on`() {
        assertEquals(true, toSyncState(testSyncInfo(syncing = true), null, time).syncing)
        assertEquals(false, toSyncState(testSyncInfo(syncing = false), null, time).syncing)
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
            toSyncState(info, unlockedView(), time),
        )
    }

    @Test
    fun `before the first unlock the offline copy is shown`() {
        val info = testSyncInfo(openedVersion = null)
        assertEquals(
            SyncStatus.OfflineCopy(syncedLocal, OfflineReason.ShareUnreachable),
            toSyncState(info, null, time).status,
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
        assertEquals(OfflineReason.NewVersionRejected, reasonOf(SyncProblem.AUTH_FAILED, rejected = true))
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
                toSyncState(info, unlockedView(), time),
                "$problem",
            )
        }
    }

    @Test
    fun `the open share version is current again once the problem is cleared by a successful sync`() {
        val failed = testSyncInfo(openedVersion = OpenedVersion.SHARE, lastProblem = SyncProblem.UNREACHABLE)
        assertEquals(
            SyncStatus.OfflineCopy(syncedLocal, OfflineReason.ShareUnreachable),
            toSyncState(failed, unlockedView(), time).status,
        )
        val recovered = testSyncInfo(
            openedVersion = OpenedVersion.SHARE,
            lastSuccessfulSync = Instant.parse("2026-11-02T08:00:00Z"),
            lastProblem = null,
        )
        assertEquals(
            SyncStatus.Current(LocalDateTime.of(2026, 11, 2, 8, 0)),
            toSyncState(recovered, unlockedView(), time).status,
        )
    }

    @Test
    fun `a running sync with a recorded problem keeps the offline copy, without a problem the share version stays current`() {
        val failed = testSyncInfo(openedVersion = OpenedVersion.SHARE, lastProblem = SyncProblem.UNREACHABLE, syncing = true)
        assertEquals(
            SyncState(SyncStatus.OfflineCopy(syncedLocal, OfflineReason.ShareUnreachable), syncing = true),
            toSyncState(failed, unlockedView(), time),
        )
        val clean = testSyncInfo(openedVersion = OpenedVersion.SHARE, syncing = true)
        assertEquals(
            SyncState(SyncStatus.Current(syncedLocal), syncing = true),
            toSyncState(clean, unlockedView(), time),
        )
    }

    @Test
    fun `a pending server question turns the open share version into the offline copy`() {
        val info = testSyncInfo(serverChange = testServerChange())
        assertEquals(
            SyncStatus.OfflineCopy(syncedLocal, OfflineReason.OtherServer),
            toSyncState(info, unlockedView(), time).status,
        )
    }

    @Test
    fun `instants are shown in the system zone`() {
        val berlin = testTime(zone = ZoneId.of("Europe/Berlin"))
        val summer = testSyncInfo(lastSuccessfulSync = Instant.parse("2026-07-01T10:00:00Z"))
        val winter = testSyncInfo(lastSuccessfulSync = Instant.parse("2026-11-01T10:00:00Z"))
        assertEquals(SyncStatus.Current(LocalDateTime.of(2026, 7, 1, 12, 0)), toSyncState(summer, null, berlin).status)
        assertEquals(SyncStatus.Current(LocalDateTime.of(2026, 11, 1, 11, 0)), toSyncState(winter, null, berlin).status)
    }

    @Test
    fun `the zone is read at every call`() {
        var zone: ZoneId = ZoneId.of("UTC")
        val moving = TimeSource(Clock.systemUTC()) { zone }
        val info = testSyncInfo()
        assertEquals(SyncStatus.Current(syncedLocal), toSyncState(info, null, moving).status)
        zone = ZoneId.of("Asia/Tokyo")
        assertEquals(SyncStatus.Current(syncedLocal.plusHours(9)), toSyncState(info, null, moving).status)
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
            toSyncState(withProblem, null, time).status,
        )

        val nothing = testSyncInfo(openedVersion = OpenedVersion.OFFLINE_COPY, lastSuccessfulSync = null)
        assertEquals(
            SyncStatus.OfflineCopy(null, OfflineReason.ShareUnreachable),
            toSyncState(nothing, null, time).status,
        )

        assertEquals(SyncStatus.Current(null), toSyncState(testSyncInfo(lastSuccessfulSync = null), null, time).status)
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
            toListBanner(info, unlockedView(newVersionRejected = true), dismissed = false, time = time),
        )
    }

    @Test
    fun `a running sync without a recorded problem shows no offline reason`() {
        val running = testSyncInfo(openedVersion = OpenedVersion.OFFLINE_COPY, syncing = true)
        val state = toSyncState(running, unlockedView(), time)
        assertEquals(SyncState(SyncStatus.OfflineCopy(syncedLocal, null), syncing = true), state)
        // Before the first unlock, too.
        assertNull((toSyncState(testSyncInfo(openedVersion = null, syncing = true), null, time).status as SyncStatus.OfflineCopy).reason)
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
            return (toSyncState(info, unlockedView(newVersionRejected = rejected), time).status as SyncStatus.OfflineCopy).reason
        }
        assertEquals(OfflineReason.ShareUnreachable, reason(SyncProblem.UNREACHABLE))
        assertEquals(OfflineReason.LoginFailed, reason(SyncProblem.AUTH_FAILED))
        assertEquals(OfflineReason.OtherServer, reason(problem = null, serverChange = true))
        assertEquals(OfflineReason.NewVersionRejected, reason(problem = null, rejected = true))
    }

    @Test
    fun `list banner shows the server question first`() {
        val info = testSyncInfo(serverChange = testServerChange(host = "10.0.0.5"))
        assertEquals(ListBanner.OtherServer("10.0.0.5"), toListBanner(info, unlockedView(), dismissed = false, time = time))
        assertEquals(
            ListBanner.OtherServer("10.0.0.5"),
            toListBanner(info, unlockedView(newVersionRejected = true), dismissed = false, time = time),
        )
        // The server question cannot be dismissed like the rejected-version banner.
        assertEquals(
            ListBanner.OtherServer("10.0.0.5"),
            toListBanner(info, unlockedView(newVersionRejected = true), dismissed = true, time = time),
        )
    }

    @Test
    fun `list banner shows the rejected version until dismissed`() {
        val info = testSyncInfo(openedVersion = OpenedVersion.OFFLINE_COPY)
        val rejected = unlockedView(newVersionRejected = true)
        assertEquals(ListBanner.NewVersionRejected(syncedLocal), toListBanner(info, rejected, dismissed = false, time = time))
        assertNull(toListBanner(info, rejected, dismissed = true, time = time))
    }

    @Test
    fun `list banner is empty without a problem`() {
        assertNull(toListBanner(testSyncInfo(), unlockedView(), dismissed = false, time = time))
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
}
