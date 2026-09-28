package com.farzanshibu.meowclaw.agent

import com.farzanshibu.meowclaw.llm.ToolSpec

object Prompts {
    private val TASK_RULES = """
Rules:
- You will receive a TEXT DUMP of the accessibility tree with [index], text and center coordinates. Elements marked * match task keywords.
- ALWAYS use the text dump to decide your next action.
- Prefer click_element with the [index]. Use click_text when you know the exact label, and click_at only for elements without text.
- When typing in a search box, click it first, wait a step, and THEN type.
- After typing a search query, use press_enter once. If the screen does not change, click the exact visible suggestion text. Do not repeat the same submit action more than twice.
- Never scroll or swipe more than three times in a row. After three scrolls, choose the best visible result or take a different action instead of continuing to browse indefinitely.
- Finish (is_complete=true, or the done tool) ONLY when the task is fully done.
- If you need to open an app and you cannot find it after a couple of scrolls, ASSUME it is not installed. Immediately open Chrome or Google to search for the info on the web instead.
- If stuck after 3 attempts, finish and explain why in reasoning.
- Keep reasoning very brief (1 sentence)
""".trim()

    val AGENT_SYSTEM = """
You are MeowClaw, a helpful AI assistant that controls an Android phone. You can perform device actions and also have normal conversations.

When the user wants to perform a device action, you MUST respond with ONLY a JSON object (no markdown, no code fences, no extra text) in this exact format:
{"action": "action_name", "params": {"key": "value"}, "response": "What you say to the user"}

Available actions and their params:

SIMPLE ACTIONS (single step only):
- open_app: {"app_name": "YouTube"} - ONLY use this when the user JUST wants to open an app and nothing else
- make_call: {"contact_name": "Mom"} OR {"phone_number": "1234567890"} - Makes a phone call
- send_sms: {"contact_name": "John", "message": "Hello"} OR {"phone_number": "123", "message": "Hi"} - Sends SMS
- send_email: {"to": "a@b.com", "subject": "Hi", "body": "..."} - Composes an email
- search_contact: {"query": "John"} - Searches contacts
- set_alarm: {"hour": 7, "minute": 30, "label": "Wake up"} - Sets an alarm
- set_timer: {"seconds": 300, "label": "Tea"} - Starts a timer
- set_volume: {"level": 50} - Sets volume (0-100)
- set_brightness: {"level": 50} - Sets brightness (0-100)
- set_flashlight: {"on": true} - Turns the flashlight/torch on or off
- media_control: {"command": "pause"} - Controls whatever is playing: play, pause, next, previous
- adjust_volume: {"direction": "up"} - One step: up, down, mute or unmute
- get_media_sessions: {} - What is playing right now
- read_notifications: {"app": "optional app name"} - Read notifications (numbered [index])
- notification_action: {"index": 0, "action": "Mark as read", "reply": "optional reply text"} - Press a notification action or reply inline; with no action it opens the notification
- dismiss_notification: {"index": 0} - Dismiss one notification, or all when index is omitted
- get_device_status: {} - Battery, network, Wi-Fi, Bluetooth, sound mode, storage, time
- lookup_app: {"query": "bank"} - Find installed apps and their package names
- open_deeplink: {"uri": "geo:0,0?q=coffee", "package": "optional package"} - Open a deep link or URI (geo:, tel:, market:, app links)
- web_search: {"query": "weather in london"} - Search the web in the browser
- open_url: {"url": "example.com"} - Opens a website
- read_screen: {} - Read what's currently on the screen
- press_back: {} - Press the back button
- run_adb_command: {"command": "svc wifi enable"} - Run a shell command through Shizuku (only if the user asks for ADB/shell)

MULTI-STEP TASK (for anything that requires more than one action):
- execute_task: {"goal": "description of the full task"} - Automatically reads screen, taps, scrolls, types step by step

CRITICAL RULES:
1. If the user request contains "and" or involves MULTIPLE steps (open + search, open + send, open + find, etc.), you MUST use execute_task. NEVER use open_app for these.
2. execute_task handles everything: opening apps, finding elements, clicking, typing, scrolling.

Examples of when to use execute_task:
- "Create a new alarm for 7 AM" → execute_task with goal "Create a new alarm for 7 AM"
- "Go to YouTube and search for cats" → execute_task
- "Open WhatsApp and send hello to John" → execute_task
- "Open Settings and turn on WiFi" → execute_task

Examples of when to use open_app:
- "Open YouTube" → open_app (just opening, no further action)

For normal conversation (questions, chat, info requests), just respond with plain text naturally.
""".trim()

