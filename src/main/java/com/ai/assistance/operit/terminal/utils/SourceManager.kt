package com.ai.assistance.operit.terminal.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.ai.assistance.operit.terminal.data.MirrorSource
import com.ai.assistance.operit.terminal.data.PackageManagerType
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SourceManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("source_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    // 定义所有内置的源
    private val builtInAptSources = listOf(
        MirrorSource("tuna_apt", "清华源", "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/", true),
        MirrorSource("bfsu_apt", "北外源", "https://mirrors.bfsu.edu.cn/ubuntu-ports/", true),
        MirrorSource("aliyun_apt", "阿里源", "https://mirrors.aliyun.com/ubuntu-ports/", true),
        MirrorSource("ustc_apt", "中科大源", "https://mirrors.ustc.edu.cn/ubuntu-ports/", true),
        MirrorSource("official_apt", "官方源", "http://ports.ubuntu.com/ubuntu-ports/", false)
    )

    private val builtInPipSources = listOf(
        MirrorSource("tuna_pip", "清华源", "https://pypi.tuna.tsinghua.edu.cn/simple", true),
        MirrorSource("bfsu_pip", "北外源", "https://mirrors.bfsu.edu.cn/pypi/web/simple", true),
        MirrorSource("aliyun_pip", "阿里源", "https://mirrors.aliyun.com/pypi/simple/", true),
        MirrorSource("ustc_pip", "中科大源", "https://pypi.mirrors.ustc.edu.cn/simple/", true),
        MirrorSource("official_pip", "官方源", "https://pypi.org/simple", true)
    )
    
    private val builtInNpmSources = listOf(
        MirrorSource("taobao_npm", "淘宝源", "https://registry.npmmirror.com/", true),
        MirrorSource("tencent_npm", "腾讯源", "https://mirrors.cloud.tencent.com/npm/", true),
        MirrorSource("huawei_npm", "华为源", "https://repo.huaweicloud.com/repository/npm/", true),
        MirrorSource("official_npm", "官方源", "https://registry.npmjs.org/", true)
    )
    
    private val builtInRustSources = listOf(
        MirrorSource("ustc_rust", "中科大源", "https://mirrors.ustc.edu.cn/rust-static", true),
        MirrorSource("tuna_rust", "清华源", "https://mirrors.tuna.tsinghua.edu.cn/rustup", true),
        MirrorSource("bfsu_rust", "北外源", "https://mirrors.bfsu.edu.cn/rustup", true),
        MirrorSource("sjtu_rust", "上海交大源", "https://mirrors.sjtug.sjtu.edu.cn/rust-static", true),
        MirrorSource("official_rust", "官方源", "https://static.rust-lang.org", true)
    )

    // 获取自定义源
    private fun getCustomSources(pm: PackageManagerType): List<MirrorSource> {
        val key = customSourcesKey(pm)
        val jsonString = prefs.getString(key, null) ?: return emptyList()
        return try {
            json.decodeFromString<List<MirrorSource>>(jsonString)
        } catch (e: Exception) {
            // 序列化异常可能包含原始 URL 和凭据，诊断只保留异常类型。
            throw IllegalStateException("Unable to read custom mirror sources (${e.javaClass.simpleName})")
        }
    }
    
    // 保存自定义源
    fun saveCustomSource(pm: PackageManagerType, source: MirrorSource) {
        validateMirrorSource(source)
        val customSources = getCustomSources(pm).toMutableList()
        // 如果已存在相同ID的源，替换它；否则添加
        val index = customSources.indexOfFirst { it.id == source.id }
        if (index >= 0) {
            customSources[index] = source
        } else {
            customSources.add(source)
        }
        
        val key = customSourcesKey(pm)
        check(prefs.edit().putString(key, json.encodeToString(customSources)).commit()) { "Unable to save mirror source" }
    }
    
    // 删除自定义源，并在同一份偏好提交中维护选中源不变量。
    fun deleteCustomSource(pm: PackageManagerType, sourceId: String) {
        val customSources = getCustomSources(pm).toMutableList()
        customSources.removeAll { it.id == sourceId }

        val selectedKey = selectedSourceKey(pm)
        val selectedId = getSelectedSourceId(pm)
        val editor = prefs.edit().putString(customSourcesKey(pm), json.encodeToString(customSources))
        if (selectedId == sourceId) editor.putString(selectedKey, defaultSourceId(pm))
        check(editor.commit()) { "Unable to delete mirror source" }
    }
    
    // 获取所有源（内置 + 自定义）
    val aptSources: List<MirrorSource>
        get() = builtInAptSources + getCustomSources(PackageManagerType.APT)
    
    val pipSources: List<MirrorSource>
        get() = builtInPipSources + getCustomSources(PackageManagerType.PIP)
    
    val npmSources: List<MirrorSource>
        get() = builtInNpmSources + getCustomSources(PackageManagerType.NPM)
    
    val rustSources: List<MirrorSource>
        get() = builtInRustSources + getCustomSources(PackageManagerType.RUST)

    // 获取当前为特定包管理器选择的源ID
    fun getSelectedSourceId(pm: PackageManagerType): String {
        val defaultId = defaultSourceId(pm)
        val storedId = prefs.getString(selectedSourceKey(pm), null)
        val resolvedId = resolveSelectedSourceId(
            selectedId = storedId,
            defaultId = defaultId,
            availableSources = sourcesFor(pm),
        )
        if (storedId != null && storedId != resolvedId) {
            // Repair stale preferences left by an older custom-source deletion. Keeping the
            // selected-ID invariant durable prevents environment startup from failing later in
            // `getSelectedSource()` with an unrelated NullPointerException.
            Log.w(TAG, "Resetting unknown selected ${pm.name} source '$storedId' to '$resolvedId'")
            check(prefs.edit().putString(selectedSourceKey(pm), resolvedId).commit()) { "Unable to repair selected mirror source" }
        }
        return resolvedId
    }
    
    // 获取当前源
    fun getSelectedSource(pm: PackageManagerType): MirrorSource {
        val id = getSelectedSourceId(pm)
        return (sourcesFor(pm).firstOrNull { it.id == id }
            ?: error("Selected ${pm.name} source '$id' is not available")).also(::validateMirrorSource)
    }

    // 保存选择的源ID
    fun setSelectedSourceId(pm: PackageManagerType, sourceId: String) {
        val source = requireNotNull(sourcesFor(pm).firstOrNull { it.id == sourceId }) {
            "Unknown mirror source"
        }
        validateMirrorSource(source)
        check(prefs.edit().putString(selectedSourceKey(pm), sourceId).commit()) { "Unable to select mirror source" }
    }
    
    fun getRustSourceEnvCommand(source: MirrorSource): String = rustSourceEnvironmentCommand(source)

    private fun customSourcesKey(pm: PackageManagerType): String = when (pm) {
        PackageManagerType.APT -> "custom_apt_sources"
        PackageManagerType.PIP -> "custom_pip_sources"
        PackageManagerType.NPM -> "custom_npm_sources"
        PackageManagerType.RUST -> "custom_rust_sources"
    }

    private fun selectedSourceKey(pm: PackageManagerType): String = when (pm) {
        PackageManagerType.APT -> "selected_apt_source"
        PackageManagerType.PIP -> "selected_pip_source"
        PackageManagerType.NPM -> "selected_npm_source"
        PackageManagerType.RUST -> "selected_rust_source"
    }

    private fun defaultSourceId(pm: PackageManagerType): String = when (pm) {
        PackageManagerType.APT -> "tuna_apt"
        PackageManagerType.PIP -> "tuna_pip"
        PackageManagerType.NPM -> "taobao_npm"
        PackageManagerType.RUST -> "ustc_rust"
    }

    private fun sourcesFor(pm: PackageManagerType): List<MirrorSource> = when (pm) {
        PackageManagerType.APT -> aptSources
        PackageManagerType.PIP -> pipSources
        PackageManagerType.NPM -> npmSources
        PackageManagerType.RUST -> rustSources
    }

    private companion object {
        const val TAG = "SourceManager"
    }
}

internal fun aptSourceDistributionLines(sourceUrl: String, codename: String = "resolute"): String =
    """
    deb $sourceUrl ${codename} main restricted universe multiverse
    deb $sourceUrl ${codename}-updates main restricted universe multiverse
    deb $sourceUrl ${codename}-backports main restricted universe multiverse
    deb $sourceUrl ${codename}-security main restricted universe multiverse
    """.trimIndent()

/**
 * Resolves a persisted source ID against the current source catalog and restores the required
 * selected-source invariant when a custom source was removed or its preference was corrupted.
 */
internal fun resolveSelectedSourceId(
    selectedId: String?,
    defaultId: String,
    availableSources: List<MirrorSource>,
): String {
    require(availableSources.any { it.id == defaultId }) {
        "Default source '$defaultId' is not present in the source catalog"
    }
    return selectedId?.takeIf { id -> availableSources.any { it.id == id } } ?: defaultId
}
