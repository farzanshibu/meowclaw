package com.farzanshibu.meowclaw.agent

import com.farzanshibu.meowclaw.AgentAccessibilityService
import com.farzanshibu.meowclaw.UiNode
import com.farzanshibu.meowclaw.data.SkillMemory
import com.farzanshibu.meowclaw.llm.ScreenImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Turns the accessibility tree into compact text the LLM can act on. */
class ScreenReader {
    /** Nodes from the latest dump, so actions can target an element by its index. */
    @Volatile var lastNodes: List<UiNode> = emptyList()
        private set

    private val service get() = AgentAccessibilityService.instance

    suspend fun currentPackage(): String? = withContext(Dispatchers.Default) { service?.currentPackage() }

    suspend fun describe(task: String?, compressed: Boolean): String = withContext(Dispatchers.Default) {
        val s = service ?: return@withContext NOT_RUNNING
        val nodes = s.dumpScreen()
        lastNodes = nodes
        if (nodes.isEmpty()) return@withContext "Could not read screen. Make sure accessibility service is enabled."
        val pkg = s.currentPackage()
        if (compressed) compressed(nodes, pkg, task.orEmpty()) else full(nodes, pkg)
    }

    fun node(index: Int): UiNode? = lastNodes.firstOrNull { it.index == index }

    /** JPEG of the screen for vision models (Android 11+), without the agent overlay. */
    suspend fun screenshot(cacheDir: java.io.File): ScreenImage? {
        val s = service ?: return null
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        s.overlay.setHidden(true)
        val bytes = try { s.screenshotJpeg() } finally { s.overlay.setHidden(false) }
        bytes ?: return null
        return withContext(Dispatchers.IO) {
            val file = java.io.File(cacheDir, "screen.jpg").apply { writeBytes(bytes) }
            ScreenImage(file)
        }
    }

    companion object {
        const val NOT_RUNNING = "Accessibility service is not running."

        fun full(nodes: List<UiNode>, pkg: String?): String = buildString {
            pkg?.let { appendLine("Current app: $it") }
            appendLine("Screen elements:")
            for (node in nodes) {
                var label = node.text.ifEmpty { node.contentDescription }
                if (label.isEmpty() && !node.isClickable && !node.isEditable && !node.isScrollable) continue
                if (label.length > 200) label = label.take(200) + "..."
                val tags = buildList {
                    if (node.isClickable) add("clickable")
                    if (node.isEditable) add("editable")
                    if (node.isScrollable) add("scrollable")
                    if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                    if (node.isFocused) add("focused")
                }
                val b = node.bounds
                append("  [${node.index}] [${node.className}] ")
                append(if (label.isNotEmpty()) "\"$label\"" else "(no text)")
                if (tags.isNotEmpty()) append(" {${tags.joinToString(", ")}}")
                appendLine(" bounds:[${b.left},${b.top},${b.right},${b.bottom}] center:(${b.centerX()},${b.centerY()})")
            }
        }

        private val STATUS_NOISE = Regex("^\\d{1,2}:\\d{2}$")

        fun compressed(nodes: List<UiNode>, pkg: String?, task: String): String = buildString {
            pkg?.let { appendLine("APP: $it") }
            val keywords = SkillMemory.keywords(task)
            for (node in nodes) {
                var label = node.text.ifEmpty { node.contentDescription }
                val lower = label.lowercase()
                if ("battery" in lower || "percent" in lower || "do not disturb" in lower ||
                    "three bars" in lower || STATUS_NOISE.matches(lower)
                ) continue
                if (label.isEmpty() && !node.isClickable && !node.isEditable && !node.isScrollable) continue
                if (label.length > 50) label = label.take(50) + "..."
                val tags = buildList {
                    if (node.isClickable) add("tap")
                    if (node.isEditable) add("edit")
                    if (node.isScrollable) add("scroll")
                    if (node.isCheckable) add(if (node.isChecked) "on" else "off")
                    if (node.isFocused) add("focus")
                }
                val type = when (node.className) {
                    "TextView" -> "text"
                    "Button" -> "btn"
                    "Switch" -> "toggle"
                    "ImageView" -> "img"
                    "EditText" -> "input"
                    "FrameLayout", "LinearLayout" -> "view"
                    else -> node.className.lowercase()
                }
                val target = if (label.isNotEmpty() && keywords.any { it in lower }) "*" else ""
                val line = "[${node.index}]$target $type ${if (label.isNotEmpty()) "\"$label\"" else ""} " +
                    "${if (tags.isNotEmpty()) "[${tags.joinToString(",")}]" else ""} " +
                    "center:(${node.bounds.centerX()},${node.bounds.centerY()})"
                appendLine(line.trim().replace(Regex("\\s+"), " "))
            }
        }
    }
}

data class RecoveryAction(val action: String, val direction: String = "down", val description: String)

/** Picks a cheap recovery move after a failed step, based on what is on screen. */
object RecoveryEngine {
    fun diagnose(lastFailedAction: String, screen: String): RecoveryAction {
        val lower = screen.lowercase()
        if (listOf("loading", "progress", "spinner", "wait").any { it in lower }) {
            return RecoveryAction("wait", description = "App seems to be loading, waiting...")
        }
        if ("gboard" in lower || "keyboard" in lower) {
            return RecoveryAction("press_back", description = "Keyboard might be blocking the screen, dismissing it.")
        }
        if (lastFailedAction in setOf("click_text", "click_at", "click_element", "double_click", "long_press")) {
            return if ("scrollable" in lower || "[scroll" in lower || ",scroll" in lower) {
                RecoveryAction("scroll", "down", "Click failed, trying to scroll down to find the target.")
            } else {
                RecoveryAction("press_back", description = "Click failed and not scrollable, pressing back to retry from previous screen.")
            }
        }
        if (lastFailedAction == "open_app") {
            return RecoveryAction("press_home", description = "Failed to open app, going home to try a different approach.")
        }
        return RecoveryAction("press_back", description = "Unknown failure, pressing back to recover.")
    }
}
