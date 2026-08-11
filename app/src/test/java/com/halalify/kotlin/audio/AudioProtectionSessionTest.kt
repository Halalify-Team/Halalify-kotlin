package com.halalify.kotlin.audio

import com.halalify.kotlin.capture.CaptureStatePublisher
import com.halalify.kotlin.capture.CaptureUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProtectionSessionTest {
    @Test
    fun `session coordinates monitor blocker and idempotent cleanup`() {
        val publisher = FakePublisher()
        val monitor = FakeMonitor()
        val blocker = FakeBlocker()
        val notifications = mutableListOf<String>()
        val session = AudioProtectionSession(
            statePublisher = publisher,
            processorProvider = AudioProcessorProvider {
                AudioProcessorSelection(processor = null, unavailableReason = "missing")
            },
            monitorFactory = AudioMonitorFactory { _, onMusicDetected, onEvent ->
                monitor.onMusicDetected = onMusicDetected
                monitor.onEvent = onEvent
                monitor
            },
            musicBlocker = blocker,
            updateNotification = notifications::add,
        )

        session.start()
        monitor.onMusicDetected()
        monitor.onEvent(
            AudioMonitorEvent.Isolated(
                musicScore = 0.9F,
                musicDetected = true,
                isolationActive = true,
                detectorLabel = "music",
            ),
        )
        session.close()
        session.close()

        assertTrue(publisher.state.audioStatus.orEmpty().contains("media sound muted"))
        assertEquals(1, notifications.size)
        assertEquals(1, blocker.blockCalls)
        assertEquals(1, blocker.closeCalls)
        assertEquals(1, monitor.closeCalls)
    }

    private class FakePublisher : CaptureStatePublisher {
        var state = CaptureUiState()
        override val isPreviewRequested = false

        override fun updateState(transform: (CaptureUiState) -> CaptureUiState) {
            state = transform(state)
        }
    }

    private class FakeMonitor : AudioMonitor {
        lateinit var onMusicDetected: () -> Unit
        lateinit var onEvent: (AudioMonitorEvent) -> Unit
        var closeCalls = 0

        override fun start() {
            onEvent(AudioMonitorEvent.Started(modelActive = false))
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private class FakeBlocker : MusicBlocker {
        var blockCalls = 0
        var closeCalls = 0

        override fun blockMusic(): MusicBlockResult {
            blockCalls += 1
            return MusicBlockResult(pauseRequested = true, mediaMuted = true)
        }

        override fun close() {
            closeCalls += 1
        }
    }
}
