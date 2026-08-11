package com.ai.assistance.operit.terminal.view.domain.ansi

import android.graphics.Color
import androidx.core.graphics.toColorInt

/**
 * ANSI 颜色处理工具
 */
object AnsiColorUtils {
    
    /**
     * 标准 ANSI 颜色 (30-37, 40-47)
     */
    fun getAnsiColor(index: Int): Int = when(index) {
        0 -> "#000000".toColorInt() // Black
        1 -> "#CD0000".toColorInt() // Red
        2 -> "#00CD00".toColorInt() // Green
        3 -> "#CDCD00".toColorInt() // Yellow
        4 -> "#0000EE".toColorInt() // Blue
        5 -> "#CD00CD".toColorInt() // Magenta
        6 -> "#00CDCD".toColorInt() // Cyan
        7 -> "#E5E5E5".toColorInt() // White
        else -> Color.WHITE
    }
    
    /**
     * 明亮 ANSI 颜色 (90-97, 100-107)
     */
    fun getAnsiBrightColor(index: Int): Int = when(index) {
        0 -> "#7F7F7F".toColorInt() // Bright Black (Gray)
        1 -> "#FF0000".toColorInt() // Bright Red
        2 -> "#00FF00".toColorInt() // Bright Green
        3 -> "#FFFF00".toColorInt() // Bright Yellow
        4 -> "#5C5CFF".toColorInt() // Bright Blue
        5 -> "#FF00FF".toColorInt() // Bright Magenta
        6 -> "#00FFFF".toColorInt() // Bright Cyan
        7 -> "#FFFFFF".toColorInt() // Bright White
        else -> Color.WHITE
    }
    
    /**
     * Xterm 256 色
     * 
     * 色彩分布:
     * 0-15:   基础 16 色
     * 16-231: 6x6x6 RGB 色立方
     * 232-255: 24 级灰度
     */
    fun getXterm256Color(colorIndex: Int): Int {
        if (colorIndex < 0 || colorIndex > 255) return Color.WHITE
        
        // 16 基础色
        if (colorIndex < 16) {
            val baseColors = intArrayOf(
                0x000000, 0x800000, 0x008000, 0x808000, 
                0x000080, 0x800080, 0x008080, 0xc0c0c0,
                0x808080, 0xff0000, 0x00ff00, 0xffff00, 
                0x0000ff, 0xff00ff, 0x00ffff, 0xffffff
            )
            return baseColors[colorIndex] or 0xFF000000.toInt()
        }
        
        // 216 色立方 (6x6x6)
        if (colorIndex < 232) {
            val i = colorIndex - 16
            val r = (i / 36) * 51
            val g = ((i % 36) / 6) * 51
            val b = (i % 6) * 51
            return Color.rgb(r, g, b)
        }
        
        // 24 级灰度
        val gray = (colorIndex - 232) * 10 + 8
        return Color.rgb(gray, gray, gray)
    }
    
    /**
     * 从 SGR 参数解析颜色
     * @param params SGR 参数列表
     * @param startIndex 开始解析的索引
     * @return Pair<颜色值, 消耗的参数数量>
     */
    fun parseColorFromSgr(params: List<Int>, startIndex: Int): Pair<Int, Int>? {
        if (startIndex >= params.size) return null
        
        val firstParam = params[startIndex]
        
        // 256 色模式: 38;5;n 或 48;5;n
        if (firstParam == 5 && startIndex + 1 < params.size) {
            val colorIndex = params[startIndex + 1]
            return Pair(getXterm256Color(colorIndex), 2)
        }
        
        // RGB 模式: 38;2;r;g;b 或 48;2;r;g;b
        if (firstParam == 2 && startIndex + 3 < params.size) {
            val r = params[startIndex + 1].coerceIn(0, 255)
            val g = params[startIndex + 2].coerceIn(0, 255)
            val b = params[startIndex + 3].coerceIn(0, 255)
            return Pair(Color.rgb(r, g, b), 4)
        }
        
        return null
    }
}
