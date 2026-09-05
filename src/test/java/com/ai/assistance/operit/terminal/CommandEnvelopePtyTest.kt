package com.ai.assistance.operit.terminal

import java.util.Base64
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in Windows WSL test; Linux hosts use their native Python PTY implementation. */
class CommandEnvelopePtyTest {
    @Test
    fun realReadlinePreservesPayloadAndSessionState() {
        val windows = requireNotNull(System.getProperty("os.name")).startsWith("Windows")
        val distro = System.getenv("KIYORI_PTY_WSL_DISTRO")
        assumeTrue("Set KIYORI_PTY_WSL_DISTRO for real PTY validation on Windows", !windows || distro != null)
        val source = "package main\nimport \"fmt\"\nfunc main() {\n\tfmt.Println(\"hi\")\n}\n"
        val nodeSource = "if (true) {\n\tconsole.log(\"中文 ' \\\" \\\\ \$ ` !\");\n}\n"
        val special = "中文😀\t'\"\\\$`!\r\n" + (1..31).map { it.toChar() }.joinToString("") + '\u007f'
        val commands = listOf(
            "cat > main.go <<'EOF'\n${source}EOF\ncat > node.js <<'EOF'\n${nodeSource}EOF\n",
            "cat > tab.py <<'EOF'\nif True:\n\tprint('中文 hi')\nEOF\npython3 tab.py\n" +
                "cat > space.py <<'EOF'\nif True:\n    print('space hi')\nEOF\npython3 space.py",
            "cat > Makefile <<'EOF'\nall:\n\t@echo hi\nEOF\n",
            "printf '%s' '${special.replace("'", "'\\''")}' > special.bin",
            "mkdir kept; cd kept; export KIYORI_PROBE='中文 value'",
            "printf '%s' \"\$KIYORI_PROBE\" > export.txt; pwd -P",
            "(sleep 0.2; printf done > background.txt) &",
            "wait; cat background.txt",
            "mkdir gone; cd gone; rmdir ../gone",
            "printf recovered > recovered.txt; pwd -P",
            "false",
            "printf '%s' '${"x\t中文'\\!".repeat(1800).replace("'", "'\\''")}' > large.txt",
            "sleep 30",
            "printf alive > after-interrupt.txt",
            "HOME=\"\$PWD/missing-home\"; mkdir gone-again; cd gone-again; rmdir ../gone-again",
            "printf should-not-run > \"\$KIYORI_PROBE_ROOT/unexpected.txt\"",
        )
        val script = javaClass.getResource("/command_envelope_pty.py")!!.readText()
        val loader = "import base64,sys;exec(base64.b64decode(sys.argv[1]))"
        val encodedScript = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_8))
        val argv = if (windows) listOf("wsl", "-d", distro!!, "--exec", "python3", "-c", loader, encodedScript)
            else listOf("python3", "-c", loader, encodedScript)
        val process = ProcessBuilder(argv).redirectErrorStream(true).start()
        val encoder = Base64.getEncoder()
        process.outputStream.bufferedWriter().use { writer ->
            (listOf(source, special) + commands.mapIndexed { index, command ->
                buildCommandWithExitMarkerProtocol(command, "00000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
            }).forEach { writer.appendLine(encoder.encodeToString(it.toByteArray(Charsets.UTF_8))) }
        }
        val finished = process.waitFor(90, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        assertTrue("PTY regression exceeded 90 seconds", finished)
        val result = process.inputStream.bufferedReader().readText()
        assertEquals(result, 0, process.exitValue())
        assertTrue(result, result.contains("PTY regression PASS"))
        println(result)
    }
}
