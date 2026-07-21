# KiyoriTerminalCore working rules

This file applies to the `KiyoriTerminalCore` repository. The repository is maintained as the `terminal` Git submodule of `Kiyori-CN/Kiyori`.

## Repository and branch boundaries

- Develop, commit, and push maintained changes on `main` only.
- `origin` is `Kiyori-CN/KiyoriTerminalCore`; `upstream` is the read-only source repository `AAswordman/OperitTerminalCore`.
- Do not force-push, rewrite published history, or push Kiyori changes to `upstream`.
- After publishing a child commit, update and validate the `terminal` gitlink in the Kiyori parent repository.

## Compatibility boundaries

- Preserve the namespace `com.ai.assistance.operit.terminal`, AIDL contracts, persisted paths, and externally consumed identifiers unless a separate migration plan is approved.
- Repository branding may use `KiyoriTerminalCore`; compatibility identifiers do not imply current product ownership.
- Do not add an independent application-update or remote-announcement channel. Distribution is owned by the Kiyori parent project.

## Validation and delivery

- Keep generated `build/` and `.cxx/` content out of Git.
- Run `git diff --check` for every change.
- When build validation is authorized, use the parent repository Gradle wrapper and include at least `:terminal:assembleDebug`; use the full parent `assembleDebug` gate for gitlink or integration changes.
- Report the child commit, parent gitlink commit, build result, and any remaining device verification separately.
