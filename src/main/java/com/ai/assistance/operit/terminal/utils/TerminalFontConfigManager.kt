package com.ai.assistance.operit.terminal.utils

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import com.ai.assistance.operit.terminal.view.canvas.RenderConfig
import com.ai.assistance.operit.terminal.R
import java.io.File

/**
 * 终端字体配置管理器
 * 管理终端字体的设置，包括字体大小、字体路径、字体名称等
 */
internal data class TerminalRenderConfiguration(val config: RenderConfig, val fontLoadFailed: Boolean)

class TerminalFontConfigManager private constructor(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences(
        "terminal_font_prefs",
        Context.MODE_PRIVATE
    )
    
    companion object {
        private const val KEY_FONT_SIZE = "font_size"
        private const val KEY_FONT_PATH = "font_path"
        private const val KEY_FONT_NAME = "font_name"
        private const val KEY_TARGET_FPS = "target_fps"
        
        private const val DEFAULT_FONT_SIZE = 42f
        private const val DEFAULT_TARGET_FPS = 60
        private const val MIN_TARGET_FPS = 15
        private const val MAX_TARGET_FPS = 120
        
        @Volatile
        private var INSTANCE: TerminalFontConfigManager? = null
        
        fun getInstance(context: Context): TerminalFontConfigManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TerminalFontConfigManager(context.applicationContext).also { 
                    INSTANCE = it 
                }
            }
        }
    }

    /**
     * 加载完整的渲染配置
     */
    fun loadRenderConfig(): RenderConfig = loadRenderConfiguration().config

    internal fun loadRenderConfiguration(): TerminalRenderConfiguration {
        val font = loadTypeface()
        return TerminalRenderConfiguration(
            RenderConfig(fontSize = getFontSize(), typeface = font.first, targetFps = getTargetFps()),
            font.second,
        )
    }

    /**
     * 根据保存的路径或名称加载字体
     *
     * 优先使用用户指定的字体路径/名称；当用户未显式指定时，
     * 默认回退到内置的 JetBrains Mono Nerd Font，确保是真正等宽的字体，
     * 避免各家 ROM 对 "monospace" 映射不一致导致的对齐问题。
     */
    private fun loadTypeface(): Pair<Typeface, Boolean> {
        val fontPath = getFontPath()
        val fontName = getFontName()
        var missingCustomFile = false

        return try {
            // 1. 用户显式指定了字体文件路径
            fontPath?.let { path ->
                val file = File(path)
                if (file.exists() && file.isFile) {
                    return Typeface.createFromFile(file) to false
                }
                missingCustomFile = true
            }

            // 2. 用户显式指定了系统字体名称
            fontName?.let { name ->
                return (when (name.lowercase()) {
                    // 这里仍然允许用户强制使用系统字体
                    "monospace", "mono" -> Typeface.MONOSPACE
                    "serif" -> Typeface.SERIF
                    "sans-serif", "sans" -> Typeface.SANS_SERIF
                    else -> Typeface.create(name, Typeface.NORMAL)
                }) to missingCustomFile
            }

            // 3. 未指定任何字体时，统一使用内置 JetBrains Mono Nerd Font（真·等宽）
            appContext.resources.getFont(R.font.jetbrains_mono_nerd_font_regular) to missingCustomFile
        } catch (e: Exception) {
            // 保留原有字体恢复行为，同时向 UI 返回失败状态，避免把替代字体说成用户所选字体。
            Typeface.MONOSPACE to true
        }
    }

    /**
     * 获取字体大小
     */
    fun getFontSize(): Float {
        return prefs.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE)
    }
    
    /**
     * 设置字体大小
     */
    fun setFontSize(size: Float) {
        require(size.isFinite() && size in 12f..100f) { "Invalid terminal font size" }
        check(prefs.edit().putFloat(KEY_FONT_SIZE, size).commit()) { "Unable to save font size" }
    }
    
    /**
     * 获取字体文件路径
     */
    fun getFontPath(): String? {
        val path = prefs.getString(KEY_FONT_PATH, null)
        return if (path.isNullOrBlank()) null else path
    }
    
    /**
     * 设置字体文件路径
     */
    fun setFontPath(path: String?) {
        val normalized = path?.takeIf(String::isNotBlank)
        normalized?.let {
            val file = File(it)
            require(file.isAbsolute && file.isFile && file.canRead()) { "Font file is not readable" }
            Typeface.createFromFile(file)
        }
        check(prefs.edit().putString(KEY_FONT_PATH, normalized).commit()) { "Unable to save font path" }
    }
    
    /**
     * 获取系统字体名称
     */
    fun getFontName(): String? {
        val name = prefs.getString(KEY_FONT_NAME, null)
        return if (name.isNullOrBlank()) null else name
    }
    
    /**
     * 设置系统字体名称（如 "monospace", "serif", "sans-serif"）
     */
    fun setFontName(name: String?) {
        val normalized = name?.trim()?.takeIf(String::isNotEmpty)
        require(normalized?.none { it.isISOControl() } != false) { "Invalid font family name" }
        check(prefs.edit().putString(KEY_FONT_NAME, normalized).commit()) { "Unable to save font name" }
    }

    /**
     * 获取目标帧率
     */
    fun getTargetFps(): Int {
        return prefs.getInt(KEY_TARGET_FPS, DEFAULT_TARGET_FPS)
            .coerceIn(MIN_TARGET_FPS, MAX_TARGET_FPS)
    }

    /**
     * 设置目标帧率
     */
    fun setTargetFps(fps: Int) {
        require(fps in MIN_TARGET_FPS..MAX_TARGET_FPS) { "Invalid target frame rate" }
        check(prefs.edit().putInt(KEY_TARGET_FPS, fps).commit()) { "Unable to save target frame rate" }
    }
    
    /**
     * 清除所有字体设置，恢复默认
     */
    fun resetToDefault() {
        check(prefs.edit().remove(KEY_FONT_SIZE).remove(KEY_FONT_PATH).remove(KEY_FONT_NAME)
            .remove(KEY_TARGET_FPS).commit()) { "Unable to reset font settings" }
    }
}
