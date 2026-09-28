package com.farzanshibu.meowclaw.agent

import android.util.Log
import com.farzanshibu.meowclaw.AppGraph
import com.farzanshibu.meowclaw.data.ActionStep
import com.farzanshibu.meowclaw.data.AppJson
import com.farzanshibu.meowclaw.data.SavedSkill
import com.farzanshibu.meowclaw.llm.LanguageModel
import com.farzanshibu.meowclaw.llm.ScreenImage
import com.farzanshibu.meowclaw.service.LiveTask
import com.farzanshibu.meowclaw.service.LiveTask.Phase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Multi-step UI automation: read the screen, ask the LLM for the next step,
 * act with the virtual mouse/keyboard, repeat until done.
 */
class TaskExecutor(private val graph: AppGraph) {

    suspend fun execute(goal: String, onProgress: (String) -> Unit): String {
        if (!graph.input.isAccessibilityReady) {
            return "Accessibility service is not enabled. Go to Settings → Accessibility → MeowClaw Screen Control and enable it."
        }
        val results = mutableListOf("Starting task: $goal")
        onProgress("Starting task: $goal")
        graph.input.beginSession()
        graph.input.label("Working…")
        live { it.copy(phase = Phase.STARTING, maxSteps = graph.settings.current.effectiveMaxSteps) }
        var totalTokens = 0
        var step = 0
        try {
            // Keep the agent from reading or touching its own chat, including skill replays.
            if (graph.screen.currentPackage() == graph.context.packageName) {
                onProgress("Moving to background...")
                graph.input.global("home")
                delay(1_200)
            }
            graph.skills.find(goal)?.takeIf(SavedSkill::isReliable)?.let { skill ->
                onProgress("Found saved skill! Replaying ${skill.steps.size} steps...")
                live { it.copy(phase = Phase.ACTING, action = "Replaying", detail = "saved skill") }
                if (replay(skill, results, onProgress)) {
                    results += "Task complete via skill memory."
                    return finish(goal, "Success", 0, skill.steps.size, results, "Task Completed",
                        "Agent finished its goal using memory.", "Task Complete! (Memory)", "Done.")
                }
                onProgress("Replay failed, falling back to AI...")
                graph.skills.recordFailure(skill.id)
            }

            var lastAction = ""
            var sameActionCount = 0
            var consecutiveFailures = 0
            var lastFailedAction = ""
            val executed = mutableListOf<ActionStep>()

            val shortcut = navigationShortcut(goal)
            if (shortcut != null) {
                results += "Using navigation shortcut: ${shortcut.size} steps"
                onProgress("Using navigation shortcut...")
                for (s in shortcut) {
                    live { it.copy(phase = Phase.ACTING, action = actionLabel(s.action), detail = s.params.str("app_name") ?: s.params.str("text").orEmpty()) }
                    val outcome = graph.steps.run(s.action, s.params)
                    delay(if (s.action == "open_app") 3_000 else 1_500)
                    if (!outcome.success) break
                    executed += s
                    lastAction = s.action
                }
            }

            val fastPath = GoalFastPath(goal, skipFirstOpen = shortcut?.firstOrNull()?.action == "open_app")
            val maxSteps = graph.settings.current.effectiveMaxSteps
            // What the user said mid-task (voice or chat); it steers every later step.
            val updates = mutableListOf<String>()
            while (step < maxSteps) {
                live { it.copy(phase = Phase.WAITING, step = step) }
                delay(settleDelay(lastAction))
                graph.controller.takeSteering().takeIf { it.isNotEmpty() }?.let { new ->
                    updates += new
                    results += "User update: ${new.joinToString("; ")}"
                    onProgress("Got it: ${new.joinToString("; ")}")
                    live { it.copy(detail = "Got it: ${new.last()}") }
                    // A new instruction deserves a fresh attempt.
                    consecutiveFailures = 0
                    lastFailedAction = ""
                }
                val screen = graph.screen.describe(goal, graph.settings.current.useScreenCompression)

                // Clauses the screen clearly satisfies run without a model call,
                // unless the user has since changed what they want.
                val move = if (updates.isEmpty()) fastPath.next(graph.screen.lastNodes) else null
                if (move != null) {
                    step++
                    live {
                        when (move) {
                            is GoalFastPath.Move.Tap -> it.copy(phase = Phase.ACTING, action = "Tap", detail = move.label, step = step)
                            is GoalFastPath.Move.Type -> it.copy(phase = Phase.ACTING, action = "Type", detail = move.text, step = step)
                            GoalFastPath.Move.Submit -> it.copy(phase = Phase.ACTING, action = "Enter", detail = "", step = step)
                        }
                    }
                    val (label, outcome) = when (move) {
                        is GoalFastPath.Move.Tap -> {
                            onProgress("Step $step: Tap \"${move.label}\"")
                            graph.input.label("Click")
                            "tap ${move.label}" to graph.input.clickNode(move.node)
                        }
                        is GoalFastPath.Move.Type -> {
                            onProgress("Step $step: Type \"${move.text}\"")
                            "type ${move.text}" to graph.input.typeText(move.text)
                        }
                        GoalFastPath.Move.Submit -> {
                            onProgress("Step $step: Press enter")
                            "submit" to graph.input.pressEnter()
                        }
                    }
                    results += "Step $step: ${outcome.message} (fast path)"
                    lastAction = when (move) {
                        is GoalFastPath.Move.Type -> "type_text"
                        else -> "click_element"
                    }
                    if (outcome.success) {
                        fastPath.advance(move)
                        executed += when (move) {
                            is GoalFastPath.Move.Tap -> ActionStep("click_text", JsonObject(mapOf("text" to JsonPrimitive(move.label))))
                            is GoalFastPath.Move.Type -> ActionStep("type_text", JsonObject(mapOf("text" to JsonPrimitive(move.text))))
                            GoalFastPath.Move.Submit -> ActionStep("press_enter", JsonObject(emptyMap()))
                        }
                        if (fastPath.isComplete) {
                            results += "Task complete."
                            onProgress("Task complete.")
                            graph.skills.save(goal, executed)
                            return finish(goal, "Success", totalTokens, step, results, "Task Completed",
                                "Finished: $goal", "Task Complete!", "Done — $label.")
                        }
                        continue
                    }
                }

                val failureHint = if (consecutiveFailures >= 3) {
                    "\n\nWARNING: You have failed $consecutiveFailures times in a row with the same approach. " +
                        "You MUST try a completely different action. If open_app failed, try press_home and look for the app icon. " +
                        "If click_text failed, use click_element or click_at. Do NOT repeat the same failed action."
                } else ""
                val previous = if (step > 0) "\nPREVIOUS ACTION RESULT: ${results.last()}\n" else ""
                val steer = if (updates.isEmpty()) "" else
                    "\n\nUSER UPDATES DURING THE TASK (newest last; they override the task where they conflict):\n" +
                        updates.joinToString("\n") { "- $it" }
                val prompt = "TASK: $goal$steer\n\nCURRENT SCREEN TEXT DUMP:\n$screen$previous$failureHint\n" +
                    "Step ${step + 1}/$maxSteps. Look at the text dump and coordinates. What is the next action?"

                graph.input.label("Thinking…")
                live { it.copy(phase = Phase.THINKING, step = step + 1) }
                val image = if (graph.language.acceptsImages) graph.screen.screenshot(graph.context.cacheDir) else null
                val parsed = try {
                    askForStep(prompt, image).also { totalTokens += it.second }.first
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    results += "AI error: ${e.message}"
                    onProgress("Error: ${e.message}")
                    return finish(goal, "Failed", totalTokens, step, results, "Task Error", "AI encountered an error.",
                        "AI Error: ${e.message}", "I could not complete the task because the AI service failed.")
                }
                if (parsed == null) {
                    results += "Step ${step + 1}: could not parse the AI response"
                    return finish(goal, "Failed", totalTokens, step, results, "Task Error", "AI formatting error.",
                        "Agent Error: unreadable response", "I could not understand the AI response. Please try again.")
                }

                val action = parsed["action"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() } ?: "done"
                val params = parsed["params"]?.let { runCatching { it.jsonObject }.getOrNull() } ?: JsonObject(emptyMap())
                val reasoning = parsed["reasoning"]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }.orEmpty()
                val isComplete = parsed["is_complete"]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() } == true
                onProgress("Step ${step + 1}: $reasoning")
                step++

                sameActionCount = if (action == lastAction) sameActionCount + 1 else 1
                val repeatLimit = when (action) {
                    "press_enter", "wait" -> 2
                    "scroll", "swipe", "scroll_at" -> 3
                    else -> Int.MAX_VALUE
                }
                if (sameActionCount > repeatLimit) {
                    val blocked = "Blocked repeated $action action. Use a different action on the visible screen."
                    results += blocked
                    onProgress(blocked)
                    consecutiveFailures = 3
                    lastFailedAction = action
                    lastAction = action
                    continue
                }
                lastAction = action

                if (action == "done") {
                    val summary = reasoning.ifBlank { "Done." }
                    results += "Task complete: $reasoning"
                    onProgress("Task complete: $reasoning")
                    if (updates.isEmpty()) graph.skills.save(goal, executed)
                    return finish(goal, "Success", totalTokens, step, results, "Task Completed",
                        reasoning.ifBlank { "Agent finished its goal." }, "Task completed", summary)
                }

                graph.input.label(actionLabel(action))
                live { it.copy(phase = Phase.ACTING, action = actionLabel(action), detail = reasoning, step = step) }
                val outcome = graph.steps.run(action, params)
                Log.d(TAG, "Step $step $action → ${outcome.message}")

                if (!outcome.success) {
                    consecutiveFailures = if (action == lastFailedAction) consecutiveFailures + 1 else 1
                    lastFailedAction = action
                    if (consecutiveFailures >= 5) {
                        results += "Agent is stuck. Stopping task after $consecutiveFailures consecutive failures."
                        onProgress("Agent stuck — stopping task.")
                        return finish(goal, "Failed", totalTokens, step, results, "Task Stuck",
                            "Agent could not complete the task after repeated failures.", "Agent stuck. Task stopped.",
                            "I could not complete the task. Please try again.")
                    }
                    val recovery = RecoveryEngine.diagnose(action, screen)
                    onProgress("Recovering: ${recovery.description}")
                    live { it.copy(phase = Phase.RECOVERING, detail = recovery.description) }
                    when (recovery.action) {
                        "wait" -> delay(2_000)
                        "scroll" -> graph.input.scrollAt(recovery.direction)
                        "press_back" -> graph.input.global("back")
                        "press_home" -> graph.input.global("home")
                    }
                    results += "Step $step: ${outcome.message}. Recovery: ${recovery.description}"
                    continue
                }

                consecutiveFailures = 0
                lastFailedAction = ""
                executed += ActionStep(action, params)
                results += "Step $step: ${outcome.message} ($reasoning)"
                if (!isComplete && step % 3 == 0) graph.toaster.show("Working... (Step $step)")

                if (isComplete) {
                    results += "Task complete."
                    onProgress("Task complete.")
                    if (updates.isEmpty()) graph.skills.save(goal, executed)
                    return finish(goal, "Success", totalTokens, step, results, "Task Completed", "Agent finished its goal.",
                        "Task Complete!", reasoning.ifBlank { "Done." })
                }
            }

