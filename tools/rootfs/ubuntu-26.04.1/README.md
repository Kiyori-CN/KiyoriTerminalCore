# Ubuntu 26.04.1 rootfs builder

This directory builds the arm64 Ubuntu userspace embedded by KiyoriTerminalCore. It is a build-time
pipeline, not an Android runtime updater.

The input is Canonical Ubuntu Base 26.04.1 for arm64. `build.sh` verifies Canonical's signed
`SHA256SUMS` with the installed Ubuntu archive keyring and requires the pinned source hash before it
extracts anything. Package installation uses the frozen Ubuntu snapshot
`20260827T205830Z`; changing the base asset, snapshot, package list, output format, or source epoch is
a new rootfs revision and requires a new manifest plus Android migration review.

Run inside an isolated Linux build environment with root privileges, `qemu-user-static`, `curl`,
`gpgv`, `tar`, and `xz-utils`:

```bash
sudo ./build.sh /absolute/output/directory
```

The output directory receives the `.tar.xz` asset and a complete package/version/architecture lock.
The build blocks service startup, removes the build-only QEMU binary, empties machine identity and
runtime caches, validates the Resolute/arm64 command surface, and emits a deterministically ordered
archive. Resolute's Ubuntu Base may leave `rust-coreutils` installed after the GNU provider is
selected; the builder removes that unused package because it owns a 114-entry hard-link farm that
Android cannot materialize. `--hard-dereference` then preserves every package path as an independent
regular file (rather than changing dpkg-managed files into symlinks), and the builder fails if any
tar hard-link member remains. Run the builder twice and compare SHA-256 before advancing the Android
asset manifest.

The checked-in asset manifest is `terminal/src/main/assets/ubuntu-rootfs-manifest.json`; its digest,
size, package lock, pinned Canonical Base input, and Snapshot are generated only after two identical
builds plus archive-level verification. The legacy Noble archive remains available at
`terminal/tools/rootfs/legacy/ubuntu-noble-aarch64-pd-v4.18.0.tar.xz` as migration/forensics evidence,
but it is outside Android `assets` and therefore is not duplicated in new APKs. Do not delete the
legacy file or device backups until the Android migration and device acceptance gates have passed.

Audit each candidate from the final archive bytes before comparing the two builds:

```bash
sudo ./verify.sh /absolute/output/directory
```

The verifier rejects unsafe or duplicate archive paths, members nested below an earlier symlink,
relative symlinks that escape the virtual root, hard-link members, and device/FIFO members. It also
compares the package lock with the archived dpkg database, checks that build-only files and machine
identity were removed, and executes the archived arm64 command surface through QEMU.

From the repository root, the lightweight checked-in-asset gate is:

```powershell
.\.venv\Scripts\python.exe -B ci\script\check_ubuntu_rootfs_asset.py --repository .
```

Kiyori explicitly selects Ubuntu's `coreutils-from-gnu` package. Ubuntu 26.04 defaults many commands
to rust-coreutils, but the terminal's historical scripts and output parsing depend on the GNU command
surface. The build fails if `coreutils-from-uutils` remains selected.
