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
        "node -e \"process.exit(Number(process.versions.node.split('.')[0]) >= " +
            "$REQUIRED_NODE_MAJOR_VERSION ? 0 : 1)\" >/dev/null 2>&1 && " +
            "pnpm --version >/dev/null 2>&1 && " +
            "tsc --version >/dev/null 2>&1 && " +
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
            // Kiyori's visible and hidden Ubuntu shells share npm's global bin through the fixed
            // runtime PATH. Installing both CLIs there avoids a second pnpm-specific PATH owner.
            "npm install -g pnpm ${packages.joinToString(" ")}",
        )
    }
}
