package com.farzanshibu.meowclaw.agent

import com.farzanshibu.meowclaw.AppGraph
import com.farzanshibu.meowclaw.data.AgentAction
import com.farzanshibu.meowclaw.data.AgentActionResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

internal fun JsonObject.str(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.takeIf { it.isNotEmpty() }

internal fun JsonObject.num(key: String): Double? =
    this[key]?.let { runCatching { it.jsonPrimitive.doubleOrNull ?: it.jsonPrimitive.contentOrNull?.toDoubleOrNull() }.getOrNull() }

internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
        ?: str(key)?.split('+', ',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: emptyList()

data class StepOutcome(val success: Boolean, val message: String)

/** Executes one UI step (mouse, keyboard or system action) of a multi-step task. */
class StepExecutor(private val graph: AppGraph) {
    private val input get() = graph.input

    suspend fun run(action: String, params: JsonObject): StepOutcome {
        fun xy(xKey: String = "x", yKey: String = "y"): Pair<Float, Float>? {
            val x = params.num(xKey) ?: return null
            val y = params.num(yKey) ?: return null
            return x.toFloat() to y.toFloat()
        }
        return when (action) {
            "click_element" -> {
                val index = params.num("index")?.toInt()
                val node = index?.let { graph.screen.node(it) }
                if (node == null) StepOutcome(false, "No element with index $index on the last screen")
                else input.clickNode(node).let { StepOutcome(it.success, it.message) }
            }
            "click_text" -> input.clickText(params.str("text").orEmpty()).toOutcome()
            "click_at" -> xy()?.let { (x, y) -> input.tap(x, y).toOutcome() } ?: bad("click_at needs x and y")
            "double_click" -> xy()?.let { (x, y) -> input.doubleTap(x, y).toOutcome() } ?: bad("double_click needs x and y")
            "long_press", "right_click" -> xy()?.let { (x, y) ->
                input.longPress(x, y, (params.num("duration_ms") ?: 800.0).toLong()).toOutcome()
            } ?: bad("long_press needs x and y")
            "mouse_move" -> xy()?.let { (x, y) -> input.move(x, y).toOutcome() } ?: bad("mouse_move needs x and y")
            "drag", "swipe" -> {
                val (w, h) = input.screenSize()
                val sx = params.num("startX")?.toFloat() ?: (w / 2f)
                val sy = params.num("startY")?.toFloat() ?: (h * 0.8f)
                val ex = params.num("endX")?.toFloat() ?: (w / 2f)
                val ey = params.num("endY")?.toFloat() ?: (h * 0.25f)
                val duration = params.num("duration_ms")?.toLong() ?: if (action == "drag") 600 else 300
                input.drag(sx, sy, ex, ey, duration, hold = action == "drag").toOutcome()
            }
            "scroll" -> input.scroll(params.str("direction") ?: "down").toOutcome()
            "scroll_at" -> input.scrollAt(
                params.str("direction") ?: "down",
                params.num("x")?.toFloat(), params.num("y")?.toFloat(),
            ).toOutcome()
            "type_text" -> input.typeText(params.str("text").orEmpty(), params.str("field_hint")).toOutcome()
            "keyboard_type" -> input.keyboardType(params.str("text").orEmpty()).toOutcome()
            "key_press", "hotkey" -> input.key(params.str("key").orEmpty(), params.strings("modifiers")).toOutcome()
            "press_enter" -> input.pressEnter().toOutcome()
            "press_back" -> input.global("back").toOutcome()
            "press_home" -> input.global("home").toOutcome()
            "open_recents" -> input.global("recents").toOutcome()
            "open_notifications" -> input.global("notifications").toOutcome()
            "open_quick_settings" -> input.global("quick_settings").toOutcome()
            "lock_screen" -> input.global("lock_screen").toOutcome()
            "take_screenshot" -> input.global("screenshot").toOutcome()
            "open_app" -> graph.apps.openApp(params.str("app_name").orEmpty()).let { StepOutcome(it.startsWith("Opened"), it) }
            "wait" -> {
                delay(1_000)
                StepOutcome(true, "Waited")
            }
            "wait_for" -> waitFor(params.str("text").orEmpty(), (params.num("timeout_seconds") ?: 8.0).coerceIn(1.0, 30.0))
            "read_notifications" -> info(graph.notifications.read(params.str("app")))
            "notification_action" -> params.num("index")?.let {
                info(graph.notifications.act(it.toInt(), params.str("action"), params.str("reply")))
            } ?: bad("notification_action needs the notification index")
            "dismiss_notification" -> info(graph.notifications.dismiss(params.num("index")?.toInt()))
            "get_media_sessions" -> info(graph.notifications.mediaSessions())
            "media_control" -> params.str("command").orEmpty().let { cmd ->
                info(graph.notifications.control(cmd) ?: graph.system.media(cmd))
            }
            "get_device_status" -> info(graph.system.status())
            "adjust_volume" -> info(graph.system.adjustVolume(params.str("direction").orEmpty()))
            "lookup_app" -> info(graph.apps.lookup(params.str("query")))
            "open_deeplink" -> info(graph.apps.openDeepLink(params.str("uri") ?: params.str("url").orEmpty(), params.str("package")))
            "web_search" -> info(graph.apps.webSearch(params.str("query").orEmpty()))
            "done" -> StepOutcome(true, "Done step reached")
            else -> StepOutcome(false, "Unknown action: $action")
        }
    }

    private fun com.farzanshibu.meowclaw.input.InputResult.toOutcome() = StepOutcome(success, message)
    private fun bad(message: String) = StepOutcome(false, message)
    private fun info(message: String) = StepOutcome(!message.looksFailed(), message)

    /** Polls the screen until an element's text or description contains [text]. */
    private suspend fun waitFor(text: String, timeoutSeconds: Double): StepOutcome {
        if (text.isBlank()) return bad("wait_for needs text to look for")
        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000).toLong()
        while (true) {
            graph.screen.describe(null, compressed = true)
            val hit = graph.screen.lastNodes.any {
                it.text.contains(text, ignoreCase = true) || it.contentDescription.contains(text, ignoreCase = true)
            }
            if (hit) return StepOutcome(true, "\"$text\" is on screen")
            if (System.currentTimeMillis() >= deadline) return StepOutcome(false, "\"$text\" did not appear within ${timeoutSeconds.toInt()}s")
            delay(700)
        }
    }
}

/** Handler messages that report a failure; "No notifications." is an answer, not a failure. */
internal fun String.looksFailed(): Boolean =
    startsWith("Error") || startsWith("Could not") || startsWith("Cannot") || startsWith("Allow ") ||
        (startsWith("No ") && !startsWith("No notifications") && !startsWith("No installed app"))

/** Executes a top-level action from the LLM or the on-device router. */
class ActionHandler(private val graph: AppGraph) {
    companion object {
        /** Top-level actions implemented once in [StepExecutor]. */
        val STEP_BACKED = setOf(
            "media_control", "read_notifications", "notification_action", "dismiss_notification", "get_media_sessions",
            "get_device_status", "adjust_volume", "lookup_app", "open_deeplink", "web_search",
        )

        /** Actions whose result is the answer, so it is shown in full. */
        val INFO_ACTIONS = setOf("read_screen", "read_notifications", "get_media_sessions", "get_device_status", "lookup_app", "search_contact")
    }

    suspend fun execute(action: AgentAction, onProgress: (String) -> Unit): AgentActionResult {
        val p = action.params
        return try {
            val details: String = when (action.action) {
                "open_app" -> graph.apps.openApp(p.str("app_name").orEmpty())
                "launch_package" -> graph.apps.openPackage(p.str("package_name").orEmpty())
                "make_call" -> graph.communication.makeCall(p.str("contact_name"), p.str("phone_number"))
                "send_sms" -> graph.communication.sendSms(p.str("contact_name"), p.str("phone_number"), p.str("message").orEmpty())
                "send_email" -> graph.communication.sendEmail(p.str("to").orEmpty(), p.str("subject"), p.str("body"))
                "search_contact" -> graph.contacts.searchAndFormat(p.str("query").orEmpty())
                "set_alarm" -> graph.alarms.setAlarm(p.num("hour")?.toInt() ?: 0, p.num("minute")?.toInt() ?: 0, p.str("label"))
                "set_timer" -> graph.alarms.setTimer(p.num("seconds")?.toInt() ?: 60, p.str("label"))
                "set_volume" -> graph.system.setVolume(p.num("level")?.toInt() ?: 50)
                "set_brightness" -> graph.system.setBrightness(p.num("level")?.toInt() ?: 50)
                "set_flashlight" -> graph.system.setFlashlight(p["on"]?.let { runCatching { it.jsonPrimitive.content.toBooleanStrict() }.getOrNull() } ?: true)
                in STEP_BACKED -> graph.steps.run(action.action, p).message
                "run_adb_command" -> graph.shell.run(p.str("command").orEmpty())
                "open_url" -> graph.apps.openUrl(p.str("url").orEmpty())
                "read_screen" -> graph.screen.describe(null, compressed = false)
                "click_element", "type_on_screen", "scroll_screen", "press_back", "click_text", "click_at",
                "key_press", "press_enter", "press_home" -> {
                    val step = when (action.action) {
                        "type_on_screen" -> "type_text"
                        "scroll_screen" -> "scroll"
                        else -> action.action
                    }
                    graph.steps.run(step, p).message
                }
                "execute_task" -> graph.taskExecutor.execute(p.str("goal") ?: action.response, onProgress)
                else -> action.response
            }
            AgentActionResult(action.action, success = !details.looksFailed(), details = details)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AgentActionResult(action.action, success = false, details = "Error: ${e.message}")
        }
    }
}
