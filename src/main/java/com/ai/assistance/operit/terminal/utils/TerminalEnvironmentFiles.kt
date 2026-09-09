package com.ai.assistance.operit.terminal.utils

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** 调用方先验证应用私有目录及挂载；此处不跟随链接，不把删除失败当作成功。 */
internal fun deleteTerminalEnvironmentTree(
    root: Path,
    deletePath: (Path) -> Unit = { Files.deleteIfExists(it) },
) {
    try { Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
    catch (_: NoSuchFileException) { return }
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            deletePath(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
            if (exc != null) throw exc
            deletePath(dir)
            return FileVisitResult.CONTINUE
        }
        override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc
    })
}
