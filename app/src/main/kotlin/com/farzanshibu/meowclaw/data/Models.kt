package com.farzanshibu.meowclaw.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Instant

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
    encodeDefaults = true
}

fun nowIso(): String = Instant.now().toString()

/** Where an assistant reply came from, shown as a small badge in the chat. */
enum class ReplySource { NEEDLE, LLM, LOCAL, SYSTEM }

@Serializable
data class AgentActionResult(
    val actionType: String,
    val success: Boolean,
    val details: String? = null,
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String,
    val timestamp: String = nowIso(),
    val actionResult: AgentActionResult? = null,
    val source: ReplySource? = null,
    val latencyMs: Long? = null,
    val isProgress: Boolean = false,
) {
    val isUser: Boolean get() = role == "user"
}

@Serializable
data class ChatSession(
    val id: String,
    val title: String,
    val timestamp: String,
    val messages: List<ChatMessage>,
)

/** An action requested by the LLM or the on-device router. */
@Serializable
data class AgentAction(
    val action: String = "general_query",
    val params: JsonObject = JsonObject(emptyMap()),
    val response: String = "",
)

@Serializable
data class ActionStep(
    val action: String = "",
    val params: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class SavedSkill(
    val id: String,
    val task: String,
    @SerialName("task_keywords") val taskKeywords: List<String> = emptyList(),
    @SerialName("success_count") val successCount: Int = 0,
    @SerialName("fail_count") val failCount: Int = 0,
    @SerialName("last_used") val lastUsed: String = nowIso(),
    val steps: List<ActionStep> = emptyList(),
) {
    val isReliable: Boolean
        get() = successCount >= 1 && failCount.toDouble() / (successCount + failCount) < 0.3
}

@Serializable
data class TaskRecord(
    val goal: String,
    val status: String,
    @SerialName("total_tokens") val totalTokens: Int = 0,
    @SerialName("steps_taken") val stepsTaken: Int = 0,
    val trace: List<String> = emptyList(),
    val timestamp: String = nowIso(),
)
