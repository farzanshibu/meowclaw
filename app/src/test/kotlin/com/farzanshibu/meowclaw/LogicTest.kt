package com.farzanshibu.meowclaw

import com.farzanshibu.meowclaw.data.AppSettings
import com.farzanshibu.meowclaw.data.SkillMemory
import com.farzanshibu.meowclaw.input.InputController
import com.farzanshibu.meowclaw.llm.LlmClient
import com.farzanshibu.meowclaw.llm.needle.IntentRouter
import com.farzanshibu.meowclaw.llm.needle.NeedleCall
import com.farzanshibu.meowclaw.llm.needle.NeedleEngine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntentRouterTest {
    private fun call(name: String, vararg args: Pair<String, Any>) = NeedleCall(
        name,
        JsonObject(args.associate { (k, v) -> k to if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()) }),
    )

    @Test fun multiStepRequestsGoToTheLlm() {
        assertNotNull(IntentRouter.screenTaskReason("open youtube and search for cats"))
        assertNotNull(IntentRouter.screenTaskReason("send hello to Priya on WhatsApp"))
        assertNotNull(IntentRouter.screenTaskReason("turn on wifi"))
        assertNull(IntentRouter.screenTaskReason("set volume to 40"))
        assertNull(IntentRouter.screenTaskReason("change the brightness to 20"))
        assertNull(IntentRouter.screenTaskReason("open whatsapp"))
        assertNull(IntentRouter.screenTaskReason("call mom"))
    }

    @Test fun openAppOnlyForBareOpenRequests() {
        assertNotNull(IntentRouter.toAction(call("open_app", "app_name" to "YouTube"), "open youtube"))
        assertNotNull(IntentRouter.toAction(call("open_app", "app_name" to "Settings"), "please launch the settings app"))
        assertNull(IntentRouter.toAction(call("open_app", "app_name" to "YouTube"), "search for cats on youtube"))
        assertNull(IntentRouter.toAction(call("open_app", "app_name" to "Maps"), "open youtube"))
    }

    @Test fun domainsBecomeUrls() {
        val action = IntentRouter.toAction(call("open_app", "app_name" to "github.com"), "go to github.com")
        assertEquals("open_url", action?.action)
    }

    @Test fun timerDurationComesFromTheRequest() {
        // Needle copies "10" into both fields; the request says 10 minutes.
        val action = IntentRouter.toAction(call("set_timer", "hours" to 10, "minutes" to 10), "set a timer for 10 minutes")
        assertEquals(600, action!!.params["seconds"]!!.jsonPrimitive.int)
        assertEquals(5400, IntentRouter.parseDurationSeconds("timer for 1 hour 30 minutes"))
        assertEquals(90, IntentRouter.parseDurationSeconds("timer 90 seconds"))
        assertEquals(1800, IntentRouter.parseDurationSeconds("half an hour timer"))
        assertNull(IntentRouter.parseDurationSeconds("start a timer"))
    }

    @Test fun alarmTimeIsVerified() {
        assertEquals(18 to 45, IntentRouter.parseClockTime("wake me up at 6:45 pm"))
        assertEquals(7 to 0, IntentRouter.parseClockTime("alarm at 7 am"))
        assertEquals(0 to 30, IntentRouter.parseClockTime("alarm 12:30 am"))
        assertEquals(21 to 15, IntentRouter.parseClockTime("alarm 21:15"))
        assertNull(IntentRouter.parseClockTime("alarm in 5 minutes"))
        val action = IntentRouter.toAction(call("set_alarm", "hour" to 6, "minute" to 45), "wake me up at 6:45 pm")
        assertEquals(18, action!!.params["hour"]!!.jsonPrimitive.int)
    }

    @Test fun messagesNeedRecipientAndText() {
        assertNull(IntentRouter.toAction(call("send_sms", "message" to "hi"), "send hi"))
        assertNotNull(IntentRouter.toAction(call("send_sms", "contact_name" to "Mom", "message" to "hi"), "text mom hi"))
        assertNull(IntentRouter.toAction(call("make_call"), "call"))
        assertNull(IntentRouter.toAction(call("set_volume", "level" to 140), "volume 140"))
    }

    @Test fun parsesEngineEnvelope() {
        val raw = """{"type":"call","success":true,"function_calls":[{"name":"set_volume","arguments":{"level":40}}],""" +
            """"suppressed_calls":[],"reasoning":"level 40","confidence":1.0000}"""
        val result = NeedleEngine.parse(raw, 12)
        assertEquals("set_volume", result.calls.single().name)
        assertEquals(1.0, result.confidence!!, 1e-9)
        assertTrue(result.suppressed.isEmpty())
    }
}

class LlmParsingTest {
    @Test fun parsesActionJson() {
        val action = LlmClient.parseAction("""{"action": "open_app", "params": {"app_name": "YouTube"}, "response": "Opening"}""")
        assertEquals("open_app", action?.action)
    }

