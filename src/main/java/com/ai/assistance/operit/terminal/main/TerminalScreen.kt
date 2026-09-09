package com.ai.assistance.operit.terminal.main

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
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
    // Resolve the initial route before composing the page. The route is owned synchronously by
    // this composable so a first tap cannot race an asynchronous navigation back stack update.
    val startDestination = remember(env.forceShowSetup) {
        // The start route belongs to this mounted TerminalScreen instance. Do not key it by
        // LocalContext: IME/configuration changes can replace the wrapper context and otherwise
        // re-read terminal_prefs while a user navigation is already in flight.
        val preferences = context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
        resolveTerminalStartDestination(
            forceShowSetup = env.forceShowSetup,
            isFirstLaunch = preferences.getBoolean("is_first_launch", true),
        )
    }
    // This is the only visible-page state owner. A NavHost would keep the old SurfaceView entry
    // alive while its asynchronous back stack catches up, allowing it to cover or steal input
    // from setup/settings. Composing exactly one route removes that window entirely.
    var activeRoute by remember(startDestination) { mutableStateOf(startDestination) }

    fun requestRoute(route: String) {
        if (shouldRequestTerminalRoute(activeRoute, route)) activeRoute = route
    }

    fun returnToTerminalHome() {
        activeRoute = TerminalRoutes.TERMINAL_HOME_ROUTE
    }

    fun completeSetupNavigation() {
        returnToTerminalHome()
        context.getSharedPreferences("terminal_prefs", Context.MODE_PRIVATE)
            .edit { putBoolean("is_first_launch", false) }
    }

    BackHandler(enabled = systemBackEnabled) {
        when (resolveTerminalBackAction(activeRoute)) {
            TerminalBackAction.RETURN_TO_TERMINAL_HOME ->
                if (activeRoute == TerminalRoutes.SETUP_ROUTE) {
                    completeSetupNavigation()
                } else {
                    returnToTerminalHome()
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

    when (activeRoute) {
        TerminalRoutes.TERMINAL_HOME_ROUTE -> {
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
        TerminalRoutes.SETUP_ROUTE -> {
            SetupScreen(
                onBack = ::completeSetupNavigation,
                setupInProgress = env.setupProgress?.let { !it.completed && !it.failed } == true,
                onSetup = { commands ->
                    env.onSetup(commands)
                    completeSetupNavigation()
                }
            )
        }
        TerminalRoutes.SETTINGS_ROUTE -> {
            SettingsScreen(
                onNavigateToSetup = { requestRoute(TerminalRoutes.SETUP_ROUTE) },
                onBack = {
                    returnToTerminalHome()
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
