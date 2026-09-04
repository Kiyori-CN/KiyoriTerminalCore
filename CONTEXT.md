# KiyoriTerminalCore shared context

This file defines stable repository terminology and compatibility boundaries. Task status and transient implementation notes do not belong here.

## Repository identity

- **KiyoriTerminalCore** is the Kiyori-owned terminal library repository at `https://github.com/Kiyori-CN/KiyoriTerminalCore`.
- The repository is a fork of `https://github.com/AAswordman/OperitTerminalCore`; that repository is named **upstream**.
- Kiyori includes this repository as the `terminal` Git submodule and Gradle module `:terminal`.
- The maintained default branch is `main`.

## Module contract

- `TerminalManager` owns terminal sessions and exposes terminal state through Kotlin Flows. Command
  execution events are published through one FIFO dispatcher, so a command's start and output events
  always arrive before its completion event. Non-completion events are the sole command-body source;
  completion events carry an empty body plus the authoritative exit code, so bounded UI history can
  never overwrite a complete incremental transcript.
- `TerminalService` exposes terminal operations to other processes.
- `ITerminalService.aidl` and `ITerminalCallback.aidl` define the IPC contract.
- The module is an Android library. Application branding, release distribution, and update ownership belong to the Kiyori parent project.
- FTP support excludes FTPServer's transitive MINA artifact and consumes the deterministic
  `sanitizeMinaCore` output instead. The task removes only the closed, unused
  `BogusTrustManagerFactory*` trust-all helper family and rejects upstream input or retained-bytecode
  reference drift before compilation.

## Compatibility boundary

The namespace `com.ai.assistance.operit.terminal`, AIDL names, persisted paths, and externally consumed identifiers are compatibility identifiers inherited from the upstream implementation. They are not renamed by repository branding work. Changing one requires a separate compatibility and migration design.

KiyoriTerminalCore does not poll an independent upstream application-update channel. New versions are delivered by advancing the pinned submodule commit in `Kiyori-CN/Kiyori`.

## User-visible environment

- Terminal-owned explanatory text uses the Kiyori brand. The first READY frame contains no injected product banner; it is limited to the shell prompt and user output.
- Ubuntu remains the actual distribution identity; its rootfs archive, `os-release`, `issue`, hostname, standard shell files, and package metadata are not rewritten as Kiyori.
- Node.js setup installs the pinned Node.js `24.20.0` LTS arm64 archive (official SHA-256 verified) before `pnpm` and global TypeScript when `pnpm` is selected as a dependency. The shared readiness contract requires Node `24.20.0`, bundled npm `11.19.0`, pnpm `11.25.0`, and TypeScript `7.0.2`; it resolves `npm prefix -g` and invokes the installed tools from that exact `$HOME/.local/bin`, so visible and hidden sessions do not depend on profile-specific `PATH` state. Ruby is an optional Ubuntu `ruby` apt package and is ready only after both `command -v ruby` and `ruby --version` succeed. Java setup uses Resolute OpenJDK 25, and Gradle uses the official `9.7.1` distribution with a fixed SHA-256; selecting Gradle provisions the JDK package when its readiness probe is not confirmed.
- Hidden command probes bootstrap the same Ubuntu rootfs before starting a persistent `/bin/bash --noprofile --norc -s` stdin shell. The `-s` mode is required because a non-interactive Bash without a script exits immediately when its stdin is a pipe; a dead hidden shell must never be reported as a successful probe.
- Visible command envelopes emit an ANSI OSC `1337` payload containing `__KIYORI_COMMAND_EXIT__:<uuid>:<exit-code>`; the payload is consumed by the terminal parser and is not rendered as text. A session-level display filter carries incomplete OSC data across PTY read chunks so a split marker suffix cannot leak into the canvas. The exit code is the authoritative completion status and is accepted only when its command ID matches the currently executing command. The parser still accepts the historical `__OPERIT_COMMAND_EXIT__` text marker for sessions started by older builds.
- Every chroot/proot `env -i` launch receives the Android host IANA timezone explicitly; the rootfs includes `tzdata`, so shell and language runtimes use device-local time even though the launcher starts with a clean environment. Static fake `/proc` fixtures remain compatibility data and do not represent a live boot time.
- Visible command cancellation targets one exact command ID. If Ctrl+C reaches a prompt, that prompt
  settles the cancelled command even when its trailing OSC envelope was interrupted. If cancellation,
  a writer failure, or PTY EOF cannot prove a usable command boundary, `TerminalManager` replaces the
  PTY under the same logical session ID and increments its shell generation. Queued commands remain in
  FIFO order and resume only after the replacement reaches `READY`; a rebuilt shell resets cwd,
  exported variables, and other process-local context.
