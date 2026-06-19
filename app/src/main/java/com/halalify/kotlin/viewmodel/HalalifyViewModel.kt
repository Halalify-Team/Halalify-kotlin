package com.halalify.kotlin.viewmodel

import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.halalify.kotlin.media.CHUNK_DURATION_SECONDS
import com.halalify.kotlin.media.calculateChunkCount
import com.halalify.kotlin.media.cutVideoSegment
import com.halalify.kotlin.media.downloadAudio
import com.halalify.kotlin.media.downloadVideo
import com.halalify.kotlin.media.extractAudioSegment
import com.halalify.kotlin.media.fetchVideoMetadata
import com.halalify.kotlin.media.muxVideoWithCleanAudio
import com.halalify.kotlin.media.validateYoutubeUrl
import com.halalify.kotlin.model.AppScreen
import com.halalify.kotlin.model.ChunkPhase
import com.halalify.kotlin.model.ChunkState
import com.halalify.kotlin.model.ProcessingState
import com.halalify.kotlin.network.cleanAudioWithBackend
import com.halalify.kotlin.network.loginWithBackendDevAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class HalalifyViewModel : ViewModel() {

    private val _screen = MutableStateFlow(AppScreen.INPUT)
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    private val _processing = MutableStateFlow(ProcessingState())
    val processing: StateFlow<ProcessingState> = _processing.asStateFlow()

    private val _backendUrl = MutableStateFlow("http://10.0.2.2:3000")
    val backendUrl: StateFlow<String> = _backendUrl.asStateFlow()

    private val _devEmail = MutableStateFlow("dev@halalify.local")
    val devEmail: StateFlow<String> = _devEmail.asStateFlow()

    private val _sessionToken = MutableStateFlow("")
    val sessionToken: StateFlow<String> = _sessionToken.asStateFlow()

    private val _loginStatus = MutableStateFlow("")
    val loginStatus: StateFlow<String> = _loginStatus.asStateFlow()

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn.asStateFlow()

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
        val token = _sessionToken.value.trim()

        viewModelScope.launch {
            _screen.value = AppScreen.PROCESSING
            _processing.value = ProcessingState(currentPhaseLabel = "Initializing...")

            try {
                validateYoutubeUrl(url)
                if (baseUrl.isBlank()) error("Backend URL is required.")
                if (token.isBlank()) error("Run dev login or paste a session token first.")

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
                        currentPhaseLabel = "Downloading video...",
                    )
                }

                // Phase 2: Download video once
                updatePhase("Downloading video...")
                val video = downloadVideo(activity, url)
                val videoPath = video.path ?: error(video.message)

                // Phase 3: Download audio once
                updatePhase("Downloading audio...")
                val audio = downloadAudio(activity, url)
                val sourceAudioPath = audio.path ?: error(audio.message)

                // Phase 4: Process each chunk
                val muxedChunks = mutableListOf<String>()

                repeat(totalChunks) { index ->
                    val start = index * CHUNK_DURATION_SECONDS
                    val duration = (metadata.durationSeconds - start)
                        .coerceAtMost(CHUNK_DURATION_SECONDS)
                        .coerceAtLeast(1)

                    // Cut video segment
                    updateChunk(index, ChunkPhase.CUTTING_VIDEO)
                    updatePhase("Chunk ${index + 1}/$totalChunks: cutting video...")
                    val cut = cutVideoSegment(
                        activity = activity,
                        inputPath = videoPath,
                        startSeconds = start,
                        durationSeconds = duration,
                        chunkIndex = index,
                    )
                    val videoChunkPath = cut.path ?: error(cut.message)

                    // Extract audio segment
                    updateChunk(index, ChunkPhase.EXTRACTING_AUDIO)
                    updatePhase("Chunk ${index + 1}/$totalChunks: extracting audio...")
                    val extracted = extractAudioSegment(
                        activity = activity,
                        inputPath = sourceAudioPath,
                        startSeconds = start,
                        durationSeconds = duration,
                        chunkIndex = index,
                    )
                    val audioChunkPath = extracted.path ?: error(extracted.message)

                    // Clean audio on backend
                    updateChunk(index, ChunkPhase.CLEANING_BACKEND)
                    updatePhase("Chunk ${index + 1}/$totalChunks: removing music...")
                    val clean = cleanAudioWithBackend(
                        activity = activity,
                        inputPath = audioChunkPath,
                        backendUrl = baseUrl,
                        sessionToken = token,
                        chunkIndex = index,
                        durationSeconds = duration,
                    )
                    val cleanPath = clean.path ?: error(clean.message)

                    // Mux video + clean audio
                    updateChunk(index, ChunkPhase.MUXING)
                    updatePhase("Chunk ${index + 1}/$totalChunks: merging...")
                    val mux = muxVideoWithCleanAudio(
                        activity = activity,
                        videoPath = videoChunkPath,
                        cleanAudioPath = cleanPath,
                        durationSeconds = duration,
                    )
                    val muxPath = mux.path ?: error(mux.message)
                    muxedChunks += muxPath

                    // Mark chunk done
                    updateChunk(index, ChunkPhase.DONE)
                    _processing.update {
                        it.copy(
                            completedChunks = muxedChunks.size,
                            playablePaths = muxedChunks.toList(),
                            firstChunkReady = true,
                        )
                    }
                }

                // All done
                _processing.update {
                    it.copy(
                        isComplete = true,
                        currentPhaseLabel = "Complete!",
                        playablePaths = muxedChunks.toList(),
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
