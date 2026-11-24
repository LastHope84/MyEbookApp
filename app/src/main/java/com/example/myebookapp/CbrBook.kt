package com.example.myebookapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.github.junrar.Archive
import com.github.junrar.rarfile.FileHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * CBR support uses junrar's decompressor (UnRAR license). It may read RAR
 * archives but may not be used to build a RAR-compatible archiver.
 */
object CbrParser {
    fun parse(
        context: Context,
        uri: Uri,
        fileName: String,
        fileSize: Long,
        lastModified: Long
    ): EpubMetadata {
        val file = cbrFile(context, uri.toString(), fileSize, lastModified)

        // Plenty of .cbr files are really ZIPs; read those without RAR code.
        val zipped = runCatching { ComicParser.parse(context, Uri.fromFile(file), fileName) }.getOrNull()
        if (zipped != null) {
            return EpubMetadata(
                fileName = fileName,
                title = zipped.title,
                author = zipped.author,
                publishingDate = zipped.publishingDate,
                publisher = zipped.publisher,
                language = zipped.language,
                coverBitmap = zipped.coverBitmap,
                series = zipped.series,
                seriesIndex = zipped.seriesIndex,
                tags = zipped.tags
            )
        }

        Archive(file).use { archive ->
            if (archive.isEncrypted) error("This CBR is password-protected.")
            val headers = cbrImageHeaders(archive)
            if (headers.isEmpty()) error("No comic pages found")
            val info = readComicInfo(archive)
            val fields = comicInfoFields(info, fileName)
            val cover = extractBytes(archive, headers.first())?.let { ComicParser.decodeSampled(it, 1024) }
            return EpubMetadata(
                fileName = fileName,
                title = fields.title,
                author = fields.author,
                publishingDate = fields.publishingDate,
                publisher = fields.publisher,
                language = fields.language,
                coverBitmap = cover,
                series = fields.series,
                seriesIndex = fields.seriesIndex,
                tags = fields.tags
            )
        }
    }

    private fun readComicInfo(archive: Archive): ComicInfoData? {
        val candidates = archive.fileHeaders.filter { header ->
            !header.isDirectory &&
                header.fileName?.substringAfterLast('/')?.equals("ComicInfo.xml", ignoreCase = true) == true
        }
        val header = candidates.minByOrNull { it.fileName?.count { ch -> ch == '/' } ?: 0 } ?: return null
        val bytes = extractBytes(archive, header) ?: return null
        return runCatching { parseComicInfoXml(bytes.inputStream()) }.getOrNull()
    }

    private fun extractBytes(archive: Archive, header: FileHeader): ByteArray? = runCatching {
        val out = ByteArrayOutputStream()
        archive.extractFile(header, out)
        out.toByteArray()
    }.getOrNull()
}

internal fun cbrImageHeaders(archive: Archive): List<FileHeader> {
    val headers = mutableListOf<FileHeader>()
    for (header in archive.fileHeaders) {
        if (header.isDirectory) continue
        val name = header.fileName ?: continue
        if (!ComicParser.isImage(name) || ComicParser.isJunk(name)) continue
        headers.add(header)
    }
    headers.sortWith(compareBy(NaturalOrder) { it.fileName ?: "" })
    return headers
}

private fun cbrFile(context: Context, uriString: String, fileSize: Long, lastModified: Long): File {
    val uri = Uri.parse(uriString)
    if (uri.scheme == "file") {
        val direct = uri.path?.let { File(it) }
        if (direct != null && direct.isFile) return direct
    }
    val (size, modified) = queryDocumentVersion(context, uri, fileSize, lastModified)
    return cachedDocumentCopy(context, uriString, size, modified, "comics", "cbr")
}

class CbrPageSource private constructor(private val pages: List<File>) : PagedImageSource {
    override val pageCount: Int get() = pages.size

    override suspend fun decode(index: Int, maxWidth: Int, maxHeight: Int): Bitmap? {
        val file = pages.getOrNull(index) ?: return null
        return withContext(Dispatchers.IO) { decodeFile(file, maxWidth, maxHeight) }
    }

    override fun close() = Unit

    companion object {
        private const val MARKER = ".complete"

        fun open(context: Context, uriString: String, fileSize: Long, lastModified: Long): CbrPageSource {
            val file = cbrFile(context, uriString, fileSize, lastModified)
            val dir = extractionDir(context, uriString, fileSize, lastModified)
            if (!File(dir, MARKER).isFile) extractToCache(file, dir)
            val pages = dir.listFiles()
                ?.filter { it.isFile && !it.name.startsWith(".") }
                ?.sortedBy { it.name }
                ?: emptyList()
            if (pages.isEmpty()) error("No comic pages found")
            return CbrPageSource(pages)
        }

        private fun extractToCache(file: File, dir: File) {
            val parent = dir.parentFile ?: error("Could not prepare the comic cache.")
            val temp = File(parent, dir.name + ".tmp")
            temp.deleteRecursively()
            temp.mkdirs()
            try {
                Archive(file).use { archive ->
                    if (archive.isEncrypted) error("This CBR is password-protected.")
                    val headers = cbrImageHeaders(archive)
                    if (headers.isEmpty()) error("No comic pages found")
                    headers.forEachIndexed { index, header ->
                        val name = header.fileName ?: return@forEachIndexed
                        val extension = name.substringAfterLast('.', "jpg").lowercase()
                        val safeExtension = if (ComicParser.isImage("x.$extension")) extension else "jpg"
                        val dest = File(temp, "%05d.%s".format(index, safeExtension))
                        FileOutputStream(dest).use { out -> archive.extractFile(header, out) }
                    }
                }
                File(temp, MARKER).writeText("ok")
                dir.deleteRecursively()
                if (!temp.renameTo(dir)) {
                    temp.copyRecursively(dir, overwrite = true)
                    temp.deleteRecursively()
                }
                prune(parent, dir)
            } catch (e: Exception) {
                temp.deleteRecursively()
                throw e
            }
        }

        private fun extractionDir(context: Context, uriString: String, fileSize: Long, lastModified: Long): File {
            val key = sha256("$uriString|$fileSize|$lastModified").take(24)
            return File(File(context.cacheDir, "cbr_pages"), key)
        }

        private fun prune(parent: File, keep: File) {
            val now = System.currentTimeMillis()
            val others = parent.listFiles()?.filter { it.isDirectory && it.absolutePath != keep.absolutePath } ?: return
            others.sortedByDescending { it.lastModified() }.forEachIndexed { index, dir ->
                if (index >= 2 || now - dir.lastModified() > 30L * 24 * 60 * 60 * 1000) dir.deleteRecursively()
            }
        }

        private fun decodeFile(file: File, maxWidth: Int, maxHeight: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            if (bounds.outWidth > 20000 || bounds.outHeight > 20000) return null
            var sample = 1
            while (bounds.outWidth / sample > maxWidth * 2 || bounds.outHeight / sample > maxHeight * 2) sample *= 2
            return BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
