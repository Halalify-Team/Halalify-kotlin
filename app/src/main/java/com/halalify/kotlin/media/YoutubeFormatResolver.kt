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

    /**
     * Resolve a catalog usable for downloading chunk ranges.
     *
     * The InputScreen already populated a catalog via the fast InnerTube player API
     * while the user was typing the URL. YouTube direct CDN URLs stay valid for
     * ~6h, so a catalog fetched seconds ago is perfectly good for byte-range
     * downloads. Reusing it skips the multi-second yt-dlp getInfo round-trip that
     * previously ran after the user tapped "Halalify It", so the first chunk can
     * start streaming almost immediately.
     *
     * If no fresh cached catalog exists (or it expired), InnerTube is tried first
     * because it is dramatically faster than yt-dlp; yt-dlp only runs as a
     * fallback. [forceYtDlpRefresh] is reserved for 403 recovery mid-download.
     */
    suspend fun resolveFreshForDownload(activity: ComponentActivity, url: String): YoutubeFormatCatalog {
        freshCatalog(url)?.let {
            Log.i("HalalifyDownload", "Reusing fresh media catalog (skipping yt-dlp refresh).")
            return it
        }
        return resolveFreshIgnoringCache(activity, url)
    }

    private suspend fun resolveFreshIgnoringCache(
        activity: ComponentActivity,
        url: String,
    ): YoutubeFormatCatalog = runCatching {
        withContext(Dispatchers.IO) {
            discoverFastYoutubeFormats(url)
        }
    }.getOrElse { fastError ->
        Log.w(
            "HalalifyDownload",
            "Fast media refresh failed; using yt-dlp: ${fastError.message}"
        )
        discoverYoutubeFormats(activity, url)
    }.also { catalog ->
        cache(url, catalog)
    }

    /**
     * Fresh-resolve via yt-dlp, used only after a chunk download hits 403
     * (stale signed URL). Invalidates the cache so subsequent chunk downloads
     * pick up the new URLs.
     */
    suspend fun forceYtDlpRefresh(activity: ComponentActivity, url: String): YoutubeFormatCatalog {
        invalidate(url)
        return runCatching {
            discoverYoutubeFormats(activity, url)
        }.getOrElse { ytDlpError ->
            Log.w(
                "HalalifyDownload",
                "yt-dlp forced refresh failed; falling back to fast player API: ${ytDlpError.message}"
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
