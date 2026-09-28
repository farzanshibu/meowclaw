package com.farzanshibu.meowclaw

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import androidx.annotation.RequiresApi
import com.farzanshibu.meowclaw.overlay.AgentOverlay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** A visible, meaningful node from the accessibility tree. */
data class UiNode(
    val index: Int,
    val text: String,
    val contentDescription: String,
    val className: String,
    val viewId: String,
    val isClickable: Boolean,
    val isEditable: Boolean,
    val isScrollable: Boolean,
    val isCheckable: Boolean,
    val isChecked: Boolean,
    val isFocused: Boolean,
    val bounds: Rect,
    val depth: Int,
)

/**
 * Reads the screen and drives other apps: node actions, dispatched gestures
 * (the virtual mouse) and, on Android 13+, the accessibility input method
 * (a virtual keyboard that types into whatever field has focus).
 */
class AgentAccessibilityService : AccessibilityService() {

    lateinit var overlay: AgentOverlay
        private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        overlay = AgentOverlay(this)
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        if (::overlay.isInitialized) overlay.dispose()
        super.onDestroy()
    }

    // ─── Screen reading ──────────────────────────────────────────

    private inline fun <T> forEachForeignRoot(block: (AccessibilityNodeInfo) -> T?): T? {
        val roots = windows?.takeIf { it.isNotEmpty() }?.mapNotNull { it.root }
            ?: listOfNotNull(rootInActiveWindow)
        for (root in roots) {
            if (root.packageName?.toString() == packageName) continue
            block(root)?.let { return it }
        }
        return null
    }

    fun dumpScreen(): List<UiNode> {
        val nodes = mutableListOf<UiNode>()
        forEachForeignRoot<Unit> { traverse(it, nodes, 0); null }
        return nodes
    }

    private fun traverse(node: AccessibilityNodeInfo, out: MutableList<UiNode>, depth: Int) {
        val rect = Rect().also(node::getBoundsInScreen)
        val visible = node.isVisibleToUser && rect.width() > 0 && rect.height() > 0
        if (visible) {
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable || node.isEditable || node.isScrollable) {
                out += UiNode(
                    index = out.size,
                    text = text,
                    contentDescription = desc,
                    className = node.className?.toString().orEmpty().substringAfterLast('.'),
                    viewId = node.viewIdResourceName.orEmpty(),
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    isScrollable = node.isScrollable,
                    isCheckable = node.isCheckable,
                    isChecked = node.isChecked,
                    isFocused = node.isFocused,
                    bounds = rect,
                    depth = depth,
                )
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { traverse(it, out, depth + 1) }
        }
    }

    /** Foreground app package, preferring the active application window. */
    fun currentPackage(): String? {
        var ownSeen = false
        var fallback: String? = null
        for (window in windows.orEmpty()) {
            if (window.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val pkg = window.root?.packageName?.toString() ?: continue
            if (window.isActive || window.isFocused) return pkg
            if (pkg == packageName) ownSeen = true else if (fallback == null) fallback = pkg
        }
        return fallback ?: if (ownSeen) packageName else null
    }

    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun screenshotJpeg(): ByteArray? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val full = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                buffer.close()
                // Vision models downscale anyway; 720 px wide keeps text legible and tokens low.
                val bitmap = full?.let {
                    if (it.width <= 720) it
                    else Bitmap.createScaledBitmap(it, 720, it.height * 720 / it.width, true).also { _ -> it.recycle() }
                }
                val bytes = bitmap?.let {
                    ByteArrayOutputStream().use { out ->
                        it.compress(Bitmap.CompressFormat.JPEG, 60, out)
                        out.toByteArray()
                    }
                }
                if (cont.isActive) cont.resume(bytes)
            }

            override fun onFailure(errorCode: Int) {
                if (cont.isActive) cont.resume(null)
            }
        })
    }

    // ─── Node actions ────────────────────────────────────────────

    /** Finds a node by visible text; returns its centre so the caller can animate the cursor first. */
    fun findTextTarget(target: String): Rect? = forEachForeignRoot { root ->
        // Prefer real buttons/suggestions over an editable field echoing the same query.
        findNode(root, target, exact = true, skipEditable = true)
            ?: findNode(root, target, exact = false, skipEditable = true)
            ?: findNode(root, target, exact = true, skipEditable = false)
            ?: findNode(root, target, exact = false, skipEditable = false)
    }?.let { Rect().also(it::getBoundsInScreen) }

    fun clickByText(target: String): Boolean = forEachForeignRoot { root ->
        val node = findNode(root, target, true, true) ?: findNode(root, target, false, true)
            ?: findNode(root, target, true, false) ?: findNode(root, target, false, false)
        node?.let { if (clickNodeOrParent(it)) true else null }
    } ?: false

    private fun findNode(node: AccessibilityNodeInfo, target: String, exact: Boolean, skipEditable: Boolean): AccessibilityNodeInfo? {
        val text = node.text?.toString().orEmpty()
        val desc = node.contentDescription?.toString().orEmpty()
        val matches = if (exact) text.equals(target, true) || desc.equals(target, true)
        else text.contains(target, true) || desc.contains(target, true)
        if (matches && (!skipEditable || !node.isEditable) && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNode(child, target, exact, skipEditable)?.let { return it }
        }
        return null
    }

    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        while (target != null && !target.isClickable) target = target.parent
        if (target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return true
        val rect = Rect().also(node::getBoundsInScreen)
        return !rect.isEmpty && tapNow(rect.exactCenterX(), rect.exactCenterY())
    }

    /** Sets text on an editable field matching [hint] (or the focused/first one). */
    fun setText(text: String, hint: String?): Rect? = forEachForeignRoot { root ->
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && hint.isNullOrBlank() }
            ?: findEditable(root, hint) ?: (if (!hint.isNullOrBlank()) findEditable(root, null) else null)
        node?.let {
            it.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            if (it.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) Rect().also(it::getBoundsInScreen) else null
        }
    }

    /** Bounds of the editable field the agent would type into, for cursor placement. */
    fun editableTarget(hint: String?): Rect? = forEachForeignRoot { root ->
        (root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable && hint.isNullOrBlank() }
            ?: findEditable(root, hint) ?: findEditable(root, null))?.let { Rect().also(it::getBoundsInScreen) }
    }

    private fun findEditable(node: AccessibilityNodeInfo, hint: String?): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser) {
            if (hint.isNullOrBlank()) return node
            val fields = listOf(node.text, node.contentDescription, node.hintText, node.viewIdResourceName)
            if (fields.any { it?.toString()?.contains(hint, true) == true }) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditable(child, hint)?.let { return it }
        }
        return null
    }

    fun scrollNode(direction: String, target: String?): Boolean = forEachForeignRoot { root ->
        findScrollable(root, target)?.let {
            val action = when (direction.lowercase()) {
                "up", "backward", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            }
            if (it.performAction(action)) true else null
        }
    } ?: false

    private fun findScrollable(node: AccessibilityNodeInfo, target: String?): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isVisibleToUser) {
            if (target == null) return node
            if (node.text?.contains(target, true) == true || node.contentDescription?.contains(target, true) == true) return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findScrollable(child, target)?.let { return it }
        }
        return null
    }

    /** IME "enter" on the focused field, then a visible keyboard action key. */
    fun imeEnterOnFocused(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val submitted = forEachForeignRoot { root ->
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    ?.takeIf { it }
            }
            if (submitted == true) return true
        }
        for (window in windows.orEmpty()) {
            val root = window.root ?: continue
            val node = findKeyboardAction(root)
            if (node != null && clickNodeOrParent(node)) return true
        }
        return false
    }

    private fun findKeyboardAction(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val label = (node.text?.toString().orEmpty().ifEmpty { node.contentDescription?.toString().orEmpty() })
            .trim().lowercase()
        if (node.isClickable && (label in KEYBOARD_ACTIONS || label.endsWith(" search"))) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findKeyboardAction(child)?.let { return it }
        }
        return null
    }

    fun inputMethodWindowBounds(): Rect? = windows.orEmpty()
        .firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        ?.let { Rect().also(it::getBoundsInScreen) }?.takeIf { !it.isEmpty }

    fun global(action: Int): Boolean = performGlobalAction(action)

    // ─── Gestures (virtual mouse) ────────────────────────────────

    private fun tapNow(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        return dispatchGesture(
            GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 60)).build(),
            null, null,
        )
    }

    /** Dispatches [gesture] and waits until the system reports it finished. */
    suspend fun perform(gesture: GestureDescription): Boolean = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { cont ->
            val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (cont.isActive) cont.resume(false)
                }
            }, null)
            if (!accepted && cont.isActive) cont.resume(false)
        }
    } ?: false

    // ─── Virtual keyboard (Android 13+) ──────────────────────────

    /** True when a text field has an input connection this service can type into. */
    val canUseInputMethod: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            inputMethod?.currentInputConnection != null && inputMethod?.currentInputStarted == true

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun imeCommitText(text: String): Boolean {
        val connection = inputMethod?.currentInputConnection ?: return false
        connection.commitText(text, 1, null)
        return true
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun imeKey(keyCode: Int, metaState: Int = 0): Boolean {
        val connection = inputMethod?.currentInputConnection ?: return false
        val now = android.os.SystemClock.uptimeMillis()
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
        connection.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, metaState))
        return true
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun imeEditorAction(): Boolean {
        val im = inputMethod ?: return false
        val connection = im.currentInputConnection ?: return false
        val action = (im.currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
        if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            return imeKey(KeyEvent.KEYCODE_ENTER)
        }
        connection.performEditorAction(action)
        return true
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun imeClear(): Boolean {
        val connection = inputMethod?.currentInputConnection ?: return false
        connection.performContextMenuAction(android.R.id.selectAll)
        connection.commitText("", 1, null)
        return true
    }

    companion object {
        @Volatile var instance: AgentAccessibilityService? = null
            private set

        fun isRunning(): Boolean = instance != null

        private val KEYBOARD_ACTIONS = setOf("search", "enter", "go", "done", "send", "next")
    }
}
