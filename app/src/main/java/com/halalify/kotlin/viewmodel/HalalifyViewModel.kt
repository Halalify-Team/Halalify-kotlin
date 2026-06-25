package com.halalify.kotlin.viewmodel

import androidx.activity.ComponentActivity
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.halalify.kotlin.BuildConfig
import com.halalify.kotlin.media.downloadAudioChunk
import com.halalify.kotlin.media.downloadAudioFile
import com.halalify.kotlin.media.downloadVideo
import com.halalify.kotlin.media.downloadVideoSection
import com.halalify.kotlin.media.discoverFastYoutubeFormats
import com.halalify.kotlin.media.discoverYoutubeFormats
import com.halalify.kotlin.media.YoutubeFormatCatalog
import com.halalify.kotlin.media.validateYoutubeUrl
import com.halalify.kotlin.media.concatVideoSegments
import com.halalify.kotlin.media.muxFullVideoWithCleanAudio
import com.halalify.kotlin.media.muxVideoWithCleanAudio
import com.halalify.kotlin.media.normalizeAudio
import com.halalify.kotlin.media.testYtDlpVersion
import com.halalify.kotlin.model.AppScreen
import com.halalify.kotlin.model.ChunkPhase
import com.halalify.kotlin.model.ChunkState
import com.halalify.kotlin.model.FormatDiscoveryState
import com.halalify.kotlin.model.ProcessingState
import com.halalify.kotlin.model.LibraryItem
import com.halalify.kotlin.model.QuotaState
import com.halalify.kotlin.model.VideoQuality
import com.halalify.kotlin.network.cleanAudioWithBackend
import com.halalify.kotlin.network.fetchQuotaState
import com.halalify.kotlin.network.loginWithGoogleIdTokenDetailed
import com.halalify.kotlin.network.loginWithBackendDevAccountDetailed
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock

