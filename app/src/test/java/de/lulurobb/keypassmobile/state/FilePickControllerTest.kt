package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.session.LockReason
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.settings.KpmSettings
import de.lulurobb.keypassmobile.platform.setup.ChooseResult
import de.lulurobb.keypassmobile.platform.setup.FileCheck
import de.lulurobb.keypassmobile.platform.setup.OlderVersion
import de.lulurobb.keypassmobile.platform.setup.PickedFile
import de.lulurobb.keypassmobile.ui.common.PickFailure
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.dialogs.OlderVersionInfo
import de.lulurobb.keypassmobile.ui.setup.SourceFlowMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FilePickControllerTest {

    private val fileSetup = FakeFileSetup()
    private val platform = FakeAppPlatform(settings = KpmSettings(setupCompleted = true)).also { it.nextFileSetup = { fileSetup } }
    private val messages = AppMessages()

    init {
        fileSetup.defaultChoose = ChooseResult.Saved(4096L, true, SyncResult.UpToDate)
    }
    private val saved = mutableListOf<Pair<SourceFlowMode, Long?>>()

    private val uri = "content://docs/document/primary%3ASyncthing%2FPass.kdbx"
    private val picked = PickedFile(
        uri = uri,
        displayName = "Pass.kdbx",
        sizeBytes = 4096L,
        lastModified = Instant.parse("2026-09-12T18:05:00Z"),
        place = "Interner Speicher / Syncthing",
    )

    private fun TestScope.controller() = FilePickController(platform, backgroundScope, testTime(), messages) { mode, size ->
        saved += mode to size
    }

    private fun unlocked() {
        platform.sessionFlow.value = unlockedView(testVault(emptyList()))
    }

    private fun TestScope.pickAndResult(controller: FilePickController, changing: Boolean = true, result: String? = uri) {
        controller.pick(changing)
        controller.onPicked(result)
        runCurrent()
    }

    private fun savedResult(locked: Boolean = false) =
        ChooseResult.Saved(fileSizeBytes = 4096L, fileChanged = true, sync = SyncResult.UpToDate, lockedForNewDatabase = locked)

    // ---- request ----

    @Test
    fun `pick asks the activity for the picker at the start folder and the launch clears the request`() = runTest {
        val controller = controller()
        fileSetup.initialFolderFor = { _, _ -> "content://docs/document/primary%3ASyncthing" }
        controller.pick(changing = true)

        val request = assertNotNull(controller.request.value)
        assertEquals("content://docs/document/primary%3ASyncthing", request.initialUri)
        assertEquals(listOf(FakeFileSetup.InitialFolderCall(null, true)), fileSetup.initialFolderCalls)
        controller.onLaunched(request.id + 1)
        assertNotNull(controller.request.value)
        controller.onLaunched(request.id)
        assertNull(controller.request.value)
    }

    @Test
    fun `going back in the picker changes nothing and posts nothing`() = runTest {
        val controller = controller()
        pickAndResult(controller, result = null)
        assertNull(messages.message.value)
        assertTrue(fileSetup.acceptCalls.isEmpty())
        assertTrue(fileSetup.closed)
        assertTrue(saved.isEmpty())
    }

    // ---- mode derivation ----

    @Test
    fun `the mode follows the setup and the session when the result arrives`() = runTest {
        val controller = controller()
        fileSetup.defaultAccept = FileCheck.Ok(picked)

        platform.settingsFlow.value = KpmSettings(setupCompleted = false)
        pickAndResult(controller, changing = false)
        assertEquals(SourceFlowMode.FirstRun, saved.last().first)

        platform.settingsFlow.value = KpmSettings(setupCompleted = true)
        unlocked()
        pickAndResult(controller)
        assertEquals(SourceFlowMode.ChangeUnlocked, saved.last().first)

        platform.lock()
        pickAndResult(controller)
        assertEquals(SourceFlowMode.ChangeLocked, saved.last().first)
        assertEquals(3, saved.size)
        assertEquals(4096L, saved.last().second)
    }

    // ---- busy ----

    @Test
    fun `the busy view shows only after 300 ms and ends with the pick`() = runTest {
        val controller = controller()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        val gate = CompletableDeferred<Unit>()
        fileSetup.acceptGate = gate
        controller.pick(changing = true)
        controller.onPicked(uri)
        runCurrent()
        assertFalse(controller.busy.value)
        advanceTimeBy(299)
        assertFalse(controller.busy.value)
        advanceTimeBy(2)
        assertTrue(controller.busy.value)

        gate.complete(Unit)
        runCurrent()
        assertFalse(controller.busy.value)
        assertEquals(1, saved.size)
    }

    @Test
    fun `a quick check never shows the busy view`() = runTest {
        val controller = controller()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        pickAndResult(controller)
        advanceTimeBy(1000)
        assertFalse(controller.busy.value)
    }

    // ---- errors ----

    @Test
    fun `a rejected file becomes a message with its name and the permission is given back`() = runTest {
        val controller = controller()
        val expected = listOf(
            FileCheck.NotKdbx("liesmich.txt") to UiMessage.PickFailed(PickFailure.NotKdbx, "liesmich.txt"),
            FileCheck.Unreadable("a.kdbx") to UiMessage.PickFailed(PickFailure.Unreadable, "a.kdbx"),
            FileCheck.NoLastingAccess("b.kdbx") to UiMessage.PickFailed(PickFailure.NoLastingAccess, "b.kdbx"),
        )
        for ((check, message) in expected) {
            messages.dismiss()
            fileSetup.discarded.clear()
            fileSetup.acceptResults += check
            pickAndResult(controller)
            assertEquals(message, messages.message.value)
            assertEquals(listOf(uri), fileSetup.discarded)
            assertTrue(fileSetup.closed)
        }
        assertTrue(saved.isEmpty())
        assertTrue(fileSetup.chooseCalls.isEmpty())
    }

    @Test
    fun `a failed switch after a passed check is an unreadable file`() = runTest {
        val controller = controller()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        fileSetup.chooseResults += ChooseResult.Failed(SyncProblem.REMOTE_ERROR)
        pickAndResult(controller)
        assertEquals(UiMessage.PickFailed(PickFailure.Unreadable, "Pass.kdbx"), messages.message.value)
        assertTrue(saved.isEmpty())
        assertTrue(uri in fileSetup.discarded)
    }

    @Test
    fun `Neu waehlen opens the picker in the folder of the rejected file`() = runTest {
        val controller = controller()
        fileSetup.acceptResults += FileCheck.NotKdbx("liesmich.txt")
        pickAndResult(controller)
        fileSetup.initialFolderCalls.clear()

        controller.repickAfterError()

        assertEquals(listOf(FakeFileSetup.InitialFolderCall(uri, true)), fileSetup.initialFolderCalls)
        assertNotNull(controller.request.value)
    }

    @Test
    fun `Neu waehlen without a rejected file does nothing`() = runTest {
        val controller = controller()
        controller.repickAfterError()
        assertNull(controller.request.value)
    }

    // ---- DS-14 dialog ----

    private val older = OlderVersion(
        fileName = "Pass.kdbx",
        fileModified = Instant.parse("2026-09-12T18:05:00Z"),
        openModified = Instant.parse("2026-10-31T21:38:00Z"),
    )

    private fun TestScope.openDialog(controller: FilePickController) {
        unlocked()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        fileSetup.olderResults += older
        pickAndResult(controller)
    }

    @Test
    fun `an older file asks first while unlocked`() = runTest {
        val controller = controller()
        openDialog(controller)

        assertEquals(
            OlderVersionInfo("Pass.kdbx", LocalDateTime.of(2026, 9, 12, 18, 5), LocalDateTime.of(2026, 10, 31, 21, 38)),
            controller.olderDialog.value,
        )
        assertEquals(listOf(picked), fileSetup.olderCalls)
        assertTrue(fileSetup.chooseCalls.isEmpty())
        assertFalse(controller.busy.value)
        assertFalse(fileSetup.closed)
    }

    @Test
    fun `Trotzdem verwenden continues the switch`() = runTest {
        val controller = controller()
        openDialog(controller)
        fileSetup.chooseResults += savedResult()

        controller.onOlderConfirm()
        runCurrent()

        assertNull(controller.olderDialog.value)
        assertEquals(listOf(picked), fileSetup.chooseCalls)
        assertEquals(listOf<Pair<SourceFlowMode, Long?>>(SourceFlowMode.ChangeUnlocked to 4096L), saved)
        assertEquals(UiMessage.SourceSaved, messages.message.value)
        assertTrue(fileSetup.closed)
        assertTrue(fileSetup.discarded.isEmpty())
    }

    @Test
    fun `Abbrechen discards the file and posts nothing`() = runTest {
        val controller = controller()
        openDialog(controller)

        controller.onOlderCancel()

        assertNull(controller.olderDialog.value)
        assertEquals(listOf(uri), fileSetup.discarded)
        assertTrue(fileSetup.chooseCalls.isEmpty())
        assertTrue(saved.isEmpty())
        assertNull(messages.message.value)
        assertTrue(fileSetup.closed)
    }

    @Test
    fun `a lock while the dialog is open counts as cancel`() = runTest {
        val controller = controller()
        openDialog(controller)

        platform.lock()
        controller.onLocked()

        assertNull(controller.olderDialog.value)
        assertEquals(listOf(uri), fileSetup.discarded)
        assertTrue(fileSetup.chooseCalls.isEmpty())
        assertNull(messages.message.value)
    }

    @Test
    fun `a lock without a dialog changes nothing`() = runTest {
        val controller = controller()
        controller.onLocked()
        assertTrue(fileSetup.discarded.isEmpty())
    }

    @Test
    fun `the first run and a locked change never ask`() = runTest {
        val controller = controller()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        fileSetup.olderResults += older
        fileSetup.olderResults += older

        platform.settingsFlow.value = KpmSettings(setupCompleted = false)
        pickAndResult(controller, changing = false)
        platform.settingsFlow.value = KpmSettings(setupCompleted = true)
        pickAndResult(controller)

        assertTrue(fileSetup.olderCalls.isEmpty())
        assertNull(controller.olderDialog.value)
        assertEquals(2, saved.size)
    }

    // ---- messages ----

    @Test
    fun `Quelle gespeichert is posted only when not locked and not in the first run`() = runTest {
        val controller = controller()
        fileSetup.defaultAccept = FileCheck.Ok(picked)

        // First run: none.
        platform.settingsFlow.value = KpmSettings(setupCompleted = false)
        pickAndResult(controller, changing = false)
        assertNull(messages.message.value)

        // Unlocked, no lock: posted.
        platform.settingsFlow.value = KpmSettings(setupCompleted = true)
        unlocked()
        pickAndResult(controller)
        assertEquals(UiMessage.SourceSaved, messages.message.value)

        // Unlocked, the switch locked the app (other database): the lock notice is the only message.
        messages.dismiss()
        fileSetup.chooseResults += savedResult(locked = true)
        pickAndResult(controller)
        assertNull(messages.message.value)

        // Locked change: posted on the unlock screen.
        platform.sessionFlow.value = SessionView.Locked(LockReason.User)
        pickAndResult(controller)
        assertEquals(UiMessage.SourceSaved, messages.message.value)
    }

    // ---- D10 ----

    @Test
    fun `a pick that was started unlocked and finds the app locked is discarded silently`() = runTest {
        val controller = controller()
        unlocked()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        controller.pick(changing = true)
        platform.lock()

        controller.onPicked(uri)
        runCurrent()

        assertEquals(listOf(uri), fileSetup.discarded)
        assertTrue(fileSetup.acceptCalls.isEmpty())
        assertTrue(fileSetup.olderCalls.isEmpty())
        assertTrue(fileSetup.chooseCalls.isEmpty())
        assertTrue(saved.isEmpty())
        assertNull(messages.message.value)
        assertTrue(fileSetup.closed)
    }

    @Test
    fun `a pick that was started locked is applied as a locked change`() = runTest {
        val controller = controller()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        controller.pick(changing = true)
        controller.onPicked(uri)
        runCurrent()
        assertEquals(listOf<Pair<SourceFlowMode, Long?>>(SourceFlowMode.ChangeLocked to 4096L), saved)
        assertTrue(fileSetup.discarded.isEmpty())
    }

    @Test
    fun `a result without a pick in progress (process restart) is a new pick with the mode of the moment`() = runTest {
        val controller = controller()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        // Locked after the restart: applied as a locked change.
        controller.onPicked(uri)
        runCurrent()
        assertEquals(listOf(uri), fileSetup.acceptCalls)
        assertEquals(listOf<Pair<SourceFlowMode, Long?>>(SourceFlowMode.ChangeLocked to 4096L), saved)
        assertEquals(UiMessage.SourceSaved, messages.message.value)
        assertTrue(fileSetup.discarded.isEmpty())
        assertTrue(fileSetup.closed)

        // First run (the setup was never completed): first-run mode.
        saved.clear()
        platform.settingsFlow.value = KpmSettings(setupCompleted = false)
        controller.onPicked(uri)
        runCurrent()
        assertEquals(listOf<Pair<SourceFlowMode, Long?>>(SourceFlowMode.FirstRun to 4096L), saved)
    }

    @Test
    fun `a rejected result after a restart is a message, and a missing result changes nothing`() = runTest {
        val controller = controller()
        fileSetup.acceptResults += FileCheck.NotKdbx("liesmich.txt")
        controller.onPicked(uri)
        runCurrent()
        assertEquals(UiMessage.PickFailed(PickFailure.NotKdbx, "liesmich.txt"), messages.message.value)

        messages.dismiss()
        fileSetup.acceptCalls.clear()
        controller.onPicked(null)
        runCurrent()
        assertTrue(fileSetup.acceptCalls.isEmpty())
        assertNull(messages.message.value)
    }

    @Test
    fun `a result after close is only given back`() = runTest {
        val controller = controller()
        controller.close()
        controller.onPicked(uri)
        runCurrent()
        assertTrue(fileSetup.acceptCalls.isEmpty())
        assertEquals(listOf(uri), fileSetup.discarded)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `D10 a lock while the file is checked discards it silently`() = runTest {
        val controller = controller()
        unlocked()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        fileSetup.acceptGate = CompletableDeferred()
        controller.pick(changing = true)
        controller.onPicked(uri)
        runCurrent()

        platform.lock()
        fileSetup.acceptGate!!.complete(Unit)
        runCurrent()

        assertEquals(listOf(uri), fileSetup.discarded)
        assertTrue(fileSetup.olderCalls.isEmpty())
        assertTrue(fileSetup.chooseCalls.isEmpty())
        assertTrue(saved.isEmpty())
        assertNull(messages.message.value)
        assertTrue(fileSetup.closed)
    }

    @Test
    fun `D10 a lock while the file is checked shows no error message either`() = runTest {
        val controller = controller()
        unlocked()
        fileSetup.acceptResults += FileCheck.NotKdbx("liesmich.txt")
        fileSetup.acceptGate = CompletableDeferred()
        controller.pick(changing = true)
        controller.onPicked(uri)
        runCurrent()

        platform.lock()
        fileSetup.acceptGate!!.complete(Unit)
        runCurrent()

        assertNull(messages.message.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun `D10 a lock during the older version check does not apply the file as a locked change`() = runTest {
        val controller = controller()
        unlocked()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        // The platform check finds nothing when the app is locked meanwhile (no result queued).
        fileSetup.olderGate = CompletableDeferred()
        controller.pick(changing = true)
        controller.onPicked(uri)
        runCurrent()
        assertEquals(listOf(picked), fileSetup.olderCalls)

        platform.lock()
        fileSetup.olderGate!!.complete(Unit)
        runCurrent()

        assertNull(controller.olderDialog.value)
        assertTrue(fileSetup.chooseCalls.isEmpty(), "no locked change without the DS-14 check")
        assertEquals(listOf(uri), fileSetup.discarded)
        assertTrue(saved.isEmpty())
        assertNull(messages.message.value, "no Quelle gespeichert")
        assertTrue(fileSetup.closed)
    }

    @Test
    fun `D10 a switch the platform discarded changes nothing and says nothing`() = runTest {
        val controller = controller()
        unlocked()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        fileSetup.chooseResults += ChooseResult.Discarded

        pickAndResult(controller)

        assertEquals(listOf(picked), fileSetup.chooseCalls)
        assertTrue(saved.isEmpty())
        assertNull(messages.message.value)
        assertTrue(fileSetup.closed)
        assertFalse(controller.busy.value)
        // The pick is over: the next one opens the picker again.
        controller.pick(changing = true)
        assertNotNull(controller.request.value)
    }

    // ---- double tap ----

    @Test
    fun `a second tap while a pick is in progress does not open a second picker`() = runTest {
        var started = 0
        platform.nextFileSetup = { started++; fileSetup }
        val controller = controller()
        controller.pick(changing = true)
        val first = assertNotNull(controller.request.value)
        controller.pick(changing = true)
        assertEquals(1, started)
        assertEquals(first, controller.request.value)

        // The picker is open: still in progress.
        controller.onLaunched(first.id)
        controller.pick(changing = true)
        assertEquals(1, started)
        assertNull(controller.request.value)

        // Back from the picker ends the pick.
        controller.onPicked(null)
        controller.pick(changing = true)
        assertEquals(2, started)
        assertNotNull(controller.request.value)
    }

    @Test
    fun `Neu waehlen ignores a second tap too`() = runTest {
        var started = 0
        platform.nextFileSetup = { started++; fileSetup }
        val controller = controller()
        fileSetup.acceptResults += FileCheck.NotKdbx("liesmich.txt")
        pickAndResult(controller)
        assertEquals(1, started)

        controller.repickAfterError()
        controller.repickAfterError()
        assertEquals(2, started)
    }

    @Test
    fun `the start folder is computed off the caller and a tap meanwhile is ignored`() = runTest {
        var started = 0
        platform.nextFileSetup = { started++; fileSetup }
        val controller = controller()
        fileSetup.initialFolderGate = CompletableDeferred()
        controller.pick(changing = true)
        assertNull(controller.request.value, "the folder is not known yet")

        controller.pick(changing = true)
        assertEquals(1, started)

        fileSetup.initialFolderGate!!.complete(Unit)
        runCurrent()
        assertNotNull(controller.request.value)
    }

    // ---- D10 across a process death ----

    @Test
    fun `the launch state is the mode of the pick in progress and null otherwise`() = runTest {
        val controller = controller()
        assertNull(controller.launchState())

        // Locked change.
        controller.pick(changing = true)
        assertEquals("ChangeLocked", controller.launchState())
        controller.onPicked(null)
        assertNull(controller.launchState())

        // Unlocked change.
        unlocked()
        controller.pick(changing = true)
        assertEquals("ChangeUnlocked", controller.launchState())
        controller.onPicked(null)

        // First run.
        platform.settingsFlow.value = KpmSettings(setupCompleted = false)
        controller.pick(changing = false)
        assertEquals("FirstRun", controller.launchState())
    }

    @Test
    fun `D10 a result after a process death for a pick that started unlocked is discarded silently`() = runTest {
        // New process: no pick in progress, the app is locked, the activity restored the saved mode.
        val controller = controller()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        controller.restoreLaunchState("ChangeUnlocked")

        controller.onPicked(uri)
        runCurrent()

        assertTrue(fileSetup.acceptCalls.isEmpty())
        assertTrue(fileSetup.olderCalls.isEmpty())
        assertTrue(fileSetup.chooseCalls.isEmpty())
        assertEquals(listOf(uri), fileSetup.discarded, "the new grant is released (the setup keeps the current source's own)")
        assertTrue(fileSetup.closed)
        assertTrue(saved.isEmpty())
        assertNull(messages.message.value)
        assertNull(controller.launchState())
    }

    @Test
    fun `a first run pick after a process death is a new pick`() = runTest {
        val controller = controller()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        platform.settingsFlow.value = KpmSettings(setupCompleted = false)
        controller.restoreLaunchState("FirstRun")

        controller.onPicked(uri)
        runCurrent()

        assertEquals(listOf(uri), fileSetup.acceptCalls)
        assertEquals(listOf<Pair<SourceFlowMode, Long?>>(SourceFlowMode.FirstRun to 4096L), saved)
        assertTrue(fileSetup.discarded.isEmpty())
    }

    @Test
    fun `a pick that started locked is applied as a locked change after a process death`() = runTest {
        val controller = controller()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        controller.restoreLaunchState("ChangeLocked")

        controller.onPicked(uri)
        runCurrent()

        assertEquals(listOf<Pair<SourceFlowMode, Long?>>(SourceFlowMode.ChangeLocked to 4096L), saved)
        assertEquals(UiMessage.SourceSaved, messages.message.value)
        assertTrue(fileSetup.discarded.isEmpty())
    }

    @Test
    fun `no saved state or an unknown one falls back to a new pick`() = runTest {
        val controller = controller()
        fileSetup.defaultAccept = FileCheck.Ok(picked)

        controller.restoreLaunchState(null)
        controller.onPicked(uri)
        runCurrent()
        assertEquals(1, saved.size)

        controller.restoreLaunchState("Unsinn")
        controller.onPicked(uri)
        runCurrent()
        assertEquals(2, saved.size)
        assertTrue(fileSetup.discarded.isEmpty())
    }

    @Test
    fun `the restored mode is used once`() = runTest {
        val controller = controller()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        controller.restoreLaunchState("ChangeUnlocked")

        controller.onPicked(uri)
        runCurrent()
        assertTrue(saved.isEmpty())

        // A second result without any state: the fallback, a new pick.
        controller.onPicked(uri)
        runCurrent()
        assertEquals(1, saved.size)
    }

    @Test
    fun `a restored mode is ignored while a pick is in progress and for a missing result`() = runTest {
        val controller = controller()
        fileSetup.defaultAccept = FileCheck.Ok(picked)

        // A pick of this process (e.g. a rotation): its own mode counts, the saved state is not used.
        controller.pick(changing = true)
        controller.restoreLaunchState("ChangeUnlocked")
        controller.onPicked(uri)
        runCurrent()
        assertEquals(listOf<Pair<SourceFlowMode, Long?>>(SourceFlowMode.ChangeLocked to 4096L), saved)

        // The user went back in the picker after the restart: nothing happens, nothing to give back.
        saved.clear()
        messages.dismiss()
        controller.restoreLaunchState("ChangeUnlocked")
        controller.onPicked(null)
        runCurrent()
        assertTrue(fileSetup.discarded.isEmpty())
        assertTrue(saved.isEmpty())
        assertNull(messages.message.value)
    }

    // ---- close ----

    @Test
    fun `close during the check cancels it and closes the setup`() = runTest {
        val controller = controller()
        fileSetup.defaultAccept = FileCheck.Ok(picked)
        fileSetup.acceptGate = CompletableDeferred()
        controller.pick(changing = true)
        controller.onPicked(uri)
        runCurrent()
        advanceTimeBy(400)
        assertTrue(controller.busy.value)

        controller.close()
        runCurrent()

        assertFalse(controller.busy.value)
        assertTrue(fileSetup.closed)
        assertTrue(saved.isEmpty())
    }
}
