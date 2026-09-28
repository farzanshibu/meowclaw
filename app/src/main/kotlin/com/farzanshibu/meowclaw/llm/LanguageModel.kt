package com.farzanshibu.meowclaw.llm

import android.util.Log
import com.farzanshibu.meowclaw.data.AppSettings
import com.farzanshibu.meowclaw.data.LlmProvider
import com.farzanshibu.meowclaw.llm.cactus.LocalCall
import com.farzanshibu.meowclaw.llm.cactus.LocalLlm
import com.farzanshibu.meowclaw.llm.cactus.LocalModel
import com.farzanshibu.meowclaw.llm.cactus.LocalModelCatalog
import com.farzanshibu.meowclaw.llm.cactus.LocalModelManager
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** A screenshot handed to vision models: a file for on-device, base64 for APIs. */
class ScreenImage(val file: File) {
    val dataUrl: String by lazy {
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
    }
}

/** Tool schema in OpenAI function format; also what Cactus consumes. */
data class ToolSpec(val name: String, val description: String, val params: Map<String, Pair<String, String>> = emptyMap(), val required: List<String> = emptyList())

fun List<ToolSpec>.toJson(): JsonArray = buildJsonArray {
    forEach { tool ->
        add(buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        tool.params.forEach { (name, spec) ->
                            put(name, buildJsonObject { put("type", spec.first); put("description", spec.second) })
                        }
                    })
                    put("required", buildJsonArray { tool.required.forEach { add(JsonPrimitive(it)) } })
                })
            })
        })
    }
}

/**
 * The agent's "big brain": an OpenAI-compatible API (cloud or LAN server) or
 * an on-device model through Cactus. Callers see one interface either way.
 */
class LanguageModel(
    private val settings: () -> AppSettings,
    private val api: LlmClient,
    private val local: LocalLlm,
    private val models: LocalModelManager,
) {
    val activeLocal: LocalModel?
        get() = settings().takeIf { it.llmProvider == LlmProvider.ON_DEVICE }
            ?.let { LocalModelCatalog.byId(it.localModelId) }
            ?.takeIf { models.isReady(it) }

    val isOnDevice: Boolean get() = settings().llmProvider == LlmProvider.ON_DEVICE

    val isConfigured: Boolean
        get() = if (isOnDevice) activeLocal != null else settings().isApiConfigured

    /** Whether task steps should include a screenshot. */
    val acceptsImages: Boolean
        get() = settings().sendScreenshots && (activeLocal?.vision ?: (!isOnDevice && settings().apiVision))

    val displayName: String
        get() = activeLocal?.name ?: settings().model

    /** Streams prose; when the model calls a tool, returns [callToJson] of that call instead. */
    suspend fun streamChat(
        system: String,
        turns: List<ChatTurn>,
        tools: List<ToolSpec>? = null,
        callToJson: ((LocalCall) -> String)? = null,
        onDelta: (String) -> Unit,
    ): String {
        val model = if (isOnDevice) activeLocal ?: throw notReady() else null
        if (model == null) {
            // Cloud: native tool calls when the endpoint supports them. The system
            // prompt still describes the JSON format, so text replies keep working.
            if (tools != null && callToJson != null && api.supportsTools()) {
                try {
                    val reply = api.streamChat(system, turns, tools.toJson(), onDelta)
                    return reply.call?.let(callToJson) ?: reply.content
                } catch (e: ToolsUnsupportedException) {
                    Log.w(TAG, "Endpoint has no tool calling; using JSON prompts: ${e.message}")
                }
            }
            return api.streamChat(system, turns, null, onDelta).content
        }

        val useTools = model.tools && tools != null && callToJson != null
        val streamed = StringBuilder()
        val result = local.complete(
            model, system, turns,
            tools = if (useTools) tools!!.toJson() else null,
            maxTokens = settings().maxTokens.coerceAtMost(2048),
            temperature = settings().temperature.coerceAtMost(1.0),
            // Tool-call tokens are not prose; show text only once it is known to be an answer.
            onToken = if (useTools) null else { token ->
                streamed.append(token)
                onDelta(token)
            },
        )
        result.calls.firstOrNull()?.let { if (useTools) return callToJson!!(it) }
        val text = LlmClient.stripThink(result.text.ifBlank { streamed.toString() })
            .ifBlank { throw LlmException("${model.name} returned an empty answer.") }
        if (useTools) onDelta(text)
        return text
    }

    /** One step of the task loop; tool calls are converted with [callToJson]. */
    suspend fun complete(
        system: String,
        prompt: String,
        image: ScreenImage? = null,
        tools: List<ToolSpec>? = null,
        callToJson: ((LocalCall) -> String)? = null,
        toolSystem: String? = null,
        cloudTools: List<ToolSpec>? = null,
        cloudToolSystem: String? = null,
    ): LlmResponse {
        val model = if (isOnDevice) activeLocal ?: throw notReady() else null
        if (model == null) {
            val images = listOfNotNull(image?.dataUrl)
            if (cloudTools != null && callToJson != null && api.supportsTools()) {
                try {
                    val reply = api.complete(cloudToolSystem ?: system, prompt, images, tools = cloudTools.toJson())
                    return reply.call?.let { reply.copy(content = callToJson(it)) } ?: reply
                } catch (e: ToolsUnsupportedException) {
                    Log.w(TAG, "Endpoint has no tool calling; using JSON prompts: ${e.message}")
                }
            }
            return api.complete(system, prompt, images)
        }

        val useTools = model.tools && tools != null && callToJson != null
        val result = local.complete(
            model,
            // With native tools the JSON-format instructions only compete with the tool schema.
            if (useTools) toolSystem ?: system else system,
            listOf(ChatTurn("user", prompt)),
            images = listOfNotNull(image?.file?.absolutePath).takeIf { model.vision }.orEmpty(),
            tools = if (useTools) tools!!.toJson() else null,
            maxTokens = 512,
            temperature = 0.2,
            forceTools = useTools,
        )
        val call = result.calls.firstOrNull()
        val content = if (useTools && call != null) callToJson!!(call) else LlmClient.stripThink(result.text)
        if (content.isBlank()) throw LlmException("${model.name} returned an empty answer.")
        return LlmResponse(content, result.totalTokens)
    }

    private val TAG = "LanguageModel"

    private fun notReady() = LlmException("Pick and download an on-device model in Settings.")

    companion object {
        fun actionJson(call: LocalCall, response: String = ""): String = buildJsonObject {
            put("action", call.name)
            put("params", call.arguments)
            put("response", response)
        }.toString()

        fun stepJson(call: LocalCall): String = buildJsonObject {
            put("action", call.name)
            put("params", JsonObject(call.arguments.filterKeys { it != "summary" && it != "reasoning" }))
            val why = (call.arguments["reasoning"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            put("reasoning", why ?: (call.arguments["summary"] as? JsonPrimitive)?.content ?: "")
            put("is_complete", call.name == "done")
        }.toString()
    }
}
