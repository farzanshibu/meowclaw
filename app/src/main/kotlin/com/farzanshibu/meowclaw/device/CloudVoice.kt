package com.farzanshibu.meowclaw.device

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Cloud text-to-speech: streams 24 kHz PCM from ElevenLabs, OpenAI or Deepgram
 * straight into an [AudioTrack], so speech starts as the first bytes arrive.
 * Blocking; call off the main thread.
 */
class CloudTts(private val http: OkHttpClient = DEFAULT_HTTP) {
    private val current = AtomicReference<AudioTrack?>(null)
    private val cancelled = AtomicBoolean(false)

    /** Speaks [text] with [provider]. Returns false (having played nothing) if the provider failed. */
    fun speak(provider: VoiceProvider, config: VoiceConfig, text: String): Boolean {
        cancelled.set(false)
        val spec = VoiceProtocol.ttsRequest(provider, config, text)
        val request = Request.Builder().url(spec.url)
            .apply { spec.headers.forEach { (k, v) -> header(k, v) } }
            .post(spec.jsonBody.toRequestBody(JSON))
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: Exception) {
            Log.w(TAG, "${provider.label} TTS unreachable: ${e.message}")
            return false
        }
        response.use { r ->
            if (!r.isSuccessful) {
                Log.w(TAG, "${provider.label} TTS HTTP ${r.code}: ${r.body.string().take(200)}")
                return false
            }
            return play(r)
        }
    }

    private fun play(r: Response): Boolean {
        val minBuf = AudioTrack.getMinBufferSize(VOICE_SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            .setAudioFormat(
                AudioFormat.Builder().setSampleRate(VOICE_SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build(),
            )
            .setBufferSizeInBytes(maxOf(minBuf * 4, VOICE_SAMPLE_RATE))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        current.getAndSet(track)?.let { runCatching { it.stop(); it.release() } }
        var written = 0L
        try {
            track.play()
            val input = r.body.byteStream()
            val buf = ByteArray(4800)
            var carry = -1 // keep writes 16-bit aligned across reads
            while (!cancelled.get()) {
                val n = input.read(buf, if (carry >= 0) 1 else 0, buf.size - 1)
                if (n < 0) break
                var len = n
                if (carry >= 0) { buf[0] = carry.toByte(); len += 1; carry = -1 }
                if (len % 2 == 1) { carry = buf[len - 1].toInt() and 0xFF; len -= 1 }
                if (len > 0) { track.write(buf, 0, len); written += len }
            }
            if (!cancelled.get()) {
                // Let the tail play out before releasing.
                val frames = (written / 2).toInt()
                val deadline = System.currentTimeMillis() + 2_000 + frames * 1000L / VOICE_SAMPLE_RATE
                while (!cancelled.get() && track.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) Thread.sleep(30)
            }
        } catch (e: Exception) {
            Log.w(TAG, "playback failed: ${e.message}")
        } finally {
            current.compareAndSet(track, null)
            runCatching { track.stop() }
            track.release()
        }
        return written > 0
    }

    fun stop() {
        cancelled.set(true)
        current.getAndSet(null)?.let { runCatching { it.pause(); it.flush() } }
    }

    companion object {
        private const val TAG = "CloudTts"
        private val JSON = "application/json".toMediaType()
        val DEFAULT_HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Cloud speech-to-text for one utterance: records the microphone, streams it to
 * the provider's realtime socket while you talk, stops on a pause, and returns
 * the final transcript. Falls back to uploading the recording if the socket fails.
 * Blocking; call off the main thread.
 */
class CloudStt(private val http: OkHttpClient = DEFAULT_HTTP) {
    private val stopRequested = AtomicBoolean(false)

    fun cancel() = stopRequested.set(true)

    /**
     * Listens once. [onPartial] gets live text while you speak. Returns the transcript,
     * "" if nothing was said, or null if every provider failed.
     */
    @SuppressLint("MissingPermission") // callers check RECORD_AUDIO first
    fun listenOnce(provider: VoiceProvider, config: VoiceConfig, onPartial: (String) -> Unit = {}): String? {
        stopRequested.set(false)
        val session = runCatching { StreamSession(provider, config, onPartial) }
            .onFailure { Log.w(TAG, "${provider.label} stream unavailable: ${it.message}") }
            .getOrNull()

        val minBuf = AudioRecord.getMinBufferSize(VOICE_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, VOICE_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, FRAME_BYTES * 10),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            session?.close()
            return null
        }
        val pcm = java.io.ByteArrayOutputStream()
        val vad = EndOfSpeech()
        try {
            recorder.startRecording()
            val frame = ByteArray(FRAME_BYTES)
            while (!stopRequested.get()) {
                var got = 0
                while (got < FRAME_BYTES) {
                    val n = recorder.read(frame, got, FRAME_BYTES - got)
                    if (n <= 0) break
                    got += n
                }
                if (got <= 0) break
                pcm.write(frame, 0, got)
                session?.send(frame.copyOf(got))
                if (vad.feed(VoiceProtocol.rms(frame, got), FRAME_MS)) break
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
        if (!vad.heard || stopRequested.get()) {
            session?.close()
            return ""
        }
        session?.finish()?.takeIf { it.isNotBlank() }?.let { return it }
        // Streaming failed or came back empty: upload the recording instead.
        return transcribeFile(provider, config, pcm.toByteArray())
    }

    /** One-shot transcription of a recorded utterance. */
    fun transcribeFile(provider: VoiceProvider, config: VoiceConfig, pcm: ByteArray): String? = try {
        val wav = VoiceProtocol.wav(pcm).toRequestBody("audio/wav".toMediaType())
        val request = when (provider) {
            VoiceProvider.OPENAI -> Request.Builder().url("${config.openAiBase.trimEnd('/')}/audio/transcriptions")
                .header("Authorization", "Bearer ${config.openAiKey.trim()}")
                .post(MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", "gpt-4o-mini-transcribe")
                    .addFormDataPart("file", "speech.wav", wav).build())
            VoiceProvider.ELEVENLABS -> Request.Builder().url("${config.elevenLabsBase.trimEnd('/')}/v1/speech-to-text")
                .header("xi-api-key", config.elevenLabsKey.trim())
                .post(MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model_id", "scribe_v2")
                    .addFormDataPart("file", "speech.wav", wav).build())
            VoiceProvider.DEEPGRAM -> Request.Builder()
                .url("${config.deepgramBase.trimEnd('/')}/v1/listen?model=nova-3&smart_format=true")
                .header("Authorization", "Token ${config.deepgramKey.trim()}")
                .post(wav)
            VoiceProvider.SYSTEM -> return null
        }.build()
        http.newCall(request).execute().use { r ->
            val body = r.body.string()
            if (!r.isSuccessful) {
                Log.w(TAG, "${provider.label} file STT HTTP ${r.code}: ${body.take(200)}")
                null
            } else VoiceProtocol.fileTranscript(provider, body)
        }
    } catch (e: Exception) {
        Log.w(TAG, "${provider.label} file STT failed: ${e.message}")
        null
    }

    /** A live websocket to one provider for one utterance. */
    private inner class StreamSession(
        private val provider: VoiceProvider,
        config: VoiceConfig,
        private val onPartial: (String) -> Unit,
    ) {
        @Volatile private var state = SttState()
        private val finished = CountDownLatch(1)
        private val opened = CountDownLatch(1)
        private val ws: WebSocket

        init {
            val spec = VoiceProtocol.sttSocket(provider, config)
            val request = Request.Builder().url(spec.url).apply { spec.headers.forEach { (k, v) -> header(k, v) } }.build()
            ws = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    VoiceProtocol.sttStart(provider)?.let(webSocket::send)
                    opened.countDown()
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val before = state.text
                    state = VoiceProtocol.sttEvent(provider, state, text)
                    if (state.text != before && !state.done) onPartial(state.text)
                    if (state.done) finished.countDown()
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (state.closing && state.final == null) state = state.copy(final = state.text)
                    state = state.copy(done = true)
                    finished.countDown()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    state = state.copy(error = t.message ?: "socket failed", done = true)
                    opened.countDown()
                    finished.countDown()
                }
            })
            if (!opened.await(6, TimeUnit.SECONDS) || state.error != null) {
                ws.cancel()
                error(state.error ?: "connect timeout")
            }
        }

        fun send(pcm: ByteArray) {
            if (state.done) return
            when (val f = VoiceProtocol.sttAudio(provider, pcm)) {
                is AudioFrame.Text -> ws.send(f.json)
                is AudioFrame.Binary -> ws.send(f.pcm.toByteString())
            }
        }

        /** Commits the utterance and waits for the final transcript. */
        fun finish(): String? {
            state = state.copy(closing = true)
            VoiceProtocol.sttCommit(provider).forEach(ws::send)
            finished.await(12, TimeUnit.SECONDS)
            ws.close(1000, null)
            return if (state.error != null && state.text.isBlank()) null else state.text
        }

        fun close() {
            ws.close(1000, null)
        }
    }

    companion object {
        private const val TAG = "CloudStt"
        private const val FRAME_MS = 20
        private const val FRAME_BYTES = VOICE_SAMPLE_RATE / 1000 * FRAME_MS * 2
        val DEFAULT_HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }
}
