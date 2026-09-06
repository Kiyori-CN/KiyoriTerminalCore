# KiyoriTerminalCore

KiyoriTerminalCore 是 [Kiyori](https://github.com/Kiyori-CN/Kiyori) 内置的 Android 终端引擎。项目以独立私有仓库维护，由父仓库通过 `terminal` Git 子模块和 `:terminal` Gradle 模块集成。

本项目实现源自 [AAswordman/OperitTerminalCore](https://github.com/AAswordman/OperitTerminalCore)。仓库继续准确保留上游作者归属、来源说明和许可证要求；仓库所有权及 Kiyori 专属维护工作由本项目独立负责。

## 模块能力

- 支持多个本地或 SSH 终端会话，并由单一状态所有者统一管理。
- 基于 Android PTY 的持久交互式 Bash 会话。
- 批量命令执行：保留 Unicode、TAB、引号、控制字符、多行输入、工作目录、环境变量和后台任务。
- ANSI 终端解析、增量输出历史、命令取消、会话恢复以及 Compose/Canvas 终端界面。
- AIDL 服务访问，使终端任务可以独立于 UI 进程继续运行。
- 可迁移的 Ubuntu 26.04.1 Resolute arm64 用户空间，并按需配置 Node.js、pnpm、TypeScript、Ruby、Python、Rust 和 Gradle。
- 本地与 SSH 文件系统提供者、SSH 配置、可选本地 SSH 服务，以及采用确定性依赖清理流程的 FTP 支持。

本模块是 Android Library，不负责应用更新、发布分发、产品导航或独立公告渠道；这些职责属于 Kiyori 父项目。

## 仓库结构

| 路径 | 职责 |
| --- | --- |
| `src/main/java/com/ai/assistance/operit/terminal/TerminalManager.kt` | 会话、命令、环境安装和恢复的单一运行时所有者。 |
| `src/main/java/com/ai/assistance/operit/terminal/TerminalSession.kt` | PTY 会话生命周期和 Shell 状态。 |
| `src/main/java/com/ai/assistance/operit/terminal/service/TerminalService.kt` | Android Service 及 AIDL 回调桥接。 |
| `src/main/aidl/com/ai/assistance/operit/terminal/` | 稳定 IPC 契约和可 Parcelable 事件定义。 |
| `src/main/java/com/ai/assistance/operit/terminal/provider/` | 本地/SSH 命令与文件系统提供者，包括隐藏探测器。 |
| `src/main/java/com/ai/assistance/operit/terminal/view/` | ANSI 处理、无障碍、Canvas 渲染、手势和 Compose 集成。 |
| `src/main/assets/ubuntu-rootfs-manifest.json` | 内置 rootfs 的摘要、大小、架构、软件包和来源元数据。 |
| `tools/rootfs/ubuntu-26.04.1/` | 可复现 Linux 构建器和归档校验器。 |
| `src/test/` | JVM 契约测试和可选的真实 Bash PTY 测试。 |

## 运行时架构

`TerminalManager` 是终端会话和终端状态的唯一所有者。调用方可以在应用进程内直接使用它，也可以绑定 `TerminalService`；两条路径最终使用同一套会话和事件契约。`TerminalService` 通过 `ITerminalService` 和 `ITerminalCallback` 转发状态与有序事件，不创建第二套运行时。

每个本地会话包含一个长期运行的 Bash/PTY 对。交互式键盘输入直接写入 PTY。批量命令会编码为单个 ASCII Bash ANSI-C 引用参数，并在同一 Shell 中求值，因此 `cd`、环境变量、后台任务及 Shell 局部状态可以跨命令保留。由于 Bash 无法表示 NUL，包含 NUL 的命令会在进入队列或修改状态前被拒绝。

命令事件由一个 FIFO 分发器统一排序。命令开始事件和所有输出分片一定先于完成事件到达。完成事件只携带权威退出码，正文为空；因此 UI 历史的正文唯一来源仍是增量输出事件。可见命令信封使用私有 ANSI OSC `1337` 标记 `__KIYORI_COMMAND_EXIT__:<command-id>:<exit-code>`。解析器仅为旧版本创建的会话兼容历史标记 `__OPERIT_COMMAND_EXIT__`。

取消操作精确指向一个命令 ID。如果 Ctrl+C、写入失败或 PTY EOF 导致命令边界无法确认，管理器会在保持逻辑会话 ID 不变的前提下重建 PTY，递增 Shell generation，并在新 Shell 到达 `READY` 后继续执行排队命令。重建 Shell 会重置工作目录和环境变量等进程级状态。

## IPC 契约

以下标识属于兼容边界。除非已有独立的迁移设计，否则不得重命名：

- Namespace：`com.ai.assistance.operit.terminal`
- Service：`com.ai.assistance.operit.terminal.service.TerminalService`
- AIDL：`ITerminalService`、`ITerminalCallback`、`CommandExecutionEvent` 和 `SessionDirectoryEvent`
- 持久化路径、`OPERIT_*` 环境变量、native 库文件名、隐藏命令标记和 Ubuntu 挂载路径

Library Manifest 中的 Service 设置为 non-exported。终端 UI 的展示方式及绑定进程由父应用决定。

## Ubuntu 用户空间

随包提供的 arm64 rootfs 是 Ubuntu 26.04.1 Resolute。其 `os-release`、软件包元数据、Shell 文件和发行版身份均保持 Ubuntu 原样；Kiyori 负责外层配置流程和终端展示。首次进入 `READY` 时只输出 Shell 提示符和用户输出，不注入产品横幅。

运行时和已提交 manifest 共同约束以下工具版本：

| 能力 | 契约 |
| --- | --- |
| Node.js | `24.20.0` arm64 归档，内置 npm `11.19.0`，通过 SHA-256 校验。 |
| pnpm | `12.3.4`，安装至 `$HOME/.local/bin`。 |
| TypeScript | `7.0.2`，安装至同一 npm 全局 bin 目录。 |
| Ruby | Ubuntu `ruby` 软件包；只有 `command -v ruby` 和 `ruby --version` 均成功后才视为就绪。 |
| OpenJDK | Ubuntu Resolute `openjdk-25-jdk`，版本以记录的 Snapshot 为准。 |
| Gradle | 官方 `9.7.1` 分发包，固定 SHA-256；需要时同时配置 OpenJDK 25。 |

当前安装标记为 `.kiyori_installed_ok`。有效的 `.operit_installed_ok` 只允许作为一次性历史迁移输入，验证新标记后立即删除；新安装绝不会写入历史标记。隐藏探测使用同一 rootfs 和明确的工具路径，不依赖交互式 Shell 的 profile。

rootfs 输入、软件包锁、确定性归档规则和校验命令见[rootfs 构建指南](tools/rootfs/ubuntu-26.04.1/README.md)。

## 集成到 Kiyori

父仓库固定记录经过验证的子模块提交：

```bash
git submodule sync -- terminal
git submodule update --init terminal
./gradlew :terminal:assembleDebug --no-daemon --console=plain
```

只有在子模块提交完成验证并推送后，父仓库才可以更新 `terminal` gitlink。不得将生成的 `build/` 或 `.cxx/` 内容复制到父仓库。

## 开发与验证

Windows 下从 `D:\10_Project\Kiyori` 执行；Linux/macOS 使用对应的父仓库路径。

```powershell
.\gradlew.bat :terminal:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :terminal:assembleDebug --no-daemon --console=plain
git -C terminal diff --check
```

`CommandEnvelopePtyTest` 通过 Python PTY 支持，使用真实 Bash/Readline 执行生产命令信封。Linux 需要 `python3` 和 Bash；Windows 必须将 `KIYORI_PTY_WSL_DISTRO` 设置为有效的 WSL 发行版，否则该测试会明确报告跳过。Android/proot 行为、触摸交互和视觉布局仍需设备验收。

父仓库集成门禁：

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

标准产物为 `app/build/outputs/apk/debug/app-debug.apk`。本地构建成功不等于设备验收通过。

## rootfs 构建

构建器必须在隔离的 Linux 环境中以 root 运行，并提供 `curl`、`gpgv`、`qemu-aarch64-static`、`sha256sum`、`tar`、`xz` 和 `python3`。构建开始前会验证 Canonical 签名校验清单和固定的 Ubuntu Base 摘要：

```bash
cd terminal/tools/rootfs/ubuntu-26.04.1
sudo ./build.sh /absolute/output/directory
sudo ./verify.sh /absolute/output/directory
```

在更新 Android manifest 前，必须比较两次独立构建的归档 SHA-256，并通过归档校验器。校验器会拒绝不安全路径、符号链接逃逸、硬链接、设备/FIFO 条目、构建专用文件、Noble 引用和软件包锁偏差。已提交资产的快速检查由父仓库的 `check_ubuntu_rootfs_asset.py` 执行。

## 兼容性、来源与许可证

本仓库为 Kiyori 独立维护，同时保留上游兼容边界。品牌调整不得替换继承的 Namespace 或运行时标识。修改状态所有权、持久化数据、命令标记、rootfs 迁移或 IPC 行为前，应先阅读 `CONTEXT.md`。

仓库许可证和第三方声明是权威依据。Ubuntu、Node.js、软件包源、JNI 组件、FTPServer/MINA 及其他内置输入均继续遵守各自的声明和许可证。GitHub 私有可见性不会取消源码归属或许可证义务。

## 故障排查

- **Windows 上 PTY 测试被跳过：** 将 `KIYORI_PTY_WSL_DISTRO` 设置为有效 WSL 发行版后，重新执行 `:terminal:testDebugUnitTest`。
- **会话停留在 `FAILED`：** 先检查 rootfs 安装的首个错误和 manifest 摘要，再考虑清除应用数据；暂存或健康检查失败时，管理器会保留原有可用 rootfs。
- **交互式 Shell 能找到工具，但隐藏探测找不到：** 确认工具安装在文档规定的 `$HOME/.local/bin` 或 `$HOME/.cargo/bin`；隐藏探测不会继承交互式 `PATH`。
- **取消后终端上下文发生变化：** 只有在旧 PTY 边界无法确认时才会发生。管理器会报告恢复并启动新的 Shell generation，同时保持队列顺序。
- **父仓库构建了错误的子模块提交：** 在父仓库执行 `git -C terminal rev-parse HEAD` 和 `git ls-tree HEAD terminal`，确认父仓库固定了经过验证的子模块提交。

## 分支与交付策略

维护性变更统一在 `main` 分支开发、提交和推送。`upstream` 仅作为只读来源参考，Kiyori 变更不得推送到该远端。子模块提交与父仓库 gitlink 必须分别审查和验证。
