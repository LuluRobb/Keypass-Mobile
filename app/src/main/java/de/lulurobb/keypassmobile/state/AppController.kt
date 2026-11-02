package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.core.sync.SyncProblem
import de.lulurobb.keypassmobile.platform.session.SyncReport
import de.lulurobb.keypassmobile.platform.session.SyncResult
import de.lulurobb.keypassmobile.platform.session.SyncTrigger
import de.lulurobb.keypassmobile.ui.common.UiMessage
import de.lulurobb.keypassmobile.ui.detail.EntryDetailModel
import de.lulurobb.keypassmobile.ui.list.EntryListModel
import de.lulurobb.keypassmobile.ui.settings.SettingsModel
import de.lulurobb.keypassmobile.ui.setup.SourceFlowMode
import de.lulurobb.keypassmobile.ui.setup.SourceFlowModel
import de.lulurobb.keypassmobile.ui.setup.SystemSetupModel
import de.lulurobb.keypassmobile.ui.unlock.UnlockModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

/** What the app shows now, with the state holder of that screen. [route] says which screen, [model] is its state holder. */
sealed interface AppScreen {
    val route: AppRoute
    val model: Any

    class Source(override val route: AppRoute.SourceFlow, override val model: SourceFlowModel) : AppScreen

    class SystemSetup(override val model: SystemSetupModel) : AppScreen {
        override val route: AppRoute = AppRoute.SystemSetup
    }

    class Unlock(override val route: AppRoute.Unlock, override val model: UnlockModel) : AppScreen

    class EntryList(override val model: EntryListModel) : AppScreen {
        override val route: AppRoute = AppRoute.EntryList
    }

    class EntryDetail(override val route: AppRoute.EntryDetail, override val model: EntryDetailModel) : AppScreen

    class Settings(override val model: SettingsModel) : AppScreen {
        override val route: AppRoute = AppRoute.Settings
    }
}

/**
 * App-level logic of the main activity: owns the state holders of all screens and
 * decides which one is shown ([screen]). The route is derived from [AppPlatform]
 * (see [computeRoute]); only the screens opened on top of the entry list are kept by hand.
 *
 * Models that hold vault references (list, detail, settings, the unlocked source change) get
 * a child scope that is cancelled when the model is discarded, and are discarded at the
 * latest when the session locks. All calls come from the main thread; navigation actions
 * run one after the other, so a callback of a model that fires during its construction
 * (settings without source, detail of a missing entry) is applied after the current step.
 *
 * Messages: one [AppMessages] for all screens and the sync reports that are not answered by a call
 * (see [onReport]). The lock message of every real Unlocked -> Locked transition is not a snackbar: it
 * goes to the unlock model, which shows it as a notice until the next successful unlock.
 */
