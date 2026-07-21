package com.halalify.kotlin.model

// --- Existing data transfer objects ---

internal data class DownloadResult(
    val message: String,
    val path: String?,
    /**
     * True when an upstream YouTube request returned 403, indicating the
     * signed direct media URL has expired. Callers can trigger a one-shot
     * refresh of the format catalog and retry the chunk.
     */
    val forbidden: Boolean = false,
)

internal data class FileResult(
    val message: String,
    val path: String?,
    val minutesRemaining: Double? = null,
)

internal data class UploadStart(
    val chunkKey: String,
    val minutesRemaining: String?,
)

internal data class VideoMetadata(
    val title: String,
    val durationSeconds: Int,
)

internal data class QuotaState(
    val userId: String = "",
    val email: String = "",
    val plan: String = "",
    val accountStatus: String = "",
    val minutesRemaining: Double? = null,
    val minutesTotal: Double? = null,
    val minutesUsed: Double? = null,
    val usagePercent: Int? = null,
    val resetDate: String? = null,
    val customerPortalUrl: String? = null,
    val statusMessage: String = "",
    val isLoading: Boolean = false,
) {
    val hasLiveData: Boolean
        get() = minutesRemaining != null && minutesTotal != null
}

// --- App state models ---

enum class AppScreen {
    INPUT,
    PROCESSING,
    DOWNLOAD,
    RESULT,
    LIBRARY,
    PROFILE,
}

enum class VideoQuality(val label: String, val maxHeight: Int) {
    P144("144p", 144),
    P240("240p", 240),
    P360("360p", 360),
    P480("480p", 480),
    P720("720p", 720),
    P1080("1080p", 1080),
    P1440("1440p", 1440),
    P2160("2160p", 2160),
}

/**
 * Blur strictness levels.
 * - CONSERVATIVE: only blur high-confidence women (fewer false positives, may miss some women)
 * - BALANCED: default trade-off
 * - STRICT: blur aggressively (catches more women, may blur some men / ambiguous faces)
 */
enum class BlurStrictness(val label: String, val femaleBlurThreshold: Float, val ambiguousFemaleThreshold: Float) {
    CONSERVATIVE("Conservative", 0.50f, 0.35f),
    BALANCED("Balanced", 0.35f, 0.20f),
    STRICT("Strict", 0.20f, 0.10f),
}

data class FormatDiscoveryState(
    val url: String = "",
    val videoTitle: String = "",
    val channelName: String = "",
    val thumbnailUrl: String = "",
    val durationSeconds: Int = 0,
    val availableQualities: List<VideoQuality> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

data class LibraryItem(
    val id: String,
    val title: String,
    val filePath: String,
    val originalUrl: String,
    val durationSeconds: Int,
    val fileSizeBytes: Long = 0L,
    val timestamp: Long
)


enum class ChunkPhase {
    WAITING,
    CUTTING_VIDEO,
    BLURRING_VIDEO,
    EXTRACTING_AUDIO,
    CLEANING_BACKEND,
    MUXING,
    DONE,
    SKIPPED,
    ERROR,
}

data class ChunkState(
    val index: Int,
    val totalChunks: Int,
    val phase: ChunkPhase = ChunkPhase.WAITING,
    val errorMessage: String? = null,
)

data class ProcessingState(
    val videoTitle: String = "",
    val originalUrl: String = "",
    val totalDurationSeconds: Int = 0,
    val totalChunks: Int = 0,
    val chunks: List<ChunkState> = emptyList(),
    val completedChunks: Int = 0,
    val currentPhaseLabel: String = "Preparing...",
    val isComplete: Boolean = false,
    val isSavedToGallery: Boolean = false,
    val isLibraryPlayback: Boolean = false,
    val errorMessage: String? = null,
    val playablePaths: List<String> = emptyList(),
    val firstChunkReady: Boolean = false,
    val removeMusic: Boolean = true,
    val blurWomen: Boolean = false,
    val quality: VideoQuality = VideoQuality.P360,
    val blurStrictness: BlurStrictness = BlurStrictness.BALANCED,
    /**
     * Elapsed realtime millis when the current processing job started.
     * Used to project ETA from measured chunk throughput.
     */
    val processingStartedAt: Long = 0L,
    /**
     * When a chunk fails and the pipeline pauses, this is the failed chunk index.
     * Null when processing is running, complete, or fully failed without partial output.
     */
    val pausedChunkIndex: Int? = null,
)
