package com.assistant.fishingclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class FishingAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: FishingAccessibilityService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun performTap(x: Float, y: Float, durationMs: Long = 60L): Boolean {
        if (x < 0f || y < 0f) return false
        return try {
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(35L, 200L))
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            false
        }
    }

    fun performHold(x: Float, y: Float, holdDurationMs: Long): Boolean {
        if (x < 0f || y < 0f) return false
        return try {
            val safeDuration = holdDurationMs.coerceIn(45L, 900L)
            val path = Path().apply { moveTo(x, y) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, safeDuration)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            false
        }
    }
}
