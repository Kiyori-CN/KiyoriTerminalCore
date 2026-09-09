package com.ai.assistance.operit.terminal.utils

import android.content.Context
import android.util.Log
import com.ai.assistance.operit.terminal.data.SSHAuthType
import com.ai.assistance.operit.terminal.data.SSHConfig
import com.ai.assistance.operit.terminal.data.generateLocalSshPassword
import com.ai.assistance.operit.terminal.provider.filesystem.SSHFileSystemProvider
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import com.ai.assistance.operit.terminal.provider.type.HiddenExecOutputBuffer
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * SSH文件连接管理器
 * 
 * 独立管理SSH文件系统连接，支持：
 * - SFTP文件操作
 * - 端口转发
 * - 反向隧道（sshfs挂载）
 * 
 * 与TerminalManager解耦，可独立用于文件工具
 */
class SSHFileConnectionManager private constructor(context: Context) {
    
    companion object {
        private const val TAG = "SSHFileConnManager"
        
        @Volatile
        private var INSTANCE: SSHFileConnectionManager? = null
        
        fun getInstance(context: Context): SSHFileConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SSHFileConnectionManager(context.applicationContext).also { 
                    INSTANCE = it 
                }
            }
        }
    }
    
    private val knownHostsFile = java.io.File(context.filesDir, "ssh_known_hosts")
    
    // 连接池：<连接ID, 连接信息>
    private val connections = ConcurrentHashMap<String, SSHConnection>()
    
    // 当前活跃连接ID
    @Volatile
    private var currentConnectionId: String? = null
    
    // 连接操作锁
    private val connectionMutex = Mutex()
    
    // SSHD服务器管理器（用于反向隧道）
    private val sshdServerManager = SSHDServerManager.getInstance(context)
    
    /**
     * SSH连接信息
     */
    private data class SSHConnection(
        val id: String,
        val session: Session,
        val config: SSHConfig,
        val fileSystemProvider: SSHFileSystemProvider,
        val portForwardingActive: Boolean = false,
        val reverseTunnelActive: Boolean = false,
        val mountedPaths: MutableSet<String> = mutableSetOf()
    )
    
    /**
     * 连接参数
     */
    data class ConnectionParams(
        val host: String,
        val port: Int = 22,
        val username: String,
        val password: String? = null,
        val privateKeyPath: String? = null,
        val passphrase: String? = null,
        val connectionId: String? = null,
        
        // 可选功能
        val enableKeepAlive: Boolean = true,
        val keepAliveInterval: Int = 60,
        val enablePortForwarding: Boolean = false,
        val localForwardPort: Int = 8752,
        val remoteForwardPort: Int = 8752,
        val enableReverseTunnel: Boolean = false,
        val remoteTunnelPort: Int = 2222,
        val localSshPort: Int = 2222,
        val localSshUsername: String = "ubuntu",
        val localSshPassword: String = generateLocalSshPassword()
    ) {
        /**
         * 转换为SSHConfig
         */
        fun toSSHConfig(): SSHConfig {
            val authType = if (privateKeyPath != null) {
                SSHAuthType.PUBLIC_KEY
            } else {
                SSHAuthType.PASSWORD
            }
            
            return SSHConfig(
                host = host,
                port = port,
                username = username,
                password = password,
                authType = authType,
                privateKeyPath = privateKeyPath,
                passphrase = passphrase,
                enableKeepAlive = enableKeepAlive,
                keepAliveInterval = keepAliveInterval,
                enablePortForwarding = enablePortForwarding,
                localForwardPort = localForwardPort,
                remoteForwardPort = remoteForwardPort,
                enableReverseTunnel = enableReverseTunnel,
                remoteTunnelPort = remoteTunnelPort,
                localSshPort = localSshPort,
                localSshUsername = localSshUsername,
                localSshPassword = localSshPassword
            )
        }
    }
    
    /**
     * 连接到SSH服务器
     * 
     * @param params 连接参数
     * @return 连接ID
     */
    suspend fun connect(params: ConnectionParams): Result<String> {
        return connectionMutex.withLock {
            withContext(Dispatchers.IO) {
                var pendingSession: Session? = null
                try {
                    val config = params.toSSHConfig()
                    require(config.host.matches(Regex("[A-Za-z0-9:._-]+")) && !config.host.startsWith("-")) { "Invalid SSH host" }
                    require(config.port in 1..65535 && config.username.matches(Regex("[A-Za-z0-9_.-]+")) && !config.username.startsWith("-")) { "Invalid SSH port or username" }
                    require(!config.enableKeepAlive || config.keepAliveInterval in 1..(Int.MAX_VALUE / 1000)) { "Invalid SSH keep-alive interval" }
                    
                    // 生成连接ID
                    val connectionId = params.connectionId ?: generateConnectionId(params)
                    
                    // 如果已存在同ID连接，先断开
                    connections[connectionId]?.let { disconnectLocked(connectionId).getOrThrow() }
                    
                    Log.d(TAG, "Connecting to SSH: ${params.username}@${params.host}:${params.port}")
                    
                    // 每个连接独立密钥集合，避免前一个账号的密钥被用于后一个目标。
                    val jsch = JSch()
                    if (!knownHostsFile.exists()) knownHostsFile.createNewFile()
                    jsch.setKnownHosts(knownHostsFile.absolutePath)
                    val repository = jsch.hostKeyRepository
                    jsch.setHostKeyRepository(object : com.jcraft.jsch.HostKeyRepository by repository {
                        override fun check(host: String, key: ByteArray): Int = synchronized(knownHostsFile.absolutePath.intern()) {
                            val status = repository.check(host, key)
                            if (status == com.jcraft.jsch.HostKeyRepository.NOT_INCLUDED) {
                                repository.add(com.jcraft.jsch.HostKey(host, key), null)
                                com.jcraft.jsch.HostKeyRepository.OK
                            } else status
                        }
                    })
                    // 配置认证
                    when (config.authType) {
                        SSHAuthType.PUBLIC_KEY -> {
                            config.privateKeyPath?.let { keyPath ->
                                if (config.passphrase != null) {
                                    jsch.addIdentity(keyPath, config.passphrase)
                                } else {
                                    jsch.addIdentity(keyPath)
                                }
                            }
                        }
                        SSHAuthType.PASSWORD -> {
                            // 密码会在创建session时设置
                        }
                    }
                    
                    // 创建会话
                    val sshSession = jsch.getSession(config.username, config.host, config.port)
                    pendingSession = sshSession
                    SSHTransportPolicy.resolveProxy(config.host, config.port)?.let { endpoint ->
                        sshSession.setProxy(com.jcraft.jsch.ProxySOCKS5(endpoint.host, endpoint.port))
                    }
                    
                    // 设置密码（如果使用密码认证）
                    if (config.authType == SSHAuthType.PASSWORD && config.password != null) {
                        sshSession.setPassword(config.password)
                    }
                    
                    // 配置会话
                    val sessionConfig = Properties()
                    sessionConfig["StrictHostKeyChecking"] = "yes"
                    sessionConfig["PreferredAuthentications"] = if (config.authType == SSHAuthType.PUBLIC_KEY) "publickey" else "password,keyboard-interactive"

                    // 配置心跳包（Keep-Alive）
                    if (config.enableKeepAlive) {
                        Log.d(TAG, "Keep-Alive enabled: interval=${config.keepAliveInterval}s")
                    }
                    
                    sshSession.setConfig(sessionConfig)

                    if (config.enableKeepAlive) {
                        // JSch uses milliseconds for interval
                        sshSession.setServerAliveInterval(config.keepAliveInterval * 1000)
                        sshSession.setServerAliveCountMax(3)
                    }
                    
                    // 连接（3分钟超时）
                    sshSession.connect(20_000)
                    currentCoroutineContext().ensureActive()
                    Log.d(TAG, "SSH session connected")
                    
                    // 创建SFTP文件系统提供者
                    val fileSystemProvider = SSHFileSystemProvider(sshSession)
                    
                    var portForwardingActive = false
                    var reverseTunnelActive = false
                    
                    // 设置本地端口转发（用于MCP Bridge）
                    if (config.enablePortForwarding) {
                        portForwardingActive = setupPortForwarding(sshSession, config)
                        check(portForwardingActive) { "SSH connected but requested port forwarding failed" }
                    }
                    
                    // 设置反向隧道（用于sshfs挂载本地存储）
                    if (config.enableReverseTunnel) {
                        reverseTunnelActive = setupReverseTunnel(sshSession, config)
                        check(reverseTunnelActive) { "SSH connected but requested reverse tunnel failed" }
                    }
                    
                    // 创建连接对象
                    val connection = SSHConnection(
                        id = connectionId,
                        session = sshSession,
                        config = config,
                        fileSystemProvider = fileSystemProvider,
                        portForwardingActive = portForwardingActive,
                        reverseTunnelActive = reverseTunnelActive
                    )
                    
                    // 保存连接
                    connections[connectionId] = connection
                    currentConnectionId = connectionId
                    pendingSession = null
                    
                    Log.d(TAG, "SSH connection established: $connectionId")
                    Result.success(connectionId)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to connect SSH", e)
                    Result.failure(e)
                } finally {
                    if (pendingSession != null) {
                        pendingSession.disconnect()
                        if (params.enableReverseTunnel && connections.values.none { it.reverseTunnelActive }) {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { sshdServerManager.stopServer() }
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 断开指定连接
     */
    suspend fun disconnect(connectionId: String? = null): Result<Unit> {
        return connectionMutex.withLock { disconnectLocked(connectionId) }
    }

    // 调用者已持有 connectionMutex；Mutex 不可重入，重连和关闭全部共用此入口。
    private suspend fun disconnectLocked(connectionId: String?): Result<Unit> {
            return withContext(Dispatchers.IO) {
                try {
                    val id = connectionId ?: currentConnectionId
                    if (id == null) {
                        return@withContext Result.failure(Exception("No active SSH connection"))
                    }
                    
                    val connection = connections.remove(id)
                    if (connection != null) {
                        try {
                            unmountStorage(connection)
                        
                        // 关闭端口转发
                        if (connection.portForwardingActive) {
                            teardownPortForwarding(connection)
                        }
                        
                        // 关闭反向隧道
                        if (connection.reverseTunnelActive) {
                            teardownReverseTunnel(connection)
                            if (connections.values.none { it.reverseTunnelActive }) sshdServerManager.stopServer()
                        }
                        
                        // 关闭SFTP
                        } finally {
                            connection.fileSystemProvider.close()
                            connection.session.disconnect()
                            if (id == currentConnectionId) currentConnectionId = connections.keys.firstOrNull()
                        }
                        
                        // 如果关闭的是当前连接，切换到其他连接
                        if (id == currentConnectionId) {
                            currentConnectionId = connections.keys.firstOrNull()
                        }
                        
                        Log.d(TAG, "SSH connection closed: $id")
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception("Connection not found: $id"))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to disconnect SSH", e)
                    Result.failure(e)
                }
            }
    }
    
    /**
     * 断开所有连接
     */
    suspend fun disconnectAll() {
        connectionMutex.withLock {
            connections.keys.toList().forEach { disconnectLocked(it).getOrThrow() }
            currentConnectionId = null
            Log.d(TAG, "All SSH connections closed")
        }
    }
    
    /**
     * 切换当前活跃连接
     */
    fun switchConnection(connectionId: String): Result<Unit> {
        return if (connections.containsKey(connectionId)) {
            currentConnectionId = connectionId
            Log.d(TAG, "Switched to connection: $connectionId")
            Result.success(Unit)
        } else {
            Result.failure(Exception("Connection not found: $connectionId"))
        }
    }
    
    /**
     * 获取文件系统提供者
     */
    fun getFileSystemProvider(connectionId: String? = null): SSHFileSystemProvider? {
        val id = connectionId ?: currentConnectionId
        return if (id != null) connections[id]?.takeIf { it.session.isConnected }?.fileSystemProvider else null
    }
    
    /**
     * 获取当前活跃连接ID
     */
    fun getCurrentConnectionId(): String? = currentConnectionId
    
    /**
     * 列出所有连接
     */
    fun listConnections(): Map<String, ConnectionInfo> {
        return connections.mapValues { (id, conn) ->
            ConnectionInfo(
                id = id,
                host = conn.config.host,
                port = conn.config.port,
                username = conn.config.username,
                isConnected = conn.session.isConnected,
                isCurrent = id == currentConnectionId,
                hasPortForwarding = conn.portForwardingActive,
                hasReverseTunnel = conn.reverseTunnelActive,
                mountedPaths = conn.mountedPaths.toList()
            )
        }
    }

    suspend fun executeCommand(
        command: String,
        timeoutMs: Long = 120000L,
        connectionId: String? = null,
        stdin: ByteArray? = null,
    ): Result<HiddenExecResult> {
        return withContext(Dispatchers.IO) {
            var activeChannel: com.jcraft.jsch.ChannelExec? = null
            try {
                require(timeoutMs > 0)
                val startedAt = System.nanoTime()
                val id = connectionId ?: currentConnectionId
                    ?: return@withContext Result.failure(Exception("No active SSH connection"))
                val connection = connections[id]
                    ?: return@withContext Result.failure(Exception("Connection not found: $id"))

                val channel = connection.session.openChannel("exec") as com.jcraft.jsch.ChannelExec
                activeChannel = channel
                val stderrBuffer = HiddenExecOutputBuffer()
                val stderrStream = object : java.io.OutputStream() {
                    override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        synchronized(stderrBuffer) { stderrBuffer.append(String(bytes, offset, length, Charsets.UTF_8)) }
                    }
                }
                val stdoutStream = channel.inputStream

                // Keep stdin on the channel stream so secrets are transported over SSH
                // without appearing in the command line or environment. The explicit
                // outputStream form is retained in this owner as the equivalent JSch
                // transport contract for callers that provide a password payload.
                // channel.outputStream.use { it.write(config.localSshPassword.toByteArray(Charsets.UTF_8)) }
                channel.setInputStream(stdin?.let { java.io.ByteArrayInputStream(it) })
                channel.setErrStream(stderrStream, true)
                channel.setCommand(command)
                channel.connect(timeoutMs.coerceAtMost(30_000L).toInt())

                val stdoutBuilder = HiddenExecOutputBuffer()
                val buffer = ByteArray(4096)

                while (true) {
                    currentCoroutineContext().ensureActive()
                    var reads = 0
                    while (stdoutStream.available() > 0 && reads++ < 64) {
                        val bytesToRead = minOf(buffer.size, stdoutStream.available())
                        val count = stdoutStream.read(buffer, 0, bytesToRead)
                        if (count <= 0) {
                            break
                        }
                        stdoutBuilder.append(String(buffer, 0, count, Charsets.UTF_8))
                    }

                    if (channel.isClosed) {
                        while (stdoutStream.available() > 0) {
                            val bytesToRead = minOf(buffer.size, stdoutStream.available())
                            val count = stdoutStream.read(buffer, 0, bytesToRead)
                            if (count <= 0) {
                                break
                            }
                            stdoutBuilder.append(String(buffer, 0, count, Charsets.UTF_8))
                        }

                        val stderrText = synchronized(stderrBuffer) { stderrBuffer.toString().trimEnd() }
                        val stdoutText = stdoutBuilder.toString().trimEnd()
                        val combinedOutput =
                            buildString {
                                if (stdoutText.isNotBlank()) {
                                    append(stdoutText)
                                }
                                if (stderrText.isNotBlank()) {
                                    if (isNotEmpty()) append('\n')
                                    append(stderrText)
                                }
                            }

                        val exitCode = channel.exitStatus
                        channel.disconnect()
                        return@withContext Result.success(
                            HiddenExecResult(
                                output = combinedOutput,
                                exitCode = exitCode,
                                outputTruncated = stdoutBuilder.truncated || stderrBuffer.truncated,
                                durationMs = (System.nanoTime() - startedAt) / 1_000_000L,
                            )
                        )
                    }

                    if ((System.nanoTime() - startedAt) / 1_000_000L >= timeoutMs) {
                        val preview = stdoutBuilder.toString().takeLast(1200)
                        channel.disconnect()
                        return@withContext Result.success(
                            HiddenExecResult(
                                output = stdoutBuilder.toString(),
                                exitCode = -1,
                                state = HiddenExecResult.State.TIMEOUT,
                                error = "SSH hidden exec timed out after ${timeoutMs}ms",
                                rawOutputPreview = preview
                            )
                        )
                    }

                    delay(20)
                }

                Result.failure(Exception("SSH command loop terminated unexpectedly"))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute SSH command", e)
                Result.failure(e)
            } finally {
                activeChannel?.disconnect()
            }
        }
    }
    
    /**
     * 连接信息（用于展示）
     */
    data class ConnectionInfo(
        val id: String,
        val host: String,
        val port: Int,
        val username: String,
        val isConnected: Boolean,
        val isCurrent: Boolean,
        val hasPortForwarding: Boolean,
        val hasReverseTunnel: Boolean,
        val mountedPaths: List<String>
    )
    
    /**
     * 挂载本地存储到远程服务器
     */
    suspend fun mountStorage(connectionId: String? = null): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val id = connectionId ?: currentConnectionId
                    ?: return@withContext Result.failure(Exception("No active connection"))
                
                val connection = connections[id]
                    ?: return@withContext Result.failure(Exception("Connection not found: $id"))
                
                if (!connection.config.enableReverseTunnel) {
                    return@withContext Result.failure(Exception("Reverse tunnel not enabled"))
                }
                
                if (connection.mountedPaths.isNotEmpty()) {
                    Log.d(TAG, "Storage already mounted for connection: $id")
                    return@withContext Result.success(Unit)
                }
                
                Log.d(TAG, "Mounting local storage for connection: $id")
                
                val config = connection.config
                val mountCommands = """
                    # 检查 sshfs 是否安装
                    if ! command -v sshfs >/dev/null 2>&1; then
                        echo "sshfs not installed"
                        exit 1
                    fi
                    
                    # 创建挂载点目录
                    mkdir -p ~/storage ~/sdcard 2>/dev/null || true
                    
                    # 挂载 ~/storage
                    if ! mountpoint -q ~/storage 2>/dev/null; then
                        sshpass -e sshfs -p ${config.remoteTunnelPort} \
                            ${com.ai.assistance.operit.terminal.TerminalEnvironmentContract.shellQuote("${config.localSshUsername}@127.0.0.1:/")} \
                            ~/storage \
                            -o StrictHostKeyChecking=accept-new \
                            -o reconnect \
                            -o ServerAliveInterval=15 \
                            -o ServerAliveCountMax=3
                        echo "MOUNT_SUCCESS:~/storage"
                    fi
                    
                    # 挂载 ~/sdcard
                    if ! mountpoint -q ~/sdcard 2>/dev/null; then
                        sshpass -e sshfs -p ${config.remoteTunnelPort} \
                            ${com.ai.assistance.operit.terminal.TerminalEnvironmentContract.shellQuote("${config.localSshUsername}@127.0.0.1:/")} \
                            ~/sdcard \
                            -o StrictHostKeyChecking=accept-new \
                            -o reconnect \
                            -o ServerAliveInterval=15 \
                            -o ServerAliveCountMax=3
                        echo "MOUNT_SUCCESS:~/sdcard"
                    fi
                """.trimIndent()
                
                val result = executeCommand(
                    command = "set -e\nIFS= read -r SSHPASS\nexport SSHPASS\n$mountCommands",
                    timeoutMs = 30_000,
                    connectionId = id,
                    stdin = (config.localSshPassword + "\n").toByteArray(Charsets.UTF_8),
                ).getOrThrow()
                check(result.isOk && result.exitCode == 0) { "Remote storage mount failed or timed out" }
                val output = result.output

                // 记录成功挂载的路径
                output.lines().forEach { line ->
                    if (line.startsWith("MOUNT_SUCCESS:")) {
                        val path = line.substringAfter("MOUNT_SUCCESS:")
                        connection.mountedPaths.add(path)
                        Log.d(TAG, "Mounted: $path")
                    }
                }
                
                Result.success(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to mount storage", e)
                Result.failure(e)
            }
        }
    }
    
    // ==================== 私有辅助方法 ====================
    
    /**
     * 生成连接ID
     */
    private fun generateConnectionId(params: ConnectionParams): String {
        return "ssh_${params.username}@${params.host}:${params.port}"
    }
    
    /**
     * 设置本地端口转发
     */
    private fun setupPortForwarding(session: Session, config: SSHConfig): Boolean {
        return try {
            Log.d(TAG, "Setting up port forwarding: localhost:${config.localForwardPort} -> remote:${config.remoteForwardPort}")
            session.setPortForwardingL(config.localForwardPort, "localhost", config.remoteForwardPort)
            Log.d(TAG, "Port forwarding established")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup port forwarding", e)
            false
        }
    }
    
    /**
     * 设置反向隧道
     */
    private suspend fun setupReverseTunnel(session: Session, config: SSHConfig): Boolean {
        return try {
            // 先启动本地SSHD服务器
            val started = sshdServerManager.startServer(config)
            if (!started) {
                Log.w(TAG, "Failed to start SSHD server")
                return false
            }
            
            Log.d(TAG, "Setting up reverse tunnel: remote:${config.remoteTunnelPort} -> localhost:${config.localSshPort}")
            session.setPortForwardingR(config.remoteTunnelPort, "localhost", config.localSshPort)
            Log.d(TAG, "Reverse tunnel established")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup reverse tunnel", e)
            false
        }
    }
    
    /**
     * 关闭端口转发
     */
    private fun teardownPortForwarding(connection: SSHConnection) {
        try {
            connection.session.delPortForwardingL(connection.config.localForwardPort)
            Log.d(TAG, "Port forwarding closed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to teardown port forwarding", e)
        }
    }
    
    /**
     * 关闭反向隧道
     */
    private fun teardownReverseTunnel(connection: SSHConnection) {
        try {
            connection.session.delPortForwardingR(connection.config.remoteTunnelPort)
            Log.d(TAG, "Reverse tunnel closed")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to teardown reverse tunnel", e)
        }
    }
    
    /**
     * 卸载存储
     */
    private fun unmountStorage(connection: SSHConnection) {
        if (connection.mountedPaths.isEmpty()) return
        
        try {
            Log.d(TAG, "Unmounting storage paths: ${connection.mountedPaths}")
            
            val unmountCommands = connection.mountedPaths.joinToString("\n") { path ->
                """
                if mountpoint -q $path 2>/dev/null; then
                    fusermount -u $path 2>/dev/null || umount $path 2>/dev/null || true
                    echo "UNMOUNT_SUCCESS:$path"
                fi
                """.trimIndent()
            }
            
            val channel = connection.session.openChannel("exec") as com.jcraft.jsch.ChannelExec
            try {
                channel.setCommand(unmountCommands)
                channel.setOutputStream(object : java.io.OutputStream() { override fun write(value: Int) = Unit })
                channel.setErrStream(object : java.io.OutputStream() { override fun write(value: Int) = Unit })
                channel.connect(5_000)
                val deadline = System.nanoTime() + 5_000_000_000L
                while (!channel.isClosed && System.nanoTime() < deadline) Thread.sleep(20)
                check(channel.isClosed) { "Remote unmount did not finish within 5 seconds" }
            } finally { channel.disconnect() }
            connection.mountedPaths.clear()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unmount storage", e)
        }
    }
}