            results += "Reached maximum steps ($maxSteps). Task may be incomplete."
            onProgress("Reached maximum steps.")
            return finish(goal, "Failed", totalTokens, maxSteps, results, "Task Stopped", "Reached maximum steps ($maxSteps).",
                "Reached maximum steps.", "I could not complete the task within the allowed steps.")
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                results += "Task cancelled by user."
                graph.notifier.taskFinished("Task Cancelled", "Task was stopped by the user.")
                graph.taskHistory.log(goal, "Cancelled", totalTokens, step, results)
                graph.toaster.show("Task Cancelled")
            }
            throw e
        } finally {
            graph.input.endSession()
        }
    }

    /** Asks the LLM for one step; retries once when the reply is not JSON. */
    private suspend fun askForStep(prompt: String, image: ScreenImage?): Pair<JsonObject?, Int> {
        var tokens = 0
        val fullPrompt = if (image != null) "$prompt\n(A screenshot of the current screen is attached.)" else prompt
        repeat(2) { attempt ->
            // A malformed tool call is retried once as plain JSON output.
            val useTools = attempt == 0
            val response = graph.language.complete(
                Prompts.TASK_SYSTEM, fullPrompt, image,
                tools = if (useTools) Prompts.TASK_TOOLS else null,
                callToJson = if (useTools) LanguageModel::stepJson else null,
                toolSystem = Prompts.TASK_TOOL_SYSTEM,
                cloudTools = if (useTools) Prompts.TASK_TOOLS_CLOUD else null,
                cloudToolSystem = Prompts.TASK_TOOL_SYSTEM_CLOUD,
            )
            tokens += response.totalTokens
            val parsed = runCatching {
                AppJson.parseToJsonElement(com.farzanshibu.meowclaw.llm.LlmClient.extractJson(response.content)).jsonObject
            }.getOrNull()
            if (parsed != null) return parsed to tokens
            Log.w(TAG, "Unparseable step (attempt ${attempt + 1}): ${response.content.take(300)}")
            delay(2_000)
        }
        return null to tokens
    }

    private suspend fun replay(skill: SavedSkill, results: MutableList<String>, onProgress: (String) -> Unit): Boolean {
        skill.steps.forEachIndexed { i, s ->
            onProgress("Replaying step ${i + 1}/${skill.steps.size}: ${s.action}")
            delay(settleDelay(s.action))
            // Element indexes change between screens; re-read before index clicks.
            if (s.action == "click_element") graph.screen.describe(null, compressed = true)
            val outcome = graph.steps.run(s.action, s.params)
            results += "Memory Replay Step ${i + 1}: ${outcome.message}"
            if (!outcome.success) return false
        }
        return true
    }

    private suspend fun finish(
        goal: String, status: String, tokens: Int, steps: Int, trace: List<String>,
        title: String, body: String, toast: String, reply: String,
    ): String {
        graph.notifier.taskFinished(title, body)
        graph.taskHistory.log(goal, status, tokens, steps, trace)
        graph.toaster.show(toast)
        return reply
    }

    private fun live(transform: (LiveTask) -> LiveTask) = graph.liveStatus.update(transform)

    private fun settleDelay(lastAction: String): Long = when (lastAction) {
        "open_app" -> 3_000
        "type_text", "keyboard_type" -> 2_000
        "click_text", "click_at", "click_element", "double_click", "long_press" -> 1_500
        "scroll", "scroll_at", "swipe", "drag" -> 1_000
        "" -> 300
        else -> 1_200
    }

    private fun actionLabel(action: String) = when (action) {
        "click_element", "click_text", "click_at" -> "Click"
        "double_click" -> "Double click"
        "long_press" -> "Hold"
        "type_text", "keyboard_type" -> "Typing"
        "key_press" -> "Key"
        "scroll", "scroll_at" -> "Scroll"
        "drag", "swipe" -> "Drag"
        "open_app" -> "Opening app"
        else -> action.replace('_', ' ')
    }

    /** Predefined first steps for common goals, executed without the LLM. */
    private fun navigationShortcut(goal: String): List<ActionStep>? {
        val lower = goal.lowercase()
        fun open(app: String) = ActionStep("open_app", JsonObject(mapOf("app_name" to JsonPrimitive(app))))
        fun click(text: String) = ActionStep("click_text", JsonObject(mapOf("text" to JsonPrimitive(text))))
        when {
            "dark mode" in lower || "dark theme" in lower -> return listOf(open("Settings"), click("Display"))
            "wifi" in lower || "wi-fi" in lower -> return listOf(open("Settings"), click("Network & internet"))
            "bluetooth" in lower -> return listOf(open("Settings"), click("Connected devices"))
        }
        val patterns = linkedMapOf(
            "Settings" to listOf("settings", "brightness", "display", "notification"),
            "Play Store" to listOf("play store", "playstore", "download", "install app", "google play"),
            "YouTube" to listOf("youtube"),
            "WhatsApp" to listOf("whatsapp"),
            "Chrome" to listOf("chrome", "browse", "search google"),
            "Camera" to listOf("camera", "take a photo", "take photo", "take a picture"),
            "Gallery" to listOf("gallery", "photos"),
            "Messages" to listOf("message", "sms", "text to"),
            "Phone" to listOf("call", "dial"),
            "Gmail" to listOf("gmail", "email"),
            "Maps" to listOf("maps", "navigate to", "directions"),
            "Clock" to listOf("alarm", "timer", "stopwatch"),
            "Calculator" to listOf("calculator", "calculate", "calc"),
        )
        patterns.forEach { (app, keywords) -> if (keywords.any { it in lower }) return listOf(open(app)) }
        Regex("^open\\s+([a-zA-Z0-9]+)").find(lower)?.let { m ->
            return listOf(open(m.groupValues[1].replaceFirstChar(Char::uppercase)))
        }
        return null
    }

    companion object {
        private const val TAG = "TaskExecutor"
    }
}