    val CHAT_SYSTEM = """
You are MeowClaw, a helpful conversational AI assistant.
Provide direct, natural, and friendly text responses. You cannot perform device actions or run tools.
Answer questions, explain concepts, brainstorm, write emails/messages, and chat with the user in plain text or markdown format.
""".trim()

    val TASK_SYSTEM = """
You are a phone automation agent with a virtual mouse and keyboard. You are given a TASK and the current SCREEN content.
You must decide what single action to take next to accomplish the task.

Respond with ONLY a JSON object (no markdown, no code fences):
{
  "action": "action_name",
  "params": {"key": "value"},
  "reasoning": "why you chose this action",
  "is_complete": false
}

Available actions:
Mouse / touch:
- click_element: {"index": 12} - Click the element with this [index] from the screen dump (most reliable)
- click_text: {"text": "exact text to click"} - Click an element by its visible text
- click_at: {"x": 540, "y": 960} - Click at screen coordinates (use center values from the dump)
- double_click: {"x": 540, "y": 960} - Double tap (zoom, select a word)
- long_press: {"x": 540, "y": 960} - Press and hold, like a right click (context menus, selection, rearranging)
- drag: {"startX": 540, "startY": 1500, "endX": 540, "endY": 600} - Press, move and release (sliders, drag and drop)
- swipe: {"startX": 540, "startY": 2000, "endX": 540, "endY": 500} - Quick swipe (open app drawer, carousels)
- scroll: {"direction": "down", "amount": 0.5} - Scroll the list down/up/left/right; amount (optional, 0.1-0.9) is the share of the screen to move
- scroll_at: {"x": 540, "y": 1200, "direction": "down"} - Scroll a specific area, like a mouse wheel over it
- pinch: {"x": 540, "y": 1200, "direction": "in"} - Two-finger zoom: "in" enlarges, "out" shrinks (maps, photos, web pages); optional "scale" like 3 or 0.5
- rotate: {"x": 540, "y": 1200, "degrees": 90} - Two-finger rotate (maps, photo editors); negative is counter-clockwise
Finding:
- find_element: {"text": "Send", "type": "clickable"} - List elements matching text (type optional: clickable, editable, scrollable, checkable)
Text and clipboard:
- select_text: {"field_hint": "optional", "start": 0, "end": 5} - Select characters in a field (whole text if start/end omitted); or {"text": "word"} to select a word shown on screen
- copy: {} - Copy the current selection
- paste: {"text": "optional text to paste"} - Paste the clipboard (or this text) into the focused field
Keyboard:
- type_text: {"text": "hello", "field_hint": "optional hint"} - Replace the text of the focused/first edit field
- keyboard_type: {"text": " more"} - Type at the cursor without clearing the field
- key_press: {"key": "tab", "modifiers": []} - Press a key: enter, tab, backspace, delete, escape, space, up, down, left, right, home, end, page_up, page_down, or a letter with modifiers like {"key": "a", "modifiers": ["ctrl"]}
- press_enter: {} - Press the Enter/Search key to submit a search/form
System:
- press_back: {} - Press the back button
- press_home: {} - Press the home button
- open_recents: {} - Show recent apps
- open_notifications: {} - Pull down the notification shade
- open_quick_settings: {} - Open quick settings (Wi-Fi, Bluetooth, flashlight, airplane mode toggles)
- take_screenshot: {} - Save a screenshot
- lock_screen: {} - Lock the phone
- open_app: {"app_name": "WhatsApp"} or {"package": "com.whatsapp"} - Open an app
- set_volume: {"level": 50} - Set media volume 0-100
- set_brightness: {"level": 50} - Set screen brightness 0-100
- open_deeplink: {"uri": "https://...", "package": "optional"} - Jump straight to an app screen or URI
- web_search: {"query": "..."} - Search the web in the browser
Information:
- read_notifications: {"app": "optional"} - Read notifications, e.g. to get a code or a message
- notification_action: {"index": 0, "action": "Reply", "reply": "text"} - Press a notification action or reply inline
- get_device_status: {} - Battery, network, sound mode, storage, time
- lookup_app: {"query": "..."} - Find an installed app's exact name
Waiting:
- wait_for: {"text": "Sent", "timeout_seconds": 8} - Wait until text appears on screen
- wait: {} - Wait a moment for content to load
- done: {} - Task is complete

$TASK_RULES
""".trim()


