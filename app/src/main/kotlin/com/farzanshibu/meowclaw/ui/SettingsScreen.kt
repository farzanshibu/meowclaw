package com.farzanshibu.meowclaw.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.farzanshibu.meowclaw.AgentAccessibilityService
import com.farzanshibu.meowclaw.data.AppSettings
import com.farzanshibu.meowclaw.data.LlmProvider
import com.farzanshibu.meowclaw.data.ThemeMode
import com.farzanshibu.meowclaw.graph
import com.farzanshibu.meowclaw.llm.LlmClient
import com.farzanshibu.meowclaw.llm.cactus.LocalModel
import com.farzanshibu.meowclaw.service.AgentNotificationListener
import com.farzanshibu.meowclaw.llm.cactus.LocalModelCatalog
import com.farzanshibu.meowclaw.llm.cactus.ModelState
import com.farzanshibu.meowclaw.llm.needle.NeedleState
import com.farzanshibu.meowclaw.update.UpdateManifest
import com.farzanshibu.meowclaw.update.UpdateState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class Provider(val label: String, val baseUrl: String, val model: String? = null, val hint: String? = null)

val PROVIDERS = listOf(
    Provider("Local server", "http://127.0.0.1:8080/v1", hint = "llama.cpp / LM Studio on this phone or LAN"),
    Provider("Ollama", "http://127.0.0.1:11434/v1", "gemma3:4b"),
    Provider("Ollama Cloud", "https://ollama.com/v1", "gemma3:4b"),
    Provider("OpenRouter", "https://openrouter.ai/api/v1", "openai/gpt-oss-120b:free"),
    Provider("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
    Provider("Groq", "https://api.groq.com/openai/v1"),
    Provider("NVIDIA", LlmClient.NVIDIA_BASE_URL, LlmClient.NVIDIA_DEFAULT_MODEL),
)

@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenHistory: () -> Unit) {
    val context = LocalContext.current
    val g = context.graph
    val c = Brutal.colors
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(c.paper)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ScreenHeader("Settings", c.purple, onBack)
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BrainSection(settings) { message -> scope.launch { snackbar.showSnackbar(message) } }
                OnDeviceSection(settings)
                ControlSection()

                Section("Look", c.pink) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeMode.entries.forEach { mode ->
                            Choice(mode.name, settings.theme == mode, c.pink, Modifier.weight(1f)) {
                                g.settings.update { it.copy(theme = mode) }
                            }
                        }
                    }
                }

                Section("Agent limits", c.orange) {
                    SwitchRow("Unlimited steps", "Run until the model says it is done", settings.disableMaxSteps) { v ->
                        g.settings.update { it.copy(disableMaxSteps = v) }
                    }
                    if (!settings.disableMaxSteps) {
                        Stepper("Max steps per task", settings.maxSteps, 5, 50) { v -> g.settings.update { it.copy(maxSteps = v) } }
                    }
                    Stepper("Max tokens", settings.maxTokens, 256, 8192, step = 256) { v -> g.settings.update { it.copy(maxTokens = v) } }
                    Stepper(
                        "Temperature ×100", (settings.temperature * 100).roundToInt(), 0, 200, step = 10,
                    ) { v -> g.settings.update { it.copy(temperature = v / 100.0) } }
                    SwitchRow("Compact screen dumps", "Fewer tokens per step", settings.useScreenCompression) { v ->
                        g.settings.update { it.copy(useScreenCompression = v) }
                    }
                    SwitchRow("Send system prompt", "Turn off for custom LoRA fine-tunes", settings.useSystemPrompt) { v ->
                        g.settings.update { it.copy(useSystemPrompt = v) }
                    }
                }

                TelegramSection(settings)
                PermissionsSection()
                UpdateSection()

                Section("Logs & about", c.blue) {
                    BrutalButton(onOpenHistory, Modifier.fillMaxWidth(), fill = c.green) { Text("TASK HISTORY") }
                    LinkRow("Source code", "https://github.com/farzanshibu/meowclaw")
                    LinkRow("Needle 3 · Cactus Compute", "https://cactuscompute.com/needle")
                    LinkRow("Cactus engine", "https://github.com/cactus-compute/cactus")
                }
                Spacer(Modifier.height(20.dp))
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}

