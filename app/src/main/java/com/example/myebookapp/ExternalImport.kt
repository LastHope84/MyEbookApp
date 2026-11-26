package com.example.myebookapp

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Files opened from another app (Downloads, a file manager, a share sheet) only
 * carry a temporary read grant. Copying them into app storage lets the library
 * keep working after the granting app is gone.
 */
object ExternalImport {
    private val SUPPORTED = mapOf(
        "epub" to "EPUB",
        "cbz" to "CBZ",
        "cbr" to "CBR",
        "pdf" to "PDF",
        "mobi" to "MOBI",
        "azw3" to "AZW3",
        "azw" to "AZW3",
        "prc" to "AZW3"
    )

    suspend fun import(context: Context, uri: Uri): BookEntity = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
            ?: throw IllegalStateException("This file has no name. Save it to a folder and add the folder instead.")
        val extension = name.substringAfterLast('.', "").lowercase()
        val format = SUPPORTED[extension]
            ?: throw IllegalStateException("This file type is not supported. Try EPUB, CBZ, PDF, MOBI or AZW3.")

        val dir = File(context.filesDir, "imports").apply { mkdirs() }
        val dest = destinationFile(context, dir, uri, name)
        val created = !dest.exists()
        if (created) {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Permission expired or the file moved.")
            input.use { source -> dest.outputStream().buffered().use { out -> source.copyTo(out) } }
        }
        if (dest.length() <= 0) {
            if (created) dest.delete()
            throw IllegalStateException("The file is empty.")
        }
        if (created) ImportCopies.save(context, dest)

        val fileUri = Uri.fromFile(dest)
        val meta = try {
            when (format) {
                "EPUB" -> EpubParser.parseEpub(context, fileUri, dest.name)
                "CBZ" -> ComicParser.parse(context, fileUri, dest.name).let {
                    EpubMetadata(
                        fileName = dest.name,
                        title = it.title,
                        author = it.author,
                        publishingDate = it.publishingDate,
                        publisher = it.publisher,
                        language = it.language,
                        coverBitmap = it.coverBitmap,
                        series = it.series,
                        seriesIndex = it.seriesIndex,
                        tags = it.tags
                    )
                }
                "PDF" -> PdfParser.parse(context, fileUri.toString(), dest.name, dest.length(), dest.lastModified())
                "CBR" -> CbrParser.parse(context, fileUri, dest.name, dest.length(), dest.lastModified())
                else -> basicMeta(dest.name)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            basicMeta(dest.name)
        }

        val cover = meta.coverBitmap?.let { LibraryScanner.saveWebpThumbnail(context, fileUri.toString(), it) }
        val entry = BookEntity(
            uriString = fileUri.toString(),
            fileName = dest.name,
            format = format,
            fileSize = dest.length(),
            lastModified = dest.lastModified(),
            title = meta.title,
            author = meta.author,
            publishingDate = meta.publishingDate,
            publisher = meta.publisher,
            language = meta.language,
            coverPath = cover,
            lastLocatorJson = null,
            progressPercent = 0.0,
            lastOpenedAt = 0L,
            metadataExtracted = true,
            series = meta.series,
            seriesIndex = meta.seriesIndex,
            tags = meta.tags
        )
        val dao = AppDatabase.getInstance(context).bookDao()
        // Adding the file again is an explicit request to keep it.
        dao.clearRemoved(entry.uriString)
        dao.insertIgnore(entry)
        dao.getByUri(entry.uriString)
            ?: throw IllegalStateException("Could not add the file to the library.")
    }

    private fun basicMeta(name: String) = EpubMetadata(
        fileName = name,
        title = name.substringBeforeLast('.').ifBlank { "Untitled" },
        author = "Unknown",
        publishingDate = "Unknown",
        publisher = "Unknown",
        language = "Unknown",
        coverBitmap = null
    )

    private fun displayName(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.lastPathSegment?.substringAfterLast('/')
        val fromProvider = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')
    }

    /** Reopening the same download reuses its copy instead of duplicating storage. */
    private fun destinationFile(context: Context, dir: File, uri: Uri, name: String): File {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "imported" }
        val existing = File(dir, safe)
        val providerSize = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
        if (existing.isFile && providerSize > 0 && existing.length() == providerSize) return existing

        var candidate = existing
        val base = safe.substringBeforeLast('.')
        val extension = safe.substringAfterLast('.', "")
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, if (extension.isEmpty()) "${base}_$index" else "${base}_$index.$extension")
            index++
        }
        return candidate
    }
}
