package com.halalify.kotlin.media

import android.net.Uri
import android.util.Log
import com.halalify.kotlin.model.VideoMetadata
import com.halalify.kotlin.model.VideoQuality
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

internal fun discoverFastYoutubeFormats(youtubeUrl: String): YoutubeFormatCatalog {
    validateYoutubeUrl(youtubeUrl)
    val videoId = extractYoutubeVideoId(youtubeUrl)
        ?: error("Could not identify the YouTube video ID.")
    // WEB_EMBEDDED_PLAYER produces CDN URLs that googlevideo honors when fetched
    // with browser UA + https://www.youtube.com Referer. The ANDROID client URL
    // signature is rejected with 403 unless the request originates from the
    // YouTube app (with a proof-of-origin token we can't mint), so the previous
    // ANDROID catalog poisoned the cache and forced a ~30s yt-dlp refresh on
    // the first chunk — see HalalifyRange 403 logs.
    val clientVersion = "1.20240701"
    val browserUserAgent =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0 Mobile Safari/537.36"
    val requestJson = JSONObject()
        .put("videoId", videoId)
        .put(
            "context",
            JSONObject().put(
                "client",
                JSONObject()
                    .put("clientName", "WEB_EMBEDDED_PLAYER")
                    .put("clientVersion", clientVersion)
            )
        )
        .toString()
        .toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
        .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
        .header("User-Agent", browserUserAgent)
        .header("X-YouTube-Client-Name", "56")
        .header("X-YouTube-Client-Version", clientVersion)
        .header("Referer", "https://www.youtube.com/")
        .post(requestJson)
        .build()
    val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
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
        val metadata = VideoMetadata(
            title = details.optString("title").ifBlank { "Untitled video" },
            durationSeconds = details.optString("lengthSeconds").toDoubleOrNull()?.toInt()
                ?.takeIf { it > 0 }
                ?: error("YouTube response did not include a valid duration."),
        )
        val streamingData = json.optJSONObject("streamingData")
            ?: error("YouTube response did not include streaming data.")
        val allFormats = buildList {
            listOf("formats", "adaptiveFormats").forEach { arrayName ->
                val array = streamingData.optJSONArray(arrayName) ?: return@forEach
                for (index in 0 until array.length()) {
                    array.optJSONObject(index)?.let(::add)
                }
            }
        }
        val directFormats = allFormats.filter { it.optString("url").startsWith("http") }
        val audioFormats = directFormats.filter { format ->
            val mimeType = format.optString("mimeType")
            mimeType.startsWith("audio/") ||
                (mimeType.startsWith("video/") && mimeType.contains("mp4a"))
        }
        val bestAudio = audioFormats.maxWithOrNull(
            compareBy<JSONObject>(
                { if (it.optString("mimeType").startsWith("audio/")) 1 else 0 },
                { if (it.optString("mimeType").contains("mp4a")) 1 else 0 },
                { it.optInt("bitrate", 0) },
            )
        ) ?: error("YouTube response did not include a direct audio stream.")

        fun resource(format: JSONObject) = DirectMediaResource(
            url = format.getString("url"),
            headers = mapOf(
                "User-Agent" to browserUserAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-us,en;q=0.5",
                "Sec-Fetch-Mode" to "navigate",
                "Referer" to "https://www.youtube.com/",
            ),
            extension = if (format.optString("mimeType").contains("webm")) "webm" else "mp4",
            formatId = format.optInt("itag").toString(),
        )

        val sessions = VideoQuality.entries.mapNotNull { quality ->
            val candidates = directFormats.filter { format ->
                format.optString("mimeType").startsWith("video/") &&
                    bucketHeight(format.optInt("height", 0)) == quality
            }
            val selectedVideo = candidates.maxWithOrNull(
                compareBy<JSONObject>(
                    { if (it.optString("mimeType").contains("video/mp4")) 1 else 0 },
                    { if (it.optString("mimeType").contains("avc1")) 1 else 0 },
                    { if (it.optString("mimeType").contains("mp4a")) 1 else 0 },
                    { it.optInt("fps", 0) },
                    { it.optInt("bitrate", 0) },
                )
            ) ?: return@mapNotNull null
            val selectedAudio = if (selectedVideo.optString("mimeType").contains("mp4a")) {
                selectedVideo
            } else {
                bestAudio
            }
            quality to DirectMediaSession(
                metadata = metadata,
                video = resource(selectedVideo),
                audio = resource(selectedAudio),
            )
        }.toMap()
        if (sessions.isEmpty()) {
            error("YouTube response did not include direct video formats.")
        }
        Log.i(
            "HalalifyDownload",
            "Fast available qualities for ${metadata.title}: " +
                sessions.entries.joinToString { (quality, session) ->
                    "${quality.label}(v=${session.video.formatId},a=${session.audio.formatId})"
                }
        )
        YoutubeFormatCatalog(metadata = metadata, sessionsByQuality = sessions)
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
