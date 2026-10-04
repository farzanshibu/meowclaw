package com.farzanshibu.meowclaw.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.farzanshibu.meowclaw.R
import com.farzanshibu.meowclaw.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Runs `adb shell`-level commands through Shizuku when it is installed and authorised. */
class ShizukuShell {
    val isAvailable: Boolean
        get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    val hasPermission: Boolean
        get() = isAvailable && !Shizuku.isPreV11() &&
            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    val isReady: Boolean get() = hasPermission

    suspend fun requestPermission(): Boolean {
        if (!isAvailable) return false
        if (hasPermission) return true
        return withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        if (requestCode != REQUEST_CODE) return
                        Shizuku.removeRequestPermissionResultListener(this)
                        if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
                Shizuku.addRequestPermissionResultListener(listener)
                cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
                Shizuku.requestPermission(REQUEST_CODE)
            }
        } ?: false
    }

    /** Returns stdout (or stderr) of [command], or a message starting with "Error". */
    suspend fun run(command: String, timeoutSeconds: Long = 15): String = withContext(Dispatchers.IO) {
        if (!isAvailable) return@withContext "Error: Shizuku is not running. Please start Shizuku first."
        if (!hasPermission && !requestPermission()) return@withContext "Error: Shizuku permission denied."
        try {
            // Shizuku 13 keeps newProcess private; it remains the simplest way
            // to run a one-shot shell command without a bound user service.
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java,
            ).apply { isAccessible = true }
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return@withContext "Error: command timed out"
            }
            val out = process.inputStream.bufferedReader().readText().trim()
            val err = process.errorStream.bufferedReader().readText().trim()
            when {
                out.isNotEmpty() -> out
                err.isNotEmpty() -> if (process.exitValue() != 0) "Error: $err" else err
                else -> "Command executed (no output)"
            }
        } catch (e: Exception) {
            "Error running command: ${e.cause?.message ?: e.message}"
        }
    }

    suspend fun succeeded(command: String): Boolean = !run(command).startsWith("Error")

    companion object {
        private const val REQUEST_CODE = 4711
    }
}

class Notifier(private val context: Context) {
    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TASKS, "Task Completions", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Notifications for when a task completes" },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Agent running", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while MeowClaw works in the background" },
        )
    }

    private var nextId = 1000

    fun canPost(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < 33

    fun taskFinished(title: String, body: String) {
        if (!canPost()) return
        val notification = NotificationCompat.Builder(context, CHANNEL_TASKS)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(nextId++, notification) }
    }

    fun serviceNotification(text: String, actions: List<NotificationCompat.Action> = emptyList()) =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_agent)
            .setContentTitle("MeowClaw")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .apply { actions.forEach(::addAction) }
            .build()

    fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_TASKS = "task_completion_channel"
        const val CHANNEL_SERVICE = "agent_service_channel"
    }
}

class Toaster(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    fun show(message: String) {
        main.post { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    }
}

/**
 * Spoken replies. Uses the configured cloud voice (ElevenLabs, OpenAI or Deepgram)
 * and falls back down the chain to Android's built-in text-to-speech.
 */
class Speaker(context: Context, private val config: () -> VoiceConfig = { VoiceConfig() }) {
    @Volatile private var ready = false
    private lateinit var tts: TextToSpeech
    private val cloud = CloudTts()
    private val worker = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "meowclaw-voice-out") }
    @Volatile private var generation = 0

    /** The provider that spoke last, for the settings screen. */
    @Volatile var lastProvider: VoiceProvider? = null
        private set

    /** Called with true when speech starts and false when it ends, so the microphone can pause. */
    var onSpeaking: ((Boolean) -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts.language = Locale.getDefault()
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { onSpeaking?.invoke(true) }
                    override fun onDone(utteranceId: String?) { onSpeaking?.invoke(false) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { onSpeaking?.invoke(false) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { onSpeaking?.invoke(false) }
                })
                ready = true
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return
        // Markdown symbols read badly aloud.
        val plain = VoiceProtocol.speakable(text).take(TextToSpeech.getMaxSpeechInputLength() - 1)
        if (plain.isBlank()) return
        val c = config()
        stop()
        val gen = ++generation
        worker.execute {
            for (provider in c.ttsChain) {
                if (gen != generation) return@execute
                if (provider == VoiceProvider.SYSTEM) {
                    lastProvider = provider
                    if (ready) tts.speak(plain, TextToSpeech.QUEUE_FLUSH, null, "reply")
                    return@execute
                }
                onSpeaking?.invoke(true)
                val ok = try { cloud.speak(provider, c, plain) } finally { onSpeaking?.invoke(false) }
                if (ok) { lastProvider = provider; return@execute }
            }
        }
    }

    fun stop() {
        generation++
        cloud.stop()
        if (ready) tts.stop()
    }
}

