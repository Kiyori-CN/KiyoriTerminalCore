package com.ai.assistance.operit.terminal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Serializes command events before they enter the shared flow.
 *
 * OutputProcessor can receive a PTY chunk on one coroutine while command setup or completion is
 * being handled on another. Publishing each callback with an independent launch makes a completion
 * event observable before already-produced output, causing consumers that stop at completion to
 * receive a truncated command result. A single FIFO queue preserves callback order without
 * changing the command protocol or result model.
 */
internal class OrderedCommandExecutionEventDispatcher(
    scope: CoroutineScope,
    private val publish: suspend (CommandExecutionEvent) -> Unit,
) {
    private val events = Channel<CommandExecutionEvent>(Channel.UNLIMITED)
    private val dispatchJob: Job = scope.launch {
        for (event in events) {
            publish(event)
        }
    }

    fun offer(event: CommandExecutionEvent): Boolean = events.trySend(event).isSuccess

    fun close() {
        events.close()
    }

    suspend fun closeAndJoin() {
        events.close()
        dispatchJob.join()
    }
}
