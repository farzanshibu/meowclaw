package com.farzanshibu.meowclaw.llm.cactus

import android.content.Context
import android.util.Log
import com.farzanshibu.meowclaw.data.AppJson
import com.farzanshibu.meowclaw.llm.ChatTurn
import com.farzanshibu.meowclaw.llm.LlmException
import com.farzanshibu.meowclaw.llm.interruptible
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

data class LocalCall(val name: String, val arguments: JsonObject)

data class LocalResult(
    val text: String,
    val calls: List<LocalCall>,
    val totalTokens: Int,
    val confidence: Double?,
    val decodeTps: Double?,
)

/**
 * Runs the selected on-device model through the matching Cactus runtime.
 * One model is resident at a time; switching models frees the previous one.
 */
class LocalLlm(private val context: Context, private val models: LocalModelManager) {
    private val dispatcher = Executors.newSingleThreadExecutor { Thread(null, it, "cactus", 16L shl 20) }
        .asCoroutineDispatcher()
    private val mutex = Mutex()
    private var loaded: LocalModel? = null
    private var handle = 0L

    val loadedModel: LocalModel? get() = loaded

    private val loadingFlow = MutableStateFlow(false)
    /** True while model weights are being loaded into memory (can take seconds). */
    val loading: StateFlow<Boolean> = loadingFlow.asStateFlow()

    fun isRuntimeAvailable(model: LocalModel) = when (model.runtime) {
        Runtime.V1 -> CactusV1Native.loaded
        Runtime.V2 -> CactusV2Native.loaded
    }

    private fun ensureLoaded(model: LocalModel) {
        if (loaded == model && handle != 0L) return
        unloadLocked()
        if (!models.isReady(model)) throw LlmException("${model.name} is not downloaded yet.")
        if (!isRuntimeAvailable(model)) throw LlmException("On-device models need a 64-bit ARM phone.")
        loadingFlow.value = true
        try {
            load(model)
        } finally {
            loadingFlow.value = false
        }
    }

    private fun load(model: LocalModel) {
        val path = models.dir(model).absolutePath
        val telemetryDir = File(context.filesDir, "cactus").apply { mkdirs() }.absolutePath
        handle = when (model.runtime) {
            Runtime.V1 -> {
                CactusV1Native.nativeSetCacheDir(telemetryDir)
                CactusV1Native.nativeInit(path, null, false)
            }
            Runtime.V2 -> {
                CactusV2Native.nativeSetTelemetryEnvironment("meowclaw", telemetryDir, null)
                CactusV2Native.nativeInit(path, null, false)
            }
        }
        if (handle == 0L) {
            val error = lastError(model.runtime)
            throw LlmException("Could not load ${model.name}: ${error.ifBlank { "not enough memory?" }}")
        }
        loaded = model
        Log.i(TAG, "Loaded ${model.name}")
    }

    private fun unloadLocked() {
        val current = loaded ?: return
        if (handle != 0L) when (current.runtime) {
            Runtime.V1 -> CactusV1Native.nativeDestroy(handle)
            Runtime.V2 -> CactusV2Native.nativeDestroy(handle)
        }
        handle = 0L
        loaded = null
    }

    suspend fun unload() = mutex.withLock { withContext(dispatcher) { unloadLocked() } }

