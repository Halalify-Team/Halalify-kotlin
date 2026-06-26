package com.halalify.kotlin.storage

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.halalify.kotlin.model.LibraryItem
import java.io.File
import java.io.FileInputStream
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal class LibraryRepository(
    private val filesDir: File,
) {
    private val libraryFile: File
        get() = File(filesDir, "library.json")

    fun loadItems(): List<LibraryItem> {
        if (!libraryFile.exists()) return emptyList()

        val jsonArray = JSONArray(libraryFile.readText())
        val items = mutableListOf<LibraryItem>()
        for (index in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(index)
            val filePath = obj.getString("filePath")
            val videoFile = File(filePath)
            if (!videoFile.isFile || videoFile.length() <= 0L) continue

            items.add(
                LibraryItem(
                    id = obj.getString("id"),
                    title = obj.getString("title"),
                    filePath = filePath,
                    originalUrl = obj.getString("originalUrl"),
                    durationSeconds = obj.getInt("durationSeconds"),
                    fileSizeBytes = obj.optLong("fileSizeBytes", videoFile.length()),
                    timestamp = obj.getLong("timestamp"),
                )
            )
        }

        val sortedItems = items.sortedByDescending { it.timestamp }
        persistItems(sortedItems)
        return sortedItems
    }

    fun saveItem(
        title: String,
        filePath: String,
        originalUrl: String,
        durationSeconds: Int,
        currentItems: List<LibraryItem>,
    ): List<LibraryItem> {
        val libraryDir = File(filesDir, "halalify-library").apply { mkdirs() }
        val sourceFile = File(filePath)
        if (!sourceFile.exists()) return currentItems

        val extension = sourceFile.extension.ifBlank { "mp4" }
        val destFile = File(libraryDir, "lib_${UUID.randomUUID().toString().take(8)}.$extension")
        sourceFile.copyTo(destFile, overwrite = true)

        val item = LibraryItem(
            id = UUID.randomUUID().toString(),
            title = title,
            filePath = destFile.absolutePath,
            originalUrl = originalUrl,
            durationSeconds = durationSeconds,
            fileSizeBytes = destFile.length(),
            timestamp = System.currentTimeMillis(),
        )

        return (listOf(item) + currentItems).also(::persistItems)
    }

    fun deleteItem(itemId: String, currentItems: List<LibraryItem>): List<LibraryItem> {
        val remainingItems = currentItems.filterNot { item ->
            if (item.id != itemId) return@filterNot false
            File(item.filePath).delete()
            true
        }
        persistItems(remainingItems)
        return remainingItems
    }

    fun saveVideoToGallery(context: Context, videoFilePath: String, title: String): String? {
        val sourceFile = File(videoFilePath)
        if (!sourceFile.exists()) return null

        val resolver = context.contentResolver
        val cleanTitle = title.replace("[^a-zA-Z0-9]".toRegex(), "_")
        val fileName = "Halalify_${cleanTitle}_${System.currentTimeMillis()}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Video.Media.TITLE, title)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Halalify")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val itemUri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return null
        try {
            resolver.openOutputStream(itemUri).use { outputStream ->
                if (outputStream == null) return null
                FileInputStream(sourceFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }

            return itemUri.toString()
        } catch (error: Exception) {
            runCatching { resolver.delete(itemUri, null, null) }
            return null
        }
    }

    private fun persistItems(items: List<LibraryItem>) {
        val jsonArray = JSONArray()
        for (item in items) {
            val obj = JSONObject()
                .put("id", item.id)
                .put("title", item.title)
                .put("filePath", item.filePath)
                .put("originalUrl", item.originalUrl)
                .put("durationSeconds", item.durationSeconds)
                .put("fileSizeBytes", item.fileSizeBytes)
                .put("timestamp", item.timestamp)
            jsonArray.put(obj)
        }
        libraryFile.writeText(jsonArray.toString())
    }
}
