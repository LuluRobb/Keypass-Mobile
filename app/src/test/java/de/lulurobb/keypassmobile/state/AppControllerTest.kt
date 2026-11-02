package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.settings.IdleTimeout
import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.biometric.FingerprintAvailability
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.platform.session.PasswordRequiredReason
import de.lulurobb.keypassmobile.platform.session.SyncReport
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.session.SyncTrigger
import de.lulurobb.keypassmobile.platform.session.UnlockOffer
import de.lulurobb.keypassmobile.platform.session.UnlockOutcome
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.settings.SourceConfig
import de.lulurobb.keypassmobile.platform.setup.ChooseResult
import de.lulurobb.keypassmobile.platform.setup.ConnectResult
import de.lulurobb.keypassmobile.platform.setup.ServerInfo
import de.lulurobb.keypassmobile.platform.setup.ShareEntry
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.detail.EntryDetailModel
import de.lulurobb.keypassmobile.ui.list.EntryListModel
import de.lulurobb.keypassmobile.ui.settings.SettingsModel
import de.lulurobb.keypassmobile.ui.setup.SourceFlowMode
import de.lulurobb.keypassmobile.ui.setup.SourceFlowModel
import de.lulurobb.keypassmobile.ui.setup.SourceFlowScreen
import de.lulurobb.keypassmobile.ui.unlock.UnlockModel
import de.lulurobb.keypassmobile.ui.unlock.UnlockProblem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerTest {

    private val setup = FakeSourceSetup()
    private val platform = FakeAppPlatform().also { it.nextSetup = { setup } }

    private val entryId = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
    private val vault = testVault(listOf(testEntry("Mail", id = entryId)))
    private val server = ServerInfo(
        name = "reitano-nas",
        guid = UUID.fromString("11111111-2222-3333-4444-555555555555"),
        dialect = "SMB 3.1.1",
        encrypted = true,
    )

    private fun source() = SourceConfig(
        host = "nas.local",
        port = 445,
        domain = "",
        user = "luca",
        share = "Docs",
        filePath = listOf("KeePass", "Db.kdbx"),
    )

    private val completed = KpmSettings(source = source(), setupCompleted = true)

    private fun TestScope.controller(): AppController {
        val controller = AppController(platform, backgroundScope, testTime(), StandardTestDispatcher(testScheduler))
        runCurrent()
        return controller
    }

    private val AppController.routeNow: AppRoute get() = screen.value.route

    private fun AppController.unlockModel(): UnlockModel = (screen.value as AppScreen.Unlock).model

    /** The lock notice on the unlock screen that is showing. */
    private fun AppController.lockNotice(): UiMessage? = unlockModel().state.value.lockNotice

    private fun AppController.sourceModel(): SourceFlowModel = (screen.value as AppScreen.Source).model

    /** Controller on an unlocked session of [vault] with the setup completed. */
    private fun TestScope.unlockedController(): AppController {
        platform.settingsFlow.value = completed
        platform.sessionFlow.value = unlockedView(vault)
        return controller()
    }

    /** Server form to browser of a first-run flow. */
    private fun TestScope.connectFirstRun(flow: SourceFlowModel) {
        setup.connectResults += ConnectResult.Connected(server, listOf(ShareEntry("Docs", "")))
        flow.onHostChange("nas.local")
        flow.onUserChange("luca")
        flow.readPassword = { "geheim".toCharArray() }
        flow.onConnect()
        runCurrent()
    }

    /** Browser to saved; the platform has the source before it answers. */
    private fun TestScope.saveFirstRun(flow: SourceFlowModel, size: Long = 4096L) {
        flow.onSelectFile(listOf("Docs", "KeePass", "Db.kdbx"))
        platform.settingsFlow.value = KpmSettings(source = source())
        setup.chooseResults += ChooseResult.Saved(size, true, SyncResult.UpToDate)
        flow.onUseFile()
        runCurrent()
    }

    /** Source change from the prefilled form to saved (both change modes). */
    private fun TestScope.saveChange(flow: SourceFlowModel) {
        setup.connectResults += ConnectResult.Connected(server, null)
        flow.readPassword = { "geheim".toCharArray() }
        flow.onConnect()
        runCurrent()
        flow.onUseFile()
        runCurrent()
    }

    private fun unlocked() = UnlockOutcome.Unlocked(false, null, null)

    // ---- first run ----

    @Test
    fun `first run goes from the server form over unlock and system setup to the list`() = runTest {
        platform.settingsFlow.value = KpmSettings()
        val controller = controller()
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Server), controller.routeNow)

        val flow = controller.sourceModel()
        connectFirstRun(flow)
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Browse), controller.routeNow)

        saveFirstRun(flow)
        assertEquals(AppRoute.Unlock(firstRun = true), controller.routeNow)
        val unlock = controller.unlockModel()
        assertEquals(4096L, unlock.state.value.firstRun?.fileSizeBytes)
        assertFalse(setup.closed)

        platform.unlockOutcomes += unlocked()
        platform.sessionOnUnlock = unlockedView(vault)
        unlock.submit(false, { "pw".toCharArray() }, {})
        runCurrent()
        assertEquals(AppRoute.SystemSetup, controller.routeNow)
        assertTrue(setup.closed)

        (controller.screen.value as AppScreen.SystemSetup).model.onFinish()
        runCurrent()
        assertTrue("completeSetup" in platform.calls)
        assertEquals(AppRoute.EntryList, controller.routeNow)
        assertEquals(UiMessage.SetupDone(false), controller.messages.message.value)

        // The setup is over: the next unlock screen is the normal one, on a new model.
        platform.lock()
        runCurrent()
        assertEquals(AppRoute.Unlock(firstRun = false), controller.routeNow)
        assertNotSame(unlock, controller.unlockModel())
        assertNull(controller.unlockModel().state.value.firstRun)
    }

    @Test
    fun `back from step 3 reopens the browser and a second save leads to step 3 again`() = runTest {
        platform.settingsFlow.value = KpmSettings()
        val controller = controller()
        val flow = controller.sourceModel()
        connectFirstRun(flow)
        saveFirstRun(flow)
        assertEquals(AppRoute.Unlock(firstRun = true), controller.routeNow)

        assertTrue(controller.onBack())
        runCurrent()
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Browse), controller.routeNow)
        assertSame(flow, controller.sourceModel())
        assertFalse(setup.closed)

        saveFirstRun(flow, size = 8192L)
        assertEquals(AppRoute.Unlock(firstRun = true), controller.routeNow)
        assertEquals(8192L, controller.unlockModel().state.value.firstRun?.fileSizeBytes)
    }

    @Test
    fun `back from step 3 without a flow starts a new first-run flow at the server form`() = runTest {
        // Process death after the file was saved: source set, setup not completed.
        platform.settingsFlow.value = KpmSettings(source = source())
        val controller = controller()
        assertEquals(AppRoute.Unlock(firstRun = true), controller.routeNow)

        assertTrue(controller.onBack())
        runCurrent()
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Server), controller.routeNow)
    }

    @Test
    fun `the roots hand system back to the activity`() = runTest {
        platform.settingsFlow.value = KpmSettings()
        val first = controller()
        // Step 1: leave the app. Step 2: back goes to the form first.
        assertFalse(first.onBack())
        connectFirstRun(first.sourceModel())
        assertTrue(first.onBack())
        runCurrent()
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Server), first.routeNow)
        assertFalse(first.onBack())
    }

    @Test
    fun `system setup and the normal unlock screen are roots`() = runTest {
        platform.settingsFlow.value = completed
        val controller = controller()
        assertEquals(AppRoute.Unlock(firstRun = false), controller.routeNow)
        assertFalse(controller.onBack())

        platform.settingsFlow.value = completed.copy(setupCompleted = false)
        platform.sessionFlow.value = unlockedView(vault)
        runCurrent()
        assertEquals(AppRoute.SystemSetup, controller.routeNow)
        assertFalse(controller.onBack())
    }

    // ---- in-app navigation ----

    @Test
    fun `list detail and settings are pushed and popped by back`() = runTest {
        val controller = unlockedController()
        assertEquals(AppRoute.EntryList, controller.routeNow)
        assertFalse(controller.onBack())

        controller.openEntry(entryId)
        runCurrent()
        assertEquals(AppRoute.EntryDetail(entryId), controller.routeNow)
        assertTrue(controller.onBack())
        runCurrent()
        assertEquals(AppRoute.EntryList, controller.routeNow)

        controller.openSettings()
        runCurrent()
        assertEquals(AppRoute.Settings, controller.routeNow)
        assertTrue(controller.onBack())
        runCurrent()
        assertEquals(AppRoute.EntryList, controller.routeNow)
    }

    @Test
    fun `the stop of the activity hides a revealed password`() = runTest {
        platform.passwords[entryId] = "geheim"
        val controller = unlockedController()
        controller.openEntry(entryId)
        runCurrent()
        val detail = (controller.screen.value as AppScreen.EntryDetail).model
        detail.onTogglePassword()
        runCurrent()
        assertEquals("geheim", String(assertNotNull(detail.view.value?.state?.shownPassword)))

        controller.onStop()
        runCurrent()
        assertNull(detail.view.value?.state?.shownPassword)
        assertTrue(platform.revealed.single().all { it == '\u0000' })
    }

    @Test
    fun `navigation actions only apply on the screen they start from`() = runTest {
        val controller = unlockedController()
        controller.editSource()
        runCurrent()
        assertEquals(AppRoute.EntryList, controller.routeNow)

        controller.openEntry(entryId)
        controller.openEntry(UUID.randomUUID())
        controller.openSettings()
        runCurrent()
        assertEquals(AppRoute.EntryDetail(entryId), controller.routeNow)
        assertTrue(controller.onBack())
        runCurrent()
        assertEquals(AppRoute.EntryList, controller.routeNow)
    }

    @Test
    fun `the list model survives a detail and is the same after back`() = runTest {
        val controller = unlockedController()
        val list = controller.screen.value.model
        controller.openEntry(entryId)
        runCurrent()
        assertTrue(controller.onBack())
        runCurrent()
        assertSame(list, controller.screen.value.model)
    }

    @Test
    fun `an entry that is missing pops its detail`() = runTest {
        val controller = unlockedController()
        controller.openEntry(UUID.randomUUID())
        runCurrent()
        assertEquals(AppRoute.EntryList, controller.routeNow)
    }

    @Test
    fun `a vanished source pops the settings`() = runTest {
        val controller = unlockedController()
        controller.openSettings()
        runCurrent()
        assertEquals(AppRoute.Settings, controller.routeNow)

        platform.settingsFlow.value = completed.copy(source = null)
        runCurrent()
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Server), controller.routeNow)
        platform.settingsFlow.value = completed
        runCurrent()
        assertEquals(AppRoute.EntryList, controller.routeNow)
    }

    @Test
    fun `editing the source from the settings pops back to the settings on save`() = runTest {
        val controller = unlockedController()
        controller.openSettings()
        controller.editSource()
        runCurrent()
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.ChangeUnlocked, SourceFlowScreen.Server), controller.routeNow)
        val flow = controller.sourceModel()

        saveChange(flow)
        assertEquals(AppRoute.Settings, controller.routeNow)
        assertTrue(setup.closed)
        assertEquals(UiMessage.SourceSaved, controller.messages.message.value)
    }

    @Test
    fun `back from the unlocked source change goes one folder up, then pops and closes the flow`() = runTest {
        val controller = unlockedController()
        controller.openSettings()
        controller.editSource()
        runCurrent()
        setup.connectResults += ConnectResult.Connected(server, null)
        val flow = controller.sourceModel()
        flow.readPassword = { "geheim".toCharArray() }
        flow.onConnect()
        runCurrent()
        assertEquals(SourceFlowScreen.Browse, flow.screen.value)

        // Browser to form (a folder up first, the start is in the folder of the file).
        while (flow.screen.value == SourceFlowScreen.Browse) {
            assertTrue(controller.onBack())
            runCurrent()
        }
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.ChangeUnlocked, SourceFlowScreen.Server), controller.routeNow)
        assertFalse(setup.closed)

        assertTrue(controller.onBack())
        runCurrent()
        assertEquals(AppRoute.Settings, controller.routeNow)
        assertTrue(setup.closed)
    }

    // ---- lock ----

    @Test
    fun `a lock collapses the stack, closes the change flow and cancels the models`() = runTest {
        val controller = unlockedController()
        val list = controller.screen.value.model as EntryListModel
        controller.openEntry(entryId)
        runCurrent()
        val detail = controller.screen.value.model as EntryDetailModel
        assertTrue(controller.onBack())
        controller.openSettings()
        runCurrent()
        val settings = controller.screen.value.model as SettingsModel
        controller.editSource()
        runCurrent()
        // The source setup (and its connection) is opened by the connect.
        setup.connectResults += ConnectResult.Connected(server, null)
        val flow = controller.sourceModel()
        flow.readPassword = { "geheim".toCharArray() }
        flow.onConnect()
        runCurrent()
        assertEquals(1, platform.setups.size)
        assertFalse(platform.setups[0].closed)

        platform.lock()
        runCurrent()
        assertEquals(AppRoute.Unlock(firstRun = false), controller.routeNow)
        assertTrue(platform.setups[0].closed)

        // The old models no longer follow the session: after the next unlock they know nothing of it.
        val settingsAfterLock = settings.state.value
        val newer = testVault(listOf(testEntry("Other", id = entryId)))
        platform.sessionFlow.value = unlockedView(newer)
        runCurrent()
        assertEquals(AppRoute.EntryList, controller.routeNow)
        assertNotSame(list, controller.screen.value.model)
        assertTrue(list.view.value?.vault !== newer)
        assertTrue(detail.view.value?.vault !== newer)
        assertSame(settingsAfterLock, settings.state.value)
    }

    @Test
    fun `a lock resets the unlock model`() = runTest {
        platform.settingsFlow.value = completed
        val controller = controller()
        val unlock = controller.unlockModel()
        assertEquals(1, platform.calls.count { it == "unlockOffer" })

        platform.sessionFlow.value = unlockedView(vault)
        runCurrent()
        assertEquals(AppRoute.EntryList, controller.routeNow)
        platform.lock()
        runCurrent()
        assertSame(unlock, controller.unlockModel())
        assertEquals(2, platform.calls.count { it == "unlockOffer" })
    }

    @Test
    fun `the lock reason is a notice on the unlock screen for real transitions only`() = runTest {
        // Initial locked state with a reason: no notice.
        platform.settingsFlow.value = completed
        platform.sessionFlow.value = SessionView.Locked(LockReason.User)
        val controller = controller()
        assertNull(controller.lockNotice())

        // A failed unlock ends in Locked, too: no notice.
        platform.sessionFlow.value = SessionView.Unlocking
        runCurrent()
        platform.sessionFlow.value = SessionView.Locked(LockReason.User)
        runCurrent()
        assertNull(controller.lockNotice())

        val expected = listOf(
            LockReason.User to UiMessage.Locked,
            LockReason.Notification to UiMessage.Locked,
            LockReason.Idle(IdleTimeout.MINUTES_2) to UiMessage.AutoLocked(120),
            LockReason.ServiceRefused to UiMessage.LockedNoService,
            LockReason.SourceChanged to UiMessage.LockedSourceChanged,
        )
        for ((reason, message) in expected) {
            platform.sessionFlow.value = unlockedView(vault)
            runCurrent()
            assertEquals(AppRoute.EntryList, controller.routeNow)
            platform.sessionFlow.value = SessionView.Locked(reason)
            runCurrent()
            assertEquals(message, controller.lockNotice(), reason.toString())
            // Never a snackbar.
            assertNull(controller.messages.message.value, reason.toString())
        }

        // Swipe-away: nothing to say, and the notice of the lock before is gone.
        platform.sessionFlow.value = unlockedView(vault)
        runCurrent()
        platform.sessionFlow.value = SessionView.Locked(LockReason.TaskRemoved)
        runCurrent()
        assertNull(controller.lockNotice())
    }

    @Test
    fun `a lock right after the unlock is shown although the session never looked unlocked`() = runTest {
        // The platform publishes Unlocked and locks at once; the StateFlow conflates it to Locked(ServiceRefused).
        platform.settingsFlow.value = completed
        val controller = controller()
        val unlock = controller.unlockModel()
        assertEquals(1, platform.calls.count { it == "unlockOffer" })

        platform.sessionFlow.value = SessionView.Unlocking
        runCurrent()
        assertNull(controller.lockNotice())
        platform.sessionFlow.value = SessionView.Locked(LockReason.ServiceRefused)
        runCurrent()

        assertEquals(UiMessage.LockedNoService, controller.lockNotice())
        assertNull(controller.messages.message.value)
        assertEquals(AppRoute.Unlock(firstRun = false), controller.routeNow)
        // Like every lock: the unlock screen starts from the beginning.
        assertSame(unlock, controller.unlockModel())
        assertEquals(2, platform.calls.count { it == "unlockOffer" })

        // Shown once: a settings change with the same session does not start it again.
        platform.settingsFlow.value = completed.copy(fingerprintEnabled = true)
        runCurrent()
        assertEquals(UiMessage.LockedNoService, controller.lockNotice())
        assertEquals(2, platform.calls.count { it == "unlockOffer" })
    }

    @Test
    fun `a lock without any state in between is shown, a failed unlock in between does not hide the next one`() = runTest {
        platform.settingsFlow.value = completed
        val controller = controller()
        platform.sessionFlow.value = SessionView.Locked(LockReason.ServiceRefused)
        runCurrent()
        assertEquals(UiMessage.LockedNoService, controller.lockNotice())

        // Failed unlock (reason null by contract), then the same conflated lock again.
        platform.sessionFlow.value = SessionView.Unlocking
        runCurrent()
        platform.sessionFlow.value = SessionView.Locked(null)
        runCurrent()
        // The failed unlock is no lock: the notice of the lock before stays.
        assertEquals(UiMessage.LockedNoService, controller.lockNotice())
        platform.sessionFlow.value = SessionView.Locked(LockReason.ServiceRefused)
        runCurrent()
        assertEquals(UiMessage.LockedNoService, controller.lockNotice())
    }

    @Test
    fun `the state at start shows no notice, also with a reason of an earlier lock`() = runTest {
        platform.settingsFlow.value = completed
        for (reason in listOf(null, LockReason.TaskRemoved, LockReason.ServiceRefused, LockReason.Idle(IdleTimeout.MINUTES_2))) {
            platform.sessionFlow.value = SessionView.Locked(reason)
            val controller = controller()
            assertNull(controller.lockNotice(), "$reason")
            assertNull(controller.messages.message.value, "$reason")
            // A settings change while locked does not make it look new.
            platform.settingsFlow.value = platform.settingsFlow.value.copy(fingerprintEnabled = !platform.settingsFlow.value.fingerprintEnabled)
            runCurrent()
            assertNull(controller.lockNotice(), "$reason")
        }
    }

    @Test
    fun `a lock after an app start in the unlocked state builds the unlock screen with the notice`() = runTest {
        // The unlock model does not exist yet (autofill unlocked the session before the app started).
        val controller = unlockedController()
        platform.sessionFlow.value = SessionView.Locked(LockReason.Idle(IdleTimeout.MINUTES_2))
        runCurrent()
        assertEquals(AppRoute.Unlock(firstRun = false), controller.routeNow)
        assertEquals(UiMessage.AutoLocked(120), controller.lockNotice())
    }

    @Test
    fun `the notice goes with the successful unlock and no lock message shows up on the list`() = runTest {
        platform.settingsFlow.value = completed
        platform.offer = UnlockOffer.Password(PasswordRequiredReason.NOT_SET_UP)
        val controller = controller()
        platform.sessionFlow.value = unlockedView(vault)
        runCurrent()
        platform.sessionFlow.value = SessionView.Locked(LockReason.User)
        runCurrent()
        val unlock = controller.unlockModel()
        assertEquals(UiMessage.Locked, unlock.state.value.lockNotice)

        platform.unlockOutcomes += unlocked()
        platform.sessionOnUnlock = unlockedView(vault)
        unlock.submit(false, { "pw".toCharArray() }, {})
        runCurrent()
        assertEquals(AppRoute.EntryList, controller.routeNow)
        assertNull(unlock.state.value.lockNotice)
        assertNull(controller.messages.message.value)
    }

    @Test
    fun `a lock removes a snackbar of the open session`() = runTest {
        // "Zwischenablage gelöscht" comes with the start of a lock, possibly before the session shows Locked.
        val controller = unlockedController()
        controller.messages.post(UiMessage.ClipboardCleared)
        platform.sessionFlow.value = SessionView.Locked(LockReason.User)
        runCurrent()
        assertNull(controller.messages.message.value)
        assertEquals(UiMessage.Locked, controller.lockNotice())
    }

    @Test
    fun `the notice of a lock is cleared when another source is chosen`() = runTest {
        platform.settingsFlow.value = completed
        platform.offer = UnlockOffer.Password(PasswordRequiredReason.NOT_SET_UP)
        val controller = controller()
        platform.sessionFlow.value = unlockedView(vault)
        runCurrent()
        platform.sessionFlow.value = SessionView.Locked(LockReason.SourceChanged)
        runCurrent()
        assertEquals(UiMessage.LockedSourceChanged, controller.lockNotice())

        controller.chooseSource()
        runCurrent()
        saveChange(controller.sourceModel())
        assertEquals(AppRoute.Unlock(firstRun = false), controller.routeNow)
        assertNull(controller.lockNotice())
    }

    @Test
    fun `an unlock elsewhere ends a locked source change`() = runTest {
        platform.settingsFlow.value = completed
        platform.offer = UnlockOffer.Password(PasswordRequiredReason.NOT_SET_UP)
        val controller = controller()
        controller.chooseSource()
        runCurrent()
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.ChangeLocked, SourceFlowScreen.Server), controller.routeNow)
        setup.connectResults += ConnectResult.Connected(server, null)
        controller.sourceModel().readPassword = { "geheim".toCharArray() }
        controller.sourceModel().onConnect()
        runCurrent()
        assertFalse(setup.closed)

        platform.sessionFlow.value = unlockedView(vault)
        runCurrent()
        assertEquals(AppRoute.EntryList, controller.routeNow)
        assertTrue(setup.closed)
    }

    // ---- unlock problem Corrupt ----

    @Test
    fun `corrupt offline copy leads to a locked source change and back to the unlock screen on save`() = runTest {
        platform.settingsFlow.value = completed
        platform.offer = UnlockOffer.Password(PasswordRequiredReason.NOT_SET_UP)
        val controller = controller()
        val unlock = controller.unlockModel()
        platform.unlockOutcomes += UnlockOutcome.Corrupt
        unlock.submit(false, { "pw".toCharArray() }, {})
        runCurrent()
        assertEquals(UnlockProblem.Corrupt, unlock.state.value.problem)

        controller.chooseSource()
        runCurrent()
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.ChangeLocked, SourceFlowScreen.Server), controller.routeNow)

        saveChange(controller.sourceModel())
        assertEquals(AppRoute.Unlock(firstRun = false), controller.routeNow)
        assertSame(unlock, controller.unlockModel())
        assertNull(unlock.state.value.problem)
        assertTrue(setup.closed)
    }

    @Test
    fun `back out of the locked source change closes it`() = runTest {
        platform.settingsFlow.value = completed
        platform.offer = UnlockOffer.Password(PasswordRequiredReason.NOT_SET_UP)
        val controller = controller()
        controller.chooseSource()
        runCurrent()
        // The connection opens with the first connect: until then there is nothing to close.
        assertTrue(platform.setups.isEmpty())
        assertTrue(controller.onBack())
        runCurrent()
        assertEquals(AppRoute.Unlock(firstRun = false), controller.routeNow)

        // With a connection: back through the browser and the form closes it.
        controller.chooseSource()
        runCurrent()
        val flow = controller.sourceModel()
        setup.connectResults += ConnectResult.Connected(server, null)
        flow.readPassword = { "geheim".toCharArray() }
        flow.onConnect()
        runCurrent()
        assertEquals(1, platform.setups.size)
        assertFalse(platform.setups[0].closed)
        repeat(10) {
            if (controller.routeNow !is AppRoute.Unlock) {
                controller.onBack()
                runCurrent()
            }
        }
        assertEquals(AppRoute.Unlock(firstRun = false), controller.routeNow)
        assertTrue(platform.setups[0].closed)
    }

    @Test
    fun `in the first run the corrupt notice reopens the first-run flow`() = runTest {
        platform.settingsFlow.value = KpmSettings(source = source())
        val controller = controller()
        controller.chooseSource()
        runCurrent()
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Server), controller.routeNow)
    }

    // ---- choosing another database from the unlock screen ----

    @Test
    fun `another database can be chosen from the normal unlock screen and the unlock screen shows the new file`() = runTest {
        platform.settingsFlow.value = completed
        platform.offer = UnlockOffer.Password(PasswordRequiredReason.NOT_SET_UP)
        val controller = controller()
        val unlock = controller.unlockModel()
        assertEquals("Db.kdbx", unlock.state.value.fileName)

        controller.chooseSource()
        runCurrent()
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.ChangeLocked, SourceFlowScreen.Server), controller.routeNow)
        val flow = controller.sourceModel()

        // No password typed: the empty one is sent.
        setup.connectResults += ConnectResult.Connected(server, listOf(ShareEntry("Docs", "")))
        flow.readPassword = { CharArray(0) }
        flow.onConnect()
        runCurrent()
        assertEquals("", setup.connectCalls.single().password)
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.ChangeLocked, SourceFlowScreen.Browse), controller.routeNow)

        flow.onSelectFile(listOf("Docs", "Neu", "Andere.kdbx"))
        platform.settingsFlow.value = completed.copy(source = source().copy(filePath = listOf("Neu", "Andere.kdbx")))
        flow.onUseFile()
        runCurrent()
        assertEquals(AppRoute.Unlock(firstRun = false), controller.routeNow)
        assertSame(unlock, controller.unlockModel())
        assertEquals("Andere.kdbx", unlock.state.value.fileName)
        assertEquals(2, platform.calls.count { it == "unlockOffer" })
        assertTrue(setup.closed)
    }

    @Test
    fun `the unlocked source change from the settings needs the typed password too`() = runTest {
        val controller = unlockedController()
        controller.openSettings()
        runCurrent()
        controller.editSource()
        runCurrent()
        val flow = controller.sourceModel()
        setup.connectResults += ConnectResult.Connected(server, null)
        flow.readPassword = { CharArray(0) }
        flow.onConnect()
        runCurrent()
        assertEquals("", setup.connectCalls.single().password)
    }

    // ---- fingerprint availability on resume ----

    @Test
    fun `the resume reads the fingerprint availability again on setup step 3`() = runTest {
        platform.availability = FingerprintAvailability.NONE_ENROLLED
        // Source set, setup not completed: the unlock screen is step 3.
        platform.settingsFlow.value = KpmSettings(source = source())
        val controller = controller()
        assertEquals(AppRoute.Unlock(firstRun = true), controller.routeNow)
        assertFalse(controller.unlockModel().state.value.firstRun!!.fingerprintAvailable)

        // The user enrolled a fingerprint in the system settings and comes back.
        platform.availability = FingerprintAvailability.AVAILABLE
        controller.refreshSystem()
        runCurrent()
        val first = controller.unlockModel().state.value.firstRun!!
        assertTrue(first.fingerprintAvailable)
        assertTrue(first.fingerprintWanted)
    }

    @Test
    fun `the resume reads the fingerprint availability again on the normal unlock screen`() = runTest {
        platform.settingsFlow.value = completed
        platform.offer = UnlockOffer.Password(PasswordRequiredReason.CANCELLED)
        platform.fingerprintUsable = true
        val controller = controller()
        assertTrue(controller.unlockModel().state.value.fingerprintUsable)

        platform.fingerprintUsable = false
        controller.refreshSystem()
        runCurrent()
        assertFalse(controller.unlockModel().state.value.fingerprintUsable)
    }

    // ---- sync reports ----

    private fun TestScope.reportMessage(trigger: SyncTrigger, result: SyncResult, controller: AppController): UiMessage? {
        controller.messages.dismiss()
        platform.emitReport(trigger, result)
        runCurrent()
        return controller.messages.message.value
    }

    @Test
    fun `the report present at creation is not shown`() = runTest {
        platform.emitReport(SyncTrigger.AFTER_UNLOCK, SyncResult.Updated)
        val controller = controller()
        assertNull(controller.messages.message.value)

        // Same id again (a different value of the same run): still seen.
        platform.lastReportFlow.value = SyncReport(1, SyncTrigger.AFTER_UNLOCK, SyncResult.Failed(SyncProblem.SERVER_CHANGED))
        runCurrent()
        assertNull(controller.messages.message.value)

        assertEquals(UiMessage.SyncUpdated, reportMessage(SyncTrigger.AFTER_UNLOCK, SyncResult.Updated, controller))
    }

    @Test
    fun `each report id is shown once`() = runTest {
        val controller = controller()
        assertEquals(UiMessage.SyncUpdated, reportMessage(SyncTrigger.AFTER_UNLOCK, SyncResult.Updated, controller))

        controller.messages.dismiss()
        platform.lastReportFlow.value = SyncReport(1, SyncTrigger.AFTER_UNLOCK, SyncResult.Failed(SyncProblem.SERVER_CHANGED))
        runCurrent()
        assertNull(controller.messages.message.value)

        // The next run with the same result is shown again.
        assertEquals(UiMessage.SyncUpdated, reportMessage(SyncTrigger.AFTER_UNLOCK, SyncResult.Updated, controller))
    }

    @Test
    fun `after unlock only a newer version and a changed server are reported`() = runTest {
        val controller = controller()
        assertEquals(UiMessage.SyncUpdated, reportMessage(SyncTrigger.AFTER_UNLOCK, SyncResult.Updated, controller))
        assertEquals(
            UiMessage.SyncOtherServer,
            reportMessage(SyncTrigger.AFTER_UNLOCK, SyncResult.Failed(SyncProblem.SERVER_CHANGED), controller),
        )
        val silent = listOf(
            SyncResult.UpToDate,
            SyncResult.CandidateReady,
            SyncResult.NewVersionRejected,
            SyncResult.NotConfigured,
            SyncResult.Failed(SyncProblem.UNREACHABLE),
            SyncResult.Failed(SyncProblem.AUTH_FAILED),
            SyncResult.Failed(SyncProblem.INVALID_FILE),
        )
        for (result in silent) {
            assertNull(reportMessage(SyncTrigger.AFTER_UNLOCK, result, controller), result.toString())
        }
    }

    @Test
    fun `scheduled runs follow the same rule`() = runTest {
        val controller = controller()
        assertEquals(UiMessage.SyncUpdated, reportMessage(SyncTrigger.SCHEDULED, SyncResult.Updated, controller))
        assertEquals(
            UiMessage.SyncOtherServer,
            reportMessage(SyncTrigger.SCHEDULED, SyncResult.Failed(SyncProblem.SERVER_CHANGED), controller),
        )
        assertNull(reportMessage(SyncTrigger.SCHEDULED, SyncResult.Failed(SyncProblem.UNREACHABLE), controller))
        assertNull(reportMessage(SyncTrigger.SCHEDULED, SyncResult.UpToDate, controller))
    }

    @Test
    fun `requested and setup runs are silent here`() = runTest {
        val controller = controller()
        for (trigger in listOf(SyncTrigger.REQUESTED, SyncTrigger.SETUP)) {
            assertNull(reportMessage(trigger, SyncResult.Updated, controller), trigger.toString())
            assertNull(reportMessage(trigger, SyncResult.Failed(SyncProblem.SERVER_CHANGED), controller), trigger.toString())
        }
    }

    // ---- stop ----

    @Test
    fun `stopping the app at step 3 closes the first-run connection and back shows the server form`() = runTest {
        platform.settingsFlow.value = KpmSettings()
        val controller = controller()
        val flow = controller.sourceModel()
        connectFirstRun(flow)
        saveFirstRun(flow)
        assertEquals(AppRoute.Unlock(firstRun = true), controller.routeNow)
        assertFalse(setup.closed)

        controller.onStop()
        assertTrue(setup.closed)
        // The unlock step itself stays.
        assertEquals(AppRoute.Unlock(firstRun = true), controller.routeNow)

        assertTrue(controller.onBack())
        runCurrent()
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Server), controller.routeNow)
        assertSame(flow, controller.sourceModel())

        // A new connection is opened for the next connect.
        val second = FakeSourceSetup()
        platform.nextSetup = { second }
        second.connectResults += ConnectResult.Connected(server, listOf(ShareEntry("Docs", "")))
        flow.readPassword = { "geheim".toCharArray() }
        flow.onConnect()
        runCurrent()
        assertEquals(2, platform.setups.size)
        assertEquals(1, second.connectCalls.size)
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Browse), controller.routeNow)
    }

    @Test
    fun `stopping the app while a setup screen shows keeps the connection`() = runTest {
        platform.settingsFlow.value = KpmSettings()
        val controller = controller()
        val flow = controller.sourceModel()
        connectFirstRun(flow)
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Browse), controller.routeNow)

        controller.onStop()
        assertFalse(setup.closed)
        assertEquals(AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Browse), controller.routeNow)
    }

    @Test
    fun `stopping the app without a first-run flow does nothing`() = runTest {
        val controller = unlockedController()
        controller.onStop()
        assertEquals(AppRoute.EntryList, controller.routeNow)
        controller.close()
        controller.onStop()
    }

    // ---- misc ----

    @Test
    fun `close ends the open source connection`() = runTest {
        platform.settingsFlow.value = completed
        platform.offer = UnlockOffer.Password(PasswordRequiredReason.NOT_SET_UP)
        val controller = controller()
        controller.chooseSource()
        runCurrent()
        setup.connectResults += ConnectResult.Connected(server, null)
        controller.sourceModel().readPassword = { "geheim".toCharArray() }
        controller.sourceModel().onConnect()
        runCurrent()
        assertFalse(platform.setups[0].closed)
        controller.close()
        assertTrue(platform.setups[0].closed)
    }
}
