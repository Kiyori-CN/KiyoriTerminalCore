package com.ai.assistance.operit.terminal.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.ai.assistance.operit.terminal.R
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.terminal.data.SSHAuthType
import com.ai.assistance.operit.terminal.data.SSHConfig
import com.ai.assistance.operit.terminal.data.generateLocalSshPassword

/**
 * SSH 配置界面（单一配置）
 */
@Composable
fun SSHConfigScreen(
    config: SSHConfig?,
    enabled: Boolean = true,
    onSave: suspend (SSHConfig) -> Unit,
    onDelete: suspend () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "SSH 连接配置",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = SettingsTheme.onSurfaceColor
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        if (config == null) {
            // 无配置，显示添加按钮
            Text(
                text = "暂无SSH配置",
                color = SettingsTheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            var showDialog by remember { mutableStateOf(false) }
            
            Button(
                enabled = enabled,
                onClick = { showDialog = true },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SettingsTheme.primaryColor
                )
            ) {
                Text("设置 SSH 配置")
            }
            
            if (showDialog) {
                SSHConfigEditDialog(
                    config = null,
                    onDismiss = { showDialog = false },
                    onConfirm = { newConfig ->
                        onSave(newConfig)
                        showDialog = false
                    }
                )
            }
        } else {
            // 显示当前配置
            SSHConfigCard(
                config = config,
                enabled = enabled,
                onEdit = { newConfig -> onSave(newConfig) },
                onDelete = onDelete
            )
        }
    }
}

/**
 * SSH 配置卡片
 */
