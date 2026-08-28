package com.halalify.kotlin.media

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastTrackedContentEventAtMs = Long.MIN_VALUE
    private var lastWindowPackageName: String? = null
    private var lastPotentialContentReplacementAtMs = Long.MIN_VALUE
    private var lastPotentialContentReplacementPackageName: String? = null
    private var chromeTabOverviewActive = false
    private val settledContentDiscard = Runnable {
        lastPotentialContentReplacementAtMs = Long.MIN_VALUE
        lastPotentialContentReplacementPackageName = null
        TrustedOverlayHost.invalidateContent(discardExistingProtection = true)
    }
    private val settledScrollRefresh = Runnable {
        lastTrackedContentEventAtMs = Long.MIN_VALUE
        TrustedOverlayHost.invalidateContent(
            discardExistingProtection = true,
            suppressDiscardedReappearance = false,
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        cancelSettledContentDiscard()
        cancelSettledScrollRefresh()
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
                cancelSettledScrollRefresh()
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
                if (
                    !shouldRefreshProtectionAfterScroll(
                        sdkInt = Build.VERSION.SDK_INT,
                        scrollDeltaX = event.scrollDeltaX,
                        scrollDeltaY = event.scrollDeltaY,
                    )
                ) return
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
                mainHandler.removeCallbacks(settledScrollRefresh)
                mainHandler.postDelayed(
                    settledScrollRefresh,
                    SCROLL_SETTLE_DELAY_MS,
                )
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                markPotentialContentReplacement(
                    eventTimeMs = event.eventTime,
                    sourcePackageName = sourcePackageName,
                )
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

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // Typing in a browser address/search field can replace the
                // page without creating a new Activity or Android window.
                markPotentialContentReplacement(
                    eventTimeMs = event.eventTime,
                    sourcePackageName = sourcePackageName,
                )
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (
                    shouldScheduleSettledProtectionDiscard(
                        lastPotentialContentReplacementAtMs =
                            lastPotentialContentReplacementAtMs,
                        lastPotentialContentReplacementPackageName =
                            lastPotentialContentReplacementPackageName,
                        contentChangeEventAtMs = event.eventTime,
                        contentChangePackageName = sourcePackageName,
                    )
                ) {
                    // Web pages emit many changes while loading. Debouncing
                    // until the stream becomes quiet clears stale protection
                    // once, after the user-driven transition has settled.
                    mainHandler.removeCallbacks(settledContentDiscard)
                    mainHandler.postDelayed(
                        settledContentDiscard,
                        CONTENT_SETTLE_DELAY_MS,
                    )
                }
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        cancelSettledContentDiscard()
        cancelSettledScrollRefresh()
        TrustedOverlayHost.disconnect(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cancelSettledContentDiscard()
        cancelSettledScrollRefresh()
        TrustedOverlayHost.disconnect(this)
        super.onDestroy()
    }

    private fun markPotentialContentReplacement(
        eventTimeMs: Long,
        sourcePackageName: String,
    ) {
        cancelSettledScrollRefresh()
        lastPotentialContentReplacementAtMs = eventTimeMs
        lastPotentialContentReplacementPackageName = sourcePackageName
        mainHandler.removeCallbacks(settledContentDiscard)
    }

    private fun cancelSettledContentDiscard() {
        mainHandler.removeCallbacks(settledContentDiscard)
        lastPotentialContentReplacementAtMs = Long.MIN_VALUE
        lastPotentialContentReplacementPackageName = null
    }

    private fun cancelSettledScrollRefresh() {
        mainHandler.removeCallbacks(settledScrollRefresh)
    }

    private companion object {
        // Web pages can emit a continuous stream of synthetic scroll events.
        // Treat only the first event after a quiet gap as a new interaction.
        // Window-content changes are used only after a user click/text edit and
        // are debounced, so ordinary animations cannot churn stable protection.
        const val CONTENT_EVENT_QUIET_GAP_MS = 350L
        const val CONTENT_SETTLE_DELAY_MS = 500L
        const val SCROLL_SETTLE_DELAY_MS = 450L
    }
}

internal fun shouldRefreshProtectionAfterScroll(
    sdkInt: Int,
    scrollDeltaX: Int,
    scrollDeltaY: Int,
): Boolean =
    sdkInt < Build.VERSION_CODES.P || scrollDeltaX != 0 || scrollDeltaY != 0

internal fun shouldScheduleSettledProtectionDiscard(
    lastPotentialContentReplacementAtMs: Long,
    lastPotentialContentReplacementPackageName: String?,
    contentChangeEventAtMs: Long,
    contentChangePackageName: String,
    replacementWindowMs: Long = 10_000L,
): Boolean {
    if (
        lastPotentialContentReplacementAtMs == Long.MIN_VALUE ||
        lastPotentialContentReplacementPackageName != contentChangePackageName ||
        contentChangeEventAtMs < lastPotentialContentReplacementAtMs
    ) {
        return false
    }
    return contentChangeEventAtMs - lastPotentialContentReplacementAtMs <= replacementWindowMs
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
    private val contentInvalidationListeners =
        CopyOnWriteArraySet<(Boolean, Boolean) -> Unit>()
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

    fun subscribeToContentInvalidation(
        listener: (Boolean, Boolean) -> Unit,
    ): Closeable {
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

    fun invalidateContent(
        discardExistingProtection: Boolean = false,
        suppressDiscardedReappearance: Boolean = discardExistingProtection,
    ) {
        contentInvalidationListeners.forEach { listener ->
            runCatching {
                listener(
                    discardExistingProtection,
                    suppressDiscardedReappearance,
                )
            }
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
