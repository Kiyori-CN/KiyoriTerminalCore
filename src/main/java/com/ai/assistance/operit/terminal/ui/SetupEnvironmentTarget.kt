package com.ai.assistance.operit.terminal.ui

import com.ai.assistance.operit.terminal.TerminalEnvironmentContract
import com.ai.assistance.operit.terminal.provider.type.HiddenExecResult
import java.util.Base64

/** 单次检测的投影，不持有第二套会话或安装状态。身份只用于拒绝跨目标安装。 */
internal data class SetupEnvironmentTarget(
    val identity: String,
    val system: String,
    val architecture: String,
    val user: String,
    val home: String,
    val host: String,
    val privilege: String,
    val aptAvailable: Boolean,
) {
    val canInstall: Boolean
        get() = system == "Linux" && architecture in setOf("aarch64", "arm64", "x86_64", "amd64") &&
            aptAvailable && privilege in setOf("root", "sudo")

    fun bindCommands(commands: List<String>): List<String> = commands.map { command ->
        // 独立 Bash 的 errexit 不会被外层 if/&& 禁用。每一步重新比对身份；SSH 断开、
        // 用户切到另一主机或 HOME/UID 改变时，在任何安装副作用发生前停止。
        TerminalEnvironmentContract.bashScript("""
            set -euo pipefail
            ${TerminalEnvironmentContract.TOOL_PATH_COMMAND}
            if [ "${'$'}($SETUP_IDENTITY_COMMAND)" != ${TerminalEnvironmentContract.shellQuote(identity)} ]; then
              printf 'Environment changed; refresh environment setup before installing.\n' >&2
              exit 1
            fi
            cd "${'$'}HOME"
            $command
        """.trimIndent())
    } + listOf("""
        if [ "${'$'}($SETUP_IDENTITY_COMMAND)" = ${TerminalEnvironmentContract.shellQuote(identity)} ]; then
          ${TerminalEnvironmentContract.TOOL_PATH_COMMAND}
        else
          printf 'Environment changed; PATH activation cancelled.\n' >&2
          false
        fi
    """.trimIndent())

    fun aptCommand(command: String): String = if (privilege == "sudo") "sudo -n env $command" else command
}

internal const val SETUP_IDENTITY_COMMAND =
    "printf '%s\\n' \"${'$'}(uname -s)\" \"${'$'}(uname -m)\" \"${'$'}(hostname)\" " +
        "\"${'$'}(id -u)\" \"${'$'}HOME\" \"${'$'}(cat /etc/machine-id 2>/dev/null)\" | sha256sum | cut -d ' ' -f1"

internal val SETUP_TARGET_COMMAND = """
    command -v timeout >/dev/null && command -v base64 >/dev/null && command -v sha256sum >/dev/null || exit 1
    setup_privilege=none
    if [ "${'$'}(id -u)" = 0 ]; then setup_privilege=root
    elif command -v sudo >/dev/null && timeout 3s sudo -n true </dev/null >/dev/null 2>&1; then setup_privilege=sudo; fi
    setup_apt=0
    if command -v apt-get >/dev/null && command -v dpkg >/dev/null; then setup_apt=1; fi
    setup_target="${'$'}(printf '%s\n' "${'$'}($SETUP_IDENTITY_COMMAND)" "${'$'}(uname -s)" "${'$'}(uname -m)" "${'$'}(id -un)" "${'$'}HOME" "${'$'}(hostname)" "${'$'}setup_privilege" "${'$'}setup_apt" | base64 | tr -d '\n')"
    printf '__KIYORI_ENV_TARGET__:%s\n' "${'$'}setup_target"
""".trimIndent()

internal fun setupEnvironmentTarget(result: HiddenExecResult): SetupEnvironmentTarget? {
    if (!result.isOk || result.exitCode != 0 || result.outputTruncated) return null
    val frames = result.output.lineSequence().filter { it.startsWith("__KIYORI_ENV_TARGET__:") }.toList()
    if (frames.size != 1) return null
    val fields = decodeProbeText(frames.single().substringAfter(':'))?.trimEnd('\n')?.split('\n') ?: return null
    if (fields.size != 8 || !Regex("[a-f0-9]{64}").matches(fields[0])) return null
    return SetupEnvironmentTarget(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5], fields[6], fields[7] == "1")
}

internal fun decodeProbeText(value: String): String? = runCatching {
    require(value.length <= 8192)
    String(Base64.getDecoder().decode(value), Charsets.UTF_8)
        .filter { it == '\n' || it == '\t' || !it.isISOControl() }
}.getOrNull()

internal fun packageProbeDetails(result: HiddenExecResult): Map<String, String> {
    if (!result.isOk || result.exitCode != 0) return emptyMap()
    return result.output.lineSequence().filter { it.startsWith("__KIYORI_ENV_PROBE__:") }
        .mapNotNull { line ->
            val fields = line.split(':', limit = 4)
            if (fields.size != 4) null else decodeProbeText(fields[3])?.let { fields[1] to it.trim().take(2048) }
        }.groupBy({ it.first }, { it.second }).mapNotNull { (id, values) ->
            values.singleOrNull()?.let { id to it }
        }.toMap()
}

internal fun packagePresenceCommand(pkg: PackageItem): String = when (pkg.id) {
    "nodejs" -> "command -v node"
    "pnpm" -> "command -v pnpm || command -v tsc"
    "rust" -> "command -v rustc || command -v cargo"
    "openjdk-25" -> "command -v java"
    "python-is-python3", "python3-venv", "python3-pip" -> "command -v python3"
    "openssh-server" -> "command -v sshd"
    else -> "command -v ${TerminalEnvironmentContract.shellQuote(pkg.id)}"
}

internal fun packageDiagnosticCommand(pkg: PackageItem): String = when (pkg.id) {
    "nodejs" -> "command -v node; node --version; command -v npm; npm --version"
    "pnpm" -> "command -v pnpm; pnpm --version; command -v tsc; tsc --version"
    else -> packagePresenceCommand(pkg)
}
