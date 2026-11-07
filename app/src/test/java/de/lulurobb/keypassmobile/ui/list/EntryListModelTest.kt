package de.lulurobb.keypassmobile.ui.list

import de.lulurobb.keypassmobile.core.sync.OpenedVersion
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.settings.FileSource
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.SmbSource
import de.lulurobb.keypassmobile.platform.settings.SourceKind
import de.lulurobb.keypassmobile.state.AppMessages
import de.lulurobb.keypassmobile.state.FakeAppPlatform
import de.lulurobb.keypassmobile.state.SessionView
import de.lulurobb.keypassmobile.state.testEntry
import de.lulurobb.keypassmobile.state.testServerChange
import de.lulurobb.keypassmobile.state.testSyncInfo
import de.lulurobb.keypassmobile.state.testTime
import de.lulurobb.keypassmobile.state.testVault
import de.lulurobb.keypassmobile.state.unlockedView
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.components.OfflineReason
import de.lulurobb.keypassmobile.ui.components.SyncStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class EntryListModelTest {

    private val paypal = testEntry("PayPal", username = "anna", group = listOf("Finanzen"))
    private val bank = testEntry("Bank", group = listOf("Finanzen"))
    private val shop = testEntry("Shop", group = listOf("Einkauf"))

    private val platform = FakeAppPlatform(session = unlockedView(testVault(listOf(paypal, bank, shop))))
    private val messages = AppMessages()

    private fun TestScope.model(): EntryListModel {
        val model = EntryListModel(
            platform,
            backgroundScope,
            testTime(),
            messages,
            StandardTestDispatcher(testScheduler),
        )
        runCurrent()
        return model
    }

    private fun EntryListModel.state() = assertNotNull(view.value).state

    @Test
    fun `no query shows all sections with the total`() = runTest {
        val model = model()
        val state = model.state()
        val content = assertIs<EntryListContent.Grouped>(state.content)
        assertEquals(3, content.total)
        assertEquals(listOf("Einkauf", "Finanzen"), content.sections.map { it.groupPath.single() })
        assertEquals(listOf("Einkauf", "Finanzen"), state.topGroups)
        assertEquals("Root", state.rootGroupName)
        assertNull(state.groupFilter)
    }

    @Test
    fun `query is immediate, the results follow after the computation`() = runTest {
        val model = model()
        model.onQueryChange("pay")
        assertEquals("pay", model.query.value)
        assertIs<EntryListContent.Grouped>(model.state().content)
        runCurrent()
        val state = model.state()
        assertEquals("pay", state.query)
        val content = assertIs<EntryListContent.Search>(state.content)
        assertEquals(listOf(paypal), content.results)
        assertEquals(3, content.total)
    }

    @Test
    fun `blank query counts as no search`() = runTest {
        val model = model()
        model.onQueryChange("   ")
        runCurrent()
        assertIs<EntryListContent.Grouped>(model.state().content)
    }

    @Test
    fun `group filter limits sections and totals, also during a search`() = runTest {
        val model = model()
        model.onGroupFilterChange("Finanzen")
        runCurrent()
        var state = model.state()
        assertEquals("Finanzen", state.groupFilter)
        assertEquals(2, assertIs<EntryListContent.Grouped>(state.content).total)

        model.onQueryChange("shop")
        runCurrent()
        state = model.state()
        val content = assertIs<EntryListContent.Search>(state.content)
        assertEquals(emptyList(), content.results)
        assertEquals(2, content.total)
    }

    @Test
    fun `a filter whose group vanished counts as Alle`() = runTest {
        val model = model()
        model.onGroupFilterChange("Finanzen")
        runCurrent()
        platform.sessionFlow.value = unlockedView(testVault(listOf(shop)), version = 2)
        runCurrent()
        val state = model.state()
        assertNull(state.groupFilter)
        assertEquals(1, assertIs<EntryListContent.Grouped>(state.content).total)
    }

    @Test
    fun `locked session has no view`() = runTest {
        platform.sessionFlow.value = SessionView.Locked(null)
        val model = model()
        assertNull(model.view.value)
    }

    @Test
    fun `lock resets query, filter and dismissed banner and drops the view`() = runTest {
        platform.sessionFlow.value = unlockedView(testVault(listOf(paypal)), newVersionRejected = true)
        val model = model()
        model.onQueryChange("pay")
        model.onGroupFilterChange("Finanzen")
        model.onBannerDismiss()
        runCurrent()
        assertNull(model.state().banner)

        platform.lock()
        runCurrent()
        assertNull(model.view.value)
        assertEquals("", model.query.value)
        assertNull(model.groupFilter.value)

        platform.sessionFlow.value = unlockedView(testVault(listOf(paypal)), newVersionRejected = true)
        runCurrent()
        val state = model.state()
        assertEquals("", state.query)
        assertIs<EntryListContent.Grouped>(state.content)
        assertIs<ListBanner.NewVersionRejected>(state.banner)
    }

    @Test
    fun `server question beats the rejected version, dismiss hides only the latter`() = runTest {
        platform.sessionFlow.value = unlockedView(testVault(listOf(paypal)), newVersionRejected = true)
        val model = model()
        assertIs<ListBanner.NewVersionRejected>(model.state().banner)

        platform.syncFlow.value = testSyncInfo(serverChange = testServerChange(host = "nas.local"))
        runCurrent()
        assertEquals(ListBanner.OtherServer("nas.local"), model.state().banner)

        model.onBannerDismiss()
        runCurrent()
        assertEquals(ListBanner.OtherServer("nas.local"), model.state().banner)

        platform.syncFlow.value = testSyncInfo()
        runCurrent()
        assertNull(model.state().banner)
    }

    @Test
    fun `sync state follows the platform`() = runTest {
        val model = model()
        assertEquals(false, model.state().sync.syncing)
        platform.syncFlow.value = testSyncInfo(syncing = true)
        runCurrent()
        assertEquals(true, model.state().sync.syncing)

        platform.syncFlow.value = testSyncInfo(openedVersion = OpenedVersion.OFFLINE_COPY, lastProblem = SyncProblem.UNREACHABLE)
        runCurrent()
        assertIs<SyncStatus.OfflineCopy>(model.state().sync.status)
    }

    @Test
    fun `sync now posts the result message`() = runTest {
        val model = model()
        platform.syncResults += SyncResult.Updated
        model.onSyncNow()
        runCurrent()
        assertEquals(UiMessage.SyncUpdated, messages.message.value)

        platform.syncResults += SyncResult.Failed(SyncProblem.UNREACHABLE)
        model.onSyncNow()
        runCurrent()
        assertEquals(UiMessage.SyncUnreachable, messages.message.value)
    }

    @Test
    fun `sync now says nothing when the banner shows the result`() = runTest {
        val model = model()
        platform.syncResults += SyncResult.NewVersionRejected
        model.onSyncNow()
        runCurrent()
        assertNull(messages.message.value)
    }

    @Test
    fun `sync now does not start a second run while one is running`() = runTest {
        val model = model()
        platform.syncFlow.value = testSyncInfo(syncing = true)
        model.onSyncNow()
        runCurrent()
        assertEquals(emptyList(), platform.calls.filter { it == "syncNow" })
    }

    @Test
    fun `details open the dialog with the server info, reject closes it and keeps the banner`() = runTest {
        platform.syncFlow.value = testSyncInfo(serverChange = testServerChange(host = "nas.local", newName = "neu"))
        val model = model()
        assertNull(model.serverDialog.value)

        model.onBannerDetails()
        runCurrent()
        val info = assertNotNull(model.serverDialog.value)
        assertEquals("nas.local", info.host)
        assertEquals("reitano-nas", info.rememberedName)
        assertEquals("neu", info.currentName)

        model.onServerReject()
        runCurrent()
        assertNull(model.serverDialog.value)
        assertEquals(ListBanner.OtherServer("nas.local"), model.state().banner)
        assertEquals(emptyList(), platform.calls.filter { it == "trustNewServer" })
    }

    @Test
    fun `accept trusts the server and posts ServerAccepted`() = runTest {
        platform.syncFlow.value = testSyncInfo(serverChange = testServerChange())
        val model = model()
        model.onBannerDetails()
        runCurrent()
        platform.trustResults += SyncResult.UpToDate
        model.onServerAccept()
        runCurrent()
        assertNull(model.serverDialog.value)
        assertEquals(listOf("trustNewServer"), platform.calls.filter { it == "trustNewServer" })
        assertEquals(UiMessage.ServerAccepted, messages.message.value)
    }

    @Test
    fun `accept with a failing sync posts the sync message`() = runTest {
        platform.syncFlow.value = testSyncInfo(serverChange = testServerChange())
        val model = model()
        platform.trustResults += SyncResult.Failed(SyncProblem.UNREACHABLE)
        model.onServerAccept()
        runCurrent()
        assertEquals(UiMessage.SyncUnreachable, messages.message.value)
    }

    @Test
    fun `dialog closes when the server question is gone`() = runTest {
        platform.syncFlow.value = testSyncInfo(serverChange = testServerChange())
        val model = model()
        model.onBannerDetails()
        runCurrent()
        assertNotNull(model.serverDialog.value)
        platform.syncFlow.value = testSyncInfo()
        runCurrent()
        assertNull(model.serverDialog.value)
    }

    @Test
    fun `lock button locks the platform`() = runTest {
        val model = model()
        model.onLock()
        runCurrent()
        assertEquals(listOf("lock"), platform.calls)
        assertNull(model.view.value)
    }

    // ---- source type: banner, chip kind and messages ----

    private val smb = SmbSource("nas.local", 445, "", "kpmtest", "daten", listOf("KeePass", "pw.kdbx"))
    private val file = FileSource("content://docs/doc/1", "Passwoerter.kdbx", "Interner Speicher / Syncthing")

    @Test
    fun `a missing file shows the source banner of a file and the file kind in the sync state`() = runTest {
        platform.settingsFlow.value = KpmSettings(source = file)
        platform.syncFlow.value = testSyncInfo(openedVersion = OpenedVersion.OFFLINE_COPY, lastProblem = SyncProblem.NOT_FOUND)
        val model = model()
        val state = model.state()
        assertEquals(SourceKind.FILE, state.sync.kind)
        val banner = assertIs<ListBanner.SourceMissing>(state.banner)
        assertEquals(SourceProblem.FILE_NOT_FOUND, banner.problem)
        assertNull(banner.smbUser)
        assertEquals(
            SyncStatus.OfflineCopy(banner.copyFrom, OfflineReason.LocalFileNotFound),
            state.sync.status,
        )
    }

    @Test
    fun `a failed SMB login shows the source banner with the user`() = runTest {
        platform.settingsFlow.value = KpmSettings(source = smb)
        platform.syncFlow.value = testSyncInfo(openedVersion = OpenedVersion.OFFLINE_COPY, lastProblem = SyncProblem.AUTH_FAILED)
        val model = model()
        val banner = assertIs<ListBanner.SourceMissing>(model.state().banner)
        assertEquals(SourceProblem.SMB_LOGIN_FAILED, banner.problem)
        assertEquals("kpmtest", banner.smbUser)
        assertEquals(SourceKind.SMB, model.state().sync.kind)
    }

    @Test
    fun `the source banner replaces the rejected version banner and cannot be dismissed`() = runTest {
        platform.settingsFlow.value = KpmSettings(source = file)
        platform.sessionFlow.value = unlockedView(testVault(listOf(paypal)), newVersionRejected = true)
        val model = model()
        assertEquals(SourceKind.FILE, assertIs<ListBanner.NewVersionRejected>(model.state().banner).kind)

        platform.syncFlow.value = testSyncInfo(openedVersion = OpenedVersion.OFFLINE_COPY, lastProblem = SyncProblem.ACCESS_DENIED)
        runCurrent()
        assertEquals(SourceProblem.FILE_NO_ACCESS, assertIs<ListBanner.SourceMissing>(model.state().banner).problem)

        model.onBannerDismiss()
        runCurrent()
        assertIs<ListBanner.SourceMissing>(model.state().banner)

        // Source works again: the normal rules apply (the dismissed rejected banner stays hidden).
        platform.syncFlow.value = testSyncInfo()
        runCurrent()
        assertNull(model.state().banner)
    }

    @Test
    fun `an unreachable share shows no banner`() = runTest {
        platform.settingsFlow.value = KpmSettings(source = smb)
        platform.syncFlow.value = testSyncInfo(openedVersion = OpenedVersion.OFFLINE_COPY, lastProblem = SyncProblem.UNREACHABLE)
        val model = model()
        assertNull(model.state().banner)
    }

    @Test
    fun `the banner follows a switch of the source type`() = runTest {
        platform.settingsFlow.value = KpmSettings(source = smb)
        platform.syncFlow.value = testSyncInfo(openedVersion = OpenedVersion.OFFLINE_COPY, lastProblem = SyncProblem.NOT_FOUND)
        val model = model()
        assertEquals(SourceProblem.SMB_NOT_FOUND, assertIs<ListBanner.SourceMissing>(model.state().banner).problem)
        platform.settingsFlow.value = KpmSettings(source = file)
        runCurrent()
        assertEquals(SourceProblem.FILE_NOT_FOUND, assertIs<ListBanner.SourceMissing>(model.state().banner).problem)
    }

    @Test
    fun `sync now of a file source posts the file messages`() = runTest {
        platform.settingsFlow.value = KpmSettings(source = file)
        val model = model()
        platform.syncResults += SyncResult.UpToDate
        model.onSyncNow()
        runCurrent()
        assertEquals(UiMessage.SyncFileUnchanged, messages.message.value)

        platform.syncResults += SyncResult.Updated
        model.onSyncNow()
        runCurrent()
        assertEquals(UiMessage.SyncUpdatedFile, messages.message.value)

        platform.syncResults += SyncResult.Failed(SyncProblem.NOT_FOUND)
        model.onSyncNow()
        runCurrent()
        assertEquals(UiMessage.SyncFileNotFound, messages.message.value)

        platform.syncResults += SyncResult.Failed(SyncProblem.ACCESS_DENIED)
        model.onSyncNow()
        runCurrent()
        assertEquals(UiMessage.SyncFileNoAccess, messages.message.value)
    }
}
