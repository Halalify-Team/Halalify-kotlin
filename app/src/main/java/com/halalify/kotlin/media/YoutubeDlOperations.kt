package com.halalify.kotlin.media

import androidx.activity.ComponentActivity
import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.halalify.kotlin.model.DownloadResult
import com.halalify.kotlin.model.VideoMetadata
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.halalify.kotlin.model.VideoQuality
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val YOUTUBE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0 Mobile Safari/537.36"
private const val YOUTUBE_FFMPEG_HEADERS =
    "User-Agent: Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0 Mobile Safari/537.36\r\nReferer: https://www.youtube.com/\r\n"
private const val VIDEO_SEEK_PREROLL_SECONDS = 10
private const val MAX_FINAL_VIDEO_BYTES = 1024L * 1024L * 1024L

internal data class DirectMediaResource(
    val url: String,
    val headers: Map<String, String>,
    val extension: String,
    val formatId: String,
)

internal data class DirectMediaSession(
    val metadata: VideoMetadata,
    val audio: DirectMediaResource,
    val video: DirectMediaResource,
)

internal data class YoutubeFormatCatalog(
    val metadata: VideoMetadata,
    val sessionsByQuality: Map<VideoQuality, DirectMediaSession>,
) {
    val availableQualities: List<VideoQuality>
        get() = sessionsByQuality.keys.sortedBy { it.maxHeight }

    fun sessionFor(quality: VideoQuality): DirectMediaSession? =
        sessionsByQuality[quality]
            ?: sessionsByQuality.entries
                .filter { it.key.maxHeight <= quality.maxHeight }
                .maxByOrNull { it.key.maxHeight }
            ?.value
}

internal suspend fun discoverYoutubeFormats(
    activity: ComponentActivity,
    youtubeUrl: String,
): YoutubeFormatCatalog = withContext(Dispatchers.IO) {
    validateYoutubeUrl(youtubeUrl)
    initYoutubeDl(activity)

    val cacheDir = File(activity.cacheDir, "yt-dlp-cache").apply { mkdirs() }

    // The library's getInfo() handles -j, JSON parsing, and format extraction
    val request = YoutubeDLRequest(youtubeUrl).apply {
        addOption("--no-playlist")
        addOption("--socket-timeout", "10")
        addOption("--retries", "2")
        addOption("--extractor-retries", "2")
        addOption("--no-update")
        addOption("--no-warnings")
        addOption("--no-call-home")
        addOption("--user-agent", YOUTUBE_USER_AGENT)
        addOption("--remote-components", "ejs:github")
        addOption("--extractor-args", "youtube:player_client=default,android,mweb;skip=hls,dash")
        addOption("--cache-dir", cacheDir.absolutePath)
    }
    val videoInfo = YoutubeDL.getInstance().getInfo(request)

    val title = videoInfo.title?.ifBlank { "Untitled video" } ?: "Untitled video"
    val duration = videoInfo.duration.takeIf { it > 0 }
        ?: error("yt-dlp did not return a valid video duration.")
    val metadata = VideoMetadata(title = title, durationSeconds = duration)

    val allFormats = videoInfo.formats ?: arrayListOf()

    // Video formats: has a URL, has video codec, has height, is not manifest
    val videoFormats = allFormats.filter { format ->
        format.url?.startsWith("http") == true &&
            format.manifestUrl.isNullOrBlank() &&
            format.vcodec.isPresentCodec() &&
            format.height > 0
    }
    // Audio-only formats: has URL, no video, has audio, is not manifest
    val audioFormats = allFormats.filter { format ->
        format.url?.startsWith("http") == true &&
            format.manifestUrl.isNullOrBlank() &&
            !format.vcodec.isPresentCodec() &&
            format.acodec.isPresentCodec()
    }
    val bestAudio = audioFormats.maxByOrNull { it.abr }

    val sessions = VideoQuality.entries.mapNotNull { quality ->
        val candidates = videoFormats.filter { bucketHeight(it.height) == quality }
        val bestVideo = candidates.maxWithOrNull(
            compareBy<VideoFormat>(
                { if (it.ext == "mp4") 1 else 0 },
                { if (it.vcodec?.startsWith("avc") == true) 1 else 0 },
                { it.fps },
                { it.tbr },
            )
        ) ?: return@mapNotNull null

        // If it's progressive (has audio too), use it for both
        val audio = if (bestVideo.acodec.isPresentCodec()) bestVideo else bestAudio
            ?: return@mapNotNull null

        quality to DirectMediaSession(
            metadata = metadata,
            video = bestVideo.toDirectMediaResource(),
            audio = audio.toDirectMediaResource(),
        )
    }.toMap()

    if (sessions.isEmpty()) {
        error("yt-dlp found no downloadable video qualities for this video.")
    }
    Log.i(
        "HalalifyDownload",
        "Available qualities for ${metadata.title}: " +
            sessions.entries.joinToString { (quality, session) ->
                "${quality.label}(v=${session.video.formatId},a=${session.audio.formatId})"
            }
    )
    YoutubeFormatCatalog(metadata = metadata, sessionsByQuality = sessions)
}

