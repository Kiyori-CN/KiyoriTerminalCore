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
    fun unattendedSystemRepairCannotOpenDebconfPrompts() {
        val commands = TerminalEnvironmentContract.SYSTEM_REPAIR_COMMANDS

        assertEquals(4, commands.size)
        assertTrue(commands.all { it.startsWith("DEBIAN_FRONTEND=noninteractive ") })
        assertTrue(commands[0].contains("dpkg --configure -a"))
        assertTrue(commands.drop(1).all { it.contains("apt-get ") })
        assertTrue(commands.none { it.contains(" apt ") })
    }

    @Test
    fun aptAndNodeSetupShareTheUnattendedInstallContract() {
        val aptCommand = TerminalEnvironmentContract.buildAptInstallCommand(
            listOf("python3-venv", "package'quoted")
        )
        val nodeCommand = TerminalEnvironmentContract.buildNodeJsInstallCommand()

        assertEquals(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y 'python3-venv' 'package'\\''quoted'",
            aptCommand,
        )
        assertTrue(nodeCommand.contains("DEBIAN_FRONTEND=noninteractive bash -"))
        assertTrue(nodeCommand.endsWith("DEBIAN_FRONTEND=noninteractive apt-get install -y nodejs"))
    }

    @Test
    fun nodeSetupUsesTheSharedNpmGlobalBin() {
        val commands = TerminalEnvironmentContract.buildNodePackageSetupCommands(
            packages = listOf("typescript"),
            registryUrl = "https://registry.example.test/"
        )

        assertEquals(3, commands.size)
        assertEquals("npm config set registry 'https://registry.example.test/'", commands[0])
        assertEquals("npm install -g pnpm 'typescript'", commands[2])
        assertTrue(commands.none { command -> command.startsWith("pnpm add -g") })
        assertTrue(commands.none { command -> command.contains(".bashrc") })
    }

    @Test
    fun pythonSetupUsesTheSelectedIndexAndQuotesIt() {
        val commands = TerminalEnvironmentContract.buildPipConfigurationCommands(
            "https://pypi.example.test/simple?mirror=one'two"
        )

        assertEquals(5, commands.size)
        assertTrue(commands[2].contains("index-url = https://pypi.example.test/simple?mirror=one'\\''two"))
        assertTrue(commands[4].contains("index-url = \"https://pypi.example.test/simple?mirror=one'\\''two\""))
    }

    @Test
    fun userLocalInstallPathsArePersistedAndActivatedInTheCurrentShell() {
        assertEquals(
            listOf(
                "pipx ensurepath",
                "export PATH=\"\$HOME/.local/bin:\$PATH\"",
            ),
            TerminalEnvironmentContract.PIPX_POST_INSTALL_COMMANDS,
        )
        assertEquals(
            listOf("source \"\$HOME/.cargo/env\""),
            TerminalEnvironmentContract.RUSTUP_POST_INSTALL_COMMANDS,
        )
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
    fun nodeInstallerIsReusableAsThePnpmPrerequisite() {
        val command = TerminalEnvironmentContract.buildNodeJsInstallCommand()

        assertTrue(command.contains("setup_24.x"))
        assertTrue(command.contains("DEBIAN_FRONTEND=noninteractive"))
        assertTrue(command.endsWith("apt-get install -y nodejs"))
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
