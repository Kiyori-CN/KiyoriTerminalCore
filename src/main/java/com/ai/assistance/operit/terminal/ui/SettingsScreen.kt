package com.ai.assistance.operit.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.Source
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ai.assistance.operit.terminal.data.PackageManagerType
import com.ai.assistance.operit.terminal.data.SourceConfig
import com.ai.assistance.operit.terminal.utils.TerminalFontConfigManager
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

// 设置面板跟随宿主主题；原生终端画布继续使用自己的 RenderConfig。
object SettingsTheme {
    val primaryColor: Color @Composable get() = MaterialTheme.colorScheme.primary
    val primaryVariant: Color @Composable get() = MaterialTheme.colorScheme.primary
    val backgroundColor: Color @Composable get() = MaterialTheme.colorScheme.background
    val surfaceColor: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow
    val onSurfaceColor: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val errorColor: Color @Composable get() = MaterialTheme.colorScheme.error
    val errorVariant: Color @Composable get() = MaterialTheme.colorScheme.error
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onNavigateToSetup: () -> Unit,
) {
    val context = LocalContext.current
    val settingsScope = rememberCoroutineScope()
    var fontResetPending by remember { mutableStateOf(false) }
    var fontResetFailed by remember { mutableStateOf(false) }
    val viewModel: SettingsViewModel = viewModel { SettingsViewModel(context.applicationContext as android.app.Application) }
    
    val cacheSize by viewModel.cacheSize.collectAsState()
    val isCalculatingCache by viewModel.isCalculatingCache.collectAsState()
    val isClearingCache by viewModel.isClearingCache.collectAsState()
    
    // FTP服务器相关状态
    val ftpServerStatus by viewModel.ftpServerStatus.collectAsState()
    val isFtpServerRunning by viewModel.isFtpServerRunning.collectAsState()
    val isManagingFtpServer by viewModel.isManagingFtpServer.collectAsState()
    
    // 源管理相关状态
    val sourceConfigs by viewModel.sourceConfigs.collectAsState()
    val sourceLoadError by viewModel.sourceLoadError.collectAsState()
    var showSourceDialogFor by remember { mutableStateOf<PackageManagerType?>(null) }

    val virtualKeyboardLayout by viewModel.virtualKeyboardLayout.collectAsState()
    var showVirtualKeyboardDialog by remember { mutableStateOf(false) }
    
    // 字体配置相关状态
    val fontConfigManager = remember { TerminalFontConfigManager.getInstance(context) }
    var fontSize by remember { mutableFloatStateOf(fontConfigManager.getFontSize()) }
    var fontPath by remember { mutableStateOf(fontConfigManager.getFontPath() ?: "") }
    var fontName by remember { mutableStateOf(fontConfigManager.getFontName() ?: "") }
    var targetFps by remember { mutableIntStateOf(fontConfigManager.getTargetFps()) }
    var showFontSizeDialog by remember { mutableStateOf(false) }
    var showFontPathDialog by remember { mutableStateOf(false) }
    var showFontNameDialog by remember { mutableStateOf(false) }
    var showTargetFpsDialog by remember { mutableStateOf(false) }
    
    // SSH配置相关状态（单一配置）
    val sshConfig by viewModel.sshConfig.collectAsState()
    val sshEnabled by viewModel.sshEnabled.collectAsState()
    val sshLoadError by viewModel.sshLoadError.collectAsState()
    val sshBusy by viewModel.sshBusy.collectAsState()
    var sshTogglePending by remember { mutableStateOf(false) }
    var sshToggleFailed by remember { mutableStateOf(false) }
    var showSshToolsMissingDialog by remember { mutableStateOf(false) }
    var showOpensshMissingDialog by remember { mutableStateOf(false) }
    
    // 共享tmp设置状态
    val sharedTmpEnabled by viewModel.sharedTmpEnabled.collectAsState()
    
    val chrootEnabled by viewModel.chrootEnabled.collectAsState()
    val chrootMountStatus by viewModel.chrootMountStatus.collectAsState()
    val chrootMountDetails by viewModel.chrootMountDetails.collectAsState()
    val isInspectingChrootMounts by viewModel.isInspectingChrootMounts.collectAsState()
    val isUnmountingChrootMounts by viewModel.isUnmountingChrootMounts.collectAsState()
    
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showUnmountConfirmDialog by remember { mutableStateOf(false) }

    val virtualKeyboardSummary = remember(virtualKeyboardLayout) {
        virtualKeyboardLayout.rows.joinToString(" | ") { row ->
            row.joinToString(" ") { it.label }
        }
    }

    // 当 ViewModel 通知显示对话框时，更新本地状态
    val showSshToolsMissingDialogState by viewModel.showSshToolsMissingDialog.collectAsState()
    LaunchedEffect(showSshToolsMissingDialogState) {
        showSshToolsMissingDialog = showSshToolsMissingDialogState
    }
    
    val showOpensshMissingDialogState by viewModel.showOpensshMissingDialog.collectAsState()
    LaunchedEffect(showOpensshMissingDialogState) {
        showOpensshMissingDialog = showOpensshMissingDialogState
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(com.ai.assistance.operit.terminal.R.string.settings_title), color = SettingsTheme.onSurfaceColor) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(com.ai.assistance.operit.terminal.R.string.back), tint = SettingsTheme.onSurfaceColor)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SettingsTheme.surfaceColor
                ),
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
        containerColor = SettingsTheme.backgroundColor
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // FTP服务器管理区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SettingsTheme.surfaceColor)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(com.ai.assistance.operit.terminal.R.string.ftp_server_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SettingsTheme.onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = ftpServerStatus,
                        color = if (isFtpServerRunning) SettingsTheme.primaryColor else SettingsTheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isFtpServerRunning) {
                            Button(
                                onClick = { viewModel.stopFtpServer() },
                                enabled = !isManagingFtpServer && !isClearingCache && !isUnmountingChrootMounts,
                                colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.errorColor),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isManagingFtpServer) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = SettingsTheme.onSurfaceColor
                                    )
                                } else {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isManagingFtpServer) stringResource(com.ai.assistance.operit.terminal.R.string.ftp_server_stopping) else stringResource(com.ai.assistance.operit.terminal.R.string.ftp_server_stop))
                            }
                        } else {
                            Button(
                                onClick = { viewModel.startFtpServer() },
                                enabled = !isManagingFtpServer && !isClearingCache && !isUnmountingChrootMounts,
                                colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.primaryColor),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isManagingFtpServer) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = SettingsTheme.onSurfaceColor
                                    )
                                } else {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(if (isManagingFtpServer) stringResource(com.ai.assistance.operit.terminal.R.string.ftp_server_starting) else stringResource(com.ai.assistance.operit.terminal.R.string.ftp_server_start))
                            }
                        }
                    }
                    
                    if (isFtpServerRunning) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(com.ai.assistance.operit.terminal.R.string.ftp_server_tip),
                            color = SettingsTheme.primaryColor,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(com.ai.assistance.operit.terminal.R.string.ftp_server_suggestion),
                            color = SettingsTheme.primaryColor,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            
            // 缓存管理区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SettingsTheme.surfaceColor)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(com.ai.assistance.operit.terminal.R.string.storage_management_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SettingsTheme.onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(com.ai.assistance.operit.terminal.R.string.ubuntu_environment_size, cacheSize),
                        color = SettingsTheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.getCacheSize() },
                            enabled = !isCalculatingCache && !isClearingCache,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = SettingsTheme.primaryColor
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SettingsTheme.primaryColor)
                        ) {
                            if (isCalculatingCache) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = SettingsTheme.primaryColor
                                )
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isCalculatingCache) stringResource(com.ai.assistance.operit.terminal.R.string.refresh_size_calculating) else stringResource(com.ai.assistance.operit.terminal.R.string.refresh_size))
                        }
                        
                        Button(
                            enabled = !isClearingCache && !isManagingFtpServer && !isUnmountingChrootMounts,
                            onClick = { showClearCacheDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.errorColor),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(if (isClearingCache) com.ai.assistance.operit.terminal.R.string.environment_resetting else com.ai.assistance.operit.terminal.R.string.reset_environment))
                        }
                    }
                }
            }
            
            // 项目地址和更新检查区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SettingsTheme.surfaceColor)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = stringResource(com.ai.assistance.operit.terminal.R.string.project_address_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SettingsTheme.onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(com.ai.assistance.operit.terminal.R.string.project_name),
                        color = SettingsTheme.onSurfaceVariant,
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                context.startActivity(
                                    android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        "https://github.com/Kiyori-CN/Kiyori".toUri()
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                            enabled = true,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = SettingsTheme.primaryColor
                            ),
                            border = androidx.compose.foundation.BorderStroke(1.dp, SettingsTheme.primaryColor)
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(com.ai.assistance.operit.terminal.R.string.visit_project))
                        }
                    }
                }
            }
            
            // SSH配置区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SettingsTheme.surfaceColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // SSH 启用开关
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(com.ai.assistance.operit.terminal.R.string.ssh_enable_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = SettingsTheme.onSurfaceColor
                            )
                            if (sshConfig != null) {
                            Text(
                                text = if (sshEnabled) stringResource(com.ai.assistance.operit.terminal.R.string.ssh_use_remote_desc) else stringResource(com.ai.assistance.operit.terminal.R.string.ssh_use_local_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = SettingsTheme.onSurfaceColor.copy(alpha = 0.6f)
                            )
                            // 显示反向挂载状态
                            sshConfig?.let { config ->
                                if (config.enableReverseTunnel) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = stringResource(com.ai.assistance.operit.terminal.R.string.ssh_reverse_mount_enabled),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SettingsTheme.primaryColor,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = stringResource(com.ai.assistance.operit.terminal.R.string.ssh_reverse_mount_desc),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = SettingsTheme.onSurfaceVariant,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                        Switch(
                            checked = sshEnabled,
                            onCheckedChange = { enabled ->
                                sshTogglePending = true
                                sshToggleFailed = false
                                settingsScope.launch {
                                    try { viewModel.setSSHEnabled(enabled) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (_: Exception) { sshToggleFailed = true }
                                    finally { sshTogglePending = false }
                                }
                            },
                            enabled = (sshEnabled || (sshConfig != null && !sshLoadError)) && !sshBusy && !sshTogglePending,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SettingsTheme.primaryColor,
                                checkedTrackColor = SettingsTheme.primaryColor.copy(alpha = 0.5f)
                            )
                        )
                    }
                    
                    if (sshToggleFailed) {
                        Text(stringResource(com.ai.assistance.operit.terminal.R.string.terminal_settings_save_failed), color = MaterialTheme.colorScheme.error)
                    }
                    SSHConnectionActions(viewModel, enabled = !sshTogglePending && !isClearingCache && !isUnmountingChrootMounts)
                    if (sshConfig == null && !sshLoadError) {
                        Text(
                            text = stringResource(com.ai.assistance.operit.terminal.R.string.ssh_config_required),
                            style = MaterialTheme.typography.bodySmall,
                            color = SettingsTheme.onSurfaceColor.copy(alpha = 0.6f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                    
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = SettingsTheme.onSurfaceColor.copy(alpha = 0.1f)
                    )
                    
                    // SSH 配置表单
                    if (!sshLoadError) SSHConfigScreen(
                        config = sshConfig,
                        enabled = !sshBusy && !sshTogglePending,
                        onSave = { config ->
                            viewModel.saveSSHConfig(config)
                        },
                        onDelete = {
                            viewModel.deleteSSHConfig()
                        }
                    )
                }
            }
            
            // 共享tmp设置区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SettingsTheme.surfaceColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(com.ai.assistance.operit.terminal.R.string.shared_tmp_title),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = SettingsTheme.onSurfaceColor
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (sharedTmpEnabled) {
                                    stringResource(com.ai.assistance.operit.terminal.R.string.shared_tmp_enabled_desc)
                                } else {
                                    stringResource(com.ai.assistance.operit.terminal.R.string.shared_tmp_disabled_desc)
                                },
                                fontSize = 14.sp,
                                color = SettingsTheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = sharedTmpEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setSharedTmpEnabled(enabled)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SettingsTheme.primaryColor,
                                checkedTrackColor = SettingsTheme.primaryColor.copy(alpha = 0.5f)
                            )
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(com.ai.assistance.operit.terminal.R.string.shared_tmp_note),
                        fontSize = 12.sp,
                        color = SettingsTheme.onSurfaceVariant.copy(alpha = 0.8f),
                        lineHeight = 16.sp
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SettingsTheme.surfaceColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(com.ai.assistance.operit.terminal.R.string.chroot_mode_title),
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = SettingsTheme.onSurfaceColor
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (chrootEnabled) {
                                    stringResource(com.ai.assistance.operit.terminal.R.string.chroot_mode_enabled_desc)
                                } else {
                                    stringResource(com.ai.assistance.operit.terminal.R.string.chroot_mode_disabled_desc)
                                },
                                fontSize = 14.sp,
                                color = SettingsTheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = chrootEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setChrootEnabled(enabled)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SettingsTheme.primaryColor,
                                checkedTrackColor = SettingsTheme.primaryColor.copy(alpha = 0.5f)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(com.ai.assistance.operit.terminal.R.string.chroot_mode_note),
                        fontSize = 12.sp,
                        color = SettingsTheme.onSurfaceVariant.copy(alpha = 0.8f),
                        lineHeight = 16.sp
                    )

                    if (chrootEnabled) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = chrootMountStatus,
                            fontSize = 14.sp,
                            color = SettingsTheme.primaryColor
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.inspectChrootMounts() },
                                enabled = !isInspectingChrootMounts && !isUnmountingChrootMounts && !isClearingCache && !isManagingFtpServer,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = SettingsTheme.primaryColor
                                ),
                                border = androidx.compose.foundation.BorderStroke(1.dp, SettingsTheme.primaryColor)
                            ) {
                                if (isInspectingChrootMounts) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = SettingsTheme.primaryColor
                                    )
                                } else {
                                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(com.ai.assistance.operit.terminal.R.string.chroot_mount_check))
                            }

                            Button(
                                onClick = { showUnmountConfirmDialog = true },
                                enabled = !isInspectingChrootMounts && !isUnmountingChrootMounts && !isClearingCache && !isManagingFtpServer,
                                colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.errorColor),
                                modifier = Modifier.weight(1f)
                            ) {
                                if (isUnmountingChrootMounts) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = SettingsTheme.onSurfaceColor
                                    )
                                } else {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(com.ai.assistance.operit.terminal.R.string.chroot_mount_unmount))
                            }
                        }

                        if (chrootMountDetails.isNotBlank()) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(com.ai.assistance.operit.terminal.R.string.chroot_mount_details_title),
                                fontSize = 12.sp,
                                color = SettingsTheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = chrootMountDetails,
                                fontSize = 12.sp,
                                color = SettingsTheme.onSurfaceColor,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(SettingsTheme.backgroundColor.copy(alpha = 0.5f), MaterialTheme.shapes.small)
                                    .padding(12.dp)
                            )
                        }
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SettingsTheme.surfaceColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(com.ai.assistance.operit.terminal.R.string.virtual_keyboard_settings_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SettingsTheme.onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    SettingsItem(
                        title = stringResource(com.ai.assistance.operit.terminal.R.string.virtual_keyboard_custom_title),
                        subtitle = virtualKeyboardSummary,
                        onClick = { showVirtualKeyboardDialog = true },
                        icon = Icons.Default.TextFields
                    )
                }
            }
            
            // 字体设置区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SettingsTheme.surfaceColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(com.ai.assistance.operit.terminal.R.string.font_settings_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SettingsTheme.onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 字体大小设置
                    SettingsItem(
                        title = stringResource(com.ai.assistance.operit.terminal.R.string.font_size_title),
                        enabled = !fontResetPending,
                        subtitle = "$fontSize px",
                        onClick = { showFontSizeDialog = true },
                        icon = Icons.Default.TextFields
                    )
                    HorizontalDivider(color = SettingsTheme.backgroundColor)

                    // 渲染帧率设置
                    SettingsItem(
                        title = stringResource(com.ai.assistance.operit.terminal.R.string.target_fps_title),
                        enabled = !fontResetPending,
                        subtitle = "${targetFps} FPS",
                        onClick = { showTargetFpsDialog = true },
                        icon = Icons.Default.TextFields
                    )
                    HorizontalDivider(color = SettingsTheme.backgroundColor)
                    
                    // 字体路径设置
                    SettingsItem(
                        title = stringResource(com.ai.assistance.operit.terminal.R.string.font_path_title),
                        enabled = !fontResetPending,
                        subtitle = fontPath.ifEmpty { stringResource(com.ai.assistance.operit.terminal.R.string.font_not_set) },
                        onClick = { showFontPathDialog = true },
                        icon = Icons.Default.Folder
                    )
                    HorizontalDivider(color = SettingsTheme.backgroundColor)
                    
                    // 系统字体名称设置
                    SettingsItem(
                        title = stringResource(com.ai.assistance.operit.terminal.R.string.font_name_title),
                        enabled = !fontResetPending,
                        subtitle = fontName.ifEmpty { stringResource(com.ai.assistance.operit.terminal.R.string.font_not_set) },
                        onClick = { showFontNameDialog = true },
                        icon = Icons.Default.TextFields
                    )
                    HorizontalDivider(color = SettingsTheme.backgroundColor)
                    
                    if (fontResetFailed) {
                        Text(stringResource(com.ai.assistance.operit.terminal.R.string.terminal_settings_save_failed), color = MaterialTheme.colorScheme.error)
                    }
                    // 重置按钮
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        enabled = !fontResetPending,
                        onClick = {
                            if (!fontResetPending) {
                                fontResetPending = true
                                fontResetFailed = false
                                settingsScope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { fontConfigManager.resetToDefault() }
                                        fontSize = fontConfigManager.getFontSize()
                                        fontPath = fontConfigManager.getFontPath().orEmpty()
                                        fontName = fontConfigManager.getFontName().orEmpty()
                                        targetFps = fontConfigManager.getTargetFps()
                                    } catch (cancelled: CancellationException) { throw cancelled }
                                    catch (error: Exception) {
                                        android.util.Log.e("SettingsScreen", "Unable to reset font settings (${error.javaClass.simpleName})")
                                        fontResetFailed = true
                                    } finally { fontResetPending = false }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = SettingsTheme.primaryColor
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SettingsTheme.primaryColor)
                    ) {
                        Text(stringResource(if (fontResetPending) com.ai.assistance.operit.terminal.R.string.ssh_config_saving else com.ai.assistance.operit.terminal.R.string.font_reset_default))
                    }
                }
            }
            
            // 源管理区域
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SettingsTheme.surfaceColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(com.ai.assistance.operit.terminal.R.string.source_management_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = SettingsTheme.onSurfaceColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (sourceLoadError) {
                        Text(stringResource(com.ai.assistance.operit.terminal.R.string.source_load_failed), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = viewModel::reloadSourceConfigs) {
                            Text(stringResource(com.ai.assistance.operit.terminal.R.string.setup_refresh))
                        }
                    }
                    sourceConfigs.forEach { (pm, config) ->
                        SettingsItem(
                            title = pm.displayName,
                            subtitle = stringResource(com.ai.assistance.operit.terminal.R.string.source_current, config.sources.find { it.id == config.selectedSourceId }?.name ?: "N/A"),
                            onClick = { showSourceDialogFor = pm },
                            icon = Icons.Default.Source
                        )
                        HorizontalDivider(color = SettingsTheme.backgroundColor)
                    }
                }
            }
            HorizontalDivider(color = SettingsTheme.surfaceColor)
        }
    }
    
    if (showSshToolsMissingDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.onSshToolsMissingDialogDismissed() },
            title = { 
                Text(
                    text = stringResource(com.ai.assistance.operit.terminal.R.string.ssh_tools_missing_title),
                    color = SettingsTheme.onSurfaceColor, 
                    fontWeight = FontWeight.Bold
                ) 
            },
            text = { 
                SelectionContainer {
                    Text(
                        text = stringResource(com.ai.assistance.operit.terminal.R.string.ssh_tools_missing_message),
                        color = SettingsTheme.onSurfaceColor,
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.onSshToolsMissingDialogDismissed()
                        onNavigateToSetup()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.primaryColor)
                ) {
                    Text(stringResource(com.ai.assistance.operit.terminal.R.string.go_to_setup))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.onSshToolsMissingDialogDismissed() }
                ) {
                    Text(stringResource(com.ai.assistance.operit.terminal.R.string.dialog_cancel))
                }
            },
            containerColor = SettingsTheme.surfaceColor
        )
    }
    
    if (showOpensshMissingDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.onOpensshMissingDialogDismissed() },
            title = { 
                Text(
                    text = stringResource(com.ai.assistance.operit.terminal.R.string.openssh_missing_title),
                    color = SettingsTheme.onSurfaceColor, 
                    fontWeight = FontWeight.Bold
                ) 
            },
            text = { 
                SelectionContainer {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = stringResource(com.ai.assistance.operit.terminal.R.string.openssh_missing_desc),
                            color = SettingsTheme.onSurfaceColor,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(com.ai.assistance.operit.terminal.R.string.openssh_local_component),
                            color = SettingsTheme.primaryColor,
                            fontSize = 14.sp
                        )
                        Text(
                            text = stringResource(com.ai.assistance.operit.terminal.R.string.openssh_go_to_install),
                            color = SettingsTheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(com.ai.assistance.operit.terminal.R.string.openssh_remote_component),
                            color = SettingsTheme.primaryColor,
                            fontSize = 14.sp
                        )
                        Text(
                            text = stringResource(com.ai.assistance.operit.terminal.R.string.openssh_remote_install),
                            color = SettingsTheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                        Text(
                            text = stringResource(com.ai.assistance.operit.terminal.R.string.openssh_install_ubuntu_cmd),
                            color = SettingsTheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Text(
                            text = stringResource(com.ai.assistance.operit.terminal.R.string.openssh_install_centos_cmd),
                            color = SettingsTheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.onOpensshMissingDialogDismissed()
                        onNavigateToSetup()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.primaryColor)
                ) {
                    Text(stringResource(com.ai.assistance.operit.terminal.R.string.openssh_install_button))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { viewModel.onOpensshMissingDialogDismissed() }
                ) {
                    Text(stringResource(com.ai.assistance.operit.terminal.R.string.cancel))
                }
            },
            containerColor = SettingsTheme.surfaceColor
        )
    }

    if (showFontSizeDialog) {
        TerminalValueSettingDialog(
            initialValue = fontSize.toString(),
            title = stringResource(com.ai.assistance.operit.terminal.R.string.font_size_dialog_title),
            label = stringResource(com.ai.assistance.operit.terminal.R.string.font_size_label),
            hint = stringResource(com.ai.assistance.operit.terminal.R.string.font_size_invalid),
            keyboardType = KeyboardType.Decimal,
            validate = { input -> input.trim().toFloatOrNull()?.let { it.isFinite() && it in 12f..100f } == true },
            onDismiss = { showFontSizeDialog = false },
            onConfirm = { input ->
                val value = input.trim().toFloat()
                withContext(Dispatchers.IO) { fontConfigManager.setFontSize(value) }
                fontSize = value
            },
        )
    }
    if (showTargetFpsDialog) {
        TerminalValueSettingDialog(
            initialValue = targetFps.toString(),
            title = stringResource(com.ai.assistance.operit.terminal.R.string.target_fps_dialog_title),
            label = stringResource(com.ai.assistance.operit.terminal.R.string.target_fps_label),
            hint = stringResource(com.ai.assistance.operit.terminal.R.string.target_fps_invalid),
            keyboardType = KeyboardType.Number,
            validate = { it.trim().toIntOrNull()?.let { value -> value in 15..120 } == true },
            onDismiss = { showTargetFpsDialog = false },
            onConfirm = { input ->
                val value = input.trim().toInt()
                withContext(Dispatchers.IO) { fontConfigManager.setTargetFps(value) }
                targetFps = value
            },
        )
    }
    if (showFontPathDialog) {
        TerminalValueSettingDialog(
            initialValue = fontPath,
            title = stringResource(com.ai.assistance.operit.terminal.R.string.font_path_dialog_title),
            label = stringResource(com.ai.assistance.operit.terminal.R.string.font_path_label),
            hint = stringResource(com.ai.assistance.operit.terminal.R.string.font_path_hint),
            validate = { it.none(Char::isISOControl) },
            onDismiss = { showFontPathDialog = false },
            onConfirm = { input ->
                withContext(Dispatchers.IO) { fontConfigManager.setFontPath(input) }
                fontPath = fontConfigManager.getFontPath().orEmpty()
            },
        )
    }
    if (showFontNameDialog) {
        TerminalValueSettingDialog(
            initialValue = fontName,
            title = stringResource(com.ai.assistance.operit.terminal.R.string.font_name_dialog_title),
            label = stringResource(com.ai.assistance.operit.terminal.R.string.font_name_label),
            hint = stringResource(com.ai.assistance.operit.terminal.R.string.font_name_hint),
            validate = { it.none(Char::isISOControl) },
            onDismiss = { showFontNameDialog = false },
            onConfirm = { input ->
                withContext(Dispatchers.IO) { fontConfigManager.setFontName(input) }
                fontName = fontConfigManager.getFontName().orEmpty()
            },
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            title = { 
                Text(stringResource(com.ai.assistance.operit.terminal.R.string.reset_dialog_title), color = SettingsTheme.errorColor, fontWeight = FontWeight.Bold)
            },
            text = { 
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        stringResource(com.ai.assistance.operit.terminal.R.string.reset_dialog_description),
                        color = SettingsTheme.onSurfaceColor,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(com.ai.assistance.operit.terminal.R.string.reset_dialog_item1), color = SettingsTheme.onSurfaceColor)
                    Text(stringResource(com.ai.assistance.operit.terminal.R.string.reset_dialog_item2), color = SettingsTheme.onSurfaceColor)
                    Text(stringResource(com.ai.assistance.operit.terminal.R.string.reset_dialog_item3), color = SettingsTheme.onSurfaceColor)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(com.ai.assistance.operit.terminal.R.string.reset_dialog_warning),
                        color = SettingsTheme.errorColor
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(com.ai.assistance.operit.terminal.R.string.reset_dialog_ftp_warning),
                        color = SettingsTheme.errorColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = !isClearingCache && !isManagingFtpServer && !isUnmountingChrootMounts,
                    onClick = {
                        viewModel.clearCache()
                        showClearCacheDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.errorColor)
                ) {
                    Text(stringResource(com.ai.assistance.operit.terminal.R.string.reset_confirm), color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showClearCacheDialog = false },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = SettingsTheme.onSurfaceVariant
                    ),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SettingsTheme.onSurfaceVariant)
                ) {
                    Text(stringResource(com.ai.assistance.operit.terminal.R.string.dialog_cancel), color = SettingsTheme.onSurfaceVariant)
                }
            },
            containerColor = SettingsTheme.surfaceColor
        )
    }

    if (showUnmountConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showUnmountConfirmDialog = false },
            title = { Text(stringResource(com.ai.assistance.operit.terminal.R.string.chroot_mount_unmount)) },
            text = { Text(stringResource(com.ai.assistance.operit.terminal.R.string.chroot_unmount_confirmation)) },
            confirmButton = {
                TextButton(enabled = !isClearingCache && !isManagingFtpServer && !isUnmountingChrootMounts && !isInspectingChrootMounts,
                    onClick = { viewModel.unmountChrootMounts(); showUnmountConfirmDialog = false }) {
                    Text(stringResource(com.ai.assistance.operit.terminal.R.string.confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { showUnmountConfirmDialog = false }) { Text(stringResource(com.ai.assistance.operit.terminal.R.string.cancel)) } },
        )
    }

    if (showVirtualKeyboardDialog) {
        VirtualKeyboardCustomizationDialog(
            initialLayout = virtualKeyboardLayout,
            onDismiss = { showVirtualKeyboardDialog = false },
            onConfirm = { layout, expected ->
                viewModel.saveVirtualKeyboardLayout(layout, expected)
                showVirtualKeyboardDialog = false
            }
        )
    }
    
    // 源选择弹窗
    showSourceDialogFor?.let { pm ->
        val config = sourceConfigs[pm]
        if (config != null) {
            SourceSelectionDialog(
                context = context,
                packageManager = pm,
                config = config,
                onDismiss = { showSourceDialogFor = null },
                onSourceSelected = { sourceId ->
                    viewModel.updateSource(pm, sourceId)
                    showSourceDialogFor = null
                },
                onAddCustomSource = { name, url ->
                    viewModel.addCustomSource(pm, name, url)
                },
                onDeleteCustomSource = { sourceId ->
                    viewModel.deleteCustomSource(pm, sourceId)
                }
            )
        }
    }
}

@Composable
private fun SettingsItem(
    title: String,
    enabled: Boolean = true,
    subtitle: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.ChevronRight
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = SettingsTheme.onSurfaceColor, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = subtitle, color = SettingsTheme.onSurfaceVariant, fontSize = 14.sp)
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = SettingsTheme.primaryColor
        )
    }
}
