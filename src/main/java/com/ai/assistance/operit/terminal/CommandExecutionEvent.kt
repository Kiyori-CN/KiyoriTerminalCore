package com.ai.assistance.operit.terminal

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CommandExecutionEvent(
    val commandId: String,
    val sessionId: String,
    val outputChunk: String, // 命令执行过程量；完成事件固定为空
    val isCompleted: Boolean, // 是否执行完毕
    val exitCode: Int? = null // 完成时的真实 shell 退出码
) : Parcelable

/**
 * Creates the sole completion-event shape. Command output is delivered by incremental events;
 * keeping this body empty prevents bounded UI history from replacing a complete tool transcript.
 */
internal fun completedCommandExecutionEvent(
    commandId: String,
    sessionId: String,
    exitCode: Int?,
): CommandExecutionEvent = CommandExecutionEvent(
    commandId = commandId,
    sessionId = sessionId,
    outputChunk = "",
    isCompleted = true,
    exitCode = exitCode,
)

@Parcelize
data class SessionDirectoryEvent(
    val sessionId: String,
    val currentDirectory: String
) : Parcelable