@Composable
fun ScreenHeader(title: String, accent: Color, onBack: (() -> Unit)?, trailing: @Composable (() -> Unit)? = null) {
    val c = Brutal.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { BrutalIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", it) }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.brutal(accent, c.ink, c.shadow, 8.dp, shadowOffset = 3.dp)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) { Text(title.uppercase(), fontWeight = FontWeight.Black, fontSize = 20.sp, color = c.onAccent) }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Where the "big brain" runs: an API or an on-device model. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrainSection(settings: AppSettings, showMessage: (String) -> Unit) {
    val g = LocalContext.current.graph
    val c = Brutal.colors
    Section("Brain", c.yellow, "Chat and multi-step tasks. Pick an on-device model or any OpenAI-compatible API.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Choice("ON-DEVICE", settings.llmProvider == LlmProvider.ON_DEVICE, c.yellow, Modifier.weight(1f)) {
                g.settings.update { it.copy(llmProvider = LlmProvider.ON_DEVICE) }
            }
            Choice("API", settings.llmProvider == LlmProvider.API, c.blue, Modifier.weight(1f)) {
                g.settings.update { it.copy(llmProvider = LlmProvider.API) }
            }
        }
        Spacer(Modifier.height(6.dp))
        if (settings.llmProvider == LlmProvider.ON_DEVICE) LocalModelPicker(settings) else ApiFields(settings, showMessage)
        SwitchRow(
            "Send screenshots", "Vision models also see the screen each step",
            settings.sendScreenshots,
        ) { v -> g.settings.update { it.copy(sendScreenshots = v) } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalModelPicker(settings: AppSettings) {
    val g = LocalContext.current.graph
    val c = Brutal.colors
    val states by g.localModels.states.collectAsStateWithLifecycle()
    val chat by g.controller.ui.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    // Re-measured whenever a download finishes or a model is removed.
    val usedBytes = remember(states) { g.localModels.usedBytes() }
    if (!g.localModels.isSupported) {
        Text("On-device models need a 64-bit ARM phone.", color = c.red, fontWeight = FontWeight.Bold)
        return
    }
    fun delete(model: LocalModel?) = scope.launch { g.deleteLocalModels(model) }
    LocalModelCatalog.models.forEach { model ->
        val state = states[model.id] ?: ModelState.NotDownloaded
        val selected = settings.localModelId == model.id
        Column(
            Modifier.fillMaxWidth().padding(end = 3.dp, bottom = 3.dp)
                .brutal(if (selected) c.yellow else c.surface, c.ink, c.shadow, 10.dp, shadowOffset = if (selected) 3.dp else 0.dp, border = 2.dp)
                .clickable(enabled = state == ModelState.Ready) { g.settings.update { it.copy(localModelId = model.id) } }
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(model.name, fontWeight = FontWeight.Black, color = if (selected) c.onAccent else c.ink)
                    Text(model.note, fontSize = 12.sp, color = if (selected) c.onAccent else c.muted)
                }
                Spacer(Modifier.width(8.dp))
                when (state) {
                    ModelState.Ready, is ModelState.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selected && state == ModelState.Ready) Tag("active", c.green, icon = Icons.Rounded.Check)
                        // The running task may be using this model.
                        Box(
                            Modifier.size(36.dp).clickable(enabled = !chat.busy) { delete(model) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(Icons.Rounded.Delete, "Delete", tint = if (chat.busy) c.muted else if (selected) c.onAccent else c.ink) }
                    }
                    is ModelState.Downloading, ModelState.Extracting ->
                        Box(Modifier.size(36.dp).clickable { g.localModels.cancel(model) }, contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Close, "Cancel", tint = c.ink)
                        }
                    else -> BrutalIconButton(Icons.Rounded.CloudDownload, "Download", { g.localModels.download(model) }, fill = c.green, size = 36.dp)
                }
            }
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Tag(model.sizeLabel, c.surface)
                if (model.vision) Tag("vision", c.pink)
                if (model.tools) Tag("tools", c.blue)
                if (model.audio) Tag("audio", c.purple)
                Tag("cactus ${model.runtime.name.lowercase()}", c.orange)
            }
            when (state) {
                is ModelState.Downloading -> ProgressLine("Downloading ${(state.progress * 100).roundToInt()}%", state.progress)
                ModelState.Extracting -> ProgressLine("Unpacking…", null)
                is ModelState.Failed -> Text("Failed: ${state.message}", color = c.red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                else -> Unit
            }
        }
    }
    if (usedBytes > 0) {
        BrutalButton({ confirmDeleteAll = true }, Modifier.fillMaxWidth(), fill = c.red, enabled = !chat.busy) {
            Icon(Icons.Rounded.Delete, null)
            Spacer(Modifier.width(6.dp))
            Text("DELETE ALL MODELS · ${"%.1f".format(usedBytes / 1e9)} GB")
        }
    }
    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            containerColor = c.paper,
            title = { Text("Delete all models?", fontWeight = FontWeight.Black) },
            text = { Text("Removes every downloaded on-device model and partial download. You can download them again later.") },
            confirmButton = {
                TextButton(onClick = { confirmDeleteAll = false; delete(null) }) { Text("DELETE", color = c.red, fontWeight = FontWeight.Black) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAll = false }) { Text("CANCEL", color = c.ink) } },
        )
    }
}

