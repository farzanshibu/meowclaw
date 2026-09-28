package com.farzanshibu.meowclaw.agent

import android.util.Log
import com.farzanshibu.meowclaw.AppGraph
import com.farzanshibu.meowclaw.data.AgentAction
import com.farzanshibu.meowclaw.data.AgentActionResult
import com.farzanshibu.meowclaw.data.ReplySource
import com.farzanshibu.meowclaw.llm.ChatTurn
import com.farzanshibu.meowclaw.llm.LanguageModel
import com.farzanshibu.meowclaw.llm.LlmClient
import com.farzanshibu.meowclaw.llm.needle.IntentRouter
import com.farzanshibu.meowclaw.llm.needle.NeedleCall
import com.farzanshibu.meowclaw.llm.needle.RouteDecision
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.CancellationException

enum class Mode { CHAT, AGENT }

sealed interface BrainOutcome {
    data class Reply(val text: String, val source: ReplySource, val latencyMs: Long? = null) : BrainOutcome
    data class Acted(
        val action: AgentAction,
        val response: String,
        val result: AgentActionResult,
        val source: ReplySource,
        val latencyMs: Long? = null,
    ) : BrainOutcome
}

interface BrainListener {
    /** Visible LLM text so far (raw action JSON is hidden by the caller once parsed). */
    fun onStream(text: String) = Unit
    fun onProgress(message: String) = Unit
    /** Called once an action is about to run. */
    fun onAction(action: AgentAction) = Unit
}

/** Rolling LLM context, capped like v1 (last 20 turns). */
class ConversationHistory {
    private val turns = ArrayDeque<ChatTurn>()

    @Synchronized fun add(role: String, content: String) {
        turns.addLast(ChatTurn(role, content))
        while (turns.size > 20) turns.removeFirst()
    }

    @Synchronized fun snapshot(): List<ChatTurn> = turns.toList()
    @Synchronized fun clear() = turns.clear()
}

/**
 * Decides who handles a request. In agent mode Needle runs first on-device;
 * the LLM handles chat, multi-step screen tasks and anything Needle refuses.
 */
class AgentBrain(private val graph: AppGraph) {

    suspend fun handle(text: String, mode: Mode, history: ConversationHistory, listener: BrainListener): BrainOutcome {
        val s = graph.settings.current
        val llmReady = graph.language.isConfigured
        if (mode == Mode.AGENT && s.needleEnabled && graph.needle.isReady) {
            val decision = try {
                graph.router.route(text, s.needleMinConfidence, skipPrecheck = !llmReady)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Needle routing failed", e)
                null
            }
            Log.d(TAG, "Needle route for \"$text\": $decision")
            when (decision) {
                is RouteDecision.Direct -> if (decision.confident || !llmReady) {
                    return runLocal(text, decision, history, listener)
                }
                is RouteDecision.Unhandled -> if (!llmReady) {
                    return BrainOutcome.Reply(OFFLINE_HELP, ReplySource.NEEDLE, decision.result.latencyMs)
                }
                is RouteDecision.Escalate -> if (!llmReady) {
                    val why = if (decision.screenTask) "It needs several steps on screen" else decision.reason
                    return BrainOutcome.Reply(
                        "$why, which needs a language model. Download an on-device model or add an API in Settings.\n\n$OFFLINE_HELP",
                        ReplySource.SYSTEM,
                    )
                }
                null -> Unit
            }
        }

        if (!llmReady) {
            return BrainOutcome.Reply(
                if (mode == Mode.CHAT) "Chat needs a language model. Download an on-device model (Gemma 4, Qwen) or add an API in Settings."
                else "No language model is configured. " + (if (graph.needle.isReady) OFFLINE_HELP else
                    "Download the on-device Needle model or configure an LLM in Settings."),
                ReplySource.SYSTEM,
            )
        }
        return runLlm(text, mode, history, listener)
    }

