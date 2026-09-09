package com.ai.assistance.operit.terminal.ui

import com.ai.assistance.operit.terminal.data.SSHAuthType
import com.ai.assistance.operit.terminal.data.SSHConfig

internal enum class SSHConfigField { HOST, PORT, USERNAME, PASSWORD, PRIVATE_KEY, KEEP_ALIVE, REMOTE_PORT, LOCAL_PORT, LOCAL_USERNAME, LOCAL_PASSWORD }

/** 编辑表单只负责它暴露的字段，保留正向转发等由其他入口持有的配置。 */
internal data class SSHConfigDraft(
    val host: String,
    val port: String,
    val username: String,
    val authType: SSHAuthType,
    val password: String,
    val privateKeyPath: String,
    val passphrase: String,
    val enableKeepAlive: Boolean,
    val keepAliveInterval: String,
    val enableReverseTunnel: Boolean,
    val remoteTunnelPort: String,
    val localSshPort: String,
    val localSshUsername: String,
    val localSshPassword: String,
) {
    private fun port(value: String) = value.trim().toIntOrNull()?.takeIf { it in 1..65535 }
    private fun interval() = keepAliveInterval.trim().toIntOrNull()?.takeIf { it in 1..(Int.MAX_VALUE / 1000) }
    private fun validName(value: String) = value.matches(Regex("[A-Za-z0-9_.-]+")) && !value.startsWith("-")

    val errors: Set<SSHConfigField>
        get() = buildSet {
            if (!host.trim().matches(Regex("[A-Za-z0-9:._-]+")) || host.trim().startsWith("-")) add(SSHConfigField.HOST)
            if (port(port) == null) add(SSHConfigField.PORT)
            if (!validName(username.trim())) add(SSHConfigField.USERNAME)
            if (authType == SSHAuthType.PASSWORD && (password.isEmpty() || '\u0000' in password)) add(SSHConfigField.PASSWORD)
            if (authType == SSHAuthType.PUBLIC_KEY && (privateKeyPath.isBlank() || privateKeyPath.any { it.isISOControl() })) add(SSHConfigField.PRIVATE_KEY)
            if (enableKeepAlive && interval() == null) add(SSHConfigField.KEEP_ALIVE)
            if (enableReverseTunnel) {
                if (port(remoteTunnelPort) == null) add(SSHConfigField.REMOTE_PORT)
                if (port(localSshPort) == null) add(SSHConfigField.LOCAL_PORT)
                if (!validName(localSshUsername.trim())) add(SSHConfigField.LOCAL_USERNAME)
                if (localSshPassword.length < 16 || localSshPassword.isBlank() || '\u0000' in localSshPassword) add(SSHConfigField.LOCAL_PASSWORD)
            }
        }

    fun toConfig(original: SSHConfig?): SSHConfig {
        require(errors.isEmpty()) { "Invalid SSH configuration fields: ${errors.joinToString()}" }
        val base = original ?: SSHConfig(host = host.trim(), username = username.trim(), authType = authType)
        return base.copy(
            host = host.trim(), port = checkNotNull(port(port)), username = username.trim(), authType = authType,
            password = if (authType == SSHAuthType.PASSWORD) password else null,
            privateKeyPath = if (authType == SSHAuthType.PUBLIC_KEY) privateKeyPath.trim() else null,
            passphrase = if (authType == SSHAuthType.PUBLIC_KEY) passphrase.takeIf { it.isNotEmpty() } else null,
            enableKeepAlive = enableKeepAlive,
            // 未启用的分组不提交其隐藏输入；再次启用时仍须完整校验。
            keepAliveInterval = if (enableKeepAlive) checkNotNull(interval()) else base.keepAliveInterval,
            enableReverseTunnel = enableReverseTunnel,
            remoteTunnelPort = if (enableReverseTunnel) checkNotNull(port(remoteTunnelPort)) else base.remoteTunnelPort,
            localSshPort = if (enableReverseTunnel) checkNotNull(port(localSshPort)) else base.localSshPort,
            localSshUsername = if (enableReverseTunnel) localSshUsername.trim() else base.localSshUsername,
            localSshPassword = if (enableReverseTunnel) localSshPassword else base.localSshPassword,
        )
    }
}
