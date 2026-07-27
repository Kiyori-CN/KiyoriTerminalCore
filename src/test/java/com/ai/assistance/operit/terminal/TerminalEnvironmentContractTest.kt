package com.ai.assistance.operit.terminal

import com.ai.assistance.operit.terminal.view.domain.KIYORI_WELCOME_MESSAGE
import com.ai.assistance.operit.terminal.ui.completedCommandOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEnvironmentContractTest {
    @Test
    fun nodeSetupUsesTheSharedNpmGlobalBin() {
        val commands = TerminalEnvironmentContract.buildNodePackageSetupCommands(
            packages = listOf("typescript")
        )

        assertEquals(3, commands.size)
        assertEquals("npm install -g pnpm typescript", commands[2])
        assertTrue(commands.none { command -> command.startsWith("pnpm add -g") })
        assertTrue(commands.none { command -> command.contains(".bashrc") })
    }

    @Test
    fun nodeToolchainRequiresAnExactCompletionMarker() {
        val commandEcho = TerminalEnvironmentContract.NODE_TOOLCHAIN_CHECK_COMMAND

        assertTrue(commandEcho.contains(">= 24"))
        assertTrue(commandEcho.contains("global_bin=\"\$(npm prefix -g)/bin\""))
        assertTrue(commandEcho.contains("\"\$global_bin/pnpm\" --version"))
        assertTrue(commandEcho.contains("\"\$global_bin/tsc\" --version"))
        assertFalse(commandEcho.contains("&& pnpm --version"))
        assertFalse(commandEcho.contains("&& tsc --version"))
        assertFalse(TerminalEnvironmentContract.isNodeToolchainReady(commandEcho))
        assertTrue(
            TerminalEnvironmentContract.isNodeToolchainReady(
                "${TerminalEnvironmentContract.NODE_TOOLCHAIN_READY_MARKER}\r\n"
            )
        )
    }

    @Test
    fun setupDetectionUsesOnlyTheAuthoritativeCompletionOutput() {
        val commandId = "environment-check"
        val sessionId = "terminal-session"
        val marker = TerminalEnvironmentContract.NODE_TOOLCHAIN_READY_MARKER

        assertNull(
            completedCommandOutput(
                CommandExecutionEvent(
                    commandId = commandId,
                    sessionId = sessionId,
                    outputChunk = marker,
                    isCompleted = false,
                )
            )
        )
        assertEquals(
            marker,
            completedCommandOutput(
                CommandExecutionEvent(
                    commandId = commandId,
                    sessionId = sessionId,
                    outputChunk = marker,
                    isCompleted = true,
                )
            )
        )
    }

    @Test
    fun welcomeMessageUsesCompactKiyoriBranding() {
        val visibleLines = KIYORI_WELCOME_MESSAGE.lineSequence().filter { it.isNotEmpty() }.toList()

        assertTrue(KIYORI_WELCOME_MESSAGE.contains("Kiyori Ubuntu environment on Android"))
        assertFalse(KIYORI_WELCOME_MESSAGE.contains("Your portable Ubuntu environment"))
        assertTrue(visibleLines.all { line -> line.length <= 48 })
    }
}
