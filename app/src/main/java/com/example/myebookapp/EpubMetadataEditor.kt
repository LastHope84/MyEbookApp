package com.example.myebookapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import kotlin.math.roundToInt

data class EpubEditRequest(
    val title: String,
    val author: String,
    val publishingDate: String,
    val publisher: String,
    val language: String,
    val newCoverBitmap: Bitmap? = null
)

sealed class EpubWriteResult {
    data object WrittenToFile : EpubWriteResult()
    data class ReadOnlyFallback(val reason: String) : EpubWriteResult()
}

object EpubMetadataEditor {

    private const val DC_NS = "http://purl.org/dc/elements/1.1/"

    fun writeMetadataToEpub(
        context: Context,
        bookUri: Uri,
        edit: EpubEditRequest
    ): EpubWriteResult {
        val workDir = File(context.cacheDir, "epub_edit_${System.currentTimeMillis()}").apply {
            mkdirs()
        }

        try {
            val srcFile = File(workDir, "src.epub")
            val inputStream = if (bookUri.scheme == "file" && bookUri.path != null) {
                File(bookUri.path!!).inputStream()
            } else {
                context.contentResolver.openInputStream(bookUri)
            } ?: return EpubWriteResult.ReadOnlyFallback("Could not open original EPUB.")

            inputStream.use { ins ->
                srcFile.outputStream().buffered().use { out -> ins.copyTo(out) }
            }

            val outFile = File(workDir, "edited.epub")

            ZipFile(srcFile).use { zip ->
                val containerEntry = findEntryIgnoreCase(zip, "META-INF/container.xml")
                    ?: return EpubWriteResult.ReadOnlyFallback("Missing META-INF/container.xml.")
                val opfPath = parseOpfPathFromContainer(zip.getInputStream(containerEntry).readBytes())
                    ?: return EpubWriteResult.ReadOnlyFallback("Could not locate OPF file.")
                val opfEntry = findEntryIgnoreCase(zip, opfPath)
                    ?: return EpubWriteResult.ReadOnlyFallback("OPF entry not found in EPUB.")

                val opfBytes = zip.getInputStream(opfEntry).readBytes()
                val (updatedOpfBytes, coverReplacement) = updateOpfAndPrepareCover(
                    opfPath = opfPath,
                    opfBytes = opfBytes,
                    edit = edit
                )

                repackEpub(
                    sourceZip = zip,
                    outputFile = outFile,
                    opfPath = opfPath,
                    updatedOpfBytes = updatedOpfBytes,
                    coverReplacement = coverReplacement
                )
            }

            // Sanity-verify the repacked archive before touching original file
            ZipFile(outFile).use { verifyZip ->
                if (!verifyZip.entries().hasMoreElements()) {
                    return EpubWriteResult.ReadOnlyFallback("Repack verification failed.")
                }
            }

            return writeBackToUri(context, bookUri, outFile)
        } finally {
            workDir.deleteRecursively()
        }
    }

    fun saveFreshWebpCover(
        context: Context,
        uriString: String,
        oldCoverPath: String?,
        bitmap: Bitmap
    ): String? {
        return runCatching {
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val file = File(coversDir, "${sha256(uriString)}_${System.currentTimeMillis()}.webp")

            val maxHeight = 320f
            val scale = if (bitmap.height > maxHeight) maxHeight / bitmap.height else 1f
            val target = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true
                )
            } else {
                bitmap
            }