    @Test fun repairsMissingBraceAndFences() {
        assertEquals("execute_task", LlmClient.parseAction("```json\n{\"action\": \"execute_task\", \"params\": {\"goal\": \"x\"}\n```")?.action)
        assertEquals("set_volume", LlmClient.parseAction("{\"action\": \"set_volume\", \"params\": {\"level\": 3}")?.action)
    }

    @Test fun plainTextIsNotAnAction() {
        assertNull(LlmClient.parseAction("Paris is the capital of France."))
    }

    @Test fun extractsJsonFromProse() {
        assertEquals("{\"a\":1}", LlmClient.extractJson("Sure! {\"a\":1} hope that helps"))
        assertEquals("{\"b\":2}", LlmClient.extractJson("```json\n{\"b\":2}\n```"))
    }

    @Test fun stripsThinkBlocks() {
        assertEquals("answer", LlmClient.stripThink("<think>hmm\nmore</think> answer"))
    }

    @Test fun completionsUrl() {
        assertEquals("https://api.x.com/v1/chat/completions", LlmClient.completionsUrl("https://api.x.com/v1"))
        assertEquals("https://api.x.com/v1/chat/completions", LlmClient.completionsUrl("https://api.x.com/v1/"))
    }

    @Test fun localServersNeedNoKey() {
        assertTrue(AppSettings(apiKey = "", baseUrl = "http://127.0.0.1:8080/v1", model = "gemma").isApiConfigured)
        assertTrue(AppSettings(apiKey = "", baseUrl = "http://192.168.1.20:11434/v1", model = "gemma").isApiConfigured)
        assertFalse(AppSettings(apiKey = "", baseUrl = "https://api.deepseek.com").isApiConfigured)
    }
}

class InputAndMemoryTest {
    @Test fun adbTextEscaping() {
        assertEquals("'hello%sworld'", InputController.adbText("hello world"))
        assertEquals("'it'\\''s'", InputController.adbText("it's"))
    }

    @Test fun keyNames() {
        assertEquals(android.view.KeyEvent.KEYCODE_ENTER, InputController.keyCode("enter"))
        assertEquals(android.view.KeyEvent.KEYCODE_A, InputController.keyCode("a"))
        assertEquals(android.view.KeyEvent.KEYCODE_F5, InputController.keyCode("f5"))
        assertNull(InputController.keyCode("nonsense"))
    }

    @Test fun skillSimilarity() {
        val a = SkillMemory.keywords("Open YouTube and search for cats")
        assertEquals(listOf("youtube", "search", "cats"), a)
        assertEquals(1.0, SkillMemory.jaccard(a, SkillMemory.keywords("search youtube for cats")), 1e-9)
    }
}

class GoalFastPathTest {
    private fun node(i: Int, text: String) = com.farzanshibu.meowclaw.UiNode(
        i, text, "", "TextView", "", true, false, false, false, false, false, android.graphics.Rect(), 0,
    )

    @Test fun parsesTapTargets() {
        assertEquals(listOf("setting", "display"), com.farzanshibu.meowclaw.agent.GoalFastPath.parseTargets("open setting then open display"))
        assertEquals(listOf("settings", "network & internet", "wi-fi"),
            com.farzanshibu.meowclaw.agent.GoalFastPath.parseTargets("open settings, tap network & internet and then tap wi-fi"))
        assertTrue(com.farzanshibu.meowclaw.agent.GoalFastPath.parseTargets("open youtube and search for cats").isEmpty())
    }

    @Test fun matchesVisibleLabels() {
        val nodes = listOf(node(0, "Network & internet"), node(1, "Displayed apps"), node(2, "Display & touch"))
        val fp = com.farzanshibu.meowclaw.agent.GoalFastPath("open setting then open display", skipFirstOpen = true)
        val move = fp.next(nodes) as com.farzanshibu.meowclaw.agent.GoalFastPath.Move.Tap
        assertEquals("Display & touch", move.label)
        fp.advance(move)
        assertTrue(fp.isComplete)
    }

    @Test fun searchClauses() {
        val steps = com.farzanshibu.meowclaw.agent.GoalFastPath.parseSteps("open youtube and search for cats on youtube")
        assertEquals(com.farzanshibu.meowclaw.agent.GoalFastPath.Step.Search("cats"), steps.last())
        val weather = com.farzanshibu.meowclaw.agent.GoalFastPath.parseSteps("open chrome and search for weather in london")
        assertEquals(com.farzanshibu.meowclaw.agent.GoalFastPath.Step.Search("weather in london"), weather.last())
        val fp = com.farzanshibu.meowclaw.agent.GoalFastPath("open chrome and search for weather in london", skipFirstOpen = true)
        val button = node(0, "Search or type URL")
        assertTrue(fp.next(listOf(button)) is com.farzanshibu.meowclaw.agent.GoalFastPath.Move.Tap)
    }

