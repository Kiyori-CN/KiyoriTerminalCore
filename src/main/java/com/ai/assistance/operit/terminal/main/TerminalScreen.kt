package com.ai.assistance.operit.terminal.main

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ai.assistance.operit.terminal.TerminalEnv
import com.ai.assistance.operit.terminal.ui.SetupScreen
import com.ai.assistance.operit.terminal.ui.TerminalHome
import com.ai.assistance.operit.terminal.ui.SettingsScreen

@Composable
fun TerminalScreen(
    env: TerminalEnv,
    onClose: () -> Unit,
    useLocalImeHandling: Boolean = true,
    manageHostWindowSoftInputMode: Boolean = true,
    systemBackEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val hostActivity = remember(context) { context.findActivity() }
    val manifestSoftInputMode = remember(hostActivity) { hostActivity?.manifestSoftInputMode() }
    val navController = rememberNavController()
    // Resolve the initial route before composing NavHost. An asynchronous route correction can
    // race with the first tap on the environment button and pop the newly opened setup screen.
    val startDestination = remember(env.forceShowSetup) {
        // The start route belongs to this mounted TerminalScreen instance. Do not key it by
        // LocalContext: IME/configuration changes can replace the wrapper context and otherwise
        // make NavHost re-read terminal_prefs while a user navigation is already in flight.
        val preferences = context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
        resolveTerminalStartDestination(
            forceShowSetup = env.forceShowSetup,
            isFirstLaunch = preferences.getBoolean("is_first_launch", true),
        )
    }
    // NavController publishes its destination asynchronously. Keep the requested route as the
    // single UI owner so a rapid second tap cannot observe the old home route and enqueue another
    // setup navigation while the SurfaceView is being released.
    var requestedRoute by remember(startDestination) { mutableStateOf(startDestination) }

    fun requestRoute(route: String) {
        if (!shouldRequestTerminalRoute(requestedRoute, route)) return
        requestedRoute = route
        navController.navigate(route) {
            launchSingleTop = true
        }
    }

    fun completeSetupNavigation() {
        requestedRoute = TerminalRoutes.TERMINAL_HOME_ROUTE
        context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
            .edit { putBoolean("is_first_launch", false) }
        navController.navigate(TerminalRoutes.TERMINAL_HOME_ROUTE) {
            popUpTo(TerminalRoutes.SETUP_ROUTE) { inclusive = true }
        }
    }

    BackHandler(enabled = systemBackEnabled) {
        when (resolveTerminalBackAction(requestedRoute)) {
            TerminalBackAction.RETURN_TO_TERMINAL_HOME ->
                if (requestedRoute == TerminalRoutes.SETUP_ROUTE) {
                    completeSetupNavigation()
                } else {
                    requestedRoute = TerminalRoutes.TERMINAL_HOME_ROUTE
                    check(navController.popBackStack()) {
                        "Terminal settings route must have a terminal home destination"
                    }
                }
            TerminalBackAction.CLOSE_TERMINAL -> onClose()
        }
    }
    
    DisposableEffect(
        hostActivity,
        manifestSoftInputMode,
        useLocalImeHandling,
        manageHostWindowSoftInputMode,
    ) {
        if (useLocalImeHandling && manageHostWindowSoftInputMode) {
            hostActivity?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
        onDispose {
            if (useLocalImeHandling && manageHostWindowSoftInputMode) {
                val window = hostActivity?.window
                if (window != null && manifestSoftInputMode != null) {
                    window.setSoftInputMode(manifestSoftInputMode)
                }
            }
        }
    }

    // 使用 NavHost 处理所有导航
    NavHost(
        navController = navController,
        startDestination = startDestination,
        // TerminalHome contains a SurfaceView. AnimatedContent-style route transitions keep the
        // old SurfaceView alive while SetupScreen is entering, which can expose the underlying AI
        // page and send a tap to the wrong input owner on Android/OEM window compositors.
        enterTransition = { EnterTransition.None },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = { ExitTransition.None },
    ) {
        
        composable(TerminalRoutes.TERMINAL_HOME_ROUTE) {
            TerminalHome(
                env = env,
                useLocalImeHandling = useLocalImeHandling,
                onNavigateToSetup = {
                    requestRoute(TerminalRoutes.SETUP_ROUTE)
                },
                onNavigateToSettings = {
                    requestRoute(TerminalRoutes.SETTINGS_ROUTE)
                }
            )
        }
        
        composable(TerminalRoutes.SETUP_ROUTE) {
            SetupScreen(
                onBack = ::completeSetupNavigation,
                onSetup = { commands ->
                    env.onSetup(commands)
                    completeSetupNavigation()
                }
            )
        }
        
        composable(TerminalRoutes.SETTINGS_ROUTE) {
            SettingsScreen(
                onBack = {
                    requestedRoute = TerminalRoutes.TERMINAL_HOME_ROUTE
                    navController.popBackStack()
                }
            )
        }
    }
    
}

internal enum class TerminalBackAction {
    RETURN_TO_TERMINAL_HOME,
    CLOSE_TERMINAL,
}

internal fun resolveTerminalBackAction(route: String): TerminalBackAction =
    when (route) {
        TerminalRoutes.SETUP_ROUTE,
        TerminalRoutes.SETTINGS_ROUTE,
        -> TerminalBackAction.RETURN_TO_TERMINAL_HOME
        TerminalRoutes.TERMINAL_HOME_ROUTE -> TerminalBackAction.CLOSE_TERMINAL
        else -> error("Unsupported terminal route for system Back: $route")
    }

internal fun resolveTerminalStartDestination(
    forceShowSetup: Boolean,
    isFirstLaunch: Boolean,
): String = when {
    forceShowSetup || isFirstLaunch -> TerminalRoutes.SETUP_ROUTE
    else -> TerminalRoutes.TERMINAL_HOME_ROUTE
}

internal fun shouldRequestTerminalRoute(currentRoute: String, targetRoute: String): Boolean =
    currentRoute != targetRoute

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

private fun Activity.manifestSoftInputMode(): Int =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        packageManager.getActivityInfo(
            componentName,
            PackageManager.ComponentInfoFlags.of(0)
        ).softInputMode
    } else {
        @Suppress("DEPRECATION")
        packageManager.getActivityInfo(componentName, 0).softInputMode
    }