            val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSY
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }

            file.outputStream().use { out -> target.compress(format, 80, out) }

            if (!oldCoverPath.isNullOrBlank() && oldCoverPath != file.absolutePath) {
                runCatching { File(oldCoverPath).delete() }
            }
            file.absolutePath
        }.getOrNull()
    }

    fun decodeSampledImageUri(context: Context, imageUri: Uri, maxDimension: Int = 1400): Bitmap? {
        return runCatching {
            val bytes = context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                ?: return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxDimension ||
                bounds.outHeight / sampleSize > maxDimension
            ) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    private fun writeBackToUri(context: Context, bookUri: Uri, editedFile: File): EpubWriteResult {
        if (bookUri.scheme == "file" && bookUri.path != null) {
            return runCatching {
                val dest = File(bookUri.path!!)
                editedFile.inputStream().buffered().use { ins ->
                    dest.outputStream().buffered().use { out -> ins.copyTo(out) }
                }
                EpubWriteResult.WrittenToFile
            }.getOrElse {
                EpubWriteResult.ReadOnlyFallback(it.localizedMessage ?: "File write failed.")
            }
        }

        val outStream = runCatching {
            context.contentResolver.openOutputStream(bookUri, "wt")
                ?: context.contentResolver.openOutputStream(bookUri, "w")
        }.getOrNull()

        if (outStream == null) {
            return EpubWriteResult.ReadOnlyFallback(
                "Saved in library! Re-add this folder once via ☰ → Add / Scan Folder to grant write access to .epub files."
            )
        }

        return runCatching {
            outStream.use { out ->
                editedFile.inputStream().buffered().use { ins -> ins.copyTo(out) }
            }
            EpubWriteResult.WrittenToFile
        }.getOrElse {
            EpubWriteResult.ReadOnlyFallback(
                "Saved in library! Re-add this folder once via ☰ → Add / Scan Folder to write directly into .epub files."
            )
        }
    }

    private fun updateOpfAndPrepareCover(
        opfPath: String,
        opfBytes: ByteArray,
        edit: EpubEditRequest
    ): Pair<ByteArray, Pair<String, ByteArray>?> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }
        val doc = factory.newDocumentBuilder().parse(opfBytes.inputStream())
        val packageEl = doc.documentElement
        val opfNs = packageEl.namespaceURI

        val metadataEl = findFirstElementByLocalName(doc, "metadata")
            ?: throw IllegalStateException("OPF is missing <metadata>.")

        replaceDcValues(doc, metadataEl, "title", listOf(edit.title))
        val authorList = edit.author
            .split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
        replaceDcValues(doc, metadataEl, "creator", authorList)
        replaceDcValues(doc, metadataEl, "date", cleanSingleField(edit.publishingDate))
        replaceDcValues(doc, metadataEl, "publisher", cleanSingleField(edit.publisher))
        replaceDcValues(doc, metadataEl, "language", cleanSingleField(edit.language))

        val coverReplacement = edit.newCoverBitmap?.let { bitmap ->
            applyCoverInDom(doc, packageEl, metadataEl, opfNs, opfPath, bitmap)
        }

        val transformer = TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            setOutputProperty(OutputKeys.INDENT, "yes")
        }
        val out = ByteArrayOutputStream()
        transformer.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray() to coverReplacement
    }

    private fun applyCoverInDom(
        doc: Document,
        packageEl: Element,
        metadataEl: Element,
        opfNs: String?,
        opfPath: String,
        bitmap: Bitmap
    ): Pair<String, ByteArray>? {
        val manifestEl = findFirstElementByLocalName(doc, "manifest") ?: return null
        val items = collectChildElementsByLocalName(manifestEl, "item")

        var existingCoverItem = items.firstOrNull { item ->
            item.getAttribute("properties")
                .split(' ')
                .any { it == "cover-image" }
        }

        if (existingCoverItem == null) {
            val metaCoverId = collectChildElementsByLocalName(metadataEl, "meta")
                .firstOrNull { it.getAttribute("name").equals("cover", ignoreCase = true) }
                ?.getAttribute("content")
            if (!metaCoverId.isNullOrBlank()) {
                existingCoverItem = items.firstOrNull { it.getAttribute("id") == metaCoverId }
            }
        }

        val isEpub3 = packageEl.getAttribute("version").trim().startsWith("3")

        if (existingCoverItem != null) {
            val href = existingCoverItem.getAttribute("href")
            if (href.isNotBlank()) {
                val mediaType = existingCoverItem.getAttribute("media-type")
                val asPng = mediaType.equals("image/png", ignoreCase = true) ||
                    href.endsWith(".png", ignoreCase = true)
                val bytes = encodeCoverImage(bitmap, asPng)
                existingCoverItem.setAttribute("media-type", if (asPng) "image/png" else "image/jpeg")
                val zipPath = resolveZipPath(opfPath, href)
                return zipPath to bytes
            }
        }

        val newHref = "cover_custom.jpg"
        val newId = "custom-cover-image"
        val itemEl = if (opfNs != null) doc.createElementNS(opfNs, "item") else doc.createElement("item")
        itemEl.setAttribute("id", newId)
        itemEl.setAttribute("href", newHref)
        itemEl.setAttribute("media-type", "image/jpeg")
        if (isEpub3) {
            itemEl.setAttribute("properties", "cover-image")
        }
        manifestEl.appendChild(itemEl)

        val existingMetaCover = collectChildElementsByLocalName(metadataEl, "meta")
            .firstOrNull { it.getAttribute("name").equals("cover", ignoreCase = true) }
        if (existingMetaCover != null) {
            existingMetaCover.setAttribute("content", newId)
        } else {
            val metaEl = if (opfNs != null) doc.createElementNS(opfNs, "meta") else doc.createElement("meta")
            metaEl.setAttribute("name", "cover")
            metaEl.setAttribute("content", newId)
            metadataEl.appendChild(metaEl)
        }

        val zipPath = resolveZipPath(opfPath, newHref)
        return zipPath to encodeCoverImage(bitmap, asPng = false)
    }

    private fun encodeCoverImage(bitmap: Bitmap, asPng: Boolean): ByteArray {
        val maxHeight = 1400f
        val scale = if (bitmap.height > maxHeight) maxHeight / bitmap.height else 1f
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        if (asPng) {
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        } else {
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
        return out.toByteArray()
    }

    private fun repackEpub(
        sourceZip: ZipFile,
        outputFile: File,
        opfPath: String,
        updatedOpfBytes: ByteArray,
        coverReplacement: Pair<String, ByteArray>?
    ) {
        val normalizedOpf = opfPath.trimStart('/')
        val normalizedCover = coverReplacement?.first?.trimStart('/')
        var coverWritten = false

        ZipOutputStream(outputFile.outputStream().buffered()).use { zos ->
            // 1. mimetype MUST be first and uncompressed (STORED)
            val mimeBytes = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(mimeBytes) }.value
            val mimeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimeBytes.size.toLong()
                compressedSize = mimeBytes.size.toLong()
                this.crc = crc
            }
            zos.putNextEntry(mimeEntry)
            zos.write(mimeBytes)
            zos.closeEntry()

            val entries = sourceZip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val name = entry.name.trimStart('/')
                if (entry.isDirectory || name.equals("mimetype", ignoreCase = true)) continue

                zos.putNextEntry(ZipEntry(entry.name))
                when {
                    name.equals(normalizedOpf, ignoreCase = true) -> {
                        zos.write(updatedOpfBytes)
                    }
                    normalizedCover != null && name.equals(normalizedCover, ignoreCase = true) -> {
                        zos.write(coverReplacement.second)
                        coverWritten = true
                    }
                    else -> {
                        sourceZip.getInputStream(entry).use { it.copyTo(zos) }
                    }
                }
                zos.closeEntry()
            }

            if (coverReplacement != null && !coverWritten) {
                zos.putNextEntry(ZipEntry(coverReplacement.first))
                zos.write(coverReplacement.second)
                zos.closeEntry()
            }
        }
    }

    private fun cleanSingleField(value: String): List<String> {
        val trimmed = value.trim()
        return if (trimmed.isBlank() || trimmed.equals("Unknown", ignoreCase = true)) {
            emptyList()
        } else {
            listOf(trimmed)
        }
    }

    private fun replaceDcValues(
        doc: Document,
        metadataEl: Element,
        localName: String,
        values: List<String>
    ) {
        val toRemove = collectChildElementsByLocalName(metadataEl, localName)
        toRemove.forEach { metadataEl.removeChild(it) }

        for (value in values) {
            if (value.isBlank()) continue
            val el = doc.createElementNS(DC_NS, "dc:$localName")
            el.textContent = value
            metadataEl.appendChild(el)
        }
    }

    private fun findFirstElementByLocalName(doc: Document, localName: String): Element? {
        val list = doc.getElementsByTagNameNS("*", localName)
        if (list.length > 0) return list.item(0) as? Element
        val fallback = doc.getElementsByTagName(localName)
        return if (fallback.length > 0) fallback.item(0) as? Element else null
    }

    private fun collectChildElementsByLocalName(parent: Element, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node is Element) {
                val nodeLocal = node.localName ?: node.tagName.substringAfterLast(':')
                if (nodeLocal.equals(localName, ignoreCase = true)) {
                    result.add(node)
                }
            }
        }
        return result
    }

    private fun findEntryIgnoreCase(zip: ZipFile, targetPath: String): ZipEntry? {
        val normalized = targetPath.trimStart('/')
        zip.getEntry(normalized)?.let { return it }
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val e = entries.nextElement()
            if (!e.isDirectory && e.name.trimStart('/').equals(normalized, ignoreCase = true)) {
                return e
            }
        }
        return null
    }

    private fun parseOpfPathFromContainer(containerBytes: ByteArray): String? {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(containerBytes.inputStream())
        val rootfiles = doc.getElementsByTagNameNS("*", "rootfile")
        for (i in 0 until rootfiles.length) {
            val el = rootfiles.item(i) as? Element ?: continue
            val path = el.getAttribute("full-path")
            if (path.isNotBlank()) return path
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

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(24)
    }
}
