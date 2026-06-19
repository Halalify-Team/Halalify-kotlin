package com.halalify.kotlin.media

import androidx.activity.ComponentActivity
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.halalify.kotlin.model.FileResult
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val CHUNK_DURATION_SECONDS = 60

internal suspend fun cutFirstTenSeconds(
    activity: ComponentActivity,
    inputPath: String?,
): FileResult = cutVideoSegment(
    activity = activity,
    inputPath = inputPath,
    startSeconds = 0,
    durationSeconds = CHUNK_DURATION_SECONDS,
    chunkIndex = 0,
)

internal suspend fun cutVideoSegment(
    activity: ComponentActivity,
    inputPath: String?,
    startSeconds: Int,
    durationSeconds: Int,
    chunkIndex: Int,
): FileResult = withContext(Dispatchers.IO) {
    try {
        val source = inputPath?.let(::File)
            ?: error("Download a local video before cutting.")
        if (!source.isFile || source.length() <= 0L) {
            error("Input file is missing or empty: ${source.absolutePath}")
        }

        val outputDir = File(activity.filesDir, "halalify-cut-test")
        outputDir.mkdirs()

        val extension = source.extension.ifBlank { "webm" }
        val outputFile = File(outputDir, "cut_${chunkIndex}_${UUID.randomUUID().toString().take(8)}.$extension")
        val startedAt = System.currentTimeMillis()
        val command = listOf(
            "-y",
            "-ss", startSeconds.toString(),
            "-t", durationSeconds.toString(),
            "-i", source.absolutePath,
            "-c", "copy",
            outputFile.absolutePath,
        ).joinToString(" ") { it.ffmpegQuote() }

        val session = FFmpegKit.execute(command)
        val elapsedMs = System.currentTimeMillis() - startedAt
        val returnCode = session.returnCode
        if (!ReturnCode.isSuccess(returnCode)) {
            error(
                "ffmpeg failed. code=${returnCode?.value}\n" +
                    session.allLogsAsString.takeLast(2500)
            )
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            error("ffmpeg finished but produced no output file.")
        }

        FileResult(
            message = "SUCCESS: video segment cut locally.\n" +
                "chunk: $chunkIndex\n" +
                "start: ${startSeconds}s\n" +
                "duration: ${durationSeconds}s\n" +
                "size: ${outputFile.length()} bytes\n" +
                "path: ${outputFile.absolutePath}\n" +
                "elapsed: ${elapsedMs}ms",
            path = outputFile.absolutePath,
        )
    } catch (error: Throwable) {
        FileResult(
            message = "FAILED: ${error.javaClass.simpleName}: ${error.message}",
            path = null,
        )
    }
}

internal fun String.ffmpegQuote(): String =
    "'${replace("'", "'\\''")}'".toString()

internal suspend fun extractFirstTenSecondsAudio(
    activity: ComponentActivity,
    inputPath: String?,
): FileResult = extractAudioSegment(
    activity = activity,
    inputPath = inputPath,
    startSeconds = 0,
    durationSeconds = CHUNK_DURATION_SECONDS,
    chunkIndex = 0,
)

