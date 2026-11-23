package com.qingsong.qishuiautomation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class QishuiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        AutomationState.log("无障碍服务已连接")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: QishuiAccessibilityService? = null

        fun isConnected(): Boolean = instance != null

        fun click(x: Int, y: Int): Boolean {
            val service = instance ?: return false
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 80L))
                .build()
            return service.dispatchGesture(gesture, null, null)
        }

        fun back(): Boolean =
            instance?.performGlobalAction(GLOBAL_ACTION_BACK) ?: false

        fun currentPackage(): String? =
            instance?.rootInActiveWindow?.packageName?.toString()

        fun findDescription(description: String, rect: ScreenRect): OcrHit? {
            val root = instance?.rootInActiveWindow ?: return null
            return findInTree(root, description, rect)
        }

        private fun findInTree(
            node: AccessibilityNodeInfo,
            description: String,
            rect: ScreenRect,
        ): OcrHit? {
            val nodeText = node.text?.toString().orEmpty()
            val nodeDescription = node.contentDescription?.toString().orEmpty()
            if (nodeDescription == description || nodeText == description) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val x = bounds.centerX()
                val y = bounds.centerY()
                if (rect.contains(x, y)) {
                    return OcrHit(description, x, y)
                }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                val found = findInTree(child, description, rect)
                if (found != null) return found
            }
            return null
        }
    }
}
