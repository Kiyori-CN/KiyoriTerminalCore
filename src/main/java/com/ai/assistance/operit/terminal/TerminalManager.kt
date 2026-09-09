package com.ai.assistance.operit.terminal

import android.app.Application
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.io.IOException
import java.io.FileInputStream
import java.security.MessageDigest
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineStart
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import com.ai.assistance.operit.terminal.data.TerminalState
import com.ai.assistance.operit.terminal.data.CommandHistoryItem
import com.ai.assistance.operit.terminal.data.QueuedCommand
import com.ai.assistance.operit.terminal.view.domain.OutputProcessor
import java.util.UUID
import java.util.TimeZone
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import com.ai.assistance.operit.terminal.data.PackageManagerType
import com.ai.assistance.operit.terminal.data.SessionInitState
import com.ai.assistance.operit.terminal.utils.SourceManager
import com.ai.assistance.operit.terminal.utils.UbuntuRootfsManifest
import com.ai.assistance.operit.terminal.utils.SSHConfigManager
import com.ai.assistance.operit.terminal.utils.SSHDServerManager
import com.ai.assistance.operit.terminal.provider.filesystem.FileSystemProvider
import com.ai.assistance.operit.terminal.provider.filesystem.LocalFileSystemProvider
import com.ai.assistance.operit.terminal.provider.filesystem.PRootBindMount
import com.ai.assistance.operit.terminal.provider.filesystem.PRootMountMapping
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.ai.assistance.operit.terminal.provider.type.TerminalProvider
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import com.ai.assistance.operit.terminal.provider.type.LocalTerminalProvider
import com.ai.assistance.operit.terminal.provider.type.SSHTerminalProvider
import com.ai.assistance.operit.terminal.data.TerminalSessionData
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator
import com.ai.assistance.operit.terminal.completedCommandExecutionEvent

data class CommandCancellationResult(
    val commandFound: Boolean,
    val settled: Boolean,
    val sessionHealthy: Boolean,
    val sessionRecovered: Boolean,
    val contextPreserved: Boolean,
)

private data class CommandSubmissionFailure(
    val terminalSession: TerminalSession?,
    val shellGeneration: Long,
)