- Startup permission repair remains idempotent, but its progress text is emitted only when an Android group is actually added to the Ubuntu rootfs; reopening an already repaired terminal is silent.
- Environment Setup sends unattended `dpkg`/`apt-get` steps with `DEBIAN_FRONTEND=noninteractive`; selected mirror IDs are validated against the current catalog and stale custom-source IDs are repaired to the built-in default before script generation.
- Environment Setup probes all package rows through one structured hidden command. Its generated Bash script uses physical LF statement boundaries and emits exactly one framed `__KIYORI_ENV_PROBE__:<package-id>:<0|1>` record per package; a failed command, incomplete frame, or missing/duplicate/malformed package record projects as `UNKNOWN`/'Unable to detect' rather than an incorrect installed state. Python readiness is capability-based (`python` and `python3` resolve to the same target, `python3 -m venv`, and `python3 -m pip`); Debian status parsing accepts the exact `dpkg-query` value `install ok installed`. Hidden probes resolve pipx and rustup tools through `$HOME/.local/bin` and `$HOME/.cargo/bin` explicitly because their non-profile shell must not depend on interactive `PATH` state. Setup persists pipx's future-shell path and activates that bin in the current visible shell; rustup setup sources only its generated `$HOME/.cargo/env` after a successful install.
- `TerminalScreen` keeps setup/home/settings route changes immediate (`EnterTransition.None`/`ExitTransition.None`) because `TerminalHome` contains a native `SurfaceView`; retaining the old surface during an animated route transition can expose the host screen or capture a tap while setup is entering. In non-fullscreen mode, `TerminalHome` reserves the IME inset in layout coordinates so the toolbar never visually overlaps the native surface; navigation also clears the window input connection before disposal.
- `TerminalScreen` owns system Back in route order: setup/settings return to Terminal Home and Terminal Home asks its host to close the terminal panel. The host supplies an explicit visibility bit because the retained AI tree remains composed offscreen; a hidden terminal must not consume Software Home Back. Environment and settings toolbar entries keep stable 40dp touch targets.
- `installed-rootfs/ubuntu`, the active `.kiyori_installed_ok` marker, the one-time historical `.operit_installed_ok` migration input, `OPERIT_*`, native filenames, hidden command markers, and chroot paths remain compatibility identifiers.
- The checked-in Ubuntu 26.04.1 Resolute asset is described by `src/main/assets/ubuntu-rootfs-manifest.json`.
  `TerminalManager` validates the archive size and SHA-256 into a temporary file before an atomic
  replacement in the app files directory; a mismatched or truncated existing archive is never used.
  The legacy Noble archive remains in the repository under `terminal/tools/rootfs/legacy` for
  migration/forensics and is not an APK asset; new installation and upgrade decisions are driven
  only by the Resolute manifest.
- Installation extracts into `installed-rootfs/ubuntu.install.tmp`, validates the Resolute release,
  architecture and command surface, migrates the old `/root` user workspace while excluding the old
  Python venv and transient cache, and then moves the old directory to a PID-labelled backup before
  moving the staging directory into the compatibility path. A failed move or health check preserves
  the old directory; the backup is not silently deleted. `.kiyori_installed_ok` is the only marker
  written for a new or migrated active rootfs. A valid `.operit_installed_ok` is accepted only once as
  a historical migration input and is removed after the new marker is verified; it is never copied or
  newly written. The install lock
  is an atomic directory lock; an owner PID that is missing or changing is never treated as an
  immediately stale lock, and extraction/staging/migration/activation failures publish a specific
  progress message while retaining the underlying tar error output. A staging-health failure also
  reports the individual release, directory, symlink-target, and executable-mode condition plus a
  bounded listing of the expected paths; these diagnostics do not relax the health gate. Required
  command paths use BusyBox `stat` to validate the archived owner execute bit because Android host
  `[ -x]`/`access(X_OK)` is not a reliable staging-path test; successful PRoot execution remains a
  separate device gate. Extraction runs in an isolated subprocess with `umask 022`, because Android's
  app-process `umask 077` would otherwise turn archive `0755/0644` entries into `0700/0600`. The umask
  fix preserves archive modes but does not, by itself, prove that the PRoot command surface starts.
- The frozen Ubuntu Snapshot used by the build pipeline is not a runtime source. Runtime APT settings
  write the selected mirror with the Resolute suites (`resolute`, `resolute-updates`,
  `resolute-backports`, `resolute-security`) and do not leave a second build-only source enabled.
- The embedded Resolute archive is deliberately hardlink-free. The build selects the GNU coreutils
  provider, removes the unused `rust-coreutils` package that would otherwise leave a large hard-link
  farm, and archives with `tar --hard-dereference`; the manifest and archive verifier require zero
  tar hardlink members. This preserves dpkg-managed paths as regular files and avoids Android
  filesystems' hardlink extraction failure without adding a second runtime extraction path.
