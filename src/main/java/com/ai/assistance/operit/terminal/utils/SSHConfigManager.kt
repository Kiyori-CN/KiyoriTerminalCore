package com.ai.assistance.operit.terminal.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ai.assistance.operit.terminal.data.SSHAuthType
import com.ai.assistance.operit.terminal.data.SSHConfig
import com.ai.assistance.operit.terminal.data.generateLocalSshPassword
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * SSH 配置管理器（单一配置）
 * 
 * 只管理一个 SSH 连接配置
 */
class SSHConfigManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "ssh_config",
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val TAG = "SSHConfigManager"
        private const val KEY_CONFIG = "config"
        private const val KEY_ENABLED = "ssh_enabled"
    }
    
    /**
     * 获取 SSH 配置
     */
    suspend fun getConfig(): SSHConfig? = withContext(Dispatchers.IO) {
        val configJson = prefs.getString(KEY_CONFIG, null)
        if (configJson == null) {
            Log.d(TAG, "getConfig: No config found")
            return@withContext null
        }
        
        try {
            val json = JSONObject(configJson)
            val config = parseConfig(json)
            if (json.optString("localSshPassword") != config.localSshPassword) {
                check(prefs.edit().putString(KEY_CONFIG, toJson(config).toString()).commit()) {
                    "Unable to persist local SSH credential rotation"
                }
                Log.i(TAG, "Rotated insecure or missing local SSH password")
            }
            config
        } catch (e: Exception) {
            // 损坏配置不能被解释为未配置，否则启用 SSH 时可能在本地执行。
            throw IllegalStateException("Unable to read SSH configuration; review or remove it in settings")
        }
    }
    
    /**
     * 保存 SSH 配置
     */
    suspend fun saveConfig(config: SSHConfig) = withContext(Dispatchers.IO) {
        Log.d(TAG, "saveConfig: Saving config for ${config.username}@${config.host}:${config.port}")
        val json = toJson(config)
        val jsonString = json.toString()

        check(prefs.edit().putString(KEY_CONFIG, jsonString).commit()) { "Unable to save SSH configuration" }
    }
    
    /**
     * 删除 SSH 配置
     */
    suspend fun deleteConfig() = withContext(Dispatchers.IO) {
        Log.d(TAG, "deleteConfig: Deleting SSH config")
        check(prefs.edit().remove(KEY_CONFIG).putBoolean(KEY_ENABLED, false).commit()) { "Unable to delete SSH configuration" }
    }
    
    /**
     * 检查是否有配置
     */
    suspend fun hasConfig(): Boolean = withContext(Dispatchers.IO) {
        prefs.contains(KEY_CONFIG)
    }
    
    /**
     * 获取 SSH 是否启用
     */
    fun isEnabled(): Boolean {
        return prefs.getBoolean(KEY_ENABLED, false)
    }
    
    /**
     * 设置 SSH 是否启用
     */
    suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        Log.d(TAG, "setEnabled: $enabled")
        check(prefs.edit().putBoolean(KEY_ENABLED, enabled).commit()) { "Unable to save SSH connection preference" }
    }
    
    private fun parseConfig(json: JSONObject): SSHConfig {
        return SSHConfig(
            host = json.getString("host"),
            port = json.optInt("port", 22),
            username = json.getString("username"),
            authType = SSHAuthType.valueOf(json.getString("authType")),
            password = json.optString("password", "").takeIf { it.isNotEmpty() },
            privateKeyPath = json.optString("privateKeyPath", "").takeIf { it.isNotEmpty() },
            passphrase = json.optString("passphrase", "").takeIf { it.isNotEmpty() },
            // 反向隧道配置
            enableReverseTunnel = json.optBoolean("enableReverseTunnel", false),
            remoteTunnelPort = json.optInt("remoteTunnelPort", 8888),
            localSshPort = json.optInt("localSshPort", 8022),
            localSshUsername = json.optString("localSshUsername", "root"),
            enablePortForwarding = json.optBoolean("enablePortForwarding", true),
            localForwardPort = json.optInt("localForwardPort", 8751),
            remoteForwardPort = json.optInt("remoteForwardPort", 8752),
            enableKeepAlive = json.optBoolean("enableKeepAlive", true),
            keepAliveInterval = json.optInt("keepAliveInterval", 30),
            localSshPassword =
                json.optString("localSshPassword")
                    .takeUnless {
                        it.length < 16
                    }
                    ?: generateLocalSshPassword()
        )
    }
    
    private fun toJson(config: SSHConfig): JSONObject {
        val json = JSONObject()
        json.put("host", config.host)
        json.put("port", config.port)
        json.put("username", config.username)
        json.put("authType", config.authType.name)
        config.password?.let { json.put("password", it) }
        config.privateKeyPath?.let { json.put("privateKeyPath", it) }
        config.passphrase?.let { json.put("passphrase", it) }
        // 反向隧道配置
        json.put("enableReverseTunnel", config.enableReverseTunnel)
        json.put("remoteTunnelPort", config.remoteTunnelPort)
        json.put("localSshPort", config.localSshPort)
        json.put("localSshUsername", config.localSshUsername)
        json.put("localSshPassword", config.localSshPassword)
        json.put("enablePortForwarding", config.enablePortForwarding)
        json.put("localForwardPort", config.localForwardPort)
        json.put("remoteForwardPort", config.remoteForwardPort)
        json.put("enableKeepAlive", config.enableKeepAlive)
        json.put("keepAliveInterval", config.keepAliveInterval)
        return json
    }
}

