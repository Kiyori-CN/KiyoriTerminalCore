package com.ai.assistance.operit.terminal.ui

import com.ai.assistance.operit.terminal.TerminalEnvironmentContract
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupEnvironmentProbeTest {
    @Test
    fun packageChecksUseDeterministicCommands() {
        assertTrue(packageCheckCommand(PackageItem("python3-pip", "", "python3-pip")).contains("python3 -m pip"))
        assertTrue(packageCheckCommand(PackageItem("uv", "", "pipx install uv")).contains("${'$'}HOME/.local/bin"))
        assertTrue(packageCheckCommand(PackageItem("nodejs", "", "node")).contains("node -v"))
        assertTrue(packageCheckCommand(PackageItem("pnpm", "", "typescript")) == TerminalEnvironmentContract.NODE_TOOLCHAIN_CHECK_COMMAND)
    }

    @Test
    fun pythonChecksUseRuntimeCapabilitiesAndExactDpkgStatus() {
        assertTrue(packageCheckCommand(PackageItem("python-is-python3", "", "python-is-python3")).contains("readlink -f"))
        assertTrue(packageCheckCommand(PackageItem("python3-venv", "", "python3-venv")).contains("python3 -m venv"))
        assertTrue(packageCheckCommand(PackageItem("python3-pip", "", "python3-pip")).contains("python3 -m pip"))
        assertTrue(checkPackageInstalled(HiddenExecResult("", 0), PackageItem("python3-pip", "", "python3-pip")))
        assertTrue(checkPackageInstalled(HiddenExecResult("install ok installed", 0), PackageItem("python3-pip", "", "python3-pip")))
        assertFalse(checkPackageInstalled(HiddenExecResult("", 0), PackageItem("uv", "", "pipx install uv")))
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

    @Test
    fun structuredProbeDistinguishesInstalledMissingAndUnknown() {
        val packages = listOf(
            PackageItem("python3-pip", "", "python3-pip"),
            PackageItem("python3-venv", "", "python3-venv"),
            PackageItem("uv", "", "pipx install uv"),
        )
        val result = HiddenExecResult(
            output = """
                __KIYORI_ENV_PROBE_BEGIN__
                __KIYORI_ENV_PROBE__:python3-pip1
                __KIYORI_ENV_PROBE__:python3-venv0
                __KIYORI_ENV_PROBE_END__
            """.trimIndent(),
            exitCode = 0,
        )
        val statuses = packageProbeStatuses(result, packages)

        assertEquals(InstallStatus.INSTALLED, statuses["python3-pip"])
        assertEquals(InstallStatus.NOT_INSTALLED, statuses["python3-venv"])
        assertEquals(InstallStatus.UNKNOWN, statuses["uv"])
    }

    @Test
    fun structuredProbeUsesOneFramedCommandForAllPackages() {
        val packages = listOf(
            PackageItem("python-is-python3", "", "python-is-python3"),
            PackageItem("python3-pip", "", "python3-pip"),
            PackageItem("uv", "", "pipx install uv"),
        )
        val command = packageProbeCommand(packages)

        assertTrue(command.startsWith("printf '%s\\n' '__KIYORI_ENV_PROBE_BEGIN__'"))
        assertTrue(command.contains("if (python3 -m pip --version"))
        assertTrue(command.contains("if (PATH=\"${'$'}HOME/.local/bin:${'$'}PATH\""))
        assertTrue(command.endsWith("printf '%s\\n' '__KIYORI_ENV_PROBE_END__'\\n"))
    }
}
