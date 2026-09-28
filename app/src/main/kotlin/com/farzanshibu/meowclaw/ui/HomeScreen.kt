package com.farzanshibu.meowclaw.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.farzanshibu.meowclaw.AgentAccessibilityService
import com.farzanshibu.meowclaw.agent.Mode
import com.farzanshibu.meowclaw.graph
import com.farzanshibu.meowclaw.llm.needle.NeedleState
import com.farzanshibu.meowclaw.update.UpdateState
import kotlinx.coroutines.launch
import java.time.LocalTime

@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onOpenHistory: () -> Unit) {
    val context = LocalContext.current
    val g = context.graph
    val c = Brutal.colors
    val ui by g.controller.ui.collectAsStateWithLifecycle()
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    val needleState by g.needle.state.collectAsStateWithLifecycle()
    val modelStates by g.localModels.states.collectAsStateWithLifecycle()
    val sessions by g.chatHistory.sessions.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    val listening by g.handsFree.active.collectAsStateWithLifecycle()
    var accessibilityOn by remember { mutableStateOf(AgentAccessibilityService.isRunning()) }
    // Recomposed when settings or model downloads change.
    @Suppress("UNUSED_EXPRESSION") settings; @Suppress("UNUSED_EXPRESSION") modelStates
    val llmReady = g.language.isConfigured
    val needleReady = needleState == NeedleState.Ready && settings.needleEnabled

    val update by g.updater.status.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { g.chatHistory.load() }
    LaunchedEffect(Unit) { g.updater.check() }
    LifecycleResumeEffect(Unit) {
        accessibilityOn = AgentAccessibilityService.isRunning()
        onPauseOrDispose { }
    }
    LaunchedEffect(ui.messages.size, ui.messages.lastOrNull()?.content?.length, ui.busy) {
        if (ui.messages.isNotEmpty()) listState.animateScrollToItem(ui.messages.lastIndex)
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) g.handsFree.start()
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet(drawerContainerColor = c.paper, drawerShape = RoundedCornerShape(0.dp)) {
                Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
                    Wordmark()
                    Spacer(Modifier.height(20.dp))
                    BrutalButton(
                        onClick = { scope.launch { drawer.close() }; g.controller.newChat() },
                        modifier = Modifier.fillMaxWidth(), fill = c.yellow,
                    ) {
                        Icon(Icons.Rounded.Add, null)
                        Spacer(Modifier.width(6.dp))
                        Text("NEW CHAT")
                    }
                    Spacer(Modifier.height(20.dp))
                    Text("HISTORY", fontFamily = Brutal.Mono, fontWeight = FontWeight.Black, fontSize = 12.sp, color = c.ink)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (sessions.isEmpty()) item {
                            Text("No chats yet.", color = c.muted, fontSize = 13.sp)
                        }
                        items(sessions, key = { it.id }) { session ->
                            val current = session.id == ui.sessionId
                            Row(
                                Modifier.fillMaxWidth()
                                    .brutal(if (current) c.blue else c.surface, c.ink, c.shadow, 10.dp, shadowOffset = if (current) 3.dp else 0.dp, border = 2.dp)
                                    .clickable {
                                        scope.launch { drawer.close() }
                                        g.controller.load(session)
                                    }
                                    .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    session.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Bold, fontSize = 14.sp,
                                    color = if (current) c.onAccent else c.ink,
                                )
                                Box(
                                    Modifier.size(40.dp).clickable { scope.launch { g.controller.delete(session.id) } },
                                    contentAlignment = Alignment.Center,
                                ) { Icon(Icons.Rounded.Close, "Delete", Modifier.size(18.dp), tint = if (current) c.onAccent else c.ink) }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    DrawerLink(Icons.Rounded.History, "Task history", c.green) { scope.launch { drawer.close() }; onOpenHistory() }
                    Spacer(Modifier.height(10.dp))
                    DrawerLink(Icons.Rounded.Settings, "Settings", c.purple) { scope.launch { drawer.close() }; onOpenSettings() }
                }
            }
        },
    ) {
        Box(Modifier.fillMaxSize().background(c.paper)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
                // Top bar
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BrutalIconButton(Icons.Rounded.Menu, "Menu", { scope.launch { drawer.open() } })
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f)) { Wordmark() }
                    // Master power for the whole agent: floating controls, voice, Telegram.
                    BrutalSwitch(settings.agentEnabled, { on -> g.settings.update { it.copy(agentEnabled = on) } })
                    Spacer(Modifier.width(10.dp))
                    BrutalIconButton(Icons.Rounded.Add, "New chat", g.controller::newChat, enabled = !ui.busy, fill = c.yellow)
                    Spacer(Modifier.width(8.dp))
                    BrutalIconButton(Icons.Rounded.Settings, "Settings", onOpenSettings)
                }

                ModeSelector(ui.mode, g.controller::setMode)

                when {
                    !settings.agentEnabled -> Banner("MeowClaw is off. No floating controls, voice or remote.", "TURN ON", c.yellow) {
                        g.settings.update { it.copy(agentEnabled = true) }
                    }
                    !llmReady && !needleReady -> Banner("No AI yet — grab an on-device model or add an API.", "SET UP", c.orange, onOpenSettings)
                    ui.mode == Mode.AGENT && !accessibilityOn -> Banner("Screen Control is off. Multi-step tasks need it.", "ENABLE", c.pink, onOpenSettings)
                }
                (update as? UpdateState.Available)?.let { available ->
                    Banner("MeowClaw v${available.manifest.versionName} is out.", "UPDATE", c.green) {
                        scope.launch { g.updater.install(available.manifest) }
                    }
                }
                if (ui.mode == Mode.AGENT) EngineStrip(needleReady, llmReady, g.language.isOnDevice, g.language.displayName)

                Box(Modifier.weight(1f)) {
                    if (ui.messages.isEmpty()) {
                        EmptyState(ui.mode) { g.controller.send(it) }
                    } else {
                        LazyColumn(
                            state = listState,
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            itemsIndexed(ui.messages) { _, message -> MessageBubble(message) }
                        }
                    }
                }

                if (ui.busy) WorkingBar(onStop = g.controller::cancel)

                InputBar(
                    value = input,
                    onValueChange = { input = it },
                    listening = listening,
                    busy = ui.busy,
                    onSend = {
                        g.controller.send(input)
                        input = ""
                    },
                    onMic = {
                        when {
                            listening -> g.handsFree.stop()
                            !settings.agentEnabled -> g.toaster.show("Turn MeowClaw on first")
                            g.handsFree.hasPermission() -> g.handsFree.start()
                            else -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun Wordmark() {
    val c = Brutal.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.brutal(c.yellow, c.ink, c.shadow, 6.dp, shadowOffset = 2.dp, border = 2.dp)
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) { Text("MEOW", fontWeight = FontWeight.Black, fontSize = 17.sp, color = c.onAccent, letterSpacing = 0.5.sp) }
        Spacer(Modifier.width(6.dp))
        Text("CLAW", fontWeight = FontWeight.Black, fontSize = 17.sp, color = c.ink, letterSpacing = 0.5.sp)
    }
}

@Composable
private fun DrawerLink(icon: ImageVector, label: String, accent: Color, onClick: () -> Unit) {
    BrutalButton(onClick, Modifier.fillMaxWidth(), fill = accent) {
        Icon(icon, null)
        Spacer(Modifier.width(8.dp))
        Text(label.uppercase(), Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Rounded.ArrowForward, null)
    }
}

@Composable
private fun ModeSelector(mode: Mode, onSelect: (Mode) -> Unit) {
    val c = Brutal.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            .brutal(c.surface, c.ink, c.shadow, 12.dp)
            .padding(4.dp),
    ) {
        listOf(Mode.CHAT to (Icons.Rounded.ChatBubble to "CHAT"), Mode.AGENT to (Icons.Rounded.SmartToy to "AGENT")).forEach { (m, spec) ->
            val selected = m == mode
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(if (selected) (if (m == Mode.AGENT) c.green else c.blue) else Color.Transparent)
                    .clickable { onSelect(m) }
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (selected) c.onAccent else c.ink
                Icon(spec.first, null, Modifier.size(16.dp), tint = color)
                Spacer(Modifier.width(6.dp))
                Text(spec.second, fontWeight = FontWeight.Black, color = color, fontSize = 13.sp, fontFamily = Brutal.Mono)
            }
        }
    }
}

