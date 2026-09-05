package com.ai.assistance.operit.terminal.main

import com.ai.assistance.operit.terminal.main.TerminalRoutes.SETUP_ROUTE
import com.ai.assistance.operit.terminal.main.TerminalRoutes.SETTINGS_ROUTE
import com.ai.assistance.operit.terminal.main.TerminalRoutes.TERMINAL_HOME_ROUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalStartDestinationTest {
    @Test
    fun forcedSetupWinsBeforeFirstFrameNavigationCanRaceWithUserInput() {
        assertEquals(SETUP_ROUTE, resolveTerminalStartDestination(forceShowSetup = true, isFirstLaunch = false))
    }

    @Test
    fun persistedFirstLaunchChoosesSetupAndReturningUsersChooseHome() {
        assertEquals(SETUP_ROUTE, resolveTerminalStartDestination(forceShowSetup = false, isFirstLaunch = true))
        assertEquals(TERMINAL_HOME_ROUTE, resolveTerminalStartDestination(forceShowSetup = false, isFirstLaunch = false))
    }

    @Test
    fun systemBackUnwindsTerminalRoutesBeforeClosingTheAiComputerPanel() {
        assertEquals(
            TerminalBackAction.RETURN_TO_TERMINAL_HOME,
            resolveTerminalBackAction(SETUP_ROUTE),
        )
        assertEquals(
            TerminalBackAction.RETURN_TO_TERMINAL_HOME,
            resolveTerminalBackAction(SETTINGS_ROUTE),
        )
        assertEquals(
            TerminalBackAction.CLOSE_TERMINAL,
            resolveTerminalBackAction(TERMINAL_HOME_ROUTE),
        )
    }

    @Test
    fun routeRequestIsIdempotentWhenTheSynchronousRouteIsAlreadyActive() {
        assertTrue(shouldRequestTerminalRoute(TERMINAL_HOME_ROUTE, SETUP_ROUTE))
        assertFalse(shouldRequestTerminalRoute(SETUP_ROUTE, SETUP_ROUTE))
        assertFalse(shouldRequestTerminalRoute(SETTINGS_ROUTE, SETTINGS_ROUTE))
    }
}
