package com.halalify.kotlin.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.io.File

@Composable
internal fun LocalVideoPlayer(
    filePath: String,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val player = remember(filePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(File(filePath))))
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                this.player = player
                if (onToggleFullscreen != null) {
                    setFullscreenButtonClickListener { isFs ->
                        onToggleFullscreen()
                    }
                }
            }
        },
        update = { playerView ->
            playerView.player = player
            playerView.setFullscreenButtonState(isFullscreen)
        },
    )
}

@Composable
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun ChunkPlaylistPlayer(
    filePaths: List<String>,
    onChunkChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    isFullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = true
        }
    }

    var lastPlayedPath by remember { mutableStateOf("") }

    LaunchedEffect(filePaths) {
        if (filePaths.isEmpty()) return@LaunchedEffect
        val mediaItems = filePaths.map { path -> MediaItem.fromUri(Uri.fromFile(File(path))) }
        val newPath = filePaths.first()
        val isSinglePathChanged = filePaths.size == 1 && lastPlayedPath.isNotEmpty() && lastPlayedPath != newPath

        when {
            player.mediaItemCount == 0 -> {
                player.setMediaItems(mediaItems)
                player.prepare()
            }
            isSinglePathChanged -> {
                val currentPosition = player.absolutePlaylistPositionMs()
                val isPlaying = player.playWhenReady
                player.setMediaItems(mediaItems, 0, currentPosition)
                player.prepare()
                player.playWhenReady = isPlaying
            }
            mediaItems.size < player.mediaItemCount -> {
                val safeIndex = player.currentMediaItemIndex.coerceIn(0, mediaItems.lastIndex)
                player.setMediaItems(mediaItems, safeIndex, player.currentPosition)
                player.prepare()
            }
            mediaItems.size > player.mediaItemCount -> {
                val oldSize = player.mediaItemCount
                player.addMediaItems(mediaItems.drop(oldSize))
                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                } else if (player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(oldSize, 0L)
                    player.prepare()
                    player.play()
                }
            }
        }
        lastPlayedPath = newPath
        player.playWhenReady = true
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                onChunkChanged(player.currentMediaItemIndex.coerceAtLeast(0))
            }
        }
        player.addListener(listener)
        onChunkChanged(player.currentMediaItemIndex.coerceAtLeast(0))

        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                useController = true
                setShowMultiWindowTimeBar(true)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                this.player = player
                if (onToggleFullscreen != null) {
                    setFullscreenButtonClickListener { isFs ->
                        onToggleFullscreen()
                    }
                }
            }
        },
        update = { playerView ->
            playerView.player = player
            playerView.setShowMultiWindowTimeBar(true)
            playerView.setFullscreenButtonState(isFullscreen)
        },
    )
}

private fun Player.absolutePlaylistPositionMs(): Long {
    if (mediaItemCount <= 1) return currentPosition
    val window = Timeline.Window()
    var positionMs = currentPosition
    for (index in 0 until currentMediaItemIndex) {
        currentTimeline.getWindow(index, window)
        val durationMs = window.durationMs
        if (durationMs > 0) positionMs += durationMs
    }
    return positionMs
}
