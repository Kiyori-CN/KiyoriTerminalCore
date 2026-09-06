# KiyoriTerminalCore

KiyoriTerminalCore is the Android terminal engine embedded by [Kiyori](https://github.com/Kiyori-CN/Kiyori). It is maintained as an independent, private repository and is consumed by the parent project as the `terminal` Git submodule and the `:terminal` Gradle module.

The implementation originated from [AAswordman/OperitTerminalCore](https://github.com/AAswordman/OperitTerminalCore). The source attribution, upstream authorship notices, and license terms in this repository remain accurate. Repository ownership and ongoing Kiyori-specific maintenance are separate from the upstream repository.

## What this module provides

- Multiple local or SSH terminal sessions with one session state owner.
- Persistent interactive Bash sessions backed by an Android PTY.
- Batch command execution that preserves Unicode, TAB, quotes, control characters, multiline input, working directory, exports, and background jobs.
- ANSI terminal parsing, incremental output history, cancellation, session recovery, and a Compose/canvas terminal surface.
- AIDL service access for clients that need terminal work to outlive a UI process.
- A relocatable Ubuntu 26.04.1 Resolute arm64 userspace with on-demand Node.js, pnpm, TypeScript, Ruby, Python, Rust, and Gradle setup.
- Local and SSH-backed filesystem providers, SSH configuration, an optional local SSH server, and FTP support with a deterministic dependency sanitizer.

The module is an Android library. It does not own application updates, release distribution, product navigation, or an independent announcement channel; those responsibilities belong to Kiyori.

## Repository layout

| Path | Responsibility |
| --- | --- |
| `src/main/java/com/ai/assistance/operit/terminal/TerminalManager.kt` | Singleton runtime owner for sessions, commands, environment installation, and recovery. |
| `src/main/java/com/ai/assistance/operit/terminal/TerminalSession.kt` | PTY-backed session lifecycle and shell state. |
| `src/main/java/com/ai/assistance/operit/terminal/service/TerminalService.kt` | Android service and AIDL callback bridge. |
| `src/main/aidl/com/ai/assistance/operit/terminal/` | Stable IPC contracts and parcelable event definitions. |
| `src/main/java/com/ai/assistance/operit/terminal/provider/` | Local/SSH command and filesystem providers, including hidden probes. |
| `src/main/java/com/ai/assistance/operit/terminal/view/` | ANSI processing, accessibility, canvas rendering, gestures, and Compose integration. |
| `src/main/assets/ubuntu-rootfs-manifest.json` | Checked-in digest, size, architecture, package, and source metadata for the embedded rootfs. |
| `tools/rootfs/ubuntu-26.04.1/` | Reproducible Linux builder and archive verifier. |
| `src/test/` | JVM contract tests and the optional real-Bash PTY test. |

## Runtime architecture

`TerminalManager` is the only owner of terminal sessions and terminal state. A client either uses it directly in the application process or binds to `TerminalService`; both paths reach the same session and event contracts. `TerminalService` forwards state and ordered events through `ITerminalService` and `ITerminalCallback` without creating a second runtime.

Each local session contains one long-lived Bash/PTY pair. Interactive keyboard input is written directly to the PTY. Batch commands are encoded as a single ASCII Bash ANSI-C quoted argument and evaluated inside that same shell, so `cd`, exports, jobs, and shell-local state survive across commands. NUL is rejected before queue or state mutation because Bash cannot represent it.

Command events use one FIFO dispatcher. A command start event and all output chunks arrive before its completion event. The completion event carries the authoritative exit code and an empty body; UI history therefore keeps the incremental transcript as the only command output source. Visible envelopes use the private ANSI OSC `1337` marker `__KIYORI_COMMAND_EXIT__:<command-id>:<exit-code>`. The parser accepts the historical `__OPERIT_COMMAND_EXIT__` marker only for sessions created by older builds.

Cancellation targets one command ID. If Ctrl+C, a writer failure, or PTY EOF leaves the command boundary unproven, the manager replaces the PTY under the same logical session ID, increments the shell generation, and resumes queued commands only after the replacement reaches `READY`. A replacement shell necessarily resets process-local state such as the working directory and exports.

## IPC contract

The following identifiers are compatibility boundaries and must not be renamed without a migration design:

- Namespace: `com.ai.assistance.operit.terminal`
- Service: `com.ai.assistance.operit.terminal.service.TerminalService`
- AIDL: `ITerminalService`, `ITerminalCallback`, `CommandExecutionEvent`, and `SessionDirectoryEvent`
- Persisted paths, `OPERIT_*` environment names, native library filenames, hidden command markers, and Ubuntu mount paths

The service is non-exported in the library manifest. The parent application decides how to expose the terminal UI and which process binds to the service.

## Ubuntu environment

The packaged arm64 rootfs is Ubuntu 26.04.1 Resolute. Ubuntu files such as `os-release`, package metadata, shell files, and distribution identity remain Ubuntu data; Kiyori owns the surrounding setup flow and terminal presentation. The first `READY` frame contains the shell prompt and user output only.

The checked-in manifest and runtime enforce these pinned capabilities:

| Capability | Contract |
| --- | --- |
| Node.js | `24.20.0` arm64 archive with bundled npm `11.19.0`, verified by SHA-256 |
| pnpm | `12.3.4`, installed below `$HOME/.local/bin` |
| TypeScript | `7.0.2`, installed in the same npm global bin |
| Ruby | Ubuntu `ruby` package, ready only after `command -v ruby` and `ruby --version` succeed |
| OpenJDK | Ubuntu Resolute `openjdk-25-jdk` at the recorded snapshot |
| Gradle | Official `9.7.1` distribution with a fixed SHA-256; it provisions OpenJDK 25 when needed |

The active installation marker is `.kiyori_installed_ok`. A valid `.operit_installed_ok` is accepted only once as historical migration input and is removed after the new marker is verified. New installations never write the historical marker. Hidden probes use the same rootfs and explicit tool paths as visible sessions, so readiness does not depend on an interactive profile.

Rootfs inputs, package locks, deterministic archive rules, and verification commands are documented in [the rootfs builder guide](tools/rootfs/ubuntu-26.04.1/README.md).

## Integrating with Kiyori

The parent repository pins an exact child commit:

```bash
git submodule sync -- terminal
git submodule update --init terminal
./gradlew :terminal:assembleDebug --no-daemon --console=plain
```

The parent project must update the `terminal` gitlink only after a child commit has been validated and pushed. Do not copy generated `build/` or `.cxx/` output into the parent repository.

## Development and validation

Run commands from `D:\10_Project\Kiyori` on Windows or from the equivalent parent checkout on Linux/macOS.

```powershell
.\gradlew.bat :terminal:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :terminal:assembleDebug --no-daemon --console=plain
git -C terminal diff --check
```

`CommandEnvelopePtyTest` executes the production command envelope against real Bash/Readline through Python PTY support. Linux hosts need `python3` and Bash. Windows hosts must set `KIYORI_PTY_WSL_DISTRO` to an existing WSL distribution; otherwise that test reports a deliberate skip. Android/proot behavior, touch interaction, and visual layout remain device-level acceptance items.

For the parent integration gate, run the full wrapper task required by the parent repository:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

The expected parent artifact is `app/build/outputs/apk/debug/app-debug.apk`. A successful local build does not prove device acceptance.

## Rootfs builder

The builder must run in an isolated Linux environment as root with `curl`, `gpgv`, `qemu-aarch64-static`, `sha256sum`, `tar`, `xz`, and `python3`. It verifies Canonical's signed checksum list and the pinned Ubuntu Base digest before installation:

```bash
cd terminal/tools/rootfs/ubuntu-26.04.1
sudo ./build.sh /absolute/output/directory
sudo ./verify.sh /absolute/output/directory
```

The output archive and package lock are compared byte-for-byte across two builds before the Android manifest is advanced. The verifier rejects unsafe paths, symlink escapes, hard links, device/FIFO entries, build-only files, Noble references, and package-lock drift. The checked-in asset gate is run from the parent repository with `check_ubuntu_rootfs_asset.py`.

## Compatibility, provenance, and licensing

This repository is maintained independently for Kiyori while retaining the upstream compatibility surface. Do not replace the inherited namespace or identifiers as a branding exercise. Review `CONTEXT.md` before changing state ownership, persisted data, command markers, rootfs migration, or IPC behavior.

The repository license and third-party notices are authoritative. Ubuntu, Node.js, package sources, JNI components, FTPServer/MINA, and other bundled inputs retain their respective notices and licenses. A private GitHub visibility setting does not remove source attribution or license obligations.

## Troubleshooting

- **The PTY test is skipped on Windows:** set `KIYORI_PTY_WSL_DISTRO` to a valid WSL distribution and rerun `:terminal:testDebugUnitTest`.
- **A session stays in `FAILED`:** inspect the first rootfs installation error and the manifest digest before deleting application data; the manager preserves a previous active rootfs when staging or health checks fail.
- **A tool is visible in the profile but hidden probes cannot find it:** verify the tool was installed under the documented `$HOME/.local/bin` or `$HOME/.cargo/bin`; hidden probes intentionally do not inherit an interactive `PATH`.
- **The terminal loses context after cancellation:** this is expected only when the old PTY boundary could not be proven. The manager reports recovery and starts a fresh shell generation; queued commands remain ordered.
- **The parent builds an unexpected child commit:** inspect `git -C terminal rev-parse HEAD` and `git ls-tree HEAD terminal` in the parent. The parent must pin the validated child commit explicitly.

## Branch and delivery policy

Maintained changes are developed, committed, and pushed on `main`. The `upstream` remote is read-only source reference material; Kiyori changes are never pushed there. Child commits and the parent gitlink are reviewed and validated separately.
