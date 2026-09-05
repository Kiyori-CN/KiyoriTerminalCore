# KiyoriTerminalCore

KiyoriTerminalCore is the Android terminal library maintained for [Kiyori](https://github.com/Kiyori-CN/Kiyori). It is designed as a reusable component, exposing its features through a centralized `TerminalManager` and corresponding AIDL interfaces for inter-process communication.

This repository is derived from [AAswordman/OperitTerminalCore](https://github.com/AAswordman/OperitTerminalCore). Upstream authorship, contribution history, and license terms remain intact. Kiyori-specific development is maintained on this repository's `main` branch.

## Module Responsibilities

The `terminal-core` module is responsible for the following core tasks:

-   **Session Management**: Creating, switching, and closing multiple independent terminal sessions.
-   **Command Execution**: Handling the dispatch of commands and interacting with the underlying shell environment.
-   **State Management**: Acting as a single source of truth for the terminal's state, including session details, command history, and the current working directory. This state is exposed via Kotlin Flows.
-   **Event Notification**: Broadcasting events such as command output and directory changes through Kotlin Flows and an AIDL callback mechanism.

## Key Components

-   **`TerminalManager`**: A singleton class that serves as the main entry point for interacting with the module. It encapsulates all core logic and exposes reactive streams (Kotlin Flows) for observing the terminal's state.

-   **`TerminalService`**: An Android `Service` that wraps the `TerminalManager`. It exposes the terminal's functionality via AIDL (`ITerminalService`), allowing it to be used as a background service and enabling communication from other processes.

-   **AIDL Interface (`ITerminalService.aidl`, `ITerminalCallback.aidl`)**: Defines the contract for communication between the `TerminalService` and its clients. This allows the UI to run in a separate process from the terminal engine, preventing the terminal session from being terminated if the UI is closed.

## Technical Implementation

-   **Architecture**: The module utilizes a reactive architecture, with Kotlin Flows at its core for state management and event propagation.
-   **Concurrency**: Asynchronous operations are managed using Kotlin Coroutines, ensuring that the main thread is not blocked.
-   **Communication**: While designed for IPC with AIDL, the `TerminalManager` can also be used directly within the same process for a simpler setup.
-   **FTP dependency hygiene**: The Gradle module excludes FTPServer's transitive MINA JAR and
    generates a reproducible replacement with `sanitizeMinaCore`. The task verifies the expected
    upstream trust-all helper classes, rejects references from retained bytecode, removes stale JAR
    signatures and module descriptors, and fails if any excluded class remains.

## Usage

Kiyori integrates this repository as the `terminal` Git submodule and pins an exact commit for reproducible builds. A client can bind to `TerminalService` for background operation and IPC, or access the `TerminalManager` singleton directly when running in the same process.

Repository branding does not rename the inherited `com.ai.assistance.operit.terminal` namespace or AIDL contracts. Those identifiers remain compatibility boundaries; see [CONTEXT.md](CONTEXT.md).

Batch commands preserve TAB, Unicode, quotes, control characters and multiline payloads through the
interactive Bash session. Input is encoded before Readline and evaluated in the existing shell, so
directory changes, exports and background jobs persist. NUL-containing commands fail explicitly.
If the current directory was deleted, the next batch command first changes to `$HOME`; if that fails,
the command does not execute and reports the directory error. Raw keyboard input keeps its normal
TAB completion and Ctrl+C behavior.

`CommandEnvelopePtyTest` runs the production envelope against real Bash/Readline using Python's PTY
support. Linux hosts need `python3` and Bash; on Windows, set `KIYORI_PTY_WSL_DISTRO` to an existing
WSL distribution before running `:terminal:testDebugUnitTest` through the parent Gradle wrapper.
Without that explicit Windows selection the PTY test is reported as skipped; Android/proot device
acceptance remains separate.

## Ubuntu environment

The embedded Ubuntu rootfs keeps the Ubuntu distribution identity and existing internal paths. Kiyori owns the surrounding terminal presentation and the first-run toolchain flow; the READY frame does not inject a product banner. The shipped arm64 rootfs is Ubuntu 26.04.1 Resolute, built from a signed Canonical Base input and a frozen package snapshot so the core package set is reproducible and current at the recorded snapshot. Large optional toolchains are downloaded only when selected, keeping APK size and Android memory peaks bounded.

The environment setup uses the following audited stable versions:

| Tool | Version and delivery |
| --- | --- |
| Node.js | `24.20.0` LTS arm64 archive, official SHA-256 verified; bundled npm `11.19.0` |
| pnpm | `12.3.4`, installed into `$HOME/.local/bin` |
| TypeScript | `7.0.2`, installed into the same npm global bin |
| Ruby | Ubuntu `ruby` package, installed on demand and verified with `ruby --version` |
| OpenJDK | Ubuntu Resolute `openjdk-25-jdk` (25.0.4+7-1~26.04 at the recorded snapshot) |
| Gradle | Official `9.7.1` binary distribution, fixed SHA-256; selecting Gradle also provisions OpenJDK 25 when needed |

Selecting `pnpm` provisions Node.js 24 before npm installs pnpm and TypeScript. Readiness resolves
`npm prefix -g` and checks the exact Node/npm/pnpm/TypeScript versions from that bin, so visible and
hidden sessions do not depend on profile-specific `PATH` state. The active rootfs marker is
`.kiyori_installed_ok`; `.operit_installed_ok` is accepted only as a one-time historical migration
input and is removed after the new marker is verified. The inherited namespace, AIDL, paths and
protocol identifiers remain compatibility boundaries.