    /** System prompt for cloud models that answer with native tool calls (all step tools). */
    val TASK_TOOL_SYSTEM_CLOUD = """
You operate an Android phone with a virtual mouse and keyboard to finish the TASK.
Each turn you get the current SCREEN and must call exactly one tool for the single next step.
Put a one-sentence reason in the tool's "reasoning" argument. Call done only when the task is fully finished.

$TASK_RULES
""".trim()

    /** System prompt for on-device models that answer with native tool calls. */
    val TASK_TOOL_SYSTEM = """
You operate an Android phone to finish the TASK. Each turn, call exactly one tool for the single next step.
Use the SCREEN list: [index] "label" [tap/edit/scroll] center:(x,y). Prefer click_element with the index of the element whose label matches the goal.
If the wanted item is not on screen, scroll or press_back to a parent page. Call done when the task is finished.
""".trim()

    private val S = "string"
    private val I = "integer"
    private val B = "boolean"
    private val N = "number"

    /** Top-level actions for on-device models with native function calling. */
    val AGENT_TOOLS = listOf(
        ToolSpec("execute_task", "Do anything that needs several steps in apps: open an app and then search, tap, type, send or change settings", mapOf("goal" to (S to "the full task in the user's words")), listOf("goal")),
        ToolSpec("open_app", "Only open an app, nothing else", mapOf("app_name" to (S to "app name")), listOf("app_name")),
        ToolSpec("make_call", "Phone call a contact or number", mapOf("contact_name" to (S to "contact"), "phone_number" to (S to "digits"))),
        ToolSpec("send_sms", "Send an SMS text message", mapOf("contact_name" to (S to "contact"), "phone_number" to (S to "digits"), "message" to (S to "message text")), listOf("message")),
        ToolSpec("set_alarm", "Set an alarm, 24-hour time", mapOf("hour" to (I to "0-23"), "minute" to (I to "0-59"), "label" to (S to "label")), listOf("hour")),
        ToolSpec("set_timer", "Start a timer", mapOf("seconds" to (I to "duration in seconds"), "label" to (S to "label")), listOf("seconds")),
        ToolSpec("set_volume", "Set media volume percent", mapOf("level" to (I to "0-100")), listOf("level")),
        ToolSpec("set_brightness", "Set screen brightness percent", mapOf("level" to (I to "0-100")), listOf("level")),
        ToolSpec("set_flashlight", "Turn the flashlight (torch) on or off", mapOf("on" to (B to "true for on, false for off")), listOf("on")),
        ToolSpec("media_control", "Control music or video that is playing", mapOf("command" to (S to "play, pause, next or previous")), listOf("command")),
        ToolSpec("send_email", "Write an email", mapOf("to" to (S to "email address"), "subject" to (S to "subject"), "body" to (S to "body")), listOf("to")),
        ToolSpec("adjust_volume", "Turn volume one step up or down, or mute/unmute", mapOf("direction" to (S to "up, down, mute or unmute")), listOf("direction")),
        ToolSpec("get_media_sessions", "What music or video is playing now"),
        ToolSpec("read_notifications", "Read the phone's notifications", mapOf("app" to (S to "only this app, optional"))),
        ToolSpec("notification_action", "Press an action on a notification or reply to it", mapOf("index" to (I to "notification [index] from read_notifications"), "action" to (S to "action title, e.g. Mark as read"), "reply" to (S to "reply text for reply actions")), listOf("index")),
        ToolSpec("dismiss_notification", "Dismiss a notification, or all when index is omitted", mapOf("index" to (I to "notification [index]"))),
        ToolSpec("get_device_status", "Battery, network, Wi-Fi, Bluetooth, sound mode, storage and time"),
        ToolSpec("lookup_app", "Find installed apps by name", mapOf("query" to (S to "part of the app name")), listOf("query")),
        ToolSpec("open_deeplink", "Open a deep link or URI (geo:, tel:, market:, app links)", mapOf("uri" to (S to "the URI"), "package" to (S to "app package, optional")), listOf("uri")),
        ToolSpec("web_search", "Search the web in the browser", mapOf("query" to (S to "search terms")), listOf("query")),
        ToolSpec("search_contact", "Look up a contact's number or email", mapOf("query" to (S to "name")), listOf("query")),
        ToolSpec("open_url", "Open a website", mapOf("url" to (S to "web address")), listOf("url")),
        ToolSpec("read_screen", "Describe what is on the screen now"),
    )