// Range-based height bucketing instead of exact matching
internal fun bucketHeight(height: Int): VideoQuality? = when {
    height <= 0 -> null
    height <= 160 -> VideoQuality.P144
    height <= 260 -> VideoQuality.P240
    height <= 400 -> VideoQuality.P360
    height <= 540 -> VideoQuality.P480
    height <= 780 -> VideoQuality.P720
    height <= 1200 -> VideoQuality.P1080
    height <= 1600 -> VideoQuality.P1440
    else -> VideoQuality.P2160
}

// Simple extension to convert library's VideoFormat to our DirectMediaResource
private fun VideoFormat.toDirectMediaResource(): DirectMediaResource {
    val headers = httpHeaders?.entries
        ?.filterNot { (name, _) -> name.equals("Accept-Encoding", ignoreCase = true) }
        ?.associate { it.key to it.value }
        ?.toMutableMap() ?: mutableMapOf()
    headers.putIfAbsent("User-Agent", YOUTUBE_USER_AGENT)
    headers.putIfAbsent("Referer", "https://www.youtube.com/")
    return DirectMediaResource(
        url = url ?: "",
        headers = headers,
        extension = ext?.ifBlank { "mp4" } ?: "mp4",
        formatId = formatId ?: "",
    )
}

private fun String?.isPresentCodec(): Boolean = !isNullOrBlank() && this != "none"

internal suspend fun testYtDlpVersion(activity: ComponentActivity): String = withContext(Dispatchers.IO) {
    try {
        val startedAt = System.currentTimeMillis()
        initYoutubeDl(activity)
        updateYoutubeDlIfNeeded(activity)
        val response = YoutubeDL.getInstance().execute(
            YoutubeDLRequest(listOf("--version")),
            "halalify-version-test",
            true,
        )
        val elapsedMs = System.currentTimeMillis() - startedAt
        val version = response.out.trim().ifBlank { response.err.trim() }
        "SUCCESS: local yt-dlp executed.\nexit: ${response.exitCode}\nversion: $version\nelapsed: ${elapsedMs}ms"
    } catch (error: Throwable) {
        "FAILED: ${error.javaClass.simpleName}: ${error.message}"
    }
}





internal fun calculateChunkCount(durationSeconds: Int): Int =
    when {
        durationSeconds <= 0 -> 1
        durationSeconds <= FIRST_CHUNK_DURATION_SECONDS -> 1
        else -> 1 + ((durationSeconds - FIRST_CHUNK_DURATION_SECONDS + CHUNK_DURATION_SECONDS - 1) / CHUNK_DURATION_SECONDS)
    }

internal suspend fun downloadVideo(
    activity: ComponentActivity,
    media: DirectMediaResource,
): DownloadResult = downloadMediaResource(
    activity = activity,
    media = media,
    directoryName = "halalify-full-video",
    filePrefix = "video",
)

internal suspend fun downloadAudioFile(
    activity: ComponentActivity,
    media: DirectMediaResource,
): DownloadResult = downloadMediaResource(
    activity = activity,
    media = media,
    directoryName = "halalify-full-audio",
    filePrefix = "audio",
)

private suspend fun downloadMediaResource(
    activity: ComponentActivity,
    media: DirectMediaResource,
    directoryName: String,
    filePrefix: String,
): DownloadResult = withContext(Dispatchers.IO) {
    try {
        val startedAt = System.currentTimeMillis()
        val outputDir = File(activity.filesDir, directoryName)
        outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }
        val extension = media.extension.takeIf {
            it in setOf("mp4", "m4a", "webm", "mkv")
        } ?: "mp4"
        val outputFile = File(
            outputDir,
            "${filePrefix}_${UUID.randomUUID().toString().take(8)}.$extension"
        )
        downloadDirectUrlToFile(media, outputFile)

        DownloadResult(
            message = "SUCCESS: media file downloaded locally.\n" +
                "size: ${outputFile.length()} bytes\n" +
                "path: ${outputFile.absolutePath}\n" +
                "elapsed: ${System.currentTimeMillis() - startedAt}ms",
            path = outputFile.absolutePath,
        )
    } catch (error: Throwable) {
        DownloadResult(
            message = "FAILED: ${error.javaClass.simpleName}: ${error.message}",
            path = null,
        )
    }
}

