package com.halalify.kotlin.viewmodel

import androidx.activity.ComponentActivity
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.halalify.kotlin.media.CHUNK_DURATION_SECONDS
import com.halalify.kotlin.media.FIRST_CHUNK_DURATION_SECONDS
import com.halalify.kotlin.media.cutVideoSegment
import com.halalify.kotlin.media.downloadAudio
import com.halalify.kotlin.media.downloadVideo
import com.halalify.kotlin.media.extractAudioSegment
import com.halalify.kotlin.media.fetchVideoMetadata
import com.halalify.kotlin.media.validateYoutubeUrl
import com.halalify.kotlin.media.concatAudioSegments
import com.halalify.kotlin.media.muxFullVideoWithCleanAudio
import com.halalify.kotlin.media.muxVideoWithCleanAudio
import com.halalify.kotlin.media.normalizeAudio
import com.halalify.kotlin.media.testYtDlpVersion
import com.halalify.kotlin.model.AppScreen
import com.halalify.kotlin.model.ChunkPhase
import com.halalify.kotlin.model.ChunkState
import com.halalify.kotlin.model.ProcessingState
import com.halalify.kotlin.model.LibraryItem
import com.halalify.kotlin.network.cleanAudioWithBackend
import com.halalify.kotlin.network.loginWithBackendDevAccount
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock

internal class HalalifyViewModel(application: Application) : AndroidViewModel(application) {

    private val _libraryItems = MutableStateFlow<List<LibraryItem>>(emptyList())
    val libraryItems: StateFlow<List<LibraryItem>> = _libraryItems.asStateFlow()

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    init {
        loadLibrary()
    }

    private fun getLibraryFile(): File {
        return File(getApplication<Application>().filesDir, "library.json")
    }

