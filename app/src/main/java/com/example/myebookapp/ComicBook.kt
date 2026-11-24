package com.example.myebookapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.util.LruCache
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

data class ComicMetadata(
    val title: String,
    val author: String,
    val publishingDate: String,
    val publisher: String,
    val language: String,
    val coverBitmap: Bitmap?,
    val series: String?,
    val seriesIndex: Double?,
    val tags: String?,
    val pageCount: Int
)

/** Page names sort 2 before 10, so "page_10.jpg" never lands between 1 and 2. */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val startA = i
                while (i < a.length && a[i].isDigit()) i++
                val startB = j
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(startA, i).trimStart('0').ifEmpty { "0" }
                val nb = b.substring(startB, j).trimStart('0').ifEmpty { "0" }
                if (na.length != nb.length) return na.length - nb.length
                val cmp = na.compareTo(nb)
                if (cmp != 0) return cmp
            } else {
                val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

object ComicParser {
    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "avif")

    fun isImage(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS

    /** Junk that archive tools and macOS leave behind must not become a page. */
    fun isJunk(name: String): Boolean {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith("__MACOSX/")) return true
        return normalized.split('/').any { it.startsWith(".") }
    }

    /**
     * Streaming pass for the scanner: lists pages and reads ComicInfo without
     * copying the whole archive, then reopens the stream only for the cover.
     */
    fun parse(context: Context, uri: Uri, fileName: String): ComicMetadata {
        val names = mutableListOf<String>()
        var info: ComicInfoData? = null
        val stream = openStream(context, uri)
            ?: throw IllegalStateException("Permission expired or the file moved — add the folder again.")
        stream.use { input ->
            ZipInputStream(BufferedInputStream(input)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        if (isImage(entry.name) && !isJunk(entry.name)) {
                            names.add(entry.name)
                        } else if (info == null &&
                            entry.name.substringAfterLast('/').equals("ComicInfo.xml", ignoreCase = true)
                        ) {
                            info = runCatching { parseComicInfoXml(zip) }.getOrNull()
                        }
                    }
                    entry = zip.nextEntry
                }
            }
        }
        if (names.isEmpty()) error("No comic pages found")
        names.sortWith(NaturalOrder)

        val seriesName = info?.series?.takeIf { it.isNotBlank() }
        val number = info?.number?.takeIf { it.isNotBlank() }?.let { formatNumber(it) }
        val title = info?.title?.takeIf { it.isNotBlank() }
            ?: if (seriesName != null && number != null) "$seriesName #$number" else seriesName
            ?: fileName.substringBeforeLast('.').ifBlank { "Untitled comic" }
        val author = info?.writer?.takeIf { it.isNotBlank() }
            ?: info?.penciller?.takeIf { it.isNotBlank() }
            ?: "Unknown"
        val date = listOfNotNull(
            info?.year?.takeIf { it.isNotBlank() },
            info?.month?.takeIf { it.isNotBlank() }?.toIntOrNull()?.let { "%02d".format(it) }
        ).joinToString("-").ifBlank { "Unknown" }
        val genre = info?.genre?.takeIf { it.isNotBlank() }
            ?.replace(',', ';')
            ?.replace(Regex("\\s*;\\s*"), "; ")

        return ComicMetadata(
            title = title,
            author = author,
            publishingDate = date,
            publisher = info?.publisher?.takeIf { it.isNotBlank() } ?: "Unknown",
            language = info?.language?.takeIf { it.isNotBlank() } ?: "Unknown",
            coverBitmap = readCover(context, uri, names.first()),
            series = seriesName,
            seriesIndex = number?.toDoubleOrNull(),
            tags = genre,
            pageCount = names.size
        )
    }

    private fun openStream(context: Context, uri: Uri): InputStream? =
        if (uri.scheme == "file") uri.path?.let { runCatching { File(it).inputStream() }.getOrNull() }
        else context.contentResolver.openInputStream(uri)

    private fun readCover(context: Context, uri: Uri, target: String): Bitmap? {
        val stream = openStream(context, uri) ?: return null
        return runCatching {
            stream.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name == target) {
                            return@runCatching decodeSampled(readCapped(zip), 1024)
                        }
                        entry = zip.nextEntry
                    }
                }
            }
            null
        }.getOrNull()
    }

    /** Cap entry reads so a malformed archive cannot exhaust memory. */
    private fun readCapped(input: InputStream, limit: Int = 32 * 1024 * 1024): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > limit) throw IllegalStateException("Comic page is too large to read.")
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun decodeSampled(bytes: ByteArray, max: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth > 20000 || bounds.outHeight > 20000) return null
        var sample = 1
        while (bounds.outWidth / sample > max || bounds.outHeight / sample > max) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun formatNumber(raw: String): String =
        raw.toDoubleOrNull()?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: raw
}

private fun parseComicInfoXml(stream: InputStream): ComicInfoData? {
    val fields = HashMap<String, String>()
    val known = setOf("title", "series", "number", "writer", "penciller", "publisher", "year", "month", "languageiso", "genre")
    val parser = Xml.newPullParser()
    parser.setInput(stream, null)
    var event = parser.eventType
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
            val tag = parser.name?.lowercase() ?: ""
            if (tag in known) {
                val text = runCatching { parser.nextText() }.getOrNull()?.trim()
                if (!text.isNullOrBlank()) fields[tag] = text
            }
        }
        event = parser.next()
    }
    return ComicInfoData(
        title = fields["title"],
        series = fields["series"],
        number = fields["number"],
        writer = fields["writer"],
        penciller = fields["penciller"],
        publisher = fields["publisher"],
        year = fields["year"],
        month = fields["month"],
        language = fields["languageiso"],
        genre = fields["genre"]
    )
}

