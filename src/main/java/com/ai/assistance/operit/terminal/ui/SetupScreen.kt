package com.ai.assistance.operit.terminal.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ai.assistance.operit.terminal.CommandExecutionEvent
import com.ai.assistance.operit.terminal.TerminalEnvironmentContract
import com.ai.assistance.operit.terminal.TerminalManager
import com.ai.assistance.operit.terminal.data.PackageManagerType
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import com.ai.assistance.operit.terminal.utils.SourceManager
import android.content.Context
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.CancellationException

enum class InstallStatus {
    CHECKING,
    INSTALLED,
    NOT_INSTALLED,
    NEEDS_CONFIGURATION,
    UNKNOWN,
}

data class PackageItem(
    val id: String,
    val name: String,
    val command: String,
    val description: String = ""
)

data class PackageCategory(
    val id: String,
    val name: String,
    val description: String,
    val packages: List<PackageItem>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onBack: () -> Unit,
    onSetup: (List<String>) -> Unit,
    setupInProgress: Boolean = false,
) {
    val context = LocalContext.current
    val rootView = LocalView.current
    val sourceManager = remember { SourceManager(context) }

    // Setup has no text input. Clear the previous terminal connection on entry so a delayed
    // SurfaceView focus callback cannot reopen the IME over the environment page.
    DisposableEffect(rootView) {
        rootView.clearFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(rootView.windowToken, 0)
        onDispose { }
    }
    
    // 检查SSH是否启用
    var isSSHEnabled by remember { mutableStateOf(false) }
    
    
    // 资源值必须在 Composable 作用域中解析，避免把旧 Locale 文案缓存进 remember 状态。
    val packageCategories =
        listOf(
            PackageCategory(
                id = "nodejs",
                name = stringResource(com.ai.assistance.operit.terminal.R.string.category_nodejs_name),
                description = stringResource(com.ai.assistance.operit.terminal.R.string.category_nodejs_desc),
                packages =
                    listOf(
                        PackageItem(
                            "nodejs",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_nodejs_name),
                            TerminalEnvironmentContract.buildNodeJsInstallCommand(),
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_nodejs_desc),
                        ),
                        PackageItem(
                            "pnpm",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_pnpm_name),
                            "typescript",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_pnpm_desc),
                        ),
                    ),
            ),
            PackageCategory(
                id = "python",
                name = stringResource(com.ai.assistance.operit.terminal.R.string.category_python_name),
                description = stringResource(com.ai.assistance.operit.terminal.R.string.category_python_desc),
                packages =
                    listOf(
                        PackageItem(
                            "python-is-python3",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_python_link_name),
                            "python-is-python3",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_python_link_desc),
                        ),
                        PackageItem(
                            "python3-venv",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_python_venv_name),
                            "python3-venv",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_python_venv_desc),
                        ),
                        PackageItem(
                            "python3-pip",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_python_pip_name),
                            "python3-pip",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_python_pip_desc),
                        ),
                        PackageItem(
                            "uv",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_uv_name),
                            "pipx install uv",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_uv_desc),
                        ),
                    ),
            ),
            PackageCategory(
                id = "ssh",
                name = stringResource(com.ai.assistance.operit.terminal.R.string.category_ssh_name),
                description = stringResource(com.ai.assistance.operit.terminal.R.string.category_ssh_desc),
                packages =
                    listOf(
                        PackageItem(
                            "ssh",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_ssh_client_name),
                            "ssh",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_ssh_client_desc),
                        ),
                        PackageItem(
                            "sshpass",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_sshpass_name),
                            "sshpass",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_sshpass_desc),
                        ),
                        PackageItem(
                            "openssh-server",
                            "OpenSSH 服务器",
                            "openssh-server",
                            "用于反向隧道挂载本地文件系统",
                        ),
                    ),
            ),
            PackageCategory(
                id = "java",
                name = stringResource(com.ai.assistance.operit.terminal.R.string.category_java_name),
                description = stringResource(com.ai.assistance.operit.terminal.R.string.category_java_desc),
                packages =
                    listOf(
                        PackageItem(
                            TerminalEnvironmentContract.OPENJDK_PACKAGE_ID,
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_openjdk_name),
                            TerminalEnvironmentContract.OPENJDK_APT_PACKAGE,
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_openjdk_desc),
                        ),
                        PackageItem(
                            "gradle",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_gradle_name),
                            TerminalEnvironmentContract.buildGradleInstallCommand(),
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_gradle_desc),
                        ),
                    ),
            ),
            PackageCategory(
                id = "rust",
                name = stringResource(com.ai.assistance.operit.terminal.R.string.category_rust_name),
                description = stringResource(com.ai.assistance.operit.terminal.R.string.category_rust_desc),
                packages =
                    listOf(
                        PackageItem(
                            "rust",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_rust_name),
                            "RUST_INSTALL_COMMAND",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_rust_desc),
                        ),
                    ),
            ),
            PackageCategory(
                id = "go",
                name = stringResource(com.ai.assistance.operit.terminal.R.string.category_go_name),
                description = stringResource(com.ai.assistance.operit.terminal.R.string.category_go_desc),
                packages =
                    listOf(
                        PackageItem(
                            "go",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_go_name),
                            "golang-go",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_go_desc),
                        ),
                    ),
            ),
            PackageCategory(
                id = "ruby",
                name = stringResource(com.ai.assistance.operit.terminal.R.string.category_ruby_name),
                description = stringResource(com.ai.assistance.operit.terminal.R.string.category_ruby_desc),
                packages =
                    listOf(
                        PackageItem(
                            TerminalEnvironmentContract.RUBY_PACKAGE_ID,
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_ruby_name),
                            TerminalEnvironmentContract.RUBY_APT_PACKAGE,
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_ruby_desc),
                        ),
                    ),
            ),
        )

    // 跟踪每个分类的展开状态
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }
    
    // 跟踪选中的包
    val selectedPackages = remember { mutableStateMapOf<String, Boolean>() }
    
    // 新增：跟踪包的安装状态
    val packageStatus = remember { mutableStateMapOf<String, InstallStatus>() }
    val terminalManager = remember(context) { TerminalManager.getInstance(context) }
    var refreshGeneration by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf<SetupEnvironmentTarget?>(null) }
    var probeDetails by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var probeRunning by remember { mutableStateOf(true) }
    // 包检测使用一次结构化 hidden probe，不能抢占用户当前可见 PTY 会话或把探测命令加入用户队列。
    LaunchedEffect(terminalManager, refreshGeneration) {
        probeRunning = true
        target = null
        probeDetails = emptyMap()
        val allPackages = packageCategories.flatMap { it.packages }
        allPackages.forEach { pkg ->
            packageStatus[pkg.id] = InstallStatus.CHECKING
        }

        val result = try {
            isSSHEnabled = terminalManager.usesSshEnvironment()
            terminalManager.executeHiddenCommand(
                command = packageProbeCommand(allPackages),
                executorKey = "environment-setup-check",
                timeoutMs = 60_000L,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("SetupScreen", "Failed to inspect environment packages", error)
            HiddenExecResult(
                output = "",
                exitCode = -1,
                state = HiddenExecResult.State.EXECUTION_ERROR,
                error = error.message ?: "environment probe failed",
            )
        }
        val statuses = packageProbeStatuses(result, allPackages)
        target = setupEnvironmentTarget(result)
        probeDetails = packageProbeDetails(result)
        allPackages.forEach { pkg ->
            val status = statuses[pkg.id] ?: InstallStatus.UNKNOWN
            packageStatus[pkg.id] = status
            if (status == InstallStatus.INSTALLED) {
                selectedPackages[pkg.id] = true
            }
        }
        probeRunning = false
    }

    var showSetupDialog by remember { mutableStateOf(false) }
    var setupSubmitted by remember { mutableStateOf(false) }
    val commandsToRun = remember { mutableStateOf<List<String>>(emptyList()) }

    if (showSetupDialog) {
        AlertDialog(
            onDismissRequest = { showSetupDialog = false },
            title = { Text(stringResource(com.ai.assistance.operit.terminal.R.string.setup_dialog_title)) },
            text = { Text(stringResource(com.ai.assistance.operit.terminal.R.string.setup_dialog_message) +
                "\n${target?.user}@${target?.host} · ${target?.home}\n" +
                stringResource(com.ai.assistance.operit.terminal.R.string.setup_changes_notice)) },
            confirmButton = {
                Button(
                    enabled = !setupSubmitted,
                    onClick = {
                        if (!setupSubmitted) {
                            setupSubmitted = true
                            showSetupDialog = false
                            onSetup(commandsToRun.value)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(stringResource(com.ai.assistance.operit.terminal.R.string.dialog_confirm), color = MaterialTheme.colorScheme.onPrimary)
                }
            },
            dismissButton = {
                Button(
                    onClick = { showSetupDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Text(stringResource(com.ai.assistance.operit.terminal.R.string.dialog_cancel), color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
    ) {
        // 标题
        Text(
            text = stringResource(com.ai.assistance.operit.terminal.R.string.setup_title),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Text(
            text = stringResource(com.ai.assistance.operit.terminal.R.string.setup_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // SSH模式警告横幅
        run {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚠️",
                        fontSize = 20.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Column {
                        Text(
                            text = stringResource(if (isSSHEnabled) com.ai.assistance.operit.terminal.R.string.setup_target_ssh else com.ai.assistance.operit.terminal.R.string.setup_target_local),
                            color = MaterialTheme.colorScheme.tertiary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = target?.let { "${it.user}@${it.host} · ${it.system} ${it.architecture}\n${it.home}\n" +
                                stringResource(if (it.canInstall) com.ai.assistance.operit.terminal.R.string.setup_target_supported else com.ai.assistance.operit.terminal.R.string.setup_target_unsupported) }
                                ?: stringResource(if (probeRunning) com.ai.assistance.operit.terminal.R.string.setup_detecting else com.ai.assistance.operit.terminal.R.string.setup_probe_failed),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
                }
                TextButton(onClick = { refreshGeneration++ }, enabled = !probeRunning && !setupSubmitted) {
                    Text(stringResource(com.ai.assistance.operit.terminal.R.string.setup_refresh))
                }
            }
        }

        // 包分类列表
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(packageCategories) { category ->
                CategoryCard(
                    category = category.copy(packages = category.packages.map { pkg ->
                        pkg.copy(description = listOfNotNull(pkg.description, probeDetails[pkg.id]?.takeIf(String::isNotBlank)).joinToString("\n"))
                    }),
                    isExpanded = expandedCategories[category.id] ?: false,
                    onExpandToggle = { expandedCategories[category.id] = !expandedCategories.getOrDefault(category.id, false) },
                    selectedPackages = selectedPackages,
                    selectionEnabled = !probeRunning && !setupSubmitted && !setupInProgress,
                    packageStatus = packageStatus
                )
            }
        }

        // 底部按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = onBack,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Text(stringResource(com.ai.assistance.operit.terminal.R.string.skip), color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            
            Button(
                enabled = !setupInProgress && !probeRunning && !setupSubmitted && target?.canInstall == true &&
                    setupSelectionResolved(selectedPackages, packageStatus) &&
                    selectedPackages.any { (id, selected) -> selected && packageStatus[id] != InstallStatus.INSTALLED },
                onClick = {
                    val installTarget = target ?: return@Button
                    val commands = mutableListOf<String>()
                    
                    // 收集选中的包
                    val selectedAptPackages = mutableListOf<String>()
                    val selectedNpmPackages = mutableListOf<String>()
                    val selectedCustomCommands = mutableListOf<String>()
                    val shouldInstallUv =
                        selectedPackages.getOrDefault("uv", false) &&
                            packageStatus["uv"] != InstallStatus.INSTALLED
                    val shouldInstallRust =
                        selectedPackages.getOrDefault("rust", false) &&
                            packageStatus["rust"] != InstallStatus.INSTALLED
                    val shouldInstallGradle =
                        selectedPackages.getOrDefault("gradle", false) &&
                            packageStatus["gradle"] != InstallStatus.INSTALLED
                    val shouldInstallOpenJdkForGradle =
                        openJdkRequiredByGradle(selectedPackages, packageStatus)
                    // pnpm is installed through npm, so selecting it must also provision a
                    // usable Node.js runtime when the capability probe says Node is missing or
                    // inconclusive. Otherwise the generated npm command fails immediately.
                    val nodeJsRequiredByPnpm =
                        nodeJsRequiredByPnpm(selectedPackages, packageStatus)
                    if (nodeJsRequiredByPnpm && selectedPackages["nodejs"] != true) {
                        selectedCustomCommands.add(TerminalEnvironmentContract.buildNodeJsInstallCommand())
                    }
                    
                    packageCategories.forEach { category ->
                        category.packages.forEach { pkg ->
                            if (selectedPackages[pkg.id] == true && packageStatus[pkg.id] != InstallStatus.INSTALLED) {
                                // 根据分类和包ID判断包管理器
                                if (pkg.id == "rust") {
                                    // 获取当前选择的Rust镜像源
                                    val rustSource = sourceManager.getSelectedSource(PackageManagerType.RUST)
                                    val rustEnvCommand = sourceManager.getRustSourceEnvCommand(rustSource)
                                    // 添加环境变量设置和安装命令
                                    selectedCustomCommands.add("$rustEnvCommand && curl -v --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y")
                                } else if (pkg.id == "uv") {
                                    selectedCustomCommands.add("PIP_INDEX_URL=" +
                                        TerminalEnvironmentContract.shellQuote(sourceManager.getSelectedSource(PackageManagerType.PIP).url) +
                                        " pipx install uv")
                                } else if (pkg.id == "nodejs" || pkg.id == "gradle") {
                                    selectedCustomCommands.add(pkg.command)
                                } else if (category.id == "nodejs" && pkg.id != "nodejs") {
                                    selectedNpmPackages.add(pkg.command)
                                } else {
                                    selectedAptPackages.add(pkg.command)
                                }
                            }
                        }
                    }

                    // 添加 pipx 作为 uv 的依赖
                    if (shouldInstallUv) {
                        selectedAptPackages.add("pipx")
                    }

                    // 首先安装所有依赖包
                    val allAptDeps = mutableSetOf<String>()
                    
                    // 添加自定义命令的依赖
                    if (selectedCustomCommands.isNotEmpty()) {
                        if (shouldInstallRust) {
                            allAptDeps.add("curl")
                            allAptDeps.add("build-essential")
                        }
                        if (
                            selectedPackages.getOrDefault("nodejs", false) ||
                                nodeJsRequiredByPnpm
                        ) {
                            allAptDeps.add("curl")
                            allAptDeps.add("xz-utils")
                            allAptDeps.add("ca-certificates")
                        }
                        if (shouldInstallGradle) {
                            allAptDeps.add("unzip")
                            allAptDeps.add("curl")
                            allAptDeps.add("ca-certificates")
                        }
                        if (shouldInstallOpenJdkForGradle) {
                            allAptDeps.add(TerminalEnvironmentContract.OPENJDK_APT_PACKAGE)
                        }
                    }
                    
                    // 添加选中的 apt 包
                    allAptDeps.addAll(selectedAptPackages)
                    
                    // 使用 apt 安装所有 apt 包和依赖
                    if (allAptDeps.isNotEmpty()) {
                        commands.add(installTarget.aptCommand("DEBIAN_FRONTEND=noninteractive apt-get update"))
                        commands.add(installTarget.aptCommand(TerminalEnvironmentContract.buildAptInstallCommand(allAptDeps)))
                    }
                    
                    // 然后运行自定义命令（如安装 rust, uv, nodejs 等）
                    if (selectedCustomCommands.isNotEmpty()) {
                        commands.addAll(selectedCustomCommands)

                        // 持久化未来 shell 的 PATH，并显式激活当前安装会话；不要依赖重新加载整份 profile。
                        if (shouldInstallUv) {
                            commands.addAll(TerminalEnvironmentContract.PIPX_POST_INSTALL_COMMANDS)
                        }
                        // rustup 把工具链放在用户目录，当前 shell 必须与后续 hidden probe 使用同一路径。
                        if (shouldInstallRust) {
                            commands.addAll(TerminalEnvironmentContract.RUSTUP_POST_INSTALL_COMMANDS)
                        }
                        if (selectedCustomCommands.any { command ->
                                command == TerminalEnvironmentContract.buildNodeJsInstallCommand() ||
                                    command == TerminalEnvironmentContract.buildGradleInstallCommand()
                            }) {
                            commands.add("export PATH=\"${'$'}HOME/.local/bin:${'$'}PATH\"")
                        }
                    }
                    
                    // 安装 NPM 包（如果 nodejs 已经安装或被选中）
                    if (selectedNpmPackages.isNotEmpty()) {
                        commands.addAll(
                            TerminalEnvironmentContract.buildNodePackageSetupCommands(
                                selectedNpmPackages,
                                sourceManager.getSelectedSource(PackageManagerType.NPM).url,
                            )
                        )
                    }
                    
                    // 最后用与页面相同的真实能力探针验收所有选中项，失败必须回报非零退出码。
                    packageCategories.flatMap { it.packages }.filter { selectedPackages[it.id] == true }.forEach { pkg ->
                        commands.add(TerminalEnvironmentContract.bashScript(
                            "set -e; ${TerminalEnvironmentContract.TOOL_PATH_COMMAND}; ${packageCheckCommand(pkg)}"
                        ))
                    }
                    commandsToRun.value = installTarget.bindCommands(commands)
                    showSetupDialog = true
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Text(stringResource(com.ai.assistance.operit.terminal.R.string.start_setup), color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun CategoryCard(
    category: PackageCategory,
    isExpanded: Boolean,
    onExpandToggle: () -> Unit,
    selectedPackages: MutableMap<String, Boolean>,
    selectionEnabled: Boolean,
    packageStatus: Map<String, InstallStatus>
) {
    // 全选是当前包选择的投影，不再维护会与单项选择/检测结果漂移的第二份状态。
    val selectablePackages = category.packages.filter {
        packageStatus[it.id] == InstallStatus.NOT_INSTALLED || packageStatus[it.id] == InstallStatus.NEEDS_CONFIGURATION
    }
    val selectedCount = selectablePackages.count { selectedPackages[it.id] == true }
    val selectionState = when {
        selectedCount == 0 -> ToggleableState.Off
        selectedCount == selectablePackages.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
    }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // 分类标题行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandToggle() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    // 标题 - 第一行
                    Text(
                        text = category.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    // Kiyori 必须标签 - 第二行
                    if (category.id == "nodejs" || category.id == "python") {
                        Text(
                            text = "(${stringResource(com.ai.assistance.operit.terminal.R.string.kiyori_required)})",
                            color = MaterialTheme.colorScheme.tertiary, // Orange color
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    // 描述 - 第三行
                    Text(
                        text = category.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                
                // 全选按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    TriStateCheckbox(
                        state = selectionState,
                        enabled = selectionEnabled && selectablePackages.isNotEmpty(),
                        onClick = {
                            val selectAll = selectionState != ToggleableState.On
                            selectablePackages.forEach { pkg -> selectedPackages[pkg.id] = selectAll }
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = MaterialTheme.colorScheme.primary,
                            uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Text(
                        text = stringResource(com.ai.assistance.operit.terminal.R.string.select_all),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                
                // 展开/收起图标
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) stringResource(com.ai.assistance.operit.terminal.R.string.collapse) else stringResource(com.ai.assistance.operit.terminal.R.string.expand),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            
            // 包列表（可展开）
            if (isExpanded) {
                Spacer(modifier = Modifier.height(12.dp))
                
                category.packages.forEach { pkg ->
                    PackageItem(
                        packageItem = pkg,
                        isSelected = selectedPackages[pkg.id] ?: false,
                        onSelectionChange = { selected ->
                            selectedPackages[pkg.id] = selected
                        },
                        selectionEnabled = selectionEnabled,
                        status = packageStatus[pkg.id] ?: InstallStatus.CHECKING
                    )
                }
            }
        }
    }
}

@Composable
private fun PackageItem(
    packageItem: PackageItem,
    isSelected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    status: InstallStatus,
    selectionEnabled: Boolean,
) {
    val context = LocalContext.current
    val isInstalled = status == InstallStatus.INSTALLED
    val isChecking = status == InstallStatus.CHECKING
    val isUnknown = status == InstallStatus.UNKNOWN
    val canSelect = selectionEnabled && (status == InstallStatus.NOT_INSTALLED || status == InstallStatus.NEEDS_CONFIGURATION)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = canSelect) { onSelectionChange(!isSelected) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isChecking) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onSurface,
                strokeWidth = 2.dp
            )
        } else {
            Checkbox(
                checked = isSelected || isInstalled,
                onCheckedChange = onSelectionChange,
                enabled = canSelect,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    disabledCheckedColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
            )
        }
        
        Column(
            modifier = Modifier.padding(start = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = packageItem.name,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                if (isInstalled) {
                    Text(
                        text = " (${stringResource(com.ai.assistance.operit.terminal.R.string.installed)})",
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                } else if (isUnknown) {
                    Text(
                        text = " (${stringResource(com.ai.assistance.operit.terminal.R.string.detection_failed)})",
                        color = MaterialTheme.colorScheme.tertiary,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                } else if (status == InstallStatus.NEEDS_CONFIGURATION) {
                    Text(
                        text = " (${stringResource(com.ai.assistance.operit.terminal.R.string.setup_needs_configuration)})",
                        color = MaterialTheme.colorScheme.tertiary, fontSize = 12.sp,
                    )
                }
            }
            if (packageItem.description.isNotEmpty()) {
                Text(
                    text = packageItem.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

internal fun packageCheckCommand(pkg: PackageItem): String = when (pkg.id) {
    "rust" ->
        "PATH=\"${TerminalEnvironmentContract.RUSTUP_BIN_DIR}:${'$'}PATH\" command -v rustc && " +
            "PATH=\"${TerminalEnvironmentContract.RUSTUP_BIN_DIR}:${'$'}PATH\" rustc --version && " +
            "PATH=\"${TerminalEnvironmentContract.RUSTUP_BIN_DIR}:${'$'}PATH\" command -v cargo && " +
            "PATH=\"${TerminalEnvironmentContract.RUSTUP_BIN_DIR}:${'$'}PATH\" cargo --version"
    "uv" ->
        "PATH=\"${TerminalEnvironmentContract.PIPX_BIN_DIR}:${'$'}PATH\" command -v uv && " +
            "PATH=\"${TerminalEnvironmentContract.PIPX_BIN_DIR}:${'$'}PATH\" uv --version"
    "nodejs" -> TerminalEnvironmentContract.NODE_RUNTIME_CHECK_COMMAND
    "pnpm" -> TerminalEnvironmentContract.NODE_TOOLCHAIN_CHECK_COMMAND
    "go" -> "command -v go && go version"
    TerminalEnvironmentContract.RUBY_PACKAGE_ID -> "command -v ruby && ruby --version"
    "ssh" -> "command -v ssh && ssh -V"
    "sshpass" -> "command -v sshpass && sshpass -V"
    "openssh-server" -> "command -v sshd && sshd -V"
    "openjdk-25" ->
        "java -version 2>&1 | grep -E 'version \"${TerminalEnvironmentContract.GRADLE_REQUIRED_JAVA_MAJOR}([.]|\")'"
    "gradle" ->
        "PATH=\"${TerminalEnvironmentContract.USER_LOCAL_BIN_DIR}:${'$'}PATH\" " +
            "gradle --version | grep -F 'Gradle ${TerminalEnvironmentContract.GRADLE_VERSION}'"
    "python-is-python3" ->
        "python --version >/dev/null 2>&1 && python3 --version >/dev/null 2>&1 && " +
            "python_path=\"${'$'}(command -v python)\" && python3_path=\"${'$'}(command -v python3)\" && " +
            "[ \"${'$'}(readlink -f \"${'$'}python_path\")\" = \"${'$'}(readlink -f \"${'$'}python3_path\")\" ]"
    "python3-venv" -> "python3 -m venv --help >/dev/null 2>&1 && python3 -c 'import venv, ensurepip'"
    "python3-pip" -> "python3 -m pip --version >/dev/null 2>&1"
    else ->
        "status=${'$'}(dpkg-query -W -f='${'$'}{Status}\\n' '${pkg.command.split(" ").first()}' 2>/dev/null) && " +
            "[ \"${'$'}status\" = 'install ok installed' ]"
}

internal fun checkPackageInstalled(result: HiddenExecResult, pkg: PackageItem): Boolean {
    if (!result.isOk || result.exitCode != 0) return false
    val output = result.output

    return when (pkg.id) {
        "nodejs" -> {
            TerminalEnvironmentContract.isNodeRuntimeReady(output)
        }
        TerminalEnvironmentContract.OPENJDK_PACKAGE_ID ->
            output.lineSequence().any { line -> line.contains("version \"${TerminalEnvironmentContract.GRADLE_REQUIRED_JAVA_MAJOR}.") }
        "python-is-python3", "python3-venv", "python3-pip" -> true
        "rust", "uv", "go", TerminalEnvironmentContract.RUBY_PACKAGE_ID, "ssh", "sshpass", "openssh-server" -> output.isNotBlank()
        "gradle" -> TerminalEnvironmentContract.isGradleReady(output)
        "pnpm" -> TerminalEnvironmentContract.isNodeToolchainReady(output)
        else -> output.lineSequence().any { line ->
            line.trim() == "install ok installed" || line.trim() == "Status: install ok installed"
        }
    }
}

private const val PACKAGE_PROBE_BEGIN_MARKER = "__KIYORI_ENV_PROBE_BEGIN__"
private const val PACKAGE_PROBE_ENTRY_PREFIX = "__KIYORI_ENV_PROBE__:"
private const val PACKAGE_PROBE_END_MARKER = "__KIYORI_ENV_PROBE_END__"
private val PACKAGE_PROBE_ID_PATTERN = Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")
private val PACKAGE_PROBE_ENTRY_PATTERN =
    Regex("^${Regex.escape(PACKAGE_PROBE_ENTRY_PREFIX)}(${PACKAGE_PROBE_ID_PATTERN.pattern}):([0124])(?::([A-Za-z0-9+/=]*))?$")

internal fun packageProbeCommand(packages: List<PackageItem>): String = buildString {
    require(packages.map(PackageItem::id).distinct().size == packages.size) {
        "Environment probe package IDs must be unique"
    }
    require(packages.all { pkg -> PACKAGE_PROBE_ID_PATTERN.matches(pkg.id) }) {
        "Environment probe package IDs must use lowercase letters, digits, and hyphens"
    }

    appendLine("set +e; set +u")
    appendLine(TerminalEnvironmentContract.TOOL_PATH_COMMAND)
    appendLine("cd \"${'$'}HOME\" || exit 1")
    appendLine(SETUP_TARGET_COMMAND)
    appendLine("printf '%s\\n' '$PACKAGE_PROBE_BEGIN_MARKER'")
    packages.chunked(4).forEach { batch ->
        batch.forEach { pkg ->
            val check = """
                ${packageDiagnosticCommand(pkg)}
                if ! ( ${packagePresenceCommand(pkg)} ) >/dev/null 2>&1; then exit 3; fi
                if ( ${packageCheckCommand(pkg)} ); then exit 0; else exit 4; fi
            """.trimIndent()
            appendLine("""
                (
                set -o pipefail
                probe_output=${'$'}(timeout --kill-after=1s 8s ${TerminalEnvironmentContract.bashScript(check)} 2>&1 | head -c 2048)
                probe_rc=${'$'}?
                case "${'$'}probe_rc" in 0) probe_status=1 ;; 3) probe_status=0 ;; 4) probe_status=4 ;; *) probe_status=2 ;; esac
                probe_encoded=${'$'}(printf '%s' "${'$'}probe_output" | base64 | tr -d '\n')
                printf '%s:%s:%s\n' '$PACKAGE_PROBE_ENTRY_PREFIX${pkg.id}' "${'$'}probe_status" "${'$'}probe_encoded"
                ) &
            """.trimIndent())
        }
        appendLine("wait")
    }
    // These must be physical LF separators. A literal backslash-n is folded into the neighboring
    // shell token and makes the complete script fail before either protocol marker can be emitted.
    appendLine("printf '%s\\n' '$PACKAGE_PROBE_END_MARKER'")
}

internal fun packageProbeStatuses(
    result: HiddenExecResult,
    packages: List<PackageItem>,
): Map<String, InstallStatus> {
    if (!result.isOk || result.exitCode != 0 || result.outputTruncated) {
        return packages.associate { pkg -> pkg.id to InstallStatus.UNKNOWN }
    }

    val lines = result.output.lineSequence().map(String::trim).toList()
    val beginIndexes = lines.indices.filter { index -> lines[index] == PACKAGE_PROBE_BEGIN_MARKER }
    val endIndexes = lines.indices.filter { index -> lines[index] == PACKAGE_PROBE_END_MARKER }
    if (beginIndexes.size != 1 || endIndexes.size != 1 || beginIndexes.single() >= endIndexes.single()) {
        return packages.associate { pkg -> pkg.id to InstallStatus.UNKNOWN }
    }
    val expectedIds = packages.map(PackageItem::id).toSet()
    val values =
        lines
            .subList(beginIndexes.single() + 1, endIndexes.single())
            .mapNotNull { line ->
                if (line.startsWith(PACKAGE_PROBE_ENTRY_PREFIX) && PACKAGE_PROBE_ENTRY_PATTERN.matchEntire(line) == null) {
                    return@mapNotNull line.removePrefix(PACKAGE_PROBE_ENTRY_PREFIX).substringBefore(':') to InstallStatus.UNKNOWN
                }
                PACKAGE_PROBE_ENTRY_PATTERN.matchEntire(line)?.let { match ->
                    val id = match.groupValues[1]
                    val status = when (match.groupValues[2]) {
                        "1" -> InstallStatus.INSTALLED
                        "0" -> InstallStatus.NOT_INSTALLED
                        "4" -> InstallStatus.NEEDS_CONFIGURATION
                        else -> InstallStatus.UNKNOWN
                    }
                    id to status
                }
            }
            .filter { (id, _) -> id in expectedIds }
            .groupBy(
                keySelector = Pair<String, InstallStatus>::first,
                valueTransform = Pair<String, InstallStatus>::second,
            )

    return packages.associate { pkg ->
        val packageValues = values[pkg.id]
        pkg.id to if (packageValues?.size == 1) packageValues.single() else InstallStatus.UNKNOWN
    }
}

internal fun nodeJsRequiredByPnpm(
    selectedPackages: Map<String, Boolean>,
    packageStatus: Map<String, InstallStatus>,
): Boolean =
    selectedPackages["pnpm"] == true && packageStatus["pnpm"] != InstallStatus.INSTALLED &&
        packageStatus["nodejs"] != InstallStatus.INSTALLED

internal fun setupSelectionResolved(selected: Map<String, Boolean>, statuses: Map<String, InstallStatus>): Boolean {
    val required = selected.filterValues { it }.keys.toMutableSet()
    if ("pnpm" in required && statuses["pnpm"] != InstallStatus.INSTALLED) required.add("nodejs")
    if ("gradle" in required && statuses["gradle"] != InstallStatus.INSTALLED) required.add("openjdk-25")
    return required.all { statuses[it] in setOf(InstallStatus.INSTALLED, InstallStatus.NOT_INSTALLED, InstallStatus.NEEDS_CONFIGURATION) }
}

internal fun openJdkRequiredByGradle(
    selectedPackages: Map<String, Boolean>,
    packageStatus: Map<String, InstallStatus>,
): Boolean =
    selectedPackages["gradle"] == true &&
        packageStatus["gradle"] != InstallStatus.INSTALLED &&
        packageStatus[TerminalEnvironmentContract.OPENJDK_PACKAGE_ID] != InstallStatus.INSTALLED

/** 完成事件只表示边界和退出状态；正文始终由增量事件承载。 */
internal fun completedCommandOutput(event: CommandExecutionEvent): String? =
    null
