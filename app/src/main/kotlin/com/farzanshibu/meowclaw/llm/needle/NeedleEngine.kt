package com.farzanshibu.meowclaw.llm.needle

import android.content.Context
import android.os.Build
import android.util.Log
import com.farzanshibu.meowclaw.data.AppJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** JNI surface of libmeowclaw_needle.so (see src/main/cpp/needle_jni.cpp). */
internal object NeedleNative {
    val loaded: Boolean = runCatching { System.loadLibrary("meowclaw_needle") }
        .onFailure { Log.e("NeedleNative", "Native library failed to load", it) }
        .isSuccess

    @JvmStatic external fun nativeAvailable(): Boolean
    @JvmStatic external fun nativeLoad(path: String): Int
    @JvmStatic external fun nativeInit(system: ByteArray, tools: ByteArray, indexPath: String?): Int
    @JvmStatic external fun nativeComplete(input: ByteArray, maxNewTokens: Int): ByteArray?
    @JvmStatic external fun nativeReset()
    @JvmStatic external fun nativeLastError(): ByteArray
}

data class NeedleCall(val name: String, val arguments: JsonObject)

data class NeedleResult(
    val calls: List<NeedleCall>,
    val suppressed: List<NeedleCall>,
    val confidence: Double?,
    val reasoning: String,
    val latencyMs: Long,
    val raw: String,
)

sealed interface NeedleState {
    data object Unsupported : NeedleState
    data object NotDownloaded : NeedleState
    data class Downloading(val progress: Float) : NeedleState
    data object Ready : NeedleState
    data class Failed(val message: String) : NeedleState
}

/**
 * Needle 3 (Cactus Compute): a 35 MB on-device model that turns a request into
 * JSON tool calls in tens of milliseconds, offline. The engine is one
 * process-global model, so every call runs on a dedicated single thread.
 */
class NeedleEngine(private val context: Context) {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "needle") }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloadMutex = Mutex()
    private val modelFile = File(context.filesDir, "needle/$WEIGHTS_NAME")
    private val stateFlow = MutableStateFlow(initialState())
    val state: StateFlow<NeedleState> = stateFlow.asStateFlow()

    private var weightsLoaded = false
    private var initialisedFor: Pair<String, String>? = null

    val isSupportedAbi: Boolean
        get() = Build.SUPPORTED_ABIS.firstOrNull() in setOf("arm64-v8a", "armeabi-v7a") &&
            NeedleNative.loaded && runCatching { NeedleNative.nativeAvailable() }.getOrDefault(false)

    val isReady: Boolean get() = stateFlow.value == NeedleState.Ready

    private fun initialState(): NeedleState = when {
        !isSupportedAbi -> NeedleState.Unsupported
        modelFile.isFile && modelFile.length() == WEIGHTS_SIZE -> NeedleState.Ready
        else -> NeedleState.NotDownloaded
    }

    fun download() {
        if (stateFlow.value is NeedleState.Downloading || stateFlow.value == NeedleState.Unsupported) return
        scope.launch { downloadMutex.withLock { downloadLocked() } }
    }

    private suspend fun downloadLocked() {
        if (modelFile.isFile && modelFile.length() == WEIGHTS_SIZE) {
            stateFlow.value = NeedleState.Ready
            return
        }
        stateFlow.value = NeedleState.Downloading(0f)
        val part = File(modelFile.parentFile, "$WEIGHTS_NAME.part")
        try {
            modelFile.parentFile?.mkdirs()
            val client = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(WEIGHTS_URL).build()).execute().use { response ->
                if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                val total = response.body.contentLength().takeIf { it > 0 } ?: WEIGHTS_SIZE
                val digest = MessageDigest.getInstance("SHA-256")
                response.body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            read += n
                            stateFlow.value = NeedleState.Downloading((read.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                check(sha == WEIGHTS_SHA256) { "Checksum mismatch — download corrupted" }
            }
            check(part.renameTo(modelFile)) { "Could not store the model" }
            stateFlow.value = NeedleState.Ready
        } catch (e: Exception) {
            part.delete()
            Log.e(TAG, "Needle download failed", e)
            stateFlow.value = NeedleState.Failed(e.message ?: "Download failed")
        }
    }

    fun delete() = scope.launch {
        withContext(dispatcher) {
            // The engine cannot unload weights; the next process start reloads cleanly.
            modelFile.delete()
            stateFlow.value = initialState()
        }
    }

    /**
     * Runs one stateless query against [tools] (a JSON array of schemas).
     * [system] carries environment facts such as the date.
     */
    suspend fun complete(system: String, tools: String, query: String, maxNewTokens: Int = 256): NeedleResult =
        withContext(dispatcher) {
            check(isReady) { "Needle model is not downloaded" }
            if (!weightsLoaded) {
                check(NeedleNative.nativeLoad(modelFile.absolutePath) >= 0) { "needle_load: ${lastError()}" }
                weightsLoaded = true
            }
            if (initialisedFor != system to tools) {
                val index = File(context.filesDir, "needle/tools.idx").absolutePath
                check(NeedleNative.nativeInit(system.toByteArray(), tools.toByteArray(), index) >= 0) {
                    "needle_init: ${lastError()}"
                }
                initialisedFor = system to tools
            }
            // Every request is independent; old turns only lower accuracy.
            NeedleNative.nativeReset()
            val started = System.nanoTime()
            val raw = NeedleNative.nativeComplete(query.toByteArray(), maxNewTokens)?.decodeToString()
                ?: throw IllegalStateException("needle_complete: ${lastError()}")
            parse(raw, (System.nanoTime() - started) / 1_000_000)
        }

    private fun lastError() = runCatching { NeedleNative.nativeLastError().decodeToString() }.getOrDefault("unknown error")

    companion object {
        private const val TAG = "NeedleEngine"
        private const val REVISION = "b274efcb211a9eef48c9a88da4b43bd569696a39"
        const val WEIGHTS_NAME = "needle3.cact"
        const val WEIGHTS_SIZE = 35_335_380L
        const val WEIGHTS_SHA256 = "c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38"
        const val WEIGHTS_URL = "https://huggingface.co/Cactus-Compute/needle3/resolve/$REVISION/$WEIGHTS_NAME"

        /** Environment facts; the date lets Needle resolve "tomorrow" and weekdays. */
        fun systemFacts(now: Date = Date()): String =
            "date: ${SimpleDateFormat("yyyy-MM-dd EEE", Locale.US).format(now)}; " +
                "locale: ${Locale.getDefault().toLanguageTag()}; device: android phone"

        fun parse(raw: String, latencyMs: Long): NeedleResult {
            val json = AppJson.parseToJsonElement(raw).jsonObject
            fun calls(key: String) = json[key]?.let { element ->
                runCatching {
                    element.jsonArray.map {
                        val call = it.jsonObject
                        NeedleCall(
                            call["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            call["arguments"]?.let { a -> runCatching { a.jsonObject }.getOrNull() } ?: JsonObject(emptyMap()),
                        )
                    }
                }.getOrNull()
            }.orEmpty()
            return NeedleResult(
                calls = calls("function_calls"),
                suppressed = calls("suppressed_calls"),
                confidence = json["confidence"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() },
                reasoning = json["reasoning"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty(),
                latencyMs = latencyMs,
                raw = raw,
            )
        }
    }
}
