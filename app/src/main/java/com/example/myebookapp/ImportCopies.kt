package com.example.myebookapp

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Keeps a user-visible copy of files added with Add File or opened from another
 * app. The library keeps using the private working copy; this is a convenience
 * copy the user can find in Downloads/MyEbookApp.
 */
object ImportCopies {
    private const val FOLDER = "MyEbookApp"

    fun save(context: Context, source: File): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToDownloads(context, source)
        } else {
            saveToExternalFiles(context, source)
        }
    }.getOrDefault(false)

    private fun saveToDownloads(context: Context, source: File): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, source.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeType(source.name))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().buffered().use { it.copyTo(out) }
            } ?: error("No output stream")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null
            )
            true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }

    // Android 9 has no MediaStore Downloads collection; use the app's external
    // folder, which needs no permission and is visible to file managers there.
    private fun saveToExternalFiles(context: Context, source: File): Boolean {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return false
        val dir = File(base, FOLDER).apply { mkdirs() }
        var target = File(dir, source.name)
        val stem = source.name.substringBeforeLast('.')
        val extension = source.name.substringAfterLast('.', "")
        var index = 2
        while (target.exists()) {
            target = File(dir, if (extension.isEmpty()) "${stem}_$index" else "${stem}_$index.$extension")
            index++
        }
        source.inputStream().buffered().use { input ->
            target.outputStream().buffered().use { input.copyTo(it) }
        }
        return target.isFile && target.length() == source.length()
    }

    private fun mimeType(name: String): String =
        MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
}