    /**
     * One completion. [images] are file paths attached to the last user turn.
     * [tools] are OpenAI-style function schemas; calls come back in [LocalResult.calls].
     */
    suspend fun complete(
        model: LocalModel,
        system: String?,
        turns: List<ChatTurn>,
        images: List<String> = emptyList(),
        tools: JsonArray? = null,
        maxTokens: Int = 512,
        temperature: Double = 0.2,
        forceTools: Boolean = false,
        onToken: ((String) -> Unit)? = null,
    ): LocalResult = mutex.withLock {
        val generating = AtomicLong(0L)
        // Stop generation the moment the caller is cancelled (the STOP button).
        val stop = {
            val h = generating.get()
            if (h != 0L) when (model.runtime) {
                Runtime.V1 -> CactusV1Native.nativeStop(h)
                Runtime.V2 -> CactusV2Native.nativeStop(h)
            }
        }
        interruptible(dispatcher, stop) {
            ensureLoaded(model)
            val runtime = model.runtime
            val h = handle
            generating.set(h)
            try {
                // Each request carries its full context; stale KV state would leak between tasks.
                when (runtime) {
                    Runtime.V1 -> CactusV1Native.nativeReset(h)
                    Runtime.V2 -> CactusV2Native.nativeReset(h)
                }
                val messages = messagesJson(system, turns, images)
                val options = buildJsonObject {
                    put("max_tokens", maxTokens)
                    put("temperature", temperature)
                    put("auto_handoff", false)
                    put("enable_thinking_if_supported", false)
                    if (tools != null) put("tool_rag_top_k", 0)
                    // Constrains decoding to a well-formed tool call.
                    if (tools != null && forceTools) put("force_tools", true)
                }.toString()
                val toolsJson = tools?.toString()
                val callback = onToken?.let { cb -> CactusTokenCallback { token, _ -> cb(token) } }
                // Cancelled while loading or resetting: don't start a generation nobody will stop.
                ensureActive()
                val raw = when (runtime) {
                    Runtime.V1 -> CactusV1Native.nativeComplete(h, messages, options, toolsJson, callback, null)
                    Runtime.V2 -> {
                        val buffer = ByteArray(RESPONSE_BUFFER)
                        val n = CactusV2Native.nativeComplete(h, messages, buffer, options, toolsJson, callback, null)
                        if (n < 0) throw LlmException("On-device generation failed: ${lastError(runtime)}")
                        buffer.decodeToString(0, buffer.indexOf(0).let { if (it < 0) n.coerceAtMost(buffer.size) else it })
                    }
                }
                parse(raw)
            } finally {
                generating.set(0L)
            }
        }
    }

    private fun lastError(runtime: Runtime) = runCatching {
        when (runtime) {
            Runtime.V1 -> CactusV1Native.nativeGetLastError()
            Runtime.V2 -> CactusV2Native.nativeGetLastError()
        }
    }.getOrDefault("")

    companion object {
        private const val TAG = "LocalLlm"
        private const val RESPONSE_BUFFER = 256 * 1024

        fun messagesJson(system: String?, turns: List<ChatTurn>, images: List<String>): String {
            val array = buildJsonArray {
                if (!system.isNullOrBlank()) add(buildJsonObject { put("role", "system"); put("content", system) })
                turns.forEachIndexed { i, turn ->
                    add(buildJsonObject {
                        put("role", turn.role)
                        put("content", turn.content)
                        if (i == turns.lastIndex && turn.role == "user" && images.isNotEmpty()) {
                            put("images", buildJsonArray { images.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                        }
                    })
                }
            }
            return asciiSafe(array.toString())
        }

        /**
         * JNI hands strings over as modified UTF-8, which cannot carry emoji;
         * JSON-escaping every surrogate keeps the payload lossless.
         */
        fun asciiSafe(json: String): String {
            if (json.none { it.isSurrogate() }) return json
            val out = StringBuilder(json.length + 16)
            json.forEach { c -> if (c.isSurrogate()) out.append("\\u%04x".format(c.code)) else out.append(c) }
            return out.toString()
        }

        fun parse(raw: String): LocalResult {
            val json = runCatching { AppJson.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: return LocalResult(raw.trim(), emptyList(), 0, null, null)
            if (json["success"]?.jsonPrimitive?.contentOrNull == "false") {
                throw LlmException("On-device generation failed: ${json["error"]?.jsonPrimitive?.contentOrNull}")
            }
            val calls = runCatching {
                json["function_calls"]?.jsonArray?.map { element ->
                    val call = element.jsonObject
                    val args = call["arguments"]?.let { a ->
                        runCatching { a.jsonObject }.getOrNull()
                            ?: runCatching { AppJson.parseToJsonElement(a.jsonPrimitive.content).jsonObject }.getOrNull()
                    } ?: JsonObject(emptyMap())
                    LocalCall(call["name"]?.jsonPrimitive?.contentOrNull.orEmpty(), args)
                }
            }.getOrNull().orEmpty().filter { it.name.isNotBlank() }
            return LocalResult(
                text = json["response"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty().trim(),
                calls = calls,
                totalTokens = json["total_tokens"]?.jsonPrimitive?.intOrNull ?: 0,
                confidence = json["confidence"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() },
                decodeTps = json["decode_tps"]?.let { runCatching { it.jsonPrimitive.doubleOrNull }.getOrNull() },
            )
        }
    }
}
