package com.phoneagent.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentAccessibility"
        var instance: AgentAccessibilityService? = null
            private set

        fun isConnected(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "Accessibility service connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We process events on demand, not here
    }

    override fun onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.d(TAG, "Accessibility service destroyed")
    }

    // ========== PUBLIC ACTION METHODS ==========

    suspend fun tapByText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val node = findNodeByText(rootNode, text)
        if (node != null) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            return result
        }
        // Try finding by content description
        val nodeByDesc = findNodeByContentDescription(rootNode, text)
        if (nodeByDesc != null) {
            val result = nodeByDesc.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            nodeByDesc.recycle()
            return result
        }
        return false
    }

    suspend fun tapByDescription(description: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val node = findNodeByContentDescription(rootNode, description)
        if (node != null) {
            val result = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node.recycle()
            return result
        }
        return false
    }

    suspend fun tapAtCoordinates(x: Float, y: Float): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val path = Path()
            path.moveTo(x, y)

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)
        }
    }

    suspend fun typeText(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val focusedNode = findFocusedInputNode(rootNode)
        if (focusedNode != null) {
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val result = focusedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            focusedNode.recycle()
            return result
        }
        // Try finding any edit text
        val editText = findEditText(rootNode)
        if (editText != null) {
            editText.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Thread.sleep(300)
            val arguments = Bundle()
            arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val result = editText.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            editText.recycle()
            return result
        }
        return false
    }

    suspend fun scrollDown(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(rootNode)
        if (scrollable != null) {
            val result = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            scrollable.recycle()
            return result
        }
        // Fallback: gesture scroll
        return swipeVertical(800f, 200f)
    }

    suspend fun scrollUp(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val scrollable = findScrollableNode(rootNode)
        if (scrollable != null) {
            val result = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            scrollable.recycle()
            return result
        }
        return swipeVertical(200f, 800f)
    }

    suspend fun swipeLeft(): Boolean {
        return swipeHorizontal(900f, 100f)
    }

    suspend fun swipeRight(): Boolean {
        return swipeHorizontal(100f, 900f)
    }

    fun openApp(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager?.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                applicationContext.startActivity(launchIntent)
                true
            } else {
                Log.w(TAG, "No launch intent for package: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open app: $packageName", e)
            false
        }
    }

    fun pressBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun pressHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun pressRecents(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    fun getAllTextOnScreen(): String {
        val rootNode = rootInActiveWindow ?: return "Screen content unavailable"
        val textBuilder = StringBuilder()
        collectAllText(rootNode, textBuilder)
        return textBuilder.toString().trim()
    }

    // ========== PRIVATE HELPER METHODS ==========

    private fun findNodeByText(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        // Exact match first
        val exactResults = node.findAccessibilityNodeInfosByText(text)
        if (exactResults != null && exactResults.isNotEmpty()) {
            return exactResults[0]
        }
        // Case-insensitive search
        return findNodeByTextRecursive(node, text)
    }

    private fun findNodeByTextRecursive(node: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodeText = node.text?.toString() ?: ""
        if (nodeText.contains(text, ignoreCase = true) && node.isClickable) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByTextRecursive(child, text)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    private fun findNodeByContentDescription(node: AccessibilityNodeInfo, desc: String): AccessibilityNodeInfo? {
        val nodeDesc = node.contentDescription?.toString() ?: ""
        if (nodeDesc.contains(desc, ignoreCase = true) && (node.isClickable || node.isFocusable)) {
            return node
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findNodeByContentDescription(child, desc)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    private fun findFocusedInputNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findFocusedInputNode(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditText(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findScrollableNode(child)
            if (result != null) return result
            child.recycle()
        }
        return null
    }

    private fun collectAllText(node: AccessibilityNodeInfo, sb: StringBuilder) {
        val text = node.text?.toString()
        val desc = node.contentDescription?.toString()
        if (!text.isNullOrBlank()) sb.appendLine(text)
        else if (!desc.isNullOrBlank()) sb.appendLine(desc)

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectAllText(child, sb)
            child.recycle()
        }
    }

    private suspend fun swipeVertical(startY: Float, endY: Float): Boolean {
        val displayMetrics = resources.displayMetrics
        val centerX = displayMetrics.widthPixels / 2f

        return suspendCancellableCoroutine { continuation ->
            val path = Path()
            path.moveTo(centerX, startY)
            path.lineTo(centerX, endY)

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)
        }
    }

    private suspend fun swipeHorizontal(startX: Float, endX: Float): Boolean {
        val displayMetrics = resources.displayMetrics
        val centerY = displayMetrics.heightPixels / 2f

        return suspendCancellableCoroutine { continuation ->
            val path = Path()
            path.moveTo(startX, centerY)
            path.lineTo(endX, centerY)

            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
                .build()

            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    if (continuation.isActive) continuation.resume(false)
                }
            }, null)
        }
    }
}
