# KiyoriTerminalCore 共享上下文

本文件记录稳定的术语、所有权、兼容性契约和运行时不变量，不记录任务过程。详细构建流程见 `README.md`，rootfs 操作流程见 `tools/rootfs/ubuntu-26.04.1/README.md`。

## 仓库身份

- **仓库：** `Kiyori-CN/KiyoriTerminalCore`
- **可见性：** public；仓库独立维护，不作为 GitHub fork 表示，仓库公开不代表产品已发行。
- **来源：** 实现源自 `AAswordman/OperitTerminalCore`；上游作者归属、来源说明和许可证要求继续适用。
- **父仓库集成：** Kiyori 将本仓库挂载为 `terminal/`，并作为 Gradle 模块 `:terminal` 固定提交。
- **维护分支：** `main`
- **远端职责：** `origin` 是 Kiyori 仓库；`upstream` 是只读来源参考。

仓库身份不会改变继承的 Android Namespace 或运行时标识。GitHub 可见性和仓库关系属于元数据，不构成兼容性破坏式重命名的授权。

## 所有权与契约

- `TerminalManager` 唯一持有终端会话、进程生命周期、命令队列、rootfs 安装、环境配置和终端状态。
- `TerminalService` 通过 AIDL 向其他进程暴露该管理器，不创建第二套会话注册表或事件来源。
- `ITerminalService.aidl` 和 `ITerminalCallback.aidl` 属于稳定 IPC 契约。
- `SessionManager` 负责会话投影和面向持久化的会话元数据；PTY/session 对象负责实时进程句柄。
- 本地和 SSH 命令提供者实现同一终端提供者契约；文件系统提供者遵循当前提供者，并在适用时共享 SSH/SFTP 连接。
- 环境检测投影绑定实际 provider；SSH 读取目标用户登录交互 Bash 配置，本地显式解析用户工具目录。检测不复制 PTY 临时 export/虚拟环境；每项有界执行并区分缺失、需配置与未知。
- 安装的每一步重新验证目标系统/架构/UID/HOME/主机/machine-id 指纹；SSH 只接受远端就绪标记，断线不能落入本地 Shell。自动安装仅适用于支持的 Linux APT 主机及 root/免交互 sudo。
- Node 安装版本和运行可用性分别定义；npm 独立升级不触发 Node 重装。pnpm 原生入口、全局 copy 导入方式和 TypeScript 实际编译/运行共同定义工具链就绪；仅版本输出不够。
- 终端 UI 只能消费状态和事件，不得复制命令历史、当前目录或安装状态等事实。

## 兼容性边界

除非已有兼容迁移设计，否则不得重命名以下标识：

- `com.ai.assistance.operit.terminal`
- AIDL 名称和 Service 类名
- 持久化路径、`OPERIT_*` 变量、native 文件名、隐藏命令标记和 rootfs 挂载路径
- 模块外部使用的既有 `operit://` 及父仓库集成契约

兼容 Namespace 是继承的技术标识，不代表当前仓库所有权。新增说明文字可以使用 Kiyori 品牌，但必须保留这些标识。

## 命令与事件不变量

- 一个 FIFO 分发器统一排序命令开始、输出分片、目录变化和完成事件。
- 完成事件携带权威退出码且正文为空；命令正文只能来自非完成事件。
- 可见命令信封在 ANSI OSC `1337` 中使用 `__KIYORI_COMMAND_EXIT__:<command-id>:<exit-code>`。会话级过滤器会跨 PTY 分片保留不完整 OSC 数据，防止标记泄漏到 Canvas。
- 解析器只为历史会话接受 `__OPERIT_COMMAND_EXIT__`。
- 批量命令编码为单个 ASCII Bash ANSI-C 引用参数，由现有 Shell 解码并执行，以保留 cwd、环境变量、后台任务和多行语义。
- NUL 在进入队列或修改状态前被拒绝。
- 执行前通过 `builtin pwd -P` 验证当前目录。目录失效时切换到 `$HOME`；恢复失败则不执行用户命令，并通过相同 OSC 标记返回失败。
- 取消操作精确指向一个命令 ID。如果 Ctrl+C、写入失败或 PTY EOF 无法确认边界，管理器在保持逻辑会话 ID 的前提下重建 PTY，递增 Shell generation，并在新 PTY 到达 `READY` 后继续排队命令。重建会重置进程级 cwd 和环境变量。
- 原始键盘输入直接写入 PTY，继续保留 TAB 补全和 Ctrl+C 语义。

## 环境与 rootfs 不变量

