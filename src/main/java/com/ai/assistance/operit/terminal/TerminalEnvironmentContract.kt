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
    internal const val NODE_X64_SHA256 = "2f2c0da162318f0de47665410c7c8c2ed3d36c8f3105de4bbc61176c70a7cbf2"
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
        )

    // 安装版本与可用性不同：npm 可以独立升级，也可以由系统/NVM 安装。每一步在同一
    // export PATH 下执行，保证 npm/tsc 的 /usr/bin/env node 与检测到的解释器一致。
    internal const val TOOL_PATH_COMMAND = "export PATH=\"${'$'}HOME/.local/bin:${'$'}HOME/.cargo/bin:${'$'}PATH\"; hash -r"
    internal const val NODE_RUNTIME_CHECK_COMMAND =
        "($TOOL_PATH_COMMAND; node -e \"const v=process.versions.node.split('.').map(Number); " +
            "process.exit(v[0]>24 || (v[0]===24 && v[1]>=20) ? 0 : 1)\" && " +
            "npm --version && node --version)"

    @JvmField
    val NODE_TOOLCHAIN_CHECK_COMMAND: String = "($TOOL_PATH_COMMAND; $NODE_RUNTIME_CHECK_COMMAND && " +
            "test \"${'$'}(pnpm --version 2>&1)\" = '$PNPM_VERSION' && " +
            "test \"${'$'}(pnpm config get packageImportMethod)\" = copy && " +
            "test \"${'$'}(tsc --version)\" = 'Version $TYPESCRIPT_VERSION' && " +
            "${buildTypeScriptSmokeCommand()} && printf '$NODE_TOOLCHAIN_READY_MARKER\\n')"

    internal fun buildTypeScriptSmokeCommand(): String = bashScript("""
        set -eu
        $TOOL_PATH_COMMAND
        probe_dir="${'$'}(mktemp -d "${'$'}HOME/.kiyori-ts-check.XXXXXXXX")"
        trap 'rm -rf -- "${'$'}probe_dir"' EXIT
        cd "${'$'}probe_dir"
        printf '%s\n' 'const message: string = "kiyori-typescript-ok"; console.log(message);' > main.ts
        tsc --ignoreConfig --target ES2022 --module commonjs --rootDir . --outDir dist main.ts
        test "${'$'}(node dist/main.js)" = 'kiyori-typescript-ok'
    """.trimIndent())

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

        return listOf(bashScript("""
            set -eu
            $TOOL_PATH_COMMAND
            export NPM_CONFIG_PREFIX="${'$'}HOME/.local"
            npm install --global --registry ${shellQuote(registryUrl)} --ignore-scripts=false --include=optional ${shellQuote("pnpm@$PNPM_VERSION")} ${packages.joinToString(" ") { shellQuote(pinNodePackage(it)) }}
            # npm 的全局 ignore-scripts/omit 配置不能留下只会输出版本的 pnpm 占位入口。
            node "${'$'}HOME/.local/lib/node_modules/pnpm/install.js"
            test "${'$'}(pnpm --version 2>&1)" = '$PNPM_VERSION'
            # TS 7 原生编译器按自身路径定位内置库；proot 中硬链接会使路径指向 store。
            # 使用 pnpm 自己的配置入口保留其他键，并对后续项目生效。
            pnpm config set --global packageImportMethod copy
            $NODE_TOOLCHAIN_CHECK_COMMAND
            path_line='export PATH="${'$'}HOME/.local/bin:${'$'}PATH"'
            if ! grep -Fqx "${'$'}path_line" "${'$'}HOME/.profile" 2>/dev/null; then
              printf '\n%s\n' "${'$'}path_line" >> "${'$'}HOME/.profile"
            fi
        """.trimIndent()))
    }

    internal fun buildAptInstallCommand(packages: Collection<String>): String {
        require(packages.isNotEmpty()) { "At least one apt package is required" }
        return "$NONINTERACTIVE_APT_ENV apt-get install -y " +
            packages.joinToString(" ") { packageName -> shellQuote(packageName) }
    }

    internal fun buildNodeJsInstallCommand(): String = bashScript("""
        set -eu
        $TOOL_PATH_COMMAND
        case "${'$'}(uname -m)" in
          aarch64|arm64) node_arch=arm64; node_sha='$NODE_LTS_SHA256' ;;
          x86_64|amd64) node_arch=x64; node_sha='$NODE_X64_SHA256' ;;
          *) printf 'Unsupported Node.js architecture\n' >&2; exit 1 ;;
        esac
        node_version="$NODE_LTS_VERSION"
        node_root="${'$'}HOME/.local/opt/node-v$NODE_LTS_VERSION-linux-${'$'}node_arch"
        node_url="https://nodejs.org/dist/v$NODE_LTS_VERSION/node-v$NODE_LTS_VERSION-linux-${'$'}node_arch.tar.xz"
        mkdir -p "${'$'}HOME/.local/opt" "${'$'}HOME/.local/bin" "${'$'}HOME/.cache/kiyori"
        node_stage="${'$'}(mktemp -d "${'$'}HOME/.cache/kiyori/node-stage.XXXXXXXX")"
        trap 'rm -rf -- "${'$'}node_stage"' EXIT
        node_archive="${'$'}node_stage/node.tar.xz"
        if [ -L "${'$'}node_root" ]; then
          printf 'Refusing to follow a symlink in the Node.js installation paths\\n' >&2
          exit 1
        fi
        if [ -e "${'$'}node_root" ] || [ -L "${'$'}node_root" ]; then
          test -x "${'$'}node_root/bin/node"
          test "${'$'}("${'$'}node_root/bin/node" -p 'process.version')" = "v${'$'}node_version"
        else
          curl --fail --location --retry 3 --retry-delay 1 --connect-timeout 15 --max-time 600 \
            -o "${'$'}node_archive" "${'$'}node_url"
          printf '%s  %s\n' "${'$'}node_sha" "${'$'}node_archive" | sha256sum -c -
          tar -xJf "${'$'}node_archive" -C "${'$'}node_stage"
          node_extracted="${'$'}node_stage/node-v${'$'}node_version-linux-${'$'}node_arch"
          test -x "${'$'}node_extracted/bin/node"
          test "${'$'}("${'$'}node_extracted/bin/node" -p 'process.version')" = "v${'$'}node_version"
          PATH="${'$'}node_extracted/bin:${'$'}PATH" "${'$'}node_extracted/bin/npm" --version
          mv "${'$'}node_extracted" "${'$'}node_root"
        fi
        # 检查全部入口后再激活，避免遇到用户普通文件时只替换了一半链接。
        for tool in node npm npx; do
          link="${'$'}HOME/.local/bin/${'$'}tool"
          if [ -e "${'$'}link" ] && [ ! -L "${'$'}link" ]; then
            printf 'Refusing to replace a non-symlink at %s\n' "${'$'}link" >&2
            exit 1
          fi
        done
        for tool in node npm npx; do
          ln -sfn "${'$'}node_root/bin/${'$'}tool" "${'$'}HOME/.local/bin/${'$'}tool"
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
        "${'$'}HOME/.local/bin/node" --version
        "${'$'}HOME/.local/bin/npm" --version
    """.trimIndent()) + " && export PATH=\"${'$'}HOME/.local/bin:${'$'}PATH\""

    internal fun buildGradleInstallCommand(): String = bashScript("""
        set -eu
        gradle_version="$GRADLE_VERSION"
        gradle_root="${'$'}HOME/.local/opt/gradle-$GRADLE_VERSION"
        gradle_url="https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
        mkdir -p "${'$'}HOME/.local/opt" "${'$'}HOME/.local/bin" "${'$'}HOME/.cache/kiyori"
        gradle_stage="${'$'}(mktemp -d "${'$'}HOME/.cache/kiyori/gradle-stage.XXXXXXXX")"
        trap 'rm -rf -- "${'$'}gradle_stage"' EXIT
        gradle_archive="${'$'}gradle_stage/gradle.zip"
        if [ -L "${'$'}gradle_root" ]; then
          printf 'Refusing to follow a symlink in the Gradle installation paths\\n' >&2
          exit 1
        fi
        java -version 2>&1 | grep -E 'version \"$GRADLE_REQUIRED_JAVA_MAJOR([.]|\")' >/dev/null
        if [ -e "${'$'}gradle_root" ] || [ -L "${'$'}gradle_root" ]; then
          test -x "${'$'}gradle_root/bin/gradle"
          "${'$'}gradle_root/bin/gradle" --version | grep -F "Gradle ${'$'}gradle_version" >/dev/null
        else
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
    """.trimIndent()) + " && export PATH=\"${'$'}HOME/.local/bin:${'$'}PATH\""

    internal fun isNodeRuntimeReady(output: String?): Boolean {
        val version = output
            ?.lineSequence()
            ?.map(String::trim)
            ?.firstOrNull { line -> line.startsWith("v") }
            ?: return false
        val match = Regex("v(\\d+)\\.(\\d+)\\.(\\d+)").matchEntire(version) ?: return false
        val major = match.groupValues[1].toIntOrNull() ?: return false
        val minor = match.groupValues[2].toIntOrNull() ?: return false
        return major > 24 || (major == 24 && minor >= 20)
    }

    internal fun isGradleReady(output: String?): Boolean =
        output?.lineSequence()?.any { line -> line.trim() == "Gradle $GRADLE_VERSION" } == true

    private fun pinNodePackage(packageName: String): String =
        when (packageName) {
            "typescript" -> "typescript@$TYPESCRIPT_VERSION"
            else -> packageName
        }

    internal fun bashScript(script: String): String = "bash --noprofile --norc -c ${shellQuote(script)}"

    internal fun shellQuote(value: String): String =
        "'${value.replace("'", "'\\''")}'"
}
