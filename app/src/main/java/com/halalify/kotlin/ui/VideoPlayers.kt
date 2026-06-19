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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

@Composable
internal fun LocalVideoPlayer(filePath: String, modifier: Modifier = Modifier) {
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
                this.player = player
            }
        },
        update = { playerView ->
            playerView.player = player
        },
    )
}

@Composable
internal fun ChunkPlaylistPlayer(
    filePaths: List<String>,
    onChunkChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
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
                val currentPosition = player.currentPosition
                val isPlaying = player.playWhenReady
                player.setMediaItems(mediaItems, 0, currentPosition)
                player.prepare()
                player.playWhenReady = isPlaying
            }
            mediaItems.size < player.mediaItemCount -> {
                player.setMediaItems(mediaItems, player.currentMediaItemIndex, player.currentPosition)
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
                this.player = player
            }
        },
        update = { playerView ->
            playerView.player = player
        },
    )
}
