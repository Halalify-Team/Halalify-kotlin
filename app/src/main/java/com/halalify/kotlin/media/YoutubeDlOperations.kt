package com.halalify.kotlin.media

import androidx.activity.ComponentActivity
import android.net.Uri
import android.util.Log
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
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
    runCatching {
        extractFastAndroidMediaSession(youtubeUrl)
    }.onSuccess {
        return@withContext it
    }.onFailure { error ->
        Log.w("HalalifyPerf", "Innertube fast path failed, using yt-dlp: ${error.message}")
    }

    initYoutubeDl(activity)

    val extractorAttempts = listOf(
        "youtube:player_client=android,mweb",
        "youtube:player_client=mweb,android",
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
        request.addOption("-f", "18/best[height<=480][vcodec!=none][acodec!=none]/best[height<=480]")
        request.addOption("--user-agent", YOUTUBE_USER_AGENT)
        request.addOption("--referer", "https://www.youtube.com/")
        request.addOption("-j")

        val response = runCatching {
            YoutubeDL.getInstance().execute(
                request,
                "halalify-direct-session-$index",
                true,
            )
        }.getOrElse { error ->
            lastError = "attempt ${index + 1}: ${error.javaClass.simpleName}: ${error.message ?: error}"
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

private fun extractFastAndroidMediaSession(youtubeUrl: String): DirectMediaSession {
    val videoId = extractYoutubeVideoId(youtubeUrl)
        ?: error("Could not identify the YouTube video ID.")
    val clientVersion = "20.10.38"
    val androidUserAgent =
        "com.google.android.youtube/$clientVersion (Linux; U; Android 11) gzip"
    val requestJson = JSONObject()
        .put("videoId", videoId)
        .put(
            "context",
            JSONObject().put(
                "client",
                JSONObject()
                    .put("clientName", "ANDROID")
                    .put("clientVersion", clientVersion)
                    .put("androidSdkVersion", 30)
                    .put("osName", "Android")
                    .put("osVersion", "11")
            )
        )
        .toString()
        .toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
        .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
        .header("User-Agent", androidUserAgent)
        .header("X-YouTube-Client-Name", "3")
        .header("X-YouTube-Client-Version", clientVersion)
        .post(requestJson)
        .build()
    val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    return client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            error("YouTube player API returned HTTP ${response.code}.")
        }
        val json = JSONObject(body)
        val playability = json.optJSONObject("playabilityStatus")?.optString("status")
        if (playability != "OK") {
            val reason = json.optJSONObject("playabilityStatus")?.optString("reason")
            error(reason.ifNullOrBlank { "YouTube video is not playable." })
        }
        val details = json.optJSONObject("videoDetails")
            ?: error("YouTube response did not include video details.")
        val title = details.optString("title").ifBlank { "Untitled video" }
        val duration = details.optString("lengthSeconds").toDoubleOrNull()?.toInt()
            ?.takeIf { it > 0 }
            ?: error("YouTube response did not include a valid duration.")
        val formats = json.optJSONObject("streamingData")?.optJSONArray("formats")
            ?: error("YouTube response did not include progressive formats.")
        val candidates = buildList {
            for (index in 0 until formats.length()) {
                val format = formats.optJSONObject(index) ?: continue
                val url = format.optString("url")
                val mimeType = format.optString("mimeType")
                if (!url.startsWith("http") || !mimeType.contains("video/") ||
                    !mimeType.contains("mp4a")
                ) {
                    continue
                }
                add(format)
            }
        }
        val selected = candidates.firstOrNull { it.optInt("itag") == 18 }
            ?: candidates
                .filter { it.optInt("height", 0) in 1..480 }
                .maxByOrNull { it.optInt("height", 0) }
            ?: error("No progressive YouTube format up to 480p was available.")
        val media = DirectMediaResource(
            url = selected.getString("url"),
            headers = mapOf(
                "User-Agent" to androidUserAgent,
                "Referer" to "https://www.youtube.com/",
            ),
            extension = "mp4",
            formatId = selected.optInt("itag").toString(),
        )
        DirectMediaSession(
            metadata = VideoMetadata(title = title, durationSeconds = duration),
            audio = media,
            video = media,
        )
    }
}

private fun extractYoutubeVideoId(url: String): String? {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    val host = uri.host?.lowercase().orEmpty()
    return when {
        host == "youtu.be" || host.endsWith(".youtu.be") -> {
            uri.pathSegments.firstOrNull()
        }
        host.endsWith("youtube.com") || host.endsWith("youtube-nocookie.com") -> {
            uri.getQueryParameter("v")
                ?: uri.pathSegments
                    .let { segments ->
                        val marker = segments.indexOfFirst { it == "shorts" || it == "embed" }
                        if (marker >= 0) segments.getOrNull(marker + 1) else null
                    }
            }
        else -> null
    }?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{6,}")) }
}

private inline fun String?.ifNullOrBlank(defaultValue: () -> String): String {
    return if (isNullOrBlank()) defaultValue() else this
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
        val session = LocalMediaProxy(media).use { proxy ->
            val localCommand = commandParts
                .map { part -> if (part == media.url) proxy.url else part }
                .joinToString(" ") { it.ffmpegQuote() }
            FFmpegKit.execute(localCommand)
        }
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
        val session = LocalMediaProxy(media).use { proxy ->
            val command = listOf(
                "-y",
                "-rw_timeout", "20000000",
                "-ss", startSeconds.toCliNumber(),
                "-i", proxy.url,
                "-t", safeDuration.toCliNumber(),
                "-vn",
                "-c:a", "aac",
                "-b:a", "128k",
                outputFile.absolutePath,
            ).joinToString(" ") { it.ffmpegQuote() }
            FFmpegKit.execute(command)
        }
        if (!ReturnCode.isSuccess(session.returnCode)) {
            val logs = session.allLogsAsString
            val reason = when {
                logs.contains("403 Forbidden", ignoreCase = true) ||
                    logs.contains("Server returned 403", ignoreCase = true) ->
                    "YouTube stream URL expired."
                logs.contains("Connection refused", ignoreCase = true) ->
                    "The local media stream could not be opened."
                else -> "The requested audio range could not be read."
            }
            Log.w(
                "HalalifyMedia",
                "Audio chunk $chunkIndex failed with code=${session.returnCode?.value}\n" +
                    logs.takeLast(2500)
            )
            error(
                "$reason Retrying with a fresh YouTube stream."
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
    val selectedUrl = json.optString("url")
    val selectedVideoCodec = json.optString("vcodec")
    val selectedAudioCodec = json.optString("acodec")
    if (
        selectedUrl.startsWith("http") &&
        selectedVideoCodec.isNotBlank() && selectedVideoCodec != "none" &&
        selectedAudioCodec.isNotBlank() && selectedAudioCodec != "none"
    ) {
        val selected = json.toDirectMediaResource()
        return DirectMediaSession(
            metadata = VideoMetadata(title = title, durationSeconds = duration),
            audio = selected,
            video = selected,
        )
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

    val videoFormat = candidates
        .filter {
            it.optString("vcodec").let { codec -> codec.isNotBlank() && codec != "none" } &&
                it.optString("acodec").let { codec -> codec.isNotBlank() && codec != "none" } &&
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
        // Progressive format 18 is a reliable fallback when YouTube hides
        // audio-only URLs behind PO tokens. FFmpeg still reads only the range.
        ?: videoFormat

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
