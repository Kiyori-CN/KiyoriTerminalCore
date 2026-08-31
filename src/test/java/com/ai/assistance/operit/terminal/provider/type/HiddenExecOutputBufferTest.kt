package com.ai.assistance.operit.terminal.provider.type

import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenExecOutputBufferTest {
    @Test
    fun detectsReadyMarkerSplitAcrossReaderChunks() {
        val detector = HiddenExecReadyMarkerDetector("TERMINAL_READY")

        assertTrue(!detector.append("boot TERMINAL_"))
        assertTrue(detector.append("READY\n"))
    }

    @Test
    fun retainsProtocolMarkersWhenOutputIsTruncated() {
        val token = "token"
        val begin = "__OPERIT_HIDDEN_BEGIN__:$token"
        val pid = "__OPERIT_HIDDEN_PID__:$token:1234"
        val end = "__OPERIT_HIDDEN_END__:$token:0"
        val buffer = HiddenExecOutputBuffer(
            maxChars = 96,
            headChars = 12,
            protectedMarkerPrefixes = listOf(
                "__OPERIT_HIDDEN_BEGIN__:$token",
                "__OPERIT_HIDDEN_PID__:$token:",
                "__OPERIT_HIDDEN_END__:$token:",
            ),
        )

        buffer.append("$begin\n$pid\n")
        buffer.append("0123456789abcdef".repeat(20))
        buffer.append("\n$end\n")

        val output = buffer.toString()
        assertTrue(buffer.truncated)
        assertTrue(output.contains("$begin\n"))
        assertTrue(output.contains("$pid\n"))
        assertTrue(output.contains("$end\n"))
        assertTrue(output.indexOf(begin) < output.indexOf(pid))
        assertTrue(output.indexOf(pid) < output.indexOf(end))
    }

    @Test
    fun capturesMarkersSplitAcrossReaderChunks() {
        val prefix = "__OPERIT_HIDDEN_END__:token:"
        val buffer = HiddenExecOutputBuffer(
            maxChars = 48,
            headChars = 8,
            protectedMarkerPrefixes = listOf(prefix),
        )

        buffer.append("prefix __OPERIT_HIDDEN_END__:")
        buffer.append("token:7\n")
        buffer.append("x".repeat(100))

        assertTrue(buffer.toString().contains("$prefix"))
        assertTrue(buffer.toString().contains("$prefix" + "7\n"))
    }
}
