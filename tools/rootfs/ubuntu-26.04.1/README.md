# Ubuntu 26.04.1 rootfs 构建指南

本目录用于构建 KiyoriTerminalCore 内置的 arm64 Ubuntu 用户空间。它属于构建期流水线，不是 Android 运行时更新器，也不是运行时 APT 软件源。

## 固定输入

`build.sh` 使用已安装的 Ubuntu archive keyring 验证 Canonical 签名的 `SHA256SUMS`，并在解压前校验固定的 Ubuntu Base 摘要。软件包安装使用冻结的 Ubuntu Snapshot `20260827T205830Z`。以下输入共同定义一个 rootfs 版本：

- Canonical Ubuntu Base 26.04.1 arm64 归档及其固定 SHA-256
- Snapshot 时间戳和签名归档元数据
- `packages.txt`
- `SOURCE_DATE_EPOCH`
- 归档名称和压缩格式

任何输入发生变化，都必须生成新的 Android manifest 条目、软件包锁、归档摘要，并重新进行迁移评审。

## 构建环境

必须在隔离的 Linux 环境中以 root 运行，并确保以下命令可用：

`curl`、`gpgv`、`qemu-aarch64-static`、`sha256sum`、`tar`、`xz` 和 `python3`。

```bash
sudo ./build.sh /absolute/output/directory
```

构建器只挂载临时 `proc` 和 `dev`，禁止软件包服务启动，以 `DEBIAN_FRONTEND=noninteractive` 安装软件包，移除构建专用的 QEMU 和 policy 文件，清理机器身份与运行时缓存，并在归档前删除冻结 Snapshot 软件源。退出、中断或终止时都会执行清理。

Ubuntu 26.04 可能默认选择 `rust-coreutils`。Kiyori 会显式安装 `coreutils-from-gnu`，移除不再使用的 Rust provider，并在其仍存在时使构建失败。这样可以保留历史终端脚本使用的 GNU 命令集，同时避免 Android 文件系统无法处理的硬链接目录。归档使用 `tar --hard-dereference`、确定性排序、数字化所有者、固定 mtime 和单线程 `xz` 压缩。

## 输出物

输出目录包含：

- `ubuntu-resolute-arm64-kiyori-v1.tar.xz`
- `ubuntu-resolute-arm64-kiyori-v1.packages.tsv`

只有在两次独立构建产生相同的归档 SHA-256 且归档校验通过后，才能更新 Android asset。已提交的 manifest 位于 `terminal/src/main/assets/ubuntu-rootfs-manifest.json`，记录归档摘要、字节大小、架构、软件包锁、Canonical Base 摘要和 Snapshot 元数据。

旧 Noble 归档 `terminal/tools/rootfs/legacy/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz` 仅用于迁移和取证。它不在 Android `assets` 中，不会进入新 APK；在 Android 迁移和设备验收完成前不得删除。

## 归档校验

必须针对最终归档字节执行校验，不得只校验中间解压目录：

```bash
sudo ./verify.sh /absolute/output/directory
```

校验器会在解压前后检查：

- `xz --test` 和归档元数据
- 不允许绝对路径或向上遍历路径
- 不允许重复成员，或位于更早符号链接下的成员
- 不允许逃出虚拟根目录的符号链接
- 不允许硬链接、设备节点或 FIFO
- Ubuntu Resolute 发行版、arm64 loader、GNU coreutils 和必需命令集
- 不允许构建专用 QEMU、policy、Snapshot 软件源、辅助缓存或机器身份
- 不允许 Noble APT 引用
- 软件包锁必须与归档内 dpkg 数据库一致
- 通过 QEMU 执行 arm64 命令面

校验器会输出归档成员数、普通文件字节数、符号链接数、硬链接数、软件包锁状态和最终 SHA-256。退出码非零即表示 rootfs 候选失败。

## 父仓库验证

从 Kiyori 父仓库执行已提交资产的轻量检查：

```powershell
.\.venv\Scripts\python.exe -B ci\script\check_ubuntu_rootfs_asset.py --repository .
```

随后执行终端模块构建；涉及 gitlink 变更时，再执行完整 Debug APK 构建：

```powershell
.\gradlew.bat :terminal:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :terminal:assembleDebug --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

资产检查和 Gradle 任务只能验证仓库集成，不能代替 ARM64 设备上的解压、PRoot/chroot 启动、软件包安装和终端交互验收。

## 构建期与运行时隔离

构建 Snapshot 不是运行时软件源。运行时由 TerminalManager 写入用户选择的 Resolute 镜像及 `resolute`、`resolute-updates`、`resolute-backports`、`resolute-security` suites。构建专用源在归档前删除，避免设备无意间使用第二套软件源。
