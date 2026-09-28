package com.farzanshibu.meowclaw.llm

import android.util.Log
import com.farzanshibu.meowclaw.data.AgentAction
import com.farzanshibu.meowclaw.data.AppJson
import com.farzanshibu.meowclaw.data.AppSettings
import com.farzanshibu.meowclaw.llm.cactus.LocalCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit

data class ChatTurn(val role: String, val content: String)

/** [call] is set when the model answered with a native tool call instead of text. */
data class LlmResponse(val content: String, val totalTokens: Int, val call: LocalCall? = null)

open class LlmException(message: String) : Exception(message)

/** The endpoint rejected the `tools` field; callers retry with JSON-in-prompt instead. */
class ToolsUnsupportedException(message: String) : LlmException(message)

/** Any OpenAI-compatible /chat/completions endpoint: cloud providers or a local server. */
class LlmClient(private val settings: () -> AppSettings) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    private fun effectiveMaxTokens(s: AppSettings): Int =
        // GLM on NVIDIA reasons first; the 1,024 default can end before any visible text.
        if (isNvidiaBaseUrl(s.baseUrl) && s.model == NVIDIA_DEFAULT_MODEL && s.maxTokens < 4096) 4096 else s.maxTokens

    /** Endpoints (base URL + model) that rejected native tools; they get JSON prompts from then on. */
    private val noTools = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun toolsKey(s: AppSettings) = "${s.baseUrl.trim()}|${s.model}"

    fun supportsTools(): Boolean = toolsKey(settings()) !in noTools

    private fun request(
        s: AppSettings,
        messages: List<ChatTurn>,
        stream: Boolean,
        images: List<String> = emptyList(),
        tools: JsonArray? = null,
        forceTool: Boolean = false,
    ): Request {
        val body = buildJsonObject {
            put("model", s.model)
            put("messages", buildJsonArray {
                messages.forEachIndexed { i, turn ->
                    add(buildJsonObject {
                        put("role", turn.role)
                        if (i == messages.lastIndex && images.isNotEmpty()) {
                            // OpenAI-style multimodal content: text plus data-URL images.
                            put("content", buildJsonArray {
                                add(buildJsonObject { put("type", "text"); put("text", turn.content) })
                                images.forEach { url ->
                                    add(buildJsonObject {
                                        put("type", "image_url")
                                        put("image_url", buildJsonObject { put("url", url) })
                                    })
                                }
                            })
                        } else {
                            put("content", turn.content)
                        }
                    })
                }
            })
            put("temperature", s.temperature)
            put("max_tokens", effectiveMaxTokens(s))
            if (stream) put("stream", true)
            if (tools != null) {
                put("tools", tools)
                put("tool_choice", if (forceTool) "required" else "auto")
            }
        }
        return Request.Builder()
            .url(completionsUrl(s.baseUrl))
            .header("Content-Type", "application/json")
            .apply { if (s.apiKey.isNotBlank()) header("Authorization", "Bearer ${s.apiKey}") }
            .header("HTTP-Referer", "https://github.com/farzanshibu/meowclaw")
            .header("X-Title", "MeowClaw")
            .post(AppJson.encodeToString(JsonObject.serializer(), body).toRequestBody(jsonType))
            .build()
    }

    private fun withSystem(s: AppSettings, system: String, turns: List<ChatTurn>): List<ChatTurn> =
        if (s.useSystemPrompt) listOf(ChatTurn("system", system)) + turns else turns

    /** Streams visible text (think blocks removed); returns the full cleaned reply. */
    suspend fun streamChat(
        system: String,
        turns: List<ChatTurn>,
        tools: JsonArray? = null,
        onDelta: (String) -> Unit,
    ): LlmResponse {
        val s = settings()
        requireConfigured(s)
        val call = http.newCall(request(s, withSystem(s, system, turns), stream = true, tools = tools))
        return interruptible(Dispatchers.IO, call::cancel) {
            call.execute().use { response ->
                if (!response.isSuccessful) throw failure(s, response.code, response.body.string(), tools != null)
                val raw = StringBuilder()
                // Tool calls stream as a name followed by argument fragments.
                var toolName: String? = null
                val toolArgs = StringBuilder()
                val filter = ThinkFilter()
                val source = response.body.source()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    val data = line.trim().removePrefix("data:").trim()
                    if (!line.trim().startsWith("data:") || data.isEmpty()) continue
                    if (data == "[DONE]") break
                    val choice = runCatching {
                        AppJson.parseToJsonElement(data).jsonObject["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                    }.getOrNull() ?: continue
                    val delta = choice["delta"]?.let { runCatching { it.jsonObject }.getOrNull() }
                    val content = delta?.get("content")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                    if (!content.isNullOrEmpty()) {
                        raw.append(content)
                        filter.feed(content).takeIf { it.isNotEmpty() }?.let(onDelta)
                    }
                    (delta?.get("tool_calls") as? JsonArray)?.firstOrNull()?.let { runCatching { it.jsonObject["function"]?.jsonObject }.getOrNull() }?.let { fn ->
                        fn["name"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.takeIf { it.isNotEmpty() }?.let { toolName = it }
                        fn["arguments"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.let(toolArgs::append)
                    }
                    if (choice["finish_reason"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } != null) break
                }
                toolName?.let { name -> return@use LlmResponse("", 0, LocalCall(name, parseArguments(toolArgs.toString()))) }
                val cleaned = stripThink(raw.toString())
                if (cleaned.isEmpty()) {
                    throw LlmException("The model finished without a visible answer. Increase Max Tokens or try another model.")
                }
                LlmResponse(cleaned, 0)
            }
        }
    }

    /** Single-shot completion for the task loop, with retry and back-off. */
    suspend fun complete(
        system: String,
        prompt: String,
        images: List<String> = emptyList(),
        tools: JsonArray? = null,
        maxRetries: Int = 4,
    ): LlmResponse {
        val s = settings()
        requireConfigured(s)
        var attempt = 0
        while (true) {
            attempt++
            try {
                val call = http.newCall(
                    request(s, withSystem(s, system, listOf(ChatTurn("user", prompt))), stream = false, images = images, tools = tools, forceTool = tools != null),
                )
                return interruptible(Dispatchers.IO, call::cancel) {
                    call.execute().use { response ->
                        val body = response.body.string()
                        if (!response.isSuccessful) throw failure(s, response.code, body, tools != null)
                        val json = AppJson.parseToJsonElement(body).jsonObject
                        val message = json["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                            ?: throw LlmException("Unexpected API response format: ${body.take(300)}")
                        val tokens = json["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.intOrNull ?: 0
                        val fn = (message["tool_calls"] as? JsonArray)?.firstOrNull()
                            ?.let { runCatching { it.jsonObject["function"]?.jsonObject }.getOrNull() }
                        val name = fn?.get("name")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                        if (fn != null && !name.isNullOrEmpty()) {
                            val args = fn["arguments"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty()
                            return@use LlmResponse("", tokens, LocalCall(name, parseArguments(args)))
                        }
                        val content = message["content"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
                            ?: throw LlmException("Unexpected API response format: ${body.take(300)}")
                        val cleaned = stripThink(content)
                        if (cleaned.isEmpty()) throw LlmException("API returned an empty response.")
                        LlmResponse(cleaned, tokens)
                    }
                }
            } catch (e: ToolsUnsupportedException) {
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (attempt > maxRetries) throw if (e is LlmException) e else LlmException("Network error: ${e.message}")
                Log.w(TAG, "LLM call failed ($e), retry $attempt/$maxRetries")
                delay(3_000L * attempt)
            }
        }
    }

    suspend fun fetchModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = baseUrl.trim().removeSuffix("/").removeSuffix("/chat/completions")
            val request = Request.Builder().url("$clean/models")
                .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer ${apiKey.trim()}") }
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val json = AppJson.parseToJsonElement(response.body.string())
                val list: JsonArray = when {
                    json is JsonObject && json["data"] is JsonArray -> json["data"]!!.jsonArray
                    json is JsonArray -> json
                    else -> return@use emptyList()
                }
                val ids = list.mapNotNull { runCatching { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull() }
                if (isNvidiaBaseUrl(clean)) NVIDIA_FREE_CHAT_MODELS.filter { it in ids } else ids.sorted()
            }
        }.getOrDefault(emptyList())
    }

    /** A rejected request; when it carried tools and the error is about them, marks the endpoint tool-less. */
    private fun failure(s: AppSettings, code: Int, body: String, sentTools: Boolean): LlmException {
        val lower = body.lowercase()
        if (sentTools && code in setOf(400, 404, 405, 415, 422, 501) &&
            ("tool" in lower || "function" in lower || "not support" in lower || "unsupported" in lower)
        ) {
            noTools += toolsKey(s)
            return ToolsUnsupportedException(apiError(code, body))
        }
        return LlmException(apiError(code, body))
    }

    /** Tool arguments arrive as a JSON string; a malformed one becomes empty arguments. */
    private fun parseArguments(raw: String): JsonObject =
        runCatching { AppJson.parseToJsonElement(extractJson(raw.ifBlank { "{}" })).jsonObject }.getOrDefault(JsonObject(emptyMap()))

    private fun requireConfigured(s: AppSettings) {
        if (!s.isApiConfigured) throw LlmException("No LLM is configured. Add an API key or a local server in Settings.")
    }

    private fun apiError(code: Int, body: String): String {
        val detail = runCatching {
            val error = AppJson.parseToJsonElement(body).jsonObject["error"]
            when {
                error is JsonObject -> error["message"]?.jsonPrimitive?.contentOrNull
                error != null -> error.jsonPrimitive.contentOrNull
                else -> null
            }
        }.getOrNull() ?: body.take(500)
        return "API error ($code): $detail"
    }

    /** Hides `<think>…</think>` spans from a token stream. */
    private class ThinkFilter {
        private var inThink = false
        private val pending = StringBuilder()

        fun feed(chunk: String): String {
            pending.append(chunk)
            val out = StringBuilder()
            while (pending.isNotEmpty()) {
                val tag = if (inThink) "</think>" else "<think>"
                val index = pending.indexOf(tag)
                if (index >= 0) {
                    if (!inThink) out.append(pending, 0, index)
                    pending.delete(0, index + tag.length)
                    inThink = !inThink
                    continue
                }
                // Keep a possible partial tag for the next chunk.
                val keep = (1 until tag.length).lastOrNull { pending.endsWith(tag.substring(0, it)) } ?: 0
                if (!inThink) out.append(pending, 0, pending.length - keep)
                pending.delete(0, pending.length - keep)
                break
            }
            return out.toString()
        }
    }

    companion object {
        private const val TAG = "LlmClient"
        const val NVIDIA_BASE_URL = "https://integrate.api.nvidia.com/v1"
        const val NVIDIA_DEFAULT_MODEL = "z-ai/glm-5.2"
        val NVIDIA_FREE_CHAT_MODELS = listOf(
            "z-ai/glm-5.2",
            "nvidia/nemotron-3-nano-30b-a3b",
            "nvidia/nemotron-3-super-120b-a12b",
            "nvidia/nemotron-3-ultra-550b-a55b",
            "nvidia/nvidia-nemotron-nano-9b-v2",
            "openai/gpt-oss-20b",
            "openai/gpt-oss-120b",
            "meta/llama-3.3-70b-instruct",
            "meta/llama-3.2-3b-instruct",
            "meta/llama-3.1-8b-instruct",
            "meta/llama-3.1-70b-instruct",
            "mistralai/mistral-nemotron",
            "deepseek-ai/deepseek-v4-flash",
            "deepseek-ai/deepseek-v4-pro",
        )

        fun isNvidiaBaseUrl(url: String) =
            runCatching { URI(url.trim()).host?.lowercase() == "integrate.api.nvidia.com" }.getOrDefault(false)

        fun completionsUrl(baseUrl: String): String {
            val trimmed = baseUrl.trim()
            return when {
                trimmed.endsWith("/chat/completions") -> trimmed
                trimmed.endsWith("/") -> "${trimmed}chat/completions"
                else -> "$trimmed/chat/completions"
            }
        }

        fun stripThink(text: String) = text.replace(Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL), "").trim()

        /** Extracts the JSON object from a reply that may be fenced or surrounded by prose. */
        fun extractJson(text: String): String {
            Regex("```(?:json)?\\s*(\\{[\\s\\S]*?\\})\\s*```").find(text)?.let { return it.groupValues[1] }
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            return if (start != -1 && end > start) text.substring(start, end + 1) else text.trim()
        }

        /** Returns the action if [response] is an action JSON, or null for plain conversation. */
        fun parseAction(response: String): AgentAction? {
            var json = response.trim()
            if (json.startsWith("```")) {
                json = json.lines().drop(1).let { lines ->
                    if (lines.lastOrNull()?.trim() == "```") lines.dropLast(1) else lines
                }.joinToString("\n").trim()
            }
            if (!json.startsWith("{") || !json.contains("\"action\"")) return null
            // Small local models often drop the final brace(s).
            for (suffix in listOf("", "}", "}}")) {
                val parsed = runCatching { AppJson.parseToJsonElement(json + suffix).jsonObject }.getOrNull() ?: continue
                if ("action" !in parsed) return null
                return runCatching { AppJson.decodeFromJsonElement<AgentAction>(parsed) }.getOrNull()
            }
            return null
        }
    }
}