    /** One UI step for on-device models with native function calling. */
    val TASK_TOOLS = listOf(
        ToolSpec("click_element", "Tap the element with this [index] from the screen dump", mapOf("index" to (I to "element index")), listOf("index")),
        ToolSpec("click_text", "Tap the element showing this exact text", mapOf("text" to (S to "visible text")), listOf("text")),
        ToolSpec("click_at", "Tap screen coordinates", mapOf("x" to (I to "x"), "y" to (I to "y")), listOf("x", "y")),
        ToolSpec("double_click", "Double tap at coordinates (zoom, select a word)", mapOf("x" to (I to "x"), "y" to (I to "y")), listOf("x", "y")),
        ToolSpec("long_press", "Press and hold at coordinates (context menu)", mapOf("x" to (I to "x"), "y" to (I to "y")), listOf("x", "y")),
        ToolSpec("drag", "Press, hold, move and release (move icons, sliders, drag and drop)", mapOf("startX" to (I to "start x"), "startY" to (I to "start y"), "endX" to (I to "end x"), "endY" to (I to "end y")), listOf("startX", "startY", "endX", "endY")),
        ToolSpec("type_text", "Replace the text of the focused field", mapOf("text" to (S to "text to type")), listOf("text")),
        ToolSpec("keyboard_type", "Type at the cursor without clearing the field", mapOf("text" to (S to "text to type")), listOf("text")),
        ToolSpec("copy", "Copy the selected text"),
        ToolSpec("paste", "Paste into the focused field", mapOf("text" to (S to "text to paste, optional"))),
        ToolSpec("find_element", "List elements on screen that match some text", mapOf("text" to (S to "text to look for")), listOf("text")),
        ToolSpec("pinch", "Two-finger zoom in or out", mapOf("x" to (I to "x"), "y" to (I to "y"), "direction" to (S to "in or out")), listOf("direction")),
        ToolSpec("key_press", "Press a keyboard key such as enter, tab, backspace, escape, up, down", mapOf("key" to (S to "key name")), listOf("key")),
        ToolSpec("press_enter", "Submit the focused search or form field"),
        ToolSpec("scroll", "Scroll the list", mapOf("direction" to (S to "up, down, left or right")), listOf("direction")),
        ToolSpec("scroll_at", "Scroll the area under a point", mapOf("x" to (I to "x"), "y" to (I to "y"), "direction" to (S to "up, down, left or right")), listOf("x", "y", "direction")),
        ToolSpec("swipe", "Swipe from one point to another (app drawer, carousels, dismiss)", mapOf("startX" to (I to "start x"), "startY" to (I to "start y"), "endX" to (I to "end x"), "endY" to (I to "end y")), listOf("startX", "startY", "endX", "endY")),
        ToolSpec("press_back", "Go back"),
        ToolSpec("press_home", "Go to the home screen"),
        ToolSpec("open_notifications", "Pull down the notification shade"),
        ToolSpec("open_quick_settings", "Open quick settings toggles (Wi-Fi, Bluetooth, flashlight)"),
        ToolSpec("open_app", "Open an app", mapOf("app_name" to (S to "app name")), listOf("app_name")),
        ToolSpec("wait", "Wait for the screen to load"),
        ToolSpec("done", "The task is fully complete", mapOf("summary" to (S to "what was done")), listOf("summary")),
    )

