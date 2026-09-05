package com.ai.assistance.operit.terminal

/**
 * Shared contract for the Node.js toolchain installed inside Kiyori's Ubuntu environment.
 *
 * The parent application and the terminal setup screen must use the same readiness command so a
 * partially installed toolchain cannot be reported as ready.
 */
object TerminalEnvironmentContract {
    internal const val NODE_TOOLCHAIN_READY_MARKER = "__KIYORI_NODE_TOOLCHAIN_READY__"
    internal const val NODE_LTS_VERSION = "24.20.0"
    internal const val NODE_NPM_VERSION = "11.19.0"
    internal const val PNPM_VERSION = "12.3.4"
    internal const val TYPESCRIPT_VERSION = "7.0.2"
    internal const val GRADLE_VERSION = "9.7.1"
    internal const val GRADLE_REQUIRED_JAVA_MAJOR = 25
    internal const val OPENJDK_PACKAGE_ID = "openjdk-25"
    internal const val OPENJDK_APT_PACKAGE = "openjdk-25-jdk"
    internal const val RUBY_PACKAGE_ID = "ruby"
    internal const val RUBY_APT_PACKAGE = "ruby"
    internal const val GRADLE_SHA256 = "acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"
    internal const val NODE_LTS_SHA256 = "5f4ddab610c1ab2016b3c227cebdbf6d9495161487e4739c7b90090595f465f7"
    internal const val PIPX_BIN_DIR = "\$HOME/.local/bin"
    internal const val RUSTUP_BIN_DIR = "\$HOME/.cargo/bin"
    internal const val USER_LOCAL_BIN_DIR = "\$HOME/.local/bin"
    private const val DEFAULT_NPM_REGISTRY = "https://registry.npmmirror.com/"
    private const val NONINTERACTIVE_APT_ENV = "DEBIAN_FRONTEND=noninteractive"

    /**
     * Hidden probes intentionally do not load profile files. Keep both the persistent profile
     * update and the current visible shell's PATH activation explicit after user-local installs,
     * otherwise a successful installer and the next capability probe observe different paths.
     */
    internal val PIPX_POST_INSTALL_COMMANDS =
        listOf(
            "pipx ensurepath",
            "export PATH=\"$PIPX_BIN_DIR:\$PATH\"",
        )

