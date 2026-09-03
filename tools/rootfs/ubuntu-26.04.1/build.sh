#!/usr/bin/env bash
set -euo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly BASE_NAME="ubuntu-base-26.04.1-base-arm64.tar.gz"
readonly BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/${BASE_NAME}"
readonly SUMS_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/26.04/release/SHA256SUMS"
readonly SUMS_SIGNATURE_URL="${SUMS_URL}.gpg"
readonly BASE_SHA256="5a1906794ced63a71a8119c3f211ef5f0bbe0a243001b4bbd41fdf80c5b219fd"
readonly SNAPSHOT="20260827T205830Z"
readonly SNAPSHOT_URL="https://snapshot.ubuntu.com/ubuntu/${SNAPSHOT}/"
readonly SOURCE_DATE_EPOCH="1787864310"
readonly OUTPUT_NAME="ubuntu-resolute-arm64-kiyori-v1.tar.xz"
readonly PACKAGE_LIST="${SCRIPT_DIR}/packages.txt"
readonly UBUNTU_KEYRING="/usr/share/keyrings/ubuntu-archive-keyring.gpg"

usage() {
    printf 'Usage: %s OUTPUT_DIRECTORY\n' "$0" >&2
}

if [[ $# -ne 1 ]]; then
    usage
    exit 2
fi

if [[ "$(id -u)" -ne 0 ]]; then
    printf 'This builder must run as root inside an isolated Linux build environment.\n' >&2
    exit 1
fi

for command in curl gpgv qemu-aarch64-static sha256sum tar xz python3; do
    if ! command -v "$command" >/dev/null 2>&1; then
        printf 'Missing required build command: %s\n' "$command" >&2
        exit 1
    fi
done

if [[ ! -r "$UBUNTU_KEYRING" ]]; then
    printf 'Missing Ubuntu archive keyring: %s\n' "$UBUNTU_KEYRING" >&2
    exit 1
fi

if [[ ! -s "$PACKAGE_LIST" ]]; then
    printf 'Missing rootfs package list: %s\n' "$PACKAGE_LIST" >&2
    exit 1
fi

readonly OUTPUT_DIR="$(readlink -m -- "$1")"
readonly WORK_DIR="$(mktemp -d -t kiyori-ubuntu-rootfs.XXXXXXXX)"
readonly ROOTFS_DIR="${WORK_DIR}/rootfs"
readonly BASE_ARCHIVE="${WORK_DIR}/${BASE_NAME}"
readonly OUTPUT_ARCHIVE="${OUTPUT_DIR}/${OUTPUT_NAME}"
readonly PACKAGE_LOCK="${OUTPUT_DIR}/ubuntu-resolute-arm64-kiyori-v1.packages.tsv"

proc_mounted=0
dev_mounted=0

cleanup() {
    if [[ "$dev_mounted" -eq 1 ]]; then
        umount -l "${ROOTFS_DIR}/dev" 2>/dev/null || true
    fi
    if [[ "$proc_mounted" -eq 1 ]]; then
        umount -l "${ROOTFS_DIR}/proc" 2>/dev/null || true
    fi
    rm -rf -- "$WORK_DIR"
}
trap cleanup EXIT INT TERM

mkdir -p -- "$OUTPUT_DIR" "$ROOTFS_DIR"

curl --fail --location --silent --show-error "$SUMS_URL" -o "${WORK_DIR}/SHA256SUMS"
curl --fail --location --silent --show-error "$SUMS_SIGNATURE_URL" -o "${WORK_DIR}/SHA256SUMS.gpg"
gpgv --keyring "$UBUNTU_KEYRING" "${WORK_DIR}/SHA256SUMS.gpg" "${WORK_DIR}/SHA256SUMS"

if ! grep -F "${BASE_SHA256} *${BASE_NAME}" "${WORK_DIR}/SHA256SUMS" >/dev/null; then
    printf 'Canonical checksum list does not contain the pinned Ubuntu Base asset.\n' >&2
    exit 1
fi

curl --fail --location --silent --show-error "$BASE_URL" -o "$BASE_ARCHIVE"
printf '%s  %s\n' "$BASE_SHA256" "$BASE_ARCHIVE" | sha256sum --check --status
tar --extract --gzip --file "$BASE_ARCHIVE" --directory "$ROOTFS_DIR"

install -m 0755 "$(command -v qemu-aarch64-static)" "${ROOTFS_DIR}/usr/bin/qemu-aarch64-static"
cat > "${ROOTFS_DIR}/usr/sbin/policy-rc.d" <<'EOF'
#!/bin/sh
exit 101
EOF
chmod 0755 "${ROOTFS_DIR}/usr/sbin/policy-rc.d"

cp --dereference /etc/resolv.conf "${ROOTFS_DIR}/etc/resolv.conf"
if [[ ! -s /etc/ssl/certs/ca-certificates.crt || ! -d /usr/share/ca-certificates ]]; then
    printf 'The build host does not provide a CA certificate bundle.\n' >&2
    exit 1
fi
mkdir -p "${ROOTFS_DIR}/etc/ssl/certs" "${ROOTFS_DIR}/usr/share/ca-certificates"
# Ubuntu Base intentionally omits ca-certificates. Seed only the build-time TLS trust path; the
# pinned ca-certificates package regenerates this file before the rootfs is archived.
cp -a /etc/ssl/certs/. "${ROOTFS_DIR}/etc/ssl/certs/"
cp -a /usr/share/ca-certificates/. "${ROOTFS_DIR}/usr/share/ca-certificates/"
cat > "${ROOTFS_DIR}/etc/apt/sources.list.d/kiyori-build.sources" <<EOF
Types: deb
URIs: ${SNAPSHOT_URL}
Suites: resolute resolute-updates resolute-backports resolute-security
Components: main restricted universe multiverse
Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg
EOF
rm -f -- "${ROOTFS_DIR}/etc/apt/sources.list.d/ubuntu.sources"
printf '%s\n' 'Acquire::https::CaInfo "/etc/ssl/certs/ca-certificates.crt";' \
    > "${ROOTFS_DIR}/etc/apt/apt.conf.d/98kiyori-build-ca"

mount -t proc proc "${ROOTFS_DIR}/proc"
proc_mounted=1
mount --rbind /dev "${ROOTFS_DIR}/dev"
mount --make-rslave "${ROOTFS_DIR}/dev"
dev_mounted=1

mapfile -t packages < <(sed -e 's/[[:space:]]*#.*$//' -e '/^[[:space:]]*$/d' "$PACKAGE_LIST")
if [[ "${#packages[@]}" -eq 0 ]]; then
    printf 'Rootfs package list is empty.\n' >&2
    exit 1
fi

env -i \
    HOME=/root \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    DEBIAN_FRONTEND=noninteractive \
    LC_ALL=C.UTF-8 \
    SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt \
    chroot "$ROOTFS_DIR" /usr/bin/qemu-aarch64-static \
    /bin/bash --noprofile --norc -euxo pipefail -c "
        printf '%s\n' 'Acquire::Check-Valid-Until \"false\";' > /etc/apt/apt.conf.d/99kiyori-snapshot
        apt-get -o APT::Update::Error-Mode=any update
        apt-get dist-upgrade -y
        # Resolute marks the selected coreutils provider as Essential. Replace it with the GNU
        # provider in one APT transaction; without the explicit acknowledgement APT aborts even
        # though the replacement package supplies the same essential command surface.
        apt-get install -y --allow-remove-essential coreutils-from-gnu coreutils-from-uutils-
        # Ubuntu Base keeps rust-coreutils installed even after the uutils provider is replaced.
        # It is not needed by the GNU provider and owns a large hard-link farm under
        # /usr/lib/cargo/bin/coreutils. Removing it keeps the archive installable on Android filesystems
        # that reject tar hard-link creation and avoids materializing more than a gigabyte of duplicate
        # command binaries when hard links are flattened for the runtime archive.
        apt-get purge -y rust-coreutils
        if dpkg-query --showformat='\${Status}' --show rust-coreutils 2>/dev/null \
            | grep -Fx 'install ok installed' >/dev/null; then
            printf '%s\n' 'rust-coreutils must not remain when GNU coreutils is selected.' >&2
            exit 1
        fi
        /usr/bin/env -i PATH=/usr/bin:/bin /usr/bin/true
        apt-get install -y --no-install-recommends ${packages[*]}
        update-ca-certificates --fresh
        curl --fail --location --silent --show-error \
            '${SNAPSHOT_URL}dists/resolute/InRelease' -o /dev/null
        sed -i 's/^# *en_US.UTF-8 UTF-8/en_US.UTF-8 UTF-8/' /etc/locale.gen
        locale-gen en_US.UTF-8
        dpkg --audit
        apt-get clean
    "

rm -f -- "${ROOTFS_DIR}/usr/bin/qemu-aarch64-static" "${ROOTFS_DIR}/usr/sbin/policy-rc.d"
rm -f -- "${ROOTFS_DIR}/etc/apt/apt.conf.d/98kiyori-build-ca" \
    "${ROOTFS_DIR}/etc/apt/apt.conf.d/99kiyori-snapshot"
# The frozen Snapshot is a build input, not a runtime source. Runtime settings write the user's
# selected Resolute mirror to sources.list; leaving this file would silently add a second source.
rm -f -- "${ROOTFS_DIR}/etc/apt/sources.list.d/kiyori-build.sources"
rm -rf -- "${ROOTFS_DIR}/var/lib/apt/lists"/* "${ROOTFS_DIR}/var/cache/apt/archives"/*
# ldconfig's auxiliary cache records build-filesystem metadata and is not runtime state. Keeping it
# makes otherwise identical rootfs builds byte-different even though /etc/ld.so.cache is stable.
rm -f -- "${ROOTFS_DIR}/var/cache/ldconfig/aux-cache"
rm -rf -- "${ROOTFS_DIR}/tmp"/* "${ROOTFS_DIR}/var/tmp"/*
rm -f -- "${ROOTFS_DIR}/etc/machine-id" "${ROOTFS_DIR}/var/lib/dbus/machine-id"
: > "${ROOTFS_DIR}/etc/resolv.conf"
find "${ROOTFS_DIR}/var/log" -type f -exec truncate -s 0 {} +

umount -l "${ROOTFS_DIR}/dev"
dev_mounted=0
umount -l "${ROOTFS_DIR}/proc"
proc_mounted=0

grep -Fx 'VERSION_ID="26.04"' "${ROOTFS_DIR}/etc/os-release" >/dev/null
grep -Fx 'VERSION_CODENAME=resolute' "${ROOTFS_DIR}/etc/os-release" >/dev/null
test -x "${ROOTFS_DIR}/bin/bash"
test -x "${ROOTFS_DIR}/usr/bin/env"
test -x "${ROOTFS_DIR}/usr/bin/apt-get"
test -x "${ROOTFS_DIR}/usr/bin/dpkg"
test -x "${ROOTFS_DIR}/usr/bin/python3"
test -x "${ROOTFS_DIR}/usr/bin/tar"
test -x "${ROOTFS_DIR}/usr/bin/xz"
test -x "${ROOTFS_DIR}/usr/bin/ssh"
test -x "${ROOTFS_DIR}/usr/bin/curl"
test -e "${ROOTFS_DIR}/lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
if [[ "$(dpkg-query --admindir="${ROOTFS_DIR}/var/lib/dpkg" --showformat='${Status}' --show coreutils-from-gnu)" != "install ok installed" ]]; then
    printf 'GNU coreutils compatibility package is not installed.\n' >&2
    exit 1
fi
if dpkg-query --admindir="${ROOTFS_DIR}/var/lib/dpkg" --showformat='${Status}' --show coreutils-from-uutils 2>/dev/null \
    | grep -Fx 'install ok installed' >/dev/null; then
    printf 'rust-coreutils remains selected for the standard command surface.\n' >&2
    exit 1
fi
dpkg-query --admindir="${ROOTFS_DIR}/var/lib/dpkg" --show \
    --showformat='${binary:Package}\t${Version}\t${Architecture}\t${Installed-Size}\n' \
    | LC_ALL=C sort > "$PACKAGE_LOCK"

if grep -F $'rust-coreutils\t' "$PACKAGE_LOCK" >/dev/null; then
    printf '%s\n' 'rust-coreutils unexpectedly remains in the rootfs package lock.' >&2
    exit 1
fi

rm -f -- "$OUTPUT_ARCHIVE"
tar --create \
    --directory "$ROOTFS_DIR" \
    --sort=name \
    --numeric-owner \
    --owner=0 \
    --group=0 \
    --mtime="@${SOURCE_DATE_EPOCH}" \
    --pax-option=delete=atime,delete=ctime \
    --hard-dereference \
    --format=posix \
    --file=- . \
    | xz --threads=1 --check=crc64 --lzma2=preset=9e > "$OUTPUT_ARCHIVE"

python3 - "$OUTPUT_ARCHIVE" <<'PY'
import sys
import tarfile

archive = sys.argv[1]
with tarfile.open(archive, mode="r:xz") as tar:
    hardlinks = [member.name for member in tar if member.islnk()]
if hardlinks:
    raise SystemExit(
        "rootfs archive contains hard-link members; Android extraction requires a hardlink-free archive: "
        + ", ".join(hardlinks[:5])
    )
print("archive_hardlinks=0")
PY

sha256sum "$OUTPUT_ARCHIVE"
du -sb "$ROOTFS_DIR"
find "$ROOTFS_DIR" -xdev -printf '.' | wc -c
printf 'Package lock: %s\n' "$PACKAGE_LOCK"
printf 'Rootfs asset: %s\n' "$OUTPUT_ARCHIVE"
