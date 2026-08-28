package com.halalify.kotlin.media

import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyOverlayAccessibilityPolicyTest {
    @Test
    fun `real scroll schedules a clean protection refresh`() {
        assertTrue(
            shouldRefreshProtectionAfterScroll(
                sdkInt = Build.VERSION_CODES.P,
                scrollDeltaX = 0,
                scrollDeltaY = 240,
            ),
        )
    }

    @Test
    fun `zero delta framework scroll does not churn protection`() {
        assertFalse(
            shouldRefreshProtectionAfterScroll(
                sdkInt = Build.VERSION_CODES.P,
                scrollDeltaX = 0,
                scrollDeltaY = 0,
            ),
        )
    }

    @Test
    fun `legacy scroll without delta still schedules refresh`() {
        assertTrue(
            shouldRefreshProtectionAfterScroll(
                sdkInt = Build.VERSION_CODES.O_MR1,
                scrollDeltaX = 0,
                scrollDeltaY = 0,
            ),
        )
    }

    @Test
    fun `content change shortly after user interaction schedules stale protection discard`() {
        assertTrue(
            shouldScheduleSettledProtectionDiscard(
                lastPotentialContentReplacementAtMs = 1_000L,
                lastPotentialContentReplacementPackageName =
                    PrivacyOverlayAccessibilityService::class.java.name,
                contentChangeEventAtMs = 1_500L,
                contentChangePackageName =
                    PrivacyOverlayAccessibilityService::class.java.name,
            ),
        )
    }

    @Test
    fun `background content animation does not discard stable protection`() {
        assertFalse(
            shouldScheduleSettledProtectionDiscard(
                lastPotentialContentReplacementAtMs = Long.MIN_VALUE,
                lastPotentialContentReplacementPackageName = null,
                contentChangeEventAtMs = 1_500L,
                contentChangePackageName =
                    PrivacyOverlayAccessibilityService::class.java.name,
            ),
        )
        assertFalse(
            shouldScheduleSettledProtectionDiscard(
                lastPotentialContentReplacementAtMs = 1_000L,
                lastPotentialContentReplacementPackageName =
                    PrivacyOverlayAccessibilityService::class.java.name,
                contentChangeEventAtMs = 11_001L,
                contentChangePackageName =
                    PrivacyOverlayAccessibilityService::class.java.name,
            ),
        )
        assertFalse(
            shouldScheduleSettledProtectionDiscard(
                lastPotentialContentReplacementAtMs = 1_000L,
                lastPotentialContentReplacementPackageName =
                    PrivacyOverlayAccessibilityService::class.java.name,
                contentChangeEventAtMs = 1_500L,
                contentChangePackageName =
                    PrivacyOverlayAccessibilityPolicyTest::class.java.name,
            ),
        )
    }

    @Test
    fun `switching application discards previous protection`() {
        assertTrue(
            shouldDiscardExistingProtectionForWindowState(
                previousPackageName = "com.example.first",
                sourcePackageName = "com.example.second",
                sourceClassName = "com.example.second.MainActivity",
            ),
        )
    }

    @Test
    fun `browser navigation in the same activity discards previous protection`() {
        assertTrue(
            shouldDiscardExistingProtectionForWindowState(
                previousPackageName = "com.android.chrome",
                sourcePackageName = "com.android.chrome",
                sourceClassName = "org.chromium.chrome.browser.ChromeTabbedActivity",
            ),
        )
    }

    @Test
    fun `chrome frame layout state change keeps stable page protection`() {
        assertFalse(
            shouldDiscardExistingProtectionForWindowState(
                previousPackageName = "com.android.chrome",
                sourcePackageName = "com.android.chrome",
                sourceClassName = "android.widget.FrameLayout",
            ),
        )
    }

    @Test
    fun `temporary popup class in same application keeps current protection`() {
        assertFalse(
            shouldDiscardExistingProtectionForWindowState(
                previousPackageName = "com.android.chrome",
                sourcePackageName = "com.android.chrome",
                sourceClassName = "android.widget.ListView",
            ),
        )
    }

    @Test
    fun `frame layout from a non browser application keeps current protection`() {
        assertFalse(
            shouldDiscardExistingProtectionForWindowState(
                previousPackageName = "com.example.reader",
                sourcePackageName = "com.example.reader",
                sourceClassName = "android.widget.FrameLayout",
            ),
        )
    }

    @Test
    fun `chrome tab switcher click discards selected page protection`() {
        assertTrue(
            shouldDiscardExistingProtectionForBrowserClick(
                sourcePackageName = "com.android.chrome",
                sourceClassName = "android.widget.ImageButton",
                contentDescription = "See 10 tabs",
            ),
        )
        assertTrue(
            shouldSuspendAnalysisForBrowserOverviewClick(
                sourcePackageName = "com.android.chrome",
                sourceClassName = "android.widget.ImageButton",
                contentDescription = "See 10 tabs",
            ),
        )
    }

    @Test
    fun `chrome new tab click discards selected page protection`() {
        assertTrue(
            shouldDiscardExistingProtectionForBrowserClick(
                sourcePackageName = "com.android.chrome",
                sourceClassName = "android.widget.ImageButton",
                contentDescription = "New tab",
            ),
        )
        assertFalse(
            shouldSuspendAnalysisForBrowserOverviewClick(
                sourcePackageName = "com.android.chrome",
                sourceClassName = "android.widget.ImageButton",
                contentDescription = "New tab",
            ),
        )
    }

    @Test
    fun `chrome menu click keeps selected page protection`() {
        assertFalse(
            shouldDiscardExistingProtectionForBrowserClick(
                sourcePackageName = "com.android.chrome",
                sourceClassName = "android.widget.ImageButton",
                contentDescription = "Update available. More options",
            ),
        )
    }

    @Test
    fun `chrome tab overview menu does not start a new suspension`() {
        assertFalse(
            shouldSuspendAnalysisForBrowserOverviewClick(
                sourcePackageName = "com.android.chrome",
                sourceClassName = "android.widget.ImageButton",
                contentDescription = "Manage open tabs",
            ),
        )
    }

    @Test
    fun `chrome tab card resumes page analysis`() {
        assertTrue(
            shouldResumeAnalysisForBrowserTabClick(
                sourcePackageName = "com.android.chrome",
                sourceClassName = "android.widget.FrameLayout",
            ),
        )
        assertFalse(
            shouldResumeAnalysisForBrowserTabClick(
                sourcePackageName = "com.android.chrome",
                sourceClassName = "android.widget.ImageView",
            ),
        )
    }

    @Test
    fun `web page button cannot imitate browser toolbar navigation`() {
        assertFalse(
            shouldDiscardExistingProtectionForBrowserClick(
                sourcePackageName = "com.android.chrome",
                sourceClassName = "android.widget.Button",
                contentDescription = "Open tab",
            ),
        )
    }
}
