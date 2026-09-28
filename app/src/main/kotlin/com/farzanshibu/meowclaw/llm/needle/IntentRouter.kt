package com.farzanshibu.meowclaw.llm.needle

import com.farzanshibu.meowclaw.data.AgentAction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

sealed interface RouteDecision {
    /** Needle produced one well-formed device action. */
    data class Direct(val action: AgentAction, val result: NeedleResult, val confident: Boolean) : RouteDecision

    /** Needs a general LLM: multi-step screen work, ambiguity, or chit-chat. */
    data class Escalate(val reason: String, val screenTask: Boolean, val result: NeedleResult?) : RouteDecision

    /** Needle found no matching tool (its refusal). */
    data class Unhandled(val result: NeedleResult) : RouteDecision
}

/**
 * On-device first routing: Needle maps simple device requests straight to an
 * action, and anything it cannot do safely (multi-step app navigation,
 * messaging inside third-party apps, low confidence) goes to the LLM.
 */
class IntentRouter(private val engine: NeedleEngine) {

    /**
     * [skipPrecheck] runs Needle even on wording that looks multi-step; used
     * when no LLM is configured, so offline requests still get a best effort.
     */
    suspend fun route(text: String, minConfidence: Double, skipPrecheck: Boolean = false): RouteDecision {
        val query = text.trim()
        if (!skipPrecheck) {
            screenTaskReason(query)?.let { return RouteDecision.Escalate(it, screenTask = true, result = null) }
        }
        // Unambiguous device commands need no model at all.
        keywordAction(query)?.let {
            return RouteDecision.Direct(it, NeedleResult(emptyList(), emptyList(), 1.0, "keyword", 0, ""), confident = true)
        }

        val result = engine.complete(NeedleEngine.systemFacts(), TOOLS_JSON, query)
        val calls = result.calls
        if (calls.isEmpty()) {
            // Needle holds back calls with values it can't find in the text ("volume up" → level 100).
            // When our validator rebuilds the parameters from the words, the call is safe to run.
            result.suppressed.singleOrNull()?.let { toAction(it, query) }
                ?.takeIf { it.action in WORD_GROUNDED }
                ?.let { return RouteDecision.Direct(it, result, confident = true) }
            return if (result.suppressed.isNotEmpty()) {
                RouteDecision.Escalate("Needle was unsure", screenTask = false, result = result)
            } else {
                RouteDecision.Unhandled(result)
            }
        }
        if (calls.size > 1) return RouteDecision.Escalate("Request needs several steps", screenTask = true, result = result)

        val call = calls.single()
        val action = toAction(call, query)
            ?: return RouteDecision.Escalate("Missing details for ${call.name}", screenTask = false, result = result)
        val confident = (result.confidence ?: 0.0) >= minConfidence || action.action in WORD_GROUNDED
        return RouteDecision.Direct(action, result, confident)
    }

