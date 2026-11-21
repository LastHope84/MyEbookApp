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
    val coverBitmap: Bitmap?,
    val series: String? = null,
    val seriesIndex: Double? = null,
    val tags: String? = null
)

object EpubParser {
    fun getFileName(context: Context, uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment?.substringAfterLast('/') ?: "Unknown"
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) return c.getString(i) ?: ""
            }
        }
        return "Unknown"
    }

    fun parseEpub(context: Context, uri: Uri, fileName: String): EpubMetadata {
        val opfPath = findOpfPath(context, uri) ?: throw IllegalStateException("No OPF found")
        val opfData = readZipEntryBytes(context, uri, opfPath) ?: throw IllegalStateException("OPF unreadable")
        val parsed = opfData.inputStream().use { parseOpf(it) }

        val coverBmp = parsed.coverHref?.let { href ->
            val path = resolveZipPath(opfPath, href)
            readZipEntryBytes(context, uri, path)?.let { decodeSampledBitmap(it, 1024) }
        }

        return EpubMetadata(
            fileName = fileName,
            title = parsed.title.ifBlank { fileName.substringBeforeLast('.') },
            author = parsed.authors.joinToString("; ").ifBlank { "Unknown" },
            publishingDate = parsed.publishingDate.ifBlank { "Unknown" },
            publisher = parsed.publisher.ifBlank { "Unknown" },
            language = parsed.language.ifBlank { "Unknown" },
            coverBitmap = coverBmp,
            series = parsed.series?.ifBlank { null },
            seriesIndex = parsed.seriesIndex,
            tags = if (parsed.tags.isEmpty()) null else parsed.tags.joinToString("; ")
        )
    }

    private fun findOpfPath(context: Context, uri: Uri): String? {
        val bytes = readZipEntryBytes(context, uri, "META-INF/container.xml") ?: return null
        val parser = Xml.newPullParser().apply { setInput(bytes.inputStream(), "UTF-8") }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "rootfile") return parser.getAttributeValue(null, "full-path")
            event = parser.next()
        }
        return null
    }

    private data class OpfResult(val title: String, val authors: List<String>, val publishingDate: String, val publisher: String, val language: String, val coverHref: String?, val series: String?, val seriesIndex: Double?, val tags: List<String>)

    private fun parseOpf(stream: InputStream): OpfResult {
        val p = Xml.newPullParser().apply { setInput(stream, "UTF-8") }
        var title = ""; val authors = mutableListOf<String>(); var date = ""; var pub = ""; var lang = ""
        var sName: String? = null; var sIdx: Double? = null; val tags = mutableListOf<String>()
        var cId: String? = null; var c3Href: String? = null; val manifest = mutableMapOf<String, String>()

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val tag = p.name?.lowercase() ?: ""
                when (tag) {
                    "title" -> title = p.nextText().trim()
                    "creator" -> p.nextText().trim().let { if (it.isNotBlank()) authors.add(it) }
                    "date" -> date = p.nextText().trim()
                    "publisher" -> pub = p.nextText().trim()
                    "language" -> lang = p.nextText().trim()
                    "subject" -> p.nextText().trim().let { if (it.isNotBlank()) tags.add(it) }
                    "meta" -> {
                        val name = p.getAttributeValue(null, "name")
                        val content = p.getAttributeValue(null, "content")
                        val prop = p.getAttributeValue(null, "property")
                        when {
                            name == "cover" -> cId = content
                            name == "calibre:series" -> sName = content
                            name == "calibre:series_index" -> sIdx = content?.toDoubleOrNull()
                            prop == "belongs-to-collection" -> sName = p.nextText().trim()
                            prop == "group-position" -> sIdx = p.nextText().trim().toDoubleOrNull()
                        }
                    }
                    "item" -> {
                        val id = p.getAttributeValue(null, "id"); val href = p.getAttributeValue(null, "href")
                        if (id != null && href != null) {
                            manifest[id] = href
                            if (p.getAttributeValue(null, "properties")?.contains("cover-image") == true) c3Href = href
                        }
                    }
                }
            }
            event = p.next()
        }
        return OpfResult(title, authors, date, pub, lang, c3Href ?: manifest[cId], sName, sIdx, tags)
    }

    private fun readZipEntryBytes(context: Context, uri: Uri, target: String): ByteArray? {
        val stream = if (uri.scheme == "file") uri.path?.let { File(it).inputStream() } else context.contentResolver.openInputStream(uri)
        stream?.use { ins ->
            ZipInputStream(ins).use { zip ->
                var e = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name.trimStart('/').equals(target.trimStart('/'), true)) return zip.readBytes()
                    e = zip.nextEntry
                }
            }
        }
        return null
    }

    private fun resolveZipPath(opf: String, href: String): String {
        val parts = (opf.substringBeforeLast('/', "") + "/" + Uri.decode(href)).split('/').toMutableList()
        val result = mutableListOf<String>()
        for (p in parts) when (p) { "", "." -> {} ; ".." -> if (result.isNotEmpty()) result.removeAt(result.lastIndex) ; else -> result.add(p) }
        return result.joinToString("/")
    }

    private fun decodeSampledBitmap(bytes: ByteArray, max: Int): Bitmap? {
        val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opt)
        var s = 1; while (opt.outWidth / s > max || opt.outHeight / s > max) s *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
    }
}
