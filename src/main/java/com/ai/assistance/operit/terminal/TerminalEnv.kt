package com.ai.assistance.operit.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import android.util.Log
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator

@Stable
class TerminalEnv(
    sessionsState: State<List<TerminalSessionData>>,
    currentSessionIdState: State<String?>,
    currentDirectoryState: State<String>,
    isFullscreenState: State<Boolean>,
    terminalEmulatorState: State<AnsiTerminalEmulator>,
    private val terminalManager: TerminalManager,
    val forceShowSetup: Boolean = false
) {
    val sessions by sessionsState
    val currentSessionId by currentSessionIdState
    val currentDirectory by currentDirectoryState
    val isFullscreen by isFullscreenState
    val terminalEmulator by terminalEmulatorState

    var command by mutableStateOf("")
    private var setupExecutionJob: Job? = null

    fun onCommandChange(newCommand: String) {
        command = newCommand
    }

    fun onSendInput(inputText: String, isCommand: Boolean) {
        // 允许空输入（用于交互式场景发送回车）
        if (isCommand) {
            // 命令模式：也允许空命令（用于 SSH 等交互场景）
            terminalManager.coroutineScope.launch {
                terminalManager.sendCommand(inputText)
            }
            if (inputText == command) {
                command = ""
            }
        } else {
            // 输入模式：允许空输入（例如 ssh-keygen 直接回车使用默认路径）
            terminalManager.sendInput(inputText)
        }
    }

    fun onSetup(commands: List<String>) {
        if (commands.isEmpty()) {
            Log.w("TerminalEnv", "Ignoring empty environment setup request")
            return
        }
        if (setupExecutionJob?.isActive == true) {
            Log.w("TerminalEnv", "Ignoring duplicate environment setup request")
            return
        }

        setupExecutionJob = terminalManager.coroutineScope.launch {
            try {
                val sessionId = withTimeoutOrNull(30_000L) {
                    terminalManager.terminalState.first { state ->
                        !state.currentSessionId.isNullOrBlank()
                    }.currentSessionId
                }
                if (sessionId.isNullOrBlank()) {
                    Log.e("TerminalEnv", "Cannot start environment setup without a target terminal session")
                    return@launch
                }

                commands.forEachIndexed { index, setupCommand ->
                    val event = terminalManager.executeCommandAndWait(
                        sessionId = sessionId,
                        command = setupCommand,
                    )
                    if (event == null) {
                        Log.e("TerminalEnv", "Environment setup step ${index + 1} did not complete")
                        return@launch
                    }
                    val exitCode = event.exitCode
                    if (exitCode != 0) {
                        Log.e(
                            "TerminalEnv",
                            "Environment setup step ${index + 1} failed with exit code $exitCode"
                        )
                        return@launch
                    }
                }
                Log.i("TerminalEnv", "Environment setup completed in session $sessionId")
            } finally {
                setupExecutionJob = null
            }
        }
    }

    fun onInterrupt() = terminalManager.sendInterruptSignal()
    fun onNewSession() {
        // 在terminalManager的协程作用域中异步创建会话
        terminalManager.coroutineScope.launch {
            try {
                terminalManager.createNewSession()
                Log.d("TerminalEnv", "New session created successfully")
            } catch (e: Exception) {
                Log.e("TerminalEnv", "Failed to create new session", e)
            }
        }
    }
    fun onSwitchSession(sessionId: String) = terminalManager.switchToSession(sessionId)
    fun onCloseSession(sessionId: String) = terminalManager.closeSession(sessionId)
    
    fun saveScrollOffset(sessionId: String, scrollOffset: Float) = terminalManager.saveScrollOffset(sessionId, scrollOffset)
    fun getScrollOffset(sessionId: String): Float = terminalManager.getScrollOffset(sessionId)
}

@Composable
fun rememberTerminalEnv(terminalManager: TerminalManager, forceShowSetup: Boolean = false): TerminalEnv {
    val sessionsState = terminalManager.sessions.collectAsState(initial = emptyList())
    val currentSessionIdState = terminalManager.currentSessionId.collectAsState(initial = null)
    val currentDirectoryState = terminalManager.currentDirectory.collectAsState(initial = "$ ")
    val isFullscreenState = terminalManager.isFullscreen.collectAsState(initial = false)
    val placeholderEmulator = remember { AnsiTerminalEmulator(screenWidth = 1, screenHeight = 1, historySize = 0) }
    val terminalEmulatorState = terminalManager.terminalEmulator.collectAsState(initial = placeholderEmulator)

    return remember(terminalManager, forceShowSetup) {
        TerminalEnv(
            sessionsState = sessionsState,
            currentSessionIdState = currentSessionIdState,
            currentDirectoryState = currentDirectoryState,
            isFullscreenState = isFullscreenState,
            terminalEmulatorState = terminalEmulatorState,
            terminalManager = terminalManager,
            forceShowSetup = forceShowSetup
        )
    }
}
