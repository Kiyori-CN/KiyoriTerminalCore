package com.ai.assistance.operit.terminal

import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

open class Pty(
    val process: Process,
    val masterFd: FileDescriptor?,
    private val ptyMaster: Int,
    val stdout: InputStream,
    val stdin: OutputStream,
    private val masterFdOwner: ParcelFileDescriptor? = null
) {
    // 为本地终端提供的便利构造函数
    constructor(process: Process, masterFd: FileDescriptor, ptyMaster: Int) : this(
        process = process,
        masterFd = masterFd,
        ptyMaster = ptyMaster,
        stdout = FileInputStream(masterFd),
        stdin = FileOutputStream(masterFd)
    )

    fun waitFor(): Int {
        return process.waitFor()
    }

    fun destroy() {
        process.destroy()
        try {
            stdout.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing PTY output stream", e)
        }
        try {
            stdin.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing PTY input stream", e)
        }
        try {
            // start() adopts the native master FD; retaining and closing its owner makes
            // descriptor lifetime explicit instead of relying on private-field reflection.
            masterFdOwner?.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing PTY master descriptor", e)
        }
    }

    companion object {
        private const val TAG = "Pty"

        init {
            try {
                System.loadLibrary("pty")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load libpty.so", e)
                // Handle error appropriately, maybe disable PTY functionality
            }
        }

        @Throws(IOException::class)
        fun start(command: Array<String>, environment: Map<String, String>, workingDir: File): Pty {
            val envArray = environment.map { "${it.key}=${it.value}" }.toTypedArray()

            // This will return an array of two integers: { pid, masterFd }
            val processInfo = createSubprocess(command, envArray, workingDir.absolutePath)
            val pid = processInfo[0]
            val masterFdInt = processInfo[1]

            if (pid <= 0 || masterFdInt <= 0) {
                throw IOException("Failed to create subprocess with PTY. pid=$pid, fd=$masterFdInt")
            }

            val descriptorOwner = ParcelFileDescriptor.adoptFd(masterFdInt)
            var stdoutDescriptor: ParcelFileDescriptor? = null
            try {
                // The PTY master is full-duplex. Each stream receives its own duplicate while
                // descriptorOwner retains the original FD used by ioctl operations.
                val duplicatedStdout = ParcelFileDescriptor.dup(descriptorOwner.fileDescriptor)
                stdoutDescriptor = duplicatedStdout
                val stdinDescriptor = ParcelFileDescriptor.dup(descriptorOwner.fileDescriptor)
                val process =
                    PtyProcess(
                        pid = pid,
                        waitForStatus = Companion::waitFor,
                        pollExitStatus = Companion::pollExitStatus,
                        sendSignal = android.os.Process::sendSignal,
                    )
                return Pty(
                    process = process,
                    masterFd = descriptorOwner.fileDescriptor,
                    ptyMaster = masterFdInt,
                    stdout = ParcelFileDescriptor.AutoCloseInputStream(duplicatedStdout),
                    stdin = ParcelFileDescriptor.AutoCloseOutputStream(stdinDescriptor),
                    masterFdOwner = descriptorOwner,
                )
            } catch (e: IOException) {
                try {
                    stdoutDescriptor?.close()
                } catch (closeError: IOException) {
                    e.addSuppressed(closeError)
                }
                try {
                    descriptorOwner.close()
                } catch (closeError: IOException) {
                    e.addSuppressed(closeError)
                }
                throw IOException("Failed to duplicate PTY master descriptor", e)
            }
        }

        private external fun createSubprocess(cmdArray: Array<String>, envArray: Array<String>, workingDir: String): IntArray

        private external fun waitFor(pid: Int): Int

        private external fun pollExitStatus(pid: Int): Int
        
        /**
         * 获取终端标志位
         * bit 0: ICANON - canonical mode (line-buffered input)
         * bit 1: ECHO - echo input characters
         * bit 2: ISIG - generate signals for special characters
         * bit 3: IEXTEN - enable extended input processing
         */
        private external fun getTerminalFlags(fd: Int): Int
        
        /**
         * 获取可读字节数（用于检测是否有输出等待读取）
         */
        private external fun getAvailableBytes(fd: Int): Int
    }
    
    /**
     * 获取 PTY 模式信息
     */
    open fun getPtyMode(): PtyMode {
        if (ptyMaster <= 0) {
            // SSH 等远程终端返回默认模式
            return PtyMode(
                isCanonicalMode = true,
                isEchoEnabled = true,
                isSignalEnabled = true,
                isExtendedEnabled = true,
                availableBytes = 0
            )
        }
        
        val flags = Companion.getTerminalFlags(ptyMaster)
        val availableBytes = Companion.getAvailableBytes(ptyMaster)
        
        return PtyMode(
            isCanonicalMode = (flags and 0x01) != 0,
            isEchoEnabled = (flags and 0x02) != 0,
            isSignalEnabled = (flags and 0x04) != 0,
            isExtendedEnabled = (flags and 0x08) != 0,
            availableBytes = availableBytes
        )
    }
    
    /**
     * 设置 PTY 窗口大小
     * @param rows 行数
     * @param cols 列数
     * @return true 表示成功，false 表示失败
     */
    open fun setWindowSize(rows: Int, cols: Int): Boolean {
        if (ptyMaster <= 0) {
            // SSH 等远程终端由子类实现
            return false
        }
        
        val result = setPtyWindowSize(ptyMaster, rows, cols)
        if (result == 0) {
            Log.d("Pty", "PTY window size updated to ${rows}x${cols}")
            return true
        } else {
            Log.e("Pty", "Failed to set PTY window size to ${rows}x${cols}")
            return false
        }
    }
    
    private external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int): Int
}

