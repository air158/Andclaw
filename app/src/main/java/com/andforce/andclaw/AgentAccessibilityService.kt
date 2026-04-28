package com.andforce.andclaw

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.DisplayMetrics
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import java.util.concurrent.Executor

@SuppressLint("AccessibilityPolicy")
class AgentAccessibilityService : AccessibilityService() {
    companion object {
        var instance: AgentAccessibilityService? = null
        private const val TAG = "AiAccessibility"
    }

    @Volatile var lastEventTimeMs: Long = 0L

    override fun onServiceConnected() { instance = this }

    fun markActionStart() {
        lastEventTimeMs = System.currentTimeMillis()
    }

    suspend fun waitForUiStable(maxWaitMs: Long = 2000L, stableWindowMs: Long = 200L) {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (System.currentTimeMillis() < deadline) {
            delay(50)
            if (System.currentTimeMillis() - lastEventTimeMs >= stableWindowMs) return
        }
    }

    fun captureScreenHierarchy(): String {
        val root = rootInActiveWindow ?: return "Empty Screen"
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val sw = metrics.widthPixels
        val sh = metrics.heightPixels
        val sb = StringBuilder()
        parseNode(root, sb, sw, sh)
        return sb.toString()
    }

    private fun parseNode(node: AccessibilityNodeInfo?, sb: StringBuilder, sw: Int = 9999, sh: Int = 9999) {
        node ?: return
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        val label = if (!text.isNullOrEmpty()) text else desc
        if (node.isClickable || !label.isNullOrEmpty()) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.right > 0 && rect.bottom > 0 && rect.left < sw && rect.top < sh) {
                val cx = (rect.left + rect.right) / 2
                val cy = (rect.top + rect.bottom) / 2
                sb.append("{")
                if (!label.isNullOrEmpty()) sb.append("t:'${label.replace("'", "\\'")}',")
                sb.append("xy:[$cx,$cy]")
                if (node.isClickable) sb.append(",c:1")
                if (node.isEditable) sb.append(",e:1")
                if (node.isFocused) sb.append(",f:1")
                // resource-id local name helps LLM distinguish nav buttons from content items
                val resId = node.viewIdResourceName
                if (!resId.isNullOrEmpty()) {
                    val local = resId.substringAfter("/", "")
                    if (local.isNotEmpty() && !local.all { it.isDigit() }) sb.append(",id:$local")
                }
                // class name helps LLM distinguish ImageButton (icon) from TextView (text)
                if (node.isClickable) {
                    val cls = node.className?.toString()
                    if (!cls.isNullOrEmpty()) sb.append(",cls:${cls.substringAfterLast('.')}")
                }
                sb.append("}\n")
            }
        }
        for (i in 0 until node.childCount) parseNode(node.getChild(i), sb, sw, sh)
    }

    fun click(x: Int, y: Int) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        dispatchGesture(gesture, null, null)
    }

    fun swipe(startX: Int, startY: Int, endX: Int, endY: Int, durationMs: Long = 300) {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        dispatchGesture(gesture, null, null)
    }

    fun longPress(x: Int, y: Int, durationMs: Long = 1000) {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs)).build()
        dispatchGesture(gesture, null, null)
    }

    private val browserPackages = setOf(
        "com.android.chrome", "com.chrome.beta", "com.chrome.dev",
        "org.mozilla.firefox", "org.mozilla.fenix",
        "com.microsoft.emmx", "com.opera.browser", "com.brave.browser",
        "com.UCMobile", "com.quark.browser", "com.tencent.mtt",
        "mark.via", "org.nicoco.nicobrowser", "com.explore.web.browser",
        "com.vivaldi.browser", "com.sec.android.app.sbrowser"
    )

    fun isWebViewContext(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (isBrowserPackage(root.packageName?.toString())) return true
        return containsWebView(root)
    }

    private fun isBrowserPackage(pkg: String?): Boolean =
        pkg != null && browserPackages.contains(pkg)

    private fun containsWebView(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 15) return false
        val cls = node.className?.toString() ?: ""
        if (cls.contains("WebView", ignoreCase = true)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (containsWebView(child, depth + 1)) return true
        }
        return false
    }

    fun inputText(text: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }

        val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedNode != null) {
            if (focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
        }

        val root = rootInActiveWindow
        if (root != null) {
            val editableNode = findEditableNode(root)
            if (editableNode != null) {
                if (!editableNode.isFocused) editableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return true
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("input", text))
                if (editableNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
            }
        }

        val anyFocused = focusedNode ?: findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (anyFocused != null) {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("input", text))
            if (anyFocused.performAction(AccessibilityNodeInfo.ACTION_PASTE)) return true
        }
        return false
    }

    private fun findEditableNode(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 20) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child, depth + 1)
            if (result != null) return result
        }
        return null
    }

    fun globalAction(action: Int): Boolean = performGlobalAction(action)

    fun captureScreenshot(callback: (Bitmap?) -> Unit) {
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            Executor { it.run() },
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(
                        screenshot.hardwareBuffer, screenshot.colorSpace
                    )
                    screenshot.hardwareBuffer.close()
                    callback(bitmap)
                }

                override fun onFailure(errorCode: Int) {
                    Log.e(TAG, "截屏失败, errorCode=$errorCode")
                    callback(null)
                }
            }
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) {
            lastEventTimeMs = System.currentTimeMillis()
        }
    }

    override fun onInterrupt() {}
}
