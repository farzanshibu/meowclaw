package com.farzanshibu.meowclaw.input

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.view.KeyEvent
import android.view.WindowManager
import com.farzanshibu.meowclaw.AgentAccessibilityService
import com.farzanshibu.meowclaw.UiNode
import com.farzanshibu.meowclaw.device.ShizukuShell
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

data class InputResult(val success: Boolean, val message: String)

/**
 * The agent's hands: a virtual mouse (tap, double tap, long press, drag,
 * scroll at a point) and a virtual keyboard (text, keys, shortcuts).
 *
 * Every action picks the best available backend:
 *  1. Accessibility service — node actions and dispatched gestures.
 *  2. Input method — the accessibility input method (Android 13+), for
 *     real key events and text commits.
 *  3. ADB via Shizuku — `input tap|swipe|text|keyevent`.
 *
 * When the accessibility service runs, the on-screen cursor glides to each
 * target before the action so the user can follow along.
 */
class InputController(private val context: Context, private val shell: ShizukuShell) {
    private val service: AgentAccessibilityService? get() = AgentAccessibilityService.instance

    val isAccessibilityReady: Boolean get() = service != null
    val isAnyBackendReady: Boolean get() = service != null || shell.isReady

    fun screenSize(): Pair<Int, Int> {
        val wm = context.getSystemService(WindowManager::class.java)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = context.resources.displayMetrics
            m.widthPixels to m.heightPixels
        }
    }

    private fun clamp(x: Float, y: Float): Pair<Float, Float> {
        val (w, h) = screenSize()
        return x.coerceIn(1f, w - 1f) to y.coerceIn(1f, h - 1f)
    }

    // ─── Session / overlay ───────────────────────────────────────

    fun beginSession() {
        service?.overlay?.begin()
    }

    fun endSession() {
        service?.overlay?.setLabel(null)
        service?.overlay?.end()
    }

    fun label(text: String?) {
        service?.overlay?.setLabel(text)
    }

    private suspend fun pointAt(x: Float, y: Float, label: String? = null) {
        val overlay = service?.overlay ?: return
        if (label != null) overlay.setLabel(label)
        overlay.moveCursor(x, y)
    }

    // ─── Virtual mouse ───────────────────────────────────────────

    suspend fun move(x: Float, y: Float): InputResult {
        val (cx, cy) = clamp(x, y)
        pointAt(cx, cy)
        return InputResult(true, "Moved cursor to (${cx.toInt()}, ${cy.toInt()})")
    }

    suspend fun tap(x: Float, y: Float): InputResult {
        val (cx, cy) = clamp(x, y)
        pointAt(cx, cy, "Click")
        service?.let { s ->
            s.overlay.click(cx, cy)
            if (s.perform(stroke(cx, cy, cx, cy, 0, 60))) return ok("Clicked at (${cx.toInt()}, ${cy.toInt()})")
        }
        if (shell.isReady && shell.succeeded("input tap ${cx.toInt()} ${cy.toInt()}")) {
            return ok("Clicked at (${cx.toInt()}, ${cy.toInt()}) via ADB")
        }
        return fail("Click failed at (${cx.toInt()}, ${cy.toInt()})")
    }

    suspend fun doubleTap(x: Float, y: Float): InputResult {
        val (cx, cy) = clamp(x, y)
        pointAt(cx, cy, "Double click")
        service?.let { s ->
            s.overlay.click(cx, cy)
            val path = Path().apply { moveTo(cx, cy) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .addStroke(GestureDescription.StrokeDescription(path, 140, 50))
                .build()
            if (s.perform(gesture)) {
                s.overlay.click(cx, cy)
                return ok("Double-clicked at (${cx.toInt()}, ${cy.toInt()})")
            }
        }
        if (shell.isReady) {
            val tap = "input tap ${cx.toInt()} ${cy.toInt()}"
            if (shell.succeeded("$tap && $tap")) return ok("Double-clicked via ADB")
        }
        return fail("Double click failed")
    }

    /** Long press — the touch equivalent of a right click (context menus). */
    suspend fun longPress(x: Float, y: Float, durationMs: Long = 800): InputResult {
        val (cx, cy) = clamp(x, y)
        pointAt(cx, cy, "Hold")
        service?.let { s ->
            s.overlay.click(cx, cy, long = true)
            if (s.perform(stroke(cx, cy, cx, cy, 0, durationMs))) return ok("Long-pressed at (${cx.toInt()}, ${cy.toInt()})")
        }
        if (shell.isReady && shell.succeeded("input swipe ${cx.toInt()} ${cy.toInt()} ${cx.toInt()} ${cy.toInt()} $durationMs")) {
            return ok("Long-pressed via ADB")
        }
        return fail("Long press failed")
    }

    /** Press, move and release — drag and drop, sliders, swipes. */
    suspend fun drag(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 450, hold: Boolean = false): InputResult {
        val (sx, sy) = clamp(x1, y1)
        val (ex, ey) = clamp(x2, y2)
        pointAt(sx, sy, if (hold) "Drag" else "Swipe")
        service?.let { s ->
            if (hold) {
                // Hold first so launchers and lists pick the item up, then move.
                val path = Path().apply { moveTo(sx, sy); lineTo(sx + 1, sy + 1); lineTo(ex, ey) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 700 + durationMs))
                    .build()
                s.overlay.click(sx, sy, long = true)
                val dispatched = coroutineScope {
                    val cursor = async {
                        delay(700)
                        s.overlay.dragCursor(sx, sy, ex, ey, durationMs)
                    }
                    s.perform(gesture).also { cursor.await() }
                }
                if (dispatched) return ok("Dragged from (${sx.toInt()},${sy.toInt()}) to (${ex.toInt()},${ey.toInt()})")
            } else {
                val dispatched = coroutineScope {
                    val cursor = async { s.overlay.dragCursor(sx, sy, ex, ey, durationMs) }
                    s.perform(stroke(sx, sy, ex, ey, 0, durationMs)).also { cursor.await() }
                }
                if (dispatched) return ok("Swiped from (${sx.toInt()},${sy.toInt()}) to (${ex.toInt()},${ey.toInt()})")
            }
        }
        if (shell.isReady) {
            val cmd = if (hold) "input draganddrop" else "input swipe"
            if (shell.succeeded("$cmd ${sx.toInt()} ${sy.toInt()} ${ex.toInt()} ${ey.toInt()} $durationMs")) {
                return ok("Dragged via ADB")
            }
        }
        return fail("Drag failed")
    }

    /** Scroll like a mouse wheel at a point (or the screen centre). */
    suspend fun scrollAt(direction: String, x: Float? = null, y: Float? = null, amount: Float = 0.5f): InputResult {
        val (w, h) = screenSize()
        val cx = x ?: (w / 2f)
        val cy = y ?: (h / 2f)
        val dist = (if (direction.lowercase() in setOf("left", "right")) w else h) * amount.coerceIn(0.1f, 0.9f) / 2f
        val (x1, y1, x2, y2) = when (direction.lowercase()) {
            "up" -> listOf(cx, cy - dist, cx, cy + dist)
            "left" -> listOf(cx - dist, cy, cx + dist, cy)
            "right" -> listOf(cx + dist, cy, cx - dist, cy)
            else -> listOf(cx, cy + dist, cx, cy - dist)
        }
        return drag(x1, y1, x2, y2, 350).let { if (it.success) ok("Scrolled $direction") else it }
    }

    /** Scroll the first scrollable container, falling back to a swipe gesture. */
    suspend fun scroll(direction: String, target: String? = null): InputResult {
        service?.let { s ->
            if (direction.lowercase() in setOf("up", "down", "forward", "backward") && s.scrollNode(direction, target)) {
                return ok("Scrolled $direction")
            }
        }
        return scrollAt(if (direction == "forward") "down" else if (direction == "backward") "up" else direction)
    }

    // ─── Node targets ────────────────────────────────────────────

    suspend fun clickText(text: String): InputResult {
        val s = service ?: return fail("Accessibility service is not running")
        s.findTextTarget(text)?.let { pointAt(it.exactCenterX(), it.exactCenterY(), "Click") ; s.overlay.click(it.exactCenterX(), it.exactCenterY()) }
        return if (s.clickByText(text)) ok("Clicked \"$text\"") else fail("Could not find \"$text\" to click")
    }

    suspend fun clickNode(node: UiNode): InputResult = tap(node.bounds.exactCenterX(), node.bounds.exactCenterY())

    // ─── Virtual keyboard ────────────────────────────────────────

    /** Replaces the field's text (like typing after select-all). */
    suspend fun typeText(text: String, fieldHint: String? = null): InputResult {
        val s = service
        s?.editableTarget(fieldHint)?.let { pointAt(it.exactCenterX(), it.exactCenterY(), "Typing") }
        if (s?.setText(text, fieldHint) != null) return ok("Typed \"$text\"")

        // Fields that ignore ACTION_SET_TEXT: type through an input method.
        if (commitViaInputMethod(text, clearFirst = true)) return ok("Typed \"$text\" with the virtual keyboard")

        if (shell.isReady) {
            if (shell.succeeded("input text ${adbText(text)}")) return ok("Typed \"$text\" via ADB")
        }
        return fail("Could not type text: no focused text field")
    }

    /** Types at the cursor without clearing (like pressing keys). */
    suspend fun keyboardType(text: String): InputResult {
        service?.editableTarget(null)?.let { pointAt(it.exactCenterX(), it.exactCenterY(), "Typing") }
        if (commitViaInputMethod(text, clearFirst = false)) return ok("Typed \"$text\"")
        if (shell.isReady && shell.succeeded("input text ${adbText(text)}")) return ok("Typed \"$text\" via ADB")
        // Last resort: append through the accessibility tree.
        service?.let { s ->
            val current = s.dumpScreen().firstOrNull { it.isEditable && it.isFocused }?.text.orEmpty()
            if (s.setText(current + text, null) != null) return ok("Typed \"$text\"")
        }
        return fail("No text field is focused")
    }

    private fun commitViaInputMethod(text: String, clearFirst: Boolean): Boolean {
        val s = service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && s != null && s.canUseInputMethod) {
            if (clearFirst) s.imeClear()
            return s.imeCommitText(text)
        }
        return false
    }

    /** Presses a key, optionally with modifiers: key("a", listOf("ctrl")). */
    suspend fun key(name: String, modifiers: List<String> = emptyList()): InputResult {
        val lower = name.trim().lowercase()
        // System navigation keys map onto global actions.
        service?.let { s ->
            if (modifiers.isEmpty()) {
                val global = when (lower) {
                    "back" -> AccessibilityService.GLOBAL_ACTION_BACK
                    "home_screen", "system_home" -> AccessibilityService.GLOBAL_ACTION_HOME
                    "recents", "app_switch" -> AccessibilityService.GLOBAL_ACTION_RECENTS
                    "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                    else -> null
                }
                if (global != null) return if (s.global(global)) ok("Pressed $lower") else fail("Could not press $lower")
            }
        }
        val code = keyCode(lower) ?: return fail("Unknown key \"$name\"")
        val meta = modifiers.fold(0) { acc, m -> acc or metaFor(m) }
        label(listOf(*modifiers.toTypedArray(), lower).joinToString("+"))

        val s = service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && s != null && s.canUseInputMethod && s.imeKey(code, meta)) {
            return ok("Pressed ${describe(lower, modifiers)}")
        }
        if (shell.isReady) {
            val command = if (modifiers.isEmpty()) "input keyevent $code"
            else "input keycombination ${modifiers.mapNotNull { modifierKeyCode(it) }.joinToString(" ")} $code"
            if (shell.succeeded(command)) return ok("Pressed ${describe(lower, modifiers)} via ADB")
        }
        if (code == KeyEvent.KEYCODE_ENTER && modifiers.isEmpty()) return pressEnter()
        return fail("No keyboard backend available for ${describe(lower, modifiers)}")
    }

    /** Submits the focused field: IME action, keyboard action key, ADB, then a tap on the keyboard. */
    suspend fun pressEnter(): InputResult {
        val s = service
        if (s != null && s.imeEnterOnFocused()) return ok("Submitted the focused field")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && s != null && s.canUseInputMethod && s.imeEditorAction()) {
            return ok("Submitted the focused field")
        }
        if (shell.isReady && shell.succeeded("input keyevent 66")) return ok("Pressed Enter via ADB")
        s?.inputMethodWindowBounds()?.let { b: Rect ->
            // Action key sits bottom-right on nearly every keyboard layout.
            return tap(b.right - b.width() * 0.10f, b.bottom - b.height() * 0.14f)
        }
        return fail("Could not submit the focused field")
    }

    suspend fun global(action: String): InputResult {
        val s = service
        val code = when (action) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "screenshot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            else -> return fail("Unknown global action $action")
        }
        if (s != null && s.global(code)) return ok("Pressed $action")
        val keyEvent = when (action) {
            "back" -> 4
            "home" -> 3
            "recents" -> 187
            "lock_screen" -> 26
            "screenshot" -> 120
            else -> null
        }
        if (keyEvent != null && shell.isReady && shell.succeeded("input keyevent $keyEvent")) return ok("Pressed $action via ADB")
        if (action == "notifications" && shell.isReady && shell.succeeded("cmd statusbar expand-notifications")) {
            return ok("Opened notifications via ADB")
        }
        if (action == "quick_settings" && shell.isReady && shell.succeeded("cmd statusbar expand-settings")) {
            return ok("Opened quick settings via ADB")
        }
        return fail("Could not press $action")
    }

    // ─── Helpers ─────────────────────────────────────────────────

    private fun stroke(x1: Float, y1: Float, x2: Float, y2: Float, start: Long, duration: Long): GestureDescription {
        val path = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, start, duration.coerceAtLeast(1)))
            .build()
    }

    private fun ok(message: String) = InputResult(true, message)
    private fun fail(message: String) = InputResult(false, message)

    private fun describe(key: String, modifiers: List<String>) =
        (modifiers.map { it.lowercase() } + key).joinToString("+")

    companion object {
        /**
         * Argument for `input text`: it reads "%s" as a space, and the command
         * runs through exactly one `sh -c`, so single quoting is enough.
         */
        fun adbText(text: String): String =
            "'" + text.replace(" ", "%s").replace("'", "'\\''") + "'"

        fun keyCode(name: String): Int? = when (name) {
            "enter", "return" -> KeyEvent.KEYCODE_ENTER
            "tab" -> KeyEvent.KEYCODE_TAB
            "space" -> KeyEvent.KEYCODE_SPACE
            "backspace", "delete", "del" -> KeyEvent.KEYCODE_DEL
            "forward_delete" -> KeyEvent.KEYCODE_FORWARD_DEL
            "escape", "esc" -> KeyEvent.KEYCODE_ESCAPE
            "up", "arrow_up" -> KeyEvent.KEYCODE_DPAD_UP
            "down", "arrow_down" -> KeyEvent.KEYCODE_DPAD_DOWN
            "left", "arrow_left" -> KeyEvent.KEYCODE_DPAD_LEFT
            "right", "arrow_right" -> KeyEvent.KEYCODE_DPAD_RIGHT
            "home" -> KeyEvent.KEYCODE_MOVE_HOME
            "end" -> KeyEvent.KEYCODE_MOVE_END
            "page_up", "pageup" -> KeyEvent.KEYCODE_PAGE_UP
            "page_down", "pagedown" -> KeyEvent.KEYCODE_PAGE_DOWN
            "search" -> KeyEvent.KEYCODE_SEARCH
            "menu" -> KeyEvent.KEYCODE_MENU
            "volume_up" -> KeyEvent.KEYCODE_VOLUME_UP
            "volume_down" -> KeyEvent.KEYCODE_VOLUME_DOWN
            "media_play_pause", "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "media_next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "media_previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "copy" -> KeyEvent.KEYCODE_COPY
            "paste" -> KeyEvent.KEYCODE_PASTE
            "cut" -> KeyEvent.KEYCODE_CUT
            else -> when {
                name.length == 1 && name[0] in 'a'..'z' -> KeyEvent.KEYCODE_A + (name[0] - 'a')
                name.length == 1 && name[0] in '0'..'9' -> KeyEvent.KEYCODE_0 + (name[0] - '0')
                name.matches(Regex("f([1-9]|1[0-2])")) -> KeyEvent.KEYCODE_F1 + name.drop(1).toInt() - 1
                name.startsWith("keycode_") -> KeyEvent.keyCodeFromString(name.uppercase()).takeIf { it != KeyEvent.KEYCODE_UNKNOWN }
                else -> name.toIntOrNull()
            }
        }

        private fun metaFor(modifier: String): Int = when (modifier.lowercase()) {
            "ctrl", "control" -> KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
            "shift" -> KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
            "alt" -> KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
            "meta", "cmd", "win" -> KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
            else -> 0
        }

        private fun modifierKeyCode(modifier: String): Int? = when (modifier.lowercase()) {
            "ctrl", "control" -> KeyEvent.KEYCODE_CTRL_LEFT
            "shift" -> KeyEvent.KEYCODE_SHIFT_LEFT
            "alt" -> KeyEvent.KEYCODE_ALT_LEFT
            "meta", "cmd", "win" -> KeyEvent.KEYCODE_META_LEFT
            else -> null
        }
    }
}
