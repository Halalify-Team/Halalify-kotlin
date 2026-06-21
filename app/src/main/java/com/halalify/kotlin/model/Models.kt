package com.halalify.kotlin.model

// --- Existing data transfer objects ---

internal data class DownloadResult(
    val message: String,
    val path: String?,
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
    RESULT,
    LIBRARY,
    PROFILE,
}

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
    EXTRACTING_AUDIO,
    CLEANING_BACKEND,
    MUXING,
    DONE,
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
)
