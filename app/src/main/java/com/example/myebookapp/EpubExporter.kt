package com.example.myebookapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * Writes a shareable EPUB that carries the corrections saved in this app.
 * The library's original file is never modified.
 */
object EpubExporter {
    private class OpfResult(val opf: ByteArray, val cover: Pair<String, ByteArray>?)

    fun export(context: Context, book: BookEntity, dest: File): Boolean {
        val work = File(context.cacheDir, "export_${System.currentTimeMillis()}").apply { mkdirs() }
        return try {
            val src = File(work, "src.epub")
            val input = if (book.uriString.startsWith("file:")) {
                File(Uri.parse(book.uriString).path ?: return false).inputStream()
            } else {
                context.contentResolver.openInputStream(Uri.parse(book.uriString)) ?: return false
            }
            input.use { it.copyTo(src.outputStream()) }
            ZipFile(src).use { zip ->
                val opfPath = readOpfPath(zip) ?: return false
                val opfBytes = zip.getInputStream(zip.getEntry(opfPath)).readBytes()
                val result = rewriteOpf(context, book, zip, opfPath, opfBytes) ?: return false
                repack(zip, dest, opfPath, result)
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            work.deleteRecursively()
        }
    }

    private fun readOpfPath(zip: ZipFile): String? {
        val container = zip.getEntry("META-INF/container.xml") ?: return null
        val doc = parse(zip.getInputStream(container).readBytes()) ?: return null
        return (doc.getElementsByTagNameNS("*", "rootfile").item(0) as? Element)
            ?.getAttribute("full-path")?.takeIf { it.isNotBlank() }
    }

    private fun rewriteOpf(context: Context, book: BookEntity, zip: ZipFile, opfPath: String, bytes: ByteArray): OpfResult? {
        val doc = parse(bytes) ?: return null
        val meta = doc.getElementsByTagNameNS("*", "metadata").item(0) as? Element ?: return null
        val opfNs = meta.namespaceURI

        // "Unknown" means the app never learned the value: leave the file's own value alone.
        // An empty string means the user cleared it: remove it from the exported copy.
        fun setDc(name: String, value: String?, keepWhenUnknown: Boolean = true) {
            if (keepWhenUnknown && value.equals("Unknown", ignoreCase = true)) return
            val old = meta.getElementsByTagNameNS("*", name)
            for (i in old.length - 1 downTo 0) meta.removeChild(old.item(i))
            if (!value.isNullOrBlank()) {
                val e = doc.createElementNS("http://purl.org/dc/elements/1.1/", "dc:$name")
                e.textContent = value
                meta.appendChild(e)
            }
        }

        if (book.title.isNotBlank()) setDc("title", book.title, keepWhenUnknown = false)
        setDc("publisher", book.publisher)
        setDc("date", book.publishingDate)
        setDc("language", book.language)

        if (!book.author.equals("Unknown", ignoreCase = true)) {
            val old = meta.getElementsByTagNameNS("*", "creator")
            for (i in old.length - 1 downTo 0) meta.removeChild(old.item(i))
            book.author.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { author ->
                val e = doc.createElementNS("http://purl.org/dc/elements/1.1/", "dc:creator")
                e.textContent = author
                meta.appendChild(e)
            }
        }

        val tags = book.tags
        if (tags != null) {
            val old = meta.getElementsByTagNameNS("*", "subject")
            for (i in old.length - 1 downTo 0) meta.removeChild(old.item(i))
            tags.split(";").map { it.trim() }.filter { it.isNotBlank() }.forEach { tag ->
                val e = doc.createElementNS("http://purl.org/dc/elements/1.1/", "dc:subject")
                e.textContent = tag
                meta.appendChild(e)
            }
        }

        val series = book.series
        if (series != null) {
            val metas = meta.getElementsByTagNameNS("*", "meta")
            for (i in metas.length - 1 downTo 0) {
                val m = metas.item(i) as? Element ?: continue
                val name = m.getAttribute("name")
                if (name == "calibre:series" || name == "calibre:series_index") meta.removeChild(m)
            }
            if (series.isNotBlank()) {
                meta.appendChild(doc.createElementNS(opfNs, "meta").apply {
                    setAttribute("name", "calibre:series")
                    setAttribute("content", series)
                })
                meta.appendChild(doc.createElementNS(opfNs, "meta").apply {
                    setAttribute("name", "calibre:series_index")
                    setAttribute("content", (book.seriesIndex ?: 1.0).toString())
                })
            }
        }

        val metas = meta.getElementsByTagNameNS("*", "meta")
        for (i in 0 until metas.length) {
            val m = metas.item(i) as? Element ?: continue
            if (m.getAttribute("property") == "dcterms:modified") {
                m.textContent = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .format(Date())
                break
            }
        }

        val cover = replaceCover(context, book, doc, zip, opfPath)
        val out = ByteArrayOutputStream()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc), StreamResult(out))
        return OpfResult(out.toByteArray(), cover)
    }

    private fun replaceCover(context: Context, book: BookEntity, doc: Document, zip: ZipFile, opfPath: String): Pair<String, ByteArray>? {
        val coverFile = book.exportCoverPath?.let(::File)?.takeIf { it.exists() } ?: return null
        val bitmap = BitmapFactory.decodeFile(coverFile.absolutePath) ?: return null
        val manifest = doc.getElementsByTagNameNS("*", "manifest").item(0) as? Element ?: return null

        var coverId: String? = null
        val metas = doc.getElementsByTagNameNS("*", "meta")
        for (i in 0 until metas.length) {
            val m = metas.item(i) as? Element ?: continue
            if (m.getAttribute("name") == "cover") {
                coverId = m.getAttribute("content")
                break
            }
        }

        val items = manifest.getElementsByTagNameNS("*", "item")
        var item: Element? = null
        if (coverId != null) {
            for (i in 0 until items.length) {
                val candidate = items.item(i) as? Element ?: continue
                if (candidate.getAttribute("id") == coverId) { item = candidate; break }
            }
        }
        if (item == null) {
            for (i in 0 until items.length) {
                val candidate = items.item(i) as? Element ?: continue
                if (candidate.getAttribute("properties").split(" ").contains("cover-image")) { item = candidate; break }
            }
        }
        val target = item ?: return null
        val href = target.getAttribute("href").takeIf { it.isNotBlank() } ?: return null
        val zipPath = resolveZipPath(opfPath, href)
        if (zip.getEntry(zipPath) == null) return null

        val out = ByteArrayOutputStream()
        val mediaType = if (target.getAttribute("media-type").equals("image/png", ignoreCase = true)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            "image/png"
        } else {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            "image/jpeg"
        }
        target.setAttribute("media-type", mediaType)
        return zipPath to out.toByteArray()
    }

    private fun resolveZipPath(opfPath: String, href: String): String {
        val dir = opfPath.substringBeforeLast('/', "")
        val decoded = Uri.decode(href.substringBefore('#'))
        val combined = if (dir.isEmpty()) decoded else "$dir/$decoded"
        val parts = ArrayDeque<String>()
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeLast()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun repack(zip: ZipFile, dest: File, opfPath: String, result: OpfResult) {
        val cover = result.cover
        dest.parentFile?.mkdirs()
        ZipOutputStream(dest.outputStream().buffered()).use { out ->
            val mimetype = "application/epub+zip".toByteArray()
            out.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = mimetype.size.toLong()
                crc = CRC32().apply { update(mimetype) }.value
            })
            out.write(mimetype)
            out.closeEntry()

            zip.entries().asSequence().forEach { entry ->
                if (entry.name == "mimetype") return@forEach
                out.putNextEntry(ZipEntry(entry.name))
                when {
                    entry.name == opfPath -> out.write(result.opf)
                    cover != null && entry.name == cover.first -> out.write(cover.second)
                    else -> zip.getInputStream(entry).copyTo(out)
                }
                out.closeEntry()
            }
        }
    }

    private fun parse(bytes: ByteArray): Document? = runCatching {
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(bytes.inputStream())
    }.getOrNull()
}
