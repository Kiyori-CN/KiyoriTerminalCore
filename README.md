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

## Usage

Kiyori integrates this repository as the `terminal` Git submodule and pins an exact commit for reproducible builds. A client can bind to `TerminalService` for background operation and IPC, or access the `TerminalManager` singleton directly when running in the same process.

Repository branding does not rename the inherited `com.ai.assistance.operit.terminal` namespace or AIDL contracts. Those identifiers remain compatibility boundaries; see [CONTEXT.md](CONTEXT.md).
