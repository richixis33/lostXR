package com.samrat.cardboardhands

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Key events go to the focused game, never to a background service.
 * An accessibility key filter is the only non-root way to see Joy-Con buttons while a game is in front.
 */
class JoyConInputService : AccessibilityService() {
    override fun onKeyEvent(event: KeyEvent): Boolean = JoyConButtons.onKey(event)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = JoyConButtons.clear()

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        JoyConButtons.clear()
        return super.onUnbind(intent)
    }

    companion object {
        fun isEnabled(context: android.content.Context): Boolean {
            val enabled = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabled.split(':').any {
                it.equals("${context.packageName}/${JoyConInputService::class.java.name}", true) ||
                    it.equals("${context.packageName}/.JoyConInputService", true)
            }
        }
    }
}
