package com.halalify.kotlin.audio

import com.halalify.kotlin.capture.CaptureStatePublisher
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface AudioMonitorFactory {
    fun create(
        processor: AudioFrameProcessor?,
        onMusicDetected: () -> Unit,
        onEvent: (AudioMonitorEvent) -> Unit,
    ): AudioMonitor
}

internal data class MusicProtectionState(
    val pauseRequested: Boolean = false,
    val mediaMuted: Boolean = false,
)

/** Coordinates one audio-monitoring session through replaceable boundary interfaces. */
internal class AudioProtectionSession(
    private val statePublisher: CaptureStatePublisher,
    private val processorProvider: AudioProcessorProvider,
    private val monitorFactory: AudioMonitorFactory,
    private val musicBlocker: MusicBlocker,
    private val updateNotification: (String) -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    private var monitor: AudioMonitor? = null
    @Volatile private var protectionState = MusicProtectionState()

    fun start() {
        check(!closed.get()) { "Audio protection session is closed." }
        check(monitor == null) { "Audio protection session is already running." }

        val selection = processorProvider.create()
        var createdMonitor: AudioMonitor? = null
        try {
            createdMonitor = monitorFactory.create(
                selection.processor,
                ::onMusicDetected,
            ) { event ->
                publishStatus(
                    AudioStatusFormatter.forEvent(
                        event = event,
                        unavailableReason = selection.unavailableReason,
                        protectionState = protectionState,
                    ),
                )
            }
            monitor = createdMonitor
            createdMonitor.start()
        } catch (error: Exception) {
            if (createdMonitor == null) {
                selection.processor?.close()
            } else {
                createdMonitor.close()
            }
            monitor = null
            musicBlocker.close()
            closed.set(true)
            throw error
        }
    }

    private fun onMusicDetected() {
        val result = musicBlocker.blockMusic()
        protectionState = MusicProtectionState(
            pauseRequested = result.pauseRequested,
            mediaMuted = result.mediaMuted,
        )
        val status = AudioStatusFormatter.forBlockResult(result)
        publishStatus(status)
        updateNotification(status)
    }

    private fun publishStatus(status: String) {
        statePublisher.updateState { current -> current.copy(audioStatus = status) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            monitor?.close()
        } finally {
            monitor = null
            try {
                musicBlocker.close()
            } finally {
                protectionState = MusicProtectionState()
            }
        }
    }
}

internal object AudioStatusFormatter {
    fun forBlockResult(result: MusicBlockResult): String = when {
        result.mediaMuted && result.pauseRequested ->
            "Audio: music confirmed; media sound muted and pause requested."
        result.mediaMuted -> "Audio: music confirmed; media sound muted on this device."
        result.pauseRequested ->
            "Audio: music confirmed; pause request sent to the playing media app."
        else -> "Audio: music confirmed, but the device media stream could not be muted."
    }

    fun forEvent(
        event: AudioMonitorEvent,
        unavailableReason: String?,
        protectionState: MusicProtectionState,
    ): String = when (event) {
        is AudioMonitorEvent.Started -> if (event.modelActive) {
            "Audio: monitoring eligible playback; music detection is active."
        } else {
            "Audio: capture is active, but the audio AI is unavailable " +
                "(${unavailableReason ?: "model unavailable"})."
        }
        is AudioMonitorEvent.CapturedOnly -> if (event.rms > AUDIBLE_RMS) {
            "Audio: playback detected; waiting for the local music model."
        } else {
            "Audio: waiting for capturable media playback."
        }
        is AudioMonitorEvent.Isolated -> isolatedStatus(event, protectionState)
        is AudioMonitorEvent.Failed -> "Audio capture stopped: ${event.reason}"
    }

    private fun isolatedStatus(
        event: AudioMonitorEvent.Isolated,
        protectionState: MusicProtectionState,
    ): String {
        val score = "%.0f%%".format(event.musicScore * 100F)
        if (!event.musicDetected) {
            val separator = if (event.isolationActive) "DTLN ready" else "passthrough"
            return "Audio: no significant music ($score); $separator."
        }

        val separator = if (event.isolationActive) {
            "DTLN speech stem produced"
        } else {
            "isolation unavailable"
        }
        val mute = if (protectionState.mediaMuted) "; media sound muted" else ""
        val pause = if (protectionState.pauseRequested) "; pause request sent" else ""
        return "Audio: music detected ($score, ${event.detectorLabel ?: "music"}); " +
            "$separator$mute$pause."
    }

    private const val AUDIBLE_RMS = 0.002F
}
