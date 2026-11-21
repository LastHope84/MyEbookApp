package com.example.myebookapp
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import org.w3c.dom.*
import java.io.*
import java.util.zip.*
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.*
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.roundToInt

data class EpubEditRequest(val title: String, val author: String, val publishingDate: String, val publisher: String, val language: String, val newCoverBitmap: Bitmap?, val removeCover: Boolean, val series: String?, val seriesIndex: Double?, val tags: String?)
sealed class EpubWriteResult { data object WrittenToFile : EpubWriteResult(); data class ReadOnlyFallback(val r: String) : EpubWriteResult() }

object EpubMetadataEditor {
    fun writeMetadataToEpub(ctx: Context, uri: Uri, ed: EpubEditRequest): EpubWriteResult {
        val work = File(ctx.cacheDir, "epub_edit_${System.currentTimeMillis()}").apply { mkdirs() }
        return try {
            val src = File(work, "src.epub")
            ctx.contentResolver.openInputStream(uri)?.use { it.copyTo(src.outputStream()) } ?: return EpubWriteResult.ReadOnlyFallback("Unreadable")
            val out = File(work, "out.epub")
            ZipFile(src).use { zip ->
                val opfP = parseOpfPath(zip.getInputStream(zip.getEntry("META-INF/container.xml")).readBytes()) ?: return EpubWriteResult.ReadOnlyFallback("No OPF")
                val opfB = zip.getInputStream(zip.getEntry(opfP)).readBytes()
                val (newOpf, covRep, skip) = updateOpf(opfP, opfB, ed)
                repack(zip, out, opfP, newOpf, covRep, skip)
            }
            writeBack(ctx, uri, out)
        } finally { work.deleteRecursively() }
    }

    private fun updateOpf(path: String, bytes: ByteArray, ed: EpubEditRequest): Triple<ByteArray, Pair<String, ByteArray>?, Set<String>> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(bytes.inputStream())
        val meta = doc.getElementsByTagNameNS("*", "metadata").item(0) as Element
        fun setDc(n: String, v: String) {
            val old = meta.getElementsByTagNameNS("*", n); for (i in 0 until old.length) meta.removeChild(old.item(0))
            if (v.isNotBlank()) { val e = doc.createElementNS("http://purl.org/dc/elements/1.1/", "dc:$n"); e.textContent = v; meta.appendChild(e) }
        }
        setDc("title", ed.title); setDc("date", ed.publishingDate); setDc("publisher", ed.publisher); setDc("language", ed.language)
        val creators = meta.getElementsByTagNameNS("*", "creator"); for(i in 0 until creators.length) meta.removeChild(creators.item(0))
        ed.author.split(";").forEach { val a=it.trim(); if(a.isNotBlank()){ val e=doc.createElementNS("http://purl.org/dc/elements/1.1/", "dc:creator"); e.textContent=a; meta.appendChild(e) } }
        
        // Save Series (Calibre Style)
        val metaNodes = meta.getElementsByTagName("meta")
        for(i in 0 until metaNodes.length) { val m=metaNodes.item(i) as Element; if(m.getAttribute("name")=="calibre:series" || m.getAttribute("name")=="calibre:series_index") meta.removeChild(m) }
        if (!ed.series.isNullOrBlank()) {
            val s = doc.createElement("meta"); s.setAttribute("name", "calibre:series"); s.setAttribute("content", ed.series); meta.appendChild(s)
            val si = doc.createElement("meta"); si.setAttribute("name", "calibre:series_index"); si.setAttribute("content", (ed.seriesIndex ?: 1.0).toString()); meta.appendChild(si)
        }

        val out = ByteArrayOutputStream(); TransformerFactory.newInstance().newTransformer().transform(DOMSource(doc), StreamResult(out))
        return Triple(out.toByteArray(), null, emptySet())
    }

    private fun repack(src: ZipFile, out: File, opfP: String, opfB: ByteArray, cov: Pair<String, ByteArray>?, skip: Set<String>) {
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            val m = "application/epub+zip".toByteArray(); val entry = ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = m.size.toLong(); compressedSize = m.size.toLong(); crc = CRC32().apply { update(m) }.value }
            z.putNextEntry(entry); z.write(m); z.closeEntry()
            src.entries().asSequence().forEach { e ->
                if (e.name != "mimetype" && e.name !in skip) {
                    z.putNextEntry(ZipEntry(e.name))
                    if (e.name == opfP) z.write(opfB) else src.getInputStream(e).copyTo(z)
                    z.closeEntry()
                }
            }
        }
    }

    private fun writeBack(ctx: Context, uri: Uri, file: File): EpubWriteResult {
        return try { ctx.contentResolver.openOutputStream(uri, "wt")?.use { file.inputStream().copyTo(it) }; EpubWriteResult.WrittenToFile }
        catch (e: Exception) { EpubWriteResult.ReadOnlyFallback("Saved to library (File is Read-Only)") }
    }

    private fun parseOpfPath(b: ByteArray): String? {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(b.inputStream())
        return (doc.getElementsByTagNameNS("*", "rootfile").item(0) as? Element)?.getAttribute("full-path")
    }

    fun decodeSampledImageUri(ctx: Context, uri: Uri): Bitmap? = runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { ins ->
            val b = ins.readBytes(); val opt = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(b, 0, b.size, opt)
            var s = 1; while (opt.outWidth/s > 1200 || opt.outHeight/s > 1200) s *= 2
            BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
        }
    }.getOrNull()

    fun saveFreshWebpCover(ctx: Context, uri: String, old: String?, bmp: Bitmap): String? = runCatching {
        val dir = File(ctx.filesDir, "covers").apply { mkdirs() }
        val file = File(dir, "${sha256(uri)}_${System.currentTimeMillis()}.webp")
        val s = if (bmp.height > 320f) 320f / bmp.height else 1f
        val t = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
        file.outputStream().use { t.compress(if (Build.VERSION.SDK_INT >= 29) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 80, it) }
        old?.let { File(it).delete() }; file.absolutePath
    }.getOrNull()

    private fun sha256(s: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}
