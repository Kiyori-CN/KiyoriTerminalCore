package com.ai.assistance.operit.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PtyProcessTest {
    @Test
    fun exitValueThrowsWhileChildIsRunning() {
        val process =
            PtyProcess(
                pid = 41,
                waitForStatus = { error("waitFor must not run") },
                pollExitStatus = { PTY_PROCESS_STILL_RUNNING },
                sendSignal = { _, _ -> error("signal must not run") },
            )

        assertThrows(IllegalThreadStateException::class.java) {
            process.exitValue()
        }
    }

    @Test
    fun exitValueReturnsAndCachesPolledExitStatus() {
        var pollCount = 0
        val process =
            PtyProcess(
                pid = 42,
                waitForStatus = { error("waitFor must not run") },
                pollExitStatus = {
                    pollCount += 1
                    17
                },
                sendSignal = { _, _ -> error("signal must not run") },
            )

        assertEquals(17, process.exitValue())
        assertEquals(17, process.exitValue())
        assertEquals(1, pollCount)
    }

    @Test
    fun waitForReturnsAndCachesBlockingExitStatus() {
        var waitCount = 0
        val process =
            PtyProcess(
                pid = 43,
                waitForStatus = {
                    waitCount += 1
                    137
                },
                pollExitStatus = { error("poll must not run after waitFor") },
                sendSignal = { _, _ -> error("signal must not run") },
            )

        assertEquals(137, process.waitFor())
        assertEquals(137, process.exitValue())
        assertEquals(1, waitCount)
    }

    @Test
    fun nativeWaitFailureIsNotReportedAsSuccessfulExit() {
        val process =
            PtyProcess(
                pid = 44,
                waitForStatus = { PTY_PROCESS_WAIT_FAILED },
                pollExitStatus = { PTY_PROCESS_WAIT_FAILED },
                sendSignal = { _, _ -> error("signal must not run") },
            )

        assertThrows(IllegalStateException::class.java) {
            process.exitValue()
        }
        assertThrows(IllegalStateException::class.java) {
            process.waitFor()
        }
    }
}