@Composable
private fun ProgressLine(label: String, progress: Float?) {
    val c = Brutal.colors
    Spacer(Modifier.height(6.dp))
    Text(label, fontSize = 12.sp, fontFamily = Brutal.Mono, fontWeight = FontWeight.Bold, color = c.ink)
    Spacer(Modifier.height(4.dp))
    val mod = Modifier.fillMaxWidth().height(12.dp).brutal(c.surface, c.ink, c.shadow, 6.dp, shadowOffset = 0.dp, border = 2.dp)
    if (progress != null) androidx.compose.material3.LinearProgressIndicator(progress = { progress }, modifier = mod, color = c.green, trackColor = c.surface)
    else androidx.compose.material3.LinearProgressIndicator(modifier = mod, color = c.green, trackColor = c.surface)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApiFields(settings: AppSettings, showMessage: (String) -> Unit) {
    val g = LocalContext.current.graph
    val c = Brutal.colors
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(settings.apiKey) }
    var baseUrl by remember { mutableStateOf(settings.baseUrl) }
    var model by remember { mutableStateOf(settings.model) }
    var showKey by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>?>(null) }
    var loading by remember { mutableStateOf(false) }

    BrutalTextField(
        apiKey, { apiKey = it }, "API key", placeholder = "sk-… (optional for local servers)",
        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            Box(Modifier.size(28.dp).clickable { showKey = !showKey }, contentAlignment = Alignment.Center) {
                Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show key", tint = c.ink)
            }
        },
    )
    BrutalTextField(baseUrl, { baseUrl = it }, "Base URL", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PROVIDERS.forEach { p ->
            Text(
                p.label.uppercase(),
                Modifier.brutal(c.surface, c.ink, c.shadow, 8.dp, shadowOffset = 2.dp, border = 2.dp)
                    .clickable {
                        baseUrl = p.baseUrl
                        p.model?.let { model = it }
                        p.hint?.let(showMessage)
                    }
                    .padding(horizontal = 9.dp, vertical = 6.dp),
                fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = Brutal.Mono, color = c.ink,
            )
        }
    }
    Row(verticalAlignment = Alignment.Bottom) {
        BrutalTextField(model, { model = it }, "Model", Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        BrutalButton({
            loading = true
            scope.launch {
                val found = g.llm.fetchModels(baseUrl, apiKey)
                loading = false
                if (found.isEmpty()) showMessage("No models found or error fetching models.") else models = found
            }
        }, enabled = !loading && baseUrl.isNotBlank(), fill = c.blue) { Text(if (loading) "…" else "FETCH") }
    }
    SwitchRow("Model accepts images", "Send screenshots as image_url parts", settings.apiVision) { v ->
        g.settings.update { it.copy(apiVision = v) }
    }
    BrutalButton({
        val key = apiKey.trim().removePrefix("Bearer ").removePrefix("bearer ").trim()
        g.settings.update { it.copy(apiKey = key, baseUrl = baseUrl.trim(), model = model.trim()) }
        apiKey = key
        showMessage("API settings saved")
    }, Modifier.fillMaxWidth(), fill = c.yellow) { Text("SAVE") }

    models?.let { list ->
        AlertDialog(
            onDismissRequest = { models = null },
            containerColor = c.paper,
            title = { Text(if (LlmClient.isNvidiaBaseUrl(baseUrl)) "Free NVIDIA models" else "Pick a model", fontWeight = FontWeight.Black) },
            text = {
                LazyColumn(Modifier.height(360.dp)) {
                    items(list) { id ->
                        Text(
                            id, Modifier.fillMaxWidth().clickable { model = id; models = null }.padding(vertical = 10.dp),
                            fontSize = 14.sp, fontWeight = FontWeight.Bold, color = c.ink,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { models = null }) { Text("CANCEL", color = c.ink) } },
        )
    }
}

@Composable
fun OnDeviceSection(settings: AppSettings) {
    val g = LocalContext.current.graph
    val c = Brutal.colors
    val state by g.needle.state.collectAsStateWithLifecycle()
    Section("Needle 3 · instant actions", c.green, "35 MB tool-calling model. Opens apps, calls, texts, alarms and more offline in milliseconds.") {
        SwitchRow(
            "Use Needle",
            if (settings.needleEnabled) "Try instant on-device actions before the brain" else "Off: every request goes to the language model",
            settings.needleEnabled,
        ) { v -> g.settings.update { it.copy(needleEnabled = v) } }
        if (settings.needleEnabled) when (val s = state) {
            NeedleState.Unsupported -> Text(
                "Not supported on this CPU (${Build.SUPPORTED_ABIS.firstOrNull()}).", color = c.red, fontWeight = FontWeight.Bold,
            )
            NeedleState.NotDownloaded -> BrutalButton(g.needle::download, Modifier.fillMaxWidth(), fill = c.green) {
                Icon(Icons.Rounded.CloudDownload, null)
                Spacer(Modifier.width(8.dp))
                Text("DOWNLOAD NEEDLE (35 MB)")
            }
            is NeedleState.Downloading -> ProgressLine("Downloading ${(s.progress * 100).roundToInt()}%", s.progress)
            is NeedleState.Failed -> {
                Text("Download failed: ${s.message}", color = c.red, fontWeight = FontWeight.Bold)
                BrutalButton(g.needle::download, fill = c.orange) { Text("RETRY") }
            }
            NeedleState.Ready -> {
                StatusLine(true, "Needle ready")
                Stepper(
                    "Confidence to skip the brain (%)", (settings.needleMinConfidence * 100).roundToInt(), 10, 95, step = 5,
                ) { v -> g.settings.update { it.copy(needleMinConfidence = v / 100.0) } }
                Text("Without a brain, every Needle result is used.", fontSize = 12.sp, color = c.muted)
                BrutalButton({ g.needle.delete() }, fill = c.surface) {
                    Icon(Icons.Rounded.Delete, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("DELETE", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun ControlSection() {
    val context = LocalContext.current
    val g = context.graph
    val c = Brutal.colors
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val scope = rememberCoroutineScope()
    @Suppress("UNUSED_EXPRESSION") tick
    val accessibility = AgentAccessibilityService.isRunning()
    val shizukuRunning = g.shell.isAvailable
    val shizukuGranted = g.shell.hasPermission
    val canWrite = g.system.canWriteSettings()

    Section("Hands · device control", c.blue, "How the agent sees the screen and drives its virtual mouse and keyboard.") {
        StatusLine(accessibility, if (accessibility) "Screen Control on — cursor, taps, screen reading" else "Screen Control is off")
        if (!accessibility) {
            Text(
                "Enable \"MeowClaw Screen Control\" in Accessibility. If Android says \"Restricted setting\", open App Info → ⋮ → Allow restricted settings first.",
                fontSize = 12.sp, color = c.muted,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                BrutalButton({ context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).newTask()) }, fill = c.yellow) { Text("ACCESSIBILITY") }
                BrutalButton({ openAppInfo(context) }, fill = c.surface) { Text("APP INFO") }
            }
        }
        StatusLine(
            accessibility && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) "Virtual keyboard built into Screen Control"
            else "Key presses need Android 13+ or Shizuku",
        )
        StatusLine(shizukuGranted, when {
            shizukuGranted -> "ADB via Shizuku ready"
            shizukuRunning -> "Shizuku running — needs permission"
            else -> "Shizuku not found (optional ADB fallback)"
        })
        if (shizukuRunning && !shizukuGranted) {
            BrutalButton({ scope.launch { g.shell.requestPermission(); tick++ } }, fill = c.yellow) { Text("GRANT SHIZUKU") }
        }
        StatusLine(canWrite, if (canWrite) "Can change brightness" else "Brightness needs \"Modify system settings\"")
        if (!canWrite) {
            BrutalButton({
                context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")).newTask())
            }, fill = c.surface) { Text("ALLOW") }
        }
    }
}

@Composable
fun TelegramSection(settings: AppSettings) {
    val g = LocalContext.current.graph
    val c = Brutal.colors
    var token by remember { mutableStateOf(settings.telegramToken) }
    Section("Telegram remote", c.purple, "Control the agent from anywhere. The first chat to message the bot becomes its only owner.") {
        BrutalTextField(token, { token = it }, "Bot token", visualTransformation = PasswordVisualTransformation())
        SwitchRow("Enable bot", "Listens in the background", settings.telegramEnabled) { v ->
            g.settings.update { it.copy(telegramEnabled = v, telegramToken = token.trim()) }
        }
        if (token.trim() != settings.telegramToken) {
            BrutalButton({ g.settings.update { it.copy(telegramToken = token.trim()) } }, fill = c.yellow) { Text("SAVE TOKEN") }
        }
        if (settings.telegramOwnerChatId.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Paired chat: ${settings.telegramOwnerChatId}", Modifier.weight(1f), fontSize = 13.sp, fontFamily = Brutal.Mono, color = c.ink)
                BrutalButton({ g.settings.update { it.copy(telegramOwnerChatId = "") } }, fill = c.red) { Text("RESET") }
            }
        }
    }
}

@Composable
fun PermissionsSection() {
    val context = LocalContext.current
    val c = Brutal.colors
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val permissions = buildList {
        add("Microphone" to Manifest.permission.RECORD_AUDIO)
        add("Contacts" to Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= 33) add("Notifications" to Manifest.permission.POST_NOTIFICATIONS)
    }
    Section("Permissions", c.orange, "Voice commands, contact lookup, task notifications and reading your notifications.") {
        @Suppress("UNUSED_EXPRESSION") tick
        permissions.forEach { (label, permission) ->
            val granted = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusLine(granted, label, Modifier.weight(1f))
                if (!granted) BrutalButton({ launcher.launch(permission) }, fill = c.yellow, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Text("GRANT", fontSize = 12.sp)
                }
            }
        }
        // A special access, granted on its own system page rather than a runtime dialog.
        val listening = AgentNotificationListener.hasAccess(context)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLine(listening, "Notification access", Modifier.weight(1f))
            if (!listening) BrutalButton(
                { AgentNotificationListener.requestAccess(context) },
                fill = c.yellow, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("GRANT", fontSize = 12.sp) }
        }
    }
}

@Composable
fun UpdateSection() {
    val g = LocalContext.current.graph
    val c = Brutal.colors
    val scope = rememberCoroutineScope()
    val state by g.updater.status.collectAsStateWithLifecycle()
    Section("Updates", c.yellow, "Installed: v${g.updater.currentVersion}. New versions come from GitHub releases.") {
        when (val s = state) {
            UpdateState.Idle -> Unit
            UpdateState.Checking -> ProgressLine("Checking…", null)
            UpdateState.UpToDate -> StatusLine(true, "You're on the latest version")
            is UpdateState.Available -> UpdateNotes(s.manifest)
            is UpdateState.Downloading -> ProgressLine(
                "Downloading v${s.manifest.versionName}" + (s.progress?.let { " ${(it * 100).roundToInt()}%" } ?: "…"), s.progress,
            )
            is UpdateState.Installing -> ProgressLine("Installing v${s.manifest.versionName}…", null)
            is UpdateState.Failed -> Text(s.message, color = c.red, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
        val manifest = (state as? UpdateState.Available)?.manifest ?: (state as? UpdateState.Failed)?.manifest
        val working = state is UpdateState.Checking || state is UpdateState.Downloading || state is UpdateState.Installing
        if (manifest != null) {
            BrutalButton({ scope.launch { g.updater.install(manifest) } }, Modifier.fillMaxWidth(), fill = c.green, enabled = !working) {
                Icon(Icons.Rounded.CloudDownload, null)
                Spacer(Modifier.width(8.dp))
                Text("UPDATE TO V${manifest.versionName}")
            }
        } else {
            BrutalButton({ scope.launch { g.updater.check(force = true) } }, Modifier.fillMaxWidth(), fill = c.surface, enabled = !working) {
                Text("CHECK FOR UPDATES")
            }
        }
    }
}

@Composable
private fun UpdateNotes(manifest: UpdateManifest) {
    val c = Brutal.colors
    StatusLine(false, "v${manifest.versionName} is available")
    manifest.notes.takeIf { it.isNotBlank() }?.let {
        Text(it.take(600), fontSize = 12.5.sp, color = c.muted)
    }
    manifest.releaseUrl.takeIf { it.isNotBlank() }?.let { LinkRow("Release notes", it) }
}

// ─── Building blocks ──────────────────────────────────────────────

@Composable
fun Section(title: String, accent: Color, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    BrutalCard(Modifier.fillMaxWidth()) {
        SectionTitle(title, accent, subtitle)
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
fun Choice(label: String, selected: Boolean, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Brutal.colors
    Box(
        modifier.padding(end = 3.dp, bottom = 3.dp)
            .brutal(if (selected) accent else c.surface, c.ink, c.shadow, 8.dp, shadowOffset = if (selected) 0.dp else 3.dp, border = 2.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontWeight = FontWeight.Black, fontFamily = Brutal.Mono, fontSize = 12.sp, color = if (selected) c.onAccent else c.ink)
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Brutal.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = c.ink)
            Text(subtitle, fontSize = 12.sp, color = c.muted)
        }
        Spacer(Modifier.width(8.dp))
        BrutalSwitch(checked, onChange)
    }
}

/** − value + control; chunkier and more precise than a slider. */
@Composable
fun Stepper(title: String, value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    val c = Brutal.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = c.ink)
        StepButton("−", value > min) { onChange((value - step).coerceAtLeast(min)) }
        Text(
            value.toString(), Modifier.width(56.dp), fontFamily = Brutal.Mono, fontWeight = FontWeight.Black,
            fontSize = 15.sp, color = c.ink, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepButton("+", value < max) { onChange((value + step).coerceAtMost(max)) }
    }
}

@Composable
private fun StepButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Brutal.colors
    Box(
        Modifier.size(34.dp).brutal(if (enabled) c.yellow else c.surface, c.ink, c.shadow, 8.dp, shadowOffset = 0.dp, border = 2.dp)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontWeight = FontWeight.Black, fontSize = 18.sp, color = c.onAccent) }
}

@Composable
fun StatusLine(ok: Boolean, text: String, modifier: Modifier = Modifier) {
    val c = Brutal.colors
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(5.dp))
                .brutal(if (ok) c.green else c.surface, c.ink, c.shadow, 5.dp, shadowOffset = 0.dp, border = 2.dp),
            contentAlignment = Alignment.Center,
        ) { if (ok) Icon(Icons.Rounded.Check, null, Modifier.size(14.dp), tint = c.onAccent) }
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = c.ink)
    }
}

@Composable
private fun LinkRow(title: String, url: String) {
    val context = LocalContext.current
    val c = Brutal.colors
    Text(
        "↗ $title", Modifier.fillMaxWidth()
            .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).newTask()) } }
            .padding(vertical = 6.dp),
        fontWeight = FontWeight.ExtraBold, color = c.ink,
    )
}

fun Intent.newTask(): Intent = addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

fun openAppInfo(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).newTask(),
    )
}