    companion object {
        /**
         * Tool surface for Needle: short, one action per tool, formats in the
         * descriptions (see cactuscompute.com/blog/designing-tools-for-needle).
         */
        val TOOLS_JSON: String = """
            [
            {"name":"open_app","description":"Open an installed app, only when the user just wants to open or launch it","parameters":{"type":"object","properties":{"app_name":{"type":"string","description":"app name, e.g. YouTube"}},"required":["app_name"]}},
            {"name":"make_call","description":"Phone call a contact or a phone number","parameters":{"type":"object","properties":{"contact_name":{"type":"string","description":"contact name, e.g. Mom"},"phone_number":{"type":"string","description":"digits to dial"}},"required":[]}},
            {"name":"send_sms","description":"Send a text message (SMS) to a contact or phone number","parameters":{"type":"object","properties":{"contact_name":{"type":"string"},"phone_number":{"type":"string"},"message":{"type":"string","description":"the message text, verbatim"}},"required":["message"]}},
            {"name":"send_email","description":"Write an email to an address","parameters":{"type":"object","properties":{"to":{"type":"string","description":"email address"},"subject":{"type":"string"},"body":{"type":"string"}},"required":["to"]}},
            {"name":"set_alarm","description":"Set an alarm clock for a time of day, 24-hour","parameters":{"type":"object","properties":{"hour":{"type":"integer","minimum":0,"maximum":23},"minute":{"type":"integer","minimum":0,"maximum":59},"label":{"type":"string"}},"required":["hour"]}},
            {"name":"set_timer","description":"Start a countdown timer for a duration","parameters":{"type":"object","properties":{"hours":{"type":"integer","minimum":0},"minutes":{"type":"integer","minimum":0},"seconds":{"type":"integer","minimum":0},"label":{"type":"string"}},"required":[]}},
            {"name":"set_volume","description":"Set the media volume in percent","parameters":{"type":"object","properties":{"level":{"type":"integer","minimum":0,"maximum":100}},"required":["level"]}},
            {"name":"set_brightness","description":"Set the screen brightness in percent","parameters":{"type":"object","properties":{"level":{"type":"integer","minimum":0,"maximum":100}},"required":["level"]}},
            {"name":"set_flashlight","description":"Turn the flashlight (torch) on or off","parameters":{"type":"object","properties":{"on":{"type":"boolean","description":"true to turn on, false to turn off"}},"required":["on"]}},
            {"name":"media_control","description":"Pause, resume, skip or go back in the music or video that is playing","parameters":{"type":"object","properties":{"command":{"type":"string","enum":["play","pause","next","previous"]}},"required":["command"]}},
            {"name":"adjust_volume","description":"Turn the volume up or down one step, or mute or unmute","parameters":{"type":"object","properties":{"direction":{"type":"string","enum":["up","down","mute","unmute"]}},"required":["direction"]}},
            {"name":"read_notifications","description":"Read the phone's notifications","parameters":{"type":"object","properties":{"app":{"type":"string","description":"only notifications from this app"}},"required":[]}},
            {"name":"get_device_status","description":"Check battery, charging, internet, Wi-Fi, Bluetooth, sound mode and storage","parameters":{"type":"object","properties":{}}},
            {"name":"web_search","description":"Search the web for something","parameters":{"type":"object","properties":{"query":{"type":"string","description":"what to search for"}},"required":["query"]}},
            {"name":"search_contact","description":"Look up a contact's phone number or email","parameters":{"type":"object","properties":{"query":{"type":"string","description":"name to look up"}},"required":["query"]}},
            {"name":"open_url","description":"Open a website address in the browser","parameters":{"type":"object","properties":{"url":{"type":"string","description":"web address, e.g. example.com"}},"required":["url"]}},
            {"name":"read_screen","description":"Describe what is currently shown on the screen","parameters":{"type":"object","properties":{}}}
            ]
        """.trimIndent().replace("\n", "")

        /** Actions whose parameters [toAction] reads from the user's words, not from the model. */
        val WORD_GROUNDED = setOf(
            "adjust_volume", "set_flashlight", "media_control", "get_device_status", "read_notifications", "set_timer",
        )

        /** Actions validated against the request text before they run. */
        val QUICK_ACTIONS = setOf(
            "open_app", "make_call", "send_sms", "send_email", "set_alarm", "set_timer",
            "set_volume", "set_brightness", "search_contact", "open_url", "set_flashlight", "media_control",
            "adjust_volume", "read_notifications", "get_device_status", "web_search",
        )

        private val SCREEN_VERBS = Regex(
            "\\b(search|find|look (up|for)|play|type|scroll|tap|click|press|navigate|browse|order|book|buy|" +
                "post|reply|like|share|subscribe|install|download|turn (on|off)|switch (on|off)|enable|disable|" +
                "toggle|connect|pair|delete|remove|add|create|edit|change|check|read my|show me)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val SEQUENCE = Regex("\\b(and then|then|after that|and)\\b|,\\s*(then|and)\\b", RegexOption.IGNORE_CASE)
        private val MESSAGING_APPS = Regex(
            "\\b(whatsapp|telegram|signal|instagram|insta|messenger|facebook|slack|discord|snapchat|teams|" +
                "wechat|viber|skype|zoom|twitter|linkedin|gmail|outlook)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val OPEN_ONLY = Regex(
            "^(please\\s+)?(open|launch|start|run|go to|show)\\s+(the\\s+|my\\s+)?(.+?)(\\s+app)?[.!]?$",
            RegexOption.IGNORE_CASE,
        )
        /** Device settings with a direct action, even though their wording ("turn on", "play") sounds like screen work. */
        private val DIRECT_DEVICE = Regex(
            "\\b(volume|brightness|flash ?light|torch|pause|resume|next (song|track)|previous (song|track)|skip (this )?(song|track)|" +
                "mute|unmute|notifications?|battery|charging|device status|search the web|web search|google for)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val NOT_VOLUME = Regex("\\b(group|chat|conversation|notifications?|contact|call|thread)\\b", RegexOption.IGNORE_CASE)
        private val NOTIFICATION_SETTING = Regex("\\b(turn|switch|disable|enable|block|allow|stop|silence|mute|settings?)\\b", RegexOption.IGNORE_CASE)
        private val STATUS_WORDS = Regex(
            "\\b(battery|charg\\w*|status|storage|space|online|internet|connected|wi-?fi|bluetooth|airplane|ringer|silent|do not disturb|dnd)\\b",
            RegexOption.IGNORE_CASE,
        )
        private val FLASHLIGHT = Regex("\\b(flash ?light|torch)\\b", RegexOption.IGNORE_CASE)
        private val OFF = Regex("\\b(off|disable|stop)\\b", RegexOption.IGNORE_CASE)

        /** Requests that need the screen-driving LLM loop; checked before Needle runs. */
        fun screenTaskReason(text: String): String? {
            val lower = text.lowercase()
            // "set volume to 40 and brightness to 20" is two simple actions, still multi-step.
            if (SEQUENCE.containsMatchIn(lower)) return "Request has several steps"
            if (SCREEN_VERBS.containsMatchIn(lower) && !DIRECT_DEVICE.containsMatchIn(lower)) {
                return "Request needs screen navigation"
            }
            if (MESSAGING_APPS.containsMatchIn(lower) && !OPEN_ONLY.matches(lower)) {
                return "Messaging inside another app needs screen control"
            }
            return null
        }

        private fun JsonObject.str(key: String) =
            this[key]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }

        private fun JsonObject.int(key: String) =
            this[key]?.let { runCatching { it.jsonPrimitive.intOrNull ?: it.jsonPrimitive.contentOrNull?.toDoubleOrNull()?.toInt() }.getOrNull() }

        /** Validates arguments and converts a Needle call into an app action; null when unsafe. */
        fun toAction(call: NeedleCall, query: String): AgentAction? {
            val a = call.arguments
            val params: JsonObject = when (call.name) {
                "open_app" -> {
                    val app = a.str("app_name") ?: return null
                    // "go to github.com" is a website, not an app.
                    if (DOMAIN.matches(app)) return AgentAction("open_url", obj("url" to app))
                    // Only a bare "open X": anything else is a task inside the app.
                    val match = OPEN_ONLY.find(query.trim()) ?: return null
                    val requested = match.groupValues[4].lowercase()
                    val norm = { s: String -> s.lowercase().replace(Regex("[^a-z0-9]"), "") }
                    if (!norm(requested).contains(norm(app)) && !norm(app).contains(norm(requested))) return null
                    obj("app_name" to app)
                }
                "make_call" -> {
                    val contact = a.str("contact_name")
                    val number = a.str("phone_number")?.takeIf { n -> n.count(Char::isDigit) >= 3 }
                    if (contact == null && number == null) return null
                    obj("contact_name" to contact, "phone_number" to number)
                }
                "send_sms" -> {
                    val message = a.str("message") ?: return null
                    val contact = a.str("contact_name")
                    val number = a.str("phone_number")?.takeIf { n -> n.count(Char::isDigit) >= 3 }
                    if (contact == null && number == null) return null
                    obj("contact_name" to contact, "phone_number" to number, "message" to message)
                }
                "send_email" -> {
                    val to = a.str("to")?.takeIf { it.contains('@') } ?: return null
                    obj("to" to to, "subject" to a.str("subject"), "body" to a.str("body"))
                }
                "set_alarm" -> {
                    // A time written in the request wins over the model's reading of it.
                    val (hour, minute) = parseClockTime(query)
                        ?: ((a.int("hour")?.takeIf { it in 0..23 } ?: return null) to
                            ((a.int("minute") ?: 0).takeIf { it in 0..59 } ?: return null))
                    obj("hour" to hour, "minute" to minute, "label" to a.str("label"))
                }
                "set_timer" -> {
                    // Needle tends to copy one number into every duration field,
                    // so the duration is read from the request itself.
                    val seconds = parseDurationSeconds(query) ?: return null
                    if (seconds > 24 * 3600) return null
                    obj("seconds" to seconds, "label" to a.str("label"))
                }
                "set_volume", "set_brightness" -> {
                    val level = a.int("level")?.takeIf { it in 0..100 }
                    // "turn the volume up" has no number: Needle often still picks set_volume.
                    if (level == null || (call.name == "set_volume" && !query.any(Char::isDigit))) {
                        if (call.name == "set_volume") volumeStep(query)?.let { return AgentAction("adjust_volume", obj("direction" to it), "") }
                        if (level == null) return null
                    }
                    obj("level" to level)
                }
                "set_flashlight" -> {
                    // On/off is read from the words; the model only confirms the intent.
                    if (!FLASHLIGHT.containsMatchIn(query)) return null
                    obj("on" to !OFF.containsMatchIn(query))
                }
                "media_control" -> {
                    val lower = query.lowercase()
                    // "play despacito" names something to find; only bare "play" resumes.
                    if (Regex("^(please )?play ").containsMatchIn(lower) && !MEDIA_COMMAND.matches(lower.trim()) && !TRACK_STEP.matches(lower.trim())) return null
                    val command = when {
                        Regex("\\b(next|skip)\\b").containsMatchIn(lower) -> "next"
                        Regex("\\b(previous|last|go back)\\b").containsMatchIn(lower) -> "previous"
                        Regex("\\b(pause|stop)\\b").containsMatchIn(lower) -> "pause"
                        Regex("\\b(resume|play|continue|unpause)\\b").containsMatchIn(lower) -> "play"
                        else -> return null
                    }
                    obj("command" to command)
                }
                "adjust_volume" -> obj("direction" to (volumeStep(query) ?: return null))
                "read_notifications" -> {
                    if (!Regex("\\bnotifications?\\b", RegexOption.IGNORE_CASE).containsMatchIn(query)) return null
                    // "turn off notifications for X" changes a setting; it is not a read.
                    if (NOTIFICATION_SETTING.containsMatchIn(query)) return null
                    obj("app" to a.str("app"))
                }
                "get_device_status" -> {
                    if (!STATUS_WORDS.containsMatchIn(query)) return null
                    JsonObject(emptyMap())
                }
                "web_search" -> {
                    val q = a.str("query") ?: return null
                    if (!Regex("\\b(search|google|look up|find online)\\b", RegexOption.IGNORE_CASE).containsMatchIn(query)) return null
                    obj("query" to q)
                }
                "search_contact" -> obj("query" to (a.str("query") ?: return null))
                "open_url" -> {
                    val url = a.str("url")?.takeIf { it.contains('.') && !it.contains(' ') } ?: return null
                    obj("url" to url)
                }
                "read_screen" -> JsonObject(emptyMap())
                else -> return null
            }
            return AgentAction(action = call.name, params = params, response = "")
        }

        private val MEDIA_COMMAND = Regex(
            "^(please )?(pause|resume|stop|play|continue|skip|next|previous)( (the|this|my))?( (music|song|track|video|podcast|audio|playback|media))?( please)?[.!]?$",
            RegexOption.IGNORE_CASE,
        )
        private val TRACK_STEP = Regex("^(please )?(play )?(the )?(next|previous|last) (song|track)( please)?[.!]?$", RegexOption.IGNORE_CASE)
        private val VOLUME_STEP = Regex(
            "^(please )?((turn|crank) (the )?volume (up|down)|volume (up|down)|(turn|make) it (louder|quieter)|louder|quieter|mute|unmute)( please)?[.!]?$",
            RegexOption.IGNORE_CASE,
        )
        private val READ_NOTIFICATIONS = Regex(
            "^(please )?(read|show|check|list|what are|what're|any)( me)?( (all|my|the|new))*( notifications?)( please)?[?.!]?$",
            RegexOption.IGNORE_CASE,
        )
        private val BATTERY = Regex("^(what'?s |what is |how much |check )?(my |the )?battery( level| left| percentage)?( do i have)?[?.!]?$", RegexOption.IGNORE_CASE)

        /**
         * Short, unambiguous phrasings that map to exactly one action. Checked
         * before Needle, which confuses some of them ("pause the music" → volume 0).
         */
        fun keywordAction(query: String): AgentAction? {
            val q = query.trim()
            val call: NeedleCall = when {
                MEDIA_COMMAND.matches(q) || TRACK_STEP.matches(q) -> NeedleCall("media_control", JsonObject(emptyMap()))
                FLASHLIGHT.containsMatchIn(q) && q.split(' ').size <= 6 -> NeedleCall("set_flashlight", JsonObject(emptyMap()))
                VOLUME_STEP.matches(q) -> NeedleCall("adjust_volume", JsonObject(emptyMap()))
                READ_NOTIFICATIONS.matches(q) -> NeedleCall("read_notifications", JsonObject(emptyMap()))
                BATTERY.matches(q) -> NeedleCall("get_device_status", JsonObject(emptyMap()))
                else -> return null
            }
            return toAction(call, q)
        }

        /** up / down / mute / unmute read from the words; null for "set volume to 40" or chat settings. */
        private fun volumeStep(query: String): String? {
            val lower = query.lowercase()
            // "mute the family group" is a chat setting.
            if (lower.any(Char::isDigit) || NOT_VOLUME.containsMatchIn(lower) || MESSAGING_APPS.containsMatchIn(lower)) return null
            return when {
                Regex("\\bunmute\\b").containsMatchIn(lower) -> "unmute"
                Regex("\\b(mute|silence)\\b").containsMatchIn(lower) -> "mute"
                Regex("\\b(up|louder|increase|raise|higher)\\b").containsMatchIn(lower) -> "up"
                Regex("\\b(down|quieter|decrease|lower|softer)\\b").containsMatchIn(lower) -> "down"
                else -> null
            }
        }

        private val DOMAIN = Regex("^(https?://)?[a-z0-9-]+(\\.[a-z0-9-]+)+(/\\S*)?$", RegexOption.IGNORE_CASE)

        private val DURATION_PART = Regex(
            "(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b",
            RegexOption.IGNORE_CASE,
        )

        /** "10 minutes", "1 hour 30 minutes", "90 sec", "half an hour" → seconds. */
        fun parseDurationSeconds(text: String): Int? {
            val lower = text.lowercase()
            var total = 0.0
            DURATION_PART.findAll(lower).forEach { m ->
                val n = m.groupValues[1].toDouble()
                total += when (m.groupValues[2].first()) {
                    'h' -> n * 3600
                    'm' -> n * 60
                    else -> n
                }
            }
            if (total == 0.0) {
                when {
                    "half an hour" in lower -> total = 1800.0
                    "an hour" in lower || "one hour" in lower -> total = 3600.0
                    "a minute" in lower || "one minute" in lower -> total = 60.0
                }
            }
            return total.toInt().takeIf { it > 0 }
        }

        private val CLOCK = Regex(
            "\\b(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?(?=\\s|$|[.,!?])",
            RegexOption.IGNORE_CASE,
        )

        /** Reads "7:30", "6 pm", "at 18:45"; null when the request has no explicit time. */
        fun parseClockTime(text: String): Pair<Int, Int>? {
            for (m in CLOCK.findAll(text)) {
                var hour = m.groupValues[1].toInt()
                val minute = m.groupValues[2].toIntOrNull() ?: 0
                val suffix = m.groupValues[3].lowercase().replace(".", "")
                // A bare number ("in 5 minutes") is not a clock time.
                if (m.groupValues[2].isEmpty() && suffix.isEmpty()) continue
                if (minute > 59) continue
                when (suffix) {
                    "pm" -> if (hour in 1..11) hour += 12
                    "am" -> if (hour == 12) hour = 0
                }
                if (hour in 0..23) return hour to minute
            }
            return null
        }

        private fun obj(vararg pairs: Pair<String, Any?>) = buildJsonObject {
            pairs.forEach { (key, value) ->
                when (value) {
                    null -> Unit
                    is String -> put(key, JsonPrimitive(value))
                    is Number -> put(key, JsonPrimitive(value))
                    is Boolean -> put(key, JsonPrimitive(value))
                    else -> put(key, JsonPrimitive(value.toString()))
                }
            }
        }
    }
}
