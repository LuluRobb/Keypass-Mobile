package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.ui.setup.SourceFlowMode
import de.lulurobb.keypassmobile.ui.setup.SourceFlowScreen
import java.util.UUID

/** Which screen the app shows. Derived from the platform state by [computeRoute], never stored. */
sealed interface AppRoute {
    /** Server form or file browser of a source flow (setup steps 1 and 2, "Quelle ändern"). */
    data class SourceFlow(val mode: SourceFlowMode, val screen: SourceFlowScreen) : AppRoute

    /** Setup step 4. */
    data object SystemSetup : AppRoute

    /** Unlock screen; [firstRun] is setup step 3. */
    data class Unlock(val firstRun: Boolean) : AppRoute

    data object EntryList : AppRoute

    data class EntryDetail(val id: UUID) : AppRoute

    data object Settings : AppRoute
}

/** What the user opened on top of the entry list while unlocked; the only navigation state kept by hand. */
sealed interface InAppScreen {
    data class Detail(val id: UUID) : InAppScreen

    data object Settings : InAppScreen

    /** "Quelle ändern" from the settings (a [SourceFlowMode.ChangeUnlocked] flow). */
    data object ChangeSource : InAppScreen
}

/** A source flow that takes the screen by itself: [SourceFlowMode.FirstRun] or [SourceFlowMode.ChangeLocked]. [screen] null = it shows nothing. */
data class ActiveFlow(val mode: SourceFlowMode, val screen: SourceFlowScreen?)

/**
 * The route for the given platform state, first match wins:
 * 1. an [ActiveFlow] that shows a screen: that screen
 * 2. no source: setup step 1
 * 3. unlocked, setup not completed: setup step 4
 * 4. not unlocked (locked or unlocking): the unlock screen
 * 5. otherwise the top of [stack]; the entry list when it is empty
 *
 * [changeScreen] is the screen of the unlocked source change, used when [InAppScreen.ChangeSource] is on top.
 */
fun computeRoute(
    unlocked: Boolean,
    hasSource: Boolean,
    setupCompleted: Boolean,
    flow: ActiveFlow?,
    stack: List<InAppScreen>,
    changeScreen: SourceFlowScreen?,
): AppRoute {
    val flowScreen = flow?.screen
    if (flow != null && flowScreen != null) return AppRoute.SourceFlow(flow.mode, flowScreen)
    if (!hasSource) return AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Server)
    if (unlocked && !setupCompleted) return AppRoute.SystemSetup
    if (!unlocked) return AppRoute.Unlock(firstRun = !setupCompleted)
    return when (val top = stack.lastOrNull()) {
        null -> AppRoute.EntryList
        is InAppScreen.Detail -> AppRoute.EntryDetail(top.id)
        InAppScreen.Settings -> AppRoute.Settings
        InAppScreen.ChangeSource ->
            AppRoute.SourceFlow(SourceFlowMode.ChangeUnlocked, changeScreen ?: SourceFlowScreen.Server)
    }
}