internal suspend fun downloadVideoSection(
    activity: ComponentActivity,
    youtubeUrl: String,
    quality: VideoQuality,
    startSeconds: Int,
    durationSeconds: Int,
    chunkIndex: Int,
): DownloadResult = withContext(Dispatchers.IO) {
    try {
        validateYoutubeUrl(youtubeUrl)
        val safeDuration = durationSeconds.coerceAtLeast(1)
        val startedAt = System.currentTimeMillis()
        val outputDir = File(activity.filesDir, "halalify-video-preview-download")
        outputDir.mkdirs()
        val outputFile = downloadYtDlpSection(
            activity = activity,
            youtubeUrl = youtubeUrl,
            outputDir = outputDir,
            filePrefix = "preview_${chunkIndex}",
            startSeconds = startSeconds,
            durationSeconds = safeDuration,
            attempts = videoSectionAttempts(quality),
            processLabel = "video-$chunkIndex",
            timeoutSeconds = ytDlpChunkTimeoutSeconds(safeDuration),
        )

        val elapsedMs = System.currentTimeMillis() - startedAt
        DownloadResult(
            message = "SUCCESS: video preview section downloaded locally.\n" +
                "start: ${startSeconds}s\n" +
                "duration: ${safeDuration}s\n" +
                "size: ${outputFile.length()} bytes\n" +
                "path: ${outputFile.absolutePath}\n" +
                "elapsed: ${elapsedMs}ms",
            path = outputFile.absolutePath,
        )
    } catch (error: Throwable) {
        DownloadResult(
            message = "FAILED: ${error.javaClass.simpleName}: ${error.message}",
            path = null,
        )
    }
}

internal suspend fun downloadAudioChunk(
    activity: ComponentActivity,
    youtubeUrl: String,
    startSeconds: Int,
    durationSeconds: Int,
    chunkIndex: Int,
): DownloadResult = withContext(Dispatchers.IO) {
    try {
        validateYoutubeUrl(youtubeUrl)
        val safeDuration = durationSeconds.coerceAtLeast(1)
        val startedAt = System.currentTimeMillis()
        val outputDir = File(activity.filesDir, "halalify-audio-chunk-download")
        outputDir.mkdirs()
        val outputFile = downloadYtDlpSection(
            activity = activity,
            youtubeUrl = youtubeUrl,
            outputDir = outputDir,
            filePrefix = "audio_${chunkIndex}",
            startSeconds = startSeconds,
            durationSeconds = safeDuration,
            attempts = AUDIO_SECTION_ATTEMPTS,
            processLabel = "audio-$chunkIndex",
            timeoutSeconds = ytDlpChunkTimeoutSeconds(safeDuration),
        )

        val elapsedMs = System.currentTimeMillis() - startedAt
        DownloadResult(
            message = "SUCCESS: audio chunk downloaded locally.\n" +
                "chunk: $chunkIndex\n" +
                "start: ${startSeconds}s\n" +
                "duration: ${safeDuration}s\n" +
                "size: ${outputFile.length()} bytes\n" +
                "path: ${outputFile.absolutePath}\n" +
                "elapsed: ${elapsedMs}ms",
            path = outputFile.absolutePath,
        )
    } catch (error: Throwable) {
        DownloadResult(
            message = "FAILED: ${error.javaClass.simpleName}: ${error.message}",
            path = null,
        )
    }
}

private data class YtDlpSectionAttempt(
    val format: String,
    val extractorArgs: String,
)

private val AUDIO_SECTION_ATTEMPTS = listOf(
    YtDlpSectionAttempt(
        format = "bestaudio[ext=m4a]/bestaudio/best",
        extractorArgs = "youtube:player_client=android",
    ),
    YtDlpSectionAttempt(
        format = "bestaudio[ext=m4a]/bestaudio/best",
        extractorArgs = "youtube:player_client=mweb",
    ),
    YtDlpSectionAttempt(
        format = "bestaudio/best",
        extractorArgs = "youtube:player_client=default,android,mweb",
    ),
)