internal class HalalifyViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs =
        getApplication<Application>().getSharedPreferences("halalify_session", 0)

    private fun persistSession() {
        prefs.edit()
            .putString("session_token", _sessionToken.value)
            .putString("backend_url", _backendUrl.value)
            .putString("dev_email", _devEmail.value)
            .apply()
    }

    private val _libraryItems = MutableStateFlow<List<LibraryItem>>(emptyList())
    val libraryItems: StateFlow<List<LibraryItem>> = _libraryItems.asStateFlow()

    private val _exportStatus = MutableStateFlow<String?>(null)
    val exportStatus: StateFlow<String?> = _exportStatus.asStateFlow()

    private val _isExporting = MutableStateFlow(false)
    val isExporting: StateFlow<Boolean> = _isExporting.asStateFlow()

    private val _libraryStatus = MutableStateFlow<String?>(null)
    val libraryStatus: StateFlow<String?> = _libraryStatus.asStateFlow()

    init {
        cleanupAbandonedTemporaryFiles()
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
                    val filePath = obj.getString("filePath")
                    val file = File(filePath)
                    if (!file.isFile || file.length() <= 0L) {
                        continue
                    }
                    items.add(
                        LibraryItem(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            filePath = filePath,
                            originalUrl = obj.getString("originalUrl"),
                            durationSeconds = obj.getInt("durationSeconds"),
                            fileSizeBytes = obj.optLong("fileSizeBytes", file.length()),
                            timestamp = obj.getLong("timestamp")
                        )
                    )
                }
                _libraryItems.value = items.sortedByDescending { it.timestamp }
                persistLibrary(items)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun saveToLibrary(title: String, filePath: String, originalUrl: String, durationSeconds: Int) {
        viewModelScope.launch {
            try {
                saveToLibraryInternal(title, filePath, originalUrl, durationSeconds)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun saveToLibraryInternal(
        title: String,
        filePath: String,
        originalUrl: String,
        durationSeconds: Int,
    ) = withContext(Dispatchers.IO) {
        val libraryDir = File(getApplication<Application>().filesDir, "halalify-library")
        libraryDir.mkdirs()

        val sourceFile = File(filePath)
        if (!sourceFile.exists()) return@withContext

        val destFile = File(libraryDir, "lib_${UUID.randomUUID().toString().take(8)}.${sourceFile.extension}")
        sourceFile.copyTo(destFile, overwrite = true)

        val item = LibraryItem(
            id = UUID.randomUUID().toString(),
            title = title,
            filePath = destFile.absolutePath,
            originalUrl = originalUrl,
            durationSeconds = durationSeconds,
            fileSizeBytes = destFile.length(),
            timestamp = System.currentTimeMillis()
        )

        val currentList = _libraryItems.value.toMutableList()
        currentList.add(0, item)

        persistLibrary(currentList)
        _libraryItems.value = currentList
    }

    private fun persistLibrary(items: List<LibraryItem>) {
        val jsonArray = org.json.JSONArray()
        for (it in items) {
            val obj = org.json.JSONObject()
                .put("id", it.id)
                .put("title", it.title)
                .put("filePath", it.filePath)
                .put("originalUrl", it.originalUrl)
                .put("durationSeconds", it.durationSeconds)
                .put("fileSizeBytes", it.fileSizeBytes)
                .put("timestamp", it.timestamp)
            jsonArray.put(obj)
        }

        getLibraryFile().writeText(jsonArray.toString())
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
                persistLibrary(currentList)
                _libraryStatus.value = "Removed from Library."
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearLibraryStatus() {
        _libraryStatus.value = null
    }

    fun clearExportStatus() {
        _exportStatus.value = null
    }

    fun exportToGallery(context: Context, videoPath: String, title: String) {
        if (_isExporting.value || _processing.value.isSavedToGallery) return
        viewModelScope.launch {
            _isExporting.value = true
            _exportStatus.value = "Saving to Gallery..."
            try {
                val resultUri = withContext(Dispatchers.IO) {
                    saveVideoToGallery(context, videoPath, title)
                }
                if (resultUri != null) {
                    val state = _processing.value
                    if (!state.isLibraryPlayback && state.originalUrl.isNotBlank()) {
                        saveToLibraryInternal(
                            title = state.videoTitle.ifBlank { title },
                            filePath = videoPath,
                            originalUrl = state.originalUrl,
                            durationSeconds = state.totalDurationSeconds,
                        )
                    }
                    cleanupTemporaryWorkDirsExcept(keepPaths = listOf(videoPath))
                    _exportStatus.value = "SUCCESS: Saved to Gallery (Movies/Halalify)!"
                    _processing.update { it.copy(isSavedToGallery = true) }
                } else {
                    _exportStatus.value = "FAILED: Could not save video to Gallery."
                }
            } finally {
                _isExporting.value = false
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

    fun navigateToProfile() {
        _screen.value = AppScreen.PROFILE
    }

    fun logout() {
        clearPersistedSession(status = "Signed out.")
    }

    fun playLibraryItem(item: LibraryItem) {
        val file = File(item.filePath)
        if (!file.isFile || file.length() <= 0L) {
            deleteFromLibrary(item.id)
            _libraryStatus.value = "This saved video file is missing, so it was removed from Library."
            return
        }
        _processing.value = ProcessingState(
            videoTitle = item.title,
            originalUrl = item.originalUrl,
            totalDurationSeconds = item.durationSeconds,
            totalChunks = 1,
            completedChunks = 1,
            currentPhaseLabel = "Library Playback",
            isComplete = true,
            isSavedToGallery = true,
            isLibraryPlayback = true,
            playablePaths = listOf(item.filePath),
            firstChunkReady = true
        )
        _screen.value = AppScreen.RESULT
    }

    private val _screen = MutableStateFlow(AppScreen.INPUT)
    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    private val _processing = MutableStateFlow(ProcessingState())
    val processing: StateFlow<ProcessingState> = _processing.asStateFlow()

    private val _formatDiscovery = MutableStateFlow(FormatDiscoveryState())
    val formatDiscovery: StateFlow<FormatDiscoveryState> = _formatDiscovery.asStateFlow()

    private val _backendUrl = MutableStateFlow(
        prefs.getString("backend_url", BuildConfig.DEFAULT_BACKEND_URL)
            .orEmpty()
            .ifBlank { BuildConfig.DEFAULT_BACKEND_URL }
    )
    val backendUrl: StateFlow<String> = _backendUrl.asStateFlow()

    private val _devEmail = MutableStateFlow(
        prefs.getString("dev_email", "").orEmpty()
    )
    val devEmail: StateFlow<String> = _devEmail.asStateFlow()

    private val _sessionToken = MutableStateFlow(
        prefs.getString("session_token", "").orEmpty()
    )
    val sessionToken: StateFlow<String> = _sessionToken.asStateFlow()

    private val _loginStatus = MutableStateFlow(
        if (_sessionToken.value.isNotBlank()) "Checking saved session..." else ""
    )
    val loginStatus: StateFlow<String> = _loginStatus.asStateFlow()

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn.asStateFlow()

    private val _quotaState = MutableStateFlow(QuotaState())
    val quotaState: StateFlow<QuotaState> = _quotaState.asStateFlow()

    private val playUpdateMutex = Mutex()
    private var processingJob: Job? = null
    private var formatDiscoveryJob: Job? = null
    private var warmUpJob: Job? = null
    private var cachedFormatCatalog: CachedFormatCatalog? = null

    init {
        validatePersistedSession()
    }

    fun updateBackendUrl(url: String) { _backendUrl.value = url; persistSession() }
    fun updateDevEmail(email: String) { _devEmail.value = email; persistSession() }
    fun updateSessionToken(token: String) { _sessionToken.value = token; persistSession() }

    fun beginGoogleSignIn() {
        _isLoggingIn.value = true
        _loginStatus.value = "Choose your Google account..."
    }

    fun cancelGoogleSignIn() {
        _isLoggingIn.value = false
        if (_sessionToken.value.isBlank()) {
            _loginStatus.value = ""
        }
    }

    fun reportGoogleSignInFailure(message: String) {
        _isLoggingIn.value = false
        _loginStatus.value = "FAILED: $message"
    }

    private fun validatePersistedSession() {
        val token = _sessionToken.value.trim()
        if (token.isBlank()) return
        viewModelScope.launch {
            runCatching {
                fetchQuotaState(
                    backendUrl = _backendUrl.value,
                    sessionToken = token,
                )
            }.onSuccess { quota ->
                _quotaState.value = quota.copy(isLoading = false)
                _devEmail.value = quota.email
                _loginStatus.value = "Signed in as ${quota.email}."
                persistSession()
            }.onFailure { error ->
                val message = error.message.orEmpty()
                val invalidSession = message.contains("401") ||
                    message.contains("invalid or expired", ignoreCase = true) ||
                    message.contains("session is invalid", ignoreCase = true)
                if (invalidSession) {
                    clearPersistedSession(
                        status = "Your saved session expired. Sign in again.",
                    )
                } else {
                    _loginStatus.value =
                        "Saved session is available. Account check will retry when online."
                }
            }
        }
    }

    private fun clearPersistedSession(status: String) {
        _sessionToken.value = ""
        _loginStatus.value = status
        _quotaState.value = QuotaState(statusMessage = status)
        prefs.edit().remove("session_token").apply()
    }

    fun discoverFormats(activity: ComponentActivity, youtubeUrl: String) {
        val url = youtubeUrl.trim()
        formatDiscoveryJob?.cancel()
        if (url.isBlank()) {
            _formatDiscovery.value = FormatDiscoveryState()
            return
        }
        freshFormatCatalog(url)?.let { catalog ->
            _formatDiscovery.value = FormatDiscoveryState(
                url = url,
                videoTitle = catalog.metadata.title,
                availableQualities = catalog.availableQualities,
            )
            return
        }
        formatDiscoveryJob = viewModelScope.launch {
            _formatDiscovery.value = FormatDiscoveryState(
                url = url,
                isLoading = true,
            )
            runCatching {
                withContext(Dispatchers.IO) {
                    discoverFastYoutubeFormats(url)
                }
            }.onSuccess { fastCatalog ->
                if (_formatDiscovery.value.url == url) {
                    _formatDiscovery.value = FormatDiscoveryState(
                        url = url,
                        videoTitle = fastCatalog.metadata.title,
                        availableQualities = fastCatalog.availableQualities,
                        isLoading = true,
                    )
                }
            }
            runCatching {
                discoverYoutubeFormats(activity, url)
            }.onSuccess { catalog ->
                cacheFormatCatalog(url, catalog)
                _formatDiscovery.value = FormatDiscoveryState(
                    url = url,
                    videoTitle = catalog.metadata.title,
                    availableQualities = catalog.availableQualities,
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val current = _formatDiscovery.value
                _formatDiscovery.value = if (
                    current.url == url && current.availableQualities.isNotEmpty()
                ) {
                    current.copy(isLoading = false)
                } else {
                    FormatDiscoveryState(
                        url = url,
                        errorMessage = error.userFacingMessage(),
                    )
                }
            }
        }
    }

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
            _quotaState.value = _quotaState.value.copy(
                isLoading = true,
                statusMessage = "Signing in...",
            )
            val result = loginWithBackendDevAccountDetailed(
                backendUrl = _backendUrl.value,
                email = _devEmail.value,
            )
            _loginStatus.value = result.message
            _sessionToken.value = result.sessionToken.orEmpty()
            _quotaState.value = result.quota?.copy(isLoading = false)
                ?: QuotaState(
                    statusMessage = result.message,
                    isLoading = false,
                )
            persistSession()
            _isLoggingIn.value = false
        }
    }

    fun googleLogin(idToken: String) {
        viewModelScope.launch {
            _isLoggingIn.value = true
            _loginStatus.value = "Signing in with Google..."
            _quotaState.value = _quotaState.value.copy(
                isLoading = true,
                statusMessage = "Signing in with Google...",
            )
            val result = loginWithGoogleIdTokenDetailed(
                backendUrl = _backendUrl.value,
                idToken = idToken,
            )
            _loginStatus.value = result.message
            result.sessionToken?.takeIf { it.isNotBlank() }?.let { token ->
                _sessionToken.value = token
            }
            result.quota?.let { quota ->
                _devEmail.value = quota.email
            }
            _quotaState.value = result.quota?.copy(isLoading = false)
                ?: QuotaState(
                    statusMessage = result.message,
                    isLoading = false,
                )
            if (result.sessionToken?.isNotBlank() == true) {
                persistSession()
            }
            _isLoggingIn.value = false
        }
    }

    fun refreshQuota() {
        viewModelScope.launch {
            val token = _sessionToken.value.trim()
            if (token.isBlank()) {
                _quotaState.value = QuotaState(statusMessage = "Sign in to load quota.")
                return@launch
            }
            _quotaState.value = _quotaState.value.copy(
                isLoading = true,
                statusMessage = "Refreshing quota...",
            )
            runCatching {
                fetchQuotaState(
                    backendUrl = _backendUrl.value,
                    sessionToken = token,
                )
            }.onSuccess { quota ->
                _quotaState.value = quota.copy(isLoading = false)
            }.onFailure { error ->
                _quotaState.value = _quotaState.value.copy(
                    isLoading = false,
                    statusMessage = "FAILED: ${error.userFacingMessage()}",
                )
            }
        }
    }

    fun startProcessing(
        activity: ComponentActivity,
        youtubeUrl: String,
        removeMusic: Boolean = true,
        quality: VideoQuality = VideoQuality.P360,
    ) {
        val url = youtubeUrl.trim()
        val baseUrl = _backendUrl.value.trim()
        val processingStartedAt = SystemClock.elapsedRealtime()

        fun perf(stage: String) {
            Log.i("HalalifyPerf", "+${SystemClock.elapsedRealtime() - processingStartedAt}ms $stage")
        }

        processingJob?.cancel()
        processingJob = viewModelScope.launch {
            perf("processing-start")
            _screen.value = if (removeMusic) AppScreen.PROCESSING else AppScreen.DOWNLOAD
            _processing.value = ProcessingState(
                originalUrl = url,
                currentPhaseLabel = "Initializing...",
                removeMusic = removeMusic,
                quality = quality,
            )
            clearPlaybackScratch(activity)

            try {
                validateYoutubeUrl(url)
                val token = _sessionToken.value.trim()
                if (token.isBlank()) {
                    error("Please sign in with Google before downloading a video.")
                }
                if (!removeMusic) {
                    downloadOriginalVideo(
                        activity = activity,
                        url = url,
                        quality = quality,
                        catalog = freshFormatCatalog(url),
                        perf = ::perf,
                    )
                    return@launch
                }
                if (baseUrl.isBlank()) error("Backend URL is required.")

                val quotaDeferred = async {
                    runCatching {
                        fetchQuotaState(
                            backendUrl = baseUrl,
                            sessionToken = token,
                        )
                    }
                }

                // Resolve metadata and reusable direct media URLs while quota is checked in parallel.
                updatePhase("Preparing direct media stream...")
                val initialCatalog = freshFormatCatalog(url)
                    ?: discoverYoutubeFormats(activity, url).also {
                        cacheFormatCatalog(url, it)
                    }
                var directMedia = initialCatalog.sessionFor(quality)
                    ?: error("${quality.label} is not available for this video.")
                perf(
                    "media-session-ready audio=${directMedia.audio.formatId} " +
                        "video=${directMedia.video.formatId}"
                )
                val metadata = directMedia.metadata
                updatePhase("Checking account quota...")
                val cachedQuota = _quotaState.value.takeIf {
                    it.hasLiveData && !it.isLoading
                }
                val quota = cachedQuota ?: quotaDeferred.await().getOrElse { quotaError ->
                    throw IllegalStateException(
                        "Could not verify your quota before processing. " +
                            quotaError.userFacingMessage()
                    )
                }
                _quotaState.value = quota.copy(isLoading = false)
                validateEnoughQuotaForVideo(quota, metadata.durationSeconds)
                perf("quota-verified cached=${cachedQuota != null}")
                if (cachedQuota != null) {
                    launch {
                        quotaDeferred.await().onSuccess { refreshedQuota ->
                            _quotaState.value = refreshedQuota.copy(isLoading = false)
                        }
                    }
                }

                val chunkPlans = buildChunkPlans(metadata.durationSeconds)
                val totalChunks = chunkPlans.size

                val initialChunks = (0 until totalChunks).map { i ->
                    ChunkState(index = i, totalChunks = totalChunks)
                }
                _processing.update {
                    it.copy(
                        videoTitle = metadata.title,
                        originalUrl = url,
                        totalDurationSeconds = metadata.durationSeconds,
                        totalChunks = totalChunks,
                        chunks = initialChunks,
                        currentPhaseLabel = "Preparing chunk pipeline...",
                    )
                }

                // Each audio range is read directly from YouTube. No full source audio
                // is downloaded or cut before the first chunk reaches the backend.
                val audioRangeSemaphore = Semaphore(1)
                val videoRangeSemaphore = Semaphore(1)
                val backendSemaphore = Semaphore(1)
                val muxSemaphore = Semaphore(1)
                val mediaRefreshMutex = Mutex()
                val firstChunkPlayable = CompletableDeferred<Unit>()
                val playableSegments = mutableListOf<String>()

                suspend fun refreshMediaIfUnchanged(failedMediaUrl: String) {
                    mediaRefreshMutex.withLock {
                        if (directMedia.audio.url == failedMediaUrl ||
                            directMedia.video.url == failedMediaUrl
                        ) {
                            updatePhase("Refreshing YouTube stream...")
                            val refreshedCatalog = discoverYoutubeFormats(activity, url)
                            cacheFormatCatalog(url, refreshedCatalog)
                            directMedia = refreshedCatalog.sessionFor(quality)
                                ?: error("${quality.label} is no longer available for this video.")
                            perf("media-session-refreshed")
                        }
                    }
                }

                supervisorScope {
                    val videoChunkJobs = chunkPlans.map { plan ->
                        async {
                            if (plan.index > 0) firstChunkPlayable.await()
                            videoRangeSemaphore.withPermit {
                                var media = directMedia.video
                                var videoChunk = downloadVideoSection(
                                    activity = activity,
                                    media = media,
                                    startSeconds = plan.startSeconds,
                                    durationSeconds = plan.durationSeconds,
                                    chunkIndex = plan.index,
                                )
                                if (videoChunk.path == null) {
                                    refreshMediaIfUnchanged(media.url)
                                    media = directMedia.video
                                    videoChunk = downloadVideoSection(
                                        activity = activity,
                                        media = media,
                                        startSeconds = plan.startSeconds,
                                        durationSeconds = plan.durationSeconds,
                                        chunkIndex = plan.index,
                                    )
                                }
                                (videoChunk.path ?: error(videoChunk.message))
                                    .also { perf("chunk-${plan.index}-video-ready") }
                            }
                        }
                    }
                    val chunkJobs = chunkPlans.map { plan ->
                        async {
                            try {
                                if (plan.index > 0) firstChunkPlayable.await()
                                updateChunk(plan.index, ChunkPhase.EXTRACTING_AUDIO)
                                updatePhase(
                                    "Chunk ${plan.index + 1}/$totalChunks: streaming ${plan.durationSeconds}s audio..."
                                )
                                val audioChunkPath = audioRangeSemaphore.withPermit {
                                    var media = directMedia.audio
                                    var audioChunk = downloadAudioChunk(
                                        activity = activity,
                                        media = media,
                                        startSeconds = plan.startSeconds,
                                        durationSeconds = plan.durationSeconds,
                                        chunkIndex = plan.index,
                                    )
                                    if (audioChunk.path == null) {
                                        refreshMediaIfUnchanged(media.url)
                                        media = directMedia.audio
                                        audioChunk = downloadAudioChunk(
                                            activity = activity,
                                            media = media,
                                            startSeconds = plan.startSeconds,
                                            durationSeconds = plan.durationSeconds,
                                            chunkIndex = plan.index,
                                        )
                                    }
                                    (audioChunk.path ?: error(audioChunk.message))
                                        .also { perf("chunk-${plan.index}-audio-ready") }
                                }

                                val rawCleanPath = backendSemaphore.withPermit {
                                    updateChunk(plan.index, ChunkPhase.CLEANING_BACKEND)
                                    updatePhase(
                                        "Chunk ${plan.index + 1}/$totalChunks: removing music..."
                                    )
                                    perf("chunk-${plan.index}-backend-start")
                                    val cleanResult = cleanAudioWithBackend(
                                        activity = activity,
                                        inputPath = audioChunkPath,
                                        backendUrl = baseUrl,
                                        sessionToken = token,
                                        chunkIndex = plan.index,
                                        durationSeconds = plan.durationSeconds,
                                    )
                                    cleanResult.minutesRemaining?.let { remaining ->
                                        updateQuotaAfterChunk(remaining)
                                    }
                                    (cleanResult.path ?: error(cleanResult.message))
                                        .also { perf("chunk-${plan.index}-backend-ready") }
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

                    for (index in 0 until totalChunks) {
                        val result = chunkJobs[index].await().getOrElse { chunkError ->
                            val message = "Chunk ${index + 1}/$totalChunks failed: ${chunkError.userFacingMessage()}"
                            chunkJobs.drop(index + 1).forEach { pendingJob ->
                                pendingJob.cancel()
                            }
                            videoChunkJobs.drop(index).forEach { pendingJob ->
                                pendingJob.cancel()
                            }
                            updateChunk(index, ChunkPhase.ERROR, chunkError.userFacingMessage())
                            _processing.update { state ->
                                state.copy(
                                    playablePaths = playableSegments.toList(),
                                    firstChunkReady = playableSegments.isNotEmpty(),
                                    currentPhaseLabel = if (playableSegments.isNotEmpty()) {
                                        "Stopped after ${playableSegments.size}/$totalChunks chunks"
                                    } else {
                                        "Failed"
                                    },
                                    errorMessage = message,
                                )
                            }
                            throw PartialProcessingException(message)
                        }
                        val plan = chunkPlans[index]

                        updateChunk(index, ChunkPhase.MUXING)
                        updatePhase("Chunk ${index + 1}/$totalChunks: preparing video preview...")
                        val playablePath = try {
                            val videoSegmentPath = videoChunkJobs[index].await()
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
                                File(result.cleanAudioPath).delete()
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Throwable) {
                            updateChunk(index, ChunkPhase.ERROR, error.userFacingMessage())
                            error("Chunk ${index + 1}/$totalChunks preview failed: ${error.userFacingMessage()}")
                        }

                        playableSegments += playablePath
                        perf("chunk-$index-playable-ready")
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
                            firstChunkPlayable.complete(Unit)
                            updatePhase("Ready to watch. More clean chunks are being added...")
                        }
                    }
                }

                // The clean playable chunks are the final source. Joining them avoids
                // downloading the original full video a second time.
                val finalPlayable = playUpdateMutex.withLock {
                    if (playableSegments.isNotEmpty()) {
                        updatePhase("Finalizing video...")
                        val concatResult = concatVideoSegments(
                            activity = activity,
                            segmentPaths = playableSegments,
                        )
                        val finalPath = concatResult.path ?: error(concatResult.message)
                        playableSegments.forEach { segmentPath ->
                            if (segmentPath != finalPath) File(segmentPath).delete()
                        }
                        listOf(finalPath)
                    } else {
                        emptyList()
                    }
                }

                _processing.update {
                    it.copy(
                        isComplete = true,
                        isSavedToGallery = false,
                        isLibraryPlayback = false,
                        currentPhaseLabel = "Complete!",
                        playablePaths = finalPlayable,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (partial: PartialProcessingException) {
                _processing.update {
                    it.copy(
                        errorMessage = partial.message ?: "Processing stopped.",
                        currentPhaseLabel = if (it.playablePaths.isNotEmpty()) {
                            "Stopped after ${it.completedChunks}/${it.totalChunks} chunks"
                        } else {
                            "Failed"
                        },
                    )
                }
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

    private suspend fun downloadOriginalVideo(
        activity: ComponentActivity,
        url: String,
        quality: VideoQuality,
        catalog: YoutubeFormatCatalog?,
        perf: (String) -> Unit,
    ) {
        updatePhase("Reading video information...")
        val resolvedCatalog = catalog ?: discoverYoutubeFormats(activity, url)
        val mediaSession = resolvedCatalog.sessionFor(quality)
            ?: error("${quality.label} is not available for this video.")
        val metadata = mediaSession.metadata
        val actualQuality = resolvedCatalog.sessionsByQuality.entries
            .firstOrNull { it.value == mediaSession }
            ?.key
            ?: quality
        _processing.update {
            it.copy(
                videoTitle = metadata.title,
                totalDurationSeconds = metadata.durationSeconds,
                totalChunks = 1,
                quality = actualQuality,
                currentPhaseLabel = "Downloading ${actualQuality.label} video...",
            )
        }
        perf("normal-media-session-ready")

        updatePhase("Downloading ${actualQuality.label} video...")
        val sameProgressiveFormat =
            mediaSession.video.formatId == mediaSession.audio.formatId
        val finalPath = if (sameProgressiveFormat) {
            val result = downloadVideo(activity, mediaSession.video)
            result.path ?: error(result.message)
        } else {
            val (videoResult, audioResult) = kotlinx.coroutines.coroutineScope {
                val videoDownload = async {
                    downloadVideo(activity, mediaSession.video)
                }
                val audioDownload = async {
                    downloadAudioFile(activity, mediaSession.audio)
                }
                videoDownload.await() to audioDownload.await()
            }
            val videoPath = videoResult.path ?: error(videoResult.message)
            val audioPath = audioResult.path ?: error(audioResult.message)
            updatePhase("Combining video and audio...")
            try {
                val muxResult = muxFullVideoWithCleanAudio(
                    activity = activity,
                    videoPath = videoPath,
                    cleanAudioPath = audioPath,
                    durationSeconds = metadata.durationSeconds,
                )
                muxResult.path ?: error(muxResult.message)
            } finally {
                File(videoPath).delete()
                File(audioPath).delete()
            }
        }
        perf("normal-video-downloaded")

        _processing.update {
            it.copy(
                completedChunks = 1,
                currentPhaseLabel = "Download complete!",
                isComplete = true,
                playablePaths = listOf(finalPath),
                firstChunkReady = true,
            )
        }
        perf("normal-download-complete")
    }



    fun navigateToResult() {
        _screen.value = AppScreen.RESULT
    }

    fun navigateBackFromResult() {
        val state = _processing.value
        _screen.value = when {
            state.isLibraryPlayback -> AppScreen.LIBRARY
            state.removeMusic -> AppScreen.PROCESSING
            else -> AppScreen.DOWNLOAD
        }
    }

    fun resetToInput(discardTemporaryResult: Boolean = true) {
        processingJob?.cancel()
        processingJob = null
        if (discardTemporaryResult) {
            discardCurrentTemporaryResult()
        }
        _screen.value = AppScreen.INPUT
        _processing.value = ProcessingState()
    }

    fun discardCurrentTemporaryResult() {
        val state = _processing.value
        if (!state.isLibraryPlayback && !state.isSavedToGallery) {
            state.playablePaths.forEach { path ->
                runCatching { File(path).delete() }
            }
            cleanupTemporaryWorkDirs()
            return
        }
        cleanupTemporaryWorkDirsExcept(keepPaths = state.playablePaths)
    }

    private fun updatePhase(label: String) {
        _processing.update { it.copy(currentPhaseLabel = label) }
    }

    private fun freshFormatCatalog(url: String): YoutubeFormatCatalog? {
        val cached = cachedFormatCatalog ?: return null
        if (cached.url != url) return null
        if (SystemClock.elapsedRealtime() - cached.createdAtMs > FORMAT_CATALOG_TTL_MS) {
            return null
        }
        return cached.catalog
    }

    private fun cacheFormatCatalog(url: String, catalog: YoutubeFormatCatalog) {
        cachedFormatCatalog = CachedFormatCatalog(
            url = url,
            catalog = catalog,
            createdAtMs = SystemClock.elapsedRealtime(),
        )
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
        cleanupTemporaryWorkDirs(activity.filesDir)
    }

    private fun cleanupAbandonedTemporaryFiles() {
        cleanupTemporaryWorkDirs(getApplication<Application>().filesDir)
    }

    private fun cleanupTemporaryWorkDirs(root: File = getApplication<Application>().filesDir) {
        temporaryWorkDirNames.forEach { dirName ->
            File(root, dirName).deleteRecursively()
        }
    }

    private fun cleanupTemporaryWorkDirsExcept(
        root: File = getApplication<Application>().filesDir,
        keepPaths: List<String>,
    ) {
        val keepFiles = keepPaths
            .mapNotNull { path -> runCatching { File(path).canonicalFile }.getOrNull() }
            .toSet()
        temporaryWorkDirNames.forEach { dirName ->
            val dir = File(root, dirName)
            if (!dir.exists()) return@forEach
            val dirCanonical = runCatching { dir.canonicalFile }.getOrNull() ?: dir
            val keepInsideDir = keepFiles.any { keepFile ->
                keepFile.path == dirCanonical.path || keepFile.path.startsWith("${dirCanonical.path}/")
            }
            if (!keepInsideDir) {
                dir.deleteRecursively()
                return@forEach
            }
            dir.listFiles()?.forEach { child ->
                val childCanonical = runCatching { child.canonicalFile }.getOrNull() ?: child
                val shouldKeep = keepFiles.any { keepFile ->
                    keepFile.path == childCanonical.path || keepFile.path.startsWith("${childCanonical.path}/")
                }
                if (!shouldKeep) {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                }
            }
        }
    }

    private fun buildChunkPlans(durationSeconds: Int): List<ChunkPlan> {
        val totalDuration = durationSeconds.coerceAtLeast(1)
        val plans = mutableListOf<ChunkPlan>()
        var start = 0
        var index = 0

        while (start < totalDuration) {
            val targetDuration = when (index) {
                0 -> 10
                1, 2 -> 15
                3 -> 30
                else -> 60
            }
            val duration = (totalDuration - start).coerceAtMost(targetDuration).coerceAtLeast(1)
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

    private fun validateEnoughQuotaForVideo(quota: QuotaState, durationSeconds: Int) {
        val total = quota.minutesTotal ?: 0.0
        if (total < 0.0) return

        if (!quota.hasLiveData) {
            error("Quota is not available. Please sign in and refresh your quota.")
        }
        if (quota.accountStatus.isNotBlank() && quota.accountStatus.lowercase() != "active") {
            error("Account is ${quota.accountStatus}. Please reactivate your subscription.")
        }

        val remaining = quota.minutesRemaining ?: 0.0
        val needed = durationSeconds / 60.0
        if (remaining + 0.001 < needed) {
            error(
                "Not enough quota. This video needs ${formatQuotaMinutes(needed)} minutes, " +
                    "but you have ${formatQuotaMinutes(remaining)} left."
            )
        }
    }

    private fun updateQuotaAfterChunk(minutesRemaining: Double) {
        _quotaState.update { quota ->
            quota.copy(
                minutesRemaining = minutesRemaining,
                minutesUsed = quota.minutesTotal
                    ?.takeIf { it >= 0.0 }
                    ?.let { total -> (total - minutesRemaining).coerceAtLeast(0.0) }
                    ?: quota.minutesUsed,
                usagePercent = quota.minutesTotal
                    ?.takeIf { it > 0.0 }
                    ?.let { total -> (((total - minutesRemaining).coerceAtLeast(0.0) / total) * 100).toInt().coerceIn(0, 100) }
                    ?: quota.usagePercent,
                statusMessage = "Quota updated after processing a chunk.",
                isLoading = false,
            )
        }
    }
}

private fun formatQuotaMinutes(value: Double): String = "%.1f".format(value)

private data class ChunkPlan(
    val index: Int,
    val startSeconds: Int,
    val durationSeconds: Int,
)

private data class CleanChunkResult(
    val index: Int,
    val cleanAudioPath: String,
)

private data class CachedFormatCatalog(
    val url: String,
    val catalog: YoutubeFormatCatalog,
    val createdAtMs: Long,
)

private class PartialProcessingException(message: String) : RuntimeException(message)

private const val FORMAT_CATALOG_TTL_MS = 60_000L

private val temporaryWorkDirNames = listOf(
    "halalify-audio-chunk-download",
    "halalify-audio-concat",
    "halalify-audio-download-test",
    "halalify-audio-extract-test",
    "halalify-audio-normalized",
    "halalify-clean-audio-backend",
    "halalify-clean-audio-mock",
    "halalify-concat",
    "halalify-cut-test",
    "halalify-download-test",
    "halalify-full-audio",
    "halalify-full-video",
    "halalify-mux-test",
    "halalify-playable",
    "halalify-video-preview-download",
)

private fun Throwable.userFacingMessage(): String {
    val ytDlpError = message
        ?.lineSequence()
        ?.lastOrNull { it.trim().startsWith("ERROR:") }
        ?.trim()
        ?.removePrefix("ERROR:")
        ?.trim()
    val rawMessage = message
        ?.lineSequence()
        ?.filter { it.isNotBlank() }
        ?.take(6)
        ?.joinToString("\n")
        ?.ifBlank { null }
        ?: javaClass.simpleName
    val cleaned = rawMessage
        .removePrefix("FAILED:")
        .trim()

    return when {
        !ytDlpError.isNullOrBlank() -> ytDlpError.take(700)
        this is SocketTimeoutException || cleaned.contains("timeout", ignoreCase = true) -> {
            "The backend took too long to respond. Check that the backend and database are running, then try again."
        }
        this is UnknownHostException -> {
            "Cannot find the backend host. Check the backend URL in Profile."
        }
        this is ConnectException || cleaned.contains("failed to connect", ignoreCase = true) -> {
            "Cannot reach the backend. Make sure your phone and backend are on the same network."
        }
        this is IOException || cleaned.contains("network", ignoreCase = true) -> {
            "Network request failed. Check Wi-Fi and backend status, then try again."
        }
        cleaned.contains("session", ignoreCase = true) &&
            (cleaned.contains("expired", ignoreCase = true) || cleaned.contains("invalid", ignoreCase = true)) -> {
            "Your session expired. Please sign in again from Profile."
        }
        cleaned.contains("quota", ignoreCase = true) ||
            cleaned.contains("not enough", ignoreCase = true) -> {
            cleaned.take(700)
        }
        (cleaned.contains("Direct media", ignoreCase = true) ||
            cleaned.contains("YouTube", ignoreCase = true)) &&
            cleaned.contains("403") -> {
            "YouTube temporarily rejected the download link. Please try again."
        }
        cleaned.contains("HTTP 403", ignoreCase = true) || cleaned.contains("403") -> {
            "Your account cannot process this chunk. Check your quota or sign in again."
        }
        else -> cleaned.take(700)
    }
}
