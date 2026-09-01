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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
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
import com.ai.assistance.operit.terminal.utils.SSHConfigManager
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException

enum class InstallStatus {
    CHECKING,
    INSTALLED,
    NOT_INSTALLED,
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
    onSetup: (List<String>) -> Unit
) {
    val context = LocalContext.current
    val sourceManager = remember { SourceManager(context) }
    val sshConfigManager = remember { SSHConfigManager(context) }
    
    // 检查SSH是否启用
    var isSSHEnabled by remember { mutableStateOf(false) }
    
    LaunchedEffect(Unit) {
        isSSHEnabled = sshConfigManager.isEnabled()
    }
    
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
                            "openjdk-17",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_openjdk_name),
                            "openjdk-17-jdk",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_openjdk_desc),
                        ),
                        PackageItem(
                            "gradle",
                            stringResource(com.ai.assistance.operit.terminal.R.string.package_gradle_name),
                            "gradle",
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
        )

    // 跟踪每个分类的展开状态
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }
    
    // 跟踪选中的包
    val selectedPackages = remember { mutableStateMapOf<String, Boolean>() }
    
    // 跟踪每个分类的全选状态
    val categorySelectAll = remember { mutableStateMapOf<String, Boolean>() }
    
    // 新增：跟踪包的安装状态
    val packageStatus = remember { mutableStateMapOf<String, InstallStatus>() }
    val terminalManager = remember(context) { TerminalManager.getInstance(context) }
    // 包检测使用一次结构化 hidden probe，不能抢占用户当前可见 PTY 会话或把探测命令加入用户队列。
    LaunchedEffect(terminalManager) {
        val allPackages = packageCategories.flatMap { it.packages }
        allPackages.forEach { pkg ->
            packageStatus[pkg.id] = InstallStatus.CHECKING
        }

        val result = try {
            terminalManager.executeHiddenCommand(
                command = packageProbeCommand(allPackages),
                executorKey = "environment-setup-check",
                timeoutMs = 30_000L,
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
        allPackages.forEach { pkg ->
            val status = statuses[pkg.id] ?: InstallStatus.UNKNOWN
            packageStatus[pkg.id] = status
            if (status == InstallStatus.INSTALLED) {
                selectedPackages[pkg.id] = true
            }
        }
        packageCategories.forEach { category ->
            categorySelectAll[category.id] =
                category.packages.all { pkg ->
                    packageStatus[pkg.id] == InstallStatus.INSTALLED ||
                        selectedPackages[pkg.id] == true
                }
        }
    }

    var showSetupDialog by remember { mutableStateOf(false) }
    var setupSubmitted by remember { mutableStateOf(false) }
    val commandsToRun = remember { mutableStateOf<List<String>>(emptyList()) }

    if (showSetupDialog) {
        AlertDialog(
            onDismissRequest = { showSetupDialog = false },
            title = { Text(stringResource(com.ai.assistance.operit.terminal.R.string.setup_dialog_title)) },
            text = { Text(stringResource(com.ai.assistance.operit.terminal.R.string.setup_dialog_message)) },
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006400))
                ) {
                    Text(stringResource(com.ai.assistance.operit.terminal.R.string.dialog_confirm), color = Color.White)
                }
            },
            dismissButton = {
                Button(
                    onClick = { showSetupDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A4A4A))
                ) {
                    Text(stringResource(com.ai.assistance.operit.terminal.R.string.dialog_cancel), color = Color.White)
                }
            },
            containerColor = Color(0xFF2D2D2D),
            titleContentColor = Color.White,
            textContentColor = Color.Gray
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A1A))
            .padding(16.dp)
    ) {
        // 标题
        Text(
            text = stringResource(com.ai.assistance.operit.terminal.R.string.setup_title),
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        Text(
            text = stringResource(com.ai.assistance.operit.terminal.R.string.setup_subtitle),
            color = Color.Gray,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        // SSH模式警告横幅
        if (isSSHEnabled) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFA500).copy(alpha = 0.2f)
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
                            text = "SSH 模式警告",
                            color = Color(0xFFFFA500),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "本页面在 SSH 模式下检测不准确，请自行手动配置 pnpm 和 python。",
                            color = Color.White,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )
                    }
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
                    category = category,
                    isExpanded = expandedCategories[category.id] ?: false,
                    onExpandToggle = { expandedCategories[category.id] = !expandedCategories.getOrDefault(category.id, false) },
                    selectedPackages = selectedPackages,
                    categorySelectAll = categorySelectAll,
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
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A4A4A))
            ) {
                Text(stringResource(com.ai.assistance.operit.terminal.R.string.skip), color = Color.White)
            }
            
            Button(
                onClick = {
                    val commands = mutableListOf<String>()

                    // 自动配置必须保持非交互；任何一步失败都由逐步执行协议停止后续命令。
                    commands.addAll(TerminalEnvironmentContract.SYSTEM_REPAIR_COMMANDS)
                    
                    // 镜像源配置必须复用设置页当前选择，且写入 Ubuntu rootfs 的 root HOME。
                    commands.addAll(
                        TerminalEnvironmentContract.buildPipConfigurationCommands(
                            sourceManager.getSelectedSource(PackageManagerType.PIP).url
                        )
                    )
                    
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
                                } else if (pkg.id == "uv" || pkg.id == "nodejs") {
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
                        if (selectedPackages.getOrDefault("nodejs", false)) {
                            allAptDeps.add("curl")
                        }
                    }
                    
                    // 添加选中的 apt 包
                    allAptDeps.addAll(selectedAptPackages)
                    
                    // 使用 apt 安装所有 apt 包和依赖
                    if (allAptDeps.isNotEmpty()) {
                        commands.add(TerminalEnvironmentContract.buildAptInstallCommand(allAptDeps))
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
                    
                    commandsToRun.value = commands
                    showSetupDialog = true
                },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF006400))
            ) {
                Text(stringResource(com.ai.assistance.operit.terminal.R.string.start_setup), color = Color.White)
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
    categorySelectAll: MutableMap<String, Boolean>,
    packageStatus: Map<String, InstallStatus>
) {
    val context = LocalContext.current
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D2D2D)),
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
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    // Operit必须标签 - 第二行
                    if (category.id == "nodejs" || category.id == "python") {
                        Text(
                            text = "(${stringResource(com.ai.assistance.operit.terminal.R.string.operit_required)})",
                            color = Color(0xFFFFA500), // Orange color
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    // 描述 - 第三行
                    Text(
                        text = category.description,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                
                // 全选按钮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Checkbox(
                        checked = categorySelectAll[category.id] ?: false,
                        onCheckedChange = { selectAll ->
                            categorySelectAll[category.id] = selectAll
                            category.packages.forEach { pkg ->
                                if (packageStatus[pkg.id] != InstallStatus.INSTALLED) {
                                    selectedPackages[pkg.id] = selectAll
                                }
                            }
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF006400),
                            uncheckedColor = Color.Gray
                        )
                    )
                    Text(
                        text = stringResource(com.ai.assistance.operit.terminal.R.string.select_all),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                
                // 展开/收起图标
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) stringResource(com.ai.assistance.operit.terminal.R.string.collapse) else stringResource(com.ai.assistance.operit.terminal.R.string.expand),
                    tint = Color.White
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
                            // 检查是否需要更新全选状态
                            val allSelectedAfterChange = category.packages.all { p ->
                                selectedPackages[p.id] == true
                            }
                            categorySelectAll[category.id] = allSelectedAfterChange
                        },
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
    status: InstallStatus
) {
    val context = LocalContext.current
    val isInstalled = status == InstallStatus.INSTALLED
    val isChecking = status == InstallStatus.CHECKING
    val isUnknown = status == InstallStatus.UNKNOWN

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isInstalled) { onSelectionChange(!isSelected) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isChecking) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color.White,
                strokeWidth = 2.dp
            )
        } else {
            Checkbox(
                checked = isSelected || isInstalled,
                onCheckedChange = onSelectionChange,
                enabled = !isInstalled,
                colors = CheckboxDefaults.colors(
                    checkedColor = Color(0xFF006400),
                    uncheckedColor = Color.Gray,
                    disabledCheckedColor = Color(0xFF006400).copy(alpha = 0.5f)
                )
            )
        }
        
        Column(
            modifier = Modifier.padding(start = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = packageItem.name,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                if (isInstalled) {
                    Text(
                        text = " (${stringResource(com.ai.assistance.operit.terminal.R.string.installed)})",
                        color = Color.Green.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                } else if (isUnknown) {
                    Text(
                        text = " (${stringResource(com.ai.assistance.operit.terminal.R.string.detection_failed)})",
                        color = Color(0xFFFFA500),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            if (packageItem.description.isNotEmpty()) {
                Text(
                    text = packageItem.description,
                    color = Color.Gray,
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
            "PATH=\"${TerminalEnvironmentContract.RUSTUP_BIN_DIR}:${'$'}PATH\" rustc --version"
    "uv" ->
        "PATH=\"${TerminalEnvironmentContract.PIPX_BIN_DIR}:${'$'}PATH\" command -v uv && " +
            "PATH=\"${TerminalEnvironmentContract.PIPX_BIN_DIR}:${'$'}PATH\" uv --version"
    "nodejs" -> "node -v 2>/dev/null"
    "pnpm" -> TerminalEnvironmentContract.NODE_TOOLCHAIN_CHECK_COMMAND
    "go" -> "command -v go"
    "ssh" -> "command -v ssh"
    "sshpass" -> "command -v sshpass"
    "openssh-server" -> "command -v sshd"
    "gradle" -> "command -v gradle"
    "python-is-python3" ->
        "python --version >/dev/null 2>&1 && python3 --version >/dev/null 2>&1 && " +
            "python_path=\"${'$'}(command -v python)\" && python3_path=\"${'$'}(command -v python3)\" && " +
            "[ \"${'$'}(readlink -f \"${'$'}python_path\")\" = \"${'$'}(readlink -f \"${'$'}python3_path\")\" ]"
    "python3-venv" -> "python3 -m venv --help >/dev/null 2>&1"
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
            // 检查 Node.js 版本是否 >= 24
            val versionMatch = Regex("""(?:^|\s)v(\d+)\.""").find(output)
            val majorVersion = versionMatch?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
            majorVersion >= 24
        }
        "python-is-python3", "python3-venv", "python3-pip" -> true
        "rust", "uv", "go", "ssh", "sshpass", "openssh-server", "gradle" -> output.isNotBlank()
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
    Regex("^${Regex.escape(PACKAGE_PROBE_ENTRY_PREFIX)}(${PACKAGE_PROBE_ID_PATTERN.pattern}):([01])$")

internal fun packageProbeCommand(packages: List<PackageItem>): String = buildString {
    require(packages.map(PackageItem::id).distinct().size == packages.size) {
        "Environment probe package IDs must be unique"
    }
    require(packages.all { pkg -> PACKAGE_PROBE_ID_PATTERN.matches(pkg.id) }) {
        "Environment probe package IDs must use lowercase letters, digits, and hyphens"
    }

    appendLine("printf '%s\\n' '$PACKAGE_PROBE_BEGIN_MARKER'")
    packages.forEach { pkg ->
        append("if (")
        append(packageCheckCommand(pkg))
        append(") >/dev/null 2>&1; then printf '%s:%s\\n' '")
        append(PACKAGE_PROBE_ENTRY_PREFIX)
        append(pkg.id)
        append("' '1'; else printf '%s:%s\\n' '")
        append(PACKAGE_PROBE_ENTRY_PREFIX)
        append(pkg.id)
        appendLine("' '0'; fi")
    }
    // These must be physical LF separators. A literal backslash-n is folded into the neighboring
    // shell token and makes the complete script fail before either protocol marker can be emitted.
    appendLine("printf '%s\\n' '$PACKAGE_PROBE_END_MARKER'")
}

internal fun packageProbeStatuses(
    result: HiddenExecResult,
    packages: List<PackageItem>,
): Map<String, InstallStatus> {
    if (!result.isOk || result.exitCode != 0) {
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
                PACKAGE_PROBE_ENTRY_PATTERN.matchEntire(line)?.let { match ->
                    val id = match.groupValues[1]
                    val status =
                        if (match.groupValues[2] == "1") {
                            InstallStatus.INSTALLED
                        } else {
                            InstallStatus.NOT_INSTALLED
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

/** 保留给旧测试和调用方的完成事件投影；进度事件不参与安装状态判定。 */
internal fun completedCommandOutput(event: CommandExecutionEvent): String? =
    if (event.isCompleted) event.outputChunk else null