    private suspend fun runLocal(
        text: String,
        decision: RouteDecision.Direct,
        history: ConversationHistory,
        listener: BrainListener,
    ): BrainOutcome {
        listener.onAction(decision.action)
        val result = graph.actions.execute(decision.action, listener::onProgress)
        history.add("user", text)
        history.add("assistant", "Done on-device: ${decision.action.action} → ${result.details}")
        return BrainOutcome.Acted(
            decision.action, result.details ?: "Done.", result, ReplySource.NEEDLE, decision.result.latencyMs,
        )
    }

    private suspend fun runLlm(text: String, mode: Mode, history: ConversationHistory, listener: BrainListener): BrainOutcome {
        history.add("user", text)
        val started = System.currentTimeMillis()
        val source = if (graph.language.isOnDevice) ReplySource.LOCAL else ReplySource.LLM

        // Clearly multi-step wording goes straight to the screen loop; asking a
        // model to classify it first only adds latency and a chance to misroute.
        if (mode == Mode.AGENT && IntentRouter.screenTaskReason(text) != null) {
            val task = AgentAction("execute_task", buildJsonObject { put("goal", text) }, "")
            history.add("assistant", "Running task: $text")
            return act(task, listener, source, System.currentTimeMillis() - started)
        }

        val system = if (mode == Mode.AGENT) Prompts.AGENT_SYSTEM else Prompts.CHAT_SYSTEM
        val visible = StringBuilder()
        val reply = graph.language.streamChat(
            system, history.snapshot(),
            tools = if (mode == Mode.AGENT) Prompts.AGENT_TOOLS else null,
            callToJson = { LanguageModel.actionJson(it) },
        ) { delta ->
            visible.append(delta)
            // Action replies are JSON; show only what reads like prose.
            val head = visible.trimStart()
            if (mode == Mode.CHAT || (head.isNotEmpty() && head[0] != '{' && head[0] != '`')) {
                listener.onStream(visible.toString())
            }
        }.also { history.add("assistant", it) }
        val latency = System.currentTimeMillis() - started

        val parsed = if (mode == Mode.AGENT) LlmClient.parseAction(reply) else null
        if (parsed == null) {
            listener.onStream(reply)
            return BrainOutcome.Reply(reply, source, latency)
        }
        return act(validated(parsed, text), listener, source, latency)
    }

    /**
     * Small on-device models sometimes pick a plausible but wrong quick action
     * (open_url for "open settings…"). Quick actions must be backed by the
     * user's words, using the same checks as the Needle path; otherwise the
     * request runs as a screen task.
     */
    private fun validated(action: AgentAction, text: String): AgentAction {
        if (!graph.language.isOnDevice || action.action !in IntentRouter.QUICK_ACTIONS) return action
        val checked = IntentRouter.toAction(NeedleCall(action.action, action.params), text)
        return checked?.copy(response = action.response)
            ?: AgentAction("execute_task", buildJsonObject { put("goal", text) }, "")
    }

    private suspend fun act(action: AgentAction, listener: BrainListener, source: ReplySource, latency: Long): BrainOutcome {
        listener.onAction(action)
        val result = graph.actions.execute(action, listener::onProgress)
        val response = when {
            // The model wrote its reply before seeing the data; show the data too.
            result.success && action.action in ActionHandler.INFO_ACTIONS ->
                listOfNotNull(action.response.takeIf { it.isNotBlank() }, result.details).joinToString("\n\n")
            result.success -> action.response.ifBlank { result.details ?: "Done." }
            action.response.isNotBlank() -> "${action.response}\n\n⚠️ ${result.details}"
            else -> "⚠️ ${result.details}"
        }
        return BrainOutcome.Acted(action, response, result, source, latency)
    }

    companion object {
        private const val TAG = "AgentBrain"
        const val OFFLINE_HELP =
            "On-device I can: open apps, call or text contacts, write emails, set alarms and timers, " +
                "change volume or brightness, look up contacts, open websites and read the screen."
    }
}
