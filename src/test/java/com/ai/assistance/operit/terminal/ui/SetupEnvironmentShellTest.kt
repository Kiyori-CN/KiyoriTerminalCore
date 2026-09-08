package com.ai.assistance.operit.terminal.ui

import com.ai.assistance.operit.terminal.TerminalEnvironmentContract
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class SetupEnvironmentShellTest {
    @Test
    fun realBashChecksPathFailureTimeoutAndTargetIsolation() {
        val windows = requireNotNull(System.getProperty("os.name")).startsWith("Windows")
        val distro = System.getenv("KIYORI_PTY_WSL_DISTRO")
        assumeTrue("Set KIYORI_PTY_WSL_DISTRO for real Bash tests on Windows", !windows || distro != null)
        val encoder = Base64.getEncoder()
        val source = javaClass.getResource("/setup_environment_shell.py")!!.readText()
        val argv = (if (windows) listOf("wsl", "-d", distro!!, "--exec") else emptyList()) +
            listOf("python3", "-c", "import base64,sys;exec(base64.b64decode(sys.argv[1]))", encoder.encodeToString(source.toByteArray()))
        val packages = listOf(PackageItem("nodejs", "", "node"), PackageItem("pnpm", "", "typescript"), PackageItem("uv", "", "uv"))
        val wrongTarget = SetupEnvironmentTarget("0".repeat(64), "Linux", "x86_64", "user", "/home/user", "host", "root", true)
        val commands = listOf(
            TerminalEnvironmentContract.NODE_RUNTIME_CHECK_COMMAND,
            TerminalEnvironmentContract.NODE_TOOLCHAIN_CHECK_COMMAND,
            packageProbeCommand(packages),
            TerminalEnvironmentContract.buildNodeJsInstallCommand(),
            wrongTarget.bindCommands(listOf("touch \"${'$'}HOME/forbidden\"")).first(),
            TerminalEnvironmentContract.buildNodePackageSetupCommands(listOf("typescript"), "https://registry.npmjs.org/").single(),
        )
        // 失败诊断可能超过 Windows 管道容量；先写临时日志，不能 waitFor 后才排空管道。
        val outputFile = java.io.File.createTempFile("kiyori-env-shell-", ".log").apply { deleteOnExit() }
        val inputFile = java.io.File.createTempFile("kiyori-env-shell-input-", ".txt").apply { deleteOnExit() }
        inputFile.bufferedWriter().use { writer ->
            commands.forEach { writer.appendLine(encoder.encodeToString(it.toByteArray())) }
            writer.appendLine(if (System.getenv("KIYORI_ENV_INSTALL_SMOKE") == "1") "1" else "0")
        }
        val process = ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(outputFile).redirectInput(inputFile).start()
        val finished = process.waitFor(240, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("Environment shell regression timed out", finished)
        val output = outputFile.readText().also { outputFile.delete() }
        inputFile.delete()
        assertEquals(output, 0, process.exitValue())
        assertTrue(output, output.contains("Environment shell regression PASS"))
        println(output)
    }

    @Test
    fun targetRequiresOneValidFrameAndSupportedPrivilege() {
        val fields = listOf("a".repeat(64), "Linux", "x86_64", "dev", "/home/dev", "remote", "sudo", "1").joinToString("\n")
        val frame = "__KIYORI_ENV_TARGET__:" + Base64.getEncoder().encodeToString(fields.toByteArray())
        val result = HiddenExecResult(frame, 0)
        val target = setupEnvironmentTarget(result)!!
        assertTrue(target.canInstall)
        assertFalse(target.copy(privilege = "none").canInstall)
        assertFalse(target.copy(system = "Darwin").canInstall)
        assertFalse(target.copy(aptAvailable = false).canInstall)
        assertNull(setupEnvironmentTarget(result.copy(output = "$frame\n$frame")))
        assertNull(setupEnvironmentTarget(result.copy(outputTruncated = true)))
        assertNull(setupEnvironmentTarget(result.copy(exitCode = 1)))
    }

    @Test
    fun partialProbeDoesNotBlockUnrelatedSelectionOrConfuseBrokenAndMissing() {
        val packages = listOf(PackageItem("nodejs", "", ""), PackageItem("pnpm", "", ""), PackageItem("uv", "", ""))
        val result = HiddenExecResult("__KIYORI_ENV_PROBE_BEGIN__\n__KIYORI_ENV_PROBE__:nodejs:1\n__KIYORI_ENV_PROBE__:pnpm:4\n__KIYORI_ENV_PROBE__:uv:2\n__KIYORI_ENV_PROBE_END__", 0)
        val statuses = packageProbeStatuses(result, packages)
        assertEquals(InstallStatus.NEEDS_CONFIGURATION, statuses["pnpm"])
        assertEquals(InstallStatus.UNKNOWN, statuses["uv"])
        assertTrue(setupSelectionResolved(mapOf("pnpm" to true), statuses))
        assertFalse(setupSelectionResolved(mapOf("uv" to true), statuses))
        assertFalse(setupSelectionResolved(mapOf("pnpm" to true), statuses + ("nodejs" to InstallStatus.UNKNOWN)))
        val malformedDuplicate = result.copy(output = result.output.replace(
            "__KIYORI_ENV_PROBE__:nodejs:1", "__KIYORI_ENV_PROBE__:nodejs:1\n__KIYORI_ENV_PROBE__:nodejs:invalid"
        ))
        assertEquals(InstallStatus.UNKNOWN, packageProbeStatuses(malformedDuplicate, packages)["nodejs"])
    }
}