class TerminalManager private constructor(
    private val application: Application
) {
    internal val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val envInitMutex = Mutex()
    private val environmentOperations = TerminalEnvironmentOperations()
    @Volatile
    private var isEnvInitialized = false

    private val filesDir: File = application.filesDir
    private val usrDir: File = File(filesDir, "usr")
    private val binDir: File = File(usrDir, "bin")
    private val nativeLibDir: String = application.applicationInfo.nativeLibraryDir
    private val activeSessions = ConcurrentHashMap<String, TerminalSession>()
    private val closingSessions = ConcurrentHashMap.newKeySet<String>()
    private val sessionRecoveryMutexes = ConcurrentHashMap<String, Mutex>()
    
    // SharedPreferences for reading settings
    private val prefs = application.getSharedPreferences("terminal_settings", Context.MODE_PRIVATE)

    // 状态和事件流
    private val _commandExecutionEvents = MutableSharedFlow<CommandExecutionEvent>()
    val commandExecutionEvents: SharedFlow<CommandExecutionEvent> = _commandExecutionEvents.asSharedFlow()

    private val _directoryChangeEvents = MutableSharedFlow<SessionDirectoryEvent>()
    val directoryChangeEvents: SharedFlow<SessionDirectoryEvent> = _directoryChangeEvents.asSharedFlow()

    // 核心组件
    private val commandEventDispatcher = OrderedCommandExecutionEventDispatcher(coroutineScope) { event ->
        _commandExecutionEvents.emit(event)
    }
    private val sessionManager = SessionManager(this)
    private val outputProcessor = OutputProcessor(
        onCommandExecutionEvent = { event ->
            if (!commandEventDispatcher.offer(event)) {
                Log.w(TAG, "Dropping command event after terminal manager shutdown: ${event.commandId}")
            }
        },
        onDirectoryChangeEvent = { event ->
            coroutineScope.launch {
                _directoryChangeEvents.emit(event)
            }
        },
        onCommandCompleted = { sessionId ->
            coroutineScope.launch {
                processNextQueuedCommand(sessionId)
            }
        }
    )
    private val sourceManager = SourceManager(application)
    private val sshConfigManager = SSHConfigManager(application)
    private val sshdServerManager = SSHDServerManager.getInstance(application)
    
    // 单例的 TerminalProvider
    private var terminalProvider: TerminalProvider? = null
    private val _activeEnvironmentType = MutableStateFlow<TerminalType?>(null)
    val activeEnvironmentType = _activeEnvironmentType.asStateFlow()
    private val _initialSessionState = MutableStateFlow(SessionInitState.INITIALIZING)
    val initialSessionState = _initialSessionState.asStateFlow()
    private var initialSessionJob: Job? = null
    private val providerMutex = Mutex()

    // 暴露会话管理器的状态
    val terminalState: StateFlow<TerminalState> = sessionManager.state

    // 为了向后兼容，提供单独的状态流
    val sessions = terminalState.map { it.sessions }
    val currentSessionId = terminalState.map { it.currentSessionId }
    val currentDirectory = terminalState.map { it.currentSession?.currentDirectory ?: "$ " }
    val isInteractiveMode = terminalState.map { it.currentSession?.isInteractiveMode ?: false }
    val interactivePrompt = terminalState.map { it.currentSession?.interactivePrompt ?: "" }
    val isFullscreen = terminalState.map { it.currentSession?.isFullscreen ?: false }
    val terminalEmulator = terminalState.map { it.currentSession?.ansiParser ?: AnsiTerminalEmulator() }

    companion object {
        @Volatile
        private var INSTANCE: TerminalManager? = null

        fun getInstance(context: Context): TerminalManager {
            val application = context.applicationContext as Application
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TerminalManager(application).also { INSTANCE = it }
            }
        }

        private const val TAG = "TerminalManager"
        private const val UBUNTU_FILENAME = "ubuntu-resolute-arm64-kiyori-v1.tar.xz"
        private const val UBUNTU_MANIFEST_FILENAME = "ubuntu-rootfs-manifest.json"
        private const val MAX_HISTORY_ITEMS = 500
        private const val MAX_OUTPUT_LINES_PER_ITEM = 1000
        private const val TERMINAL_ENTER = "\r"
        private const val SESSION_READY_TIMEOUT_MS = 180_000L
        private const val SESSION_CLOSE_SETTLE_TIMEOUT_MS = 3_000L
    }

    init {
        retryInitialSession()
    }

    /** 默认会话准备先于会话表出现；UI 必须能观察这段耗时及其失败。 */
    fun retryInitialSession() {
        if (initialSessionJob?.isActive == true) return
        if (terminalState.value.sessions.any { it.initState == SessionInitState.READY }) {
            _initialSessionState.value = SessionInitState.READY
            return
        }
        _initialSessionState.value = SessionInitState.INITIALIZING
        initialSessionJob = coroutineScope.launch {
            try {
                Log.d(TAG, "Creating default session...")
                createNewSession()
                _initialSessionState.value = SessionInitState.READY
                Log.d(TAG, "Default session created successfully")
            } catch (cancelled: CancellationException) {
                _initialSessionState.value = SessionInitState.FAILED
                throw cancelled
            } catch (e: Exception) {
                _initialSessionState.value = SessionInitState.FAILED
                Log.e(TAG, "Failed to create default session", e)
            }
        }
    }

    /**
     * 创建新会话 - 同步等待初始化完成
     * 自动检测终端类型：如果配置了SSH则使用SSH，否则使用本地终端
     * 
     * @param title 会话标题
     */
    suspend fun createNewSession(
        title: String? = null
    ): TerminalSessionData = environmentOperations.run {
        check(initializeEnvironment()) { "Terminal environment initialization failed" }
        // 保存的偏好可以尚未应用，会话身份必须来自真正接收命令的唯一 provider。
        val terminalType = if (getTerminalProvider() is SSHTerminalProvider) {
            TerminalType.SSH
        } else {
            TerminalType.LOCAL
        }
        
        val newSession = sessionManager.createNewSession(title, terminalType)

        // 启动属于本次创建操作；维护取消时不能遗留独立协程重新启动旧会话。
        startSession(newSession.id)

        // 等待会话初始化完成
        // Ubuntu rootfs is extracted on-device before the PTY can reach READY.  Thirty seconds
        // is shorter than a normal first install on slower Android storage and caused the only
        // default session to be closed permanently, leaving setup with no target session.
        val initState = withTimeoutOrNull(SESSION_READY_TIMEOUT_MS) { // 3分钟超时
            terminalState.first { state ->
                val session = state.sessions.find { it.id == newSession.id }
                session == null || session.initState == SessionInitState.READY || session.initState == SessionInitState.FAILED
            }.sessions.find { it.id == newSession.id }?.initState
        }

        if (initState != SessionInitState.READY) {
            Log.e(TAG, "Session initialization failed for session: ${newSession.id}, state=$initState")
            // 初始化失败，移除会话
            sessionManager.closeSession(newSession.id)
            throw Exception(
                if (initState == null) "Session initialization timeout" else "Session initialization failed"
            )
        }

        Log.d(TAG, "Session ${newSession.id} initialized successfully")
        sessionManager.getSession(newSession.id) ?: error("Terminal session was closed")
    }

    /**
     * 切换到会话
     */
    fun switchToSession(sessionId: String) {
        sessionManager.switchToSession(sessionId)
    }

    /**
     * 关闭会话
     */
    fun closeSession(sessionId: String) {
        sessionManager.closeSession(sessionId)
    }

    /**
     * 会话关闭后的运行态清理（非持久化状态）。
     */
    fun onSessionClosed(sessionId: String) {
        outputProcessor.clearSessionState(sessionId)
        sessionRecoveryMutexes.remove(sessionId)
    }
    
    /**
     * 保存会话的滚动位置
     */
    fun saveScrollOffset(sessionId: String, scrollOffset: Float) {
        sessionManager.saveScrollOffset(sessionId, scrollOffset)
    }
    
    /**
     * 获取会话的滚动位置
     */
    fun getScrollOffset(sessionId: String): Float {
        return sessionManager.getScrollOffset(sessionId)
    }

    private fun createBusyboxSymlinks() {
        val links = listOf(
            "awk", "ash", "basename", "bzip2", "curl", "cp", "chmod", "cut", "cat", "du", "dd",
            "find", "grep", "gzip", "hexdump", "head", "id", "lscpu", "mkdir", "realpath", "rm",
            "sed", "stat", "sh", "tr", "tar", "uname", "xargs", "xz", "xxd"
        )
        val busybox = File(binDir, "busybox")
        for (linkName in links) {
            try {
                createSymbolicLink(busybox, linkName, binDir, true)
                Log.d(TAG, "Created busybox link for '$linkName'")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create link for '$linkName'", e)
            }
        }
        try {
            val fileLink = File(binDir, "file")
            if (!fileLink.exists()) {
                Files.createSymbolicLink(fileLink.toPath(), File("/system/bin/file").toPath())
                Log.d(TAG, "Created symlink for 'file'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create symlink for 'file'", e)
        }
    }

    private fun writeInputToKernel(session: TerminalSessionData, input: String, source: String) {
        val writer = session.sessionWriter
            ?: throw IllegalStateException("Terminal session ${session.id} is not writable")
        writer.write(input)
        writer.flush()
        // 原始输入可能是密码或令牌，仅记录提交边界，不持久化正文。
        Log.d(TAG, "Sent terminal input to kernel [source=$source, sessionId=${session.id}, chars=${input.length}]")
    }

    /**
     * 发送命令
     */
    suspend fun sendCommand(command: String, commandId: String? = null): String {
        val sessionId = sessionManager.getCurrentSession()?.id ?: error("No terminal session selected")
        return sendUserCommand(sessionId, command, commandId)
    }

    internal suspend fun sendUserCommand(sessionId: String, command: String, commandId: String? = null): String {
        val actualCommandId = commandId ?: UUID.randomUUID().toString()
        val session = sessionManager.getSession(sessionId) ?: error("Terminal session was closed")

        // Allow input during initialization (e.g. password prompt) or interactive mode
        val isInitializing = session.initState != SessionInitState.READY
        
        if (session.isInteractiveMode || isInitializing) {
            sendInputToSession(session.id, command + TERMINAL_ENTER)
            return actualCommandId
        }

        return sendCommandToSession(session.id, command, actualCommandId)
    }

    /**
     * 向指定会话发送命令（不切换当前会话）
     */
    suspend fun sendCommandToSession(sessionId: String, command: String, commandId: String? = null): String {
        // Bash strings cannot represent NUL. Reject before enqueueing or changing execution state,
        // rather than truncating the payload or treating a bad argument as a broken PTY writer.
        require('\u0000' !in command) { "Terminal commands cannot contain NUL (U+0000)" }
        require(commandId == null || '\u0000' !in commandId) { "Terminal command ID cannot contain NUL" }
        val actualCommandId = commandId ?: UUID.randomUUID().toString()
        while (true) {
            val observedSession = sessionManager.getSession(sessionId)
                ?: throw IllegalArgumentException("Terminal session does not exist: $sessionId")

            // A logical session survives PTY replacement. Wait for the authoritative generation
            // instead of retaining a writer observed before an EOF/recovery state transition.
            if (!isSessionRuntimeReady(observedSession)) {
                if (!ensureSessionReady(sessionId, SESSION_READY_TIMEOUT_MS)) {
                    throw IllegalStateException("Terminal session is not ready: $sessionId")
                }
                continue
            }

            var retryAfterReadinessCheck = false
            var submissionFailure: CommandSubmissionFailure? = null
            observedSession.commandMutex.withLock {
                val session = sessionManager.getSession(sessionId)
                    ?: throw IllegalArgumentException("Terminal session does not exist: $sessionId")
                if (!isSessionRuntimeReady(session)) {
                    retryAfterReadinessCheck = true
                    return@withLock
                }

                // Batch execution always needs a commandId-scoped envelope. Interactive replies
                // use terminal_input; raw input here would leave the event collector hanging.
                if (session.isInteractiveMode) {
                    throw IllegalStateException(
                        "Terminal session is awaiting interactive input; use terminal_input: $sessionId"
                    )
                }

                if (session.currentExecutingCommand?.isExecuting == true) {
                    session.commandQueue.add(QueuedCommand(actualCommandId, command))
                    Log.d(TAG, "Command queued for session $sessionId (id: $actualCommandId). Queue size: ${session.commandQueue.size}")
                } else {
                    submissionFailure = executeCommandInternal(command, session, actualCommandId)
                }
            }

            if (retryAfterReadinessCheck) {
                continue
            }
            submissionFailure?.let { failure ->
                recoverAfterCommandWriteFailure(sessionId, failure)
            }
            return actualCommandId
        }
    }

    suspend fun awaitSessionReady(sessionId: String, timeoutMs: Long = SESSION_READY_TIMEOUT_MS): Boolean {
        val current = sessionManager.getSession(sessionId) ?: return false
        if (isSessionRuntimeReady(current)) {
            return true
        }
        return withTimeoutOrNull(timeoutMs) {
            terminalState.first { state ->
                val session = state.sessions.find { it.id == sessionId }
                session == null || session.initState == SessionInitState.READY ||
                    session.initState == SessionInitState.FAILED
            }
            val ready = sessionManager.getSession(sessionId)
            ready != null && isSessionRuntimeReady(ready)
        } ?: false
    }

    suspend fun ensureSessionReady(sessionId: String, timeoutMs: Long = SESSION_READY_TIMEOUT_MS): Boolean {
        val session = sessionManager.getSession(sessionId) ?: return false
        if (isSessionRuntimeReady(session)) {
            return true
        }
        if (session.initState == SessionInitState.FAILED ||
            (session.initState == SessionInitState.READY && !isSessionRuntimeReady(session))
        ) {
            return recoverSession(
                sessionId = sessionId,
                expectedSession = session.terminalSession,
                expectedGeneration = session.shellGeneration,
                reason = "session requested without a live PTY",
            )
        }
        return awaitSessionReady(sessionId, timeoutMs)
    }

    suspend fun awaitCommandSettlement(sessionId: String, commandId: String, timeoutMs: Long): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            var commandSettled = false
            while (!commandSettled) {
                val session = sessionManager.getSession(sessionId) ?: return@withTimeoutOrNull false
                val current = session.currentExecutingCommand
                commandSettled = isTargetCommandSettled(
                    targetCommandId = commandId,
                    currentCommandId = current?.id,
                    currentCommandExecuting = current?.isExecuting == true,
                )
                if (!commandSettled) {
                    delay(25L)
                }
            }
            true
        } ?: false
    }

    /**
     * 在指定会话中执行一条命令并等待权威完成事件。
     *
     * 调用方必须提供目标会话 ID；该方法不会改变当前可见 tab，也不会在会话初始化阶段把
     * 命令误当作原始 PTY 输入。它是环境配置等批处理流程的唯一等待入口。
     */
    suspend fun executeCommandAndWait(
        sessionId: String,
        command: String,
        timeoutMs: Long = 1_800_000L,
        captureOutput: Boolean = true,
    ): CommandExecutionEvent? {
        val commandId = UUID.randomUUID().toString()
        val completed = CompletableDeferred<CommandExecutionEvent>()
        val output = StringBuilder()
        // SharedFlow has no replay. UNDISPATCHED runs through subscription registration before the
        // command can publish a fast start/output/completion sequence.
        val collector = coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            commandExecutionEvents
                .filter { event -> event.sessionId == sessionId && event.commandId == commandId }
                .collect { event ->
                    if (event.isCompleted) {
                        if (!completed.isCompleted) {
                            // Return a local snapshot for setup callers without putting the body
                            // back into the shared completion event protocol.
                            completed.complete(event.copy(outputChunk = output.toString()))
                        }
                    } else if (captureOutput) {
                        output.append(event.outputChunk)
                    }
                }
        }

        return try {
            // Use the same runtime-ready gate as normal command submission. A stale READY state
            // with a dead writer/process must trigger the existing same-session recovery instead
            // of returning null and leaving environment setup without its command result.
            val ready = withTimeoutOrNull(180_000L) {
                ensureSessionReady(sessionId, SESSION_READY_TIMEOUT_MS)
            } == true
            if (!ready) {
                Log.e(TAG, "Timed out waiting for terminal session to become ready: $sessionId")
                null
            } else {
                val session = sessionManager.getSession(sessionId)
                if (session == null) {
                    Log.e(TAG, "Cannot execute command in missing terminal session: $sessionId")
                    null
                } else if (session.isInteractiveMode) {
                    Log.e(TAG, "Cannot execute batch command while session awaits interactive input: $sessionId")
                    null
                } else {
                    try {
                        sendCommandToSession(sessionId, command, commandId)
                        val completedWithinDeadline = withTimeoutOrNull(timeoutMs) { completed.await() }
                        if (completedWithinDeadline != null) {
                            completedWithinDeadline
                        } else {
                            Log.w(TAG, "Command execution timed out after ${timeoutMs}ms: $commandId")
                            try {
                                cancelCommand(
                                    sessionId = sessionId,
                                    commandId = commandId,
                                    settleTimeoutMs = SESSION_CLOSE_SETTLE_TIMEOUT_MS,
                                )
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Log.e(TAG, "Failed to cancel timed-out command $commandId", error)
                            }
                            withTimeoutOrNull(SESSION_CLOSE_SETTLE_TIMEOUT_MS) { completed.await() }
                        }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e(TAG, "Failed to submit command for session $sessionId", error)
                        null
                    }
                }
            }
        } finally {
            collector.cancel()
        }
    }

    /**
     * 处理队列中的下一个命令
     */
    private suspend fun processNextQueuedCommand(sessionId: String) {
        val observedSession = sessionManager.getSession(sessionId) ?: return
        var submissionFailure: CommandSubmissionFailure? = null
        observedSession.commandMutex.withLock {
            val session = sessionManager.getSession(sessionId) ?: return@withLock
            if (session.currentExecutingCommand?.isExecuting == true) {
                Log.w(TAG, "processNextQueuedCommand called, but a command is still executing. This should not happen.")
                return@withLock
            }

            // Recovery owns queue resumption. Leaving the head in place here prevents an old EOF
            // callback or a duplicate completion signal from writing it through a stale writer.
            if (!isSessionRuntimeReady(session)) {
                return@withLock
            }

            if (session.commandQueue.isNotEmpty()) {
                val nextCommand = session.commandQueue.removeAt(0)
                Log.d(TAG, "Processing next queued command (id: ${nextCommand.id}). Queue size: ${session.commandQueue.size}")
                submissionFailure = executeCommandInternal(nextCommand.command, session, nextCommand.id)
            }
        }
        submissionFailure?.let { failure ->
            recoverAfterCommandWriteFailure(sessionId, failure)
        }
    }

    /**
     * 内部执行命令的函数, 必须在 commandMutex 锁内部调用
     */
    private fun executeCommandInternal(
        command: String,
        session: TerminalSessionData,
        commandId: String,
    ): CommandSubmissionFailure? {
        handleRegularCommand(command, session, commandId)
        return try {
            val wrappedCommand = buildCommandWithExitMarker(command, commandId)
            val fullInput = "$wrappedCommand$TERMINAL_ENTER"
            writeInputToKernel(session, fullInput, "command")
            Log.d(TAG, "Sent command to PTY for session ${session.id}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error sending command", e)
            val failure = CommandSubmissionFailure(
                terminalSession = session.terminalSession,
                shellGeneration = session.shellGeneration,
            )
            // A broken writer cannot produce either the OSC envelope or a prompt. Complete only
            // this command, preserve queued commandIds, then replace the failed PTY generation.
            outputProcessor.abortCurrentCommandForRecovery(session.id, sessionManager)
            failure
        }
    }

    private suspend fun recoverAfterCommandWriteFailure(
        sessionId: String,
        failure: CommandSubmissionFailure,
    ) {
        val recovered = recoverSession(
            sessionId = sessionId,
            expectedSession = failure.terminalSession,
            expectedGeneration = failure.shellGeneration,
            reason = "command writer failed",
        )
        if (!recovered) {
            Log.e(TAG, "Failed to recover terminal session $sessionId after command writer failure")
        }
    }

    /**
     * Keep the interactive shell state while exposing the command's real status.
     * The OSC marker is consumed by the terminal parser and is available to
     * OutputProcessor through the raw stream without polluting the visible terminal.
     */
    private fun buildCommandWithExitMarker(command: String, commandId: String): String =
        buildCommandWithExitMarkerProtocol(command, commandId)

    /**
     * 发送输入
     */
    fun sendInput(input: String) {
        val targetId = sessionManager.getCurrentSession()?.id ?: return
        coroutineScope.launch(Dispatchers.IO) {
            try {
                sendInputToSession(targetId, input)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
                Log.e(TAG, "Error sending input", e)
            }
        }
    }

    /**
     * 发送中断信号
     */
    fun sendInterruptSignal() {
        val targetId = sessionManager.getCurrentSession()?.id ?: return
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val currentSession = sessionManager.getSession(targetId)
                currentSession?.let {
                    val commandId = it.currentExecutingCommand?.takeIf { command -> command.isExecuting }?.id
                    if (commandId == null) {
                        writeInputToKernel(it, "\u0003", "interrupt")
                        Log.d(TAG, "Sent interrupt signal (Ctrl+C) to idle session ${it.id}")
                    } else {
                        cancelCommand(it.id, commandId, SESSION_CLOSE_SETTLE_TIMEOUT_MS)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending interrupt signal", e)
            }
        }
    }

    suspend fun sendInputToSession(sessionId: String, input: String) = withContext(Dispatchers.IO) {
        val session = sessionManager.getSession(sessionId) ?: error("Terminal session was closed")
        writeInputToKernel(session, input, "direct-input")
        if (session.isWaitingForInteractiveInput) {
            sessionManager.updateSession(session.id) { it.copy(isWaitingForInteractiveInput = false) }
        }
    }

    private suspend fun startSession(sessionId: String) {
        try { environmentOperations.run { startSessionInternal(sessionId) } }
        catch (busy: TerminalEnvironmentMaintenanceException) { markSessionFailed(sessionId, busy.message.orEmpty()) }
    }

    private suspend fun startSessionInternal(sessionId: String) {
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Starting session $sessionId")
                closingSessions.remove(sessionId)

                val provider = getTerminalProvider()
                val (terminalSession, pty) = provider.startSession(sessionId).getOrThrow()
                val sessionWriter = terminalSession.stdin.writer()

                val readJob = coroutineScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
                    var reachedEof = false
                    try {
                        terminalSession.stdout.use { inputStream ->
                            val buffer = ByteArray(4096)
                            var bytesRead: Int
                            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                                val chunk = String(buffer, 0, bytesRead)
                                outputProcessor.processOutput(sessionId, chunk, sessionManager)
                            }
                            reachedEof = true
                        }
                    } catch (e: java.io.InterruptedIOException) {
                        Log.i(TAG, "Read job interrupted for session $sessionId.")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in read job for session $sessionId", e)
                    } finally {
                        if (closingSessions.remove(sessionId)) {
                            return@launch
                        }
                        val boundSession = sessionManager.getSession(sessionId)
                        if (boundSession?.terminalSession !== terminalSession) {
                            return@launch
                        }
                        if (reachedEof || !terminalSession.process.isAlive) {
                            handleTerminalSessionExit(sessionId, terminalSession)
                        }
                    }
                }

                sessionManager.updateSession(sessionId) { session ->
                    session.copy(
                        terminalSession = terminalSession,
                        pty = pty,
                        sessionWriter = sessionWriter,
                        readJob = readJob
                    )
                }
                readJob.start()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting session $sessionId", e)
                markSessionFailed(sessionId, e.message ?: "terminal session start failed")
            }
        }
    }

    private suspend fun handleTerminalSessionExit(sessionId: String, terminalSession: TerminalSession) {
        val exitCode =
            if (terminalSession.process.isAlive) {
                -1
            } else {
                runCatching { terminalSession.process.waitFor() }
                    .getOrElse {
                        Log.w(TAG, "Failed to read exit code for session $sessionId", it)
                        -1
                    }
            }

        Log.i(TAG, "Terminal session $sessionId exited with code $exitCode")
        val observedSession = sessionManager.getSession(sessionId) ?: return
        var handledExit = false
        var wasReady = false
        var expectedGeneration = observedSession.shellGeneration
        observedSession.commandMutex.withLock {
            val session = sessionManager.getSession(sessionId) ?: return@withLock
            if (session.terminalSession !== terminalSession) {
                return@withLock
            }

            handledExit = true
            wasReady = session.initState == SessionInitState.READY
            expectedGeneration = session.shellGeneration
            outputProcessor.handleSessionExit(
                sessionId = sessionId,
                message = application.getString(R.string.terminal_exited_with_code, exitCode),
                sessionManager = sessionManager
            )
            // Invalidate the dead writer under the same mutex used by command submission. A new
            // command can only observe INITIALIZING or the next READY shell generation.
            sessionManager.updateSession(sessionId) {
                it.copy(
                    sessionWriter = null,
                    initState = SessionInitState.INITIALIZING,
                    isInteractiveMode = false,
                    interactivePrompt = "",
                )
            }
        }
        if (!handledExit) {
            return
        }
        if (wasReady) {
            coroutineScope.launch {
                recoverSession(
                    sessionId = sessionId,
                    expectedSession = terminalSession,
                    expectedGeneration = expectedGeneration,
                    reason = "process exited with code $exitCode",
                )
            }
        } else {
            markSessionFailed(sessionId, "terminal process exited before READY with code $exitCode")
        }
    }

    private suspend fun recoverSession(
        sessionId: String,
        expectedSession: TerminalSession?,
        expectedGeneration: Long,
        reason: String,
    ): Boolean {
        val mutex = sessionRecoveryMutexes.computeIfAbsent(sessionId) { Mutex() }
        var shouldResumeQueue = false
        val ready = mutex.withLock recoveryLock@{
            val session = sessionManager.getSession(sessionId) ?: return@recoveryLock false
            val liveAndReady = isSessionRuntimeReady(session)
            if (session.shellGeneration != expectedGeneration ||
                (expectedSession != null && session.terminalSession !== expectedSession)
            ) {
                return@recoveryLock liveAndReady
            }

            Log.w(TAG, "Recovering terminal session $sessionId: $reason")
            var generationInvalidated = false
            session.commandMutex.withLock commandLock@{
                val boundSession = sessionManager.getSession(sessionId) ?: return@commandLock
                if (boundSession.shellGeneration != expectedGeneration ||
                    (expectedSession != null && boundSession.terminalSession !== expectedSession)
                ) {
                    return@commandLock
                }

                closeSessionRuntime(sessionId, boundSession)
                // A dead process can be observed before its reader publishes EOF. Complete that
                // command here so its collector cannot remain suspended across the new shell.
                outputProcessor.abortCurrentCommandForRecovery(sessionId, sessionManager)
                val resetSession = sessionManager.getSession(sessionId) ?: return@commandLock
                outputProcessor.clearSessionState(sessionId)
                resetSession.rawBuffer.clear()
                resetSession.currentCommandOutput.clear()
                resetSession.currentOutputLineCount = 0
                resetSession.currentCommandExitCode = null
                resetSession.currentCommandCancellationRequested = false
                resetSession.currentExecutingCommand = null
                sessionManager.updateSession(sessionId) {
                    it.copy(
                        terminalSession = null,
                        pty = null,
                        sessionWriter = null,
                        readJob = null,
                        currentDirectory = "$ ",
                        isWaitingForInteractiveInput = false,
                        lastInteractivePrompt = "",
                        isInteractiveMode = false,
                        interactivePrompt = "",
                        initState = SessionInitState.INITIALIZING,
                        isFullscreen = false,
                        shellGeneration = it.shellGeneration + 1L,
                    )
                }
                generationInvalidated = true
            }
            if (!generationInvalidated) {
                val current = sessionManager.getSession(sessionId)
                return@recoveryLock current != null && isSessionRuntimeReady(current)
            }

            val environmentReady = initializeEnvironment()
            if (!environmentReady) {
                markSessionFailed(sessionId, "terminal environment initialization failed during recovery")
                return@recoveryLock false
            }
            startSession(sessionId)
            awaitSessionReady(sessionId, SESSION_READY_TIMEOUT_MS).also {
                shouldResumeQueue = it
            }
        }
        // Resume outside the recovery mutex. A queued writer failure may need another recovery,
        // and Mutex is intentionally non-reentrant.
        if (shouldResumeQueue) {
            processNextQueuedCommand(sessionId)
        }
        return ready
    }

    private suspend fun closeSessionRuntime(sessionId: String, session: TerminalSessionData) {
        closingSessions.add(sessionId)
        runCatching { session.sessionWriter?.close() }
            .onFailure { Log.w(TAG, "Failed to close terminal writer for $sessionId", it) }
        runCatching { session.readJob?.cancel() }
            .onFailure { Log.w(TAG, "Failed to cancel terminal reader for $sessionId", it) }
        runCatching { getTerminalProvider().closeSession(sessionId) }
            .onFailure { Log.w(TAG, "Failed to close provider session $sessionId", it) }
        runCatching {
            if (session.terminalSession?.process?.isAlive == true) {
                session.terminalSession.process.destroy()
            }
        }.onFailure { Log.w(TAG, "Failed to destroy terminal process for $sessionId", it) }
    }

    private fun markSessionFailed(sessionId: String, reason: String) {
        Log.e(TAG, "Terminal session $sessionId entered FAILED state: $reason")
        sessionManager.updateSession(sessionId) { session ->
            session.copy(
                terminalSession = null,
                pty = null,
                sessionWriter = null,
                readJob = null,
                initState = SessionInitState.FAILED,
                isWaitingForInteractiveInput = false,
                isInteractiveMode = false,
                interactivePrompt = "",
            )
        }
    }
    
    /**
     * 获取或创建单例的终端提供者
     */
    private suspend fun getTerminalProvider(): TerminalProvider = environmentOperations.run {
        getTerminalProviderInternal()
    }

    private suspend fun getTerminalProviderInternal(): TerminalProvider {
        providerMutex.withLock {
            if (terminalProvider == null) {
                val provider = if (sshConfigManager.isEnabled()) {
                    val sshConfig = checkNotNull(sshConfigManager.getConfig()) { "SSH is enabled but no connection is configured" }
                    Log.d(TAG, "Creating singleton SSH terminal provider")
                    SSHTerminalProvider(application, sshConfig, this)
                } else {
                    Log.d(TAG, "Creating singleton local terminal provider")
                    LocalTerminalProvider(application)
                }
                provider.connect().getOrThrow()
                terminalProvider = provider
                _activeEnvironmentType.value = if (provider is SSHTerminalProvider) TerminalType.SSH else TerminalType.LOCAL
            }
        }
        return terminalProvider!!
    }

    suspend fun initializeEnvironment(): Boolean = try {
        environmentOperations.run { initializeEnvironmentInternal() }
    } catch (_: TerminalEnvironmentMaintenanceException) { false }

    private suspend fun initializeEnvironmentInternal(): Boolean {
        if (isEnvInitialized) {
            return withContext(Dispatchers.IO) {
                try {
                    val startScript = generateStartScript()
                    File(filesDir, "common.sh").writeText(startScript.replace("\r\n", "\n").replace("\r", "\n"))
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Environment script refresh failed", e)
                    false
                }
            }
        }

        envInitMutex.lock()
        try {
            if (isEnvInitialized) {
                return true
            }

            val success = withContext(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Starting environment initialization...")

                    // 1. Create necessary directories
                    createDirectories()

                    // 2. Link native libraries
                    linkNativeLibs()
                    createBusyboxSymlinks()

                    // 3. Extract assets
                    extractAssets()

                    // 4. Generate and write startup script
                    val startScript = generateStartScript()
                    File(filesDir, "common.sh").writeText(startScript.replace("\r\n", "\n").replace("\r", "\n"))


                    Log.d(TAG, "Environment initialization completed successfully.")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Environment initialization failed", e)
                    false
                }
            }
            if (success) {
                isEnvInitialized = true
            }
            return success
        } finally {
            envInitMutex.unlock()
        }
    }

    private fun createDirectories() {
        if (!usrDir.exists()) {
            usrDir.mkdirs()
        }
        if (!binDir.exists()) {
            binDir.mkdirs()
            Log.d(TAG, "Created bin directory at: ${binDir.absolutePath}")
        }
        File(filesDir, "tmp").mkdirs()
    }

    private fun linkNativeLibs() {
        Log.d(TAG, "Linking native libraries from: $nativeLibDir")

        val nativeLibDirFile = File(nativeLibDir)
        if (!nativeLibDirFile.exists() || !nativeLibDirFile.isDirectory) {
            Log.e(TAG, "Native library directory not found or is not a directory.")
            return
        }

        Log.d(TAG, "Native lib directory contents:")
        nativeLibDirFile.listFiles()?.forEach { file ->
            Log.d(TAG, "  - ${file.name} (file ${file.length()} bytes)")
        }

        val busybox = File(binDir, "busybox")

        // First, we need to link busybox itself so we can use it.
        val busyboxSo = File(nativeLibDir, "libbusybox.so")
        Log.d(TAG, "Checking busybox: libbusybox.so exists = ${busyboxSo.exists()}, busybox exists = ${busybox.exists()}")

        if (!busyboxSo.exists()) {
            Log.e(TAG, "libbusybox.so not found, cannot create busybox link")
            return
        }

        // Always ensure proper busybox link - remove any existing file/broken link first
        try {
            val link = busybox.toPath()
            val target = busyboxSo.toPath()

            // Delete existing file/broken link if it exists to prevent FileAlreadyExistsException
            Files.deleteIfExists(link)

            // CRITICAL: Set execute permission on the target .so file before creating symlink
            busyboxSo.setExecutable(true, false)

            // Create the symbolic link
            Files.createSymbolicLink(link, target)
            Log.d(TAG, "Created busybox symbolic link using Java NIO")

            // Verify the link was created successfully and is functional
            if (busybox.exists() && busybox.canExecute()) {
                Log.d(TAG, "Verification: busybox link exists and is executable at ${busybox.absolutePath}")
            } else {
                Log.e(TAG, "Verification failed: busybox link not functional after creation")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create busybox link using Java NIO", e)
            return
        }

        try {
            Files.deleteIfExists(File(binDir, "libtalloc.so.2").toPath())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove stale libtalloc link", e)
        }

        if (!installSudoShim()) {
            return
        }

        // Symlink other binaries
        val libraries = mapOf(
            "liboperit_proot.so" to "proot",
            "liboperit_loader.so" to "loader",
            "libbash.so" to "bash"
        )

        libraries.forEach { (libName, linkName) ->
            val libFile = File(nativeLibDir, libName)
            val linkFile = File(binDir, linkName)

            Log.d(TAG, "Checking $libName at ${libFile.absolutePath}, exists: ${libFile.exists()}")

            if (!libFile.exists()) {
                Log.w(TAG, "Native library not found: $libName")
                return@forEach
            }

            // Always ensure proper link - remove any existing file/broken link first
            try {
                val link = linkFile.toPath()
                val target = libFile.toPath()

                // Delete existing file/broken link if it exists to prevent FileAlreadyExistsException
                Files.deleteIfExists(link)

                // CRITICAL: Set execute permission on the target .so file before creating symlink
                libFile.setExecutable(true, false)

                // Create the symbolic link
                Files.createSymbolicLink(link, target)
                Log.d(TAG, "Created $linkName symbolic link using Java NIO")

                // Verify the link was created successfully and is executable
                if (linkFile.exists() && linkFile.canExecute()) {
                    Log.d(TAG, "Verification: $linkName link exists and is executable at ${linkFile.absolutePath}")
                } else {
                    Log.w(TAG, "Verification failed: $linkName link not executable after creation")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create $linkName link using Java NIO", e)
            }
        }
    }

    /**
     * Sends Ctrl+C to a specific session without changing the visible active tab.
     * Timeout cleanup must target the command's session even when another session is
     * currently selected in the UI.
     */
    fun sendInterruptSignal(sessionId: String) {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val session = sessionManager.getSession(sessionId)
                if (session == null) {
                    Log.w(TAG, "Cannot interrupt missing terminal session $sessionId")
                    return@launch
                }
                val commandId = session.currentExecutingCommand?.takeIf { it.isExecuting }?.id
                if (commandId == null) {
                    writeInputToKernel(session, "\u0003", "interrupt-session")
                    Log.d(TAG, "Sent interrupt signal (Ctrl+C) to idle session $sessionId")
                } else {
                    cancelCommand(sessionId, commandId, SESSION_CLOSE_SETTLE_TIMEOUT_MS)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending interrupt signal to session $sessionId", e)
            }
        }
    }

    /**
     * Cancels one exact command without allowing a timed-out queued call to interrupt a different
     * command. Ctrl+C can prevent the trailing OSC status envelope from running; marking the
     * command first lets the following real prompt close it. If no prompt arrives, replace the PTY
     * under the same logical session ID so later commands cannot inherit a deaf state machine.
     */
    suspend fun cancelCommand(
        sessionId: String,
        commandId: String,
        settleTimeoutMs: Long = SESSION_CLOSE_SETTLE_TIMEOUT_MS,
    ): CommandCancellationResult {
        val observedSession = sessionManager.getSession(sessionId)
            ?: return CommandCancellationResult(
                commandFound = false,
                settled = false,
                sessionHealthy = false,
                sessionRecovered = false,
                contextPreserved = false,
            )

        var cancelExecuting = false
        var cancelQueued = false
        var expectedSession: TerminalSession? = null
        var expectedGeneration = observedSession.shellGeneration
        observedSession.commandMutex.withLock {
            val session = sessionManager.getSession(sessionId) ?: return@withLock
            val current = session.currentExecutingCommand
            if (current?.id == commandId && current.isExecuting) {
                session.currentCommandCancellationRequested = true
                session.currentCommandExitCode = -1
                expectedSession = session.terminalSession
                expectedGeneration = session.shellGeneration
                try {
                    writeInputToKernel(session, "\u0003", "cancel-command")
                } catch (error: Exception) {
                    // Keep the cancellation state authoritative even if the old writer has already
                    // closed. Settlement will time out and the same logical session will be rebuilt.
                    Log.w(TAG, "Unable to write cancellation signal for command $commandId", error)
                }
                cancelExecuting = true
            } else {
                val queuedIndex = session.commandQueue.indexOfFirst { it.id == commandId }
                if (queuedIndex >= 0) {
                    session.commandQueue.removeAt(queuedIndex)
                    cancelQueued = true
                }
            }
        }

        if (cancelQueued) {
            commandEventDispatcher.offer(
                completedCommandExecutionEvent(
                    commandId = commandId,
                    sessionId = sessionId,
                    exitCode = -1,
                )
            )
            return currentSessionHealth(
                sessionId = sessionId,
                commandFound = true,
                settled = true,
                sessionRecovered = false,
                contextPreserved = true,
            )
        }

        if (!cancelExecuting) {
            return currentSessionHealth(
                sessionId = sessionId,
                commandFound = false,
                settled = true,
                sessionRecovered = false,
                contextPreserved = true,
            )
        }

        if (awaitCommandSettlement(sessionId, commandId, settleTimeoutMs)) {
            return currentSessionHealth(
                sessionId = sessionId,
                commandFound = true,
                settled = true,
                sessionRecovered = false,
                contextPreserved = true,
            )
        }

        // No prompt means this PTY cannot prove that it returned to a command boundary. Publish a
        // terminal cancellation outcome before replacing it, then resume queued work only after
        // the replacement shell reaches READY.
        outputProcessor.abortCurrentCommandForRecovery(sessionId, sessionManager)
        val recovered = recoverSession(
            sessionId = sessionId,
            expectedSession = expectedSession,
            expectedGeneration = expectedGeneration,
            reason = "command cancellation did not settle",
        )
        return currentSessionHealth(
            sessionId = sessionId,
            commandFound = true,
            settled = true,
            sessionRecovered = recovered,
            contextPreserved = !recovered,
        )
    }

    private fun currentSessionHealth(
        sessionId: String,
        commandFound: Boolean,
        settled: Boolean,
        sessionRecovered: Boolean,
        contextPreserved: Boolean,
    ): CommandCancellationResult {
        val session = sessionManager.getSession(sessionId)
        val healthy = session != null && isSessionRuntimeReady(session)
        return CommandCancellationResult(
            commandFound = commandFound,
            settled = settled,
            sessionHealthy = healthy,
            sessionRecovered = sessionRecovered,
            contextPreserved = contextPreserved && healthy,
        )
    }

    private fun installSudoShim(): Boolean {
        val sudoFile = File(binDir, "sudo")
        return try {
            Files.deleteIfExists(sudoFile.toPath())
            sudoFile.writeText(
                "#!/system/bin/sh\n" +
                    "exec \"${'$'}@\"\n"
            )
            val executable = sudoFile.setExecutable(true, false) && sudoFile.canExecute()
            if (!executable) {
                Log.e(TAG, "Failed to mark sudo shim executable: ${sudoFile.absolutePath}")
                false
            } else {
                Log.d(TAG, "Installed sudo command shim at ${sudoFile.absolutePath}")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install sudo command shim", e)
            false
        }
    }

    @Throws(IOException::class)
    private fun createSymbolicLink(target: File, linkName: String, linkDir: File, force: Boolean) {
        val linkFile = File(linkDir, linkName)

        // Use relative path for target if it's in the same directory
        val targetPath = if (target.parentFile == linkDir) {
            Paths.get(target.name)
        } else {
            target.toPath()
        }

        if (force) {
            Files.deleteIfExists(linkFile.toPath())
        }
        Files.createSymbolicLink(linkFile.toPath(), targetPath)
    }

    private fun readUbuntuRootfsManifest(): UbuntuRootfsManifest {
        val json = application.assets.open(UBUNTU_MANIFEST_FILENAME).use { input ->
            input.bufferedReader(Charsets.UTF_8).readText()
        }
        return UbuntuRootfsManifest.parse(json)
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun materializeVerifiedAsset(manifest: UbuntuRootfsManifest): File {
        val destination = File(filesDir, manifest.assetFilename)
        if (destination.isFile && destination.length() == manifest.compressedBytes) {
            val existingHash = sha256Hex(destination)
            if (existingHash == manifest.assetSha256) {
                Log.d(TAG, "Verified existing Ubuntu rootfs asset: ${destination.absolutePath}")
                return destination
            }
            Log.w(TAG, "Existing Ubuntu rootfs asset hash mismatch; replacing it atomically")
        }

        val temporary = File(filesDir, ".${manifest.assetFilename}.tmp-${UUID.randomUUID()}")
        try {
            var copiedBytes = 0L
            application.assets.open(manifest.assetFilename).use { input ->
                temporary.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copiedBytes += read
                    }
                    output.flush()
                }
            }
            require(copiedBytes == manifest.compressedBytes) {
                "Ubuntu rootfs asset size mismatch: expected=${manifest.compressedBytes} actual=$copiedBytes"
            }
            require(sha256Hex(temporary) == manifest.assetSha256) {
                "Ubuntu rootfs asset SHA-256 mismatch after extraction"
            }
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            Log.d(TAG, "Materialized verified Ubuntu rootfs asset: ${destination.absolutePath}")
            return destination
        } catch (e: Exception) {
            Log.e(TAG, "Failed to materialize verified Ubuntu rootfs asset", e)
            throw e
        } finally {
            Files.deleteIfExists(temporary.toPath())
        }
    }

    private fun extractAssets() {
        try {
            val manifest = readUbuntuRootfsManifest()
            require(manifest.assetFilename == UBUNTU_FILENAME) {
                "Manifest asset does not match the terminal runtime contract"
            }
            materializeVerifiedAsset(manifest)

            val scriptFile = File(filesDir, "setup_fake_sysdata.sh")
            application.assets.open("setup_fake_sysdata.sh").use { input ->
                val raw = input.readBytes()
                val text = raw.toString(Charsets.UTF_8)
                val normalized =
                    text
                        .removePrefix("\uFEFF")
                        .replace("\r\n", "\n")
                        .replace("\r", "\n")
                scriptFile.writeText(normalized, Charsets.UTF_8)
            }
            Log.d(TAG, "Refreshed setup_fake_sysdata.sh")
        } catch (e: IOException) {
            Log.e(TAG, "Failed to extract assets", e)
            throw e
        }
    }

    private fun generateStartScript(): String {
        val manifest = readUbuntuRootfsManifest()
        val ubuntuName = "ubuntu-${manifest.codename}-aarch64"
        val tmpDir = File(filesDir, "tmp").absolutePath
        val binDir = binDir.absolutePath
        val homeDir = filesDir.absolutePath
        val usrDir = usrDir.absolutePath
        val prootDistroPath = "$usrDir/var/lib/proot-distro"
        val ubuntuPath = "$prootDistroPath/installed-rootfs/ubuntu"
        val operitPackage = application.packageName
        val operitDataDir = application.applicationInfo.dataDir
        val currentEmulatedStoragePath = PRootMountMapping.currentEmulatedStoragePath()
        val currentUserDataRootPath = PRootMountMapping.currentUserDataRootPath()
        val operitUserDataMountPath = "$currentUserDataRootPath/$operitPackage"
        val operitLegacyDataRootPath = PRootMountMapping.legacyDataRootPath()
        val operitLegacyDataMountPath = PRootMountMapping.legacyAppDataPath(operitPackage)
        val localTmpPath = PRootMountMapping.localTmpPath()
        val guestSdcardPath = PRootMountMapping.guestSdcardPath()
        // Pass the Android host zone explicitly because every Ubuntu launch uses env -i and
        // therefore cannot inherit the device timezone from the parent process.
        val hostTimeZone = TimeZone.getDefault().id

        // 获取当前选择的源
        val aptSource = sourceManager.getSelectedSource(PackageManagerType.APT)
        val pipSource = sourceManager.getSelectedSource(PackageManagerType.PIP)
        val npmSource = sourceManager.getSelectedSource(PackageManagerType.NPM)

        val common = """
        export TMPDIR=$tmpDir
        export BIN=$binDir
        export HOME=$homeDir
        export UBUNTU_PATH=$ubuntuPath
        export UBUNTU=$UBUNTU_FILENAME
        export UBUNTU_NAME=$ubuntuName
        export USE_CHROOT=${if (prefs.getBoolean("chroot_enabled", false)) "1" else "0"}
        export OPERIT_UID=$(id -u)
        export OPERIT_GID=$(id -g)
        export OPERIT_GROUPS=$(id -G | tr ' ' ',')
        export L_NOT_INSTALLED="not installed"
        export L_INSTALLING="installing"
        export L_INSTALLED="installed"
        clear_lines(){
          printf "\\033[1A" # Move cursor up one line
          printf "\\033[K"  # Clear the line
          printf "\\033[1A" # Move cursor up one line
          printf "\\033[K"  # Clear the line
        }
        progress_echo(){
          echo -e "\\033[31m- ${'$'}@\\033[0m"
          echo "${'$'}@" > "${'$'}TMPDIR/progress_des"
        }
        bump_progress(){
          current=0
          if [ -f "${'$'}TMPDIR/progress" ]; then
            current=${'$'}(cat "${'$'}TMPDIR/progress" 2>/dev/null || echo 0)
          fi
          next=${'$'}((current + 1))
          printf "${'$'}next" > "${'$'}TMPDIR/progress"
        }
        write_default_dns(){
          target_file="${'$'}1"
          if [ -z "${'$'}target_file" ]; then
            return 1
          fi
          cat > "${'$'}target_file" <<'EOF'
nameserver 8.8.8.8
nameserver 1.1.1.1
nameserver 223.5.5.5
nameserver 223.6.6.6
nameserver 119.29.29.29
nameserver 180.76.76.76
EOF
        }
        can_access_bind_source(){
          bind_source="${'$'}1"
          if [ -z "${'$'}bind_source" ]; then
            return 1
          fi
          if [ ! -e "${'$'}bind_source" ] && [ ! -L "${'$'}bind_source" ]; then
            return 1
          fi
          "${'$'}BIN/busybox" ls -Ld "${'$'}bind_source" >/dev/null 2>&1
        }
        append_proot_bind_arg(){
          bind_source="${'$'}1"
          bind_target="${'$'}2"
          if ! can_access_bind_source "${'$'}bind_source"; then
            return 0
          fi
          if [ -z "${'$'}bind_target" ] || [ "${'$'}bind_source" = "${'$'}bind_target" ]; then
            PROOT_BIND_ARGS="${'$'}PROOT_BIND_ARGS -b ${'$'}bind_source"
          else
            PROOT_BIND_ARGS="${'$'}PROOT_BIND_ARGS -b ${'$'}bind_source:${'$'}bind_target"
          fi
        }
        run_proot_binary(){
          LD_LIBRARY_PATH= "${'$'}BIN/proot" "${'$'}@"
        }
        exec_proot_binary(){
          LD_LIBRARY_PATH= exec "${'$'}BIN/proot" "${'$'}@"
        }
        LAST_PROOT_PROBE_STATUS=0
        LAST_PROOT_PROBE_OUTPUT=""
        LAST_PROOT_PROBE_ARGS=""
        run_proot_probe(){
          LAST_PROOT_PROBE_ARGS="${'$'}*"
          if [ "${'$'}PROOT_LINK2SYMLINK" = "1" ]; then
            LAST_PROOT_PROBE_OUTPUT="${'$'}(
              run_proot_binary \
                -v 1 \
                -0 \
                -r "${'$'}UBUNTU_PATH" \
                --link2symlink \
                "${'$'}@" \
                -w /root \
                /usr/bin/env -i \
                  HOME=/root \
                  USER=root \
                  LOGNAME=root \
                  SHELL=/bin/bash \
                  TERM=xterm-256color \
                  LANG=en_US.UTF-8 \
                  TZ=$hostTimeZone \
                  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                  /bin/bash --noprofile --norc -c 'exit 0' 2>&1
            )"
            LAST_PROOT_PROBE_STATUS="${'$'}?"
          else
            LAST_PROOT_PROBE_OUTPUT="${'$'}(
              run_proot_binary \
                -v 1 \
                -0 \
                -r "${'$'}UBUNTU_PATH" \
                "${'$'}@" \
                -w /root \
                /usr/bin/env -i \
                  HOME=/root \
                  USER=root \
                  LOGNAME=root \
                  SHELL=/bin/bash \
                  TERM=xterm-256color \
                  LANG=en_US.UTF-8 \
                  TZ=$hostTimeZone \
                  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                  /bin/bash --noprofile --norc -c 'exit 0' 2>&1
            )"
            LAST_PROOT_PROBE_STATUS="${'$'}?"
          fi
          return "${'$'}LAST_PROOT_PROBE_STATUS"
        }
        print_last_proot_probe_failure(){
          echo "PRoot startup probe failed."
          echo "exit_code=${'$'}LAST_PROOT_PROBE_STATUS"
          echo "ubuntu_path=${'$'}UBUNTU_PATH"
          echo "proot_loader=${'$'}{PROOT_LOADER:-<unset>}"
          echo "proot_no_seccomp=${'$'}{PROOT_NO_SECCOMP:-<unset>}"
          echo "ld_library_path=${'$'}{LD_LIBRARY_PATH:-<unset>}"
          echo "proot_exec_ld_library_path=<empty>"
          echo "proot_link2symlink=${'$'}PROOT_LINK2SYMLINK"
          if [ -n "${'$'}PROOT_BIND_ARGS" ]; then
            echo "bind_args=${'$'}PROOT_BIND_ARGS"
          else
            echo "bind_args=<none>"
          fi
          if [ -n "${'$'}LAST_PROOT_PROBE_ARGS" ]; then
            echo "extra_args=${'$'}LAST_PROOT_PROBE_ARGS"
          else
            echo "extra_args=<none>"
          fi
          echo "--- proot stdout/stderr begin ---"
          if [ -n "${'$'}LAST_PROOT_PROBE_OUTPUT" ]; then
            printf '%s\n' "${'$'}LAST_PROOT_PROBE_OUTPUT"
          else
            echo "<empty>"
          fi
          echo "--- proot stdout/stderr end ---"
        }
        probe_proot_link2symlink(){
          run_proot_binary \
            -v 1 \
            -0 \
            -r "${'$'}UBUNTU_PATH" \
            --link2symlink \
            -w /root \
            /usr/bin/env -i \
              HOME=/root \
              USER=root \
              LOGNAME=root \
              SHELL=/bin/bash \
              TERM=xterm-256color \
              LANG=en_US.UTF-8 \
              TZ=$hostTimeZone \
              PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
              /bin/bash --noprofile --norc -c 'exit 0' >/dev/null 2>&1
        }
        probe_and_append_bind_arg(){
          bind_source="${'$'}1"
          bind_target="${'$'}2"
          if ! can_access_bind_source "${'$'}bind_source"; then
            return 0
          fi

          candidate_bind_args="-b ${'$'}bind_source"
          if [ -n "${'$'}bind_target" ] && [ "${'$'}bind_source" != "${'$'}bind_target" ]; then
            candidate_bind_args="-b ${'$'}bind_source:${'$'}bind_target"
          fi

          if [ -n "${'$'}PROOT_BIND_ARGS" ]; then
            set -- ${'$'}PROOT_BIND_ARGS ${'$'}candidate_bind_args
          else
            set -- ${'$'}candidate_bind_args
          fi

          if run_proot_probe "${'$'}@"; then
            append_proot_bind_arg "${'$'}bind_source" "${'$'}bind_target"
          fi
        }
        resolve_proot_runtime(){
          PROOT_LINK2SYMLINK=0
          PROOT_BIND_ARGS=""

          if ! run_proot_probe; then
            print_last_proot_probe_failure
            return 1
          fi

          if probe_proot_link2symlink; then
            PROOT_LINK2SYMLINK=1
          fi
          return 0
        }
        """.trimIndent()

        val installUbuntu = """
        install_ubuntu(){
          OK_FILE="${'$'}UBUNTU_PATH/${manifest.installedMarkerFilename}"
          MANIFEST_FILE="${'$'}UBUNTU_PATH/${manifest.manifestFilename}"
          LEGACY_OK_FILE="${'$'}UBUNTU_PATH/${manifest.legacyInstalledMarkerFilename}"
          LOCK_DIR="${'$'}UBUNTU_PATH.install.lock"
          LOCK_PID_FILE="${'$'}LOCK_DIR/pid"
          TMP_DIR="${'$'}UBUNTU_PATH.install.tmp"
          BACKUP_DIR="${'$'}UBUNTU_PATH.backup.${'$'}${'$'}"

          UBUNTU_PARENT="${'$'}{UBUNTU_PATH%/*}"
          if ! mkdir -p "${'$'}UBUNTU_PARENT" 2>/dev/null; then
            progress_echo "Ubuntu install directory is not writable"
            return 1
          fi

          attempt=0
          while true; do
            if mkdir "${'$'}LOCK_DIR" 2>/dev/null; then
              if printf '%s\n' "${'$'}${'$'}" > "${'$'}LOCK_PID_FILE" 2>/dev/null; then
                break
              fi
              rmdir "${'$'}LOCK_DIR" 2>/dev/null || true
              progress_echo "Ubuntu install lock initialization failed"
              return 1
            fi

            lock_pid=""
            if [ -f "${'$'}LOCK_PID_FILE" ]; then
              lock_pid=${'$'}(cat "${'$'}LOCK_PID_FILE" 2>/dev/null)
              if [ -n "${'$'}lock_pid" ] && ! kill -0 "${'$'}lock_pid" 2>/dev/null; then
                observed_lock_pid="${'$'}lock_pid"
                current_lock_pid=${'$'}(cat "${'$'}LOCK_PID_FILE" 2>/dev/null)
                if [ "${'$'}current_lock_pid" = "${'$'}observed_lock_pid" ]; then
                  rm -rf "${'$'}LOCK_DIR" 2>/dev/null || true
                  continue
                fi
              fi
            fi

            attempt=${'$'}((attempt + 1))
            if [ "${'$'}attempt" -gt 120 ]; then
              if [ -n "${'$'}lock_pid" ]; then
                progress_echo "Ubuntu install lock timeout (pid=${'$'}lock_pid)"
              else
                progress_echo "Ubuntu install lock timeout (owner unavailable)"
              fi
              return 1
            fi
            sleep 1
          done

          cleanup_install(){
            owns_lock=0
            if [ -f "${'$'}LOCK_PID_FILE" ] && [ "${'$'}(cat "${'$'}LOCK_PID_FILE" 2>/dev/null)" = "${'$'}${'$'}" ]; then
              owns_lock=1
            fi
            if [ "${'$'}owns_lock" -eq 1 ]; then
              rm -rf "${'$'}TMP_DIR" 2>/dev/null
              rm -rf "${'$'}LOCK_DIR" 2>/dev/null
            fi
          }
          trap 'cleanup_install' EXIT INT TERM

          append_staging_failure(){
            if [ -z "${'$'}staging_failure" ]; then
              staging_failure="${'$'}1"
            else
              staging_failure="${'$'}staging_failure; ${'$'}1"
            fi
          }
          report_staging_path(){
            staging_label="${'$'}1"
            staging_path="${'$'}2"
            if [ -L "${'$'}staging_path" ]; then
              staging_target=""
              if ! staging_target=${'$'}("${'$'}BIN/busybox" readlink "${'$'}staging_path" 2>/dev/null); then
                staging_target="<unreadable>"
              fi
              printf 'Ubuntu staging %s: symlink -> %s\n' "${'$'}staging_label" "${'$'}staging_target"
            elif [ -e "${'$'}staging_path" ]; then
              staging_mode=""
              if ! staging_mode=${'$'}("${'$'}BIN/busybox" stat -c '%a' "${'$'}staging_path" 2>/dev/null); then
                staging_mode="<unavailable>"
              fi
              printf 'Ubuntu staging %s: present mode=%s\n' "${'$'}staging_label" "${'$'}staging_mode"
            else
              printf 'Ubuntu staging %s: missing\n' "${'$'}staging_label"
            fi
          }
          has_owner_execute_bit(){
            executable_mode=""
            if ! executable_mode=${'$'}("${'$'}BIN/busybox" stat -c '%a' "${'$'}1" 2>/dev/null); then
              return 1
            fi
            # Android's test -x/access(X_OK) can reject an app-data rootfs path even when the
            # archive mode is correct.  Match stat's three- or four-digit octal form directly so
            # staging verifies the owner execute bit without weakening the required file mode.
            case "${'$'}executable_mode" in
              [1357][0-7][0-7]|[0-7][1357][0-7][0-7]) return 0 ;;
              *) return 1 ;;
            esac
          }
          staging_health_check(){
            staging_failure=""
            if [ ! -e "${'$'}TMP_DIR/etc" ] && [ ! -L "${'$'}TMP_DIR/etc" ]; then
              append_staging_failure "missing etc directory"
            fi
            if [ ! -e "${'$'}TMP_DIR/usr" ] && [ ! -L "${'$'}TMP_DIR/usr" ]; then
              append_staging_failure "missing usr directory"
            fi

            if [ ! -e "${'$'}TMP_DIR/etc/os-release" ] && [ ! -L "${'$'}TMP_DIR/etc/os-release" ]; then
              append_staging_failure "missing etc/os-release"
            elif ! grep -Fx 'VERSION_ID="26.04"' "${'$'}TMP_DIR/etc/os-release" >/dev/null 2>&1; then
              append_staging_failure "VERSION_ID mismatch or unreadable etc/os-release"
            fi
            if [ -e "${'$'}TMP_DIR/etc/os-release" ] || [ -L "${'$'}TMP_DIR/etc/os-release" ]; then
              if ! grep -Fx 'VERSION_CODENAME=${manifest.codename}' "${'$'}TMP_DIR/etc/os-release" >/dev/null 2>&1; then
                append_staging_failure "VERSION_CODENAME mismatch or unreadable etc/os-release"
              fi
            fi

            if [ ! -e "${'$'}TMP_DIR/bin/bash" ] && [ ! -L "${'$'}TMP_DIR/bin/bash" ]; then
              append_staging_failure "missing bin/bash"
            elif ! has_owner_execute_bit "${'$'}TMP_DIR/bin/bash"; then
              append_staging_failure "bin/bash is not executable"
            fi
            if [ ! -e "${'$'}TMP_DIR/usr/bin/env" ] && [ ! -L "${'$'}TMP_DIR/usr/bin/env" ]; then
              append_staging_failure "missing usr/bin/env"
            elif ! has_owner_execute_bit "${'$'}TMP_DIR/usr/bin/env"; then
              append_staging_failure "usr/bin/env is not executable"
            fi

            if [ -n "${'$'}staging_failure" ]; then
              progress_echo "Ubuntu rootfs staging health check failed"
              printf 'Ubuntu rootfs staging failure(s): %s\n' "${'$'}staging_failure"
              report_staging_path 'root' "${'$'}TMP_DIR"
              report_staging_path 'bin' "${'$'}TMP_DIR/bin"
              report_staging_path 'bin/bash' "${'$'}TMP_DIR/bin/bash"
              report_staging_path 'etc' "${'$'}TMP_DIR/etc"
              report_staging_path 'etc/os-release' "${'$'}TMP_DIR/etc/os-release"
              report_staging_path 'usr/lib/os-release' "${'$'}TMP_DIR/usr/lib/os-release"
              report_staging_path 'usr' "${'$'}TMP_DIR/usr"
              report_staging_path 'usr/bin' "${'$'}TMP_DIR/usr/bin"
              report_staging_path 'usr/bin/bash' "${'$'}TMP_DIR/usr/bin/bash"
              report_staging_path 'usr/bin/env' "${'$'}TMP_DIR/usr/bin/env"
              report_staging_path 'usr/bin/gnuenv' "${'$'}TMP_DIR/usr/bin/gnuenv"
              printf 'Ubuntu staging root entries:\n'
              if ! "${'$'}BIN/busybox" ls -la "${'$'}TMP_DIR" 2>/dev/null; then
                printf 'Ubuntu staging root entries: unavailable\n'
              fi
              if [ -r "${'$'}TMP_DIR/etc/os-release" ]; then
                printf 'Ubuntu staging os-release preview:\n'
                "${'$'}BIN/busybox" sed -n '1,12p' "${'$'}TMP_DIR/etc/os-release" 2>/dev/null
              fi
              return 1
            fi
            return 0
          }

          validate_rootfs_identity(){
            identity_file="${'$'}1"
            [ ! -L "${'$'}identity_file" ] && [ -f "${'$'}identity_file" ] || return 1
            grep -Fx 'ok' "${'$'}identity_file" >/dev/null 2>&1 || return 1
            grep -Fx 'schema=kiyori.rootfs.manifest.v1' "${'$'}identity_file" >/dev/null 2>&1 || return 1
            grep -Fx 'distribution=ubuntu' "${'$'}identity_file" >/dev/null 2>&1 || return 1
            grep -Fx 'release=${manifest.release}' "${'$'}identity_file" >/dev/null 2>&1 || return 1
            grep -Fx 'codename=${manifest.codename}' "${'$'}identity_file" >/dev/null 2>&1 || return 1
            grep -Fx 'architecture=${manifest.architecture}' "${'$'}identity_file" >/dev/null 2>&1 || return 1
            grep -Fx 'asset-sha256=${manifest.assetSha256}' "${'$'}identity_file" >/dev/null 2>&1 || return 1
          }

          write_rootfs_identity(){
            identity_file="${'$'}1"
            identity_tmp="${'$'}{identity_file}.tmp-${'$'}${'$'}"
            [ ! -L "${'$'}identity_file" ] || return 1
            if ! {
              printf '%s\n' 'ok'
              printf '%s\n' 'schema=kiyori.rootfs.manifest.v1'
              printf '%s\n' 'distribution=ubuntu'
              printf '%s\n' 'release=${manifest.release}'
              printf '%s\n' 'codename=${manifest.codename}'
              printf '%s\n' 'architecture=${manifest.architecture}'
              printf '%s\n' 'asset-sha256=${manifest.assetSha256}'
            } > "${'$'}identity_tmp"; then
              rm -f "${'$'}identity_tmp" 2>/dev/null || true
              return 1
            fi
            if ! chmod 0644 "${'$'}identity_tmp" 2>/dev/null; then
              rm -f "${'$'}identity_tmp" 2>/dev/null || true
              return 1
            fi
            if ! mv -f "${'$'}identity_tmp" "${'$'}identity_file" 2>/dev/null; then
              rm -f "${'$'}identity_tmp" 2>/dev/null || true
              return 1
            fi
            validate_rootfs_identity "${'$'}identity_file"
          }

          migrate_legacy_marker(){
            if [ ! -e "${'$'}LEGACY_OK_FILE" ] && [ ! -L "${'$'}LEGACY_OK_FILE" ]; then
              return 0
            fi
            if [ -L "${'$'}LEGACY_OK_FILE" ] || [ ! -f "${'$'}LEGACY_OK_FILE" ]; then
              progress_echo "Ubuntu installation marker migration rejected: legacy marker is not a regular file"
              return 1
            fi
            if ! validate_rootfs_identity "${'$'}LEGACY_OK_FILE"; then
              progress_echo "Ubuntu installation marker migration rejected: legacy marker is invalid"
              return 1
            fi
            if [ -e "${'$'}OK_FILE" ] || [ -L "${'$'}OK_FILE" ]; then
              if ! validate_rootfs_identity "${'$'}OK_FILE"; then
                progress_echo "Ubuntu installation marker migration rejected: active marker conflicts"
                return 1
              fi
            elif ! write_rootfs_identity "${'$'}OK_FILE"; then
              progress_echo "Ubuntu installation marker migration failed while writing the active marker"
              return 1
            fi
            if ! validate_rootfs_identity "${'$'}OK_FILE"; then
              progress_echo "Ubuntu installation marker migration failed verification"
              return 1
            fi
            if ! rm -f "${'$'}LEGACY_OK_FILE" 2>/dev/null; then
              progress_echo "Ubuntu installation marker migration failed while removing the legacy marker"
              return 1
            fi
            if [ -e "${'$'}LEGACY_OK_FILE" ] || [ -L "${'$'}LEGACY_OK_FILE" ]; then
              progress_echo "Ubuntu installation marker migration failed: legacy marker remains"
              return 1
            fi
            progress_echo "Ubuntu installation marker migrated"
            return 0
          }

          rootfs_is_current(){
            [ ! -e "${'$'}LEGACY_OK_FILE" ] && [ ! -L "${'$'}LEGACY_OK_FILE" ] || return 1
            validate_rootfs_identity "${'$'}OK_FILE" || return 1
            grep -Fx 'schema=kiyori.rootfs.manifest.v1' "${'$'}MANIFEST_FILE" >/dev/null 2>&1 || return 1
            grep -Fx 'distribution=ubuntu' "${'$'}MANIFEST_FILE" >/dev/null 2>&1 || return 1
            grep -Fx 'release=${manifest.release}' "${'$'}MANIFEST_FILE" >/dev/null 2>&1 || return 1
            grep -Fx 'codename=${manifest.codename}' "${'$'}MANIFEST_FILE" >/dev/null 2>&1 || return 1
            grep -Fx 'architecture=${manifest.architecture}' "${'$'}MANIFEST_FILE" >/dev/null 2>&1 || return 1
            grep -Fx 'asset-sha256=${manifest.assetSha256}' "${'$'}MANIFEST_FILE" >/dev/null 2>&1 || return 1
            grep -Fx 'VERSION_ID="26.04"' "${'$'}UBUNTU_PATH/etc/os-release" >/dev/null 2>&1 || return 1
            grep -Fx 'VERSION_CODENAME=${manifest.codename}' "${'$'}UBUNTU_PATH/etc/os-release" >/dev/null 2>&1 || return 1
            has_owner_execute_bit "${'$'}UBUNTU_PATH/bin/bash" || return 1
            has_owner_execute_bit "${'$'}UBUNTU_PATH/usr/bin/env" || return 1
          }

          migrate_root_data(){
            [ -d "${'$'}UBUNTU_PATH/root" ] || return 0
            mkdir -p "${'$'}TMP_DIR/root" 2>/dev/null
            # Preserve user projects and shell/tool configuration, but do not carry the old Python
            # virtual environment or transient caches across the ABI/runtime boundary.
            for entry in "${'$'}UBUNTU_PATH/root"/* "${'$'}UBUNTU_PATH/root"/.[!.]*; do
              [ -e "${'$'}entry" ] || [ -L "${'$'}entry" ] || continue
              name=${'$'}{entry##*/}
              case "${'$'}name" in
                .cache|.code_runner/py|pyvenv.cfg) continue ;;
              esac
              case "${'$'}name" in
                .code_runner)
                  mkdir -p "${'$'}TMP_DIR/root/.code_runner" 2>/dev/null
                  for child in "${'$'}entry"/* "${'$'}entry"/.[!.]*; do
                    [ -e "${'$'}child" ] || [ -L "${'$'}child" ] || continue
                    child_name=${'$'}{child##*/}
                    [ "${'$'}child_name" = "py" ] && continue
                    cp -a "${'$'}child" "${'$'}TMP_DIR/root/.code_runner/" 2>/dev/null || return 1
                  done
                  ;;
                *) cp -a "${'$'}entry" "${'$'}TMP_DIR/root/" 2>/dev/null || return 1 ;;
              esac
            done
          }

          marker_migration_status=0
          if ! migrate_legacy_marker; then
            marker_migration_status=1
            progress_echo "Ubuntu installation marker migration requires a fresh rootfs"
          fi

          if [ "${'$'}marker_migration_status" -eq 0 ] && rootfs_is_current; then
            VERSION=`cat ${'$'}UBUNTU_PATH/etc/issue.net 2>/dev/null`
            progress_echo "Ubuntu ${'$'}L_INSTALLED -> ${'$'}VERSION"
          else
            progress_echo "Ubuntu ${'$'}L_NOT_INSTALLED, ${'$'}L_INSTALLING..."
            if [ ! -f "${'$'}HOME/${'$'}UBUNTU" ]; then
              cleanup_install
              trap - EXIT INT TERM
              return 1
            fi
            rm -rf "${'$'}TMP_DIR" 2>/dev/null
            mkdir -p "${'$'}TMP_DIR" 2>/dev/null
            progress_echo "Extracting Ubuntu rootfs..."
            # Android app processes commonly use umask 077.  BusyBox tar applies that mask while
            # creating archive entries, which turns the rootfs' 0755 executables into 0700 files
            # and makes the shell executable gate fail for the terminal runtime.  Isolate a
            # standard 022 umask to extraction so archive modes are retained without changing the
            # caller's umask for migration, setup, or interactive commands.
            if ( umask 022; busybox tar xf "${'$'}HOME/${'$'}UBUNTU" -C "${'$'}TMP_DIR"/ ); then
              echo "Extraction complete"
              progress_echo "Ubuntu rootfs extracted; finalizing installation..."
            else
              extraction_status=${'$'}?
              progress_echo "Ubuntu rootfs extraction failed (exit=${'$'}extraction_status)"
              cleanup_install
              trap - EXIT INT TERM
              return 1
            fi

            if ! staging_health_check; then
              cleanup_install
              trap - EXIT INT TERM
              return 1
            fi

            progress_echo "Migrating Ubuntu user data..."
            if ! migrate_root_data; then
              progress_echo "Ubuntu root data migration failed"
              cleanup_install
              trap - EXIT INT TERM
              return 1
            fi
            echo 'export ANDROID_DATA=/home/' >> "${'$'}TMP_DIR/root/.bashrc"
            mkdir -p "${'$'}TMP_DIR/etc" 2>/dev/null
            progress_echo "Preparing Ubuntu network settings..."
            if ! write_default_dns "${'$'}TMP_DIR/etc/resolv.conf"; then
              progress_echo "Ubuntu DNS initialization failed"
              cleanup_install
              trap - EXIT INT TERM
              return 1
            fi
            if ! write_rootfs_identity "${'$'}TMP_DIR/${manifest.installedMarkerFilename}" ||
              ! write_rootfs_identity "${'$'}TMP_DIR/${manifest.manifestFilename}"; then
              progress_echo "Ubuntu rootfs marker write failed"
              cleanup_install
              trap - EXIT INT TERM
              return 1
            fi

            backup_suffix=0
            while [ -e "${'$'}BACKUP_DIR" ] || [ -L "${'$'}BACKUP_DIR" ]; do
              backup_suffix=${'$'}((backup_suffix + 1))
              BACKUP_DIR="${'$'}UBUNTU_PATH.backup.${'$'}${'$'}.${'$'}backup_suffix"
            done

            restore_backup(){
              if [ -e "${'$'}UBUNTU_PATH" ] || [ -L "${'$'}UBUNTU_PATH" ]; then
                rm -rf "${'$'}UBUNTU_PATH" 2>/dev/null || return 1
              fi
              if [ -e "${'$'}BACKUP_DIR" ] || [ -L "${'$'}BACKUP_DIR" ]; then
                mv "${'$'}BACKUP_DIR" "${'$'}UBUNTU_PATH" 2>/dev/null || return 1
              fi
              return 0
            }

            if [ -e "${'$'}UBUNTU_PATH" ] || [ -L "${'$'}UBUNTU_PATH" ]; then
              if ! mv "${'$'}UBUNTU_PATH" "${'$'}BACKUP_DIR" 2>/dev/null; then
                progress_echo "Ubuntu rootfs backup move failed"
                cleanup_install
                trap - EXIT INT TERM
                return 1
              fi
            fi
            if ! mv "${'$'}TMP_DIR" "${'$'}UBUNTU_PATH" 2>/dev/null; then
              progress_echo "Ubuntu rootfs activation move failed"
              if ! restore_backup; then
                progress_echo "Ubuntu rootfs rollback failed; backup retained at ${'$'}BACKUP_DIR"
              fi
              cleanup_install
              trap - EXIT INT TERM
              return 1
            fi
            if ! rootfs_is_current; then
              progress_echo "Ubuntu rootfs health check failed after activation"
              if ! restore_backup; then
                progress_echo "Ubuntu rootfs rollback failed; backup retained at ${'$'}BACKUP_DIR"
              fi
              cleanup_install
              trap - EXIT INT TERM
              return 1
            fi
            progress_echo "Ubuntu rootfs installed"
            rm -f "${'$'}HOME/${'$'}UBUNTU" 2>/dev/null
          fi

          mkdir -p ${'$'}UBUNTU_PATH/etc 2>/dev/null
          write_default_dns "${'$'}UBUNTU_PATH/etc/resolv.conf"

          rm -rf "${'$'}LOCK_DIR" 2>/dev/null
          trap - EXIT INT TERM
        }
        """.trimIndent()

        val configureSources = com.ai.assistance.operit.terminal.utils.localSourceConfigurationCommand(
            aptSource, pipSource, npmSource, manifest.codename,
        )

        val fixPermissions = """
        fix_permissions(){
          # Fix "cannot find name for group ID" warnings
          # Append Android groups to Ubuntu /etc/group
          current_groups=$(id -G)
          permissions_changed=0
          for gid in ${'$'}current_groups; do
            if ! grep -q ":${'$'}gid:" ${'$'}UBUNTU_PATH/etc/group; then
              echo "android_group_${'$'}gid:x:${'$'}gid:" >> ${'$'}UBUNTU_PATH/etc/group
              permissions_changed=1
            fi
          done
          if [ "${'$'}permissions_changed" -eq 1 ]; then
            echo "Fixing permissions..."
            echo "Permissions fixed."
          fi
        }
        """.trimIndent()

        // 读取共享tmp设置
        val sharedTmpEnabled = prefs.getBoolean("shared_tmp_enabled", true)
        val prootBindSetup = PRootMountMapping.buildRuntimeBindMounts(
            homeDir = homeDir,
            appDataDir = operitDataDir,
            packageName = operitPackage,
            chrootEnabled = false
        ).toMutableList().apply {
            if (sharedTmpEnabled) {
                add(4, PRootBindMount(tmpDir, "/dev/shm"))
            }
        }.joinToString(separator = "\n") { mount ->
            "          probe_and_append_bind_arg \"${mount.sourcePath}\" \"${mount.targetPath}\""
        }
        
        val loginUbuntu = """
        login_ubuntu(){
          COMMAND_TO_EXEC="$1"
          if [ -z "${'$'}COMMAND_TO_EXEC" ]; then
            COMMAND_TO_EXEC="/bin/bash -il"
          fi

          # Setup fake sysdata
          if [ "${'$'}USE_CHROOT" != "1" ]; then
            export INSTALLED_ROOTFS_DIR=$(dirname "${'$'}UBUNTU_PATH")
            export distro_name=$(basename "${'$'}UBUNTU_PATH")
            
            if [ -f "${'$'}HOME/setup_fake_sysdata.sh" ]; then
                source "${'$'}HOME/setup_fake_sysdata.sh"
                setup_fake_sysdata
            fi
          fi

          # 使用 proot 直接进入解压的 Ubuntu 根文件系统。
          # - 清理并设置 PATH，避免继承宿主 PATH 造成命令找不到或混用 busybox。
          # - 绑定常见伪文件系统、外部存储与当前宿主应用沙箱，保障交互和软件包管理工作正常。
          # 在 proot 环境中创建必要的目录
          mkdir -p "${'$'}UBUNTU_PATH/storage/emulated" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH$operitUserDataMountPath" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH$operitLegacyDataMountPath" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH$localTmpPath" 2>/dev/null
          mkdir -p "${'$'}UBUNTU_PATH$homeDir" 2>/dev/null

          if [ "${'$'}USE_CHROOT" = "1" ]; then
            CMD_FILE="${'$'}TMPDIR/command_to_exec"
            printf "%s" "${'$'}COMMAND_TO_EXEC" > "${'$'}CMD_FILE" 2>/dev/null || true
            CHROOT_WRAPPER="${'$'}TMPDIR/operit_chroot_wrapper.sh"
            cat > "${'$'}CHROOT_WRAPPER" <<'EOF'
        BIN="$1"
        UBUNTU_PATH="$2"
        CMD_FILE="$3"
        HOME_DIR="$4"
        OPERIT_UID="$5"
        OPERIT_GID="$6"
        OPERIT_GROUPS="$7"
        cleanup_mounts(){
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/dev/pts" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/dev" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/sys" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH/proc" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH${'$'}HOME_DIR" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH$currentUserDataRootPath" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH$operitLegacyDataRootPath" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH$localTmpPath" 2>/dev/null || true
          "${'$'}BIN/busybox" umount "${'$'}UBUNTU_PATH$guestSdcardPath" 2>/dev/null || true
        }
        cleanup_mounts

        "${'$'}BIN/busybox" mkdir -p "${'$'}UBUNTU_PATH/proc" "${'$'}UBUNTU_PATH/sys" "${'$'}UBUNTU_PATH/dev" "${'$'}UBUNTU_PATH/dev/pts" "${'$'}UBUNTU_PATH$guestSdcardPath" "${'$'}UBUNTU_PATH$currentUserDataRootPath" "${'$'}UBUNTU_PATH$operitLegacyDataRootPath" "${'$'}UBUNTU_PATH$localTmpPath" "${'$'}UBUNTU_PATH${'$'}HOME_DIR" 2>/dev/null
        "${'$'}BIN/busybox" mount -t proc proc "${'$'}UBUNTU_PATH/proc" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind /dev "${'$'}UBUNTU_PATH/dev" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind /sys "${'$'}UBUNTU_PATH/sys" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind /dev/pts "${'$'}UBUNTU_PATH/dev/pts" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind $currentEmulatedStoragePath "${'$'}UBUNTU_PATH$guestSdcardPath" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind $currentUserDataRootPath "${'$'}UBUNTU_PATH$currentUserDataRootPath" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind $operitLegacyDataRootPath "${'$'}UBUNTU_PATH$operitLegacyDataRootPath" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind $localTmpPath "${'$'}UBUNTU_PATH$localTmpPath" 2>/dev/null || true
        "${'$'}BIN/busybox" mount --bind "${'$'}HOME_DIR" "${'$'}UBUNTU_PATH${'$'}HOME_DIR" 2>/dev/null || true
        COMMAND_TO_EXEC="$(cat "${'$'}CMD_FILE" 2>/dev/null)"
        "${'$'}BIN/busybox" chroot "${'$'}UBUNTU_PATH" /usr/bin/env -i HOME=/root USER=root LOGNAME=root SHELL=/bin/bash TERM=xterm-256color LANG=en_US.UTF-8 TZ=$hostTimeZone PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin "KIYORI_SSH_BOOTSTRAP=${'$'}{KIYORI_SSH_BOOTSTRAP:-0}" "COMMAND_TO_EXEC=${'$'}COMMAND_TO_EXEC" "OPERIT_UID=${'$'}OPERIT_UID" "OPERIT_GID=${'$'}OPERIT_GID" "OPERIT_GROUPS=${'$'}OPERIT_GROUPS" /bin/bash -lc 'if [ "${'$'}KIYORI_SSH_BOOTSTRAP" != 1 ]; then echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; fi; umask 0002; if [ -n "${'$'}OPERIT_GID" ]; then chown 0:"${'$'}OPERIT_GID" /root 2>/dev/null || true; chmod 2775 /root 2>/dev/null || true; fi; eval "${'$'}COMMAND_TO_EXEC"'
        ret=${'$'}?
        cleanup_mounts
        exit ${'$'}ret
        EOF
            chmod 700 "${'$'}CHROOT_WRAPPER" 2>/dev/null || true
            exec su -c "sh \"${'$'}CHROOT_WRAPPER\" \"${'$'}BIN\" \"${'$'}UBUNTU_PATH\" \"${'$'}CMD_FILE\" \"${homeDir}\" \"${'$'}OPERIT_UID\" \"${'$'}OPERIT_GID\" \"${'$'}OPERIT_GROUPS\""
          fi
          if ! resolve_proot_runtime; then
            return 1
          fi
$prootBindSetup
          if [ "${'$'}USE_CHROOT" != "1" ]; then
            if [ ! -e /proc/stat ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.stat" "/proc/stat"; fi
            if [ ! -e /proc/loadavg ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.loadavg" "/proc/loadavg"; fi
            if [ ! -e /proc/uptime ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.uptime" "/proc/uptime"; fi
            if [ ! -e /proc/version ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.version" "/proc/version"; fi
            if [ ! -e /proc/vmstat ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.vmstat" "/proc/vmstat"; fi
            if [ ! -e /proc/sys/kernel/cap_last_cap ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.sysctl_entry_cap_last_cap" "/proc/sys/kernel/cap_last_cap"; fi
            if [ ! -e /proc/sys/fs/inotify/max_user_watches ]; then probe_and_append_bind_arg "${'$'}UBUNTU_PATH/proc/.sysctl_inotify_max_user_watches" "/proc/sys/fs/inotify/max_user_watches"; fi
          fi
          if [ -n "${'$'}PROOT_BIND_ARGS" ]; then
            set -- ${'$'}PROOT_BIND_ARGS
          else
            set --
          fi
          if [ "${'$'}PROOT_LINK2SYMLINK" = "1" ]; then
            exec_proot_binary \
              -0 \
              -r "${'$'}UBUNTU_PATH" \
              --link2symlink \
              "${'$'}@" \
              -w /root \
              /usr/bin/env -i \
                HOME=/root \
                USER=root \
                LOGNAME=root \
                SHELL=/bin/bash \
                TERM=xterm-256color \
                LANG=en_US.UTF-8 \
                TZ=$hostTimeZone \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                KIYORI_SSH_BOOTSTRAP="${'$'}{KIYORI_SSH_BOOTSTRAP:-0}" \
                COMMAND_TO_EXEC="${'$'}COMMAND_TO_EXEC" \
                /bin/bash -lc 'if [ "${'$'}KIYORI_SSH_BOOTSTRAP" != 1 ]; then echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; fi; eval "${'$'}COMMAND_TO_EXEC"'
          else
            exec_proot_binary \
              -0 \
              -r "${'$'}UBUNTU_PATH" \
              "${'$'}@" \
              -w /root \
              /usr/bin/env -i \
                HOME=/root \
                USER=root \
                LOGNAME=root \
                SHELL=/bin/bash \
                TERM=xterm-256color \
                LANG=en_US.UTF-8 \
                TZ=$hostTimeZone \
                PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
                KIYORI_SSH_BOOTSTRAP="${'$'}{KIYORI_SSH_BOOTSTRAP:-0}" \
                COMMAND_TO_EXEC="${'$'}COMMAND_TO_EXEC" \
                /bin/bash -lc 'if [ "${'$'}KIYORI_SSH_BOOTSTRAP" != 1 ]; then echo LOGIN_SUCCESSFUL; echo TERMINAL_READY; fi; eval "${'$'}COMMAND_TO_EXEC"'
          fi
        }
        """.trimIndent()

        val sshShell = """
        ssh_shell(){
          if ! install_ubuntu; then return 1; fi
          configure_sources || return 1
          fix_permissions
          bump_progress
          # SSH 自己输出远端就绪标记。断线直接结束会话，禁止落入本地 Shell 执行后续安装。
          KIYORI_SSH_BOOTSTRAP=1 login_ubuntu 'exec '"${'$'}SSH_COMMAND"
        }
        """.trimIndent()

        return """
        $common
        $installUbuntu
        $configureSources
        $fixPermissions
        $loginUbuntu
        $sshShell
        clear_lines
        start_shell(){
          if ! install_ubuntu; then
            return 1
          fi
          configure_sources || return 1
          fix_permissions
          sleep 1
          bump_progress
          login_ubuntu
        }
        """.trimIndent()
    }

    fun closeTerminalSession(sessionId: String) {
        closingSessions.add(sessionId)
        // Delegate to provider to ensure underlying process is killed
        coroutineScope.launch {
            try {
                terminalProvider?.closeSession(sessionId)
            } catch (e: Exception) {
                Log.e(TAG, "Error closing session via provider", e)
            }
        }

        activeSessions[sessionId]?.let { session ->
            session.process.destroy()
            activeSessions.remove(sessionId)
            Log.d(TAG, "Closed and removed session: $sessionId")
        }
    }

    private fun handleRegularCommand(command: String, session: com.ai.assistance.operit.terminal.data.TerminalSessionData, commandId: String) {
        session.currentCommandOutput.clear()
        session.currentOutputLineCount = 0
        session.currentCommandExitCode = null
        session.currentCommandCancellationRequested = false

        val newCommandItem = CommandHistoryItem(
            id = commandId,
            prompt = session.currentDirectory,
            command = command,
            output = "",
            isExecuting = true
        )

        // Set the current executing command reference for efficient access
        session.currentExecutingCommand = newCommandItem

        // 发出命令开始执行事件；与正文和完成事件共用 FIFO，避免完成事件抢先到达消费者。
        if (!commandEventDispatcher.offer(CommandExecutionEvent(
                commandId = newCommandItem.id,
                sessionId = session.id,
                outputChunk = "",
                isCompleted = false,
                exitCode = null
            ))) {
            Log.w(TAG, "Dropping command start event after terminal manager shutdown: ${newCommandItem.id}")
        }
    }

    internal suspend fun <T> withEnvironmentMaintenance(waitForCurrent: Boolean = false, block: suspend () -> T): T =
        environmentOperations.maintain(waitForCurrent) {
            // 已阻止新的初始化/创建和隐藏执行，且在途操作已经退出。
            isEnvInitialized = false
            val sessionsToStop = terminalState.value.sessions
            sessionsToStop.forEach { closingSessions.add(it.id) }
            providerMutex.withLock {
                terminalProvider?.disconnect()
                terminalProvider = null
                _activeEnvironmentType.value = null
            }
            check(sshdServerManager.stopServer()) { "Unable to stop local SSH server" }
            sessionsToStop.forEach { session ->
                session.readJob?.cancel()
                session.terminalSession?.process?.let { process ->
                    process.destroy()
                    check(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        "Terminal process did not exit; environment files have not been removed"
                    }
                }
            }
            activeSessions.clear()
            sessionManager.cleanup()
            try { block() } finally { isEnvInitialized = false }
        }

    fun prepareForMaintenance() {
        kotlinx.coroutines.runBlocking { withEnvironmentMaintenance(waitForCurrent = true) { } }
    }

    /** 用户确认后停止现有执行，再让新会话消费已保存配置；连接失败保持失败。 */
    suspend fun applyConnectionSettings() {
        if (sshConfigManager.isEnabled()) {
            checkNotNull(sshConfigManager.getConfig()) { "SSH connection configuration is missing" }
        }
        withEnvironmentMaintenance { }
        createNewSession()
    }

    fun cleanup() {
        prepareForMaintenance()
        commandEventDispatcher.close()
        coroutineScope.cancel()
        Log.d(TAG, "All active sessions cleaned up.")
    }

    suspend fun executeHiddenCommand(
        command: String,
        executorKey: String = "default",
        timeoutMs: Long = 120000L
    ): HiddenExecResult = environmentOperations.run {
        executeHiddenCommandInternal(command, executorKey, timeoutMs)
    }

    private suspend fun executeHiddenCommandInternal(
        command: String,
        executorKey: String,
        timeoutMs: Long,
    ): HiddenExecResult {
        val initialized = initializeEnvironment()
        if (!initialized) {
            return HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.SHELL_START_FAILED,
                error = "Terminal environment initialization failed"
            )
        }

        return getTerminalProvider().executeHiddenCommand(
            command = command,
            executorKey = executorKey,
            timeoutMs = timeoutMs
        )
    }

    /** 页面展示实际运行中的 provider，而不是尚未应用的 SSH 偏好。 */
    internal suspend fun usesSshEnvironment(): Boolean = getTerminalProvider() is SSHTerminalProvider

    /**
     * 获取文件系统提供者
     * 
     * 根据配置返回对应的提供者（本地或SSH）
     * 如果配置了SSH且已连接，返回SSH的文件系统提供者（共享SFTP连接）
     * 否则返回本地文件系统提供者
     */
    fun getFileSystemProvider(): FileSystemProvider = kotlinx.coroutines.runBlocking {
        getTerminalProvider().getFileSystemProvider()
    }
    
    /**
     * 获取SSHD服务器管理器
     * 
     * 用于管理本地SSHD服务器（反向SSH隧道场景）
     */
    fun getSSHDServerManager(): SSHDServerManager = sshdServerManager
}

internal fun isTargetCommandSettled(
    targetCommandId: String,
    currentCommandId: String?,
    currentCommandExecuting: Boolean,
): Boolean = !currentCommandExecuting || currentCommandId != targetCommandId

internal fun isTerminalRuntimeReady(
    initState: SessionInitState,
    writerAvailable: Boolean,
    processAlive: Boolean,
): Boolean = initState == SessionInitState.READY && writerAvailable && processAlive

private fun isSessionRuntimeReady(session: TerminalSessionData): Boolean =
    isTerminalRuntimeReady(
        initState = session.initState,
        writerAvailable = session.sessionWriter != null,
        processAlive = session.terminalSession?.process?.isAlive == true,
    )
