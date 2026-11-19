package com.example.myebookapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

data class EpubMetadata(
    val fileName: String,
    val title: String,
    val author: String,
    val publishingDate: String,
    val publisher: String,
    val language: String,
    val coverBitmap: Bitmap?
)

object EpubParser {

    fun getFileName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") {
            return uri.lastPathSegment?.substringAfterLast('/') ?: "Unknown"
        }
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    val name = cursor.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Unknown"
    }

    fun parseEpub(context: Context, uri: Uri, fileName: String): EpubMetadata {
        val opfPath = findOpfPath(context, uri)
            ?: throw IllegalStateException("Could not find OPF metadata inside this EPUB.")

        val opfData = readZipEntryBytes(context, uri, opfPath)
            ?: throw IllegalStateException("Could not read $opfPath inside this EPUB.")

        val parsed = opfData.inputStream().use { parseOpf(it) }

        val coverBitmap = parsed.coverHref?.let { href ->
            val resolvedPath = resolveZipPath(opfPath, href)
            val imageBytes = readZipEntryBytes(context, uri, resolvedPath)
            imageBytes?.let { decodeSampledBitmap(it, maxDimension = 1024) }
        }

        return EpubMetadata(
            fileName = fileName,
            title = parsed.title.ifBlank { fileName.substringBeforeLast('.') },
            author = parsed.authors.joinToString(", ").ifBlank { "Unknown" },
            publishingDate = parsed.publishingDate.ifBlank { "Unknown" },
            publisher = parsed.publisher.ifBlank { "Unknown" },
            language = parsed.language.ifBlank { "Unknown" },
            coverBitmap = coverBitmap
        )
    }

    private fun openUriStream(context: Context, uri: Uri): InputStream? {
        return if (uri.scheme == "file") {
            uri.path?.let { File(it).inputStream() }
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }

    private fun findOpfPath(context: Context, uri: Uri): String? {
        val containerBytes = readZipEntryBytes(context, uri, "META-INF/container.xml")
            ?: return null

        containerBytes.inputStream().use { stream ->
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(stream, "UTF-8")

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                if (eventType == XmlPullParser.START_TAG &&
                    parser.name.equals("rootfile", ignoreCase = true)
                ) {
                    val fullPath = parser.getAttributeValue(null, "full-path")
                    if (!fullPath.isNullOrBlank()) return fullPath
                }
                eventType = parser.next()
            }
        }
        return null
    }

    private data class OpfParseResult(
        val title: String,
        val authors: List<String>,
        val publishingDate: String,
        val publisher: String,
        val language: String,
        val coverHref: String?
    )

    private fun parseOpf(stream: InputStream): OpfParseResult {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, "UTF-8")

        var title = ""
        val authors = mutableListOf<String>()
        var publishingDate = ""
        var publisher = ""
        var language = ""

        var epub2CoverId: String? = null
        var epub3CoverHref: String? = null
        val manifestHrefById = mutableMapOf<String, String>()
        var fallbackImageCoverHref: String? = null

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                val tag = parser.name?.substringAfterLast(':')?.lowercase() ?: ""
                when (tag) {
                    "title" -> if (title.isBlank()) {
                        title = safeNextText(parser)
                    }
                    "creator" -> {
                        val creator = safeNextText(parser)
                        if (creator.isNotBlank()) authors.add(creator)
                    }
                    "date" -> if (publishingDate.isBlank()) {
                        publishingDate = safeNextText(parser)
                    }
                    "publisher" -> if (publisher.isBlank()) {
                        publisher = safeNextText(parser)
                    }
                    "language" -> if (language.isBlank()) {
                        language = safeNextText(parser)
                    }
                    "meta" -> {
                        val name = parser.getAttributeValue(null, "name")
                        val content = parser.getAttributeValue(null, "content")
                        if (name.equals("cover", ignoreCase = true) && !content.isNullOrBlank()) {
                            epub2CoverId = content
                        }
                    }
                    "item" -> {
                        val id = parser.getAttributeValue(null, "id")
                        val href = parser.getAttributeValue(null, "href")
                        val mediaType = parser.getAttributeValue(null, "media-type")
                        val properties = parser.getAttributeValue(null, "properties")

                        if (!id.isNullOrBlank() && !href.isNullOrBlank()) {
                            manifestHrefById[id] = href
                        }
                        if (!href.isNullOrBlank() &&
                            properties?.split(' ')?.any { it == "cover-image" } == true
                        ) {
                            epub3CoverHref = href
                        }
                        if (fallbackImageCoverHref == null &&
                            !href.isNullOrBlank() &&
                            mediaType?.startsWith("image/", ignoreCase = true) == true &&
                            (id?.contains("cover", ignoreCase = true) == true ||
                                href.contains("cover", ignoreCase = true))
                        ) {
                            fallbackImageCoverHref = href
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        val resolvedCoverHref = epub3CoverHref
            ?: epub2CoverId?.let { manifestHrefById[it] }
            ?: fallbackImageCoverHref

        return OpfParseResult(
            title = title,
            authors = authors,
            publishingDate = publishingDate,
            publisher = publisher,
            language = language,
            coverHref = resolvedCoverHref
        )
    }

    private fun safeNextText(parser: XmlPullParser): String {
        return runCatching { parser.nextText().trim() }.getOrDefault("")
    }

    private fun readZipEntryBytes(context: Context, uri: Uri, targetPath: String): ByteArray? {
        val normalizedTarget = targetPath.trimStart('/')
        openUriStream(context, uri)?.use { inputStream ->
            ZipInputStream(inputStream).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val entryName = entry.name.trimStart('/')
                    if (!entry.isDirectory && entryName.equals(normalizedTarget, ignoreCase = true)) {
                        return zip.readBytes()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return null
    }

    private fun resolveZipPath(opfPath: String, href: String): String {
        val decoded = Uri.decode(href).trimStart('/')
        val baseDir = opfPath.substringBeforeLast('/', "")
        val combined = if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
        val parts = mutableListOf<String>()
        for (part in combined.split('/')) {
            when {
                part.isEmpty() || part == "." -> {}
                part == ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
    }

    private fun decodeSampledBitmap(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
