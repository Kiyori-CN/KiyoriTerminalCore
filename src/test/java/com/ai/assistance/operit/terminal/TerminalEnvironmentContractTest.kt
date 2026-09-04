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

        assertEquals(4, commands.size)
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
        assertTrue(nodeCommand.contains("node-v${TerminalEnvironmentContract.NODE_LTS_VERSION}-linux-arm64.tar.xz"))
        assertTrue(nodeCommand.contains(TerminalEnvironmentContract.NODE_LTS_SHA256))
        assertTrue(nodeCommand.contains("test \"${'$'}(uname -m)\" = \"aarch64\""))
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

        assertEquals(4, commands.size)
        assertEquals("npm config set registry 'https://registry.example.test/'", commands[0])
        assertTrue(commands[1].contains("npm config set prefix"))
        assertEquals(
            "NPM_CONFIG_PREFIX=\"\$HOME/.local\" npm install -g 'pnpm@${TerminalEnvironmentContract.PNPM_VERSION}' 'typescript@${TerminalEnvironmentContract.TYPESCRIPT_VERSION}'",
            commands[3],
        )
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

        assertTrue(commandEcho.contains("node -p 'process.version'"))
        assertTrue(commandEcho.contains("= \"v${TerminalEnvironmentContract.NODE_LTS_VERSION}\""))
        assertTrue(commandEcho.contains("\$HOME/.local/bin"))
        assertTrue(commandEcho.contains("global_bin=\"\$(NPM_CONFIG_PREFIX=\"\$HOME/.local\""))
        assertTrue(commandEcho.contains("npm --version)\" = \"${TerminalEnvironmentContract.NODE_NPM_VERSION}"))
        assertTrue(commandEcho.contains("\"\$global_bin/pnpm\" --version)\" = \"${TerminalEnvironmentContract.PNPM_VERSION}"))
        assertTrue(commandEcho.contains("\"\$global_bin/tsc\" --version)\" = \"Version ${TerminalEnvironmentContract.TYPESCRIPT_VERSION}"))
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
