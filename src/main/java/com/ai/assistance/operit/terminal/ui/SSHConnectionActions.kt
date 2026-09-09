package com.ai.assistance.operit.terminal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.terminal.R
import com.ai.assistance.operit.terminal.provider.type.TerminalType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** 当前环境由 TerminalManager 提供，设置只描述下一次连接的选择。 */
@Composable
internal fun SSHConnectionActions(viewModel: SettingsViewModel, enabled: Boolean) {
    val active by viewModel.activeEnvironmentType.collectAsState()
    val loadError by viewModel.sshLoadError.collectAsState()
    val busy by viewModel.sshBusy.collectAsState()
    var confirmApply by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(when (active) {
            TerminalType.SSH -> R.string.ssh_active_remote
            TerminalType.LOCAL -> R.string.ssh_active_local
            else -> R.string.ssh_active_none
        }), style = MaterialTheme.typography.labelLarge)
        Text(stringResource(R.string.ssh_saved_connection_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (loadError) {
            Text(stringResource(R.string.ssh_load_failed), color = MaterialTheme.colorScheme.error)
            TextButton(enabled = enabled && !pending && !busy, onClick = viewModel::loadSSHConfigs) {
                Text(stringResource(R.string.ssh_reload_configuration))
            }
            TextButton(enabled = enabled && !pending && !busy, onClick = { failed = false; confirmRemove = true }) {
                Text(stringResource(R.string.ssh_remove_unreadable))
            }
        }
        OutlinedButton(enabled = enabled && !busy && !pending, onClick = { failed = false; confirmApply = true }) {
            Text(stringResource(R.string.ssh_apply_connection))
        }
    }
    if (confirmApply || confirmRemove) {
        AlertDialog(
            onDismissRequest = { if (!pending) { confirmApply = false; confirmRemove = false } },
            title = { Text(stringResource(if (confirmRemove) R.string.ssh_remove_unreadable else R.string.ssh_apply_connection)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(if (confirmRemove) R.string.ssh_delete_connection_note else R.string.ssh_apply_confirmation))
                    if (failed) Text(stringResource(R.string.ssh_connection_action_failed), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = enabled && !pending, onClick = {
                    pending = true
                    failed = false
                    val remove = confirmRemove
                    scope.launch {
                        try {
                            if (remove) viewModel.deleteSSHConfig() else viewModel.applyConnectionSettings()
                            confirmApply = false
                            confirmRemove = false
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed = true }
                        finally { pending = false }
                    }
                }) { Text(stringResource(if (pending) R.string.ssh_config_saving else R.string.ssh_confirm_action)) }
            },
            dismissButton = {
                TextButton(enabled = !pending, onClick = { confirmApply = false; confirmRemove = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}