internal suspend fun extractAudioSegment(
    activity: ComponentActivity,
    inputPath: String?,
    startSeconds: Int,
    durationSeconds: Int,
    chunkIndex: Int,
): FileResult = withContext(Dispatchers.IO) {
    try {
        val source = inputPath?.let(::File)
            ?: error("Download a local audio source before extracting.")
        if (!source.isFile || source.length() <= 0L) {
            error("Input file is missing or empty: ${source.absolutePath}")
        }

        val outputDir = File(activity.filesDir, "halalify-audio-extract-test")
        outputDir.mkdirs()

        val outputFile = File(outputDir, "audio_${chunkIndex}_${UUID.randomUUID().toString().take(8)}.m4a")
        val startedAt = System.currentTimeMillis()
        val command = listOf(
            "-y",
            "-ss", startSeconds.toString(),
            "-t", durationSeconds.toString(),
            "-i", source.absolutePath,
            "-vn",
            "-c:a", "aac",
            "-b:a", "128k",
            outputFile.absolutePath,
        ).joinToString(" ") { it.ffmpegQuote() }

        val session = FFmpegKit.execute(command)
        val elapsedMs = System.currentTimeMillis() - startedAt
        val returnCode = session.returnCode
        if (!ReturnCode.isSuccess(returnCode)) {
            error(
                "ffmpeg audio extraction failed. code=${returnCode?.value}\n" +
                    session.allLogsAsString.takeLast(2500)
            )
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            error("ffmpeg finished but produced no audio file.")
        }

        FileResult(
            message = "SUCCESS: audio segment extracted locally.\n" +
                "chunk: $chunkIndex\n" +
                "start: ${startSeconds}s\n" +
                "duration: ${durationSeconds}s\n" +
                "size: ${outputFile.length()} bytes\n" +
                "path: ${outputFile.absolutePath}\n" +
                "elapsed: ${elapsedMs}ms",
            path = outputFile.absolutePath,
        )
    } catch (error: Throwable) {
        FileResult(
            message = "FAILED: ${error.javaClass.simpleName}: ${error.message}",
            path = null,
        )
    }
}

internal suspend fun muxVideoWithCleanAudio(
    activity: ComponentActivity,
    videoPath: String?,
    cleanAudioPath: String?,
    durationSeconds: Int = CHUNK_DURATION_SECONDS,
): FileResult = withContext(Dispatchers.IO) {
    try {
        val video = videoPath?.let(::File)
            ?: error("Cut a video chunk before muxing.")
        val audio = cleanAudioPath?.let(::File)
            ?: error("Mock a clean audio chunk before muxing.")
        if (!video.isFile || video.length() <= 0L) {
            error("Video chunk is missing or empty: ${video.absolutePath}")
        }
        if (!audio.isFile || audio.length() <= 0L) {
            error("Clean audio chunk is missing or empty: ${audio.absolutePath}")
        }

        val outputDir = File(activity.filesDir, "halalify-mux-test")
        outputDir.mkdirs()

        val isMp4Video = video.extension.equals("mp4", ignoreCase = true)
        val outputFile = File(
            outputDir,
            "mux_${UUID.randomUUID().toString().take(8)}.${if (isMp4Video) "mp4" else "webm"}"
        )
        val startedAt = System.currentTimeMillis()
        val audioCodecArgs = if (isMp4Video) {
            listOf("-c:a", "aac", "-b:a", "128k")
        } else {
            listOf("-c:a", "libopus", "-b:a", "96k")
        }
        val command = (
            listOf(
                "-y",
                "-i", video.absolutePath,
                "-i", audio.absolutePath,
                "-t", durationSeconds.toString(),
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c:v", "copy",
            ) + audioCodecArgs + listOf(
                "-shortest",
                outputFile.absolutePath,
            )
        ).joinToString(" ") { it.ffmpegQuote() }

        val session = FFmpegKit.execute(command)
        val elapsedMs = System.currentTimeMillis() - startedAt
        val returnCode = session.returnCode
        if (!ReturnCode.isSuccess(returnCode)) {
            error(
                "ffmpeg mux failed. code=${returnCode?.value}\n" +
                    session.allLogsAsString.takeLast(2500)
            )
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            error("ffmpeg finished but produced no muxed file.")
        }

        FileResult(
            message = "SUCCESS: video chunk muxed with clean audio locally.\n" +
                "size: ${outputFile.length()} bytes\n" +
                "path: ${outputFile.absolutePath}\n" +
                "elapsed: ${elapsedMs}ms",
            path = outputFile.absolutePath,
        )
    } catch (error: Throwable) {
        FileResult(
            message = "FAILED: ${error.javaClass.simpleName}: ${error.message}",
            path = null,
        )
    }
}

