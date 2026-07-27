package com.ai.assistance.operit.terminal

/**
 * Shared contract for the Node.js toolchain installed inside Kiyori's Ubuntu environment.
 *
 * The parent application and the terminal setup screen must use the same readiness command so a
 * partially installed toolchain cannot be reported as ready.
 */
object TerminalEnvironmentContract {
    internal const val NODE_TOOLCHAIN_READY_MARKER = "__KIYORI_NODE_TOOLCHAIN_READY__"
    internal const val REQUIRED_NODE_MAJOR_VERSION = 24

    const val NODE_TOOLCHAIN_CHECK_COMMAND =
        "global_bin=\"${'$'}(npm prefix -g)/bin\" && " +
            "node -e \"process.exit(Number(process.versions.node.split('.')[0]) >= " +
            "$REQUIRED_NODE_MAJOR_VERSION ? 0 : 1)\" >/dev/null 2>&1 && " +
            "\"${'$'}global_bin/pnpm\" --version >/dev/null 2>&1 && " +
            "\"${'$'}global_bin/tsc\" --version >/dev/null 2>&1 && " +
            "printf '$NODE_TOOLCHAIN_READY_MARKER\\n'"

    fun isNodeToolchainReady(output: String?): Boolean =
        output
            ?.lineSequence()
            ?.any { line -> line.trim() == NODE_TOOLCHAIN_READY_MARKER }
            ?: false

    internal fun buildNodePackageSetupCommands(packages: List<String>): List<String> {
        require(packages.isNotEmpty()) { "At least one global Node.js package is required" }

        return listOf(
            "npm config set registry https://registry.npmmirror.com/",
            "npm cache clean --force",
            // Keep pnpm and TypeScript in npm's single global bin. Readiness resolves this exact
            // prefix instead of assuming every visible or hidden shell inherited the same PATH.
            "npm install -g pnpm ${packages.joinToString(" ")}",
        )
    }
}
