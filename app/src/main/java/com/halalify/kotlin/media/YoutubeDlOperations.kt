package com.halalify.kotlin.media

import androidx.activity.ComponentActivity
import com.halalify.kotlin.model.DownloadResult
import com.halalify.kotlin.model.VideoMetadata
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal suspend fun testYtDlpVersion(activity: ComponentActivity): String = withContext(Dispatchers.IO) {
    try {
        val startedAt = System.currentTimeMillis()
        YoutubeDL.getInstance().init(activity.applicationContext)
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

internal suspend fun fetchVideoMetadata(
    activity: ComponentActivity,
    youtubeUrl: String,
): VideoMetadata = withContext(Dispatchers.IO) {
    validateYoutubeUrl(youtubeUrl)
    YoutubeDL.getInstance().init(activity.applicationContext)

    val request = YoutubeDLRequest(youtubeUrl)
    request.addOption("--no-playlist")
    request.addOption("--extractor-args", "youtube:player_client=mweb,android;formats=missing_pot")
    request.addOption("--user-agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0 Mobile Safari/537.36")
    request.addOption("--referer", "https://www.youtube.com/")

    val info: VideoInfo = YoutubeDL.getInstance().getInfo(request)
    val duration = info.duration
    if (duration <= 0) {
        error("yt-dlp metadata did not include a valid duration.")
    }

    VideoMetadata(
        title = info.title?.takeIf { it.isNotBlank() } ?: info.fulltitle ?: "Untitled video",
        durationSeconds = duration,
    )
}

internal fun calculateChunkCount(durationSeconds: Int): Int =
    ((durationSeconds + CHUNK_DURATION_SECONDS - 1) / CHUNK_DURATION_SECONDS).coerceAtLeast(1)

internal suspend fun downloadVideo(
    activity: ComponentActivity,
    youtubeUrl: String,
): DownloadResult = withContext(Dispatchers.IO) {
    try {
        validateYoutubeUrl(youtubeUrl)
        val startedAt = System.currentTimeMillis()
        YoutubeDL.getInstance().init(activity.applicationContext)

        val outputDir = File(activity.filesDir, "halalify-download-test")
        outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }

        val attempts = listOf(
            "18/best[height<=480]/best" to "youtube:player_client=mweb,android;formats=missing_pot",
            "bestvideo[height<=360]/bestvideo" to "youtube:player_client=default,android;formats=missing_pot",
            "best[height<=480]/best[height<=720]/bestvideo[height<=360]/best" to "youtube:player_client=mweb,android;formats=missing_pot",
            "best*[height<=480]/best[height<=480]/best" to "youtube:player_client=default,mweb,android;formats=missing_pot",
        )
        var lastError = ""
        var didUpdate = false
        var outputFile: File? = null

        attempts.forEachIndexed { index, (format, extractorArgs) ->
            if (outputFile != null) return@forEachIndexed

            val jobId = "test_${index}_${UUID.randomUUID().toString().take(8)}"
            val outputTemplate = File(outputDir, "$jobId.%(ext)s").absolutePath
            val progressLog = StringBuilder()

            val request = YoutubeDLRequest(youtubeUrl)
            request.addOption("--no-playlist")
            request.addOption("--verbose")
            request.addOption("--extractor-args", extractorArgs)
            request.addOption("-f", format)
            request.addOption("--user-agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0 Mobile Safari/537.36")
            request.addOption("--referer", "https://www.youtube.com/")
            request.addOption("--no-mtime")
            request.addOption("--fixup", "never")
            request.addOption("--max-filesize", "80M")
            request.addOption("--force-overwrites")
            request.addOption("-o", outputTemplate)

            val response = try {
                YoutubeDL.getInstance().execute(
                    request,
                    "halalify-download-test",
                    true,
                ) { _, _, line ->
                    if (!line.isNullOrBlank()) {
                        progressLog.append(line.take(500)).append('\n')
                        if (progressLog.length > 4000) {
                            progressLog.delete(0, progressLog.length - 4000)
                        }
                    }
                }
            } catch (error: Throwable) {
                val message = "attempt ${index + 1} failed: ${error.javaClass.simpleName}: ${error.message}\n" +
                    progressLog.takeLast(3500)
                lastError = message
                if (!didUpdate && message.isLikelyYoutubeExtractorIssue()) {
                    didUpdate = true
                    runCatching {
                        YoutubeDL.getInstance().updateYoutubeDL(
                            activity.applicationContext,
                            YoutubeDL.UpdateChannel._NIGHTLY,
                        )
                    }.onFailure { updateError ->
                        lastError += "\nyt-dlp nightly update failed: ${updateError.message}"
                    }
                }
                null
            }

            val candidate = outputDir.listFiles()
                ?.filter { it.name.startsWith(jobId) && it.isFile && it.length() > 0L }
                ?.maxByOrNull { it.lastModified() }

            if (candidate != null) {
                outputFile = candidate
            } else if (response != null) {
                lastError = "attempt ${index + 1} produced no file.\n" +
                    "exit=${response.exitCode}\nstderr=${response.err.takeLast(1200)}\nlog=${progressLog.takeLast(1200)}"
            }
        }

        val finalOutputFile = outputFile ?: error(
            "yt-dlp video download failed after ${attempts.size} attempts.\n$lastError"
        )

        val elapsedMs = System.currentTimeMillis() - startedAt
        DownloadResult(
            message = "SUCCESS: video file downloaded locally.\n" +
                "size: ${finalOutputFile.length()} bytes\n" +
                "path: ${finalOutputFile.absolutePath}\n" +
                "elapsed: ${elapsedMs}ms",
            path = finalOutputFile.absolutePath,
        )
    } catch (error: Throwable) {
        DownloadResult(
            message = "FAILED: ${error.javaClass.simpleName}: ${error.message}",
            path = null,
        )
    }
}

internal suspend fun downloadAudio(
    activity: ComponentActivity,
    youtubeUrl: String,
): DownloadResult = withContext(Dispatchers.IO) {
    try {
        validateYoutubeUrl(youtubeUrl)
        val startedAt = System.currentTimeMillis()
        YoutubeDL.getInstance().init(activity.applicationContext)

        val outputDir = File(activity.filesDir, "halalify-audio-download-test")
        outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }

        val jobId = "audio_${UUID.randomUUID().toString().take(8)}"
        val outputTemplate = File(outputDir, "$jobId.%(ext)s").absolutePath
        val progressLog = StringBuilder()

        val request = YoutubeDLRequest(youtubeUrl)
        request.addOption("--no-playlist")
        request.addOption("--verbose")
        request.addOption("--extractor-args", "youtube:player_client=default,android;formats=missing_pot")
        request.addOption("-f", "bestaudio[ext=m4a]/bestaudio")
        request.addOption("--no-mtime")
        request.addOption("--fixup", "never")
        request.addOption("--max-filesize", "20M")
        request.addOption("--force-overwrites")
        request.addOption("-o", outputTemplate)

        val response = try {
            YoutubeDL.getInstance().execute(
                request,
                "halalify-audio-download-test",
                true,
            ) { _, _, line ->
                if (!line.isNullOrBlank()) {
                    progressLog.append(line.take(500)).append('\n')
                    if (progressLog.length > 4000) {
                        progressLog.delete(0, progressLog.length - 4000)
                    }
                }
            }
        } catch (error: Throwable) {
            error(
                "yt-dlp audio download failed: ${error.javaClass.simpleName}: ${error.message}\n" +
                    progressLog.takeLast(3500)
            )
        }

        val outputFile = outputDir.listFiles()
            ?.filter { it.name.startsWith(jobId) && it.isFile && it.length() > 0L }
            ?.maxByOrNull { it.lastModified() }
            ?: error(
                "yt-dlp finished but produced no audio file.\nexit=${response.exitCode}\nstderr=${response.err.takeLast(1200)}\nlog=${progressLog.takeLast(1200)}"
            )

        val elapsedMs = System.currentTimeMillis() - startedAt
        DownloadResult(
            message = "SUCCESS: audio source downloaded locally.\n" +
                "exit: ${response.exitCode}\n" +
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

internal fun validateYoutubeUrl(url: String) {
    if (url.isBlank()) {
        error("Paste a YouTube URL first.")
    }
    val normalized = url.lowercase()
    if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
        error("URL must start with http:// or https://")
    }
}

private fun String.isLikelyYoutubeExtractorIssue(): Boolean {
    val text = lowercase()
    return "requested format is not available" in text ||
        "signature solving failed" in text ||
        "challenge" in text ||
        "player" in text
}
