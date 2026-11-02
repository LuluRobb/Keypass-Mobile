package de.lulurobb.keypassmobile.state

import de.lulurobb.keypassmobile.ui.setup.SourceFlowMode
import de.lulurobb.keypassmobile.ui.setup.SourceFlowScreen
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals

class AppRouteTest {

    private val id = UUID.fromString("00000000-0000-0000-0000-000000000001")

    private fun route(
        unlocked: Boolean = false,
        hasSource: Boolean = true,
        setupCompleted: Boolean = true,
        flow: ActiveFlow? = null,
        stack: List<InAppScreen> = emptyList(),
        changeScreen: SourceFlowScreen? = null,
    ) = computeRoute(unlocked, hasSource, setupCompleted, flow, stack, changeScreen)

    // ---- 1: a flow that shows a screen ----

    @Test
    fun `a first-run flow shows its server or browse screen`() {
        for (screen in SourceFlowScreen.entries) {
            assertEquals(
                AppRoute.SourceFlow(SourceFlowMode.FirstRun, screen),
                route(hasSource = false, setupCompleted = false, flow = ActiveFlow(SourceFlowMode.FirstRun, screen)),
            )
        }
    }

    @Test
    fun `a first-run flow that went back from step 3 shows the browser`() {
        assertEquals(
            AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Browse),
            route(setupCompleted = false, flow = ActiveFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Browse)),
        )
    }

    @Test
    fun `a locked source change shows its screen instead of the unlock screen`() {
        for (screen in SourceFlowScreen.entries) {
            assertEquals(
                AppRoute.SourceFlow(SourceFlowMode.ChangeLocked, screen),
                route(flow = ActiveFlow(SourceFlowMode.ChangeLocked, screen)),
            )
        }
    }

    @Test
    fun `an active flow wins over every other row`() {
        val flow = ActiveFlow(SourceFlowMode.ChangeLocked, SourceFlowScreen.Browse)
        val expected = AppRoute.SourceFlow(SourceFlowMode.ChangeLocked, SourceFlowScreen.Browse)
        assertEquals(expected, route(unlocked = true, setupCompleted = false, flow = flow))
        assertEquals(expected, route(unlocked = true, stack = listOf(InAppScreen.Settings), flow = flow))
        assertEquals(expected, route(hasSource = false, flow = flow))
    }

    @Test
    fun `a flow that shows nothing is passed over`() {
        val flow = ActiveFlow(SourceFlowMode.FirstRun, null)
        assertEquals(AppRoute.Unlock(firstRun = true), route(setupCompleted = false, flow = flow))
        assertEquals(AppRoute.SystemSetup, route(unlocked = true, setupCompleted = false, flow = flow))
    }

    // ---- 2: no source ----

    @Test
    fun `without a source the route is setup step 1`() {
        val step1 = AppRoute.SourceFlow(SourceFlowMode.FirstRun, SourceFlowScreen.Server)
        assertEquals(step1, route(hasSource = false, setupCompleted = false))
        assertEquals(step1, route(hasSource = false, setupCompleted = false, flow = ActiveFlow(SourceFlowMode.FirstRun, null)))
        assertEquals(step1, route(unlocked = true, hasSource = false, setupCompleted = false))
    }

    // ---- 3: system setup ----

    @Test
    fun `unlocked with the setup not completed shows step 4`() {
        assertEquals(AppRoute.SystemSetup, route(unlocked = true, setupCompleted = false))
        assertEquals(AppRoute.SystemSetup, route(unlocked = true, setupCompleted = false, stack = listOf(InAppScreen.Settings)))
    }

    // ---- 4: unlock ----

    @Test
    fun `locked shows the unlock screen, as step 3 until the setup is completed`() {
        assertEquals(AppRoute.Unlock(firstRun = false), route(unlocked = false, setupCompleted = true))
        assertEquals(AppRoute.Unlock(firstRun = true), route(unlocked = false, setupCompleted = false))
    }

    @Test
    fun `a leftover stack does not show while locked`() {
        assertEquals(AppRoute.Unlock(firstRun = false), route(unlocked = false, stack = listOf(InAppScreen.Detail(id))))
    }

    // ---- 5: in-app stack ----

    @Test
    fun `unlocked with an empty stack shows the entry list`() {
        assertEquals(AppRoute.EntryList, route(unlocked = true))
    }

    @Test
    fun `the top of the stack decides`() {
        assertEquals(AppRoute.EntryDetail(id), route(unlocked = true, stack = listOf(InAppScreen.Detail(id))))
        assertEquals(AppRoute.Settings, route(unlocked = true, stack = listOf(InAppScreen.Settings)))
        assertEquals(
            AppRoute.SourceFlow(SourceFlowMode.ChangeUnlocked, SourceFlowScreen.Browse),
            route(
                unlocked = true,
                stack = listOf(InAppScreen.Settings, InAppScreen.ChangeSource),
                changeScreen = SourceFlowScreen.Browse,
            ),
        )
    }

    @Test
    fun `an unlocked source change without a screen yet shows the server form`() {
        assertEquals(
            AppRoute.SourceFlow(SourceFlowMode.ChangeUnlocked, SourceFlowScreen.Server),
            route(unlocked = true, stack = listOf(InAppScreen.Settings, InAppScreen.ChangeSource)),
        )
    }

    @Test
    fun `popping the top shows the screen below`() {
        val stack = listOf(InAppScreen.Settings, InAppScreen.ChangeSource)
        assertEquals(AppRoute.Settings, route(unlocked = true, stack = stack.dropLast(1)))
        assertEquals(AppRoute.EntryList, route(unlocked = true, stack = stack.dropLast(2)))
    }
}
