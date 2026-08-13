package com.halalify.kotlin.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import java.io.Closeable

internal data class MusicBlockResult(
    val pauseRequested: Boolean,
    val mediaMuted: Boolean,
)

internal interface MusicBlocker : Closeable {
    fun blockMusic(): MusicBlockResult
}

/** Pauses cooperative players and mutes the device media stream while protection is active. */
internal class PlaybackAudioFocusController(
    context: Context,
) : MusicBlocker {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val listener = AudioManager.OnAudioFocusChangeListener { }
    private var request: AudioFocusRequest? = null
    private var requested = false
    private var mutedByHalalify = false
    private var volumeBeforeMute: Int? = null

    @Synchronized
    override fun blockMusic(): MusicBlockResult = MusicBlockResult(
        pauseRequested = requestPause(),
        mediaMuted = muteMediaStream(),
    )

    @Synchronized
    private fun requestPause(): Boolean {
        if (requested) return true
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setOnAudioFocusChangeListener(listener)
                .setAcceptsDelayedFocusGain(false)
                .build()
            request = focusRequest
            audioManager.requestAudioFocus(focusRequest)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                listener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
        requested = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return requested
    }

    @Synchronized
    private fun muteMediaStream(): Boolean {
        if (mutedByHalalify) return true
        if (audioManager.isStreamMute(AudioManager.STREAM_MUSIC)) return true

        volumeBeforeMute = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            AudioManager.ADJUST_MUTE,
            0,
        )
        mutedByHalalify = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
        if (!mutedByHalalify && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) > 0) {
            // A few OEM implementations do not honor ADJUST_MUTE for media streams.
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
            mutedByHalalify = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
        }
        return mutedByHalalify || audioManager.isStreamMute(AudioManager.STREAM_MUSIC)
    }

    @Synchronized
    override fun close() {
        if (mutedByHalalify) {
            runCatching {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE,
                    0,
                )
                val savedVolume = volumeBeforeMute
                if (savedVolume != null && audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
                }
            }
        }
        mutedByHalalify = false
        volumeBeforeMute = null
        if (!requested) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                request?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(listener)
            }
        }
        requested = false
        request = null
    }
}
