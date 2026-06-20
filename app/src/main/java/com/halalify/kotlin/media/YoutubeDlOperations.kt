package com.halalify.kotlin.media

import androidx.activity.ComponentActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.halalify.kotlin.model.DownloadResult
import com.halalify.kotlin.model.VideoMetadata
import com.yausername.aria2c.Aria2c
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private const val YOUTUBE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0 Mobile Safari/537.36"
private const val YOUTUBE_FFMPEG_HEADERS =
    "User-Agent: Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0 Mobile Safari/537.36\r\nReferer: https://www.youtube.com/\r\n"

internal suspend fun testYtDlpVersion(activity: ComponentActivity): String = withContext(Dispatchers.IO) {
    try {
        val startedAt = System.currentTimeMillis()
        initYoutubeDl(activity)
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
    initYoutubeDl(activity)

    val request = YoutubeDLRequest(youtubeUrl)
    request.addOption("--no-playlist")
    request.addOption("--skip-download")
    request.addOption("--socket-timeout", "15")
    request.addOption("--retries", "1")
    request.addOption("--fragment-retries", "1")
    request.addOption("--extractor-retries", "1")
    request.addOption("--extractor-args", "youtube:player_client=android,mweb")
    request.addOption("--user-agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0 Mobile Safari/537.36")
    request.addOption("--referer", "https://www.youtube.com/")
    request.addOption("--print", "%(title)s")
    request.addOption("--print", "%(duration)s")

    val response = YoutubeDL.getInstance().execute(
        request,
        "halalify-metadata",
        true,
    )
    if (response.exitCode != 0) {
        error("yt-dlp metadata failed. exit=${response.exitCode} stderr=${response.err.takeLast(1200)}")
    }

    val outputLines = response.out
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()
    val durationLineIndex = outputLines.indexOfLast { it.toDurationSecondsOrNull() != null }
    if (durationLineIndex < 0) {
        error("yt-dlp metadata did not include a valid duration. output=${response.out.takeLast(1200)}")
    }
    val duration = outputLines[durationLineIndex].toDurationSecondsOrNull()
        ?: error("yt-dlp metadata duration could not be parsed.")
    val title = outputLines
        .take(durationLineIndex)
        .lastOrNull { it.isNotBlank() && it.toDurationSecondsOrNull() == null }
        ?: "Untitled video"

    VideoMetadata(
        title = title,
        durationSeconds = duration,
    )
}

internal fun calculateChunkCount(durationSeconds: Int): Int =
    when {
        durationSeconds <= 0 -> 1
        durationSeconds <= FIRST_CHUNK_DURATION_SECONDS -> 1
        else -> 1 + ((durationSeconds - FIRST_CHUNK_DURATION_SECONDS + CHUNK_DURATION_SECONDS - 1) / CHUNK_DURATION_SECONDS)
    }

internal suspend fun downloadVideo(
    activity: ComponentActivity,
    youtubeUrl: String,
): DownloadResult = withContext(Dispatchers.IO) {
    try {
        validateYoutubeUrl(youtubeUrl)
        val startedAt = System.currentTimeMillis()
        initYoutubeDl(activity)

        val outputDir = File(activity.filesDir, "halalify-download-test")
        outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }

        val attempts = listOf(
            "18/best[height<=480]/best" to "youtube:player_client=android",
            "18/best[height<=480]/best" to "youtube:player_client=mweb",
            "best[height<=480]/best[height<=720]/bestvideo[height<=360]/best" to "youtube:player_client=android,mweb",
            "best*[height<=480]/best[height<=480]/best" to "youtube:player_client=default,android,mweb",
        )
        var lastError = ""
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
            request.addOption("--downloader", "libaria2c.so")
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

internal suspend fun downloadVideoSection(
    activity: ComponentActivity,
    youtubeUrl: String,
    startSeconds: Int,
    durationSeconds: Int,
): DownloadResult = withContext(Dispatchers.IO) {
    try {
        validateYoutubeUrl(youtubeUrl)
        val safeDuration = durationSeconds.coerceAtLeast(1)
        val startedAt = System.currentTimeMillis()
        initYoutubeDl(activity)

        val outputDir = File(activity.filesDir, "halalify-video-preview-download")
        outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }

        val media = getDirectMedia(
            youtubeUrl = youtubeUrl,
            format = "18/best[height<=480]/best",
            extractorArgs = "youtube:player_client=mweb,android;formats=missing_pot",
            jobName = "halalify-video-url",
        )
        val sourceFile = File(outputDir, "preview_source_${UUID.randomUUID().toString().take(8)}.mp4")
        downloadDirectUrlToFile(media, sourceFile)
        val outputFile = File(outputDir, "preview_${UUID.randomUUID().toString().take(8)}.mp4")
        try {
            val command = listOf(
                "-y",
                "-ss", startSeconds.toCliNumber(),
                "-t", safeDuration.toCliNumber(),
                "-i", sourceFile.absolutePath,
                "-map", "0:v:0",
                "-map", "0:a?",
                "-c", "copy",
                outputFile.absolutePath,
            ).joinToString(" ") { it.ffmpegQuote() }
            val session = FFmpegKit.execute(command)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                error("ffmpeg local video section failed. code=${session.returnCode?.value}\n${session.allLogsAsString.takeLast(2500)}")
            }
        } finally {
            sourceFile.delete()
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            error("ffmpeg local video section produced no file.")
        }

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

internal suspend fun downloadAudio(
    activity: ComponentActivity,
    youtubeUrl: String,
): DownloadResult = withContext(Dispatchers.IO) {
    try {
        validateYoutubeUrl(youtubeUrl)
        val startedAt = System.currentTimeMillis()
        initYoutubeDl(activity)

        val outputDir = File(activity.filesDir, "halalify-audio-download-test")
        outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }

        val attempts = listOf(
            "bestaudio[ext=m4a]/bestaudio/best" to "youtube:player_client=android",
            "bestaudio[ext=m4a]/bestaudio/best" to "youtube:player_client=mweb",
            "bestaudio/best" to "youtube:player_client=default,android,mweb",
        )
        var lastError = ""
        var outputFile: File? = null

        attempts.forEachIndexed { index, (format, extractorArgs) ->
            if (outputFile != null) return@forEachIndexed

            val jobId = "audio_${index}_${UUID.randomUUID().toString().take(8)}"
            val outputTemplate = File(outputDir, "$jobId.%(ext)s").absolutePath
            val progressLog = StringBuilder()

            val request = YoutubeDLRequest(youtubeUrl)
            request.addOption("--no-playlist")
            request.addOption("--verbose")
            request.addOption("--extractor-args", extractorArgs)
            request.addOption("-f", format)
            request.addOption("--downloader", "libaria2c.so")
            request.addOption("--user-agent", YOUTUBE_USER_AGENT)
            request.addOption("--referer", "https://www.youtube.com/")
            request.addOption("--no-mtime")
            request.addOption("--fixup", "never")
            request.addOption("--max-filesize", "30M")
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
                val message = "attempt ${index + 1} failed: ${error.javaClass.simpleName}: ${error.message}\n" +
                    progressLog.takeLast(3500)
                lastError = message
                null
            }

            val candidate = outputDir.listFiles()
                ?.filter { it.name.startsWith(jobId) && it.isFile && it.length() > 0L }
                ?.maxByOrNull { it.lastModified() }

            if (candidate != null) {
                outputFile = candidate
            } else if (response != null) {
                lastError = "attempt ${index + 1} produced no audio file.\n" +
                    "exit=${response.exitCode}\nstderr=${response.err.takeLast(1200)}\nlog=${progressLog.takeLast(1200)}"
            }
        }

        val finalOutputFile = outputFile ?: error(
            "yt-dlp audio download failed after ${attempts.size} attempts.\n$lastError"
        )

        val elapsedMs = System.currentTimeMillis() - startedAt
        DownloadResult(
            message = "SUCCESS: audio source downloaded locally.\n" +
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
        initYoutubeDl(activity)

        val outputDir = File(activity.filesDir, "halalify-audio-chunk-download")
        outputDir.mkdirs()

        val media = getDirectMedia(
            youtubeUrl = youtubeUrl,
            format = "bestaudio[ext=m4a]/bestaudio/best",
            extractorArgs = "youtube:player_client=default,android;formats=missing_pot",
            jobName = "halalify-audio-url",
        )
        val sourceFile = File(outputDir, "source_audio_${chunkIndex}_${UUID.randomUUID().toString().take(8)}.m4a")
        downloadDirectUrlToFile(media, sourceFile)
        val outputFile = File(outputDir, "audio_${chunkIndex}_${UUID.randomUUID().toString().take(8)}.m4a")
        try {
            val command = listOf(
                "-y",
                "-ss", startSeconds.toCliNumber(),
                "-t", safeDuration.toCliNumber(),
                "-i", sourceFile.absolutePath,
                "-vn",
                "-c:a", "aac",
                "-b:a", "128k",
                outputFile.absolutePath,
            ).joinToString(" ") { it.ffmpegQuote() }
            val session = FFmpegKit.execute(command)
            if (!ReturnCode.isSuccess(session.returnCode)) {
                error("ffmpeg local audio section failed. code=${session.returnCode?.value}\n${session.allLogsAsString.takeLast(2500)}")
            }
        } finally {
            sourceFile.delete()
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            error("ffmpeg local audio section produced no file.")
        }

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

private fun getDirectMedia(
    youtubeUrl: String,
    format: String,
    extractorArgs: String,
    jobName: String,
): DirectMedia {
    val request = YoutubeDLRequest(youtubeUrl)
    request.addOption("--no-playlist")
    request.addOption("--skip-download")
    request.addOption("--socket-timeout", "15")
    request.addOption("--retries", "1")
    request.addOption("--fragment-retries", "1")
    request.addOption("--extractor-retries", "1")
    request.addOption("--extractor-args", extractorArgs)
    request.addOption("-f", format)
    request.addOption("--user-agent", YOUTUBE_USER_AGENT)
    request.addOption("--referer", "https://www.youtube.com/")
    request.addOption("-j")

    val response = YoutubeDL.getInstance().execute(request, jobName, true)
    if (response.exitCode != 0) {
        error("yt-dlp direct URL failed. exit=${response.exitCode} stderr=${response.err.takeLast(1200)}")
    }
    val jsonLine = response.out
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("{") }
        ?: error("yt-dlp direct media output had no JSON: ${response.out.takeLast(1200)}")
    val json = JSONObject(jsonLine)
    val url = json.optString("url").takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?: error("yt-dlp direct media JSON had no url: ${jsonLine.take(1200)}")
    val headers = mutableMapOf<String, String>()
    json.optJSONObject("http_headers")?.let { headerJson ->
        headerJson.keys().forEach { key ->
            val value = headerJson.optString(key)
            if (key.isNotBlank() && value.isNotBlank()) {
                headers[key] = value
            }
        }
    }
    headers.putIfAbsent("User-Agent", YOUTUBE_USER_AGENT)
    headers.putIfAbsent("Referer", "https://www.youtube.com/")
    return DirectMedia(url = url, headers = headers)
}

private fun downloadDirectUrlToFile(media: DirectMedia, outputFile: File) {
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
        FileOutputStream(outputFile).use { output ->
            body.byteStream().copyTo(output)
        }
    }
    if (!outputFile.isFile || outputFile.length() <= 0L) {
        error("Direct media download produced no file.")
    }
}

private data class DirectMedia(
    val url: String,
    val headers: Map<String, String>,
)

private fun initYoutubeDl(activity: ComponentActivity) {
    val context = activity.applicationContext
    YoutubeDL.getInstance().init(context)
    Aria2c.getInstance().init(context)
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

private fun Int.toYtDlpTimestamp(): String {
    val hours = this / 3600
    val minutes = (this % 3600) / 60
    val seconds = this % 60
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}

private fun Int.toCliNumber(): String = String.format(Locale.US, "%d", this)

private fun String.toDurationSecondsOrNull(): Int? {
    val seconds = toDoubleOrNull()?.toInt() ?: return null
    return seconds.takeIf { it > 0 }
}
