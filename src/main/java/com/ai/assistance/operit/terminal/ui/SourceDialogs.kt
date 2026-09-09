package com.ai.assistance.operit.terminal.ui

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ai.assistance.operit.terminal.R
import com.ai.assistance.operit.terminal.data.MirrorSource
import com.ai.assistance.operit.terminal.data.PackageManagerType
import com.ai.assistance.operit.terminal.data.SourceConfig
import com.ai.assistance.operit.terminal.utils.validMirrorSourceUrl
import com.ai.assistance.operit.terminal.utils.mirrorSourceDisplayUrl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun SourceSelectionDialog(
    context: Context,
    packageManager: PackageManagerType,
    config: SourceConfig,
    onDismiss: () -> Unit,
    onSourceSelected: suspend (String) -> Unit,
    onAddCustomSource: suspend (String, String) -> Unit,
    onDeleteCustomSource: suspend (String) -> Unit,
) {
    var selectedId by remember { mutableStateOf(config.selectedSourceId) }
    var showAdd by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<MirrorSource?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(config.sources, config.selectedSourceId) {
        if (config.sources.none { it.id == selectedId }) selectedId = config.selectedSourceId
    }
    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = false
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                Log.e("SourceSelectionDialog", "Unable to persist source settings", failure)
                error = true
            } finally { busy = false }
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy && !showAdd && pendingDelete == null) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(context.getString(R.string.select_source_title, packageManager.displayName), Modifier.weight(1f))
                IconButton(enabled = !busy, onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, context.getString(R.string.add))
                }
            }
        },
        text = {
            Column {
                Text(context.getString(R.string.source_application_timing), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error) Text(context.getString(R.string.source_operation_failed), color = MaterialTheme.colorScheme.error)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 8.dp))
                LazyColumn(Modifier.heightIn(max = 380.dp).selectableGroup()) {
                    items(config.sources, key = { it.id }) { source ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .selectable(selected = selectedId == source.id, enabled = !busy,
                                    role = Role.RadioButton, onClick = { selectedId = source.id }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selectedId == source.id, onClick = null, enabled = !busy,
                                modifier = Modifier.padding(end = 12.dp))
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                Text(source.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(mirrorSourceDisplayUrl(source.url), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (source.id.startsWith("custom_")) {
                                IconButton(enabled = !busy, onClick = { error = false; pendingDelete = source }) {
                                    Icon(Icons.Default.Delete, context.getString(R.string.delete_source), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && config.sources.any { it.id == selectedId }, onClick = {
                val submittedId = selectedId
                perform { onSourceSelected(submittedId) }
            }) { Text(context.getString(R.string.confirm)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(context.getString(R.string.cancel)) } },
    )
    pendingDelete?.let { source ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingDelete = null },
            title = { Text(context.getString(R.string.delete_source)) },
            text = {
                Column {
                    Text(context.getString(R.string.source_delete_confirmation, source.name))
                    if (error) Text(context.getString(R.string.source_operation_failed), color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    perform { onDeleteCustomSource(source.id); pendingDelete = null }
                }) { Text(context.getString(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pendingDelete = null }) { Text(context.getString(R.string.cancel)) } },
        )
    }
    if (showAdd) AddCustomSourceDialog(context, packageManager, onDismiss = { showAdd = false }, onConfirm = { name, url ->
        onAddCustomSource(name, url)
        showAdd = false
    })
}

@Composable
private fun AddCustomSourceDialog(
    context: Context,
    packageManager: PackageManagerType,
    onDismiss: () -> Unit,
    onConfirm: suspend (String, String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val validName = name.isNotBlank() && name.none { it.isISOControl() }
    val validUrl = validMirrorSourceUrl(url.trim())
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(context.getString(R.string.add_custom_source_title, packageManager.displayName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (error) Text(context.getString(R.string.source_operation_failed), color = MaterialTheme.colorScheme.error)
                OutlinedTextField(value = name, onValueChange = { name = it }, enabled = !busy,
                    label = { Text(context.getString(R.string.source_name_label)) }, singleLine = true,
                    isError = name.isNotEmpty() && !validName, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(value = url, onValueChange = { url = it }, enabled = !busy,
                    label = { Text(context.getString(R.string.source_url_label)) }, singleLine = true,
                    isError = url.isNotEmpty() && !validUrl,
                    supportingText = { Text(context.getString(R.string.source_url_requirements)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && validName && validUrl, onClick = {
                if (!busy) {
                    busy = true
                    error = false
                    val submittedName = name.trim()
                    val submittedUrl = url.trim()
                    scope.launch {
                        try { onConfirm(submittedName, submittedUrl) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            Log.e("AddCustomSourceDialog", "Unable to save custom mirror", failure)
                            error = true
                        } finally { busy = false }
                    }
                }
            }) { Text(context.getString(if (busy) R.string.ssh_config_saving else R.string.add)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(context.getString(R.string.cancel)) } },
    )
}
