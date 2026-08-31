package com.ai.assistance.operit.terminal.view.canvas

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ai.assistance.operit.terminal.view.domain.ansi.AnsiTerminalEmulator

/**
 * Compose集成桥接
 * 将CanvasTerminalView包装为Compose组件
 */
@Composable
fun CanvasTerminalScreen(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    config: RenderConfig = RenderConfig(),
    pty: com.ai.assistance.operit.terminal.Pty? = null,
    imeAnimationOffsetPx: Int = 0,
    committedImeBottomInsetPx: Int = 0,
    onInput: (String) -> Unit = {},
    onScaleChanged: (Float) -> Unit = {},
    sessionId: String? = null,
    onScrollOffsetChanged: ((String, Float) -> Unit)? = null,
    getScrollOffset: ((String) -> Float)? = null,
    tabs: List<TerminalTabRenderItem> = emptyList(),
    currentTabId: String? = null,
    onTabClick: ((String) -> Unit)? = null,
    onTabClose: ((String) -> Unit)? = null,
    onNewTab: (() -> Unit)? = null
) {
    AndroidView(
        factory = { context ->
            CanvasTerminalView(context).apply {
                setConfig(config)
                setEmulator(emulator)
                setPty(pty)
                setImeViewportState(
                    animationOffsetPx = imeAnimationOffsetPx,
                    committedBottomInsetPx = committedImeBottomInsetPx
                )
                setInputCallback(onInput)
                setScaleCallback(onScaleChanged)
                setSessionScrollCallbacks(sessionId, onScrollOffsetChanged, getScrollOffset)
                setTabBarState(tabs, currentTabId, onTabClick, onTabClose, onNewTab)
                
                // 全屏模式下自动请求焦点
                post {
                    requestFocus()
                }
                
            }
        },
        update = { view ->
            view.setConfig(config)
            view.setEmulator(emulator)
            view.setPty(pty)
            view.setImeViewportState(
                animationOffsetPx = imeAnimationOffsetPx,
                committedBottomInsetPx = committedImeBottomInsetPx
            )
            view.setInputCallback(onInput)
            view.setSessionScrollCallbacks(sessionId, onScrollOffsetChanged, getScrollOffset)
            view.setTabBarState(tabs, currentTabId, onTabClick, onTabClose, onNewTab)
        },
        onRelease = { view ->
            // Hide the native surface before stopping its renderer. SurfaceView destruction is
            // asynchronous on some Android compositors; clearing visibility first prevents its
            // last frame from covering the destination route during setup navigation.
            view.visibility = android.view.View.GONE
            view.release()
        },
        modifier = modifier
    )
}

/**
 * 带配置的Canvas终端视图
 */
@Composable
fun ConfigurableCanvasTerminal(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    fontSize: Float = 14f,
    backgroundColor: Int = 0xFF000000.toInt(),
    foregroundColor: Int = 0xFFFFFFFF.toInt(),
    cursorColor: Int = 0xFF00FF00.toInt(),
    onInput: (String) -> Unit = {}
) {
    val config = remember(fontSize, backgroundColor, foregroundColor, cursorColor) {
        RenderConfig(
            fontSize = fontSize,
            backgroundColor = backgroundColor,
            foregroundColor = foregroundColor,
            cursorColor = cursorColor
        )
    }
    
    var currentScale by remember { mutableFloatStateOf(1f) }
    
    CanvasTerminalScreen(
        emulator = emulator,
        modifier = modifier,
        config = config,
        onInput = onInput,
        onScaleChanged = { scale -> currentScale = scale }
    )
}

/**
 * 性能监控版本的Canvas终端
 */
@Composable
fun PerformanceMonitoredTerminal(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    config: RenderConfig = RenderConfig(),
    onInput: (String) -> Unit = {},
    onFpsUpdate: (Float) -> Unit = {}
) {
    AndroidView(
        factory = { context ->
            CanvasTerminalView(context).apply {
                setConfig(config)
                setEmulator(emulator)
                setInputCallback(onInput)
                setPerformanceCallback { fps: Float, frameTime: Long ->
                    onFpsUpdate(fps)
                }
                
            }
        },
        update = { view ->
            view.setConfig(config)
            view.setEmulator(emulator)
        },
        onRelease = { view ->
            view.visibility = android.view.View.GONE
            view.release()
        },
        modifier = modifier
    )
}

/**
 * 非全屏Canvas终端输出
 * 仅用于显示终端输出，不处理输入
 */
@Composable
fun CanvasTerminalOutput(
    emulator: AnsiTerminalEmulator,
    modifier: Modifier = Modifier,
    config: RenderConfig = RenderConfig(),
    pty: com.ai.assistance.operit.terminal.Pty? = null,
    imeAnimationOffsetPx: Int = 0,
    committedImeBottomInsetPx: Int = 0,
    onRequestShowKeyboard: (() -> Unit)? = null,
    sessionId: String? = null,
    onScrollOffsetChanged: ((String, Float) -> Unit)? = null,
    getScrollOffset: ((String) -> Float)? = null,
    tabs: List<TerminalTabRenderItem> = emptyList(),
    currentTabId: String? = null,
    onTabClick: ((String) -> Unit)? = null,
    onTabClose: ((String) -> Unit)? = null,
    onNewTab: (() -> Unit)? = null
) {
    AndroidView(
        factory = { context ->
            CanvasTerminalView(context).apply {
                setConfig(config)
                setEmulator(emulator)
                setPty(pty)
                setImeViewportState(
                    animationOffsetPx = imeAnimationOffsetPx,
                    committedBottomInsetPx = committedImeBottomInsetPx
                )
                setFullscreenMode(false) // 关键：设置为非全屏模式
                setOnRequestShowKeyboard(onRequestShowKeyboard)
                setSessionScrollCallbacks(sessionId, onScrollOffsetChanged, getScrollOffset)
                setTabBarState(tabs, currentTabId, onTabClick, onTabClose, onNewTab)
                
            }
        },
        update = { view ->
            view.setConfig(config)
            view.setEmulator(emulator)
            view.setPty(pty)
            view.setImeViewportState(
                animationOffsetPx = imeAnimationOffsetPx,
                committedBottomInsetPx = committedImeBottomInsetPx
            )
            view.setOnRequestShowKeyboard(onRequestShowKeyboard)
            view.setSessionScrollCallbacks(sessionId, onScrollOffsetChanged, getScrollOffset)
            view.setTabBarState(tabs, currentTabId, onTabClick, onTabClose, onNewTab)
        },
        onRelease = { view ->
            view.visibility = android.view.View.GONE
            view.release()
        },
        modifier = modifier
    )
}
