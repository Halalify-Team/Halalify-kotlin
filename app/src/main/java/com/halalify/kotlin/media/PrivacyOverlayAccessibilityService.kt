package com.halalify.kotlin.media

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Provides a trusted Android window for Halalify's opaque privacy regions.
 *
 * The service does not inspect accessibility nodes or react to events. It only
 * lets the privacy renderer use a trusted overlay that may be fully opaque
 * while keeping touch and scrolling available to the app underneath.
 */
internal class PrivacyOverlayAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        TrustedOverlayHost.connect(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        TrustedOverlayHost.disconnect()
        super.onDestroy()
    }
}

/** Connected accessibility service used to create trusted overlay windows. */
internal object TrustedOverlayHost {
    @Volatile
    private var service: PrivacyOverlayAccessibilityService? = null

    val isConnected: Boolean
        get() = service?.let { true } ?: false

    fun currentService(): PrivacyOverlayAccessibilityService? = service

    fun connect(accessibilityService: PrivacyOverlayAccessibilityService) {
        service = accessibilityService
    }

    fun disconnect() {
        service = null
    }
}
