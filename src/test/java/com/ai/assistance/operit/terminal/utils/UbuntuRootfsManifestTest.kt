package com.ai.assistance.operit.terminal.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class UbuntuRootfsManifestTest {
    @Test
    fun parsesAndValidatesPinnedResoluteManifest() {
        val manifest = UbuntuRootfsManifest.parse(
            """
            {
              "schema":"kiyori.rootfs.manifest.v1",
              "distribution":"ubuntu",
              "release":"26.04.1",
              "codename":"resolute",
              "architecture":"arm64",
              "asset":{"filename":"ubuntu-resolute-arm64-kiyori-v1.tar.xz","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","compressedBytes":1,"expandedBytes":2,"archiveMembers":3,"hardlinkMembers":0},
              "packageLock":{"filename":"ubuntu-resolute-arm64-kiyori-v1.packages.tsv","sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","packageCount":4},
              "input":{"baseSha256":"ignored-by-model"},
              "requiredCapabilities":["bash"]
            }
            """.trimIndent()
        )

        assertEquals("resolute", manifest.codename)
        assertEquals("arm64", manifest.architecture)
        assertEquals(4, manifest.packageCount)
        assertEquals(".kiyori_installed_ok", manifest.installedMarkerFilename)
        assertEquals(".kiyori_rootfs_manifest", manifest.manifestFilename)
        assertEquals(".operit_installed_ok", manifest.legacyInstalledMarkerFilename)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAReleaseOtherThanPinnedCandidate() {
        UbuntuRootfsManifest.parse(
            """
            {
              "schema":"kiyori.rootfs.manifest.v1",
              "distribution":"ubuntu",
              "release":"24.04.1",
              "codename":"noble",
              "architecture":"arm64",
              "asset":{"filename":"ubuntu-resolute-arm64-kiyori-v1.tar.xz","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","compressedBytes":1,"expandedBytes":2,"archiveMembers":3,"hardlinkMembers":0},
              "packageLock":{"filename":"ubuntu-resolute-arm64-kiyori-v1.packages.tsv","sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","packageCount":4}
            }
            """.trimIndent()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsArchiveWithHardLinkMembers() {
        UbuntuRootfsManifest.parse(
            """
            {
              "schema":"kiyori.rootfs.manifest.v1",
              "distribution":"ubuntu",
              "release":"26.04.1",
              "codename":"resolute",
              "architecture":"arm64",
              "asset":{"filename":"ubuntu-resolute-arm64-kiyori-v1.tar.xz","sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","compressedBytes":1,"expandedBytes":2,"archiveMembers":3,"hardlinkMembers":1},
              "packageLock":{"filename":"ubuntu-resolute-arm64-kiyori-v1.packages.tsv","sha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","packageCount":4}
            }
            """.trimIndent()
        )
    }
}
