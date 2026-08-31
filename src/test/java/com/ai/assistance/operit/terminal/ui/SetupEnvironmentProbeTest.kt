package com.ai.assistance.operit.terminal.ui

import com.ai.assistance.operit.terminal.TerminalEnvironmentContract
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupEnvironmentProbeTest {
    @Test
    fun packageChecksUseDeterministicCommands() {
        assertTrue(packageCheckCommand(PackageItem("python3-pip", "", "python3-pip")).contains("dpkg-query"))
        assertTrue(packageCheckCommand(PackageItem("uv", "", "pipx install uv")).contains("${'$'}HOME/.local/bin/uv"))
        assertTrue(packageCheckCommand(PackageItem("nodejs", "", "node")).contains("node -v"))
        assertTrue(packageCheckCommand(PackageItem("pnpm", "", "typescript")) == TerminalEnvironmentContract.NODE_TOOLCHAIN_CHECK_COMMAND)
    }

    @Test
    fun failedExitCodeNeverReportsInstalled() {
        val packageItem = PackageItem("ssh", "", "ssh")
        val result = HiddenExecResult(output = "/usr/bin/ssh", exitCode = 1)

        assertFalse(checkPackageInstalled(result, packageItem))
    }

    @Test
    fun successfulBinaryProbeReportsInstalled() {
        val packageItem = PackageItem("ssh", "", "ssh")
        val result = HiddenExecResult(output = "/usr/bin/ssh", exitCode = 0)

        assertTrue(checkPackageInstalled(result, packageItem))
    }

    @Test
    fun nodeVersionRequiresSupportedMajor() {
        val packageItem = PackageItem("nodejs", "", "node")

        assertTrue(checkPackageInstalled(HiddenExecResult("v24.20.0", 0), packageItem))
        assertFalse(checkPackageInstalled(HiddenExecResult("v20.19.0", 0), packageItem))
    }

    @Test
    fun nodeToolchainRequiresReadinessMarkerAndZeroExit() {
        val packageItem = PackageItem("pnpm", "", "typescript")
        val ready = HiddenExecResult(
            output = "${TerminalEnvironmentContract.NODE_TOOLCHAIN_READY_MARKER}\n",
            exitCode = 0,
        )
        val failed = ready.copy(exitCode = 1)

        assertTrue(checkPackageInstalled(ready, packageItem))
        assertFalse(checkPackageInstalled(failed, packageItem))
    }
}