/**
 * Hands-free voice: keeps recognising speech, one utterance after another,
 * until turned off — across apps and while a task runs. Each utterance goes
 * to [onUtterance]. Paused while the agent itself speaks so it doesn't hear
 * its own replies. Recognition needs the main thread; every entry point posts to it.
 */
class HandsFreeVoice(
    private val context: Context,
    private val config: () -> VoiceConfig = { VoiceConfig() },
    private val onUtterance: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val activeFlow = MutableStateFlow(false)
    val active: StateFlow<Boolean> = activeFlow.asStateFlow()
    val isActive: Boolean get() = activeFlow.value
    private val partialFlow = MutableStateFlow("")
    /** Live words while a cloud provider is transcribing you. */
    val partial: StateFlow<String> = partialFlow.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var paused = false
    private var errorStreak = 0
    /** The in-flight cloud utterance; replaced (and the old one cancelled) on every release. */
    private var cloud: CloudStt? = null
    private var cloudGen = 0

    /** After every cloud provider fails, use the system recognizer until this time. */
    private var cloudCooldownUntil = 0L

    private val usesCloud: Boolean
        get() = config().sttChain.first() != VoiceProvider.SYSTEM &&
            System.currentTimeMillis() >= cloudCooldownUntil

    val isAvailable: Boolean get() = usesCloud || SpeechRecognizer.isRecognitionAvailable(context)

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    fun start() = main.post {
        if (activeFlow.value || !hasPermission()) return@post
        activeFlow.value = true
        errorStreak = 0
        listen()
    }

    fun stop() = main.post {
        activeFlow.value = false
        release()
    }

    fun toggle() = if (activeFlow.value) stop() else start()

    fun pause(pause: Boolean) = main.post {
        paused = pause
        if (pause) release() else listen()
    }

    private fun listen() {
        if (!activeFlow.value || paused || recognizer != null || cloud != null) return
        if (usesCloud) return listenCloud()
        val sr = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = sr
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                if (recognizer !== sr) return
                errorStreak = 0
                results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }?.let(onUtterance)
                restart(0)
            }

            override fun onError(error: Int) {
                if (recognizer !== sr) return
                when (error) {
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        activeFlow.value = false
                        release()
                    }
                    // Silence: just listen again.
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(100)
                    // Busy, network or client trouble: back off so we don't spin.
                    else -> restart(500L shl minOf(errorStreak++, 4))
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        sr.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false),
        )
    }

    private fun restart(delayMs: Long) {
        release()
        main.postDelayed(::listen, delayMs)
    }

    private fun release() {
        cloud?.cancel()
        cloud = null
        cloudGen++
        partialFlow.value = ""
        recognizer?.run {
            cancel()
            destroy()
        }
        recognizer = null
    }

    /** One cloud utterance on a worker thread; loops back through [listen] like the system path. */
    private fun listenCloud() {
        val c = config()
        val stt = CloudStt()
        cloud = stt
        val gen = ++cloudGen
        val t = Thread({
            var heard: String? = null
            for (provider in c.sttChain) {
                if (provider == VoiceProvider.SYSTEM) break
                heard = stt.listenOnce(provider, c) { if (gen == cloudGen) partialFlow.value = it }
                if (heard != null) break
            }
            main.post {
                // Stopped, paused or superseded while we were listening: drop it.
                if (gen != cloudGen) return@post
                cloud = null
                partialFlow.value = ""
                when {
                    heard == null -> {
                        // Every cloud provider failed (offline, bad key, quota): use the
                        // phone's own recognizer for a minute, then try the cloud again.
                        cloudCooldownUntil = System.currentTimeMillis() + 60_000
                        main.postDelayed(::listen, 500L shl minOf(errorStreak++, 4))
                    }
                    heard.isBlank() -> main.postDelayed(::listen, 100)
                    else -> {
                        errorStreak = 0
                        onUtterance(heard)
                        main.postDelayed(::listen, 0)
                    }
                }
            }
        }, "meowclaw-voice-in")
        t.start()
    }
}
