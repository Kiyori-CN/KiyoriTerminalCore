package com.ai.assistance.operit.terminal

import com.ai.assistance.operit.terminal.view.domain.INITIAL_SCREEN_RESET_SEQUENCE
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

        assertEquals(3, commands.size)
        assertTrue(commands.all { it.startsWith("DEBIAN_FRONTEND=noninteractive ") })
        assertTrue(commands[0].contains("dpkg --configure -a"))
        assertTrue(commands.drop(1).all { it.contains("apt-get ") })
        assertTrue(commands.none { it.contains(" apt ") })
    }

    @Test
    fun aptAndNodeSetupUsePinnedStableInputs() {
        val aptCommand = TerminalEnvironmentContract.buildAptInstallCommand(
            listOf("python3-venv", "package'quoted")
        )
        val nodeCommand = TerminalEnvironmentContract.buildNodeJsInstallCommand()

        assertEquals(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y 'python3-venv' 'package'\\''quoted'",
            aptCommand,
        )
        assertTrue(nodeCommand.contains("https://nodejs.org/dist/v${TerminalEnvironmentContract.NODE_LTS_VERSION}/"))
        assertTrue(nodeCommand.contains(TerminalEnvironmentContract.NODE_LTS_SHA256))
        assertTrue(nodeCommand.contains(TerminalEnvironmentContract.NODE_X64_SHA256))
        assertFalse(nodeCommand.contains("deb.nodesource.com"))
    }

    @Test
    fun rubyUsesTheUbuntuAptPackageContract() {
        assertEquals("ruby", TerminalEnvironmentContract.RUBY_PACKAGE_ID)
        assertEquals("ruby", TerminalEnvironmentContract.RUBY_APT_PACKAGE)
        assertEquals(
            "DEBIAN_FRONTEND=noninteractive apt-get install -y 'ruby'",
            TerminalEnvironmentContract.buildAptInstallCommand(listOf(TerminalEnvironmentContract.RUBY_APT_PACKAGE)),
        )
    }

    @Test
    fun nodeSetupUsesTheSharedNpmGlobalBin() {
        val commands = TerminalEnvironmentContract.buildNodePackageSetupCommands(
            packages = listOf("typescript"),
            registryUrl = "https://registry.example.test/"
        )

        assertEquals(1, commands.size)
        val script = commands.single()
        assertTrue(script.contains("--ignore-scripts=false --include=optional"))
        assertTrue(script.contains("pnpm/install.js"))
        assertTrue(script.contains("packageImportMethod copy"))
        assertTrue(script.contains("https://registry.example.test/"))
        assertFalse(script.contains("npm cache clean"))
        assertFalse(script.contains("npm config set registry"))

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

        assertTrue(commandEcho.contains("process.versions.node"))
        assertTrue(commandEcho.contains(".local/bin"))
        assertTrue(commandEcho.contains("--rootDir . --outDir dist"))
        assertFalse(commandEcho.contains("npm prefix -g"))
        assertFalse(commandEcho.contains("11.19.0"))
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

        assertTrue(command.contains("https://nodejs.org/dist/v${TerminalEnvironmentContract.NODE_LTS_VERSION}/"))
        assertTrue(command.contains("sha256sum -c"))
        assertTrue(command.contains("export PATH=\"\$HOME/.local/bin:\$PATH\""))
    }

    @Test
    fun gradleInstallerUsesPinnedOfficialDistribution() {
        val command = TerminalEnvironmentContract.buildGradleInstallCommand()

        assertTrue(command.contains("gradle-${TerminalEnvironmentContract.GRADLE_VERSION}-bin.zip"))
        assertTrue(command.contains(TerminalEnvironmentContract.GRADLE_SHA256))
        assertTrue(command.contains("unzip -q"))
        assertTrue(command.contains("version \\\"${TerminalEnvironmentContract.GRADLE_REQUIRED_JAVA_MAJOR}"))
        assertTrue(command.contains(".local/opt/gradle-${TerminalEnvironmentContract.GRADLE_VERSION}"))
        assertFalse(command.contains("apt-get install -y gradle"))
    }

    @Test
    fun setupDetectionDoesNotReadCompletionEventBody() {
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
        assertNull(
            completedCommandOutput(
                CommandExecutionEvent(
                    commandId = commandId,
                    sessionId = sessionId,
                    outputChunk = "",
                    isCompleted = true,
                )
            )
        )
    }

    @Test
    fun readyScreenResetDoesNotInjectAProductBanner() {
        assertEquals("\u001B[2J\u001B[H", INITIAL_SCREEN_RESET_SEQUENCE)
        assertFalse(INITIAL_SCREEN_RESET_SEQUENCE.contains("Kiyori"))
        assertFalse(INITIAL_SCREEN_RESET_SEQUENCE.contains("Operit"))
    }
}
