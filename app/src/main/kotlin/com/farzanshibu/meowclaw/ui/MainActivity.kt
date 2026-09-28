package com.farzanshibu.meowclaw.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.farzanshibu.meowclaw.data.ThemeMode
import com.farzanshibu.meowclaw.graph
import com.farzanshibu.meowclaw.service.AgentService

class MainActivity : ComponentActivity() {
    override fun onResume() {
        super.onResume()
        graph.localModels.refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val g = graph
        // Foreground starts are allowed here; restores Telegram after a restart.
        AgentService.sync(this)
        setContent {
            val settings by g.settings.settings.collectAsStateWithLifecycle()
            val dark = when (settings.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }
            MeowClawTheme(dark) {
                val nav = rememberNavController()
                val start = remember { if (g.settings.current.onboardingCompleted) "home" else "onboarding" }
                NavHost(nav, startDestination = start) {
                    composable("onboarding") {
                        OnboardingScreen(onFinished = {
                            nav.navigate("home") { popUpTo("onboarding") { inclusive = true } }
                        })
                    }
                    composable("home") {
                        HomeScreen(
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenHistory = { nav.navigate("history") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(onBack = { nav.popBackStack() }, onOpenHistory = { nav.navigate("history") })
                    }
                    composable("history") { TaskHistoryScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}
