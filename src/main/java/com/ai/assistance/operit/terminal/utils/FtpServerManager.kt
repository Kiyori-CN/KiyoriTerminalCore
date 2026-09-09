package com.ai.assistance.operit.terminal.utils

import android.content.Context
import android.content.res.Resources
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.Authority
import org.apache.ftpserver.ftplet.UserManager
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.listener.nio.NioListener
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.ftpserver.DataConnectionConfigurationFactory
import java.io.File
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.*

class FtpServerManager private constructor(
    private val filesDir: File,
    private val resources: Resources
) {
    
    companion object {
        private const val TAG = "FtpServerManager"
        private const val FTP_PORT = 2127
        private const val FTP_USERNAME = "ubuntu"
        private const val FTP_PASSWORD_LENGTH = 20
        private const val FTP_PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        
        @Volatile
        private var instance: FtpServerManager? = null
        
        fun getInstance(context: Context): FtpServerManager {
            return instance ?: synchronized(this) {
                val appContext = context.applicationContext
                instance ?: FtpServerManager(
                    filesDir = appContext.filesDir,
                    resources = appContext.resources
                ).also { instance = it }
            }
        }
    }
    
    private val lifecycleMutex = Mutex()
    @Volatile private var ftpServer: FtpServer? = null
    @Volatile private var activePassword: String? = null
    private val usrDir = File(filesDir, "usr")
    
    private fun getUbuntuRootPath(): String {
        val prootDistroPath = File(usrDir, "var/lib/proot-distro")
        val ubuntuPath = File(prootDistroPath, "installed-rootfs/ubuntu")
        return ubuntuPath.absolutePath
    }
    
    suspend fun startFtpServer(): Boolean = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock { startServerInternal() }
    }

    private fun startServerInternal(): Boolean {
        return try {
            if (ftpServer?.isStopped == false) {
                Log.w(TAG, "FTP服务器已在运行")
                return true
            }
            
            val ubuntuRootPath = getUbuntuRootPath()
            val ubuntuRoot = File(ubuntuRootPath)
            
            if (!ubuntuRoot.exists()) {
                Log.e(TAG, "Ubuntu环境未初始化，无法启动FTP服务器")
                return false
            }
            
            val password = generatePassword()
            val serverFactory = FtpServerFactory()
            val listenerFactory = ListenerFactory()
            
            // 设置监听端口
            listenerFactory.port = FTP_PORT

            // 配置被动模式
            val dataConnectionConfigFactory = DataConnectionConfigurationFactory()
            dataConnectionConfigFactory.setPassivePorts("2128-2136")
            dataConnectionConfigFactory.setPassiveExternalAddress(getLocalIpAddress())
            listenerFactory.setDataConnectionConfiguration(dataConnectionConfigFactory.createDataConnectionConfiguration())

            // 配置监听器
            serverFactory.addListener("default", listenerFactory.createListener())
            
            // 创建用户管理器
            val userManagerFactory = PropertiesUserManagerFactory()
            val userManager = userManagerFactory.createUserManager()
            
            // 创建用户
            val user = BaseUser().apply {
                name = FTP_USERNAME
                this.password = password
                homeDirectory = ubuntuRootPath
                authorities = listOf<Authority>(WritePermission())
            }
            
            userManager.save(user)
            serverFactory.userManager = userManager
            
            // 创建并启动FTP服务器
            ftpServer = serverFactory.createServer()
            ftpServer?.start()
            activePassword = password
            
            Log.i(TAG, "FTP服务器已启动")
            Log.i(TAG, "服务器地址: ${getLocalIpAddress()}:$FTP_PORT")
            Log.i(TAG, "用户名: $FTP_USERNAME")
            Log.i(TAG, "根目录: $ubuntuRootPath")
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动FTP服务器失败", e)
            false
        }
    }
    
    suspend fun stopFtpServer(): Boolean = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock { stopServerInternal() }
    }

    internal suspend fun <T> withStoppedServer(block: suspend () -> T): T = withContext(Dispatchers.IO) {
        lifecycleMutex.withLock {
            check(stopServerInternal()) { "Unable to stop FTP server; environment files have not been removed" }
            block()
        }
    }

    private fun stopServerInternal(): Boolean {
        return try {
            ftpServer?.stop()
            ftpServer = null
            activePassword = null
            Log.i(TAG, "FTP服务器已停止")
            true
        } catch (e: Exception) {
            Log.e(TAG, "停止FTP服务器失败", e)
            false
        }
    }
    
    fun isFtpServerRunning(): Boolean {
        return ftpServer?.isStopped == false
    }
    
    fun getFtpServerInfo(): String {
        val password = activePassword
        return if (isFtpServerRunning() && password != null) {
            resources.getString(
                com.ai.assistance.operit.terminal.R.string.ftp_server_running_info,
                getLocalIpAddress(),
                FTP_PORT.toString(),
                FTP_USERNAME,
                password
            )
        } else {
            resources.getString(com.ai.assistance.operit.terminal.R.string.ftp_server_not_running)
        }
    }
    
    private fun getLocalIpAddress(): String {
        return "127.0.0.1"
    }

    private fun generatePassword(): String {
        val random = SecureRandom()
        return buildString(FTP_PASSWORD_LENGTH) {
            repeat(FTP_PASSWORD_LENGTH) {
                append(FTP_PASSWORD_ALPHABET[random.nextInt(FTP_PASSWORD_ALPHABET.length)])
            }
        }
    }
}
