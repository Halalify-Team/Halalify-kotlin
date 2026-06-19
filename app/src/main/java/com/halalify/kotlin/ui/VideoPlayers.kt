package com.halalify.kotlin.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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

    LaunchedEffect(filePaths) {
        val mediaItems = filePaths.map { path -> MediaItem.fromUri(Uri.fromFile(File(path))) }
        when {
            mediaItems.size < player.mediaItemCount -> {
                player.setMediaItems(mediaItems, player.currentMediaItemIndex, player.currentPosition)
                player.prepare()
            }
            mediaItems.size > player.mediaItemCount -> {
                player.addMediaItems(mediaItems.drop(player.mediaItemCount))
                if (player.playbackState == Player.STATE_IDLE) {
                    player.prepare()
                }
            }
        }
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
