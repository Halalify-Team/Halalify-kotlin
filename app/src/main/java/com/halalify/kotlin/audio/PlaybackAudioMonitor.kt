package com.halalify.kotlin.audio

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.os.Build
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface AudioMonitorEvent {
    data class Started(val modelActive: Boolean) : AudioMonitorEvent
    data class CapturedOnly(val rms: Float) : AudioMonitorEvent
    data class Isolated(
        val musicScore: Float,
        val musicDetected: Boolean,
        val isolationActive: Boolean,
        val detectorLabel: String?,
    ) : AudioMonitorEvent
    data class Failed(val reason: String) : AudioMonitorEvent
}

/** Captures playback permitted by Android and feeds fixed mono PCM frames to the AI core. */
@TargetApi(Build.VERSION_CODES.Q)
internal class PlaybackAudioMonitor(
    private val mediaProjection: MediaProjection,
    private val processor: AudioFrameProcessor?,
    private val onSpeechFrame: (ShortArray) -> Unit = {},
    private val onMusicDetected: () -> Unit = {},
    private val onEvent: (AudioMonitorEvent) -> Unit,
) : Closeable {
    private val running = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var consecutiveMusicFrames = 0
    private var musicActionTriggered = false

    @SuppressLint("MissingPermission")
    fun start() {
        check(running.compareAndSet(false, true)) { "Playback audio monitor is already running." }
        try {
            val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val minimumBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minimumBytes > 0) { "Android did not provide a playback capture buffer size." }
            val recorder = AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minimumBytes * 2, READ_SAMPLES * PCM16_BYTES))
                .setAudioPlaybackCaptureConfig(playbackConfig)
                .build()
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Android could not initialize playback audio capture."
            }
            audioRecord = recorder
            captureThread = Thread(
                { captureLoop(recorder) },
                "halalify-playback-audio",
            ).apply { start() }
            onEvent(AudioMonitorEvent.Started(modelActive = processor != null))
        } catch (error: Throwable) {
            running.set(false)
            audioRecord?.release()
            audioRecord = null
            processor?.close()
            onEvent(AudioMonitorEvent.Failed(error.message ?: error.javaClass.simpleName))
        }
    }

    private fun captureLoop(recorder: AudioRecord) {
        val readBuffer = ShortArray(READ_SAMPLES)
        val frameSamples = processor?.frameSamples ?: MONITOR_FRAME_SAMPLES
        val accumulator = AudioFrameAccumulator(frameSamples)
        try {
            recorder.startRecording()
            while (running.get()) {
                val read = recorder.read(
                    readBuffer,
                    0,
                    readBuffer.size,
                    AudioRecord.READ_BLOCKING,
                )
                if (read > 0) {
                    accumulator.append(readBuffer, read) { frame -> processFrame(frame) }
                } else if (read < 0 && running.get()) {
                    error("Playback audio read failed with code $read.")
                }
            }
        } catch (error: Throwable) {
            if (running.getAndSet(false)) {
                onEvent(AudioMonitorEvent.Failed(error.message ?: error.javaClass.simpleName))
            }
        } finally {
            accumulator.reset()
        }
    }

    private fun processFrame(frame: ShortArray) {
        val activeProcessor = processor
        if (activeProcessor == null) {
            onEvent(AudioMonitorEvent.CapturedOnly(frame.normalizedRms()))
            return
        }
        val result = activeProcessor.process(frame)
        onSpeechFrame(result.speechPcm)
        if (result.musicDetected) {
            consecutiveMusicFrames += 1
            if (consecutiveMusicFrames >= MUSIC_CONFIRMATION_FRAMES && !musicActionTriggered) {
                musicActionTriggered = true
                onMusicDetected()
            }
        } else {
            consecutiveMusicFrames = 0
            musicActionTriggered = false
        }
        onEvent(
            AudioMonitorEvent.Isolated(
                musicScore = result.musicScore,
                musicDetected = result.musicDetected,
                isolationActive = result.isolationActive,
                detectorLabel = result.detectorLabel,
            ),
        )
    }

    override fun close() {
        val wasRunning = running.getAndSet(false)
        if (wasRunning) runCatching { audioRecord?.stop() }
        val thread = captureThread
        if (thread != null && thread !== Thread.currentThread()) runCatching { thread.join(1_000) }
        captureThread = null
        audioRecord?.release()
        audioRecord = null
        consecutiveMusicFrames = 0
        musicActionTriggered = false
        processor?.close()
    }

    private companion object {
        const val SAMPLE_RATE = NativeMusicIsolationEngine.MODEL_SAMPLE_RATE
        const val READ_SAMPLES = 2_048
        const val MONITOR_FRAME_SAMPLES = SAMPLE_RATE / 2
        const val PCM16_BYTES = 2
        const val MUSIC_CONFIRMATION_FRAMES = 2
    }
}
