package com.ambix.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.atomic.AtomicBoolean

/** Ejecuta gestos del sistema solo tras habilitar el servicio manualmente en Ajustes. */
class GameAutomationService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        connected.set(true)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onDestroy() {
        if (instance === this) instance = null
        connected.set(false)
        super.onDestroy()
    }
    fun tap(x: Float, y: Float, durationMs: Long = 55L, callback: ((Boolean) -> Unit)? = null) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) { callback?.invoke(false); return }
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(
            GestureDescription.StrokeDescription(path, 0L, durationMs.coerceIn(35L, 500L))
        ).build()
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { callback?.invoke(true) }
            override fun onCancelled(gestureDescription: GestureDescription?) { callback?.invoke(false) }
        }, null)
        if (!accepted) callback?.invoke(false)
    }
    companion object {
        @Volatile var instance: GameAutomationService? = null
            private set
        val connected = AtomicBoolean(false)
    }
}