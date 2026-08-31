package com.ai.assistance.operit.terminal

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class CommandExecutionEvent(
    val commandId: String,
    val sessionId: String,
    val outputChunk: String, // 命令执行过程量
    val isCompleted: Boolean, // 是否执行完毕
    val exitCode: Int? = null // 完成时的真实 shell 退出码
) : Parcelable

@Parcelize
data class SessionDirectoryEvent(
    val sessionId: String,
    val currentDirectory: String
) : Parcelable
