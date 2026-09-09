package com.ai.assistance.operit.terminal.utils

import com.ai.assistance.operit.terminal.TerminalEnvironmentContract.shellQuote
import com.ai.assistance.operit.terminal.data.MirrorSource
import java.net.URI

/** URL 同时写入 APT、INI、TOML 与 Shell；先约束配置值，再对 Shell 参数引用。 */
internal fun validMirrorSourceUrl(value: String): Boolean {
    if (value.isBlank() || value.any { it.isWhitespace() || it.isISOControl() || it == '\\' || it == '"' }) return false
    val uri = try { URI(value) } catch (_: Exception) { return false }
    return uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() &&
        (uri.port == -1 || uri.port in 1..65535)
}

/** 列表只显示定位信息，认证信息和查询参数仍原样保存在配置中。 */
internal fun mirrorSourceDisplayUrl(value: String): String {
    val uri = try { URI(value) } catch (_: Exception) { return "…" }
    return if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) {
        "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}${uri.rawPath.orEmpty()}…"
    } else value
}

internal fun validateMirrorSource(source: MirrorSource) {
    require(source.name.isNotBlank() && source.name.none { it.isISOControl() }) { "Invalid mirror source name" }
    require(validMirrorSourceUrl(source.url)) { "Invalid mirror source URL" }
}

internal fun rustSourceEnvironmentCommand(source: MirrorSource): String {
    validateMirrorSource(source)
    val baseUrl = source.url.trimEnd('/')
    return "export RUSTUP_DIST_SERVER=${shellQuote(baseUrl)}\n" +
        "export RUSTUP_UPDATE_ROOT=${shellQuote("$baseUrl/rustup")}"
}

/** 只由本地启动脚本调用。不会向用户当前 PTY 或 SSH 会话派发配置命令。 */
internal fun localSourceConfigurationCommand(
    apt: MirrorSource,
    pip: MirrorSource,
    npm: MirrorSource,
    codename: String,
): String {
    listOf(apt, pip, npm).forEach(::validateMirrorSource)
    require(codename.matches(Regex("[a-z0-9]+"))) { "Invalid Ubuntu codename" }
    val aptLines = aptSourceDistributionLines(apt.url, codename)
    val pipConfig = "[global]\nindex-url = ${pip.url}"
    val uvConfig = "index-url = \"${pip.url}\""
    return """
        configure_sources(){
          printf '%s\n' ${shellQuote(aptLines)} > "${'$'}UBUNTU_PATH/etc/apt/sources.list" &&
          mkdir -p "${'$'}UBUNTU_PATH/root/.config/pip" "${'$'}UBUNTU_PATH/root/.config/uv" &&
          printf '%s\n' ${shellQuote(pipConfig)} > "${'$'}UBUNTU_PATH/root/.config/pip/pip.conf" &&
          printf '%s\n' ${shellQuote(uvConfig)} > "${'$'}UBUNTU_PATH/root/.config/uv/uv.toml" &&
          printf '%s\n' ${shellQuote("registry=${npm.url}")} > "${'$'}UBUNTU_PATH/root/.npmrc"
        }
    """.trimIndent()
}
