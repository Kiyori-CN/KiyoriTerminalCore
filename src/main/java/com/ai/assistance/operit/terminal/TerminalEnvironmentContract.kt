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
    private const val DEFAULT_NPM_REGISTRY = "https://registry.npmmirror.com/"
    private const val NONINTERACTIVE_APT_ENV = "DEBIAN_FRONTEND=noninteractive"

    /**
     * Environment setup is an unattended batch. Using apt-get with an explicit debconf frontend
     * prevents maintainer scripts from waiting forever for input that the setup coordinator cannot
     * supply while it is awaiting the command completion marker.
     */
    internal val SYSTEM_REPAIR_COMMANDS =
        listOf(
            "$NONINTERACTIVE_APT_ENV dpkg --configure -a",
            "$NONINTERACTIVE_APT_ENV apt-get install -f -y",
            "$NONINTERACTIVE_APT_ENV apt-get update -y",
            "$NONINTERACTIVE_APT_ENV apt-get upgrade -y",
        )

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

    internal fun buildPipConfigurationCommands(indexUrl: String): List<String> {
        require(indexUrl.isNotBlank()) { "A non-empty Python package index URL is required" }

        return listOf(
            "mkdir -p ~/.config/pip",
            "printf '%s\\n' '[global]' > ~/.config/pip/pip.conf",
            "printf '%s\\n' ${shellQuote("index-url = $indexUrl")} >> ~/.config/pip/pip.conf",
            "mkdir -p ~/.config/uv",
            "printf '%s\\n' ${shellQuote("index-url = \"$indexUrl\"")} > ~/.config/uv/uv.toml",
        )
    }

    internal fun buildNodePackageSetupCommands(
        packages: List<String>,
        registryUrl: String = DEFAULT_NPM_REGISTRY,
    ): List<String> {
        require(packages.isNotEmpty()) { "At least one global Node.js package is required" }
        require(registryUrl.isNotBlank()) { "A non-empty Node package registry URL is required" }

        return listOf(
            "npm config set registry ${shellQuote(registryUrl)}",
            "npm cache clean --force",
            // Keep pnpm and TypeScript in npm's single global bin. Readiness resolves this exact
            // prefix instead of assuming every visible or hidden shell inherited the same PATH.
            "npm install -g pnpm ${packages.joinToString(" ") { packageName -> shellQuote(packageName) }}",
        )
    }

    internal fun buildAptInstallCommand(packages: Collection<String>): String {
        require(packages.isNotEmpty()) { "At least one apt package is required" }
        return "$NONINTERACTIVE_APT_ENV apt-get install -y " +
            packages.joinToString(" ") { packageName -> shellQuote(packageName) }
    }

    internal fun buildNodeJsInstallCommand(): String =
        "curl -fsSL https://deb.nodesource.com/setup_24.x | " +
            "$NONINTERACTIVE_APT_ENV bash - && " +
            "$NONINTERACTIVE_APT_ENV apt-get install -y nodejs"

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\\''")}'"
}