@Composable
private fun EngineStrip(needle: Boolean, llm: Boolean, onDevice: Boolean, llmName: String) {
    val c = Brutal.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (needle) Tag("Needle · instant", c.green, icon = Icons.Rounded.Bolt)
        when {
            llm && onDevice -> Tag("$llmName · on-device", c.yellow, icon = Icons.Rounded.Memory)
            llm -> Tag("$llmName · API", c.blue)
            else -> Tag("No LLM", c.orange)
        }
    }
}

@Composable
private fun Banner(text: String, action: String, accent: Color, onAction: () -> Unit) {
    val c = Brutal.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)
            .padding(end = Brutal.Shadow, bottom = Brutal.Shadow)
            .brutal(accent, c.ink, c.shadow)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.WarningAmber, null, tint = c.onAccent)
        Spacer(Modifier.width(8.dp))
        Text(text, Modifier.weight(1f).padding(vertical = 10.dp), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.onAccent)
        Text(
            action, Modifier.clickable(onClick = onAction).padding(12.dp),
            fontWeight = FontWeight.Black, fontFamily = Brutal.Mono, color = c.onAccent, fontSize = 12.sp,
        )
    }
}

@Composable
private fun WorkingBar(onStop: () -> Unit) {
    val c = Brutal.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.LinearProgressIndicator(
            Modifier.weight(1f).height(10.dp).brutal(c.surface, c.ink, c.shadow, 5.dp, shadowOffset = 0.dp, border = 2.dp),
            color = c.pink, trackColor = c.surface,
        )
        Spacer(Modifier.width(10.dp))
        BrutalButton(onStop, fill = c.red, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
            Icon(Icons.Rounded.Stop, null, Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("STOP", fontSize = 12.sp)
        }
    }
}

