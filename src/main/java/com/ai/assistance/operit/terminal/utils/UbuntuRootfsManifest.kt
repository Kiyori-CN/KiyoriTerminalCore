package com.ai.assistance.operit.terminal.utils

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Immutable metadata for the one Ubuntu rootfs asset selected by the terminal runtime.
 *
 * The manifest is shipped beside the archive so the runtime never has to infer a release from a
 * filename. Hash and size validation happen before the archive can be used by an install script.
 */
data class UbuntuRootfsManifest(
    val schema: String,
    val distribution: String,
    val release: String,
    val codename: String,
    val architecture: String,
    val assetFilename: String,
    val assetSha256: String,
    val compressedBytes: Long,
    val expandedBytes: Long,
    val archiveMembers: Int,
    val hardlinkMembers: Int,
    val packageLockFilename: String,
    val packageLockSha256: String,
    val packageCount: Int,
    val installedMarkerFilename: String,
    val manifestFilename: String,
    val legacyInstalledMarkerFilename: String,
) {
    companion object {
        private const val EXPECTED_SCHEMA = "kiyori.rootfs.manifest.v1"
        private const val SHA256_LENGTH = 64
        private val JSON = Json { ignoreUnknownKeys = true }

        fun parse(jsonText: String): UbuntuRootfsManifest {
            val document = JSON.decodeFromString<ManifestDocument>(jsonText)
            val manifest = UbuntuRootfsManifest(
                schema = document.schema,
                distribution = document.distribution,
                release = document.release,
                codename = document.codename,
                architecture = document.architecture,
                assetFilename = document.asset.filename,
                assetSha256 = document.asset.sha256,
                compressedBytes = document.asset.compressedBytes,
                expandedBytes = document.asset.expandedBytes,
                archiveMembers = document.asset.archiveMembers,
                hardlinkMembers = document.asset.hardlinkMembers,
                packageLockFilename = document.packageLock.filename,
                packageLockSha256 = document.packageLock.sha256,
                packageCount = document.packageLock.packageCount,
                installedMarkerFilename = document.markers.installed,
                manifestFilename = document.markers.manifest,
                legacyInstalledMarkerFilename = document.markers.legacyInstalled,
            )
            manifest.validate()
            return manifest
        }

        @Serializable
        private data class ManifestDocument(
            val schema: String,
            val distribution: String,
            val release: String,
            val codename: String,
            val architecture: String,
            val asset: AssetDocument,
            val packageLock: PackageLockDocument,
            val markers: MarkerDocument = MarkerDocument(),
        )

        @Serializable
        private data class AssetDocument(
            val filename: String,
            val sha256: String,
            val compressedBytes: Long,
            val expandedBytes: Long,
            val archiveMembers: Int,
            val hardlinkMembers: Int,
        )

        @Serializable
        private data class PackageLockDocument(
            val filename: String,
            val sha256: String,
            val packageCount: Int,
        )

        @Serializable
        private data class MarkerDocument(
            val installed: String = ".kiyori_installed_ok",
            val manifest: String = ".kiyori_rootfs_manifest",
            val legacyInstalled: String = ".operit_installed_ok",
        )
    }

    fun validate() {
        require(schema == EXPECTED_SCHEMA) { "Unsupported Ubuntu rootfs manifest schema: $schema" }
        require(distribution == "ubuntu") { "Unsupported rootfs distribution: $distribution" }
        require(release == "26.04.1") { "Unexpected Ubuntu rootfs release: $release" }
        require(codename == "resolute") { "Unexpected Ubuntu rootfs codename: $codename" }
        require(architecture == "arm64") { "Unsupported Ubuntu rootfs architecture: $architecture" }
        require(assetFilename == "ubuntu-resolute-arm64-kiyori-v1.tar.xz") {
            "Unexpected Ubuntu rootfs asset filename: $assetFilename"
        }
        require(assetSha256.length == SHA256_LENGTH && assetSha256.all { it in "0123456789abcdef" }) {
            "Ubuntu rootfs asset SHA-256 is not lowercase hexadecimal"
        }
        require(compressedBytes > 0L) { "Ubuntu rootfs compressed size must be positive" }
        require(expandedBytes > 0L) { "Ubuntu rootfs expanded size must be positive" }
        require(archiveMembers > 0) { "Ubuntu rootfs archive member count must be positive" }
        require(hardlinkMembers == 0) {
            "Ubuntu rootfs archive must not contain hard-link members on Android: $hardlinkMembers"
        }
        require(packageLockFilename == "ubuntu-resolute-arm64-kiyori-v1.packages.tsv") {
            "Unexpected Ubuntu rootfs package lock filename: $packageLockFilename"
        }
        require(packageLockSha256.length == SHA256_LENGTH && packageLockSha256.all { it in "0123456789abcdef" }) {
            "Ubuntu rootfs package lock SHA-256 is not lowercase hexadecimal"
        }
        require(packageCount > 0) { "Ubuntu rootfs package count must be positive" }
        require(installedMarkerFilename == ".kiyori_installed_ok") {
            "Unexpected active Ubuntu installation marker: $installedMarkerFilename"
        }
        require(manifestFilename == ".kiyori_rootfs_manifest") {
            "Unexpected Ubuntu rootfs manifest marker: $manifestFilename"
        }
        require(legacyInstalledMarkerFilename == ".operit_installed_ok") {
            "Unexpected historical Ubuntu installation marker: $legacyInstalledMarkerFilename"
        }
    }
}
