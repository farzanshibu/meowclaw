package com.farzanshibu.meowclaw.remote

import android.util.Log
import com.farzanshibu.meowclaw.AppGraph
import com.farzanshibu.meowclaw.agent.BrainListener
import com.farzanshibu.meowclaw.agent.BrainOutcome
import com.farzanshibu.meowclaw.agent.ConversationHistory
import com.farzanshibu.meowclaw.agent.Mode
import com.farzanshibu.meowclaw.data.AppJson
import com.farzanshibu.meowclaw.data.ReplySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Remote control over the Telegram Bot API (long polling). The first chat that
 * messages the bot becomes its owner; other chats are refused, because the bot
 * can operate the phone.
 */
class TelegramBot(private val graph: AppGraph, private val token: String) {
    private val http = OkHttpClient.Builder().readTimeout(45, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()
    private val history = ConversationHistory()
    private val serial = Mutex()
    private var offset = 0L

    suspend fun run() = coroutineScope {
        while (isActive) {
            try {
                val response = call("getUpdates", buildJsonObject {
                    put("offset", offset + 1)
                    put("timeout", 30)
                    put("allowed_updates", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("message")) })
                })
                if (response == null) {
                    // Offline or bad token: back off instead of spinning.
                    delay(5_000)
                    continue
                }
                val updates = response["result"]?.jsonArray.orEmpty()
                for (update in updates) {
                    val obj = update.jsonObject
                    offset = obj["update_id"]?.jsonPrimitive?.longOrNull ?: offset
                    val message = obj["message"]?.jsonObject ?: continue
                    val text = message["text"]?.jsonPrimitive?.contentOrNull ?: continue
                    val chatId = message["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull ?: continue
                    launch { handle(chatId, text) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Polling error: ${e.message}")
                delay(5_000)
            }
            delay(300)
        }
    }

    private suspend fun handle(chatId: String, text: String) {
        val owner = graph.settings.current.telegramOwnerChatId
        if (owner.isEmpty()) {
            graph.settings.update { it.copy(telegramOwnerChatId = chatId) }
            send(chatId, "🔐 This chat is now paired with MeowClaw. Only this chat can control the phone. Reset pairing in Settings.")
        } else if (owner != chatId) {
            send(chatId, "⛔ This bot is paired with another chat.")
            return
        }

        if (text.trim() == "/start") {
            send(chatId, "👋 Send me a command, e.g. \"set volume to 40\" or \"open YouTube and search for cats\".")
            return
        }

        send(chatId, "🤖 Received: \"$text\". Working on it...")
        serial.withLock {
            try {
                val outcome = graph.brain.handle(text, Mode.AGENT, history, object : BrainListener {
                    override fun onProgress(message: String) {
                        graph.scope.launch { send(chatId, "⏳ $message") }
                    }
                })
                when (outcome) {
                    is BrainOutcome.Reply -> send(chatId, "💬 ${outcome.text}")
                    is BrainOutcome.Acted -> {
                        val badge = if (outcome.source == ReplySource.NEEDLE) " (on-device)" else ""
                        send(chatId, "${if (outcome.result.success) "✅" else "⚠️"} ${outcome.result.details ?: outcome.response}$badge")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                send(chatId, "❌ Error: ${e.message}")
            }
        }
    }

    private suspend fun send(chatId: String, text: String) {
        call("sendMessage", buildJsonObject {
            put("chat_id", chatId)
            put("text", text.take(4000))
        })
    }

    private suspend fun call(method: String, body: JsonObject): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.telegram.org/bot$token/$method")
                .post(AppJson.encodeToString(JsonObject.serializer(), body).toRequestBody(json))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null
                else AppJson.parseToJsonElement(response.body.string()).jsonObject
                    .takeIf { it["ok"]?.jsonPrimitive?.contentOrNull == "true" }
            }
        }.onFailure { Log.w(TAG, "$method failed: ${it.message}") }.getOrNull()
    }

    companion object {
        private const val TAG = "TelegramBot"
    }
}
