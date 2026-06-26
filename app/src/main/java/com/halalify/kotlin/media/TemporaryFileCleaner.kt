package com.halalify.kotlin.media

import java.io.File

internal class TemporaryFileCleaner(
    private val root: File,
) {
    fun cleanupAll() {
        temporaryWorkDirNames.forEach { dirName ->
            File(root, dirName).deleteRecursively()
        }
    }

    fun cleanupExcept(keepPaths: List<String>) {
        val keepFiles = keepPaths
            .mapNotNull { path -> runCatching { File(path).canonicalFile }.getOrNull() }
            .toSet()

        temporaryWorkDirNames.forEach { dirName ->
            val dir = File(root, dirName)
            if (!dir.exists()) return@forEach

            val dirCanonical = runCatching { dir.canonicalFile }.getOrNull() ?: dir
            val keepInsideDir = keepFiles.any { keepFile ->
                keepFile.path == dirCanonical.path || keepFile.path.startsWith("${dirCanonical.path}/")
            }
            if (!keepInsideDir) {
                dir.deleteRecursively()
                return@forEach
            }

            dir.listFiles()?.forEach { child ->
                val childCanonical = runCatching { child.canonicalFile }.getOrNull() ?: child
                val shouldKeep = keepFiles.any { keepFile ->
                    keepFile.path == childCanonical.path ||
                        keepFile.path.startsWith("${childCanonical.path}/")
                }
                if (!shouldKeep) {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                }
            }
        }
    }
}

private val temporaryWorkDirNames = listOf(
    "halalify-audio-chunk-download",
    "halalify-audio-concat",
    "halalify-audio-download-test",
    "halalify-audio-extract-test",
    "halalify-audio-normalized",
    "halalify-clean-audio-backend",
    "halalify-clean-audio-mock",
    "halalify-concat",
    "halalify-cut-test",
    "halalify-download-test",
    "halalify-full-audio",
    "halalify-full-video",
    "halalify-mux-test",
    "halalify-playable",
    "halalify-video-preview-download",
)
