package com.farzanshibu.meowclaw.agent

import com.farzanshibu.meowclaw.AppGraph
import com.farzanshibu.meowclaw.data.AgentAction
import com.farzanshibu.meowclaw.data.ChatMessage
import com.farzanshibu.meowclaw.data.ChatSession
import com.farzanshibu.meowclaw.data.ReplySource
import com.farzanshibu.meowclaw.data.nowIso
import com.farzanshibu.meowclaw.service.AgentService
import com.farzanshibu.meowclaw.service.LiveTask
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue

data class ChatUiState(
    val sessionId: String = System.currentTimeMillis().toString(),
    val title: String = "",
    val messages: List<ChatMessage> = emptyList(),
    val mode: Mode = Mode.AGENT,
    val busy: Boolean = false,
)

/**
 * Owns the visible conversation. Lives in the application scope, so a task
 * keeps running (and its result lands in the chat) while the UI is in the
 * background.
 */
class AgentController(private val graph: AppGraph) {
    private val state = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = state.asStateFlow()

    private val history = ConversationHistory()
    private var job: Job? = null
    /** Messages the user sent while a request was running, oldest first. */
    private val steering = ConcurrentLinkedQueue<String>()

    val isBusy: Boolean get() = state.value.busy

    fun setMode(mode: Mode) = state.update { it.copy(mode = mode) }

    /**
     * Starts a request, or steers the running one: while busy, "stop" cancels
     * and anything else is handed to the task loop at its next step.
     */
    fun send(text: String) = send(text, echo = true)

    private fun send(text: String, echo: Boolean) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        if (state.value.busy) {
            steer(trimmed)
            return
        }
        val mode = state.value.mode
        if (echo) append(ChatMessage("user", trimmed))
        steering.clear()
        graph.liveStatus.start(trimmed)
        state.update { it.copy(busy = true) }
        AgentService.sync(graph.context)

        job = graph.scope.launch {
            persist()
            // Placeholder that streaming text fills in.
            append(ChatMessage("assistant", ""))
            val placeholderIndex = state.value.messages.lastIndex
            var placeholderLive = true
            fun dropPlaceholder() {
                if (!placeholderLive) return
                placeholderLive = false
                state.update { s ->
                    val list = s.messages.toMutableList()
                    if (placeholderIndex in list.indices && list[placeholderIndex].content.isEmpty()) list.removeAt(placeholderIndex)
                    s.copy(messages = list)
                }
            }

            val listener = object : BrainListener {
                override fun onStream(text: String) {
                    if (!placeholderLive) return
                    state.update { s ->
                        val list = s.messages.toMutableList()
                        if (placeholderIndex in list.indices) list[placeholderIndex] = list[placeholderIndex].copy(content = text)
                        s.copy(messages = list)
                    }
                }

                override fun onAction(action: AgentAction) {
                    if (action.action != "execute_task") graph.liveStatus.update {
                        it.copy(phase = LiveTask.Phase.ACTING, action = action.action.replace('_', ' ').replaceFirstChar(Char::uppercase))
                    }
                    // The chat shows the outcome, not the raw action JSON.
                    state.update { s ->
                        val list = s.messages.toMutableList()
                        if (placeholderLive && placeholderIndex in list.indices) list.removeAt(placeholderIndex)
                        s.copy(messages = list)
                    }
                    placeholderLive = false
                }

                override fun onProgress(message: String) {
                    append(ChatMessage("assistant", "⏳ $message", isProgress = true))
                }
            }

            try {
                when (val outcome = graph.brain.handle(trimmed, mode, history, listener)) {
                    is BrainOutcome.Reply -> {
                        if (placeholderLive && placeholderIndex in state.value.messages.indices) {
                            replaceAt(placeholderIndex, ChatMessage("assistant", outcome.text, source = outcome.source, latencyMs = outcome.latencyMs))
                            placeholderLive = false
                        } else {
                            dropPlaceholder()
                            append(ChatMessage("assistant", outcome.text, source = outcome.source, latencyMs = outcome.latencyMs))
                        }
                        if (outcome.source == ReplySource.LLM || outcome.source == ReplySource.LOCAL) graph.speaker.speak(outcome.text)
                    }
                    is BrainOutcome.Acted -> {
                        dropPlaceholder()
                        append(
                            ChatMessage(
                                "assistant", outcome.response, actionResult = outcome.result,
                                source = outcome.source, latencyMs = outcome.latencyMs,
                            ),
                        )
                        if (outcome.action.action != "execute_task") {
                            graph.notifier.taskFinished(
                                if (outcome.result.success) "Task Completed" else "Task Failed",
                                outcome.result.details ?: "Agent finished its goal.",
                            )
                        }
                    }
                }
            } catch (e: CancellationException) {
                dropPlaceholder()
                append(ChatMessage("assistant", "Task cancelled.", source = ReplySource.SYSTEM))
            } catch (e: Exception) {
                dropPlaceholder()
                append(ChatMessage("assistant", "Error: ${e.message}", source = ReplySource.SYSTEM))
            } finally {
                graph.liveStatus.clear()
                state.update { it.copy(busy = false) }
                // A cancelled coroutine can't suspend; save the chat regardless.
                withContext(NonCancellable) { persist() }
                // Steering that arrived after the task stopped listening runs as the next request.
                val leftover = takeSteering()
                if (leftover.isNotEmpty() && isActive) send(leftover.joinToString("\n"), echo = false)
                else AgentService.sync(graph.context)
            }
        }
    }

    private fun steer(text: String) {
        append(ChatMessage("user", text))
        if (STOP_WORDS.matches(text)) {
            cancel()
            return
        }
        steering += text
        append(ChatMessage("assistant", "⏳ Noted — applying at the next step.", isProgress = true))
    }

    /** Drains steering messages; the task loop calls this before every step. */
    fun takeSteering(): List<String> = generateSequence { steering.poll() }.toList()

    fun cancel() {
        steering.clear()
        graph.speaker.stop()
        job?.cancel()
    }

    fun newChat() {
        if (state.value.busy) return
        history.clear()
        state.update { ChatUiState(mode = it.mode) }
    }

    fun load(session: ChatSession) {
        if (state.value.busy) return
        history.clear()
        session.messages.filter { it.actionResult == null && !it.isProgress && it.content.isNotBlank() }
            .forEach { history.add(it.role, it.content) }
        state.update { it.copy(sessionId = session.id, title = session.title, messages = session.messages) }
    }

    suspend fun delete(sessionId: String) {
        graph.chatHistory.delete(sessionId)
        if (state.value.sessionId == sessionId) newChat()
    }

    companion object {
        private val STOP_WORDS = Regex(
            "^(please )?(stop|cancel|abort|halt|quit|never ?mind)( (it|now|the task|task|that|everything))?( please)?[.!]*$",
            RegexOption.IGNORE_CASE,
        )
    }

    private fun append(message: ChatMessage) = state.update { it.copy(messages = it.messages + message) }

    private fun replaceAt(index: Int, message: ChatMessage) = state.update { s ->
        val list = s.messages.toMutableList()
        list[index] = message
        s.copy(messages = list)
    }

    private suspend fun persist() {
        val s = state.value
        val messages = s.messages.filter { it.content.isNotEmpty() }
        if (messages.isEmpty()) return
        val title = s.title.ifEmpty {
            val first = messages.firstOrNull { it.isUser }?.content ?: "New Chat"
            if (first.length > 28) first.take(25) + "..." else first
        }
        state.update { it.copy(title = title) }
        graph.chatHistory.save(ChatSession(s.sessionId, title, nowIso(), messages))
    }
}