    fun loadLibrary() {
        viewModelScope.launch {
            try {
                val file = getLibraryFile()
                if (!file.exists()) {
                    _libraryItems.value = emptyList()
                    return@launch
                }
                val jsonStr = file.readText()
                val jsonArray = org.json.JSONArray(jsonStr)
                val items = mutableListOf<LibraryItem>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    items.add(
                        LibraryItem(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            filePath = obj.getString("filePath"),
                            originalUrl = obj.getString("originalUrl"),
                            durationSeconds = obj.getInt("durationSeconds"),
                            timestamp = obj.getLong("timestamp")
                        )
                    )
                }
                _libraryItems.value = items.sortedByDescending { it.timestamp }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun saveToLibrary(title: String, filePath: String, originalUrl: String, durationSeconds: Int) {
        viewModelScope.launch {
            try {
                val libraryDir = File(getApplication<Application>().filesDir, "halalify-library")
                libraryDir.mkdirs()
                
                val sourceFile = File(filePath)
                if (!sourceFile.exists()) return@launch
                
                val destFile = File(libraryDir, "lib_${UUID.randomUUID().toString().take(8)}.${sourceFile.extension}")
                sourceFile.copyTo(destFile, overwrite = true)
                
                val item = LibraryItem(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    filePath = destFile.absolutePath,
                    originalUrl = originalUrl,
                    durationSeconds = durationSeconds,
                    timestamp = System.currentTimeMillis()
                )
                
                val currentList = _libraryItems.value.toMutableList()
                currentList.add(0, item)
                _libraryItems.value = currentList
                
                val jsonArray = org.json.JSONArray()
                for (it in currentList) {
                    val obj = org.json.JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("filePath", it.filePath)
                        .put("originalUrl", it.originalUrl)
                        .put("durationSeconds", it.durationSeconds)
                        .put("timestamp", it.timestamp)
                    jsonArray.put(obj)
                }
                
                getLibraryFile().writeText(jsonArray.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun deleteFromLibrary(itemId: String) {
        viewModelScope.launch {
            try {
                val currentList = _libraryItems.value.toMutableList()
                val iterator = currentList.iterator()
                while (iterator.hasNext()) {
                    val it = iterator.next()
                    if (it.id == itemId) {
                        val file = File(it.filePath)
                        if (file.exists()) {
                            file.delete()
                        }
                        iterator.remove()
                    }
                }
                _libraryItems.value = currentList
                
                val jsonArray = org.json.JSONArray()
                for (it in currentList) {
                    val obj = org.json.JSONObject()
                        .put("id", it.id)
                        .put("title", it.title)
                        .put("filePath", it.filePath)
                        .put("originalUrl", it.originalUrl)
                        .put("durationSeconds", it.durationSeconds)
                        .put("timestamp", it.timestamp)
                    jsonArray.put(obj)
                }
                
                getLibraryFile().writeText(jsonArray.toString())
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearExportStatus() {
        _exportStatus.value = null
    }

    fun exportToGallery(context: Context, videoPath: String, title: String) {
        viewModelScope.launch {
            _exportStatus.value = "Saving to Gallery..."
            val resultUri = withContext(Dispatchers.IO) {
                saveVideoToGallery(context, videoPath, title)
            }
            if (resultUri != null) {
                _exportStatus.value = "SUCCESS: Saved to Gallery (Movies/Halalify)!"
            } else {
                _exportStatus.value = "FAILED: Could not save video to Gallery."
            }
        }
    }

    private fun saveVideoToGallery(context: Context, videoFilePath: String, title: String): String? {
        val sourceFile = File(videoFilePath)
        if (!sourceFile.exists()) return null
        
        val resolver = context.contentResolver
        val cleanTitle = title.replace("[^a-zA-Z0-9]".toRegex(), "_")
        val fileName = "Halalify_${cleanTitle}_${System.currentTimeMillis()}.mp4"
        
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.TITLE, title)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Halalify")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        
        val collectionUri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val itemUri = resolver.insert(collectionUri, contentValues) ?: return null
        
        try {
            resolver.openOutputStream(itemUri).use { outputStream ->
                if (outputStream == null) return null
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }
            
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }
            
            return itemUri.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            try {
                resolver.delete(itemUri, null, null)
            } catch (ignored: Exception) {}
            return null
        }
    }

    fun navigateToLibrary() {
        _screen.value = AppScreen.LIBRARY
    }

    fun playLibraryItem(item: LibraryItem) {
        _processing.value = ProcessingState(
            videoTitle = item.title,
            totalDurationSeconds = item.durationSeconds,
            totalChunks = 1,
            completedChunks = 1,
            currentPhaseLabel = "Library Playback",
            isComplete = true,
            playablePaths = listOf(item.filePath),
            firstChunkReady = true
        )
        _screen.value = AppScreen.RESULT
    }

    private val _screen = MutableStateFlow(AppScreen.INPUT)
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    private val _processing = MutableStateFlow(ProcessingState())
    val processing: StateFlow<ProcessingState> = _processing.asStateFlow()

    private val _backendUrl = MutableStateFlow("http://192.168.8.6:3000")
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
    private var processingJob: Job? = null
    private var warmUpJob: Job? = null

    fun updateBackendUrl(url: String) { _backendUrl.value = url }
    fun updateDevEmail(email: String) { _devEmail.value = email }
    fun updateSessionToken(token: String) { _sessionToken.value = token }

    fun warmUpLocalTools(activity: ComponentActivity) {
        if (warmUpJob?.isActive == true) return
        warmUpJob = viewModelScope.launch {
            runCatching {
                testYtDlpVersion(activity)
            }
        }
    }

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

        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            _screen.value = AppScreen.PROCESSING
            _processing.value = ProcessingState(currentPhaseLabel = "Initializing...")
            clearPlaybackScratch(activity)

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
                val chunkPlans = buildChunkPlans(metadata.durationSeconds)
                val totalChunks = chunkPlans.size

                val initialChunks = (0 until totalChunks).map { i ->
                    ChunkState(index = i, totalChunks = totalChunks)
                }
                _processing.update {
                    it.copy(
                        videoTitle = metadata.title,
                        totalDurationSeconds = metadata.durationSeconds,
                        totalChunks = totalChunks,
                        chunks = initialChunks,
                        currentPhaseLabel = "Preparing chunk pipeline...",
                    )
                }

                var videoDownloadDeferred: Deferred<com.halalify.kotlin.model.DownloadResult>? = null
                fun startFullVideoDownload(): Deferred<com.halalify.kotlin.model.DownloadResult> {
                    val existing = videoDownloadDeferred
                    if (existing != null) return existing
                    val created = async { downloadVideo(activity, url) }
                    videoDownloadDeferred = created
                    return created
                }

                // Phase 2: Keep the critical path identical to the fast native host:
                // audio range -> backend -> clean audio. Video work starts only after
                // clean audio is ready so it never delays the first playable chunk.
                val cleanAudioChunks = Array<String?>(totalChunks) { null }
                val videoDownloadSemaphore = Semaphore(1)
                val backendSemaphore = Semaphore(2)
                val muxSemaphore = Semaphore(1)

                supervisorScope {
                    val sourceAudioDeferred = async {
                        updatePhase("Downloading source audio...")
                        downloadAudio(activity, url)
                    }
                    startFullVideoDownload()

                    val chunkJobs = chunkPlans.map { plan ->
                        async {
                            try {
                                updateChunk(plan.index, ChunkPhase.EXTRACTING_AUDIO)
                                updatePhase(
                                    "Chunk ${plan.index + 1}/$totalChunks: preparing ${plan.durationSeconds}s audio..."
                                )
                                val audioChunkPath = run {
                                    val sourceAudio = sourceAudioDeferred.await()
                                    val sourceAudioPath = sourceAudio.path ?: error(sourceAudio.message)
                                    val audioChunk = extractAudioSegment(
                                        activity = activity,
                                        inputPath = sourceAudioPath,
                                        startSeconds = plan.startSeconds,
                                        durationSeconds = plan.durationSeconds,
                                        chunkIndex = plan.index,
                                    )
                                    audioChunk.path ?: error(audioChunk.message)
                                }

                                updateChunk(plan.index, ChunkPhase.CLEANING_BACKEND)
                                updatePhase("Chunk ${plan.index + 1}/$totalChunks: removing music...")
                                val rawCleanPath = backendSemaphore.withPermit {
                                    val cleanResult = cleanAudioWithBackend(
                                        activity = activity,
                                        inputPath = audioChunkPath,
                                        backendUrl = baseUrl,
                                        sessionToken = token,
                                        chunkIndex = plan.index,
                                        durationSeconds = plan.durationSeconds,
                                    )
                                    cleanResult.path ?: error(cleanResult.message)
                                }
                                File(audioChunkPath).delete()

                                updatePhase("Chunk ${plan.index + 1}/$totalChunks: normalizing audio...")
                                val normResult = normalizeAudio(activity, rawCleanPath, plan.index)
                                val cleanPath = normResult.path
                                    ?: error("Normalization failed for chunk ${plan.index + 1}: ${normResult.message}")
                                File(rawCleanPath).delete()

                                Result.success(
                                    CleanChunkResult(
                                        index = plan.index,
                                        cleanAudioPath = cleanPath,
                                    )
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Throwable) {
                                updateChunk(plan.index, ChunkPhase.ERROR, error.userFacingMessage())
                                Result.failure(error)
                            }
                        }
                    }

                    val playableSegments = mutableListOf<String>()
                    for (index in 0 until totalChunks) {
                        val result = chunkJobs[index].await().getOrElse { error ->
                            updateChunk(index, ChunkPhase.ERROR, error.userFacingMessage())
                            error("Chunk ${index + 1}/$totalChunks failed: ${error.userFacingMessage()}")
                        }
                        cleanAudioChunks[index] = result.cleanAudioPath
                        val plan = chunkPlans[index]

                        updateChunk(index, ChunkPhase.MUXING)
                        updatePhase("Chunk ${index + 1}/$totalChunks: preparing video preview...")
                        val playablePath = try {
                            videoDownloadSemaphore.withPermit {
                                val fullVideo = startFullVideoDownload().await()
                                val fullVideoPath = fullVideo.path ?: error(fullVideo.message)
                                val videoChunk = cutVideoSegment(
                                    activity = activity,
                                    inputPath = fullVideoPath,
                                    startSeconds = plan.startSeconds,
                                    durationSeconds = plan.durationSeconds,
                                    chunkIndex = plan.index,
                                )
                                val videoSegmentPath = videoChunk.path ?: error(videoChunk.message)
                                try {
                                    muxSemaphore.withPermit {
                                        val muxResult = muxVideoWithCleanAudio(
                                            activity = activity,
                                            videoPath = videoSegmentPath,
                                            cleanAudioPath = result.cleanAudioPath,
                                            durationSeconds = plan.durationSeconds,
                                        )
                                        muxResult.path ?: error(muxResult.message)
                                    }
                                } finally {
                                    File(videoSegmentPath).delete()
                                }
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            updateChunk(index, ChunkPhase.ERROR, error.userFacingMessage())
                            error("Chunk ${index + 1}/$totalChunks preview failed: ${error.userFacingMessage()}")
                        }

                        playableSegments += playablePath
                        updateChunk(index, ChunkPhase.DONE)
                        _processing.update { state ->
                            state.copy(
                                completedChunks = index + 1,
                                playablePaths = playableSegments.toList(),
                                firstChunkReady = true,
                                currentPhaseLabel = if (index == 0) {
                                    "Ready to watch. Processing continues..."
                                } else {
                                    "Ready ${index + 1}/$totalChunks chunks"
                                },
                            )
                        }
                        if (index == 0) {
                            updatePhase("Ready to watch. Downloading the full video in background...")
                            startFullVideoDownload()
                        }
                    }
                }

                // All done - finalize video
                val videoResult = startFullVideoDownload().await()
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

                // Save to app local Library
                if (finalPlayable.isNotEmpty()) {
                    saveToLibrary(
                        title = metadata.title,
                        filePath = finalPlayable.first(),
                        originalUrl = url,
                        durationSeconds = metadata.durationSeconds
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _processing.update {
                    it.copy(
                        errorMessage = error.userFacingMessage(),
                        currentPhaseLabel = "Failed",
                    )
                }
            }
        }
    }



    fun navigateToResult() {
        _screen.value = AppScreen.RESULT
    }

    fun navigateBackFromResult() {
        _screen.value = if (_processing.value.isComplete) {
            AppScreen.INPUT
        } else {
            AppScreen.PROCESSING
        }
    }

    fun resetToInput() {
        processingJob?.cancel()
        processingJob = null
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

    private fun clearPlaybackScratch(activity: ComponentActivity) {
        listOf(
            "halalify-playable",
            "halalify-mux-test",
            "halalify-video-preview-download",
        ).forEach { dirName ->
            File(activity.filesDir, dirName).listFiles()?.forEach { file ->
                file.delete()
            }
        }
    }

    private fun buildChunkPlans(durationSeconds: Int): List<ChunkPlan> {
        val totalDuration = durationSeconds.coerceAtLeast(1)
        val plans = mutableListOf<ChunkPlan>()
        var start = 0
        var index = 0

        val firstDuration = totalDuration.coerceAtMost(FIRST_CHUNK_DURATION_SECONDS).coerceAtLeast(1)
        plans += ChunkPlan(
            index = index,
            startSeconds = start,
            durationSeconds = firstDuration,
        )
        start += firstDuration
        index += 1

        while (start < totalDuration) {
            val duration = (totalDuration - start).coerceAtMost(CHUNK_DURATION_SECONDS).coerceAtLeast(1)
            plans += ChunkPlan(
                index = index,
                startSeconds = start,
                durationSeconds = duration,
            )
            start += duration
            index += 1
        }

        return plans
    }
}

private data class ChunkPlan(
    val index: Int,
    val startSeconds: Int,
    val durationSeconds: Int,
)

private data class CleanChunkResult(
    val index: Int,
    val cleanAudioPath: String,
)

private fun Throwable.userFacingMessage(): String {
    val rawMessage = message
        ?.lineSequence()
        ?.filter { it.isNotBlank() }
        ?.take(12)
        ?.joinToString("\n")
        ?.ifBlank { null }
        ?: javaClass.simpleName
    return "${javaClass.simpleName}: $rawMessage".take(1800)
}
