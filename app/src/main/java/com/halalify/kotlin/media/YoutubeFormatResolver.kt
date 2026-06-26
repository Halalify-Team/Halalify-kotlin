package com.halalify.kotlin.media

import android.util.Log
import androidx.activity.ComponentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class YoutubeFormatResolver {
    private var cachedFormatCatalog: CachedFormatCatalog? = null

    fun freshCatalog(url: String): YoutubeFormatCatalog? {
        val cached = cachedFormatCatalog ?: return null
        if (cached.url != url) return null
        if (android.os.SystemClock.elapsedRealtime() - cached.createdAtMs > FORMAT_CATALOG_TTL_MS) {
            return null
        }
        return cached.catalog
    }

    fun cache(url: String, catalog: YoutubeFormatCatalog) {
        cachedFormatCatalog = CachedFormatCatalog(
            url = url,
            catalog = catalog,
            createdAtMs = android.os.SystemClock.elapsedRealtime(),
        )
    }

    fun invalidate(url: String) {
        if (cachedFormatCatalog?.url == url) {
            cachedFormatCatalog = null
        }
    }

    suspend fun resolve(activity: ComponentActivity, url: String): YoutubeFormatCatalog {
        freshCatalog(url)?.let { return it }
        return runCatching {
            withContext(Dispatchers.IO) {
                discoverFastYoutubeFormats(url)
            }
        }.getOrElse { fastError ->
            Log.w("HalalifyDownload", "Fast media resolve failed; using yt-dlp: ${fastError.message}")
            discoverYoutubeFormats(activity, url)
        }.also { catalog ->
            cache(url, catalog)
        }
    }

    suspend fun resolveFreshForDownload(activity: ComponentActivity, url: String): YoutubeFormatCatalog {
        invalidate(url)
        return runCatching {
            discoverYoutubeFormats(activity, url)
        }.getOrElse { ytDlpError ->
            Log.w(
                "HalalifyDownload",
                "yt-dlp media refresh failed; falling back to fast player API: ${ytDlpError.message}"
            )
            withContext(Dispatchers.IO) {
                discoverFastYoutubeFormats(url)
            }
        }.also { catalog ->
            cache(url, catalog)
        }
    }
}

private data class CachedFormatCatalog(
    val url: String,
    val catalog: YoutubeFormatCatalog,
    val createdAtMs: Long,
)

private const val FORMAT_CATALOG_TTL_MS = 60_000L
