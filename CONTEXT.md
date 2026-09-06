# KiyoriTerminalCore shared context

This file records stable terminology, ownership, compatibility contracts, and runtime invariants. It is not a task log. Detailed build procedures live in `README.md` and `tools/rootfs/ubuntu-26.04.1/README.md`.

## Repository identity

- **Repository:** `Kiyori-CN/KiyoriTerminalCore`
- **Visibility:** private; the repository is maintained independently and is not represented as a GitHub fork.
- **Source provenance:** the implementation originated from `AAswordman/OperitTerminalCore`; upstream authorship, attribution, and license notices remain applicable.
- **Parent integration:** Kiyori includes this repository at `terminal/` and pins it as Gradle module `:terminal`.
- **Maintained branch:** `main`
- **Remote roles:** `origin` is the Kiyori repository; `upstream` is a read-only source reference.

Repository identity does not change the inherited Android namespace or runtime identifiers. GitHub visibility and repository lineage are metadata; they do not authorize a compatibility-breaking rename.

## Ownership and contracts

- `TerminalManager` is the single owner of terminal sessions, process lifecycle, command queues, rootfs installation, environment setup, and terminal state.
- `TerminalService` exposes that manager to other processes through AIDL. It does not create a second session registry or a second event source.
- `ITerminalService.aidl` and `ITerminalCallback.aidl` are stable IPC contracts.
- `SessionManager` owns session projection and persistence-facing session metadata; the PTY/session object owns the live process handles.
- Local and SSH command providers implement the same terminal provider contract. Filesystem providers follow the active provider and share the SSH/SFTP connection when applicable.
- The terminal UI consumes state and events. It must not duplicate command history, current-directory facts, or installation state.

## Compatibility boundary

Do not rename these identifiers without a compatibility and migration design:

- `com.ai.assistance.operit.terminal`
- AIDL names and service class names
- Persisted paths, `OPERIT_*` variables, native library filenames, hidden command markers, and rootfs mount paths
- Existing `operit://`/parent integration contracts consumed outside this module

The compatibility namespace is an inherited technical identifier, not a statement about current repository ownership. New explanatory text may use Kiyori branding while preserving those identifiers.

## Command and event invariants

- One FIFO dispatcher orders command start, output chunks, directory-change events, and completion.
- Completion carries the authoritative exit code and an empty body. Non-completion events are the only source of command output text.
- Visible command envelopes use `__KIYORI_COMMAND_EXIT__:<command-id>:<exit-code>` inside ANSI OSC `1337`. A session-level filter carries incomplete OSC data across PTY chunks so split markers cannot leak into the canvas.
- The parser accepts `__OPERIT_COMMAND_EXIT__` only for historical sessions.
- Batch commands are encoded as one ASCII Bash ANSI-C quoted argument. The existing shell decodes and evaluates the original UTF-8 content, preserving cwd, exports, jobs, and multiline semantics.
- NUL is rejected before queue/state mutation.
- Before evaluation, `builtin pwd -P` validates the current directory. If it is gone, the shell changes to `$HOME`; a failed recovery prevents command execution and returns a failure marker.
- Cancellation targets one exact command ID. When Ctrl+C, writer failure, or PTY EOF cannot prove a boundary, the manager replaces the PTY under the same logical session ID, increments its shell generation, and resumes queued commands only after `READY`. The replacement resets process-local cwd and exports.
- Raw keyboard input remains direct PTY input and retains normal TAB completion and Ctrl+C behavior.

## Environment and rootfs invariants

- The packaged runtime is Ubuntu 26.04.1 Resolute arm64. Ubuntu distribution identity and package metadata are not rewritten as Kiyori.
- `src/main/assets/ubuntu-rootfs-manifest.json` is authoritative for archive size, SHA-256, architecture, package lock, source Base, and snapshot metadata.
- The active marker is `.kiyori_installed_ok`. `.operit_installed_ok` is one-time historical migration input and is removed after the new marker is verified; it is never newly written.
- Installation validates the archive into a temporary file, extracts into `installed-rootfs/ubuntu.install.tmp`, checks release/architecture/command health, migrates the old `/root` workspace while excluding transient venv/cache content, and atomically activates the result. A failed move or health check preserves the previous active rootfs and a PID-labelled backup.
- Installation uses an atomic directory lock. Missing or changing owner PIDs are not immediately treated as stale.
- Required command paths are checked with BusyBox `stat`; host-side `[ -x]` is not sufficient to prove Android staging permissions. Successful PRoot/chroot execution remains a device-level condition.
- Extraction runs with `umask 022` to preserve archive modes. This does not prove that PRoot starts on a device.
- Runtime APT configuration uses the selected Resolute mirror suites and does not retain the build-only frozen Snapshot source.
- The archive is hardlink-free because Android extraction filesystems may reject hard-link creation. The rootfs builder selects GNU coreutils and removes `rust-coreutils` before deterministic archiving.
- Hidden probes use explicit `$HOME/.local/bin` and `$HOME/.cargo/bin` paths and the same rootfs as visible sessions. Readiness is projected as `UNKNOWN` when a structured probe is incomplete, duplicated, malformed, or fails.
- Android host timezone is passed explicitly into every `env -i` launch. Static fake `/proc` fixtures are compatibility data and are not a live boot clock.

## Navigation and lifecycle invariants

- `TerminalScreen` owns setup/home/settings route state for the mounted screen instance.
- Route changes are immediate because `TerminalHome` contains a native `SurfaceView`; animated retention can expose the host screen or capture input while setup is entering.
- In non-fullscreen mode, IME insets are reserved in layout coordinates so the toolbar cannot overlap the native surface. Navigation clears the window input connection before disposal.
- Back is handled in route order: setup/settings return to Terminal Home, and Terminal Home asks its host to close the terminal panel. The host supplies an explicit visibility bit because retained AI content may remain composed offscreen.
- Environment and settings toolbar entries preserve stable 40dp touch targets.

## Dependency and security invariants

- The FTP module excludes FTPServer's transitive MINA artifact and consumes deterministic `sanitizeMinaCore` output.
- The sanitizer removes only the unused `BogusTrustManagerFactory*` trust-all helper family and rejects source or retained-bytecode reference drift.
- Credentials, cookies, private keys, Authorization headers, and tokens never enter logs, persisted audit text, or Git.
- The terminal does not own an independent update channel or remote announcement mechanism.

## Change checklist

Before changing terminal behavior, identify the owning component and compatibility impact. Update this file when an invariant, identifier, state owner, protocol, persisted path, rootfs migration rule, or validation contract changes. Keep transient failures and task status in the parent TODO system instead.
