# KiyoriTerminalCore shared context

This file defines stable repository terminology and compatibility boundaries. Task status and transient implementation notes do not belong here.

## Repository identity

- **KiyoriTerminalCore** is the Kiyori-owned terminal library repository at `https://github.com/Kiyori-CN/KiyoriTerminalCore`.
- The repository is a fork of `https://github.com/AAswordman/OperitTerminalCore`; that repository is named **upstream**.
- Kiyori includes this repository as the `terminal` Git submodule and Gradle module `:terminal`.
- The maintained default branch is `main`.

## Module contract

- `TerminalManager` owns terminal sessions and exposes terminal state through Kotlin Flows.
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

- The terminal welcome banner and terminal-owned explanatory text use the Kiyori brand.
- Ubuntu remains the actual distribution identity; its rootfs archive, `os-release`, `issue`, hostname, standard shell files, and package metadata are not rewritten as Kiyori.
- Node.js setup installs Node.js 24 before `pnpm` and global TypeScript when `pnpm` is selected as a dependency. The shared readiness contract requires a usable Node 24 runtime and npm, resolves `npm prefix -g`, and invokes the installed `pnpm` and `tsc` from that exact bin, so visible and hidden sessions do not depend on profile-specific `PATH` state.
- Hidden command probes bootstrap the same Ubuntu rootfs before starting a persistent `/bin/bash --noprofile --norc -s` stdin shell. The `-s` mode is required because a non-interactive Bash without a script exits immediately when its stdin is a pipe; a dead hidden shell must never be reported as a successful probe.
- Visible command envelopes emit an ANSI OSC `1337` payload containing `__KIYORI_COMMAND_EXIT__:<uuid>:<exit-code>`; the payload is consumed by the terminal parser and is not rendered as text. A session-level display filter carries incomplete OSC data across PTY read chunks so a split marker suffix cannot leak into the canvas. The exit code is the authoritative completion status and is accepted only when its command ID matches the currently executing command. The parser still accepts the historical `__OPERIT_COMMAND_EXIT__` text marker for sessions started by older builds.
- Startup permission repair remains idempotent, but its progress text is emitted only when an Android group is actually added to the Ubuntu rootfs; reopening an already repaired terminal is silent.
- Environment Setup sends unattended `dpkg`/`apt-get` steps with `DEBIAN_FRONTEND=noninteractive`; selected mirror IDs are validated against the current catalog and stale custom-source IDs are repaired to the built-in default before script generation.
- Environment Setup probes all package rows through one structured hidden command. Its generated Bash script uses physical LF statement boundaries and emits exactly one framed `__KIYORI_ENV_PROBE__:<package-id>:<0|1>` record per package; a failed command, incomplete frame, or missing/duplicate/malformed package record projects as `UNKNOWN`/'Unable to detect' rather than an incorrect installed state. Python readiness is capability-based (`python` and `python3` resolve to the same target, `python3 -m venv`, and `python3 -m pip`); Debian status parsing accepts the exact `dpkg-query` value `install ok installed`. Hidden probes resolve pipx and rustup tools through `$HOME/.local/bin` and `$HOME/.cargo/bin` explicitly because their non-profile shell must not depend on interactive `PATH` state. Setup persists pipx's future-shell path and activates that bin in the current visible shell; rustup setup sources only its generated `$HOME/.cargo/env` after a successful install.
- `TerminalScreen` keeps setup/home/settings route changes immediate (`EnterTransition.None`/`ExitTransition.None`) because `TerminalHome` contains a native `SurfaceView`; retaining the old surface during an animated route transition can expose the host screen or capture a tap while setup is entering. In non-fullscreen mode, `TerminalHome` reserves the IME inset in layout coordinates so the toolbar never visually overlaps the native surface; navigation also clears the window input connection before disposal.
- `TerminalScreen` owns system Back in route order: setup/settings return to Terminal Home and Terminal Home asks its host to close the terminal panel. The host supplies an explicit visibility bit because the retained AI tree remains composed offscreen; a hidden terminal must not consume Software Home Back. Environment and settings toolbar entries keep stable 40dp touch targets.
- `installed-rootfs/ubuntu`, `.operit_installed_ok`, `OPERIT_*`, native filenames, hidden command markers, and chroot paths remain compatibility identifiers.