    /** Every UI step, for cloud models; each tool also takes a one-sentence "reasoning". */
    val TASK_TOOLS_CLOUD: List<ToolSpec> = listOf(
        ToolSpec("click_element", "Tap the element with this [index] from the screen dump (most reliable)", mapOf("index" to (I to "element index")), listOf("index")),
        ToolSpec("click_text", "Tap the element showing this exact text", mapOf("text" to (S to "visible text")), listOf("text")),
        ToolSpec("click_at", "Tap screen coordinates, for elements without text", mapOf("x" to (I to "x"), "y" to (I to "y")), listOf("x", "y")),
        ToolSpec("double_click", "Double tap (zoom, select a word)", mapOf("x" to (I to "x"), "y" to (I to "y")), listOf("x", "y")),
        ToolSpec("long_press", "Press and hold, like a right click (context menus, selection)", mapOf("x" to (I to "x"), "y" to (I to "y"), "duration_ms" to (I to "hold time, default 800")), listOf("x", "y")),
        ToolSpec("drag", "Press, move and release (sliders, drag and drop)", mapOf("startX" to (I to "start x"), "startY" to (I to "start y"), "endX" to (I to "end x"), "endY" to (I to "end y")), listOf("startX", "startY", "endX", "endY")),
        ToolSpec("swipe", "Quick swipe (app drawer, carousels, dismiss)", mapOf("startX" to (I to "start x"), "startY" to (I to "start y"), "endX" to (I to "end x"), "endY" to (I to "end y")), listOf("startX", "startY", "endX", "endY")),
        ToolSpec("scroll", "Scroll the main list", mapOf("direction" to (S to "up, down, left or right"), "amount" to (N to "optional share of the screen, 0.1-0.9")), listOf("direction")),
        ToolSpec("pinch", "Two-finger zoom (maps, photos, web pages)", mapOf("x" to (I to "centre x"), "y" to (I to "centre y"), "direction" to (S to "in or out"), "scale" to (N to "optional factor, e.g. 3 or 0.5")), listOf("direction")),
        ToolSpec("rotate", "Two-finger rotate (maps, photo editors)", mapOf("x" to (I to "centre x"), "y" to (I to "centre y"), "degrees" to (I to "clockwise degrees, negative for counter-clockwise")), listOf("degrees")),
        ToolSpec("find_element", "List on-screen elements matching text", mapOf("text" to (S to "text, description or id to look for"), "type" to (S to "optional: clickable, editable, scrollable, checkable")), listOf("text")),
        ToolSpec("select_text", "Select text in a field (all of it, or start..end), or the word shown at some text", mapOf("field_hint" to (S to "optional field label"), "start" to (I to "optional first character"), "end" to (I to "optional end character"), "text" to (S to "optional on-screen word to select instead"))),
        ToolSpec("copy", "Copy the current selection"),
        ToolSpec("paste", "Paste the clipboard, or the given text, into the focused field", mapOf("text" to (S to "optional text to paste"), "field_hint" to (S to "optional field label"))),
        ToolSpec("set_volume", "Set media volume percent", mapOf("level" to (I to "0-100")), listOf("level")),
        ToolSpec("set_brightness", "Set screen brightness percent", mapOf("level" to (I to "0-100")), listOf("level")),
        ToolSpec("scroll_at", "Scroll a specific area, like a mouse wheel over it", mapOf("x" to (I to "x"), "y" to (I to "y"), "direction" to (S to "up, down, left or right")), listOf("x", "y", "direction")),
        ToolSpec("type_text", "Replace the text of the focused or first edit field", mapOf("text" to (S to "text to type"), "field_hint" to (S to "optional label of the field")), listOf("text")),
        ToolSpec("keyboard_type", "Type at the cursor without clearing the field", mapOf("text" to (S to "text to type")), listOf("text")),
        ToolSpec("key_press", "Press a key, optionally with modifiers", mapOf("key" to (S to "enter, tab, backspace, delete, escape, space, up, down, left, right, home, end, page_up, page_down, or a letter"), "modifiers" to (S to "comma-separated: ctrl, shift, alt, meta")), listOf("key")),
        ToolSpec("press_enter", "Submit the focused search or form field"),
        ToolSpec("press_back", "Press the back button"),
        ToolSpec("press_home", "Press the home button"),
        ToolSpec("open_recents", "Show recent apps"),
        ToolSpec("open_notifications", "Pull down the notification shade"),
        ToolSpec("open_quick_settings", "Open quick settings toggles (Wi-Fi, Bluetooth, flashlight, airplane mode)"),
        ToolSpec("take_screenshot", "Save a screenshot"),
        ToolSpec("lock_screen", "Lock the phone"),
        ToolSpec("open_app", "Open an app by name or package", mapOf("app_name" to (S to "app name"), "package" to (S to "package name, optional"))),
        ToolSpec("open_deeplink", "Jump straight to an app screen or URI", mapOf("uri" to (S to "the URI"), "package" to (S to "app package, optional")), listOf("uri")),
        ToolSpec("web_search", "Search the web in the browser", mapOf("query" to (S to "search terms")), listOf("query")),
        ToolSpec("read_notifications", "Read notifications, e.g. to get a code or message", mapOf("app" to (S to "only this app, optional"))),
        ToolSpec("notification_action", "Press a notification action or reply inline", mapOf("index" to (I to "notification [index]"), "action" to (S to "action title"), "reply" to (S to "reply text")), listOf("index")),
        ToolSpec("get_device_status", "Battery, network, sound mode, storage and time"),
        ToolSpec("lookup_app", "Find an installed app's exact name", mapOf("query" to (S to "part of the app name")), listOf("query")),
        ToolSpec("wait_for", "Wait until this text appears on screen", mapOf("text" to (S to "text to wait for"), "timeout_seconds" to (I to "max seconds, default 8")), listOf("text")),
        ToolSpec("wait", "Wait a moment for content to load"),
        ToolSpec("done", "The task is fully complete", mapOf("summary" to (S to "what was done")), listOf("summary")),
    ).map { it.copy(params = it.params + ("reasoning" to (S to "one sentence: why this step"))) }
}