internal suspend fun mockCleanAudio(
    activity: ComponentActivity,
    inputPath: String?,
): FileResult = withContext(Dispatchers.IO) {
    try {
        val source = inputPath?.let(::File)
            ?: error("Extract an audio chunk before mocking clean audio.")
        if (!source.isFile || source.length() <= 0L) {
            error("Input audio file is missing or empty: ${source.absolutePath}")
        }

        val outputDir = File(activity.filesDir, "halalify-clean-audio-mock")
        outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }

        val outputFile = File(outputDir, "clean_${UUID.randomUUID().toString().take(8)}.${source.extension.ifBlank { "m4a" }}")
        val startedAt = System.currentTimeMillis()
        source.copyTo(outputFile, overwrite = true)
        val elapsedMs = System.currentTimeMillis() - startedAt

        if (!outputFile.isFile || outputFile.length() <= 0L) {
            error("Mock clean audio finished but produced no file.")
        }

        FileResult(
            message = "SUCCESS: backend mock returned clean audio locally.\n" +
                "size: ${outputFile.length()} bytes\n" +
                "path: ${outputFile.absolutePath}\n" +
                "elapsed: ${elapsedMs}ms",
            path = outputFile.absolutePath,
        )
    } catch (error: Throwable) {
        FileResult(
            message = "FAILED: ${error.javaClass.simpleName}: ${error.message}",
            path = null,
        )
    }
}

internal suspend fun concatVideoSegments(
    activity: ComponentActivity,
    segmentPaths: List<String>,
): FileResult = withContext(Dispatchers.IO) {
    try {
        if (segmentPaths.isEmpty()) error("No segments to concatenate.")
        val firstFile = File(segmentPaths.first())
        val extension = firstFile.extension.ifBlank { "mp4" }

        val outputDir = File(activity.filesDir, "halalify-concat")
        outputDir.mkdirs()
        val outputFile = File(outputDir, "merged_${segmentPaths.size}_${UUID.randomUUID().toString().take(8)}.$extension")

        val listFile = File(outputDir, "concat_list.txt")
        listFile.writeText(
            segmentPaths.joinToString("\n") { path ->
                "file '${path.replace("'", "'\\''")}'"
            }
        )

        val command = listOf(
            "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", listFile.absolutePath,
            "-c", "copy",
            outputFile.absolutePath,
        ).joinToString(" ") { it.ffmpegQuote() }

        val startedAt = System.currentTimeMillis()
        val session = FFmpegKit.execute(command)
        val elapsedMs = System.currentTimeMillis() - startedAt
        val returnCode = session.returnCode
        if (!ReturnCode.isSuccess(returnCode)) {
            error("ffmpeg concat failed. code=${returnCode?.value}\n" + session.allLogsAsString.takeLast(1500))
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            error("ffmpeg concat finished but produced no output file.")
        }

        FileResult(
            message = "SUCCESS: segments concatenated.\npath: ${outputFile.absolutePath}\nelapsed: ${elapsedMs}ms",
            path = outputFile.absolutePath,
        )
    } catch (error: Throwable) {
        FileResult(
            message = "FAILED: ${error.javaClass.simpleName}: ${error.message}",
            path = null,
        )
    }
}

