package com.farzanshibu.meowclaw.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mouse
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.farzanshibu.meowclaw.R
import com.farzanshibu.meowclaw.graph
import com.farzanshibu.meowclaw.llm.needle.NeedleState
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onFinished: () -> Unit) {
    val g = LocalContext.current.graph
    val c = Brutal.colors
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    val needle by g.needle.state.collectAsStateWithLifecycle()
    val models by g.localModels.states.collectAsStateWithLifecycle()
    val pager = rememberPagerState { 3 }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    @Suppress("UNUSED_EXPRESSION") models

    Box(Modifier.fillMaxSize().background(c.paper)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Step indicator
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(c.yellow, c.blue, c.green).forEachIndexed { i, accent ->
                    Box(
                        Modifier.weight(1f).height(12.dp)
                            .brutal(if (i <= pager.currentPage) accent else c.surface, c.ink, c.shadow, 6.dp, shadowOffset = 0.dp, border = 2.dp),
                    )
                }
            }
            HorizontalPager(pager, Modifier.weight(1f), userScrollEnabled = false) { page ->
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    when (page) {
                        0 -> Welcome()
                        1 -> {
                            Title("Give it hands", "Screen Control lets the agent read the screen and use its virtual mouse and keyboard.")
                            ControlSection()
                            PermissionsSection()
                        }
                        else -> {
                            Title("Give it a brain", "Needle handles quick actions instantly. Add an on-device model or an API for chat and multi-step tasks.")
                            OnDeviceSection(settings)
                            BrainSection(settings) { message -> scope.launch { snackbar.showSnackbar(message) } }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (pager.currentPage > 0) {
                    BrutalButton({ scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }, fill = c.surface) { Text("BACK") }
                }
                Spacer(Modifier.weight(1f))
                if (pager.currentPage < 2) {
                    BrutalButton({ scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }, fill = c.yellow) {
                        Text(if (pager.currentPage == 0) "LET'S GO →" else "NEXT →")
                    }
                } else {
                    val ready = g.language.isConfigured || needle == NeedleState.Ready || needle is NeedleState.Downloading
                    BrutalButton({
                        if (!ready) {
                            scope.launch { snackbar.showSnackbar("Download Needle, an on-device model, or save an API first.") }
                        } else {
                            g.settings.update { it.copy(onboardingCompleted = true) }
                            onFinished()
                        }
                    }, fill = c.green) { Text("FINISH ✓") }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
private fun Welcome() {
    val c = Brutal.colors
    Spacer(Modifier.height(12.dp))
    Box(Modifier.size(96.dp).brutal(c.yellow, c.ink, c.shadow, 20.dp), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_logo), "MeowClaw", Modifier.size(80.dp))
    }
    Spacer(Modifier.height(8.dp))
    Wordmark()
    Text("Your phone,\nrun by an agent\nthat stays on it.", style = MaterialTheme.typography.displaySmall, color = c.ink)
    Spacer(Modifier.height(6.dp))
    Feature(Icons.Rounded.Bolt, "Instant on-device actions", "Needle 3 opens apps, calls, texts and sets alarms offline in milliseconds.", c.green)
    Feature(Icons.Rounded.Memory, "Local brains", "Gemma 4, Qwen, FunctionGemma and LFM run on the phone — with vision.", c.yellow)
    Feature(Icons.Rounded.Mouse, "Virtual mouse & keyboard", "Taps, drags, typing and shortcuts, with a visible cursor and glowing edge.", c.blue)
    Feature(Icons.Rounded.Lock, "Private by default", "Telemetry compiled out. Keys stay on the device.", c.pink)
}

@Composable
private fun Feature(icon: ImageVector, title: String, body: String, accent: Color) {
    val c = Brutal.colors
    BrutalCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(38.dp).brutal(accent, c.ink, c.shadow, 8.dp, shadowOffset = 0.dp, border = 2.dp), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = c.onAccent)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontWeight = FontWeight.Black, color = c.ink)
                Text(body, fontSize = 13.sp, color = c.muted)
            }
        }
    }
}

@Composable
private fun Title(title: String, subtitle: String) {
    val c = Brutal.colors
    Text(title, style = MaterialTheme.typography.headlineMedium, color = c.ink)
    Text(subtitle, fontSize = 14.sp, color = c.muted)
}
