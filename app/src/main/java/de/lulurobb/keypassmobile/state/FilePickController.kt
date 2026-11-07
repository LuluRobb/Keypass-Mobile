package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.platform.setup.ChooseResult
import de.lulurobb.keypassmobile.platform.setup.FileCheck
import de.lulurobb.keypassmobile.platform.setup.FileSourceSetup
import de.lulurobb.keypassmobile.platform.setup.PickedFile
import de.lulurobb.keypassmobile.ui.common.PickFailure
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.dialogs.OlderVersionInfo
import de.lulurobb.keypassmobile.ui.setup.SourceFlowMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The activity is asked to open the system file picker (ACTION_OPEN_DOCUMENT) at [initialUri]
 * (a document URI, DS-10). [id] tells the activity which request it has launched.
 */
data class PickRequest(val id: Long, val initialUri: String)

/**
 * Everything between "the user wants to pick a file" and "the source is saved" (DS-08 to DS-15):
 * asks the activity to open the picker ([request]), checks the result silently, asks for the
 * "Ältere Version" confirmation while unlocked, runs the source change and reports a failure as a
 * snackbar message with the action "Neu wählen".
 *
 * The mode is derived when the result arrives: the setup not completed is the first run, an
 * unlocked session a change while unlocked, otherwise a change while locked. A pick that was started
 * while unlocked and finds the app locked at any later point before the source is saved (picker open,
 * file check, older-version check, "Ältere Version" dialog open, the commit itself) is discarded
 * without any message (D10): the user picks again after unlocking. The last step is enforced by the
 * platform: the file setup is created with the pick and answers [ChooseResult.Discarded] when the app
 * locked meanwhile. A pick started while already locked (unlock screen) is applied as a locked change.
 *
 * A result that arrives without a pick in progress (the process was restarted while the picker was
 * open) is handled with the launch mode the activity saved ([launchState], [restoreLaunchState]):
 * a pick that was started while unlocked (D10) is discarded silently, the app is locked after a
 * restart; a pick of the first run or one started locked is handled as a new pick with the mode
 * of the moment; without saved state it is a new pick, too.
 *
 * While a pick is in progress, [pick] and [repickAfterError] do nothing (double tap).
 *
 * Each pick has its own [FileSourceSetup] (from [AppPlatform.startFileSetup]), closed when the pick
 * is over, so a permission that was taken but not saved is given back on every path.
 *
 * All calls come from the main thread. [onSaved] is called after a saved pick with the mode it ran in;
 * the caller (AppController) navigates.
 */
