package com.halalify.kotlin.model

internal data class DownloadResult(
    val message: String,
    val path: String?,
)

internal data class FileResult(
    val message: String,
    val path: String?,
)

internal data class UploadStart(
    val chunkKey: String,
    val minutesRemaining: String?,
)

internal data class VideoMetadata(
    val title: String,
    val durationSeconds: Int,
)