private fun videoSectionAttempts(quality: VideoQuality): List<YtDlpSectionAttempt> {
    val maxHeight = quality.maxHeight.toCliNumber()
    return listOf(
        YtDlpSectionAttempt(
            format = "bestvideo[height<=$maxHeight][ext=mp4]/18/bestvideo[height<=$maxHeight]/best[height<=$maxHeight]",
            extractorArgs = "youtube:player_client=android",
        ),
        YtDlpSectionAttempt(
            format = "bestvideo[height<=$maxHeight][ext=mp4]/18/bestvideo[height<=$maxHeight]/best[height<=$maxHeight]",
            extractorArgs = "youtube:player_client=mweb",
        ),
        YtDlpSectionAttempt(
            format = "best[height<=$maxHeight][ext=mp4]/18/best[height<=$maxHeight]/best",
            extractorArgs = "youtube:player_client=default,android,mweb",
        ),
    )
}

private fun downloadYtDlpSection(
    activity: ComponentActivity,
    youtubeUrl: String,
    outputDir: File,
    filePrefix: String,
    startSeconds: Int,
    durationSeconds: Int,
    attempts: List<YtDlpSectionAttempt>,
    processLabel: String,
    timeoutSeconds: Long,
): File {
    initYoutubeDl(activity)
    val section = ytDlpSectionRange(startSeconds, durationSeconds)
    var lastError = ""

    attempts.forEachIndexed { attemptIndex, attempt ->
        val jobId = "${filePrefix}_${attemptIndex}_${UUID.randomUUID().toString().take(8)}"
        val outputTemplate = File(outputDir, "$jobId.%(ext)s").absolutePath
        val progressLog = StringBuilder()
        val request = YoutubeDLRequest(youtubeUrl).apply {
            addOption("--no-playlist")
            addOption("--verbose")
            addOption("--socket-timeout", "15")
            addOption("--retries", "2")
            addOption("--fragment-retries", "2")
            addOption("--extractor-retries", "2")
            addOption("--remote-components", "ejs:github")
            addOption("--extractor-args", attempt.extractorArgs)
            addOption("-f", attempt.format)
            addOption("--download-sections", section)
            addOption("--force-keyframes-at-cuts")
            addOption("--user-agent", YOUTUBE_USER_AGENT)
            addOption("--referer", "https://www.youtube.com/")
            addOption("--no-mtime")
            addOption("--force-overwrites")
            addOption("-o", outputTemplate)
        }
        val processId = "halalify-section-$processLabel-$attemptIndex-${UUID.randomUUID().toString().take(8)}"
        val response = runCatching {
            executeYtDlpWithTimeout(
                request = request,
                processId = processId,
                timeoutSeconds = timeoutSeconds,
                progressLog = progressLog,
            )
        }.getOrElse { error ->
            lastError = "attempt ${attemptIndex + 1} failed: ${error.javaClass.simpleName}: ${error.message}\n" +
                progressLog.takeLast(3000)
            Log.w("HalalifyDownload", "yt-dlp section $processLabel attempt ${attemptIndex + 1} failed\n$lastError")
            null
        }

        val outputFile = outputDir.listFiles()
            ?.filter { file ->
                file.isFile &&
                    file.length() > 0L &&
                    file.name.startsWith(jobId) &&
                    !file.name.endsWith(".part", ignoreCase = true)
            }
            ?.maxByOrNull { it.lastModified() }

        if (response?.exitCode == 0 && outputFile != null) {
            return outputFile
        }
        if (response != null) {
            lastError = "attempt ${attemptIndex + 1} produced no section file.\n" +
                "exit=${response.exitCode}\n" +
                "stderr=${response.err.takeLast(1600)}\n" +
                "stdout=${response.out.takeLast(800)}\n" +
                "log=${progressLog.takeLast(1600)}"
            Log.w("HalalifyDownload", "yt-dlp section $processLabel produced no file\n$lastError")
        }
    }

    error("yt-dlp section download failed after ${attempts.size} attempts for $section.\n$lastError")
}

