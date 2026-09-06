# Ubuntu 26.04.1 rootfs builder

This directory builds the arm64 Ubuntu userspace embedded by KiyoriTerminalCore. It is a build-time pipeline, not an Android runtime updater and not a source of runtime APT configuration.

## Fixed inputs

`build.sh` verifies Canonical's signed `SHA256SUMS` with the installed Ubuntu archive keyring and requires the pinned Ubuntu Base digest before extraction. Package installation uses the frozen Ubuntu Snapshot `20260827T205830Z`. The following inputs define one rootfs revision:

- Canonical Ubuntu Base 26.04.1 arm64 archive and its pinned SHA-256
- Snapshot timestamp and signed archive metadata
- `packages.txt`
- `SOURCE_DATE_EPOCH`
- Archive name and compression format

Changing any of these inputs requires a new Android manifest entry, package lock, archive digest, and migration review.

## Build environment

Run in an isolated Linux environment with root privileges and these commands available:

`curl`, `gpgv`, `qemu-aarch64-static`, `sha256sum`, `tar`, `xz`, and `python3`.

```bash
sudo ./build.sh /absolute/output/directory
```

The builder mounts only the temporary `proc` and `dev` trees it needs, blocks service startup, installs packages with `DEBIAN_FRONTEND=noninteractive`, removes the build-only QEMU binary and policy file, clears machine identity and runtime caches, and removes the frozen Snapshot source before archiving. Cleanup runs on exit, interruption, and termination.

Ubuntu 26.04 may select `rust-coreutils` by default. Kiyori explicitly installs `coreutils-from-gnu`, removes the unused Rust provider, and fails if it remains installed. This preserves the GNU command surface used by historical terminal scripts and avoids an Android-incompatible hard-link farm. The archive is created with `tar --hard-dereference`, deterministic ordering, numeric ownership, a fixed mtime, and single-threaded `xz` compression.

## Outputs

The output directory receives:

- `ubuntu-resolute-arm64-kiyori-v1.tar.xz`
- `ubuntu-resolute-arm64-kiyori-v1.packages.tsv`

The Android asset is copied only after two independent builds produce identical archive SHA-256 values and the archive verifier passes. The checked-in manifest is `terminal/src/main/assets/ubuntu-rootfs-manifest.json`; it records the archive digest, byte size, architecture, package lock, Canonical Base digest, and Snapshot metadata.

The legacy Noble archive at `terminal/tools/rootfs/legacy/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz` is migration/forensics evidence. It is outside Android `assets`, is not included in new APKs, and must not be deleted until the Android migration and device acceptance gates have passed.

## Archive verification

Always verify the final archive bytes, not an intermediate extracted directory:

```bash
sudo ./verify.sh /absolute/output/directory
```

The verifier performs these checks before and after extraction:

- `xz --test` and archive metadata inspection
- no absolute or parent-traversing paths
- no duplicate members or members nested below an earlier symlink
- no symlink that escapes the virtual root
- no hard links, device nodes, or FIFOs
- Ubuntu Resolute release, arm64 loader, GNU coreutils, and required command surface
- no build-only QEMU, policy, Snapshot source, auxiliary cache, or machine identity
- no Noble APT reference
- package lock equality with the archived dpkg database
- arm64 command execution through QEMU

The verifier prints archive member counts, regular-byte totals, symlink counts, hard-link counts, package-lock status, and the final SHA-256. A non-zero exit is a failed rootfs candidate.

## Parent repository gate

From the Kiyori parent checkout, run the lightweight checked-in-asset check:

```powershell
.\.venv\Scripts\python.exe -B ci\script\check_ubuntu_rootfs_asset.py --repository .
```

Then build the terminal module and, when the gitlink changes, the complete Debug APK:

```powershell
.\gradlew.bat :terminal:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :terminal:assembleDebug --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

The asset check and Gradle tasks validate repository integration; they do not replace ARM64 device verification of extraction, PRoot/chroot startup, package installation, or terminal interaction.

## Runtime separation

The build Snapshot is not a runtime source. At runtime, TerminalManager writes the selected Resolute mirror and its `resolute`, `resolute-updates`, `resolute-backports`, and `resolute-security` suites. The build-only source is removed before the archive is emitted so a user device cannot silently receive a second package source.