    @Test fun flashlightAndMediaRouteDirectly() {
        assertNull(IntentRouter.screenTaskReason("turn on the flashlight"))
        assertNull(IntentRouter.screenTaskReason("pause the music"))
        assertNull(IntentRouter.screenTaskReason("skip this song"))
        assertNotNull(IntentRouter.screenTaskReason("play despacito on youtube"))

        val on = IntentRouter.toAction(NeedleCall("set_flashlight", JsonObject(mapOf("on" to JsonPrimitive(false)))), "turn on the torch")
        assertEquals("true", on!!.params["on"]!!.jsonPrimitive.content)
        val off = IntentRouter.toAction(NeedleCall("set_flashlight", JsonObject(emptyMap())), "flashlight off")
        assertEquals("false", off!!.params["on"]!!.jsonPrimitive.content)
        assertNull(IntentRouter.toAction(NeedleCall("set_flashlight", JsonObject(emptyMap())), "turn on wifi"))

        val next = IntentRouter.toAction(NeedleCall("media_control", JsonObject(mapOf("command" to JsonPrimitive("pause")))), "skip this song")
        assertEquals("next", next!!.params["command"]!!.jsonPrimitive.content)
        assertNull(IntentRouter.toAction(NeedleCall("media_control", JsonObject(emptyMap())), "what's the weather"))
    }

    @Test fun cloudToolCallsBecomeSteps() {
        val call = com.farzanshibu.meowclaw.llm.cactus.LocalCall(
            "click_element", JsonObject(mapOf("index" to JsonPrimitive(4), "reasoning" to JsonPrimitive("Open Display"))),
        )
        val step = kotlinx.serialization.json.Json.parseToJsonElement(com.farzanshibu.meowclaw.llm.LanguageModel.stepJson(call)) as JsonObject
        assertEquals("click_element", step["action"]!!.jsonPrimitive.content)
        assertEquals("Open Display", step["reasoning"]!!.jsonPrimitive.content)
        assertFalse("reasoning" in (step["params"] as JsonObject))
        assertTrue(com.farzanshibu.meowclaw.agent.Prompts.TASK_TOOLS_CLOUD.all { "reasoning" in it.params })
    }

    @Test fun newQuickToolsValidateAgainstTheWords() {
        fun route(name: String, text: String, args: Map<String, String> = emptyMap()) =
            IntentRouter.toAction(NeedleCall(name, JsonObject(args.mapValues { JsonPrimitive(it.value) })), text)

        assertNull(IntentRouter.screenTaskReason("read my notifications"))
        assertNull(IntentRouter.screenTaskReason("check my battery"))
        assertNotNull(route("read_notifications", "read my notifications"))
        assertNull(route("read_notifications", "turn off notifications for whatsapp"))

        assertEquals("mute", route("adjust_volume", "mute", mapOf("direction" to "up"))!!.params["direction"]!!.jsonPrimitive.content)
        assertEquals("up", route("adjust_volume", "turn the volume up")!!.params["direction"]!!.jsonPrimitive.content)
        assertNull(route("adjust_volume", "mute the family group"))
        assertNull(route("adjust_volume", "set volume to 40"))
        val step = IntentRouter.toAction(NeedleCall("set_volume", JsonObject(emptyMap())), "turn the volume up")!!
        assertEquals("adjust_volume", step.action)
        assertEquals("set_volume", route("set_volume", "set volume to 40", mapOf("level" to "40"))!!.action)

        assertNotNull(route("get_device_status", "how much battery do I have"))
        assertNull(route("get_device_status", "tell me a joke"))
        assertNotNull(route("web_search", "search the web for kotlin flows", mapOf("query" to "kotlin flows")))
        assertNull(route("web_search", "open youtube", mapOf("query" to "youtube")))
    }

    @Test fun keywordCommandsSkipTheModel() {
        fun kw(text: String) = IntentRouter.keywordAction(text)
        assertEquals("pause", kw("pause the music")!!.params["command"]!!.jsonPrimitive.content)
        assertEquals("next", kw("next song")!!.params["command"]!!.jsonPrimitive.content)
        assertEquals("play", kw("resume")!!.params["command"]!!.jsonPrimitive.content)
        assertNull(kw("play despacito"))
        assertEquals("false", kw("turn off the flashlight")!!.params["on"]!!.jsonPrimitive.content)
        assertEquals("up", kw("turn the volume up")!!.params["direction"]!!.jsonPrimitive.content)
        assertEquals("mute", kw("mute")!!.params["direction"]!!.jsonPrimitive.content)
        assertEquals("read_notifications", kw("read my notifications")!!.action)
        assertEquals("get_device_status", kw("what's my battery level?")!!.action)
        assertNull(kw("turn off notifications for whatsapp"))
        assertNull(kw("set volume to 40"))
    }
}