internal data class ComicInfoData(
    val title: String?,
    val series: String?,
    val number: String?,
    val writer: String?,
    val penciller: String?,
    val publisher: String?,
    val year: String?,
    val month: String?,
    val language: String?,
    val genre: String?
)

class ComicArchive private constructor(
    val file: File,
    private val zip: ZipFile,
    val pages: List<ZipEntry>
) : Closeable {
    private val byName: Map<String, ZipEntry> = pages.associateBy { it.name }

    fun decode(name: String, maxWidth: Int, maxHeight: Int): Bitmap? {
        val entry = byName[name] ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) } }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Refuse absurd dimensions instead of risking an out-of-memory crash.
        if (bounds.outWidth > 20000 || bounds.outHeight > 20000) return null
        var sample = 1
        while (bounds.outWidth / sample > maxWidth * 2 || bounds.outHeight / sample > maxHeight * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching {
            zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull()
    }

    override fun close() {
        runCatching { zip.close() }
    }

    companion object {
        fun open(context: Context, uriString: String, fileSize: Long, lastModified: Long): ComicArchive {
            val (currentSize, currentModified) = queryVersion(context, Uri.parse(uriString), fileSize, lastModified)
            val file = archiveFile(context, uriString, currentSize, currentModified)
            val zip = try {
                ZipFile(file)
            } catch (e: Exception) {
                throw IllegalStateException("This file is not a readable CBZ (ZIP) comic.", e)
            }
            return try {
                val pages = mutableListOf<ZipEntry>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (!entry.isDirectory && ComicParser.isImage(entry.name) && !ComicParser.isJunk(entry.name)) pages.add(entry)
                }
                if (pages.isEmpty()) error("No comic pages found")
                pages.sortWith(compareBy(NaturalOrder) { it.name })
                ComicArchive(file, zip, pages)
            } catch (e: Exception) {
                runCatching { zip.close() }
                throw e
            }
        }

        /** Read the current provider version so a replaced file never reuses an old copy. */
        private fun queryVersion(context: Context, uri: Uri, fallbackSize: Long, fallbackModified: Long): Pair<Long, Long> {
            if (uri.scheme == "file") {
                val file = uri.path?.let { File(it) }
                if (file != null && file.isFile) return file.length() to file.lastModified()
            }
            return runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                    null, null, null
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val size = cursor.getLong(0).takeIf { it > 0 } ?: fallbackSize
                    val modified = cursor.getLong(1).takeIf { it > 0 } ?: fallbackModified
                    size to modified
                }
            }.getOrNull() ?: (fallbackSize to fallbackModified)
        }

        /**
         * SAF documents have no usable file path, but a ZIP needs random access.
         * Keep one cached copy per file version and reuse it on reopen.
         */
        private fun archiveFile(context: Context, uriString: String, fileSize: Long, lastModified: Long): File {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") {
                val direct = uri.path?.let { File(it) }
                if (direct != null && direct.isFile) return direct
            }
            val dir = File(context.cacheDir, "comics").apply { mkdirs() }
            val key = sha256("$uriString|$fileSize|$lastModified").take(24)
            val target = File(dir, "$key.cbz")
            if (target.isFile && (fileSize <= 0 || target.length() == fileSize)) {
                target.setLastModified(System.currentTimeMillis())
                return target
            }
            val temp = File(dir, "$key.tmp")
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Permission expired or the file moved — add the folder again.")
            try {
                input.use { source -> temp.outputStream().buffered().use { out -> source.copyTo(out) } }
                if (fileSize > 0 && temp.length() != fileSize) {
                    throw IllegalStateException("The comic file changed while it was being opened.")
                }
                if (target.exists() && !target.delete()) {
                    throw IllegalStateException("Could not replace the cached comic.")
                }
                if (!temp.renameTo(target)) throw IllegalStateException("Could not prepare the comic file.")
                prune(dir, target)
                return target
            } catch (e: Exception) {
                temp.delete()
                throw e
            }
        }

        private fun prune(dir: File, keep: File) {
            val now = System.currentTimeMillis()
            val cached = dir.listFiles()?.filter {
                it.isFile && it.absolutePath != keep.absolutePath && it.name.endsWith(".cbz")
            } ?: return
            cached.sortedByDescending { it.lastModified() }.forEachIndexed { index, file ->
                if (index >= 2 || now - file.lastModified() > 30L * 24 * 60 * 60 * 1000) file.delete()
            }
            dir.listFiles()?.filter {
                it.isFile && it.name.endsWith(".tmp") && now - it.lastModified() > 24L * 60 * 60 * 1000
            }?.forEach { it.delete() }
        }

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** Decodes nearby pages on demand and keeps a few of them in memory. */
class ComicPageLoader(
    private val archive: ComicArchive,
    private val maxWidth: Int,
    private val maxHeight: Int
) {
    private val mutex = Mutex()
    private val cache = object : LruCache<String, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    suspend fun load(name: String): Bitmap? {
        cache.get(name)?.let { return it }
        return mutex.withLock {
            cache.get(name) ?: withContext(Dispatchers.IO) {
                archive.decode(name, maxWidth, maxHeight)?.also { cache.put(name, it) }
            }
        }
    }

    fun clear() {
        cache.evictAll()
    }

    private fun cacheBytes(): Int {
        val max = Runtime.getRuntime().maxMemory()
        return (max / 5).coerceIn(48L * 1024 * 1024, 96L * 1024 * 1024).toInt()
    }
}
