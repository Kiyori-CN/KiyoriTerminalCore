package com.ai.assistance.operit.terminal.ui

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.terminal.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TerminalValueSettingDialog(
    initialValue: String,
    title: String,
    label: String,
    hint: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    validate: (String) -> Boolean,
    onDismiss: () -> Unit,
    onConfirm: suspend (String) -> Unit,
) {
    var value by rememberSaveable { mutableStateOf(initialValue) }
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val valid = validate(value)
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = value, onValueChange = { value = it; failed = false },
                    enabled = !saving, singleLine = true, isError = !valid,
                    label = { Text(label) }, supportingText = { Text(hint) },
                    keyboardOptions = KeyboardOptions(keyboardType = keyboardType), modifier = Modifier.fillMaxWidth())
                if (failed) Text(stringResource(R.string.terminal_settings_save_failed), color = MaterialTheme.colorScheme.error)
                if (saving) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !saving, onClick = {
                if (!saving && valid) {
                    saving = true
                    failed = false
                    val submitted = value
                    scope.launch {
                        try { onConfirm(submitted); onDismiss() }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) {
                            Log.e("TerminalValueSettingDialog", "Unable to save setting (${error.javaClass.simpleName})")
                            failed = true
                        } finally { saving = false }
                    }
                }
            }) { Text(stringResource(if (saving) R.string.ssh_config_saving else R.string.confirm)) }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
