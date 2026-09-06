# KiyoriTerminalCore working rules

This file applies to the `KiyoriTerminalCore` repository, which is maintained as the `terminal` Git submodule of `Kiyori-CN/Kiyori`.

## Repository and branch boundaries

- Develop, commit, and push maintained changes on `main` only.
- `origin` is `Kiyori-CN/KiyoriTerminalCore`; `upstream` is the read-only source reference `AAswordman/OperitTerminalCore`.
- The Kiyori repository is independently maintained and must not be treated as a GitHub fork for documentation or delivery purposes.
- Do not force-push, rewrite published history, or push Kiyori changes to `upstream`.
- After publishing a child commit, update and validate the `terminal` gitlink in the Kiyori parent repository.

## Compatibility boundaries

- Preserve the namespace `com.ai.assistance.operit.terminal`, AIDL contracts, persisted paths, and externally consumed identifiers unless a separate migration plan is approved.
- Repository branding may use `KiyoriTerminalCore`; compatibility identifiers do not imply current product ownership or require a package rename.
- Keep upstream authorship, source attribution, and license notices accurate even though the repository is independently maintained and private.
- Do not add an independent application-update or remote-announcement channel. Distribution is owned by the Kiyori parent project.

## Documentation rules

- `README.md` describes integration, development, validation, and troubleshooting for contributors.
- `CONTEXT.md` contains stable ownership, protocol, compatibility, lifecycle, rootfs, and security invariants.
- `tools/rootfs/ubuntu-26.04.1/README.md` is authoritative for rootfs inputs, deterministic build steps, output locks, and archive verification.
- Update the owning document when behavior, interfaces, configuration, paths, or validation contracts change. Do not turn transient task status into a stable context statement.

## Validation and delivery

- Keep generated `build/` and `.cxx/` content out of Git.
- Run `git diff --check` for every change.
- When build validation is authorized, use the parent repository Gradle wrapper and include at least `:terminal:assembleDebug`; use the full parent `assembleDebug` gate for gitlink or integration changes.
- Run `:terminal:testDebugUnitTest` for terminal logic changes. Set `KIYORI_PTY_WSL_DISTRO` on Windows when real-Bash PTY coverage is required.
- Report the child commit, parent gitlink commit, build result, remote repository visibility/lineage, and remaining device verification separately.
