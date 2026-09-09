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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    initialSessionState: State<com.ai.assistance.operit.terminal.data.SessionInitState>,
    private val terminalManager: TerminalManager,
    val forceShowSetup: Boolean = false
) {
    val sessions by sessionsState
    val currentSessionId by currentSessionIdState
    val currentDirectory by currentDirectoryState
    val isFullscreen by isFullscreenState
    val terminalEmulator by terminalEmulatorState
    val initialSessionState by initialSessionState
    fun retryInitialSession() = terminalManager.retryInitialSession()

    var command by mutableStateOf("")
    var actionFailed by mutableStateOf(false)
        private set
    var isCreatingSession by mutableStateOf(false)
        private set
    private var commandSubmissionPending = false
    private var commandRevision = 0L
    private val orderedInput = OrderedTerminalInput(terminalManager.coroutineScope)
    fun dismissActionError() { actionFailed = false }
    private var setupExecutionJob: Job? = null
    var setupProgress by mutableStateOf<EnvironmentSetupProgress?>(null)
        private set

    fun onCommandChange(newCommand: String) {
        commandRevision++
        command = newCommand
    }

    fun onSendInput(inputText: String, isCommand: Boolean) {
        val targetId = currentSessionId ?: run { actionFailed = true; return }
        if (isCommand && commandSubmissionPending) return
        if (isCommand) commandSubmissionPending = true
        actionFailed = false
        val submittedRevision = commandRevision
        // 固定点击时的会话；挂起后切标签不能把命令发送到新的当前会话。
        orderedInput.submit {
            withContext(Dispatchers.Main.immediate) {
                try {
                    if (isCommand) terminalManager.sendUserCommand(targetId, inputText)
                    else terminalManager.sendInputToSession(targetId, inputText)
                    if (isCommand && submittedRevision == commandRevision && inputText == command) onCommandChange("")
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { actionFailed = true }
                finally { if (isCommand) commandSubmissionPending = false }
            }
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
            setupProgress = EnvironmentSetupProgress(0, commands.size)
            try {
                // The first call may still be extracting the Ubuntu rootfs on slow storage.
                // Keep the setup job attached to that session instead of returning silently
                // before it reaches READY.
                val sessionId = withTimeoutOrNull(180_000L) {
                    terminalManager.terminalState.first { state ->
                        !state.currentSessionId.isNullOrBlank()
                    }.currentSessionId
                }
                if (sessionId.isNullOrBlank()) {
                    setupProgress = EnvironmentSetupProgress(0, commands.size, failed = true)
                    Log.e("TerminalEnv", "Cannot start environment setup without a target terminal session")
                    return@launch
                }

                commands.forEachIndexed { index, setupCommand ->
                    setupProgress = EnvironmentSetupProgress(index + 1, commands.size)
                    val event = terminalManager.executeCommandAndWait(
                        sessionId = sessionId,
                        command = setupCommand,
                        // 安装输出已有可见终端 owner；这里只等待退出码，避免长 APT 日志再累积一份。
                        captureOutput = false,
                    )
                    if (event == null) {
                        setupProgress = EnvironmentSetupProgress(index + 1, commands.size, failed = true)
                        Log.e("TerminalEnv", "Environment setup step ${index + 1} did not complete")
                        return@launch
                    }
                    val exitCode = event.exitCode
                    if (exitCode != 0) {
                        setupProgress = EnvironmentSetupProgress(index + 1, commands.size, failed = true, exitCode = exitCode)
                        Log.e(
                            "TerminalEnv",
                            "Environment setup step ${index + 1} failed with exit code $exitCode"
                        )
                        return@launch
                    }
                }
                Log.i("TerminalEnv", "Environment setup completed in session $sessionId")
                setupProgress = EnvironmentSetupProgress(commands.size, commands.size, completed = true)
            } catch (error: kotlinx.coroutines.CancellationException) {
                setupProgress = setupProgress?.copy(failed = true)
                throw error
            } catch (error: Exception) {
                setupProgress = setupProgress?.copy(failed = true)
                Log.e("TerminalEnv", "Environment setup failed", error)
            } finally {
                setupExecutionJob = null
            }
        }
    }

    fun onInterrupt() = terminalManager.sendInterruptSignal()
    fun onNewSession() {
        if (isCreatingSession || initialSessionState == com.ai.assistance.operit.terminal.data.SessionInitState.INITIALIZING) return
        isCreatingSession = true
        actionFailed = false
        // 在terminalManager的协程作用域中异步创建会话
        terminalManager.coroutineScope.launch {
            try {
                terminalManager.createNewSession()
                Log.d("TerminalEnv", "New session created successfully")
            } catch (e: Exception) {
                actionFailed = true
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("TerminalEnv", "Failed to create new session", e)
            } finally { isCreatingSession = false }
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
    val initialSessionState = terminalManager.initialSessionState.collectAsState()

    return remember(terminalManager, forceShowSetup) {
        TerminalEnv(
            sessionsState = sessionsState,
            currentSessionIdState = currentSessionIdState,
            currentDirectoryState = currentDirectoryState,
            isFullscreenState = isFullscreenState,
            terminalEmulatorState = terminalEmulatorState,
            initialSessionState = initialSessionState,
            terminalManager = terminalManager,
            forceShowSetup = forceShowSetup
        )
    }
}

data class EnvironmentSetupProgress(
    val step: Int,
    val total: Int,
    val completed: Boolean = false,
    val failed: Boolean = false,
    val exitCode: Int? = null,
)