    internal val RUSTUP_POST_INSTALL_COMMANDS =
        listOf(
            "source \"\$HOME/.cargo/env\"",
        )

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
        "PATH=\"${'$'}HOME/.local/bin:${'$'}PATH\" NPM_CONFIG_PREFIX=\"${'$'}HOME/.local\" " +
            "global_bin=\"${'$'}(NPM_CONFIG_PREFIX=\"${'$'}HOME/.local\" PATH=\"${'$'}HOME/.local/bin:${'$'}PATH\" npm prefix -g)/bin\" && " +
            "test \"${'$'}(PATH=\"${'$'}HOME/.local/bin:${'$'}PATH\" node -p 'process.version')\" = \"v$NODE_LTS_VERSION\" && " +
            "test \"${'$'}(PATH=\"${'$'}HOME/.local/bin:${'$'}PATH\" npm --version)\" = \"$NODE_NPM_VERSION\" && " +
            "test \"${'$'}(\"${'$'}global_bin/pnpm\" --version)\" = \"$PNPM_VERSION\" && " +
            "test \"${'$'}(\"${'$'}global_bin/tsc\" --version)\" = \"Version $TYPESCRIPT_VERSION\" && " +
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
            "NPM_CONFIG_PREFIX=\"${'$'}HOME/.local\" npm config set prefix \"${'$'}HOME/.local\"",
            "npm cache clean --force",
            // Keep pnpm and TypeScript in npm's single global bin. Readiness resolves this exact
            // prefix instead of assuming every visible or hidden shell inherited the same PATH.
            "NPM_CONFIG_PREFIX=\"${'$'}HOME/.local\" npm install -g ${shellQuote("pnpm@$PNPM_VERSION")} " +
                packages.joinToString(" ") { packageName -> shellQuote(pinNodePackage(packageName)) },
        )
    }

    internal fun buildAptInstallCommand(packages: Collection<String>): String {
        require(packages.isNotEmpty()) { "At least one apt package is required" }
        return "$NONINTERACTIVE_APT_ENV apt-get install -y " +
            packages.joinToString(" ") { packageName -> shellQuote(packageName) }
    }

    internal fun buildNodeJsInstallCommand(): String = """
        (
        set -eu
        test "${'$'}(uname -m)" = "aarch64"
        node_version="$NODE_LTS_VERSION"
        node_root="${'$'}HOME/.local/opt/node-v$NODE_LTS_VERSION-linux-arm64"
        node_archive="${'$'}HOME/.cache/kiyori/node-v$NODE_LTS_VERSION-linux-arm64.tar.xz"
        node_stage="${'$'}HOME/.cache/kiyori/node-stage-${'$'}${'$'}"
        node_url="https://nodejs.org/dist/v$NODE_LTS_VERSION/node-v$NODE_LTS_VERSION-linux-arm64.tar.xz"
        mkdir -p "${'$'}HOME/.local/opt" "${'$'}HOME/.local/bin" "${'$'}HOME/.cache/kiyori"
        if [ -L "${'$'}node_root" ] || [ -L "${'$'}node_archive" ] || [ -L "${'$'}node_stage" ]; then
          printf 'Refusing to follow a symlink in the Node.js installation paths\\n' >&2
          exit 1
        fi
        if [ -e "${'$'}node_root" ] || [ -L "${'$'}node_root" ]; then
          test -x "${'$'}node_root/bin/node"
          test "${'$'}("${'$'}node_root/bin/node" -p 'process.version')" = "v${'$'}node_version"
        else
          rm -rf "${'$'}node_stage"
          mkdir -p "${'$'}node_stage"
          curl --fail --location --retry 3 --retry-delay 1 --connect-timeout 15 --max-time 600 \
            -o "${'$'}node_archive" "${'$'}node_url"
          printf '%s  %s\n' '${NODE_LTS_SHA256}' "${'$'}node_archive" | sha256sum -c -
          tar -xJf "${'$'}node_archive" -C "${'$'}node_stage"
          node_extracted="${'$'}node_stage/node-v${'$'}node_version-linux-arm64"
          test -x "${'$'}node_extracted/bin/node"
          mv "${'$'}node_extracted" "${'$'}node_root"
        fi
        for tool in node npm npx; do
          link="${'$'}HOME/.local/bin/${'$'}tool"
          if [ -e "${'$'}link" ] && [ ! -L "${'$'}link" ]; then
            printf 'Refusing to replace a non-symlink at %s\n' "${'$'}link" >&2
            exit 1
          fi
          ln -sfn "${'$'}node_root/bin/${'$'}tool" "${'$'}link"
        done
        NPM_CONFIG_PREFIX="${'$'}HOME/.local" "${'$'}HOME/.local/bin/npm" config set prefix "${'$'}HOME/.local"
        profile="${'$'}HOME/.profile"
        path_line='export PATH="${'$'}HOME/.local/bin:${'$'}PATH"'
        if ! grep -Fqx "${'$'}path_line" "${'$'}profile" 2>/dev/null; then
          printf '\n%s\n' "${'$'}path_line" >> "${'$'}profile"
        fi
        export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
        rm -f "${'$'}node_archive"
        rm -rf "${'$'}node_stage"
        test "${'$'}("${'$'}HOME/.local/bin/node" -p 'process.version')" = "v${'$'}node_version"
        test "${'$'}("${'$'}HOME/.local/bin/npm" --version)" = "$NODE_NPM_VERSION"
        "${'$'}HOME/.local/bin/node" --version
        "${'$'}HOME/.local/bin/npm" --version
        ) && export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
    """.trimIndent()

    internal fun buildGradleInstallCommand(): String = """
        (
        set -eu
        gradle_version="$GRADLE_VERSION"
        gradle_root="${'$'}HOME/.local/opt/gradle-$GRADLE_VERSION"
        gradle_archive="${'$'}HOME/.cache/kiyori/gradle-$GRADLE_VERSION-bin.zip"
        gradle_stage="${'$'}HOME/.cache/kiyori/gradle-stage-${'$'}${'$'}"
        gradle_url="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
        mkdir -p "${'$'}HOME/.local/opt" "${'$'}HOME/.local/bin" "${'$'}HOME/.cache/kiyori"
        if [ -L "${'$'}gradle_root" ] || [ -L "${'$'}gradle_archive" ] || [ -L "${'$'}gradle_stage" ]; then
          printf 'Refusing to follow a symlink in the Gradle installation paths\\n' >&2
          exit 1
        fi
        java -version 2>&1 | grep -E 'version \"$GRADLE_REQUIRED_JAVA_MAJOR([.]|\")' >/dev/null
        if [ -e "${'$'}gradle_root" ] || [ -L "${'$'}gradle_root" ]; then
          test -x "${'$'}gradle_root/bin/gradle"
          "${'$'}gradle_root/bin/gradle" --version | grep -F "Gradle ${'$'}gradle_version" >/dev/null
        else
          rm -rf "${'$'}gradle_stage"
          mkdir -p "${'$'}gradle_stage"
          curl --fail --location --retry 3 --retry-delay 1 --connect-timeout 15 --max-time 600 \
            -o "${'$'}gradle_archive" "${'$'}gradle_url"
          printf '%s  %s\n' '${GRADLE_SHA256}' "${'$'}gradle_archive" | sha256sum -c -
          unzip -q "${'$'}gradle_archive" -d "${'$'}gradle_stage"
          gradle_extracted="${'$'}gradle_stage/gradle-${'$'}gradle_version"
          test -x "${'$'}gradle_extracted/bin/gradle"
          mv "${'$'}gradle_extracted" "${'$'}gradle_root"
        fi
        link="${'$'}HOME/.local/bin/gradle"
        if [ -e "${'$'}link" ] && [ ! -L "${'$'}link" ]; then
          printf 'Refusing to replace a non-symlink at %s\n' "${'$'}link" >&2
          exit 1
        fi
        ln -sfn "${'$'}gradle_root/bin/gradle" "${'$'}link"
        profile="${'$'}HOME/.profile"
        path_line='export PATH="${'$'}HOME/.local/bin:${'$'}PATH"'
        if ! grep -Fqx "${'$'}path_line" "${'$'}profile" 2>/dev/null; then
          printf '\n%s\n' "${'$'}path_line" >> "${'$'}profile"
        fi
        export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
        rm -f "${'$'}gradle_archive"
        rm -rf "${'$'}gradle_stage"
        "${'$'}HOME/.local/bin/gradle" --version | grep -F "Gradle ${'$'}gradle_version"
        ) && export PATH="${'$'}HOME/.local/bin:${'$'}PATH"
    """.trimIndent()

    internal fun isNodeRuntimeReady(output: String?): Boolean {
        val version = output
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull { line -> line.startsWith("v") }
            ?: return false
        return version == "v$NODE_LTS_VERSION"
    }

    internal fun isGradleReady(output: String?): Boolean =
        output?.lineSequence()?.any { line -> line.trim() == "Gradle $GRADLE_VERSION" } == true

    private fun pinNodePackage(packageName: String): String =
        when (packageName) {
            "typescript" -> "typescript@$TYPESCRIPT_VERSION"
            else -> packageName
        }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\\''")}'"
}