class AppController(
    val platform: AppPlatform,
    private val scope: CoroutineScope,
    private val time: TimeSource = TimeSource(),
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    val messages = AppMessages()

    private class Owned<out T>(val model: T, private val scope: CoroutineScope) {
        fun cancel() = scope.cancel()
    }

    private class FlowHandle(val mode: SourceFlowMode, val model: SourceFlowModel, private val scope: CoroutineScope) {
        /** Closes the connection and wipes the password field, then stops the model's coroutines. */
        fun dispose() {
            model.close()
            scope.cancel()
        }
    }

    /** Screens above the entry list while unlocked. */
    private val stack = mutableListOf<InAppScreen>()

    private val lockTracker = LockTracker(platform.session.value)
    private var seenReportId = platform.lastSyncReport.value?.id ?: 0L

    private var firstRunFlow: FlowHandle? = null
    private var lockedChangeFlow: FlowHandle? = null
    private var unlockedChangeFlow: FlowHandle? = null

    private var unlockModel: Owned<UnlockModel>? = null

    /** firstRun the current [unlockModel] was built with. */
    private var unlockFirstRun = false

    /** Lock message for the next unlock model, when a lock found none to hand it to. */
    private var pendingLockNotice: UiMessage? = null
    private var listModel: Owned<EntryListModel>? = null
    private val detailModels = mutableMapOf<UUID, Owned<EntryDetailModel>>()
    private var settingsModel: Owned<SettingsModel>? = null
    private var systemSetupModel: SystemSetupModel? = null

    private val pending = ArrayDeque<() -> Unit>()
    private var running = false
    private var started = false
    private var closed = false

    private val mutableScreen: MutableStateFlow<AppScreen>

    /** The screen to show. Changes only when the route or its model changes. */
    val screen: StateFlow<AppScreen> get() = mutableScreen

    private val route: AppRoute get() = mutableScreen.value.route

    init {
        // Callbacks of models built by resolve() are queued and applied right after it.
        running = true
        mutableScreen = MutableStateFlow(resolve())
        started = true
        drain()
        running = false
        messages.bindClipboard(platform.clipboard, platform.session, scope)
        scope.launch {
            combine(platform.session, platform.settings) { session, _ -> session }
                .collect { session -> act { onSession(session) } }
        }
        scope.launch {
            platform.lastSyncReport.collect { onReport(it) }
        }
    }

    // ---- navigation from the screens ----

    /** Entry click in the list. */
    fun openEntry(id: UUID) = act {
        if (route == AppRoute.EntryList) stack += InAppScreen.Detail(id)
    }

    /** Settings button of the list. */
    fun openSettings() = act {
        if (route == AppRoute.EntryList) stack += InAppScreen.Settings
    }

    /** "Quelle ändern" in the settings. */
    fun editSource() = act {
        if (route == AppRoute.Settings) stack += InAppScreen.ChangeSource
    }

    /**
     * "Quelle wählen" of the unlock notice (offline copy damaged): locked source change. In the
     * first run the source flow of the setup itself is reopened.
     */
    fun chooseSource() = act {
        val current = route
        if (current !is AppRoute.Unlock) return@act
        if (current.firstRun) {
            reopenFirstRun()
        } else if (lockedChangeFlow == null) {
            lockedChangeFlow = startFlow(SourceFlowMode.ChangeLocked)
        }
    }

    /**
     * System back. True when the app handled it (screen popped, flow went back); false at the
     * roots (entry list, unlock screen, setup step 1 and 4), where the activity moves the task to the back.
     */
    fun onBack(): Boolean {
        var handled = false
        act { handled = back() }
        return handled
    }

    /** onResume: the user may come back from the system pages (also from the fingerprint settings). */
    fun refreshSystem() {
        settingsModel?.model?.refreshSystem()
        systemSetupModel?.refresh()
        unlockModel?.model?.refreshFingerprint()
    }

    /**
     * onStop of the activity, not for a configuration change. The first-run connection at the unlock step
     * (setup step 3) is closed so the SMB password does not wait in memory while the app is in the
     * background; going back from the unlock screen then shows the server form again.
     */
    fun onStop() {
        firstRunFlow?.model?.disconnect()
    }

    /** Discards all models and closes the open source connections. Call when the activity is gone for good. */
    fun close() {
        closed = true
        discardInApp()
        unlockedChangeFlow?.dispose()
        unlockedChangeFlow = null
        lockedChangeFlow?.dispose()
        lockedChangeFlow = null
        firstRunFlow?.dispose()
        firstRunFlow = null
        unlockModel?.cancel()
        unlockModel = null
        systemSetupModel = null
    }

    private fun back(): Boolean = when (val current = route) {
        is AppRoute.SourceFlow -> {
            val handle = flowFor(current.mode)
            when {
                handle?.model?.onBack() == true -> true
                current.mode == SourceFlowMode.FirstRun -> false
                current.mode == SourceFlowMode.ChangeLocked -> {
                    lockedChangeFlow?.dispose()
                    lockedChangeFlow = null
                    true
                }
                else -> {
                    stack.removeLastOrNull()
                    true
                }
            }
        }
        AppRoute.SystemSetup -> false
        is AppRoute.Unlock -> if (current.firstRun) {
            reopenFirstRun()
            true
        } else {
            false
        }
        AppRoute.EntryList -> false
        is AppRoute.EntryDetail,
        AppRoute.Settings,
        -> {
            stack.removeLastOrNull()
            true
        }
    }

    /** Back from setup step 3 to the browser; a new flow (form) when the old one is gone, e.g. after process death. */
    private fun reopenFirstRun() {
        val handle = firstRunFlow ?: startFlow(SourceFlowMode.FirstRun).also { firstRunFlow = it }
        handle.model.reopen()
    }

    private fun flowFor(mode: SourceFlowMode): FlowHandle? = when (mode) {
        SourceFlowMode.FirstRun -> firstRunFlow
        SourceFlowMode.ChangeLocked -> lockedChangeFlow
        SourceFlowMode.ChangeUnlocked -> unlockedChangeFlow
    }

    // ---- platform changes ----

    private fun onSession(session: SessionView) {
        // A failed unlock ends in Locked again; the unlock model shows its own result.
        val locked = lockTracker.onSession(session) ?: return
        // A message of the open session (a copy countdown, "Zwischenablage gelöscht") has no place on the unlock screen.
        messages.dismiss()
        collapse(lockMessage(locked.reason))
    }

    /** A lock: everything back to the unlock screen, nothing of the open vault stays. [lockNotice] is shown there. */
    private fun collapse(lockNotice: UiMessage?) {
        stack.clear()
        discardInApp()
        unlockedChangeFlow?.dispose()
        unlockedChangeFlow = null
        val current = unlockModel
        if (current != null && unlockFirstRun == !platform.settings.value.setupCompleted) {
            current.model.reset(lockNotice)
            return
        }
        // No model, or one built for the other mode (the setup was completed since): the next unlock screen builds a new one.
        current?.cancel()
        unlockModel = null
        pendingLockNotice = lockNotice
    }

    /**
     * Sync reports are shown once per [SyncReport.id]; the report present at creation counts as seen.
     * REQUESTED and SETUP stay silent, the models post from their own call results. After an
     * unlock and scheduled only a newer opened version and a changed server are worth a message.
     */
    private fun onReport(report: SyncReport?) {
        if (report == null || report.id <= seenReportId) return
        seenReportId = report.id
        when (report.trigger) {
            SyncTrigger.AFTER_UNLOCK,
            SyncTrigger.SCHEDULED,
            -> reportMessage(report.result)?.let(messages::post)
            SyncTrigger.REQUESTED,
            SyncTrigger.SETUP,
            -> Unit
        }
    }

    private fun reportMessage(result: SyncResult): UiMessage? = when (result) {
        SyncResult.Updated -> UiMessage.SyncUpdated
        is SyncResult.Failed -> if (result.problem == SyncProblem.SERVER_CHANGED) UiMessage.SyncOtherServer else null
        SyncResult.UpToDate,
        SyncResult.CandidateReady,
        SyncResult.NewVersionRejected,
        SyncResult.NotConfigured,
        -> null
    }

    private fun onSourceSaved(mode: SourceFlowMode, fileSizeBytes: Long?) = act {
        when (mode) {
            SourceFlowMode.FirstRun -> {
                val existing = unlockModel != null
                val unlock = ensureUnlock(firstRun = true).model
                // Back and forth to another file: no stale notice from the old one.
                if (existing) unlock.reset()
                unlock.setFirstRunFileSize(fileSizeBytes)
            }
            SourceFlowMode.ChangeLocked -> {
                lockedChangeFlow?.dispose()
                lockedChangeFlow = null
                unlockModel?.model?.reset()
            }
            // The flow is closed by the next resolve, it is no longer on the stack.
            SourceFlowMode.ChangeUnlocked -> stack.remove(InAppScreen.ChangeSource)
        }
    }

    // ---- derived state ----

    /** Runs [action] and then brings [screen] up to date; nested calls wait for the running one. */
    private fun act(action: () -> Unit) {
        if (closed) return
        pending.addLast(action)
        if (running) return
        running = true
        try {
            drain()
        } finally {
            running = false
        }
    }

    private fun drain() {
        while (pending.isNotEmpty()) {
            pending.removeFirst()()
            update()
        }
    }

    private fun update() {
        if (!started) return
        val next = resolve()
        val current = mutableScreen.value
        if (next.route != current.route || next.model !== current.model) mutableScreen.value = next
    }

    /** Computes the route from the platform state and brings the models in line with it. */
    private fun resolve(): AppScreen {
        val settings = platform.settings.value
        val session = platform.session.value
        val unlocked = session is SessionView.Unlocked
        val hasSource = settings.source != null
        // A leftover stack must not show up after a lock that was not seen as a transition, or without a source.
        if (session is SessionView.Locked || !hasSource) stack.clear()
        if (unlocked && hasSource) {
            // The first-run unlock worked, or the session was unlocked elsewhere (autofill): the locked flows are over.
            firstRunFlow?.dispose()
            firstRunFlow = null
            lockedChangeFlow?.dispose()
            lockedChangeFlow = null
            pendingLockNotice = null
        }
        if (!hasSource && firstRunFlow == null && lockedChangeFlow == null) {
            firstRunFlow = startFlow(SourceFlowMode.FirstRun)
        }
        if (InAppScreen.ChangeSource !in stack) {
            unlockedChangeFlow?.dispose()
            unlockedChangeFlow = null
        } else if (unlocked && unlockedChangeFlow == null) {
            unlockedChangeFlow = startFlow(SourceFlowMode.ChangeUnlocked)
        }
        val active = (lockedChangeFlow ?: firstRunFlow)?.let { ActiveFlow(it.mode, it.model.screen.value) }
        val route = computeRoute(
            unlocked = unlocked,
            hasSource = hasSource,
            setupCompleted = settings.setupCompleted,
            flow = active,
            stack = stack,
            changeScreen = unlockedChangeFlow?.model?.screen?.value,
        )
        syncModels(route)
        return screenFor(route)
    }

    private fun syncModels(route: AppRoute) {
        val inApp = route is AppRoute.EntryList ||
            route is AppRoute.EntryDetail ||
            route is AppRoute.Settings ||
            (route is AppRoute.SourceFlow && route.mode == SourceFlowMode.ChangeUnlocked)
        if (!inApp) {
            discardInApp()
        } else {
            if (listModel == null) {
                val own = childScope()
                listModel = Owned(EntryListModel(platform, own, time, messages, computeDispatcher), own)
            }
            val ids = stack.filterIsInstance<InAppScreen.Detail>().map { it.id }.toSet()
            for (id in detailModels.keys.filter { it !in ids }) detailModels.remove(id)?.cancel()
            for (id in ids) {
                if (id !in detailModels) {
                    val own = childScope()
                    detailModels[id] = Owned(EntryDetailModel(platform, own, id) { act { stack.remove(InAppScreen.Detail(id)) } }, own)
                }
            }
            if (InAppScreen.Settings !in stack) {
                settingsModel?.cancel()
                settingsModel = null
            } else if (settingsModel == null) {
                val own = childScope()
                settingsModel = Owned(SettingsModel(platform, own, time, messages) { act { stack.remove(InAppScreen.Settings) } }, own)
            }
        }
        systemSetupModel = if (route == AppRoute.SystemSetup) {
            systemSetupModel ?: SystemSetupModel(platform, messages) { unlockModel?.model?.lastFingerprintStored ?: false }
        } else {
            null
        }
    }

    private fun screenFor(route: AppRoute): AppScreen = when (route) {
        is AppRoute.SourceFlow -> AppScreen.Source(route, checkNotNull(flowFor(route.mode)).model)
        AppRoute.SystemSetup -> AppScreen.SystemSetup(checkNotNull(systemSetupModel))
        is AppRoute.Unlock -> AppScreen.Unlock(route, ensureUnlock(route.firstRun).model)
        AppRoute.EntryList -> AppScreen.EntryList(checkNotNull(listModel).model)
        is AppRoute.EntryDetail -> AppScreen.EntryDetail(route, checkNotNull(detailModels[route.id]).model)
        AppRoute.Settings -> AppScreen.Settings(checkNotNull(settingsModel).model)
    }

    /** The unlock model; built again when the mode changed (setup completed). It stays until a lock finds it stale. */
    private fun ensureUnlock(firstRun: Boolean): Owned<UnlockModel> {
        unlockModel?.let {
            if (unlockFirstRun == firstRun) return it
            it.cancel()
            unlockModel = null
        }
        // The lock message belongs to the normal unlock screen; the setup step does not show it.
        val notice = if (firstRun) null else pendingLockNotice
        pendingLockNotice = null
        val own = childScope()
        return Owned(UnlockModel(platform, own, time, messages, fillTarget = null, firstRun = firstRun, lockNotice = notice), own).also {
            unlockModel = it
            unlockFirstRun = firstRun
        }
    }

    private fun startFlow(mode: SourceFlowMode): FlowHandle {
        val own = childScope()
        val model = SourceFlowModel(platform, own, time, messages, mode) { size -> onSourceSaved(mode, size) }
        // Server <-> browser and the first-run "shows nothing" change the route.
        own.launch { model.screen.collect { act { } } }
        return FlowHandle(mode, model, own)
    }

    private fun discardInApp() {
        listModel?.cancel()
        listModel = null
        detailModels.values.forEach { it.cancel() }
        detailModels.clear()
        settingsModel?.cancel()
        settingsModel = null
    }

    /** Child scope of the controller; cancelling it stops the model's collectors, which hold vault references. */
    private fun childScope(): CoroutineScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
}
