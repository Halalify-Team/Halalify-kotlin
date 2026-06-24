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
private const val VIDEO_SEEK_PREROLL_SECONDS = 10
private const val MAX_FINAL_VIDEO_BYTES = 80L * 1024L * 1024L

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

internal suspend fun extractDirectMediaSession(
    activity: ComponentActivity,
    youtubeUrl: String,
): DirectMediaSession = withContext(Dispatchers.IO) {
    validateYoutubeUrl(youtubeUrl)
    initYoutubeDl(activity)

    val extractorAttempts = listOf(
        "youtube:player_client=android_vr",
        "youtube:player_client=android_vr,android",
        "youtube:player_client=android_vr,mweb",
    )
    var lastError = ""

    extractorAttempts.forEachIndexed { index, extractorArgs ->
        val request = YoutubeDLRequest(youtubeUrl)
        request.addOption("--no-playlist")
        request.addOption("--skip-download")
        request.addOption("--socket-timeout", "15")
        request.addOption("--retries", "1")
        request.addOption("--fragment-retries", "1")
        request.addOption("--extractor-retries", "1")
        request.addOption("--extractor-args", extractorArgs)
        request.addOption("--user-agent", YOUTUBE_USER_AGENT)
        request.addOption("--referer", "https://www.youtube.com/")
        request.addOption("-J")

        val response = runCatching {
            YoutubeDL.getInstance().execute(
                request,
                "halalify-direct-session-$index",
                true,
            )
        }.getOrElse { error ->
            lastError = "attempt ${index + 1}: ${error.javaClass.simpleName}: ${error.message}"
            return@forEachIndexed
        }
        if (response.exitCode != 0) {
            lastError = "attempt ${index + 1}: exit=${response.exitCode} stderr=${response.err.takeLast(1600)}"
            return@forEachIndexed
        }

        val jsonLine = response.out
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("{") }
        if (jsonLine == null) {
            lastError = "attempt ${index + 1}: yt-dlp returned no JSON."
            return@forEachIndexed
        }

        val parsed = runCatching { parseDirectMediaSession(JSONObject(jsonLine)) }
        if (parsed.isSuccess) {
            return@withContext parsed.getOrThrow()
        }
        lastError = "attempt ${index + 1}: ${parsed.exceptionOrNull()?.message}"
    }

    error("Could not extract seekable YouTube media URLs. $lastError")
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
): DownloadResult = withContext(Dispatchers.IO) {
    try {
        val startedAt = System.currentTimeMillis()
        val outputDir = File(activity.filesDir, "halalify-download-test")
        outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }
        val extension = media.extension.takeIf { it in setOf("mp4", "webm", "mkv") } ?: "mp4"
        val outputFile = File(outputDir, "video_${UUID.randomUUID().toString().take(8)}.$extension")
        downloadDirectUrlToFile(media, outputFile)

        DownloadResult(
            message = "SUCCESS: video file downloaded locally from the resolved media URL.\n" +
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
    media: DirectMediaResource,
    startSeconds: Int,
    durationSeconds: Int,
    chunkIndex: Int,
): DownloadResult = withContext(Dispatchers.IO) {
    try {
        val safeDuration = durationSeconds.coerceAtLeast(1)
        val startedAt = System.currentTimeMillis()

        val outputDir = File(activity.filesDir, "halalify-video-preview-download")
        outputDir.mkdirs()
        val outputFile = File(
            outputDir,
            "preview_${chunkIndex}_${UUID.randomUUID().toString().take(8)}.mp4",
        )
        val inputSeekSeconds = (startSeconds - VIDEO_SEEK_PREROLL_SECONDS).coerceAtLeast(0)
        val preciseTrimSeconds = startSeconds - inputSeekSeconds
        val commandParts = mutableListOf(
            "-y",
            "-rw_timeout", "20000000",
            "-headers", media.toFfmpegHeaders(),
            "-ss", inputSeekSeconds.toCliNumber(),
            "-i", media.url,
        )
        if (preciseTrimSeconds > 0) {
            commandParts += listOf("-ss", preciseTrimSeconds.toCliNumber())
        }
        commandParts += listOf(
            "-t", safeDuration.toCliNumber(),
            "-map", "0:v:0",
            "-an",
            "-c:v", "mpeg4",
            "-b:v", "700k",
            "-maxrate", "900k",
            "-bufsize", "1400k",
            "-pix_fmt", "yuv420p",
            "-reset_timestamps", "1",
            "-movflags", "+faststart",
            outputFile.absolutePath,
        )
        val command = commandParts.joinToString(" ") { it.ffmpegQuote() }
        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            error(
                "ffmpeg remote video section failed. code=${session.returnCode?.value}\n" +
                    session.allLogsAsString.takeLast(2500)
            )
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            error("ffmpeg remote video section produced no file.")
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

internal suspend fun downloadAudioChunk(
    activity: ComponentActivity,
    media: DirectMediaResource,
    startSeconds: Int,
    durationSeconds: Int,
    chunkIndex: Int,
): DownloadResult = withContext(Dispatchers.IO) {
    try {
        val safeDuration = durationSeconds.coerceAtLeast(1)
        val startedAt = System.currentTimeMillis()

        val outputDir = File(activity.filesDir, "halalify-audio-chunk-download")
        outputDir.mkdirs()

        val outputFile = File(outputDir, "audio_${chunkIndex}_${UUID.randomUUID().toString().take(8)}.m4a")
        val command = listOf(
            "-y",
            "-rw_timeout", "20000000",
            "-headers", media.toFfmpegHeaders(),
            "-ss", startSeconds.toCliNumber(),
            "-i", media.url,
            "-t", safeDuration.toCliNumber(),
            "-vn",
            "-c:a", "aac",
            "-b:a", "128k",
            outputFile.absolutePath,
        ).joinToString(" ") { it.ffmpegQuote() }
        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode)) {
            error(
                "ffmpeg remote audio section failed. code=${session.returnCode?.value}\n" +
                    session.allLogsAsString.takeLast(2500)
            )
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            error("ffmpeg remote audio section produced no file.")
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
            error("Video is larger than the 80 MB device download limit.")
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
                            error("Video exceeded the 80 MB device download limit.")
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

private fun parseDirectMediaSession(json: JSONObject): DirectMediaSession {
    val title = json.optString("title").ifBlank { "Untitled video" }
    val duration = json.optDouble("duration", 0.0).toInt()
    if (duration <= 0) {
        error("yt-dlp media JSON did not include a valid duration.")
    }
    val formats = json.optJSONArray("formats")
        ?: error("yt-dlp media JSON did not include formats.")
    val candidates = buildList {
        for (index in 0 until formats.length()) {
            val format = formats.optJSONObject(index) ?: continue
            val url = format.optString("url")
            val protocol = format.optString("protocol").lowercase()
            val formatNote = format.optString("format_note")
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            if (protocol.contains("m3u8") || protocol.contains("dash")) continue
            if (formatNote.contains("missing pot", ignoreCase = true)) continue
            add(format)
        }
    }

    val audioFormat = candidates
        .filter {
            it.optString("vcodec") == "none" &&
                it.optString("acodec").let { codec -> codec.isNotBlank() && codec != "none" }
        }
        .maxWithOrNull(
            compareBy<JSONObject>(
                { if (it.optString("ext") == "m4a") 1 else 0 },
                { it.optDouble("abr", 0.0) },
            )
        )
        ?: error("No seekable audio-only format was available.")

    val videoFormat = candidates
        .filter {
            it.optString("vcodec").let { codec -> codec.isNotBlank() && codec != "none" } &&
                it.optInt("height", 0).let { height -> height in 1..480 }
        }
        .maxWithOrNull(
            compareBy<JSONObject>(
                { if (it.optString("format_id") == "18") 1 else 0 },
                { if (it.optString("ext") == "mp4") 1 else 0 },
                { it.optInt("height", 0) },
                { it.optDouble("tbr", 0.0) },
            )
        )
        ?: error("No seekable video format up to 480p was available.")

    return DirectMediaSession(
        metadata = VideoMetadata(title = title, durationSeconds = duration),
        audio = audioFormat.toDirectMediaResource(),
        video = videoFormat.toDirectMediaResource(),
    )
}

private fun JSONObject.toDirectMediaResource(): DirectMediaResource {
    val headers = mutableMapOf<String, String>()
    optJSONObject("http_headers")?.let { headerJson ->
        headerJson.keys().forEach { key ->
            val value = headerJson.optString(key)
            if (key.isNotBlank() && value.isNotBlank()) {
                headers[key] = value
            }
        }
    }
    headers.putIfAbsent("User-Agent", YOUTUBE_USER_AGENT)
    headers.putIfAbsent("Referer", "https://www.youtube.com/")
    return DirectMediaResource(
        url = optString("url"),
        headers = headers,
        extension = optString("ext").ifBlank { "mp4" },
        formatId = optString("format_id"),
    )
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

private fun Int.toCliNumber(): String = String.format(Locale.US, "%d", this)
