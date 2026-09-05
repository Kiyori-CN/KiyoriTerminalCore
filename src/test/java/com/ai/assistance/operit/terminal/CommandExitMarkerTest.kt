package com.ai.assistance.operit.terminal

import com.ai.assistance.operit.terminal.view.domain.shouldFinishCommandOnPrompt
import com.ai.assistance.operit.terminal.data.SessionInitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandExitMarkerTest {
    @Test
    fun completionEventsNeverCarryAHistorySnapshot() {
        val event = completedCommandExecutionEvent(
            commandId = "command-id",
            sessionId = "session-id",
            exitCode = 0,
        )

        assertTrue(event.isCompleted)
        assertEquals("", event.outputChunk)
        assertEquals(0, event.exitCode)
    }

    @Test
    fun parsesSuccessfulAndFailedStatuses() {
        assertEquals(0, CommandExitMarker.parse("\u001B]1337;${CommandExitMarker.PREFIX}123e4567-e89b-12d3-a456-426614174000:0\u0007"))
        assertEquals(127, CommandExitMarker.parse("${CommandExitMarker.PREFIX}123e4567-e89b-12d3-a456-426614174000:127"))
        assertEquals(-9, CommandExitMarker.parse("__OPERIT_COMMAND_EXIT__:123e4567-e89b-12d3-a456-426614174000:-9"))
    }

    @Test
    fun rejectsMalformedOrUnrelatedOutput() {
        assertNull(CommandExitMarker.parse("${CommandExitMarker.PREFIX}not-a-command:0"))
        assertNull(CommandExitMarker.parse("ordinary output"))
        assertNull(CommandExitMarker.parse("${CommandExitMarker.PREFIX}123e4567-e89b-12d3-a456-426614174000:not-a-number"))
    }

    @Test
    fun commandProtocolUsesInvisibleOscAndNeverEmitsLegacyBrandText() {
        val input = buildCommandWithExitMarkerProtocol(
            command = "dpkg --configure -a",
            commandId = "123e4567-e89b-12d3-a456-426614174000",
        )

        assertTrue(input.contains(CommandExitMarker.PREFIX))
        assertTrue(input.contains("123e4567-e89b-12d3-a456-426614174000"))
        assertTrue(input.contains("\\033]1337;%s%s:%s\\007"))
        assertFalse(input.substringBefore("printf").contains('\n'))
        assertTrue(input.contains("eval $'dpkg --configure -a'; printf"))
        assertFalse(input.contains("__OPERIT_COMMAND_EXIT__"))
    }

    @Test
    fun commandProtocolEncodesMultilineBodyOnOnePhysicalLine() {
        val input = buildCommandWithExitMarkerProtocol(
            command = "cd /tmp\nexport TEST=value\n",
            commandId = "command-id",
        )

        assertTrue(input.contains("eval $'cd /tmp\\nexport TEST=value\\n'; printf"))
        assertEquals(1, input.count { it == '\n' })
        assertTrue(input.endsWith("\"\$?\"\n"))
    }

    @Test
    fun emptyCommandStillProducesACompletableNoOpEnvelope() {
        val input = buildCommandWithExitMarkerProtocol("\r\n", "command-id")

        assertTrue(input.contains("eval $':'; printf"))
    }

    @Test
    fun payloadNeverExposesEditorKeysOrHistoryExpansion() {
        val input = quoteBashCommandPayload("\t\r\n\u0003\u001b\u007f中文😀'\\!\n")
        assertTrue(input.startsWith("$'\\t\\r\\n\\003\\033\\177"))
        assertTrue(input.endsWith("\\'\\\\\\041\\n'"))
        assertTrue(input.all { it.code in 0x20..0x7e })
        assertFalse(input.contains('!'))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nulIsRejectedInsteadOfSilentlyTruncatingTheCommand() {
        buildCommandWithExitMarkerProtocol("echo before\u0000echo after", "command-id")
    }

    @Test
    fun parsesOscMarkerWhenReadAcrossChunks() {
        val marker = "\u001B]1337;${CommandExitMarker.PREFIX}123e4567-e89b-12d3-a456-426614174000:0\u0007"

        assertNull(CommandExitMarker.parse(marker.substring(0, marker.length - 1)))
        assertEquals(0, CommandExitMarker.parse(marker))
    }

    @Test
    fun displayFilterRemovesOscMarkerAcrossReadChunks() {
        val marker = "\u001B]1337;${CommandExitMarker.PREFIX}123e4567-e89b-12d3-a456-426614174000:0\u0007"
        val filter = CommandExitMarker.DisplayFilter()

        assertEquals("before", filter.filter("before${marker.substring(0, 18)}"))
        assertEquals("after", filter.filter("${marker.substring(18)}after"))
    }

    @Test
    fun displayFilterPreservesUnrelatedOscAndText() {
        val filter = CommandExitMarker.DisplayFilter()

        assertEquals("\u001B]0;title\u0007visible", filter.filter("\u001B]0;title\u0007visible"))
    }

    @Test
    fun removesOnlyTheExpectedStatusMarkerAndPreservesAdjacentOutput() {
        val commandId = "123e4567-e89b-12d3-a456-426614174000"
        val marker = "\u001B]1337;${CommandExitMarker.PREFIX}${commandId}:0\u0007"

        assertEquals(
            "prefix-without-newline\"suffix",
            CommandExitMarker.remove(
                "prefix-without-newline\"$marker" + "suffix",
                expectedCommandId = commandId,
            ),
        )
    }

    @Test
    fun doesNotRemoveAStatusMarkerBelongingToAnotherCommand() {
        val marker = "${CommandExitMarker.PREFIX}123e4567-e89b-12d3-a456-426614174000:0"

        assertEquals(
            "before$marker-after",
            CommandExitMarker.remove(
                "before$marker-after",
                expectedCommandId = "different-command",
            ),
        )
    }

    @Test
    fun intermediatePromptCannotCompleteWrappedCommandBeforeExitMarker() {
        assertFalse(shouldFinishCommandOnPrompt(commandExecuting = true, exitCode = null))
        assertTrue(shouldFinishCommandOnPrompt(commandExecuting = true, exitCode = 0))
        assertTrue(shouldFinishCommandOnPrompt(commandExecuting = true, exitCode = -1))
        assertTrue(shouldFinishCommandOnPrompt(commandExecuting = false, exitCode = null))
        assertTrue(
            shouldFinishCommandOnPrompt(
                commandExecuting = true,
                exitCode = null,
                cancellationRequested = true,
            )
        )
    }

    @Test
    fun cancelledCommandIsSettledWhenQueueAdvancesToAnotherCommand() {
        assertTrue(
            isTargetCommandSettled(
                targetCommandId = "timed-out",
                currentCommandId = "next-command",
                currentCommandExecuting = true,
            )
        )
    }

    @Test
    fun executingTargetCommandIsNotSettled() {
        assertFalse(
            isTargetCommandSettled(
                targetCommandId = "timed-out",
                currentCommandId = "timed-out",
                currentCommandExecuting = true,
            )
        )
    }

    @Test
    fun sessionIsReadyOnlyWhenStateWriterAndProcessBelongToALiveRuntime() {
        assertTrue(
            isTerminalRuntimeReady(
                initState = SessionInitState.READY,
                writerAvailable = true,
                processAlive = true,
            )
        )
        assertFalse(
            isTerminalRuntimeReady(
                initState = SessionInitState.INITIALIZING,
                writerAvailable = true,
                processAlive = true,
            )
        )
        assertFalse(
            isTerminalRuntimeReady(
                initState = SessionInitState.READY,
                writerAvailable = false,
                processAlive = true,
            )
        )
        assertFalse(
            isTerminalRuntimeReady(
                initState = SessionInitState.READY,
                writerAvailable = true,
                processAlive = false,
            )
        )
    }
}