private fun executeYtDlpWithTimeout(
    request: YoutubeDLRequest,
    processId: String,
    timeoutSeconds: Long,
    progressLog: StringBuilder,
): YoutubeDLResponse {
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    val future = executor.submit<YoutubeDLResponse> {
        YoutubeDL.getInstance().execute(
            request,
            processId,
            true,
        ) { _, _, line ->
            if (!line.isNullOrBlank()) {
                progressLog.append(line.take(500)).append('\n')
                if (progressLog.length > 5000) {
                    progressLog.delete(0, progressLog.length - 5000)
                }
            }
        }
    }
    return try {
        future.get(timeoutSeconds, TimeUnit.SECONDS)
    } catch (error: TimeoutException) {
        YoutubeDL.getInstance().destroyProcessById(processId)
        future.cancel(true)
        error("yt-dlp timed out after ${timeoutSeconds}s.")
    } finally {
        executor.shutdownNow()
    }
}

private fun ytDlpChunkTimeoutSeconds(durationSeconds: Int): Long =
    (durationSeconds * 4L + 45L).coerceAtLeast(75L).coerceAtMost(240L)

private fun ytDlpSectionRange(startSeconds: Int, durationSeconds: Int): String {
    val safeStart = startSeconds.coerceAtLeast(0)
    val safeEnd = (safeStart + durationSeconds.coerceAtLeast(1)).coerceAtLeast(safeStart + 1)
    return "*${safeStart.toYtDlpTimestamp()}-${safeEnd.toYtDlpTimestamp()}"
}

private fun downloadDirectUrlToFile(media: DirectMediaResource, outputFile: File) {
    val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    val requestBuilder = Request.Builder()
        .url(media.url)
        .get()
    media.headers.forEach { (name, value) ->
        requestBuilder.header(name, value)
    }
    val request = requestBuilder.build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            error("Direct media download failed. http=${response.code}")
        }
        val body = response.body ?: error("Direct media download returned empty body.")
        val declaredLength = body.contentLength()
        if (declaredLength > MAX_FINAL_VIDEO_BYTES) {
            error("Video is larger than the 1 GB device download limit.")
        }
        try {
            body.byteStream().use { input ->
                FileOutputStream(outputFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        totalBytes += read
                        if (totalBytes > MAX_FINAL_VIDEO_BYTES) {
                            error("Video exceeded the 1 GB device download limit.")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: Throwable) {
            outputFile.delete()
            throw error
        }
    }
    if (!outputFile.isFile || outputFile.length() <= 0L) {
        error("Direct media download produced no file.")
    }
}

private fun DirectMediaResource.toFfmpegHeaders(): String {
    return headers.entries
        .filterNot { (name, _) -> name.equals("Accept-Encoding", ignoreCase = true) }
        .joinToString(separator = "\r\n", postfix = "\r\n") { (name, value) ->
            "$name: $value"
        }
        .ifBlank { YOUTUBE_FFMPEG_HEADERS }
}

private fun initYoutubeDl(activity: ComponentActivity) {
    val context = activity.applicationContext
    YoutubeDL.getInstance().init(context)
    FFmpeg.getInstance().init(context)
    Aria2c.getInstance().init(context)
}

private fun updateYoutubeDlIfNeeded(activity: ComponentActivity) {
    val preferences = activity.getSharedPreferences("halalify_tools", 0)
    val lastUpdateAt = preferences.getLong("yt_dlp_updated_at", 0L)
    val updateIntervalMs = TimeUnit.HOURS.toMillis(24)
    if (System.currentTimeMillis() - lastUpdateAt < updateIntervalMs) return

    // Cap the update attempt at 30 seconds so it never hangs indefinitely.
    val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
    val future = executor.submit<YoutubeDL.UpdateStatus> {
        YoutubeDL.getInstance().updateYoutubeDL(
            activity.applicationContext,
            YoutubeDL.UpdateChannel.STABLE,
        )
    }
    runCatching {
        val status = future.get(30, TimeUnit.SECONDS)
        preferences.edit()
            .putLong("yt_dlp_updated_at", System.currentTimeMillis())
            .apply()
        Log.i("HalalifyDownload", "yt-dlp runtime update status: $status")
    }.onFailure {
        future.cancel(true)
        Log.w("HalalifyDownload", "Could not update yt-dlp runtime (ignored): ${it.message}")
    }
    executor.shutdownNow()
}

internal fun validateYoutubeUrl(url: String) {
    if (url.isBlank()) {
        error("Paste a YouTube URL first.")
    }
    val normalized = url.lowercase()
    if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
        error("URL must start with http:// or https://")
    }
}

private fun Int.toCliNumber(): String = String.format(Locale.US, "%d", this)

private fun Int.toYtDlpTimestamp(): String {
    val hours = this / 3600
    val minutes = (this % 3600) / 60
    val seconds = this % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}
