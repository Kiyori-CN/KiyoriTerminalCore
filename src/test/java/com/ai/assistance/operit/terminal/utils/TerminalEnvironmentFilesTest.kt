package com.ai.assistance.operit.terminal.utils

import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class TerminalEnvironmentFilesTest {
    @Test fun removesNestedTreeAndAllowsAlreadyMissingTarget() {
        val parent = Files.createTempDirectory("terminal-reset-test")
        try {
            val root = Files.createDirectory(parent.resolve("environment"))
            Files.createDirectories(root.resolve("nested"))
            Files.write(root.resolve("nested/file"), byteArrayOf(1))
            deleteTerminalEnvironmentTree(root)
            assertFalse(Files.exists(root))
            deleteTerminalEnvironmentTree(root)
        } finally { parent.toFile().deleteRecursively() }
    }

    @Test fun propagatesFailureAndPreservesUnremovedData() {
        val root = Files.createTempDirectory("terminal-reset-failure")
        val file = Files.write(root.resolve("keep"), byteArrayOf(7))
        try {
            try {
                deleteTerminalEnvironmentTree(root) { throw IOException("access denied") }
                fail("must not report success")
            } catch (expected: IOException) { assertEquals("access denied", expected.message) }
            assertArrayEquals(byteArrayOf(7), Files.readAllBytes(file))
        } finally { root.toFile().deleteRecursively() }
    }
}
