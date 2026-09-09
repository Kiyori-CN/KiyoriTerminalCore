package com.ai.assistance.operit.terminal.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import android.util.Log
import androidx.compose.material3.MaterialTheme
import com.ai.assistance.operit.terminal.utils.VirtualKeyboardConflictException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.terminal.R
import com.ai.assistance.operit.terminal.utils.VirtualKeyAction
import com.ai.assistance.operit.terminal.utils.VirtualKeyboardButtonConfig
import com.ai.assistance.operit.terminal.utils.VirtualKeyboardConfigManager
import com.ai.assistance.operit.terminal.utils.VirtualKeyboardLayoutConfig

@Composable
fun VirtualKeyboardCustomizationDialog(
    initialLayout: VirtualKeyboardLayoutConfig,
    onDismiss: () -> Unit,
    onConfirm: suspend (VirtualKeyboardLayoutConfig, VirtualKeyboardLayoutConfig) -> Unit
) {
    val openedLayout = remember { initialLayout }
    var draftButtons by remember { mutableStateOf(openedLayout.rows.flatten()) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

    fun updateButton(index: Int, block: (VirtualKeyboardButtonConfig) -> VirtualKeyboardButtonConfig) {
        val current = draftButtons.toMutableList()
        current[index] = block(current[index])
        draftButtons = current
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.virtual_keyboard_dialog_title),
                    modifier = Modifier.weight(1f),
                    color = SettingsTheme.onSurfaceColor,
                    fontWeight = FontWeight.Bold
                )
                TextButton(
                    enabled = !saving,
                    onClick = {
                        draftButtons = VirtualKeyboardConfigManager.defaultLayout().rows.flatten()
                    }
                ) {
                    Text(stringResource(R.string.virtual_keyboard_reset_default), color = SettingsTheme.primaryColor)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                saveError?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                Text(
                    text = stringResource(R.string.virtual_keyboard_value_hint),
                    color = SettingsTheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))

                repeat(VirtualKeyboardLayoutConfig.ROW_COUNT) { rowIndex ->
                    val rowTitle = if (rowIndex == 0) {
                        stringResource(R.string.virtual_keyboard_row_one)
                    } else {
                        stringResource(R.string.virtual_keyboard_row_two)
                    }

                    Text(
                        text = rowTitle,
                        color = SettingsTheme.onSurfaceColor,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    repeat(VirtualKeyboardLayoutConfig.COLUMN_COUNT) { columnIndex ->
                        val index = rowIndex * VirtualKeyboardLayoutConfig.COLUMN_COUNT + columnIndex
                        val key = draftButtons[index]
                        VirtualKeyboardKeyEditor(
                            keyIndex = columnIndex + 1,
                            keyConfig = key,
                            enabled = !saving,
                            onLabelChange = { value ->
                                updateButton(index) { it.copy(label = value) }
                            },
                            onValueChange = { value ->
                                updateButton(index) { it.copy(value = value) }
                            },
                            onActionChange = { action ->
                                updateButton(index) { current ->
                                    // 非发送动作忽略 value，但切回发送动作时保留用户草稿。
                                    current.copy(action = action)
                                }
                            }
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    if (!saving) {
                        saving = true
                        saveError = null
                        val submitted = VirtualKeyboardLayoutConfig(rows = draftButtons.chunked(VirtualKeyboardLayoutConfig.COLUMN_COUNT))
                        scope.launch {
                            try { onConfirm(submitted, openedLayout) }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: VirtualKeyboardConflictException) { saveError = R.string.terminal_keyboard_changed }
                            catch (error: Exception) {
                                Log.e("VirtualKeyboardDialog", "Unable to save keyboard (${error.javaClass.simpleName})")
                                saveError = R.string.terminal_settings_save_failed
                            } finally { saving = false }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = SettingsTheme.primaryColor)
            ) { Text(stringResource(if (saving) R.string.ssh_config_saving else R.string.confirm)) }
        },
        dismissButton = {
            OutlinedButton(enabled = !saving, onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
        containerColor = SettingsTheme.surfaceColor
    )
}

@Composable
private fun VirtualKeyboardKeyEditor(
    keyIndex: Int,
    keyConfig: VirtualKeyboardButtonConfig,
    enabled: Boolean,
    onLabelChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onActionChange: (VirtualKeyAction) -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = "$keyIndex",
            color = SettingsTheme.onSurfaceVariant,
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = keyConfig.label,
                enabled = enabled,
                onValueChange = onLabelChange,
                modifier = Modifier.weight(1f),
                label = { Text(stringResource(R.string.virtual_keyboard_key_label)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SettingsTheme.onSurfaceColor,
                    unfocusedTextColor = SettingsTheme.onSurfaceColor,
                    focusedBorderColor = SettingsTheme.primaryColor,
                    unfocusedBorderColor = SettingsTheme.onSurfaceVariant
                )
            )
            OutlinedButton(
                enabled = enabled,
                onClick = { onActionChange(keyConfig.action.next()) },
                modifier = Modifier.width(102.dp)
            ) {
                Text(
                    text = actionLabel(context, keyConfig.action),
                    maxLines = 1
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        OutlinedTextField(
            value = keyConfig.value,
            onValueChange = onValueChange,
            enabled = enabled && keyConfig.action == VirtualKeyAction.SEND_TEXT,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.virtual_keyboard_key_value)) },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = SettingsTheme.onSurfaceColor,
                unfocusedTextColor = SettingsTheme.onSurfaceColor,
                focusedBorderColor = SettingsTheme.primaryColor,
                unfocusedBorderColor = SettingsTheme.onSurfaceVariant
            )
        )
    }
}

private fun actionLabel(context: android.content.Context, action: VirtualKeyAction): String {
    return when (action) {
        VirtualKeyAction.SEND_TEXT -> context.getString(R.string.virtual_keyboard_action_send)
        VirtualKeyAction.TOGGLE_CTRL -> context.getString(R.string.virtual_keyboard_action_ctrl)
        VirtualKeyAction.TOGGLE_ALT -> context.getString(R.string.virtual_keyboard_action_alt)
    }
}

private fun VirtualKeyAction.next(): VirtualKeyAction {
    return when (this) {
        VirtualKeyAction.SEND_TEXT -> VirtualKeyAction.TOGGLE_CTRL
        VirtualKeyAction.TOGGLE_CTRL -> VirtualKeyAction.TOGGLE_ALT
        VirtualKeyAction.TOGGLE_ALT -> VirtualKeyAction.SEND_TEXT
    }
}