@Composable
private fun SSHConfigCard(
    config: SSHConfig,
    enabled: Boolean,
    onEdit: suspend (SSHConfig) -> Unit,
    onDelete: suspend () -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = SettingsTheme.surfaceColor
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 配置信息
            Text(
                text = "${config.username}@${config.host}:${config.port}",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = SettingsTheme.onSurfaceColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "认证方式: ${if (config.authType == SSHAuthType.PASSWORD) "密码" else "公钥"}",
                fontSize = 14.sp,
                color = SettingsTheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 使用提示
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = SettingsTheme.primaryColor.copy(alpha = 0.1f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "💡",
                        fontSize = 16.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = stringResource(R.string.ssh_exit_ends_session),
                        fontSize = 12.sp,
                        color = SettingsTheme.onSurfaceColor
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 操作按钮
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = enabled && !isDeleting,
                    onClick = { showEditDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SettingsTheme.primaryColor
                    )
                ) {
                    Icon(Icons.Default.Edit, "编辑", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("编辑")
                }
                
                OutlinedButton(
                    enabled = enabled && !isDeleting,
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SettingsTheme.errorColor
                    )
                ) {
                    Icon(Icons.Default.Delete, "删除", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("删除")
                }
            }
        }
    }
    
    // 编辑对话框
    if (showEditDialog) {
        SSHConfigEditDialog(
            config = config,
            onDismiss = { showEditDialog = false },
            onConfirm = { newConfig ->
                onEdit(newConfig)
                showEditDialog = false
            }
        )
    }
    
    // 删除确认对话框
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
            title = { Text("确认删除", color = SettingsTheme.onSurfaceColor) },
            text = {
                Column {
                    Text(stringResource(R.string.ssh_delete_connection_note), color = SettingsTheme.onSurfaceColor)
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(
                    enabled = !isDeleting,
                    onClick = {
                        if (!isDeleting) {
                            isDeleting = true
                            deleteError = null
                            scope.launch {
                                try {
                                    onDelete()
                                    showDeleteDialog = false
                                } catch (error: CancellationException) {
                                    throw error
                                } catch (error: Exception) {
                                    Log.e("SSHConfigScreen", "Unable to delete SSH configuration", error)
                                    deleteError = context.getString(R.string.ssh_config_delete_failed)
                                } finally {
                                    isDeleting = false
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SettingsTheme.errorColor
                    )
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(enabled = !isDeleting, onClick = { showDeleteDialog = false }) {
                    Text("取消", color = SettingsTheme.primaryColor)
                }
            },
            containerColor = SettingsTheme.surfaceColor
        )
    }
}

/**
 * SSH 配置编辑对话框
 */
@Composable
fun SSHConfigEditDialog(
    config: SSHConfig? = null,
    onDismiss: () -> Unit,
    onConfirm: suspend (SSHConfig) -> Unit
) {
    var host by remember { mutableStateOf(config?.host ?: "") }
    var port by remember { mutableStateOf(config?.port?.toString() ?: "22") }
    var username by remember { mutableStateOf(config?.username ?: "") }
    var authType by remember { mutableStateOf(config?.authType ?: SSHAuthType.PASSWORD) }
    var password by remember { mutableStateOf(config?.password ?: "") }
    var privateKeyPath by remember { mutableStateOf(config?.privateKeyPath ?: "") }
    var passphrase by remember { mutableStateOf(config?.passphrase ?: "") }
    
    // 反向隧道配置
    var enableReverseTunnel by remember { mutableStateOf(config?.enableReverseTunnel ?: false) }
    var remoteTunnelPort by remember { mutableStateOf(config?.remoteTunnelPort?.toString() ?: "8881") }
    var localSshPort by remember { mutableStateOf(config?.localSshPort?.toString() ?: "2223") }
    var localSshUsername by remember { mutableStateOf(config?.localSshUsername ?: "android") }
    var localSshPassword by remember {
        mutableStateOf(config?.localSshPassword ?: generateLocalSshPassword())
    }
    
    // 心跳包配置
    var enableKeepAlive by remember { mutableStateOf(config?.enableKeepAlive ?: true) }
    var keepAliveInterval by remember { mutableStateOf(config?.keepAliveInterval?.toString() ?: "30") }
    var isSaving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val draft = SSHConfigDraft(host, port, username, authType, password, privateKeyPath, passphrase,
        enableKeepAlive, keepAliveInterval, enableReverseTunnel, remoteTunnelPort, localSshPort, localSshUsername, localSshPassword)
    val validationErrors = draft.errors
    
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = {
            Text(
                text = if (config == null) "添加 SSH 配置" else "编辑 SSH 配置",
                color = SettingsTheme.onSurfaceColor
            )
        },
        text = {
            LazyColumn {
                if (saveError != null) {
                    item { Text(checkNotNull(saveError), color = MaterialTheme.colorScheme.error) }
                }
                item {
                    OutlinedTextField(
                            enabled = !isSaving,
                        value = host,
                            isError = SSHConfigField.HOST in validationErrors && host.isNotEmpty(),
                            supportingText = { if (SSHConfigField.HOST in validationErrors && host.isNotEmpty()) Text(stringResource(R.string.ssh_host_invalid)) },
                        onValueChange = { host = it },
                        label = { Text("主机地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SettingsTheme.onSurfaceColor,
                            unfocusedTextColor = SettingsTheme.onSurfaceColor,
                            focusedBorderColor = SettingsTheme.primaryColor,
                            unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                            focusedLabelColor = SettingsTheme.primaryColor,
                            unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                        )
                    )
                }
                
                item { Spacer(Modifier.height(8.dp)) }
                
                item {
                    OutlinedTextField(
                            enabled = !isSaving,
                        value = port,
                            isError = SSHConfigField.PORT in validationErrors && port.isNotEmpty(),
                            supportingText = { if (SSHConfigField.PORT in validationErrors && port.isNotEmpty()) Text(stringResource(R.string.ssh_port_invalid)) },
                        onValueChange = { port = it },
                        label = { Text("端口") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SettingsTheme.onSurfaceColor,
                            unfocusedTextColor = SettingsTheme.onSurfaceColor,
                            focusedBorderColor = SettingsTheme.primaryColor,
                            unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                            focusedLabelColor = SettingsTheme.primaryColor,
                            unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                        )
                    )
                }
                
                item { Spacer(Modifier.height(8.dp)) }
                
                item {
                    OutlinedTextField(
                            enabled = !isSaving,
                        value = username,
                            isError = SSHConfigField.USERNAME in validationErrors && username.isNotEmpty(),
                            supportingText = { if (SSHConfigField.USERNAME in validationErrors && username.isNotEmpty()) Text(stringResource(R.string.ssh_username_invalid)) },
                        onValueChange = { username = it },
                        label = { Text("用户名") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = SettingsTheme.onSurfaceColor,
                            unfocusedTextColor = SettingsTheme.onSurfaceColor,
                            focusedBorderColor = SettingsTheme.primaryColor,
                            unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                            focusedLabelColor = SettingsTheme.primaryColor,
                            unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                        )
                    )
                }
                
                item { Spacer(Modifier.height(8.dp)) }
                
                // 认证方式选择
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            enabled = !isSaving,
                            selected = authType == SSHAuthType.PASSWORD,
                            onClick = { authType = SSHAuthType.PASSWORD },
                            label = { Text("密码认证") },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            enabled = !isSaving,
                            selected = authType == SSHAuthType.PUBLIC_KEY,
                            onClick = { authType = SSHAuthType.PUBLIC_KEY },
                            label = { Text("公钥认证") },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                
                item { Spacer(Modifier.height(8.dp)) }
                
                // 根据认证类型显示不同字段
                if (authType == SSHAuthType.PASSWORD) {
                    item {
                        OutlinedTextField(
                            enabled = !isSaving,
                            value = password,
                            isError = SSHConfigField.PASSWORD in validationErrors && password.isNotEmpty(),
                            supportingText = { if (SSHConfigField.PASSWORD in validationErrors && password.isNotEmpty()) Text(stringResource(R.string.ssh_password_invalid)) },
                            onValueChange = { password = it },
                            label = { Text("密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SettingsTheme.onSurfaceColor,
                                unfocusedTextColor = SettingsTheme.onSurfaceColor,
                                focusedBorderColor = SettingsTheme.primaryColor,
                                unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                                focusedLabelColor = SettingsTheme.primaryColor,
                                unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                            )
                        )
                    }
                } else {
                    item {
                        OutlinedTextField(
                            enabled = !isSaving,
                            value = privateKeyPath,
                            isError = SSHConfigField.PRIVATE_KEY in validationErrors && privateKeyPath.isNotEmpty(),
                            supportingText = { if (SSHConfigField.PRIVATE_KEY in validationErrors && privateKeyPath.isNotEmpty()) Text(stringResource(R.string.ssh_private_key_invalid)) },
                            onValueChange = { privateKeyPath = it },
                            label = { Text("私钥路径") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SettingsTheme.onSurfaceColor,
                                unfocusedTextColor = SettingsTheme.onSurfaceColor,
                                focusedBorderColor = SettingsTheme.primaryColor,
                                unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                                focusedLabelColor = SettingsTheme.primaryColor,
                                unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                            )
                        )
                    }
                    
                    item { Spacer(Modifier.height(8.dp)) }
                    
                    item {
                        OutlinedTextField(
                            enabled = !isSaving,
                            value = passphrase,
                            onValueChange = { passphrase = it },
                            label = { Text("密钥密码（可选）") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SettingsTheme.onSurfaceColor,
                                unfocusedTextColor = SettingsTheme.onSurfaceColor,
                                focusedBorderColor = SettingsTheme.primaryColor,
                                unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                                focusedLabelColor = SettingsTheme.primaryColor,
                                unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                            )
                        )
                    }
                }
                
                // 心跳包配置分隔符
                item { Spacer(Modifier.height(16.dp)) }
                
                item {
                    HorizontalDivider(
                        color = SettingsTheme.onSurfaceColor.copy(alpha = 0.2f)
                    )
                }
                
                item { Spacer(Modifier.height(8.dp)) }
                
                // 心跳包开关
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用心跳包",
                                color = SettingsTheme.onSurfaceColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "防止连接因闲置而断开",
                                color = SettingsTheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            enabled = !isSaving,
                            checked = enableKeepAlive,
                            onCheckedChange = { enableKeepAlive = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SettingsTheme.primaryColor,
                                checkedTrackColor = SettingsTheme.primaryColor.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
                
                // 如果启用心跳包，显示配置字段
                if (enableKeepAlive) {
                    item { Spacer(Modifier.height(12.dp)) }
                    
                    item {
                        OutlinedTextField(
                            enabled = !isSaving,
                            value = keepAliveInterval,
                            isError = SSHConfigField.KEEP_ALIVE in validationErrors && keepAliveInterval.isNotEmpty(),
                            supportingText = { if (SSHConfigField.KEEP_ALIVE in validationErrors && keepAliveInterval.isNotEmpty()) Text(stringResource(R.string.ssh_keep_alive_invalid)) },
                            onValueChange = { keepAliveInterval = it },
                            label = { Text("心跳间隔（秒）") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SettingsTheme.onSurfaceColor,
                                unfocusedTextColor = SettingsTheme.onSurfaceColor,
                                focusedBorderColor = SettingsTheme.primaryColor,
                                unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                                focusedLabelColor = SettingsTheme.primaryColor,
                                unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                            )
                        )
                    }
                    
                    item { Spacer(Modifier.height(4.dp)) }
                    
                    item {
                        Text(
                            text = "💡 建议设置为 30-60 秒，每隔此时间向服务器发送心跳",
                            color = SettingsTheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
                
                // 反向隧道配置分隔符
                item { Spacer(Modifier.height(16.dp)) }
                
                item {
                    HorizontalDivider(
                        color = SettingsTheme.onSurfaceColor.copy(alpha = 0.2f)
                    )
                }
                
                item { Spacer(Modifier.height(8.dp)) }
                
                // 反向挂载开关
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "启用反向挂载",
                                color = SettingsTheme.onSurfaceColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = "允许远程服务器挂载本地文件系统",
                                color = SettingsTheme.onSurfaceVariant,
                                fontSize = 12.sp
                            )
                        }
                        Switch(
                            enabled = !isSaving,
                            checked = enableReverseTunnel,
                            onCheckedChange = { enableReverseTunnel = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SettingsTheme.primaryColor,
                                checkedTrackColor = SettingsTheme.primaryColor.copy(alpha = 0.5f)
                            )
                        )
                    }
                }
                
                // 反向挂载说明
                item { Spacer(Modifier.height(8.dp)) }
                
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = SettingsTheme.primaryColor.copy(alpha = 0.1f)
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "📋 反向挂载说明",
                                color = SettingsTheme.primaryColor,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = "• 本地需要：openssh-server（在环境配置中安装）",
                                color = SettingsTheme.onSurfaceColor,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "• 远程需要：sshfs（在远程服务器安装）",
                                color = SettingsTheme.onSurfaceColor,
                                fontSize = 12.sp
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "启用后，远程服务器可通过 ~/storage 和 ~/sdcard 访问本地文件",
                                color = SettingsTheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                
                // 如果启用反向隧道，显示配置字段
                if (enableReverseTunnel) {
                    item { Spacer(Modifier.height(12.dp)) }
                    
                    item {
                        OutlinedTextField(
                            enabled = !isSaving,
                            value = remoteTunnelPort,
                            isError = SSHConfigField.REMOTE_PORT in validationErrors && remoteTunnelPort.isNotEmpty(),
                            supportingText = { if (SSHConfigField.REMOTE_PORT in validationErrors && remoteTunnelPort.isNotEmpty()) Text(stringResource(R.string.ssh_port_invalid)) },
                            onValueChange = { remoteTunnelPort = it },
                            label = { Text("远程隧道端口") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SettingsTheme.onSurfaceColor,
                                unfocusedTextColor = SettingsTheme.onSurfaceColor,
                                focusedBorderColor = SettingsTheme.primaryColor,
                                unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                                focusedLabelColor = SettingsTheme.primaryColor,
                                unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                            )
                        )
                    }
                    
                    item { Spacer(Modifier.height(8.dp)) }
                    
                    item {
                        OutlinedTextField(
                            enabled = !isSaving,
                            value = localSshPort,
                            isError = SSHConfigField.LOCAL_PORT in validationErrors && localSshPort.isNotEmpty(),
                            supportingText = { if (SSHConfigField.LOCAL_PORT in validationErrors && localSshPort.isNotEmpty()) Text(stringResource(R.string.ssh_port_invalid)) },
                            onValueChange = { localSshPort = it },
                            label = { Text("本地SSH端口") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SettingsTheme.onSurfaceColor,
                                unfocusedTextColor = SettingsTheme.onSurfaceColor,
                                focusedBorderColor = SettingsTheme.primaryColor,
                                unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                                focusedLabelColor = SettingsTheme.primaryColor,
                                unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                            )
                        )
                    }
                    
                    item { Spacer(Modifier.height(8.dp)) }
                    
                    item {
                        OutlinedTextField(
                            enabled = !isSaving,
                            value = localSshUsername,
                            isError = SSHConfigField.LOCAL_USERNAME in validationErrors && localSshUsername.isNotEmpty(),
                            supportingText = { if (SSHConfigField.LOCAL_USERNAME in validationErrors && localSshUsername.isNotEmpty()) Text(stringResource(R.string.ssh_username_invalid)) },
                            onValueChange = { localSshUsername = it },
                            label = { Text("本地SSH用户名") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SettingsTheme.onSurfaceColor,
                                unfocusedTextColor = SettingsTheme.onSurfaceColor,
                                focusedBorderColor = SettingsTheme.primaryColor,
                                unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                                focusedLabelColor = SettingsTheme.primaryColor,
                                unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                            )
                        )
                    }
                    
                    item { Spacer(Modifier.height(8.dp)) }
                    
                    item {
                        OutlinedTextField(
                            enabled = !isSaving,
                            value = localSshPassword,
                            isError = SSHConfigField.LOCAL_PASSWORD in validationErrors && localSshPassword.isNotEmpty(),
                            supportingText = { if (SSHConfigField.LOCAL_PASSWORD in validationErrors && localSshPassword.isNotEmpty()) Text(stringResource(R.string.ssh_local_password_invalid)) },
                            onValueChange = { localSshPassword = it },
                            label = { Text("本地SSH密码") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = SettingsTheme.onSurfaceColor,
                                unfocusedTextColor = SettingsTheme.onSurfaceColor,
                                focusedBorderColor = SettingsTheme.primaryColor,
                                unfocusedBorderColor = SettingsTheme.onSurfaceColor.copy(alpha = 0.5f),
                                focusedLabelColor = SettingsTheme.primaryColor,
                                unfocusedLabelColor = SettingsTheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (!isSaving && validationErrors.isEmpty()) {
                        isSaving = true
                        saveError = null
                        val submitted = draft.toConfig(config)
                        scope.launch {
                            try {
                                onConfirm(submitted)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                Log.e("SSHConfigScreen", "Unable to save SSH configuration", error)
                                saveError = context.getString(R.string.ssh_config_save_failed)
                            } finally {
                                isSaving = false
                            }
                        }
                    }
                },
                enabled = !isSaving && validationErrors.isEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SettingsTheme.primaryColor
                )
            ) {
                Text(if (isSaving) stringResource(R.string.ssh_config_saving) else "保存")
            }
        },
        dismissButton = {
            TextButton(enabled = !isSaving, onClick = onDismiss) {
                Text("取消", color = SettingsTheme.primaryColor)
            }
        },
        containerColor = SettingsTheme.surfaceColor
    )
}