internal suspend fun concatAudioSegments(
    activity: ComponentActivity,
    audioPaths: List<String>,
): FileResult = withContext(Dispatchers.IO) {
    try {
        if (audioPaths.isEmpty()) error("No audio segments to concatenate.")

        val outputDir = File(activity.filesDir, "halalify-audio-concat")
        outputDir.mkdirs()
        val outputFile = File(outputDir, "merged_audio_${audioPaths.size}_${UUID.randomUUID().toString().take(8)}.m4a")

        val listFile = File(outputDir, "concat_audio_list.txt")
        listFile.writeText(
            audioPaths.joinToString("\n") { path ->
                "file '${path.replace("'", "'\\''")}'"
            }
        )

        // Re-encode to AAC to support varying input formats (e.g. mock m4a with .mp3 extension)
        val command = listOf(
            "-y",
            "-f", "concat",
            "-safe", "0",
            "-i", listFile.absolutePath,
            "-c:a", "aac",
            "-b:a", "128k",
            outputFile.absolutePath,
        ).joinToString(" ") { it.ffmpegQuote() }

        val startedAt = System.currentTimeMillis()
        val session = FFmpegKit.execute(command)
        val elapsedMs = System.currentTimeMillis() - startedAt
        val returnCode = session.returnCode
        if (!ReturnCode.isSuccess(returnCode)) {
            error("ffmpeg audio concat failed. code=${returnCode?.value}\n" + session.allLogsAsString.takeLast(1500))
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            error("ffmpeg audio concat finished but produced no output file.")
        }

        FileResult(
            message = "SUCCESS: audio segments concatenated.\npath: ${outputFile.absolutePath}\nelapsed: ${elapsedMs}ms",
            path = outputFile.absolutePath,
        )
    } catch (error: Throwable) {
        FileResult(
            message = "FAILED: ${error.javaClass.simpleName}: ${error.message}",
            path = null,
        )
    }
}

internal suspend fun muxFullVideoWithCleanAudio(
    activity: ComponentActivity,
    videoPath: String?,
    cleanAudioPath: String?,
    durationSeconds: Int,
): FileResult = withContext(Dispatchers.IO) {
    try {
        val video = videoPath?.let(::File)
            ?: error("Video path is missing.")
        val audio = cleanAudioPath?.let(::File)
            ?: error("Clean audio path is missing.")
        if (!video.isFile || video.length() <= 0L) {
            error("Video file is missing or empty: ${video.absolutePath}")
        }
        if (!audio.isFile || audio.length() <= 0L) {
            error("Clean audio file is missing or empty: ${audio.absolutePath}")
        }

        val outputDir = File(activity.filesDir, "halalify-playable")
        outputDir.mkdirs()

        val isMp4Video = video.extension.equals("mp4", ignoreCase = true)
        val outputFile = File(
            outputDir,
            "playable_${UUID.randomUUID().toString().take(8)}.${if (isMp4Video) "mp4" else "webm"}"
        )
        val startedAt = System.currentTimeMillis()
        
        val audioCodecArgs = if (isMp4Video) {
            listOf("-c:a", "aac", "-b:a", "128k")
        } else {
            listOf("-c:a", "libopus", "-b:a", "96k")
        }

        val command = (
            listOf(
                "-y",
                "-i", video.absolutePath,
                "-i", audio.absolutePath,
                "-map", "0:v:0",
                "-map", "1:a:0",
                "-c:v", "copy",
            ) + audioCodecArgs + listOf(
                "-t", durationSeconds.toString(),
                "-shortest",
                outputFile.absolutePath,
            )
        ).joinToString(" ") { it.ffmpegQuote() }

        val session = FFmpegKit.execute(command)
        val elapsedMs = System.currentTimeMillis() - startedAt
        val returnCode = session.returnCode
        if (!ReturnCode.isSuccess(returnCode)) {
            error(
                "ffmpeg mux full video failed. code=${returnCode?.value}\n" +
                    session.allLogsAsString.takeLast(2500)
            )
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            error("ffmpeg mux full video finished but produced no output file.")
        }

        FileResult(
            message = "SUCCESS: muxed full video with clean audio.\n" +
                "path: ${outputFile.absolutePath}\n" +
                "elapsed: ${elapsedMs}ms",
            path = outputFile.absolutePath,
        )
    } catch (error: Throwable) {
        FileResult(
            message = "FAILED: ${error.javaClass.simpleName}: ${error.message}",
            path = null,
        )
    }
}

