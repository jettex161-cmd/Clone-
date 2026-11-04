package com.pixelagent.app.platform

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.SparseArray
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.pixelagent.app.core.ScreenController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Optional "phone control": lets the agent read the screen and tap/type/scroll in any app.
 * It only does anything while the agent calls a screen_* tool, and must be enabled by you in
 * Android Settings -> Accessibility.
 */
class PixelAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile
        var instance: PixelAccessibilityService? = null
    }

    val controller: ScreenController by lazy { A11yController(this) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }
}

private class A11yController(private val svc: AccessibilityService) : ScreenController {
    @Volatile
    private var nodes = SparseArray<AccessibilityNodeInfo>()

    private fun settle() {
        try {
            Thread.sleep(650)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun root(): AccessibilityNodeInfo? {
        for (i in 0 until 4) {
            val r = svc.rootInActiveWindow
            if (r != null) return r
            try {
                Thread.sleep(300)
            } catch (e: InterruptedException) {
                return null
            }
        }
        return null
    }

    private fun screenSize(): Pair<Int, Int> {
        val dm = svc.resources.displayMetrics
        return Pair(dm.widthPixels, dm.heightPixels)
    }

    override fun read(): String {
        val root = root()
            ?: return "The screen content is not available (lock screen, secure window, or the app blocks accessibility). Try press_key home, or wait and retry."
        val (w, h) = screenSize()
        val sb = StringBuilder("App: ${root.packageName} | Screen: ${w}x$h px\n")
        val map = SparseArray<AccessibilityNodeInfo>()
        var counter = 0
        val rect = Rect()

        fun walk(node: AccessibilityNodeInfo, depth: Int) {
            if (counter >= 300 || depth > 40) return
            if (!node.isVisibleToUser) return
            val text = node.text?.toString()?.trim().orEmpty()
            val desc = node.contentDescription?.toString()?.trim().orEmpty()
            val hint = if (Build.VERSION.SDK_INT >= 26) node.hintText?.toString()?.trim().orEmpty() else ""
            val label = listOf(text, desc).filter { it.isNotEmpty() }.distinct().joinToString(" / ")
            val interactive = node.isClickable || node.isLongClickable || node.isEditable || node.isCheckable || node.isScrollable
            if (label.isNotEmpty() || hint.isNotEmpty() || interactive) {
                counter++
                map.put(counter, node)
                node.getBoundsInScreen(rect)
                val cls = node.className?.toString()?.substringAfterLast('.') ?: "View"
                val flags = ArrayList<String>()
                if (node.isClickable) flags.add("clickable")
                if (node.isEditable) flags.add("editable")
                if (node.isCheckable) flags.add(if (node.isChecked) "checked" else "unchecked")
                if (node.isScrollable) flags.add("scrollable")
                if (node.isFocused) flags.add("focused")
                if (!node.isEnabled) flags.add("disabled")
                sb.append("[").append(counter).append("] ").append(cls)
                if (label.isNotEmpty()) sb.append(" \"").append(label.take(100).replace("\n", " ")).append("\"")
                if (hint.isNotEmpty() && node.isEditable) sb.append(" hint=\"").append(hint.take(60)).append("\"")
                if (flags.isNotEmpty()) sb.append(" {").append(flags.joinToString(",")).append("}")
                sb.append(" @(").append(rect.centerX()).append(",").append(rect.centerY()).append(")\n")
            }
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                walk(c, depth + 1)
            }
        }
        walk(root, 0)
        nodes = map
        if (counter == 0) sb.append("(no readable elements; the app may draw its own UI, e.g. a game or video. Use screen_tap_xy.)\n")
        if (counter >= 300) sb.append("(list truncated at 300 elements; scroll for more)\n")
        return sb.toString().trim()
    }

    private fun clickableAncestor(start: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = start
        var depth = 0
        while (cur != null && depth < 8) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    override fun clickById(id: Int): String {
        val node = nodes.get(id) ?: return "Unknown id $id. Call screen_read again (ids change whenever the screen changes)."
        node.refresh()
        val target = clickableAncestor(node)
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            settle()
            return "Clicked element $id."
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        return tap(r.centerX(), r.centerY())
    }

    override fun clickByText(text: String): String {
        val root = root() ?: return "Screen content is not available."
        val matches = root.findAccessibilityNodeInfosByText(text)
        if (matches.isNullOrEmpty()) return "No element with text \"$text\" found. Call screen_read to see what is on screen."
        val best = matches.firstOrNull { it.text?.toString()?.equals(text, ignoreCase = true) == true } ?: matches[0]
        val target = clickableAncestor(best)
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            settle()
            return "Clicked \"$text\"."
        }
        val r = Rect()
        best.getBoundsInScreen(r)
        return tap(r.centerX(), r.centerY())
    }

    private fun dispatch(path: Path, durationMs: Long, done: String): String {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        val latch = CountDownLatch(1)
        var completed = false
        val accepted = svc.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                latch.countDown()
            }
        }, null)
        if (!accepted) return "The system rejected the gesture."
        latch.await(5, TimeUnit.SECONDS)
        settle()
        return if (completed) done else "The gesture was cancelled by the system."
    }

    override fun tap(x: Int, y: Int): String {
        val (w, h) = screenSize()
        if (x < 0 || y < 0 || x > w || y > h) return "Coordinates ($x,$y) are outside the screen (${w}x$h)."
        val p = Path()
        p.moveTo(x.toFloat(), y.toFloat())
        return dispatch(p, 60, "Tapped ($x,$y).")
    }

    override fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int): String {
        val (w, h) = screenSize()
        if (listOf(x1, x2).any { it < 0 || it > w } || listOf(y1, y2).any { it < 0 || it > h }) {
            return "Coordinates are outside the screen (${w}x$h)."
        }
        val p = Path()
        p.moveTo(x1.toFloat(), y1.toFloat())
        p.lineTo(x2.toFloat(), y2.toFloat())
        return dispatch(p, durationMs.toLong(), "Swiped ($x1,$y1) -> ($x2,$y2).")
    }

    private fun findScrollable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val f = findScrollable(c, depth + 1)
            if (f != null) return f
        }
        return null
    }

    override fun scroll(direction: String): String {
        val down = direction == "down"
        val root = root()
        val sc = if (root != null) findScrollable(root) else null
        if (sc != null) {
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (sc.performAction(action)) {
                settle()
                return "Scrolled $direction."
            }
        }
        val (w, h) = screenSize()
        return if (down) swipe(w / 2, (h * 0.7).toInt(), w / 2, (h * 0.3).toInt(), 350)
        else swipe(w / 2, (h * 0.3).toInt(), w / 2, (h * 0.7).toInt(), 350)
    }

    private fun findEditable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 30) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            val f = findEditable(c, depth + 1)
            if (f != null) return f
        }
        return null
    }

    override fun typeText(text: String, id: Int?): String {
        var target: AccessibilityNodeInfo? = null
        if (id != null) {
            val n = nodes.get(id) ?: return "Unknown id $id. Call screen_read again."
            target = if (n.isEditable) n else findEditable(n)
        }
        if (target == null) {
            val r = root() ?: return "Screen content is not available."
            target = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable } ?: findEditable(r)
        }
        if (target == null) return "No text field found. Click a text field first, then call screen_type."
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        settle()
        return if (ok) "Entered text." else "The field refused the text input."
    }

    override fun pressKey(key: String): String {
        val ok: Boolean = when (key) {
            "back" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            "recents" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "notifications" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            "quick_settings" -> svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            "lock_screen" -> {
                if (Build.VERSION.SDK_INT < 28) return "lock_screen needs Android 9 or newer."
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            }
            "screenshot" -> {
                if (Build.VERSION.SDK_INT < 28) return "screenshot needs Android 9 or newer."
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            }
            "enter" -> {
                if (Build.VERSION.SDK_INT < 30) return "The enter key needs Android 11+. Click the on-screen search/send button instead."
                val r = root() ?: return "Screen content is not available."
                val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(r)
                    ?: return "No focused text field to send Enter to."
                f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            else -> return "Unknown key '$key'."
        }
        settle()
        return if (ok) "Pressed $key." else "The system did not perform '$key'."
    }
}
