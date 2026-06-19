package com.halalify.kotlin.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.halalify.kotlin.media.CHUNK_DURATION_SECONDS
import com.halalify.kotlin.media.calculateChunkCount
import com.halalify.kotlin.media.cutFirstTenSeconds
import com.halalify.kotlin.media.cutVideoSegment
import com.halalify.kotlin.media.downloadAudio
import com.halalify.kotlin.media.downloadVideo
import com.halalify.kotlin.media.extractAudioSegment
import com.halalify.kotlin.media.extractFirstTenSecondsAudio
import com.halalify.kotlin.media.fetchVideoMetadata
import com.halalify.kotlin.media.mockCleanAudio
import com.halalify.kotlin.media.muxVideoWithCleanAudio
import com.halalify.kotlin.media.testYtDlpVersion
import com.halalify.kotlin.media.validateYoutubeUrl
import com.halalify.kotlin.network.cleanAudioWithBackend
import com.halalify.kotlin.network.loginWithBackendDevAccount
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val DEFAULT_YOUTUBE_URL = "https://www.youtube.com/watch?v=JVu7-XSI_OM"
private const val DEFAULT_BACKEND_URL = "http://10.0.2.2:3000"

@Composable
internal fun HalalifyApp(activity: ComponentActivity) {
    val scope = rememberCoroutineScope()
    var isBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ready. Test yt-dlp, then download one small video locally.") }
    var downloadedPath by remember { mutableStateOf<String?>(null) }
    var cutPath by remember { mutableStateOf<String?>(null) }
    var audioSourcePath by remember { mutableStateOf<String?>(null) }
    var extractedAudioPath by remember { mutableStateOf<String?>(null) }
    var cleanAudioPath by remember { mutableStateOf<String?>(null) }
    var mergedPath by remember { mutableStateOf<String?>(null) }
    var playbackPath by remember { mutableStateOf<String?>(null) }
    var playlistPaths by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeChunkIndex by remember { mutableStateOf(0) }
    var youtubeUrl by remember { mutableStateOf(DEFAULT_YOUTUBE_URL) }
    var backendUrl by remember { mutableStateOf(DEFAULT_BACKEND_URL) }
    var devEmail by remember { mutableStateOf("dev@halalify.local") }
    var sessionToken by remember { mutableStateOf("") }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF071D22),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Halalify Kotlin",
                    color = Color(0xFFF4F1E8),
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    text = "Step 17: run clean chunks for the full video duration.",
                    color = Color(0xFFC5CEC8),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                when {
                    playlistPaths.isNotEmpty() -> {
                        Text(
                            text = "Playing chunk ${activeChunkIndex + 1} / ${playlistPaths.size}",
                            color = Color(0xFFC5CEC8),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 24.dp),
                        )
                        ChunkPlaylistPlayer(
                            filePaths = playlistPaths,
                            onChunkChanged = { activeChunkIndex = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .padding(top = 12.dp),
                        )
                    }
                    playbackPath != null -> LocalVideoPlayer(
                        filePath = playbackPath!!,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .padding(top = 24.dp),
                    )
                }
                OutlinedTextField(
                    value = youtubeUrl,
                    onValueChange = { youtubeUrl = it },
                    enabled = !isBusy,
                    label = { Text("YouTube URL") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                )
                Button(
                    enabled = !isBusy,
                    modifier = Modifier.padding(top = 24.dp),
                    onClick = {
                        scope.launch {
                            isBusy = true
                            try {
                                val url = youtubeUrl.trim()
                                status = "Reading duration locally with yt-dlp..."
                                val metadata = fetchVideoMetadata(activity, url)
                                val totalChunks = calculateChunkCount(metadata.durationSeconds)
                                status = "Metadata OK:\n" +
                                    "title: ${metadata.title}\n" +
                                    "duration: ${metadata.durationSeconds}s\n" +
                                    "chunk size: ${CHUNK_DURATION_SECONDS}s\n" +
                                    "chunks needed: $totalChunks"
                            } catch (error: Throwable) {
                                status = "FAILED metadata read: ${error.javaClass.simpleName}: ${error.message}"
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                ) {
                    Text("Read duration/chunks")
                }
                Button(
                    enabled = !isBusy,
                    modifier = Modifier.padding(top = 24.dp),
                    onClick = {
                        scope.launch {
                            isBusy = true
                            status = "Initializing yt-dlp locally..."
                            status = testYtDlpVersion(activity)
                            isBusy = false
                        }
                    },
                ) {
                    Text(if (isBusy) "Working..." else "Test yt-dlp --version")
                }
                Button(
                    enabled = !isBusy,
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        scope.launch {
                            isBusy = true
                            val url = youtubeUrl.trim()
                            status = "Downloading local video...\n$url"
                            val result = downloadVideo(activity, url)
                            status = result.message
                            downloadedPath = result.path
                            cutPath = null
                            mergedPath = null
                            playbackPath = null
                            isBusy = false
                        }
                    },
                ) {
                    Text("Download video")
                }
                Button(
                    enabled = !isBusy && downloadedPath != null,
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        scope.launch {
                            isBusy = true
                            status = "Cutting first 10 seconds with local ffmpeg..."
                            val result = cutFirstTenSeconds(activity, downloadedPath)
                            status = result.message
                            cutPath = result.path
                            mergedPath = null
                            playbackPath = null
                            isBusy = false
                        }
                    },
                ) {
                    Text("Cut first 10s")
                }
                Button(
                    enabled = !isBusy && (mergedPath != null || cutPath != null || downloadedPath != null),
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        val pathToPlay = mergedPath ?: cutPath ?: downloadedPath
                        playbackPath = pathToPlay
                        status = "Playing local file:\n$pathToPlay"
                    },
                ) {
                    Text(
                        when {
                            mergedPath != null -> "Play muxed file"
                            cutPath != null -> "Play cut file"
                            else -> "Play downloaded file"
                        }
                    )
                }
                Button(
                    enabled = !isBusy,
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        scope.launch {
                            isBusy = true
                            val url = youtubeUrl.trim()
                            status = "Downloading local audio...\n$url"
                            val result = downloadAudio(activity, url)
                            status = result.message
                            audioSourcePath = result.path
                            extractedAudioPath = null
                            cleanAudioPath = null
                            isBusy = false
                        }
                    },
                ) {
                    Text("Download audio")
                }
                Button(
                    enabled = !isBusy && audioSourcePath != null,
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        scope.launch {
                            isBusy = true
                            status = "Extracting first 10 seconds of audio with local ffmpeg..."
                            val result = extractFirstTenSecondsAudio(activity, audioSourcePath)
                            status = result.message
                            extractedAudioPath = result.path
                            cleanAudioPath = null
                            mergedPath = null
                            isBusy = false
                        }
                    },
                ) {
                    Text("Extract audio 10s")
                }
                Button(
                    enabled = !isBusy && extractedAudioPath != null,
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        scope.launch {
                            isBusy = true
                            status = "Mocking backend clean-audio response locally..."
                            val result = mockCleanAudio(activity, extractedAudioPath)
                            status = result.message
                            cleanAudioPath = result.path
                            mergedPath = null
                            isBusy = false
                        }
                    },
                ) {
                    Text("Mock clean audio")
                }
                OutlinedTextField(
                    value = backendUrl,
                    onValueChange = { backendUrl = it },
                    enabled = !isBusy,
                    label = { Text("Backend URL") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                OutlinedTextField(
                    value = devEmail,
                    onValueChange = { devEmail = it },
                    enabled = !isBusy,
                    label = { Text("Dev email") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                Button(
                    enabled = !isBusy,
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        scope.launch {
                            isBusy = true
                            status = "Requesting dev session from backend..."
                            val result = loginWithBackendDevAccount(
                                backendUrl = backendUrl,
                                email = devEmail,
                            )
                            status = result.first
                            sessionToken = result.second.orEmpty()
                            isBusy = false
                        }
                    },
                ) {
                    Text("Dev login")
                }
                OutlinedTextField(
                    value = sessionToken,
                    onValueChange = { sessionToken = it },
                    enabled = !isBusy,
                    label = { Text("Session token") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                Button(
                    enabled = !isBusy,
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        scope.launch {
                            isBusy = true
                            downloadedPath = null
                            cutPath = null
                            audioSourcePath = null
                            extractedAudioPath = null
                            cleanAudioPath = null
                            mergedPath = null
                            playbackPath = null
                            playlistPaths = emptyList()
                            activeChunkIndex = 0

                            try {
                                val url = youtubeUrl.trim()
                                val baseUrl = backendUrl.trim()
                                val token = sessionToken.trim()
                                validateYoutubeUrl(url)
                                if (baseUrl.isBlank()) error("Backend URL is required.")
                                if (token.isBlank()) error("Run dev login or paste a session token first.")

                                status = "Pipeline 1/6: downloading video locally..."
                                val video = downloadVideo(activity, url)
                                val videoPath = video.path ?: error(video.message)
                                downloadedPath = videoPath

                                status = "Pipeline 2/6: cutting first 10 seconds of video..."
                                val cut = cutFirstTenSeconds(activity, videoPath)
                                val videoChunkPath = cut.path ?: error(cut.message)
                                cutPath = videoChunkPath

                                status = "Pipeline 3/6: downloading audio locally..."
                                val audio = downloadAudio(activity, url)
                                val sourceAudioPath = audio.path ?: error(audio.message)
                                audioSourcePath = sourceAudioPath

                                status = "Pipeline 4/6: extracting first 10 seconds of audio..."
                                val extracted = extractFirstTenSecondsAudio(activity, sourceAudioPath)
                                val audioChunkPath = extracted.path ?: error(extracted.message)
                                extractedAudioPath = audioChunkPath

                                status = "Pipeline 5/6: removing music on backend..."
                                val clean = cleanAudioWithBackend(
                                    activity = activity,
                                    inputPath = audioChunkPath,
                                    backendUrl = baseUrl,
                                    sessionToken = token,
                                )
                                val cleanPath = clean.path ?: error(clean.message)
                                cleanAudioPath = cleanPath

                                status = "Pipeline 6/6: muxing clean audio with video..."
                                val mux = muxVideoWithCleanAudio(
                                    activity = activity,
                                    videoPath = videoChunkPath,
                                    cleanAudioPath = cleanPath,
                                    durationSeconds = CHUNK_DURATION_SECONDS,
                                )
                                val muxPath = mux.path ?: error(mux.message)
                                mergedPath = muxPath
                                playbackPath = muxPath

                                status = "SUCCESS: first clean video chunk pipeline completed.\n" +
                                    "video chunk: $videoChunkPath\n" +
                                    "clean audio: $cleanPath\n" +
                                    "muxed file: $muxPath"
                            } catch (error: Throwable) {
                                status = "FAILED pipeline: ${error.javaClass.simpleName}: ${error.message}"
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                ) {
                    Text("Run first clean chunk")
                }
                Button(
                    enabled = !isBusy,
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        scope.launch {
                            isBusy = true
                            downloadedPath = null
                            cutPath = null
                            audioSourcePath = null
                            extractedAudioPath = null
                            cleanAudioPath = null
                            mergedPath = null
                            playbackPath = null
                            playlistPaths = emptyList()
                            activeChunkIndex = 0

                            try {
                                val url = youtubeUrl.trim()
                                val baseUrl = backendUrl.trim()
                                val token = sessionToken.trim()
                                validateYoutubeUrl(url)
                                if (baseUrl.isBlank()) error("Backend URL is required.")
                                if (token.isBlank()) error("Run dev login or paste a session token first.")

                                status = "Chunks setup: reading video duration..."
                                val metadata = fetchVideoMetadata(activity, url)
                                val totalChunks = calculateChunkCount(metadata.durationSeconds)

                                status = "Chunks setup: ${metadata.title}\n" +
                                    "duration: ${metadata.durationSeconds}s\n" +
                                    "chunks: $totalChunks"
                                delay(600)

                                status = "Chunks setup: downloading video once..."
                                val video = downloadVideo(activity, url)
                                val videoPath = video.path ?: error(video.message)
                                downloadedPath = videoPath

                                status = "Chunks setup: downloading audio once..."
                                val audio = downloadAudio(activity, url)
                                val sourceAudioPath = audio.path ?: error(audio.message)
                                audioSourcePath = sourceAudioPath

                                val muxedChunks = mutableListOf<String>()
                                repeat(totalChunks) { index ->
                                    val start = index * CHUNK_DURATION_SECONDS
                                    val duration = (metadata.durationSeconds - start)
                                        .coerceAtMost(CHUNK_DURATION_SECONDS)
                                        .coerceAtLeast(1)
                                    status = "Chunk ${index + 1}/$totalChunks: cutting video ${start}s-${start + duration}s..."
                                    val cut = cutVideoSegment(
                                        activity = activity,
                                        inputPath = videoPath,
                                        startSeconds = start,
                                        durationSeconds = duration,
                                        chunkIndex = index,
                                    )
                                    val videoChunkPath = cut.path ?: error(cut.message)
                                    cutPath = videoChunkPath

                                    status = "Chunk ${index + 1}/$totalChunks: extracting audio ${start}s-${start + duration}s..."
                                    val extracted = extractAudioSegment(
                                        activity = activity,
                                        inputPath = sourceAudioPath,
                                        startSeconds = start,
                                        durationSeconds = duration,
                                        chunkIndex = index,
                                    )
                                    val audioChunkPath = extracted.path ?: error(extracted.message)
                                    extractedAudioPath = audioChunkPath

                                    status = "Chunk ${index + 1}/$totalChunks: removing music on backend..."
                                    val clean = cleanAudioWithBackend(
                                        activity = activity,
                                        inputPath = audioChunkPath,
                                        backendUrl = baseUrl,
                                        sessionToken = token,
                                        chunkIndex = index,
                                        durationSeconds = duration,
                                    )
                                    val cleanPath = clean.path ?: error(clean.message)
                                    cleanAudioPath = cleanPath

                                    status = "Chunk ${index + 1}/$totalChunks: muxing clean chunk..."
                                    val mux = muxVideoWithCleanAudio(
                                        activity = activity,
                                        videoPath = videoChunkPath,
                                        cleanAudioPath = cleanPath,
                                        durationSeconds = duration,
                                    )
                                    val muxPath = mux.path ?: error(mux.message)
                                    mergedPath = muxPath
                                    muxedChunks += muxPath

                                    if (index == 0) {
                                        playbackPath = muxPath
                                        playlistPaths = muxedChunks.toList()
                                    } else {
                                        playlistPaths = muxedChunks.toList()
                                    }

                                    status = "Chunk ${index + 1}/$totalChunks complete.\n" +
                                        muxedChunks.joinToString(separator = "\n") { path -> "ready: $path" }
                                }

                                playbackPath = muxedChunks.firstOrNull()
                                playlistPaths = muxedChunks.toList()
                                status = "SUCCESS: $totalChunks clean chunks completed.\n" +
                                    muxedChunks.joinToString(separator = "\n") { path -> "chunk: $path" }
                            } catch (error: Throwable) {
                                status = "FAILED multi-chunk pipeline: ${error.javaClass.simpleName}: ${error.message}"
                            } finally {
                                isBusy = false
                            }
                        }
                    },
                ) {
                    Text("Run full clean flow")
                }
                Button(
                    enabled = !isBusy && extractedAudioPath != null,
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        scope.launch {
                            isBusy = true
                            status = "Uploading extracted audio to backend..."
                            val result = cleanAudioWithBackend(
                                activity = activity,
                                inputPath = extractedAudioPath,
                                backendUrl = backendUrl,
                                sessionToken = sessionToken,
                            )
                            status = result.message
                            cleanAudioPath = result.path
                            mergedPath = null
                            isBusy = false
                        }
                    },
                ) {
                    Text("Backend clean audio")
                }
                Button(
                    enabled = !isBusy && cutPath != null && cleanAudioPath != null,
                    modifier = Modifier.padding(top = 12.dp),
                    onClick = {
                        scope.launch {
                            isBusy = true
                            status = "Muxing video chunk with clean audio locally..."
                            val result = muxVideoWithCleanAudio(
                                activity = activity,
                                videoPath = cutPath,
                                cleanAudioPath = cleanAudioPath,
                                durationSeconds = CHUNK_DURATION_SECONDS,
                            )
                            status = result.message
                            mergedPath = result.path
                            playbackPath = null
                            isBusy = false
                        }
                    },
                ) {
                    Text("Mux clean chunk")
                }
                Text(
                    text = status,
                    color = Color(0xFFF4F1E8),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }
    }
}