/**
 * PTY 模式信息
 * 用于检测终端是否处于交互式输入状态
 */
data class PtyMode(
    val isCanonicalMode: Boolean,  // true = 行缓冲模式（正常命令），false = 字符模式（交互式输入）
    val isEchoEnabled: Boolean,     // 是否回显输入
    val isSignalEnabled: Boolean,   // 是否启用信号处理
    val isExtendedEnabled: Boolean, // 是否启用扩展处理
    val availableBytes: Int         // 可读字节数
) {
    /**
     * 判断是否正在等待交互式输入
     * 
     * 两种场景：
     * 1. 非规范模式（Node.js REPL, Python REPL）：禁用 ICANON，字符模式输入
     * 2. 规范模式但等待输入（apt upgrade, sudo）：保持 ICANON，但输出已停止
     */
    fun isWaitingForInput(): Boolean {
        // 场景 1: 非规范模式 = REPL（Node/Python）
        if (!isCanonicalMode && availableBytes == 0) {
            return true
        }
        
        // 场景 2: 规范模式但输出已停止 = 等待确认（apt/sudo）
        // 条件：缓冲区为空（输出已停止）
        if (availableBytes == 0) {
            // 需要由上层结合命令状态判断（是否有命令正在执行）
            return true
        }
        
        return false
    }
}
internal const val PTY_PROCESS_STILL_RUNNING: Int = Int.MIN_VALUE
internal const val PTY_PROCESS_WAIT_FAILED: Int = Int.MIN_VALUE + 1

/**
 * Process contract for a child created by forkpty().
 *
 * pollExitStatus() uses waitpid(WNOHANG), so exitValue() can distinguish a running
 * child from an exited zombie and return the real cached status without blocking.
 */
internal class PtyProcess(
    private val pid: Int,
    private val waitForStatus: (Int) -> Int,
    private val pollExitStatus: (Int) -> Int,
    private val sendSignal: (Int, Int) -> Unit,
) : Process() {
    private val exitLock = Any()
    private var cachedExitCode: Int? = null

    override fun destroy() {
        sendSignalAndLog(1, "SIGHUP")
        sendSignalAndLog(9, "SIGKILL")
    }

    private fun sendSignalAndLog(signal: Int, name: String) {
        try {
            sendSignal(pid, signal)
        } catch (e: Exception) {
            Log.e("Pty", "Failed to send $name to PTY process $pid", e)
        }
    }

    override fun exitValue(): Int =
        synchronized(exitLock) {
            cachedExitCode?.let { return@synchronized it }
            when (val status = pollExitStatus(pid)) {
                PTY_PROCESS_STILL_RUNNING ->
                    throw IllegalThreadStateException("PTY process $pid has not exited")
                PTY_PROCESS_WAIT_FAILED ->
                    throw IllegalStateException("Unable to read exit status for PTY process $pid")
                else -> status.also { cachedExitCode = it }
            }
        }

    override fun getErrorStream(): InputStream? = null

    override fun getInputStream(): InputStream? = null

    override fun getOutputStream(): OutputStream? = null

    override fun waitFor(): Int =
        synchronized(exitLock) {
            cachedExitCode?.let { return@synchronized it }
            val status = waitForStatus(pid)
            check(status != PTY_PROCESS_WAIT_FAILED) {
                "Unable to wait for PTY process $pid"
            }
            check(status != PTY_PROCESS_STILL_RUNNING) {
                "Blocking wait unexpectedly reported PTY process $pid as running"
            }
            status.also { cachedExitCode = it }
        }
}
