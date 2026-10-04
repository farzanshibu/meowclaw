package com.farzanshibu.meowclaw.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.farzanshibu.meowclaw.data.AppSettings
import com.farzanshibu.meowclaw.device.VoiceConfig
import com.farzanshibu.meowclaw.device.VoiceProvider
import com.farzanshibu.meowclaw.graph

/**
 * Voice: which engine speaks replies and which one hears you. Cloud voices
 * (ElevenLabs, OpenAI, Deepgram) sound far more natural; the phone's own
 * engines are the offline fallback whenever a cloud voice fails.
 */
@Composable
fun VoiceSection(settings: AppSettings, showMessage: (String) -> Unit) {
    val g = LocalContext.current.graph
    val c = Brutal.colors
    var elevenKey by remember { mutableStateOf(settings.elevenLabsKey) }
    var openAiKey by remember { mutableStateOf(settings.openAiVoiceKey) }
    var deepgramKey by remember { mutableStateOf(settings.deepgramKey) }
    var elevenVoice by remember { mutableStateOf(settings.elevenLabsVoice) }
    var openAiVoice by remember { mutableStateOf(settings.openAiVoice) }
    var deepgramVoice by remember { mutableStateOf(settings.deepgramVoice) }
    var name by remember { mutableStateOf(settings.voiceUserName) }

    Section("Voice", c.pink, "Who speaks replies and who hears you. Cloud voices fall back to the phone's own when offline.") {
        Text("SPEAKS REPLIES", fontSize = 11.sp, fontFamily = Brutal.Mono, color = c.ink)
        ProviderRow(settings.ttsProvider) { p -> g.settings.update { it.copy(ttsProvider = p) } }
        Text("HEARS YOU", fontSize = 11.sp, fontFamily = Brutal.Mono, color = c.ink)
        ProviderRow(settings.sttProvider) { p -> g.settings.update { it.copy(sttProvider = p) } }

        val needed = setOf(settings.ttsProvider, settings.sttProvider) - VoiceProvider.SYSTEM
        if (VoiceProvider.ELEVENLABS in needed) {
            KeyField("ElevenLabs API key", elevenKey) { elevenKey = it }
            BrutalTextField(elevenVoice, { elevenVoice = it }, "ElevenLabs voice ID", placeholder = VoiceConfig.DEFAULT_ELEVENLABS_VOICE)
        }
        if (VoiceProvider.OPENAI in needed) {
            KeyField("OpenAI API key", openAiKey) { openAiKey = it }
            BrutalTextField(openAiVoice, { openAiVoice = it }, "OpenAI voice", placeholder = "ash, cedar, marin, coral…")
        }
        if (VoiceProvider.DEEPGRAM in needed) {
            KeyField("Deepgram API key", deepgramKey) { deepgramKey = it }
            BrutalTextField(deepgramVoice, { deepgramVoice = it }, "Deepgram voice", placeholder = VoiceConfig.DEFAULT_DEEPGRAM_VOICE)
        }

        SwitchRow("Jarvis persona", "British butler phrasing and voice direction", settings.jarvisVoice) { v ->
            g.settings.update { it.copy(jarvisVoice = v) }
        }
        if (settings.jarvisVoice) BrutalTextField(name, { name = it }, "Jarvis calls you", placeholder = "sir")
        SwitchRow("Announce finished tasks", "Say how each device task went", settings.speakTaskResults) { v ->
            g.settings.update { it.copy(speakTaskResults = v) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BrutalButton({
                g.settings.update {
                    it.copy(
                        elevenLabsKey = elevenKey.trim(), openAiVoiceKey = openAiKey.trim().removePrefix("Bearer ").trim(),
                        deepgramKey = deepgramKey.trim().removePrefix("Token ").trim(),
                        elevenLabsVoice = elevenVoice.trim().ifEmpty { VoiceConfig.DEFAULT_ELEVENLABS_VOICE },
                        openAiVoice = openAiVoice.trim().ifEmpty { VoiceConfig.DEFAULT_OPENAI_VOICE },
                        deepgramVoice = deepgramVoice.trim().ifEmpty { VoiceConfig.DEFAULT_DEEPGRAM_VOICE },
                        voiceUserName = name.trim().ifEmpty { "sir" },
                    )
                }
                showMessage("Voice settings saved")
            }, Modifier.weight(1f), fill = c.pink) { Text("SAVE") }
            BrutalButton({
                val s = g.settings.current
                g.speaker.speak(
                    if (s.jarvisVoice) "Good day, ${s.voiceUserName.ifBlank { "sir" }}. All systems are online and at your service."
                    else "Voice check. Everything sounds fine.",
                )
                val missing = (setOf(s.ttsProvider) - VoiceProvider.SYSTEM).filter { s.voice.keyFor(it).isEmpty() }
                showMessage(
                    if (missing.isNotEmpty()) "No ${missing.first().label} key saved, using the phone's voice"
                    else "Testing ${s.ttsProvider.label} voice…",
                )
            }, Modifier.weight(1f), fill = c.surface) { Text("TEST VOICE") }
        }
        Text(
            "Keys stay on this phone. Hands-free mic and spoken replies use these settings everywhere, including steering a running task.",
            fontSize = 12.sp, color = c.muted,
        )
    }
}

@Composable
private fun ProviderRow(selected: VoiceProvider, onPick: (VoiceProvider) -> Unit) {
    val c = Brutal.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        VoiceProvider.entries.forEach { p ->
            Choice(p.label.uppercase(), selected == p, c.pink, Modifier.weight(1f)) { onPick(p) }
        }
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit) {
    val c = Brutal.colors
    var show by remember { mutableStateOf(false) }
    BrutalTextField(
        value, onChange, label,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailing = {
            Box(Modifier.size(28.dp).clickable { show = !show }, contentAlignment = Alignment.Center) {
                Icon(if (show) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show key", tint = c.ink)
            }
        },
    )
}