@Composable
private fun EmptyState(mode: Mode, onSuggestion: (String) -> Unit) {
    val c = Brutal.colors
    val greeting = when (LocalTime.now().hour) {
        in 5..11 -> "Good morning."
        in 12..16 -> "Good afternoon."
        in 17..21 -> "Good evening."
        else -> "Hello, night owl."
    }
    val suggestions = if (mode == Mode.CHAT) listOf(
        "Write a professional email" to c.blue,
        "Explain quantum computing simply" to c.pink,
        "Brainstorm mobile app ideas" to c.green,
        "Write a poem about robots" to c.purple,
    ) else listOf(
        "Open settings then open display" to c.yellow,
        "Set a timer for 10 minutes" to c.green,
        "Set volume to 40" to c.blue,
        "Open YouTube and search for cats" to c.pink,
        "What's on my screen?" to c.purple,
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 20.dp)) {
        Text(greeting, style = MaterialTheme.typography.headlineMedium, color = c.muted)
        Text("What should I do?", style = MaterialTheme.typography.displaySmall, color = c.ink)
        Spacer(Modifier.height(24.dp))
        Text("TRY", fontFamily = Brutal.Mono, fontWeight = FontWeight.Black, fontSize = 12.sp, color = c.ink)
        Spacer(Modifier.height(10.dp))
        suggestions.forEach { (text, accent) ->
            BrutalButton({ onSuggestion(text) }, Modifier.fillMaxWidth().padding(bottom = 6.dp), fill = accent) {
                Text(text, Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    listening: Boolean,
    busy: Boolean,
    onSend: () -> Unit,
    onMic: () -> Unit,
) {
    val c = Brutal.colors
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        BrutalIconButton(
            Icons.Rounded.Mic, if (listening) "Stop listening" else "Hands-free voice", onMic,
            fill = if (listening) c.red else c.surface, size = 50.dp,
        )
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier.weight(1f).padding(end = Brutal.Shadow, bottom = Brutal.Shadow)
                .brutal(c.surface, c.ink, c.shadow, 12.dp)
                .padding(horizontal = 14.dp, vertical = 15.dp),
        ) {
            if (value.isEmpty()) Text(
                when {
                    busy -> if (listening) "Listening — speak to steer, or say stop" else "Steer the task, or say stop…"
                    listening -> "Listening…"
                    else -> "Type a command..."
                },
                color = c.muted, fontSize = 15.sp,
            )
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                // Hardware/adb Enter sends; Shift+Enter keeps a newline.
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { e ->
                    if (e.key == Key.Enter && e.type == KeyEventType.KeyDown && !e.isShiftPressed) {
                        if (value.isNotBlank()) onSend()
                        true
                    } else false
                },
                textStyle = TextStyle(color = c.ink, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(c.ink),
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSend() }),
            )
        }
        BrutalIconButton(
            Icons.AutoMirrored.Rounded.Send, "Send", onSend,
            enabled = value.isNotBlank(), fill = c.yellow, size = 50.dp,
        )
    }
}