- 随包运行时为 Ubuntu 26.04.1 Resolute arm64；Ubuntu 发行版身份和软件包元数据不会被改写为 Kiyori。
- `src/main/assets/ubuntu-rootfs-manifest.json` 是归档大小、SHA-256、架构、软件包锁、Base 来源和 Snapshot 元数据的权威记录。
- 当前安装标记为 `.kiyori_installed_ok`。`.operit_installed_ok` 只允许作为一次性历史迁移输入，验证新标记后删除，绝不新写。
- 安装先将归档校验到临时文件，再解压至 `installed-rootfs/ubuntu.install.tmp`，检查发行版/架构/命令健康度，迁移旧 `/root` 工作区（排除临时 venv/cache），最后原子激活。移动或健康检查失败时保留旧 rootfs，并保留带 PID 的备份。
- 安装锁采用原子目录锁。锁持有者 PID 缺失或正在变化时，不得立即判断为过期。
- 必需命令路径使用 BusyBox `stat` 检查；宿主机 `[ -x]` 不能证明 Android 暂存权限。PRoot/chroot 成功启动仍需设备验证。
- 解压使用 `umask 022` 保留归档权限；这不能替代设备上的 PRoot 启动验证。
- 运行时 APT 使用所选 Resolute 镜像及其 suite，不保留构建专用 Frozen Snapshot 源。
- 归档不包含硬链接，因为 Android 文件系统可能拒绝创建硬链接。rootfs 构建器选择 GNU coreutils 并删除 `rust-coreutils`。
- 隐藏探测显式使用 `$HOME/.local/bin` 和 `$HOME/.cargo/bin`，并使用与可见会话相同的 rootfs。结构化探测不完整、重复、格式错误或执行失败时，状态必须投影为 `UNKNOWN`。
- 每次 `env -i` 启动都显式传入 Android 宿主 IANA 时区。静态 fake `/proc` 文件是兼容数据，不代表真实启动时间。

## 导航与生命周期不变量

- `TerminalScreen` 持有已挂载屏幕实例的 setup/home/settings 路由状态。
- 路由切换保持即时，因为 `TerminalHome` 包含 native `SurfaceView`；动画期间保留旧页面可能露出宿主页面或捕获错误输入。
- 非全屏模式在布局坐标中预留 IME inset，防止工具栏覆盖 native surface；导航销毁前清理 Window input connection。
- Back 按路由顺序处理：setup/settings 返回 Terminal Home，Terminal Home 请求宿主关闭终端面板。宿主提供显式可见性位，因为保留的 AI 内容可能仍在后台组合。
- 环境和设置工具栏入口保持稳定的 40dp 触摸区域。
- 设置、环境配置、SSH 与键盘编辑弹窗使用宿主 MaterialTheme；原生终端画布继续消费 RenderConfig。切换直接输入模式和虚拟键盘动作类型不清空未发送的草稿。
- 字号沿 RenderConfig 的像素单位展示，不将 px 标为 sp。字体配置写入前校验，等待持久化结果；原有字体恢复路径携带可见的加载失败提示。键盘表单冻结打开时的布局，保存时在同一配置管理器锁内检查原值，拒绝覆盖其他入口的新布局。
- SSH 表单只修改暴露的字段，保留正向转发等其他入口配置；端口为 1–65535，启用心跳时须防秒转 Int 毫秒溢出。保存/删除等实际持久化结果返回后才结束弹窗。
- SSH 开关和配置属于下次连接偏好；当前环境与新会话类型取自唯一实际 provider。应用连接设置需明确确认，经维护边界停止全部会话后重新连接；开启 SSH 但配置缺失或不可读时明确失败，不能落入本地。删除配置关闭下次 SSH 偏好，不隐式中断仍在执行的连接。
- 默认会话创建在会话表出现之前由 TerminalManager.initialSessionState 发布准备/成功/失败，页面只消费该状态；失败重试有在途防重，不建立第二初始化 owner。隐藏执行日志不包含命令与输出正文。
- 人工按键和命令在当前输入入口内按触发顺序等待写入，不能依赖 IO 线程调度顺序。草稿修订号防止同文重新输入被旧提交清空。
- 人工输入与中断固定触发时的会话身份，异步等待不得重新选择当前标签。命令输入提交成功后才清除未被修改的正文；创建会话期间阻止重复点击，失败提供可见提示。
- 软件源选择由现有本地启动配置和安装入口读取，不向当前交互会话派发隐式命令。源 URL 在配置文本与 Shell 两层校验/引用；损坏的自定义目录不能作为空列表覆盖保存。删除源与调整持久选中项使用同次偏好提交。
- 环境重置/卸载挂载期间，由 TerminalManager 的同一维护边界阻止新初始化、provider 创建、会话启动和隐藏执行；先取消并有界等待在途操作，再断开 provider 并确认进程退出。无法确认停止时不得删除环境文件；结束后清除初始化标志，下次入口执行完整初始化。
- 重置与卸载挂载在 FTP 单例的停止互斥区内执行，停止失败中止维护。文件清理不跟随目录内符号链接，读取挂载表或删除失败必须传播，不得报告完成。该边界不构成与任意外部文件写入共同的文件系统事务。

## 依赖与安全不变量

- FTP 模块排除 FTPServer 的传递依赖 MINA，并使用确定性的 `sanitizeMinaCore` 输出。
- 清理任务只移除未使用的 `BogusTrustManagerFactory*` 信任所有辅助类，并拒绝源码或保留字节码引用漂移。
- 凭据、Cookie、私钥、Authorization Header 和令牌不得进入日志、持久化审计文本或 Git。
- 终端不拥有独立更新渠道或远程公告机制。

## 变更检查

修改终端行为前，先确认状态所有者和兼容性影响。发生不变量、标识、状态所有者、协议、持久化路径、rootfs 迁移规则或验证契约变化时，必须同步更新本文件。临时故障和任务状态应记录在父仓库 TODO 系统中。
