package com.halalify.kotlin.viewmodel

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.halalify.kotlin.media.CHUNK_DURATION_SECONDS
import com.halalify.kotlin.media.calculateChunkCount
import com.halalify.kotlin.media.downloadAudio
import com.halalify.kotlin.media.downloadVideo
import com.halalify.kotlin.media.extractAudioSegment
import com.halalify.kotlin.media.fetchVideoMetadata
import com.halalify.kotlin.media.validateYoutubeUrl
import com.halalify.kotlin.media.concatAudioSegments
import com.halalify.kotlin.media.muxFullVideoWithCleanAudio
import com.halalify.kotlin.model.AppScreen
import com.halalify.kotlin.model.ChunkPhase
import com.halalify.kotlin.model.ChunkState
import com.halalify.kotlin.model.ProcessingState
import com.halalify.kotlin.network.cleanAudioWithBackend
import com.halalify.kotlin.network.loginWithBackendDevAccount
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class HalalifyViewModel : ViewModel() {

    private val _screen = MutableStateFlow(AppScreen.INPUT)
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    private val _processing = MutableStateFlow(ProcessingState())
    val processing: StateFlow<ProcessingState> = _processing.asStateFlow()

    private val _backendUrl = MutableStateFlow("http://10.0.2.2:3000")
    val backendUrl: StateFlow<String> = _backendUrl.asStateFlow()

    private val _devEmail = MutableStateFlow("tobegoodman5@gmail.com")
    val devEmail: StateFlow<String> = _devEmail.asStateFlow()

    private val _sessionToken = MutableStateFlow("")
    val sessionToken: StateFlow<String> = _sessionToken.asStateFlow()

    private val _loginStatus = MutableStateFlow("")
    val loginStatus: StateFlow<String> = _loginStatus.asStateFlow()

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn.asStateFlow()

    private val playUpdateMutex = Mutex()

    fun updateBackendUrl(url: String) { _backendUrl.value = url }
    fun updateDevEmail(email: String) { _devEmail.value = email }
    fun updateSessionToken(token: String) { _sessionToken.value = token }

    fun devLogin() {
        viewModelScope.launch {
            _isLoggingIn.value = true
            _loginStatus.value = "Requesting dev session..."
            val result = loginWithBackendDevAccount(
                backendUrl = _backendUrl.value,
                email = _devEmail.value,
            )
            _loginStatus.value = result.first
            _sessionToken.value = result.second.orEmpty()
            _isLoggingIn.value = false
        }
    }

    fun startProcessing(activity: ComponentActivity, youtubeUrl: String) {
        val url = youtubeUrl.trim()
        val baseUrl = _backendUrl.value.trim()

        viewModelScope.launch {
            _screen.value = AppScreen.PROCESSING
            _processing.value = ProcessingState(currentPhaseLabel = "Initializing...")

            try {
                validateYoutubeUrl(url)
                if (baseUrl.isBlank()) error("Backend URL is required.")

                // Auto Dev-Login if session token is empty
                var token = _sessionToken.value.trim()
                if (token.isBlank()) {
                    updatePhase("Authenticating dev session...")
                    val result = loginWithBackendDevAccount(
                        backendUrl = baseUrl,
                        email = _devEmail.value,
                    )
                    val returnedToken = result.second
                    if (returnedToken.isNullOrBlank()) {
                        error("Auto dev-login failed: ${result.first}")
                    }
                    _sessionToken.value = returnedToken
                    _loginStatus.value = result.first
                    token = returnedToken
                }

                // Phase 1: Get metadata
                updatePhase("Reading video metadata...")
                val metadata = fetchVideoMetadata(activity, url)
                val totalChunks = calculateChunkCount(metadata.durationSeconds)

                val initialChunks = (0 until totalChunks).map { i ->
                    ChunkState(index = i, totalChunks = totalChunks)
                }
                _processing.update {
                    it.copy(
                        videoTitle = metadata.title,
                        totalDurationSeconds = metadata.durationSeconds,
                        totalChunks = totalChunks,
                        chunks = initialChunks,
                        currentPhaseLabel = "Downloading audio...",
                    )
                }

                // Phase 2: Start background video download in parallel
                val videoDownloadDeferred = async {
                    downloadVideo(activity, url)
                }

                // Phase 3: Download full audio
                updatePhase("Downloading audio...")
                val audio = downloadAudio(activity, url)
                val sourceAudioPath = audio.path ?: error(audio.message)

                // Cut all audio chunks locally (instantaneous)
                updatePhase("Preparing audio chunks...")
                val audioChunkPaths = Array<String?>(totalChunks) { null }
                coroutineScope {
                    repeat(totalChunks) { index ->
                        launch {
                            val start = index * CHUNK_DURATION_SECONDS
                            val duration = (metadata.durationSeconds - start)
                                .coerceAtMost(CHUNK_DURATION_SECONDS)
                                .coerceAtLeast(1)
                            updateChunk(index, ChunkPhase.EXTRACTING_AUDIO)
                            val extracted = extractAudioSegment(
                                activity = activity,
                                inputPath = sourceAudioPath,
                                startSeconds = start,
                                durationSeconds = duration,
                                chunkIndex = index,
                            )
                            audioChunkPaths[index] = extracted.path ?: error(extracted.message)
                        }
                    }
                }

                // Phase 4: Clean all audio chunks in parallel (with Semaphore limit of 2)
                val semaphore = Semaphore(2)
                val cleanAudioChunks = Array<String?>(totalChunks) { null }

                coroutineScope {
                    repeat(totalChunks) { index ->
                        launch {
                            val start = index * CHUNK_DURATION_SECONDS
                            val duration = (metadata.durationSeconds - start)
                                .coerceAtMost(CHUNK_DURATION_SECONDS)
                                .coerceAtLeast(1)

                            val audioChunkPath = audioChunkPaths[index] ?: error("Audio chunk $index not prepared.")

                            // Clean audio on backend (with concurrency limit)
                            updateChunk(index, ChunkPhase.CLEANING_BACKEND)
                            val cleanResult = semaphore.withPermit {
                                updatePhase("Chunk ${index + 1}/$totalChunks: removing music...")
                                cleanAudioWithBackend(
                                    activity = activity,
                                    inputPath = audioChunkPath,
                                    backendUrl = baseUrl,
                                    sessionToken = token,
                                    chunkIndex = index,
                                    durationSeconds = duration,
                                )
                            }
                            val cleanPath = cleanResult.path ?: error(cleanResult.message)
                            cleanAudioChunks[index] = cleanPath
                            updateChunk(index, ChunkPhase.DONE)

                            // Mux full video with all contiguous clean audios (must wait for video download to complete!)
                            val videoResult = videoDownloadDeferred.await()
                            val videoPath = videoResult.path ?: error(videoResult.message)

                            playUpdateMutex.withLock {
                                // We only mux items that are contiguous from index 0!
                                val contiguousCleanAudios = mutableListOf<String>()
                                for (i in 0 until totalChunks) {
                                    val path = cleanAudioChunks[i]
                                    if (path != null) {
                                        contiguousCleanAudios.add(path)
                                    } else {
                                        break
                                    }
                                }

                                if (contiguousCleanAudios.isNotEmpty()) {
                                    updatePhase("Muxing playable video (${contiguousCleanAudios.size}/$totalChunks)...")
                                    
                                    // Concat audio segments
                                    val audioConcatResult = concatAudioSegments(activity, contiguousCleanAudios)
                                    val audioConcatPath = audioConcatResult.path ?: error(audioConcatResult.message)

                                    // Mux full video with clean concatenated audio
                                    val currentDuration = contiguousCleanAudios.size * CHUNK_DURATION_SECONDS
                                    val muxResult = muxFullVideoWithCleanAudio(
                                        activity = activity,
                                        videoPath = videoPath,
                                        cleanAudioPath = audioConcatPath,
                                        durationSeconds = currentDuration.coerceAtMost(metadata.durationSeconds),
                                    )
                                    val muxPath = muxResult.path ?: error(muxResult.message)

                                    // Safely delete old playable video files
                                    val playableDir = File(activity.filesDir, "halalify-playable")
                                    playableDir.listFiles()?.forEach { file ->
                                        if (file.absolutePath != muxPath) {
                                            file.delete()
                                        }
                                    }

                                    _processing.update { state ->
                                        state.copy(
                                            completedChunks = cleanAudioChunks.count { it != null },
                                            playablePaths = listOf(muxPath),
                                            firstChunkReady = true,
                                        )
                                    }
                                } else {
                                    _processing.update { state ->
                                        state.copy(
                                            completedChunks = cleanAudioChunks.count { it != null },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // All done - finalize video
                val videoResult = videoDownloadDeferred.await()
                val videoPath = videoResult.path ?: error(videoResult.message)
                val finalCleanAudios = cleanAudioChunks.filterNotNull()

                val finalPlayable = playUpdateMutex.withLock {
                    if (finalCleanAudios.isNotEmpty()) {
                        updatePhase("Finalizing video...")
                        val audioConcatResult = concatAudioSegments(activity, finalCleanAudios)
                        val audioConcatPath = audioConcatResult.path ?: error(audioConcatResult.message)
                        
                        val muxResult = muxFullVideoWithCleanAudio(
                            activity = activity,
                            videoPath = videoPath,
                            cleanAudioPath = audioConcatPath,
                            durationSeconds = metadata.durationSeconds,
                        )
                        val muxPath = muxResult.path ?: error(muxResult.message)

                        // Safely delete older playable video files
                        val playableDir = File(activity.filesDir, "halalify-playable")
                        playableDir.listFiles()?.forEach { file ->
                            if (file.absolutePath != muxPath) {
                                file.delete()
                            }
                        }

                        listOf(muxPath)
                    } else {
                        emptyList()
                    }
                }

                _processing.update {
                    it.copy(
                        isComplete = true,
                        currentPhaseLabel = "Complete!",
                        playablePaths = finalPlayable,
                    )
                }
            } catch (error: Throwable) {
                _processing.update {
                    it.copy(
                        errorMessage = "${error.javaClass.simpleName}: ${error.message}",
                        currentPhaseLabel = "Failed",
                    )
                }
            }
        }
    }



    fun navigateToResult() {
        _screen.value = AppScreen.RESULT
    }

    fun resetToInput() {
        _screen.value = AppScreen.INPUT
        _processing.value = ProcessingState()
    }

    private fun updatePhase(label: String) {
        _processing.update { it.copy(currentPhaseLabel = label) }
    }

    private fun updateChunk(index: Int, phase: ChunkPhase, errorMsg: String? = null) {
        _processing.update { state ->
            val updatedChunks = state.chunks.toMutableList()
            if (index < updatedChunks.size) {
                updatedChunks[index] = updatedChunks[index].copy(
                    phase = phase,
                    errorMessage = errorMsg,
                )
            }
            state.copy(chunks = updatedChunks)
        }
    }
}
