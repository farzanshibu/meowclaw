package com.farzanshibu.meowclaw

import com.farzanshibu.meowclaw.device.AudioFrame
import com.farzanshibu.meowclaw.device.EndOfSpeech
import com.farzanshibu.meowclaw.device.SttState
import com.farzanshibu.meowclaw.device.VoiceConfig
import com.farzanshibu.meowclaw.device.VoiceProtocol
import com.farzanshibu.meowclaw.device.VoiceProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceProtocolTest {
    private val keys = VoiceConfig(elevenLabsKey = "el-key", openAiKey = "sk-key", deepgramKey = "dg-key")

    @Test fun chainPrefersChosenThenKeyedThenSystem() {
        assertEquals(listOf(VoiceProvider.SYSTEM), VoiceConfig().ttsChain)
        // Chosen provider without a key is skipped.
        assertEquals(listOf(VoiceProvider.SYSTEM), VoiceConfig(ttsProvider = VoiceProvider.ELEVENLABS).ttsChain)
        assertEquals(
            listOf(VoiceProvider.DEEPGRAM, VoiceProvider.ELEVENLABS, VoiceProvider.OPENAI, VoiceProvider.SYSTEM),
            keys.copy(ttsProvider = VoiceProvider.DEEPGRAM).ttsChain,
        )
        assertEquals(
            listOf(VoiceProvider.ELEVENLABS, VoiceProvider.OPENAI, VoiceProvider.DEEPGRAM, VoiceProvider.SYSTEM),
            keys.copy(sttProvider = VoiceProvider.ELEVENLABS).sttChain,
        )
        // Choosing SYSTEM keeps cloud providers as later fallbacks only if they come first in the list.
        assertEquals(VoiceProvider.SYSTEM, keys.copy(ttsProvider = VoiceProvider.SYSTEM).ttsChain.first())
    }

    @Test fun elevenLabsTtsRequest() {
        val r = VoiceProtocol.ttsRequest(VoiceProvider.ELEVENLABS, keys, "Hello \"sir\"")
        assertEquals("https://api.elevenlabs.io/v1/text-to-speech/JBFqnCBsd6RMkjVDRZzb/stream?output_format=pcm_24000", r.url)
        assertEquals("el-key", r.headers["xi-api-key"])
        val body = Json.parseToJsonElement(r.jsonBody).jsonObject
        assertEquals("Hello \"sir\"", body["text"]!!.jsonPrimitive.content)
        assertEquals("eleven_flash_v2_5", body["model_id"]!!.jsonPrimitive.content)
    }

    @Test fun openAiTtsRequestCarriesJarvisDirection() {
        val r = VoiceProtocol.ttsRequest(VoiceProvider.OPENAI, keys, "Done.")
        assertEquals("https://api.openai.com/v1/audio/speech", r.url)
        assertEquals("Bearer sk-key", r.headers["Authorization"])
        val body = Json.parseToJsonElement(r.jsonBody).jsonObject
        assertEquals("gpt-4o-mini-tts", body["model"]!!.jsonPrimitive.content)
        assertEquals("ash", body["voice"]!!.jsonPrimitive.content)
        assertEquals("pcm", body["response_format"]!!.jsonPrimitive.content)
        assertTrue(body["instructions"]!!.jsonPrimitive.content.contains("JARVIS"))
        val plain = Json.parseToJsonElement(VoiceProtocol.ttsRequest(VoiceProvider.OPENAI, keys.copy(jarvis = false), "x").jsonBody).jsonObject
        assertNull(plain["instructions"])
    }

    @Test fun deepgramTtsRequestIsRawPcm() {
        val r = VoiceProtocol.ttsRequest(VoiceProvider.DEEPGRAM, keys, "Hi")
        assertEquals(
            "https://api.deepgram.com/v1/speak?model=aura-2-draco-en&encoding=linear16&sample_rate=24000&container=none",
            r.url,
        )
        assertEquals("Token dg-key", r.headers["Authorization"])
        assertEquals("""{"text":"Hi"}""", r.jsonBody)
    }

    @Test fun sttSocketsAndFrames() {
        assertEquals("wss://api.openai.com/v1/realtime?intent=transcription", VoiceProtocol.sttSocket(VoiceProvider.OPENAI, keys).url)
        assertTrue(VoiceProtocol.sttSocket(VoiceProvider.DEEPGRAM, keys).url.startsWith("wss://api.deepgram.com/v1/listen?model=nova-3&encoding=linear16&sample_rate=24000"))
        assertEquals(
            "wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=scribe_v2_realtime&audio_format=pcm_24000&commit_strategy=manual",
            VoiceProtocol.sttSocket(VoiceProvider.ELEVENLABS, keys).url,
        )
        val start = Json.parseToJsonElement(VoiceProtocol.sttStart(VoiceProvider.OPENAI)!!).jsonObject
        assertEquals("transcription", start["session"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertNull(VoiceProtocol.sttStart(VoiceProvider.DEEPGRAM))

        val pcm = byteArrayOf(1, 2, 3, 4)
        val dg = VoiceProtocol.sttAudio(VoiceProvider.DEEPGRAM, pcm)
        assertTrue(dg is AudioFrame.Binary)
        assertArrayEquals(pcm, (dg as AudioFrame.Binary).pcm)
        val el = Json.parseToJsonElement((VoiceProtocol.sttAudio(VoiceProvider.ELEVENLABS, pcm) as AudioFrame.Text).json).jsonObject
        assertEquals("AQIDBA==", el["audio_base_64"]!!.jsonPrimitive.content)
        assertEquals("false", el["commit"]!!.jsonPrimitive.content)
        assertEquals(listOf("""{"type":"Finalize"}""", """{"type":"CloseStream"}"""), VoiceProtocol.sttCommit(VoiceProvider.DEEPGRAM))
    }

    @Test fun openAiTranscriptEvents() {
        var s = SttState()
        s = VoiceProtocol.sttEvent(VoiceProvider.OPENAI, s, """{"type":"conversation.item.input_audio_transcription.delta","delta":"open "}""")
        s = VoiceProtocol.sttEvent(VoiceProvider.OPENAI, s, """{"type":"conversation.item.input_audio_transcription.delta","delta":"settings"}""")
        assertEquals("open settings", s.text)
        assertFalse(s.done)
        s = VoiceProtocol.sttEvent(VoiceProvider.OPENAI, s, """{"type":"conversation.item.input_audio_transcription.completed","transcript":"Open settings."}""")
        assertTrue(s.done)
        assertEquals("Open settings.", s.text)
        val err = VoiceProtocol.sttEvent(VoiceProvider.OPENAI, SttState(), """{"type":"error","error":{"message":"bad key"}}""")
        assertEquals("bad key", err.error)
    }

    @Test fun deepgramTranscriptEvents() {
        fun res(t: String, final: Boolean) = """{"type":"Results","is_final":$final,"channel":{"alternatives":[{"transcript":"$t"}]}}"""
        var s = SttState()
        s = VoiceProtocol.sttEvent(VoiceProvider.DEEPGRAM, s, res("call", false))
        assertEquals("call", s.text)
        s = VoiceProtocol.sttEvent(VoiceProvider.DEEPGRAM, s, res("Call mom", true))
        s = VoiceProtocol.sttEvent(VoiceProvider.DEEPGRAM, s, res("on whats", false))
        assertEquals("Call mom on whats", s.text)
        s = VoiceProtocol.sttEvent(VoiceProvider.DEEPGRAM, s.copy(closing = true), res("on WhatsApp", true))
        s = VoiceProtocol.sttEvent(VoiceProvider.DEEPGRAM, s, """{"type":"Metadata"}""")
        assertTrue(s.done)
        assertEquals("Call mom on WhatsApp", s.text)
    }

    @Test fun elevenLabsTranscriptEvents() {
        var s = SttState()
        s = VoiceProtocol.sttEvent(VoiceProvider.ELEVENLABS, s, """{"message_type":"session_started"}""")
        s = VoiceProtocol.sttEvent(VoiceProvider.ELEVENLABS, s, """{"message_type":"partial_transcript","text":"set a"}""")
        assertEquals("set a", s.text)
        s = VoiceProtocol.sttEvent(VoiceProvider.ELEVENLABS, s.copy(closing = true), """{"message_type":"committed_transcript","text":"Set a timer for ten minutes"}""")
        assertTrue(s.done)
        assertEquals("Set a timer for ten minutes", s.text)
        val auth = VoiceProtocol.sttEvent(VoiceProvider.ELEVENLABS, SttState(), """{"message_type":"auth_error","error":"You must be authenticated"}""")
        assertEquals("You must be authenticated", auth.error)
    }

    @Test fun fileTranscripts() {
        assertEquals("hello", VoiceProtocol.fileTranscript(VoiceProvider.OPENAI, """{"text":" hello "}"""))
        assertEquals("hi there", VoiceProtocol.fileTranscript(VoiceProvider.ELEVENLABS, """{"text":"hi there","words":[]}"""))
        assertEquals(
            "open maps",
            VoiceProtocol.fileTranscript(VoiceProvider.DEEPGRAM, """{"results":{"channels":[{"alternatives":[{"transcript":"open maps"}]}]}}"""),
        )
        assertNull(VoiceProtocol.fileTranscript(VoiceProvider.OPENAI, "not json"))
    }

    @Test fun jarvisLines() {
        val j = VoiceConfig(userName = "boss")
        assertEquals("Done, boss. Opened YouTube.", VoiceProtocol.taskLine(j, true, "Opened **YouTube**.", pick = 0))
        assertEquals("I'm afraid that failed, boss. No app called Foo.", VoiceProtocol.taskLine(j, false, "No app called `Foo`.", pick = 1))
        assertEquals("Finished, boss.", VoiceProtocol.taskLine(j, true, "", pick = 2))
        assertEquals("Opened YouTube.", VoiceProtocol.taskLine(j.copy(jarvis = false), true, "Opened YouTube."))
        val long = VoiceProtocol.taskLine(j, true, "word ".repeat(100))
        assertTrue(long.length < 260)
    }

    @Test fun speakableStripsMarkdown() {
        assertEquals(
            "Title Use the settings app, see a link. done",
            VoiceProtocol.speakable("# Title\n- Use the **settings** app\u2014see https://x.y/z.\n```code```done"),
        )
    }

    @Test fun endOfSpeechDetectsPauseAfterSpeech() {
        val vad = EndOfSpeech(silenceMs = 200, noSpeechMs = 2000)
        repeat(15) { assertFalse(vad.feed(30.0, 20)) }    // calibrate on quiet
        repeat(20) { assertFalse(vad.feed(3000.0, 20)) }  // talking
        assertTrue(vad.heard)
        var ended = false
        repeat(15) { if (vad.feed(30.0, 20)) ended = true } // 300 ms of quiet
        assertTrue(ended)
        val silent = EndOfSpeech(noSpeechMs = 500)
        var gaveUp = false
        repeat(60) { if (silent.feed(30.0, 20)) gaveUp = true }
        assertTrue(gaveUp)
        assertFalse(silent.heard)
    }

    @Test fun pcmHelpers() {
        val pcm = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putShort(1000).putShort(-1000).putShort(1000).putShort(-1000).array()
        assertEquals(1000.0, VoiceProtocol.rms(pcm), 0.01)
        val wav = VoiceProtocol.wav(pcm)
        assertEquals(44 + 8, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals(24000, ByteBuffer.wrap(wav, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }
}