class FilePickController(
    private val platform: AppPlatform,
    private val scope: CoroutineScope,
    private val time: TimeSource,
    private val messages: AppMessages,
    private val onSaved: (mode: SourceFlowMode, fileSizeBytes: Long?) -> Unit,
) {
    private class Pick(val setup: FileSourceSetup, val changing: Boolean, val launchedMode: SourceFlowMode)

    private class Waiting(val pick: Pick, val file: PickedFile)

    private class Rejected(val uri: String, val changing: Boolean)

    private val mutableRequest = MutableStateFlow<PickRequest?>(null)

    /** The picker to open; the activity calls [onLaunched] when it did. */
    val request: StateFlow<PickRequest?> = mutableRequest.asStateFlow()

    private val mutableBusy = MutableStateFlow(false)

    /** True when checking and saving takes longer than 300 ms: "Lese Datei …" covers the screen and back does nothing. */
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()

    private val mutableOlder = MutableStateFlow<OlderVersionInfo?>(null)

    /** The "Ältere Version" dialog (DS-14); null = closed. */
    val olderDialog: StateFlow<OlderVersionInfo?> = mutableOlder.asStateFlow()

    private var current: Pick? = null
    private var waiting: Waiting? = null
    private var rejected: Rejected? = null
    private var job: Job? = null
    private var startJob: Job? = null
    private var closed = false

    /** Launch mode restored by the activity after a process death; used once by the next result without a pick. */
    private var restoredMode: SourceFlowMode? = null
    private var busyTimer: Job? = null
    private var nextId = 0L

    /**
     * Opens the picker. [changing] is false in the first run; it lets the picker start in the folder of
     * the current file or the last picked folder (DS-10). Does nothing while a pick is in progress.
     */
    fun pick(changing: Boolean) = launchPicker(rejectedUri = null, changing = changing)

    /** "Neu wählen" of the error message: the picker opens in the folder of the rejected file (DS-10). */
    fun repickAfterError() {
        val last = rejected ?: return
        launchPicker(rejectedUri = last.uri, changing = last.changing)
    }

    /**
     * What the activity saves with its instance state while a pick is in progress: the launch mode of the
     * pick ([SourceFlowMode.name]), null without a pick. After a process death it tells [onPicked] whether
     * the pick started while the app was unlocked (D10).
     */
    fun launchState(): String? = current?.launchedMode?.name

    /**
     * The activity was recreated with the state of [launchState]. Only used when no pick is in progress
     * here (a new process); an unknown or null value changes nothing.
     */
    fun restoreLaunchState(saved: String?) {
        if (current != null) return
        restoredMode = SourceFlowMode.entries.firstOrNull { it.name == saved }
    }

    /** The activity has launched request [id]. */
    fun onLaunched(id: Long) {
        if (mutableRequest.value?.id == id) mutableRequest.value = null
    }

    /**
     * The result of the picker: the document URI, or null when the user went back or the picker could not
     * be opened (no message, the screen the pick came from stays).
     */
    fun onPicked(uri: String?) {
        var pick = current
        val restored = restoredMode
        restoredMode = null
        if (pick == null) {
            // Nobody asked for it: the process was restarted while the picker was open.
            if (uri == null) return
            if (closed) {
                discardStray(uri)
                return
            }
            val mode = modeNow()
            // D10 across a process death: started unlocked, the restart locked the app: nothing is applied, no message.
            if (restored == SourceFlowMode.ChangeUnlocked && mode != SourceFlowMode.ChangeUnlocked) {
                discardStray(uri)
                return
            }
            // Otherwise it is a new pick with the mode of the moment (first run, or started locked).
            pick = Pick(platform.startFileSetup(), changing = mode != SourceFlowMode.FirstRun, launchedMode = mode)
            current = pick
        }
        mutableRequest.value = null
        if (uri == null) {
            finish(pick)
            return
        }
        if (job?.isActive == true || waiting != null) return
        val mode = modeNow()
        // D10: started while unlocked, locked by now (auto lock while the picker was open): nothing is applied.
        if (pick.launchedMode == SourceFlowMode.ChangeUnlocked && mode != SourceFlowMode.ChangeUnlocked) {
            pick.setup.discard(uri)
            finish(pick)
            return
        }
        job = scope.launch {
            beginBusy()
            try {
                val check = pick.setup.accept(uri)
                // D10: locked while the file was checked, whatever the check found: nothing is applied, no message.
                if (pick.launchedMode == SourceFlowMode.ChangeUnlocked && modeNow() != SourceFlowMode.ChangeUnlocked) {
                    pick.setup.discard(uri)
                    finish(pick)
                    return@launch
                }
                when (check) {
                    is FileCheck.Ok -> accepted(pick, check.file, mode)
                    is FileCheck.NotKdbx -> reject(pick, uri, PickFailure.NotKdbx, check.fileName)
                    is FileCheck.Unreadable -> reject(pick, uri, PickFailure.Unreadable, check.fileName)
                    is FileCheck.NoLastingAccess -> reject(pick, uri, PickFailure.NoLastingAccess, check.fileName)
                }
            } finally {
                endBusy()
            }
        }
    }

    /** "Trotzdem verwenden": the switch goes on. */
    fun onOlderConfirm() {
        val wait = waiting ?: return
        waiting = null
        mutableOlder.value = null
        job = scope.launch {
            beginBusy()
            try {
                save(wait.pick, wait.file, SourceFlowMode.ChangeUnlocked)
            } finally {
                endBusy()
            }
        }
    }

    /** "Abbrechen" or dismissing the dialog: the picked file is given up, the source stays, no message. */
    fun onOlderCancel() {
        val wait = waiting ?: return
        waiting = null
        mutableOlder.value = null
        wait.pick.setup.discard(wait.file.uri)
        finish(wait.pick)
    }

    /** The session locked: an open dialog counts as cancel (the file is discarded). */
    fun onLocked() {
        if (waiting != null) onOlderCancel()
    }

    /** Gives everything back when the activity is gone for good. */
    fun close() {
        closed = true
        startJob?.cancel()
        startJob = null
        job?.cancel()
        job = null
        busyTimer?.cancel()
        busyTimer = null
        waiting = null
        mutableOlder.value = null
        mutableBusy.value = false
        mutableRequest.value = null
        // Gives back a permission that was taken and not saved.
        current?.setup?.close()
        current = null
    }

    // ---- internals ----

    private fun launchPicker(rejectedUri: String?, changing: Boolean) {
        // A pick is in progress from here until it is finished: a second tap must not open a second picker.
        if (closed || current != null) return
        val setup = platform.startFileSetup()
        val pick = Pick(setup, changing, modeNow())
        current = pick
        // The start folder may need a binder call: off the main thread. Runs up to there at once.
        startJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val folder = try {
                setup.initialFolder(rejectedUri, changing)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                finish(pick)
                return@launch
            }
            if (current === pick) mutableRequest.value = PickRequest(++nextId, folder)
        }
    }

    private fun modeNow(): SourceFlowMode = when {
        !platform.settings.value.setupCompleted -> SourceFlowMode.FirstRun
        platform.session.value is SessionView.Unlocked -> SourceFlowMode.ChangeUnlocked
        else -> SourceFlowMode.ChangeLocked
    }

    private suspend fun accepted(pick: Pick, file: PickedFile, mode: SourceFlowMode) {
        if (mode == SourceFlowMode.ChangeUnlocked) {
            val older = try {
                pick.setup.olderVersion(file)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // No dialog; the switch itself reports a real problem.
                null
            }
            if (modeNow() != SourceFlowMode.ChangeUnlocked) {
                // D10: locked while the check ran (it then finds nothing, too): nothing is applied, no message.
                pick.setup.discard(file.uri)
                finish(pick)
                return
            }
            if (older != null) {
                waiting = Waiting(pick, file)
                endBusy()
                mutableOlder.value = toOlderVersionInfo(older, time)
                return
            }
        }
        save(pick, file, mode)
    }

    private suspend fun save(pick: Pick, file: PickedFile, mode: SourceFlowMode) {
        val result: ChooseResult? = try {
            pick.setup.choose(file)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            pick.setup.discard(file.uri)
            null
        }
        when (result) {
            is ChooseResult.Saved -> {
                endBusy()
                finish(pick)
                // A switch that locked the app shows the lock notice only (DS-13); the first run has no message.
                if (mode != SourceFlowMode.FirstRun && !result.lockedForNewDatabase) messages.post(UiMessage.SourceSaved)
                onSaved(mode, result.fileSizeBytes)
            }
            // D10: the app locked before the commit; choose() has already given the permission back. No message.
            ChooseResult.Discarded -> {
                endBusy()
                finish(pick)
            }
            // choose() has already discarded the uri.
            is ChooseResult.Failed, null -> reject(pick, file.uri, PickFailure.Unreadable, file.displayName)
        }
    }

    /** Back on the screen the picker was opened from, with the message and its "Neu wählen" action. */
    private fun reject(pick: Pick, uri: String, reason: PickFailure, fileName: String) {
        rejected = Rejected(uri, pick.changing)
        // Already given back by the setup; once more is harmless and keeps the rule in one place.
        pick.setup.discard(uri)
        endBusy()
        finish(pick)
        messages.post(UiMessage.PickFailed(reason, fileName))
    }

    private fun finish(pick: Pick) {
        pick.setup.close()
        if (current === pick) current = null
    }

    private fun discardStray(uri: String) {
        val setup = platform.startFileSetup()
        try {
            setup.discard(uri)
        } finally {
            setup.close()
        }
    }

    private fun beginBusy() {
        busyTimer?.cancel()
        busyTimer = scope.launch {
            delay(BUSY_AFTER_MILLIS)
            mutableBusy.value = true
        }
    }

    private fun endBusy() {
        busyTimer?.cancel()
        busyTimer = null
        mutableBusy.value = false
    }

    private companion object {
        /** A quick check shows nothing; the busy view only appears when it is still running after this time. */
        const val BUSY_AFTER_MILLIS = 300L
    }
}
