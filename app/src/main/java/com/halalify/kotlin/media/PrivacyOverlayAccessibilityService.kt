package com.halalify.kotlin.media

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import java.io.Closeable
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Provides a trusted Android window for Halalify's opaque privacy regions.
 *
 * The service never inspects accessibility nodes or text. It only observes
 * navigation and real scroll event types so stale overlay coordinates can be
 * discarded, and supplies a trusted fully-opaque pass-through window.
 */
internal class PrivacyOverlayAccessibilityService : AccessibilityService() {
    private var lastTrackedContentEventAtMs = Long.MIN_VALUE
    private var lastWindowPackageName: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        TrustedOverlayHost.connect(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val sourcePackageName = event.packageName
            ?.toString()
            ?.takeIf(String::isNotBlank)
            ?: return
        if (sourcePackageName == packageName) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (sourcePackageName == lastWindowPackageName) return
                lastWindowPackageName = sourcePackageName
                lastTrackedContentEventAtMs = event.eventTime
                TrustedOverlayHost.invalidateContent()
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastWindowPackageName = sourcePackageName
                val hasRealScrollDelta =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                        event.scrollDeltaX != 0 ||
                        event.scrollDeltaY != 0
                if (!hasRealScrollDelta) return
                val startsNewInteraction =
                    lastTrackedContentEventAtMs == Long.MIN_VALUE ||
                        event.eventTime - lastTrackedContentEventAtMs >= CONTENT_EVENT_QUIET_GAP_MS
                lastTrackedContentEventAtMs = event.eventTime
                if (startsNewInteraction) {
                    TrustedOverlayHost.invalidateContent()
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        TrustedOverlayHost.disconnect(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        TrustedOverlayHost.disconnect(this)
        super.onDestroy()
    }

    private companion object {
        // Web pages can emit a continuous stream of synthetic scroll events.
        // Treat only the first event after a quiet gap as a new interaction.
        // TYPE_WINDOW_CONTENT_CHANGED is intentionally omitted: image loading
        // and animated web pages emit it continuously and can otherwise starve
        // the clean-frame analysis. FrameActivityDetector covers those changes.
        const val CONTENT_EVENT_QUIET_GAP_MS = 350L
    }
}

/** Connected accessibility service used to create trusted overlay windows. */
internal object TrustedOverlayHost {
    @Volatile
    private var service: PrivacyOverlayAccessibilityService? = null
    private val contentInvalidationListeners = CopyOnWriteArraySet<() -> Unit>()

    val isConnected: Boolean
        get() = service?.let { true } ?: false

    fun currentService(): PrivacyOverlayAccessibilityService? = service

    fun connect(accessibilityService: PrivacyOverlayAccessibilityService) {
        service = accessibilityService
        invalidateContent()
    }

    fun disconnect(accessibilityService: PrivacyOverlayAccessibilityService) {
        if (service === accessibilityService) {
            service = null
            invalidateContent()
        }
    }

    fun subscribeToContentInvalidation(listener: () -> Unit): Closeable {
        contentInvalidationListeners += listener
        return object : Closeable {
            override fun close() {
                contentInvalidationListeners -= listener
            }
        }
    }

    fun invalidateContent() {
        contentInvalidationListeners.forEach { listener ->
            runCatching(listener)
        }
    }
}
