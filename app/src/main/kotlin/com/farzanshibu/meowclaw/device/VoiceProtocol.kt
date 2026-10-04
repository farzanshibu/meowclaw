package com.farzanshibu.meowclaw.device

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Cloud voice providers. SYSTEM means Android's built-in TTS / SpeechRecognizer. */
enum class VoiceProvider(val label: String) {
    SYSTEM("System"),
    ELEVENLABS("ElevenLabs"),
    OPENAI("OpenAI"),
    DEEPGRAM("Deepgram"),
}

/**
 * Everything the voice layer needs from settings, kept as a plain value so the
 * request builders and parsers below stay unit-testable without Android.
 */
data class VoiceConfig(
    val ttsProvider: VoiceProvider = VoiceProvider.SYSTEM,
    val sttProvider: VoiceProvider = VoiceProvider.SYSTEM,
    val elevenLabsKey: String = "",
    val openAiKey: String = "",
    val deepgramKey: String = "",
    val elevenLabsVoice: String = DEFAULT_ELEVENLABS_VOICE,
    val openAiVoice: String = DEFAULT_OPENAI_VOICE,
    val deepgramVoice: String = DEFAULT_DEEPGRAM_VOICE,
    val jarvis: Boolean = true,
    val userName: String = "sir",
    // Overridable for tests / proxies.
    val elevenLabsBase: String = "https://api.elevenlabs.io",
    val openAiBase: String = "https://api.openai.com/v1",
    val deepgramBase: String = "https://api.deepgram.com",
) {
    fun keyFor(p: VoiceProvider): String = when (p) {
        VoiceProvider.SYSTEM -> "system"
        VoiceProvider.ELEVENLABS -> elevenLabsKey
        VoiceProvider.OPENAI -> openAiKey
        VoiceProvider.DEEPGRAM -> deepgramKey
    }.trim()

    /** Chosen provider first, then any other cloud provider with a key, then the system engine. */
    fun chain(chosen: VoiceProvider, supported: List<VoiceProvider>): List<VoiceProvider> =
        (listOf(chosen) + supported + VoiceProvider.SYSTEM).distinct()
            .filter { it == VoiceProvider.SYSTEM || (it in supported && keyFor(it).isNotEmpty()) }

    val ttsChain: List<VoiceProvider> get() = chain(ttsProvider, TTS_PROVIDERS)
    val sttChain: List<VoiceProvider> get() = chain(sttProvider, STT_PROVIDERS)

    companion object {
        /** "George": warm, British, butler-ish. */
        const val DEFAULT_ELEVENLABS_VOICE = "JBFqnCBsd6RMkjVDRZzb"
        const val DEFAULT_OPENAI_VOICE = "ash"
        /** British male Aura-2 voice. */
        const val DEFAULT_DEEPGRAM_VOICE = "aura-2-draco-en"
        val TTS_PROVIDERS = listOf(VoiceProvider.ELEVENLABS, VoiceProvider.OPENAI, VoiceProvider.DEEPGRAM)
        val STT_PROVIDERS = listOf(VoiceProvider.OPENAI, VoiceProvider.DEEPGRAM, VoiceProvider.ELEVENLABS)
    }
}

/** An HTTP request description, independent of the HTTP client. */
data class HttpSpec(val url: String, val headers: Map<String, String>, val jsonBody: String)

/** A websocket connect description. */
data class WsSpec(val url: String, val headers: Map<String, String>)

/** All audio is 16-bit little-endian mono PCM at this rate, in and out. */
const val VOICE_SAMPLE_RATE = 24_000

object VoiceProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    const val JARVIS_STYLE =
        "You are JARVIS, a refined British AI butler. Speak calmly and crisply with understated confidence, light dry wit, moderate pace."

    private fun esc(s: String) = Json.encodeToString(kotlinx.serialization.json.JsonPrimitive.serializer(), kotlinx.serialization.json.JsonPrimitive(s))

    // ---------------------------------------------------------------- TTS

    fun ttsRequest(p: VoiceProvider, c: VoiceConfig, text: String): HttpSpec = when (p) {
        VoiceProvider.ELEVENLABS -> HttpSpec(
            "${c.elevenLabsBase.trimEnd('/')}/v1/text-to-speech/${c.elevenLabsVoice.trim()}/stream?output_format=pcm_$VOICE_SAMPLE_RATE",
            mapOf("xi-api-key" to c.elevenLabsKey.trim()),
            """{"text":${esc(text)},"model_id":"eleven_flash_v2_5","voice_settings":{"stability":0.5,"similarity_boost":0.75}}""",
        )
        VoiceProvider.OPENAI -> HttpSpec(
            "${c.openAiBase.trimEnd('/')}/audio/speech",
            mapOf("Authorization" to "Bearer ${c.openAiKey.trim()}"),
            buildString {
                append("""{"model":"gpt-4o-mini-tts","voice":${esc(c.openAiVoice.trim())},"input":${esc(text)},"response_format":"pcm"""")
                if (c.jarvis) append(""","instructions":${esc(JARVIS_STYLE)}""")
                append("}")
            },
        )
        VoiceProvider.DEEPGRAM -> HttpSpec(
            "${c.deepgramBase.trimEnd('/')}/v1/speak?model=${c.deepgramVoice.trim()}&encoding=linear16&sample_rate=$VOICE_SAMPLE_RATE&container=none",
            mapOf("Authorization" to "Token ${c.deepgramKey.trim()}"),
            """{"text":${esc(text)}}""",
        )
        VoiceProvider.SYSTEM -> error("system TTS has no HTTP request")
    }

    // ---------------------------------------------------------------- STT (streaming)

    private fun wsBase(http: String) = http.trimEnd('/').replaceFirst(Regex("^http"), "ws")

    fun sttSocket(p: VoiceProvider, c: VoiceConfig): WsSpec = when (p) {
        VoiceProvider.OPENAI -> WsSpec(
            "${wsBase(c.openAiBase)}/realtime?intent=transcription",
            mapOf("Authorization" to "Bearer ${c.openAiKey.trim()}"),
        )
        VoiceProvider.DEEPGRAM -> WsSpec(
            "${wsBase(c.deepgramBase)}/v1/listen?model=nova-3&encoding=linear16&sample_rate=$VOICE_SAMPLE_RATE" +
                "&channels=1&interim_results=true&smart_format=true&punctuate=true",
            mapOf("Authorization" to "Token ${c.deepgramKey.trim()}"),
        )
        VoiceProvider.ELEVENLABS -> WsSpec(
            "${wsBase(c.elevenLabsBase)}/v1/speech-to-text/realtime?model_id=scribe_v2_realtime" +
                "&audio_format=pcm_$VOICE_SAMPLE_RATE&commit_strategy=manual",
            mapOf("xi-api-key" to c.elevenLabsKey.trim()),
        )
        VoiceProvider.SYSTEM -> error("system STT has no socket")
    }

    /** First text frame after connecting, if the provider needs one. */
    fun sttStart(p: VoiceProvider): String? = when (p) {
        VoiceProvider.OPENAI ->
            """{"type":"session.update","session":{"type":"transcription","audio":{"input":{"format":{"type":"audio/pcm","rate":$VOICE_SAMPLE_RATE},"transcription":{"model":"gpt-live-transcribe"},"turn_detection":null}}}}"""
        else -> null
    }

    /** One audio frame. Deepgram takes raw binary ([AudioFrame.Binary]); the others take JSON text. */
    fun sttAudio(p: VoiceProvider, pcm: ByteArray): AudioFrame {
        val b64 = java.util.Base64.getEncoder().encodeToString(pcm)
        return when (p) {
            VoiceProvider.OPENAI -> AudioFrame.Text("""{"type":"input_audio_buffer.append","audio":"$b64"}""")
            VoiceProvider.ELEVENLABS -> AudioFrame.Text(
                """{"message_type":"input_audio_chunk","audio_base_64":"$b64","sample_rate":$VOICE_SAMPLE_RATE,"commit":false}""",
            )
            VoiceProvider.DEEPGRAM -> AudioFrame.Binary(pcm)
            VoiceProvider.SYSTEM -> error("system STT has no frames")
        }
    }

    /** Frames that end the utterance and ask for the final transcript. */
    fun sttCommit(p: VoiceProvider): List<String> = when (p) {
        VoiceProvider.OPENAI -> listOf("""{"type":"input_audio_buffer.commit"}""")
        VoiceProvider.DEEPGRAM -> listOf("""{"type":"Finalize"}""", """{"type":"CloseStream"}""")
        VoiceProvider.ELEVENLABS -> listOf(
            """{"message_type":"input_audio_chunk","audio_base_64":"","sample_rate":$VOICE_SAMPLE_RATE,"commit":true}""",
        )
        VoiceProvider.SYSTEM -> emptyList()
    }

    /**
     * Folds one server message into the running [state]. Returns the new state;
     * [SttState.done] is set once the final transcript is known.
     */
    fun sttEvent(p: VoiceProvider, state: SttState, message: String): SttState {
        val ev = runCatching { json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return state
        fun str(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull
        return when (p) {
            VoiceProvider.OPENAI -> when (val t = str(ev, "type").orEmpty()) {
                "conversation.item.input_audio_transcription.delta" -> state.copy(partial = state.partial + str(ev, "delta").orEmpty())
                "conversation.item.input_audio_transcription.completed" -> state.copy(final = str(ev, "transcript").orEmpty(), done = true)
                "error" -> state.copy(error = ev["error"]?.jsonObject?.let { str(it, "message") } ?: t, done = true)
                else -> state
            }
            VoiceProvider.DEEPGRAM -> when (str(ev, "type")) {
                "Results" -> {
                    val alt = ev["channel"]?.jsonObject?.get("alternatives")?.jsonArray?.firstOrNull()?.jsonObject
                    val text = alt?.let { str(it, "transcript") }.orEmpty()
                    if (ev["is_final"]?.jsonPrimitive?.booleanOrNull == true) {
                        state.copy(committed = if (text.isBlank()) state.committed else state.committed + text, partial = "")
                    } else state.copy(partial = text)
                }
                "Metadata" -> if (state.closing) state.copy(final = state.committed.joinToString(" "), done = true) else state
                "Error" -> state.copy(error = str(ev, "description") ?: "Deepgram error", done = true)
                else -> if (ev.containsKey("err_code")) state.copy(error = str(ev, "err_msg") ?: "Deepgram error", done = true) else state
            }
            VoiceProvider.ELEVENLABS -> when (val t = str(ev, "message_type").orEmpty()) {
                "partial_transcript" -> state.copy(partial = str(ev, "text").orEmpty())
                "committed_transcript", "committed_transcript_with_timestamps" -> {
                    val text = str(ev, "text").orEmpty()
                    val next = state.copy(committed = if (text.isBlank()) state.committed else state.committed + text, partial = "")
                    if (state.closing) next.copy(final = next.committed.joinToString(" "), done = true) else next
                }
                else -> if (t.contains("error") || t == "quota_exceeded") {
                    state.copy(error = str(ev, "error") ?: str(ev, "message") ?: t, done = true)
                } else state
            }
            VoiceProvider.SYSTEM -> state
        }
    }

    // ---------------------------------------------------------------- Jarvis phrasing

    /** Transcript text from a file-transcription response body. */
    fun fileTranscript(p: VoiceProvider, body: String): String? {
        val o = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        return when (p) {
            VoiceProvider.DEEPGRAM -> o["results"]?.jsonObject?.get("channels")?.jsonArray?.firstOrNull()?.jsonObject
                ?.get("alternatives")?.jsonArray?.firstOrNull()?.jsonObject?.get("transcript")?.jsonPrimitive?.contentOrNull
            else -> o["text"]?.jsonPrimitive?.contentOrNull
        }?.trim()
    }

    /** Turns markdown-ish agent output into something pleasant to hear. */
    fun speakable(text: String): String = text
        .replace(Regex("```.*?```", RegexOption.DOT_MATCHES_ALL), " ")
        .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
        .replace(Regex("`([^`]*)`"), "$1")
        .replace(Regex("https?://\\S*[^\\s.,;:!?)]"), "a link")
        .replace(Regex("(?m)^\\s*([#>*\\-+|]|\\d+\\.)+\\s*"), "")
        .replace(Regex("[*_#|~]"), "")
        .replace("\u2014", ", ").replace("\u2013", ", ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** A short spoken line for a finished device task, in the Jarvis persona when enabled. */
    fun taskLine(c: VoiceConfig, success: Boolean, detail: String?, pick: Int = 0): String {
        val d = speakable(detail.orEmpty()).let { if (it.length > 220) it.take(217).substringBeforeLast(' ') + "…" else it }
        if (!c.jarvis) return if (success) (d.ifBlank { "Done." }) else "That didn't work. $d".trim()
        val n = c.userName.ifBlank { "sir" }
        val ok = listOf("Done, $n.", "All taken care of, $n.", "Finished, $n.", "Task complete, $n.")
        val bad = listOf("Apologies, $n. That didn't go to plan.", "I'm afraid that failed, $n.")
        val lead = if (success) ok[pick.mod(ok.size)] else bad[pick.mod(bad.size)]
        return if (d.isBlank()) lead else "$lead $d"
    }

    /** Energy of a PCM16 frame, for voice activity detection. */
    fun rms(pcm: ByteArray, length: Int = pcm.size): Double {
        val n = length / 2
        if (n == 0) return 0.0
        val buf = ByteBuffer.wrap(pcm, 0, n * 2).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0.0
        repeat(n) { val s = buf.short.toDouble(); sum += s * s }
        return sqrt(sum / n)
    }

    /** Wraps raw PCM16 mono in a WAV header (for file-transcription fallbacks). */
    fun wav(pcm: ByteArray, rate: Int = VOICE_SAMPLE_RATE): ByteArray {
        val b = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(pcm.size).put(pcm)
        return b.array()
    }
}

sealed interface AudioFrame {
    data class Text(val json: String) : AudioFrame
    class Binary(val pcm: ByteArray) : AudioFrame
}

data class SttState(
    val committed: List<String> = emptyList(),
    val partial: String = "",
    val final: String? = null,
    val error: String? = null,
    val closing: Boolean = false,
    val done: Boolean = false,
) {
    val text: String get() = final ?: (committed + partial).filter { it.isNotBlank() }.joinToString(" ")
}

/**
 * Energy-based end-of-speech detector over 20 ms frames: calibrates a noise floor,
 * then reports when speech was heard and has been followed by [silenceMs] of quiet.
 */
class EndOfSpeech(
    private val silenceMs: Int = 900,
    private val noSpeechMs: Int = 8_000,
    private val maxMs: Int = 60_000,
    private val minRms: Double = 450.0,
) {
    private val calib = ArrayList<Double>()
    private var floor: Double? = null
    var heard = false
        private set
    private var elapsed = 0
    private var lastVoice = 0

    /** Feed one frame's energy and duration; returns true when the utterance is over. */
    fun feed(rms: Double, frameMs: Int): Boolean {
        elapsed += frameMs
        val f = floor
        if (f == null) {
            calib += rms
            if (calib.size >= 15) floor = calib.sorted()[calib.size / 2]
            return false
        }
        if (rms > maxOf(minRms, f * 3)) {
            heard = true
            lastVoice = elapsed
        }
        return (heard && elapsed - lastVoice > silenceMs) || (!heard && elapsed > noSpeechMs) || elapsed > maxMs
    }
}
