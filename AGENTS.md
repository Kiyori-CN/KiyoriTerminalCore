# KiyoriTerminalCore 协作规则

本文件适用于 `KiyoriTerminalCore` 仓库。本仓库作为 `Kiyori-CN/Kiyori` 的 `terminal` Git 子模块维护。

## 仓库与分支边界

- 所有维护性变更只在 `main` 分支开发、提交和推送。
- `origin` 指向 `Kiyori-CN/KiyoriTerminalCore`；`upstream` 指向只读来源 `AAswordman/OperitTerminalCore`。
- Kiyori 仓库独立维护，文档和交付流程不得将其表述或处理为 GitHub fork。
- 不得强制推送、重写已发布历史，也不得将 Kiyori 变更推送到 `upstream`。
- 子模块提交发布后，必须在 Kiyori 父仓库更新并验证 `terminal` gitlink。

## 兼容性边界

- 除非已有独立迁移方案，否则必须保留 `com.ai.assistance.operit.terminal` Namespace、AIDL 契约、持久化路径和外部使用的标识。
- 仓库展示名称可以使用 `KiyoriTerminalCore`；兼容标识不代表当前产品所有权，也不要求重命名包名。
- 即使仓库独立维护且为 private，也必须准确保留上游作者归属、来源说明和许可证声明。
- 不得添加独立的应用更新或远程公告渠道；分发由 Kiyori 父项目负责。

## 文档职责

- `README.md`：贡献者使用的集成、开发、验证和故障排查入口。
- `CONTEXT.md`：稳定的所有权、协议、兼容性、生命周期、rootfs 和安全不变量。
- `tools/rootfs/ubuntu-26.04.1/README.md`：rootfs 输入、确定性构建、输出锁和归档校验的权威说明。
- 行为、接口、配置、路径或验证契约发生变化时，必须更新对应权威文档；不得把临时任务状态写入稳定上下文。

## 验证与交付

- 生成的 `build/` 和 `.cxx/` 内容不得进入 Git。
- 每次变更都必须执行 `git diff --check`。
- 获得构建授权后，使用父仓库 Gradle Wrapper，至少执行 `:terminal:assembleDebug`；涉及 gitlink 或父仓库集成时执行完整 `:app:assembleDebug`。
- 终端逻辑变更执行 `:terminal:testDebugUnitTest`。Windows 需要真实 Bash PTY 覆盖时，设置 `KIYORI_PTY_WSL_DISTRO`。
- 交付报告必须分别说明子模块提交、父仓库 gitlink 提交、构建结果、远端仓库可见性/关系以及尚未完成的设备验证。
