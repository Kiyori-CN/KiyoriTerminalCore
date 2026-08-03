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
- Node.js setup installs `pnpm` and global TypeScript through npm's existing global bin. The shared readiness contract resolves `npm prefix -g` and invokes the installed `pnpm` and `tsc` from that exact bin, so visible and hidden sessions do not depend on profile-specific `PATH` state.
- `installed-rootfs/ubuntu`, `.operit_installed_ok`, `OPERIT_*`, native filenames, hidden command markers, and chroot paths remain compatibility identifiers.
