#!/usr/bin/env bash
set -euo pipefail

readonly OUTPUT_NAME="ubuntu-resolute-arm64-kiyori-v1.tar.xz"
readonly PACKAGE_LOCK_NAME="ubuntu-resolute-arm64-kiyori-v1.packages.tsv"

usage() {
    printf 'Usage: %s OUTPUT_DIRECTORY\n' "$0" >&2
}

if [[ $# -ne 1 ]]; then
    usage
    exit 2
fi

if [[ "$(id -u)" -ne 0 ]]; then
    printf 'This verifier must run as root inside an isolated Linux environment.\n' >&2
    exit 1
fi

for command in cmp dpkg-query file python3 qemu-aarch64-static sha256sum tar xz; do
    if ! command -v "$command" >/dev/null 2>&1; then
        printf 'Missing required verification command: %s\n' "$command" >&2
        exit 1
    fi
done

readonly OUTPUT_DIR="$(readlink -m -- "$1")"
readonly ARCHIVE="${OUTPUT_DIR}/${OUTPUT_NAME}"
readonly PACKAGE_LOCK="${OUTPUT_DIR}/${PACKAGE_LOCK_NAME}"
readonly WORK_DIR="$(mktemp -d -t kiyori-ubuntu-rootfs-verify.XXXXXXXX)"
readonly ROOTFS_DIR="${WORK_DIR}/rootfs"
readonly ACTUAL_PACKAGE_LOCK="${WORK_DIR}/actual-packages.tsv"

cleanup() {
    rm -rf -- "$WORK_DIR"
}
trap cleanup EXIT INT TERM

if [[ ! -s "$ARCHIVE" ]]; then
    printf 'Missing rootfs archive: %s\n' "$ARCHIVE" >&2
    exit 1
fi
if [[ ! -s "$PACKAGE_LOCK" ]]; then
    printf 'Missing package lock: %s\n' "$PACKAGE_LOCK" >&2
    exit 1
fi

xz --test "$ARCHIVE"

# Inspect archive metadata before extraction. In addition to rejecting absolute or parent-traversing
# member names, reject members nested below an earlier symlink: otherwise a seemingly relative member
# could be written outside the staging tree by an extractor that follows that link.
python3 - "$ARCHIVE" <<'PY'
import posixpath
import sys
import tarfile

archive = sys.argv[1]
seen_names: set[str] = set()
seen_symlinks: set[str] = set()
member_count = 0
regular_bytes = 0
absolute_symlink_count = 0
relative_symlink_count = 0
hardlink_count = 0

with tarfile.open(archive, mode="r:xz") as tar:
    for member in tar:
        member_count += 1
        raw_name = member.name
        while raw_name.startswith("./"):
            raw_name = raw_name[2:]
        if raw_name in ("", "."):
            continue
        if raw_name.startswith("/"):
            raise SystemExit(f"absolute archive member is forbidden: {member.name!r}")

        name = posixpath.normpath(raw_name)
        if name == ".." or name.startswith("../"):
            raise SystemExit(f"parent-traversing archive member is forbidden: {member.name!r}")
        if name in seen_names:
            raise SystemExit(f"duplicate archive member is forbidden: {member.name!r}")

        components = name.split("/")
        for index in range(1, len(components)):
            ancestor = "/".join(components[:index])
            if ancestor in seen_symlinks:
                raise SystemExit(
                    f"archive member is nested below an earlier symlink: {member.name!r} via {ancestor!r}"
                )

        if member.ischr() or member.isblk() or member.isfifo():
            raise SystemExit(f"device or FIFO archive member is forbidden: {member.name!r}")
        if member.islnk():
            hardlink_count += 1
            raise SystemExit(
                f"hard-link archive member is forbidden on Android extraction filesystems: {member.name!r} -> {member.linkname!r}"
            )
        if member.isreg():
            regular_bytes += member.size
        if member.issym():
            seen_symlinks.add(name)
            if member.linkname.startswith("/"):
                absolute_symlink_count += 1
            else:
                relative_symlink_count += 1

                # Count lexical depth instead of resolving against the build host. Relative links may
                # point elsewhere inside the virtual root, but must never climb above that root.
                depth = len(posixpath.dirname(name).split("/"))
                if posixpath.dirname(name) in ("", "."):
                    depth = 0
                for part in member.linkname.split("/"):
                    if part in ("", "."):
                        continue
                    if part == "..":
                        if depth == 0:
                            raise SystemExit(
                                f"relative symlink escapes the virtual root: {member.name!r} -> {member.linkname!r}"
                            )
                        depth -= 1
                    else:
                        depth += 1

        seen_names.add(name)

print(f"archive_members={member_count}")
print(f"archive_regular_bytes={regular_bytes}")
print(f"archive_absolute_symlinks={absolute_symlink_count}")
print(f"archive_relative_symlinks={relative_symlink_count}")
print(f"archive_hardlinks={hardlink_count}")
PY

mkdir -p -- "$ROOTFS_DIR"
tar --extract --xz --numeric-owner --file "$ARCHIVE" --directory "$ROOTFS_DIR"

grep -Fx 'VERSION_ID="26.04"' "${ROOTFS_DIR}/etc/os-release" >/dev/null
grep -Fx 'VERSION_CODENAME=resolute' "${ROOTFS_DIR}/etc/os-release" >/dev/null
if grep -R -n -E '(^|[^[:alpha:]])noble([^[:alpha:]]|$)' \
    "${ROOTFS_DIR}/etc/apt" >/dev/null 2>&1; then
    printf 'The archived APT configuration still references Noble.\n' >&2
    exit 1
fi
if [[ -e "${ROOTFS_DIR}/etc/apt/sources.list.d/kiyori-build.sources" ]]; then
    printf 'The build-only Snapshot source remains in the runtime rootfs archive.\n' >&2
    exit 1
fi

test -s "${ROOTFS_DIR}/etc/ssl/certs/ca-certificates.crt"
test -e "${ROOTFS_DIR}/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
file "${ROOTFS_DIR}/bin/bash" | grep -F 'ARM aarch64' >/dev/null
if find "$ROOTFS_DIR" -xdev -name qemu-aarch64-static -print -quit | grep -q .; then
    printf 'The build-only QEMU binary remains in the rootfs archive.\n' >&2
    exit 1
fi
if [[ -e "${ROOTFS_DIR}/usr/sbin/policy-rc.d" ]]; then
    printf 'The build-only policy-rc.d remains in the rootfs archive.\n' >&2
    exit 1
fi
if [[ -e "${ROOTFS_DIR}/etc/apt/apt.conf.d/98kiyori-build-ca" \
    || -e "${ROOTFS_DIR}/etc/apt/apt.conf.d/99kiyori-snapshot" ]]; then
    printf 'A build-only APT configuration remains in the rootfs archive.\n' >&2
    exit 1
fi
if [[ -e "${ROOTFS_DIR}/var/cache/ldconfig/aux-cache" ]]; then
    printf 'The non-deterministic ldconfig auxiliary cache remains in the rootfs archive.\n' >&2
    exit 1
fi
for machine_id in "${ROOTFS_DIR}/etc/machine-id" "${ROOTFS_DIR}/var/lib/dbus/machine-id"; do
    if [[ -s "$machine_id" ]]; then
        printf 'A machine identity remains in the rootfs archive: %s\n' "$machine_id" >&2
        exit 1
    fi
done

LC_ALL=C dpkg-query --admindir="${ROOTFS_DIR}/var/lib/dpkg" --show \
    --showformat='${binary:Package}\t${Version}\t${Architecture}\t${Installed-Size}\n' \
    | LC_ALL=C sort > "$ACTUAL_PACKAGE_LOCK"
cmp "$PACKAGE_LOCK" "$ACTUAL_PACKAGE_LOCK"

if [[ "$(dpkg-query --admindir="${ROOTFS_DIR}/var/lib/dpkg" --showformat='${Status}' \
    --show coreutils-from-gnu)" != "install ok installed" ]]; then
    printf 'GNU coreutils compatibility package is not installed.\n' >&2
    exit 1
fi
if dpkg-query --admindir="${ROOTFS_DIR}/var/lib/dpkg" --showformat='${Status}' \
    --show coreutils-from-uutils 2>/dev/null | grep -Fx 'install ok installed' >/dev/null; then
    printf 'rust-coreutils remains selected for the standard command surface.\n' >&2
    exit 1
fi
if dpkg-query --admindir="${ROOTFS_DIR}/var/lib/dpkg" --showformat='${Status}' \
    --show rust-coreutils 2>/dev/null | grep -Fx 'install ok installed' >/dev/null; then
    printf 'rust-coreutils remains in the hardlink-free GNU rootfs.\n' >&2
    exit 1
fi

# Re-run the compatibility probes from the bytes that will actually be embedded, not from the
# pre-archive build tree. The temporary QEMU binary is removed again before final size reporting.
install -m 0755 "$(command -v qemu-aarch64-static)" "${ROOTFS_DIR}/usr/bin/qemu-aarch64-static"
env -i \
    HOME=/root \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    LC_ALL=C.UTF-8 \
    chroot "$ROOTFS_DIR" /usr/bin/qemu-aarch64-static \
    /bin/bash --noprofile --norc -euo pipefail -c '
        test "$(dpkg --print-architecture)" = arm64
        test "$(. /etc/os-release && printf "%s" "$VERSION_ID")" = 26.04
        /usr/bin/env --version | grep -F "env (GNU coreutils)" >/dev/null
        /usr/bin/ls --version | grep -F "ls (GNU coreutils)" >/dev/null
        python3 --version
        python3 -m pip --version
        ssh -V
        curl --version | head -n 1
    '
rm -f -- "${ROOTFS_DIR}/usr/bin/qemu-aarch64-static"

sha256sum "$ARCHIVE"
printf 'archive_bytes=%s\n' "$(stat -c '%s' "$ARCHIVE")"
printf 'expanded_bytes=%s\n' "$(du -sb "$ROOTFS_DIR" | cut -f1)"
printf 'package_count=%s\n' "$(wc -l < "$PACKAGE_LOCK")"
printf 'verified_archive=%s\n' "$ARCHIVE"
