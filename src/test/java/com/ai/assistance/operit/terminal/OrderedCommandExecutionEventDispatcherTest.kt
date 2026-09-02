package com.ai.assistance.operit.terminal

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderedCommandExecutionEventDispatcherTest {
    @Test
    fun completionIsPublishedAfterAllLongCommandOutput() = runBlocking {
        val published = mutableListOf<CommandExecutionEvent>()
        val dispatcher = OrderedCommandExecutionEventDispatcher(this) { event ->
            published += event
        }
        val commandId = "command-id"
        val sessionId = "session-id"

        assertTrue(
            dispatcher.offer(
                CommandExecutionEvent(
                    commandId = commandId,
                    sessionId = sessionId,
                    outputChunk = "",
                    isCompleted = false,
                )
            )
        )
        repeat(25) { line ->
            assertTrue(
                dispatcher.offer(
                    CommandExecutionEvent(
                        commandId = commandId,
                        sessionId = sessionId,
                        outputChunk = "line-$line",
                        isCompleted = false,
                    )
                )
            )
        }
        assertTrue(
            dispatcher.offer(
                CommandExecutionEvent(
                    commandId = commandId,
                    sessionId = sessionId,
                    outputChunk = "",
                    isCompleted = true,
                    exitCode = 0,
                )
            )
        )

        dispatcher.closeAndJoin()

        assertEquals(27, published.size)
        assertEquals(false, published.dropLast(1).any { it.isCompleted })
        assertTrue(published.last().isCompleted)
        assertEquals(0, published.last().exitCode)
    }
}
