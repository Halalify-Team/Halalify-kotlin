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
 * The service never inspects page text or accessibility-node hierarchies. It
 * observes navigation and real scroll events, plus the accessibility label of
 * a clicked native Chrome toolbar button, so stale overlay coordinates can be
 * discarded. It also supplies a trusted fully-opaque pass-through window.
 */
internal class PrivacyOverlayAccessibilityService : AccessibilityService() {
    private var lastTrackedContentEventAtMs = Long.MIN_VALUE
    private var lastWindowPackageName: String? = null
    private var chromeTabOverviewActive = false

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
                val sourceClassName = event.className?.toString()
                val resumedAfterLeavingChromeOverview =
                    chromeTabOverviewActive &&
                        !isChromePackage(sourcePackageName) &&
                        sourceClassName?.endsWith("Activity") == true
                if (resumedAfterLeavingChromeOverview) {
                    chromeTabOverviewActive = false
                    TrustedOverlayHost.setContentAnalysisSuspended(false)
                }
                val discardExistingProtection =
                    shouldDiscardExistingProtectionForWindowState(
                        previousPackageName = lastWindowPackageName,
                        sourcePackageName = sourcePackageName,
                        sourceClassName = sourceClassName,
                    )
                lastWindowPackageName = sourcePackageName
                if (!discardExistingProtection) return
                lastTrackedContentEventAtMs = event.eventTime
                if (!resumedAfterLeavingChromeOverview) {
                    TrustedOverlayHost.invalidateContent(discardExistingProtection = true)
                }
            }

            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastWindowPackageName = sourcePackageName
                val hasRealScrollDelta =
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.P ||
                        event.scrollDeltaX != 0 ||
                        event.scrollDeltaY != 0
                if (!hasRealScrollDelta) return
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Accessibility reports how far the scroll position moved;
                    // visible content moves in the opposite direction.
                    TrustedOverlayHost.moveContentBy(
                        deltaX = -event.scrollDeltaX,
                        deltaY = -event.scrollDeltaY,
                    )
                }
                val startsNewInteraction =
                    lastTrackedContentEventAtMs == Long.MIN_VALUE ||
                        event.eventTime - lastTrackedContentEventAtMs >= CONTENT_EVENT_QUIET_GAP_MS
                lastTrackedContentEventAtMs = event.eventTime
                if (startsNewInteraction) {
                    TrustedOverlayHost.invalidateContent()
                }
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val sourceClassName = event.className?.toString()
                val contentDescription = event.contentDescription?.toString()
                if (
                    shouldSuspendAnalysisForBrowserOverviewClick(
                        sourcePackageName = sourcePackageName,
                        sourceClassName = sourceClassName,
                        contentDescription = contentDescription,
                    )
                ) {
                    chromeTabOverviewActive = true
                    lastWindowPackageName = sourcePackageName
                    lastTrackedContentEventAtMs = event.eventTime
                    TrustedOverlayHost.setContentAnalysisSuspended(true)
                    return
                }
                if (
                    chromeTabOverviewActive &&
                    shouldResumeAnalysisForBrowserTabClick(
                        sourcePackageName = sourcePackageName,
                        sourceClassName = sourceClassName,
                    )
                ) {
                    chromeTabOverviewActive = false
                    lastWindowPackageName = sourcePackageName
                    lastTrackedContentEventAtMs = event.eventTime
                    TrustedOverlayHost.setContentAnalysisSuspended(false)
                    return
                }
                if (
                    !shouldDiscardExistingProtectionForBrowserClick(
                        sourcePackageName = sourcePackageName,
                        sourceClassName = sourceClassName,
                        contentDescription = contentDescription,
                    )
                ) {
                    return
                }
                chromeTabOverviewActive = false
                lastWindowPackageName = sourcePackageName
                lastTrackedContentEventAtMs = event.eventTime
                if (!TrustedOverlayHost.setContentAnalysisSuspended(false)) {
                    TrustedOverlayHost.invalidateContent(discardExistingProtection = true)
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

/**
 * Browser page navigation can keep the same Android package. An Activity-level
 * state change still represents a new logical screen, while a widget-level
 * state change usually belongs to a temporary popup/menu.
 */
internal fun shouldDiscardExistingProtectionForWindowState(
    previousPackageName: String?,
    sourcePackageName: String,
    sourceClassName: String?,
): Boolean {
    if (sourcePackageName != previousPackageName) return true
    if (sourceClassName?.endsWith("Activity") == true) return true

    // Chrome emits framework FrameLayout state changes while a page and its
    // compositor are still loading. Treating those as navigation makes a
    // correct blur disappear and return. The explicit toolbar-click policy
    // below identifies the tab overview without this ambiguous signal.
    return false
}

/** True only for Chrome's toolbar control that opens the tab overview. */
internal fun shouldSuspendAnalysisForBrowserOverviewClick(
    sourcePackageName: String,
    sourceClassName: String?,
    contentDescription: String?,
): Boolean {
    if (
        !isChromePackage(sourcePackageName) ||
        sourceClassName != "android.widget.ImageButton" ||
        contentDescription.isNullOrBlank()
    ) {
        return false
    }

    val normalized = contentDescription.trim().lowercase()
    val englishTabCount =
        normalized.startsWith("see ") &&
            (normalized.endsWith(" tab") || normalized.endsWith(" tabs"))
    return englishTabCount ||
        normalized.contains("\u0639\u0644\u0627\u0645\u0627\u062a \u0627\u0644\u062a\u0628\u0648\u064a\u0628")
}

/** A Chrome tab card is a native clickable FrameLayout in the overview. */
internal fun shouldResumeAnalysisForBrowserTabClick(
    sourcePackageName: String,
    sourceClassName: String?,
): Boolean =
    isChromePackage(sourcePackageName) &&
        sourceClassName == "android.widget.FrameLayout"

/**
 * Chrome compositor surfaces do not always emit window-state changes. A native
 * toolbar button whose accessibility label means tab/home navigation is
 * sufficient proof that the currently protected page is leaving the screen.
 * No page text or accessibility-node hierarchy is read.
 */
internal fun shouldDiscardExistingProtectionForBrowserClick(
    sourcePackageName: String,
    sourceClassName: String?,
    contentDescription: String?,
): Boolean {
    if (
        !isChromePackage(sourcePackageName) ||
        sourceClassName !in setOf("android.widget.ImageButton", "android.widget.Button") ||
        contentDescription.isNullOrBlank()
    ) {
        return false
    }

    val normalized = contentDescription.trim().lowercase()
    return shouldSuspendAnalysisForBrowserOverviewClick(
        sourcePackageName = sourcePackageName,
        sourceClassName = sourceClassName,
        contentDescription = contentDescription,
    ) ||
        normalized == "new tab" ||
        normalized.contains("\u0639\u0644\u0627\u0645\u0629 \u062a\u0628\u0648\u064a\u0628 \u062c\u062f\u064a\u062f\u0629") ||
        normalized.contains("home page") ||
        normalized.contains("\u0627\u0644\u0635\u0641\u062d\u0629 \u0627\u0644\u0631\u0626\u064a\u0633\u064a\u0629")
}

private fun isChromePackage(packageName: String): Boolean =
    packageName == "com.android.chrome" || packageName.startsWith("com.chrome.")

/** Connected accessibility service used to create trusted overlay windows. */
internal object TrustedOverlayHost {
    @Volatile
    private var service: PrivacyOverlayAccessibilityService? = null
    @Volatile
    private var contentAnalysisSuspended = false
    private val contentInvalidationListeners = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val contentMovementListeners = CopyOnWriteArraySet<(Int, Int) -> Unit>()

    val isConnected: Boolean
        get() = service?.let { true } ?: false

    val isContentAnalysisSuspended: Boolean
        get() = contentAnalysisSuspended

    fun currentService(): PrivacyOverlayAccessibilityService? = service

    fun connect(accessibilityService: PrivacyOverlayAccessibilityService) {
        service = accessibilityService
        contentAnalysisSuspended = false
        invalidateContent()
    }

    fun disconnect(accessibilityService: PrivacyOverlayAccessibilityService) {
        if (service === accessibilityService) {
            service = null
            contentAnalysisSuspended = false
            invalidateContent()
        }
    }

    fun subscribeToContentInvalidation(listener: (Boolean) -> Unit): Closeable {
        contentInvalidationListeners += listener
        return object : Closeable {
            override fun close() {
                contentInvalidationListeners -= listener
            }
        }
    }

    fun subscribeToContentMovement(listener: (Int, Int) -> Unit): Closeable {
        contentMovementListeners += listener
        return object : Closeable {
            override fun close() {
                contentMovementListeners -= listener
            }
        }
    }

    fun invalidateContent(discardExistingProtection: Boolean = false) {
        contentInvalidationListeners.forEach { listener ->
            runCatching { listener(discardExistingProtection) }
        }
    }

    @Synchronized
    fun setContentAnalysisSuspended(suspended: Boolean): Boolean {
        if (contentAnalysisSuspended == suspended) return false
        contentAnalysisSuspended = suspended
        invalidateContent(discardExistingProtection = true)
        return true
    }

    fun moveContentBy(deltaX: Int, deltaY: Int) {
        if (deltaX == 0 && deltaY == 0) return
        contentMovementListeners.forEach { listener ->
            runCatching { listener(deltaX, deltaY) }
        }
    }
}
