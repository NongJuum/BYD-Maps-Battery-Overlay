package com.byd.mapsoverlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal zero-overhead AccessibilityService whose sole purpose is providing
 * a TYPE_ACCESSIBILITY_OVERLAY window token (mBaseLayer=211000) so the BYD Overlay
 * stays cleanly above the opaque BYD StatusBar even in fullscreen apps like YouTube.
 */
class OverlayAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        try {
            startService(Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_UPDATE_CONFIG
            })
        } catch (_: Throwable) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Intentionally empty (0% CPU)
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: OverlayAccessibilityService? = null
            private set
    }
}
