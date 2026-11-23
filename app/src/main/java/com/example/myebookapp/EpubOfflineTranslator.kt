package com.example.myebookapp

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.URI
import java.util.Locale
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException

/** Stateless injection point. Implementations must return translated plain text, not HTML. */
fun interface EpubTextTranslator {
    suspend fun translate(text: String, sourceLanguage: String?, targetLanguage: String): String
}

/**
 * Creates a separate EPUB in a trusted, app-private directory. Run on a background dispatcher.
 * No UI, model download, storage permission, or translation engine is provided here.
 *
 * The engine sees individual text nodes (and selected accessibility attributes), not whole
 * paragraphs: markup and link targets cannot be passed to or modified by the engine, at the
 * cost of translation context. This is deliberately not a general EPUB repair tool.
 * Unsupported/encrypted/signed/DOCTYPE-based input fails instead of producing a partial file.
 */
object EpubOfflineTranslator {
    data class Limits(
        val maxEntries: Int = 4096,
        val maxEntryBytes: Int = 8 * 1024 * 1024,
        val maxTotalBytes: Long = 32L * 1024 * 1024,
        val maxArchiveBytes: Long = 128L * 1024 * 1024,
        val maxCompressionRatio: Long = 250,
        val maxTextNodes: Int = 200_000,
        val maxTranslatedNodeChars: Int = 8_000
    )

    data class Result(val destination: File, val translatedNodes: Int, val translatedDocuments: Int)

    /** Destination must not exist. Any exception, including cancellation, removes our output. */
    suspend fun translate(
        source: File,
        destination: File,
        sourceLanguage: String?,
        targetLanguage: String,
        translator: EpubTextTranslator,
        limits: Limits = Limits(),
        onProgress: suspend (Int, Int, Int) -> Unit = { _, _, _ -> }
    ): Result {
        require(targetLanguage.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))) { "Invalid target language tag" }
        require(limits.maxEntries > 0 && limits.maxEntryBytes > 0 && limits.maxTotalBytes > 0 &&
            limits.maxArchiveBytes > 0 && limits.maxCompressionRatio > 0 && limits.maxTextNodes > 0 &&
            limits.maxTranslatedNodeChars > 0) { "Invalid limits" }
        require(source.isFile && source.length() <= limits.maxArchiveBytes) { "Source missing or archive too large" }
        require(!destination.exists() && source.canonicalFile != destination.canonicalFile) { "Destination must be new and separate" }
        val parent = destination.absoluteFile.parentFile ?: error("Destination has no parent")
        require(parent.isDirectory) { "Destination directory does not exist" }
        val contents = linkedMapOf<String, ByteArray>()
        val entries = linkedMapOf<String, ZipEntry>()
        var total = 0L
        ZipFile(source).use { zip ->
            val it = zip.entries()
            while (it.hasMoreElements()) {
                val entry = it.nextElement()
                val name = entry.name
                require(validPath(name, entry.isDirectory) && !entries.containsKey(name)) { "Unsafe or duplicate ZIP path: $name" }
                require(entries.size < limits.maxEntries) { "Too many ZIP entries" }
                require(entry.method == ZipEntry.STORED || entry.method == ZipEntry.DEFLATED) { "Unsupported compression" }
                require(entry.size >= 0 && entry.size <= limits.maxEntryBytes && entry.compressedSize in 0..limits.maxArchiveBytes) { "Invalid ZIP size" }
                require(entry.size == 0L || (entry.compressedSize > 0 &&
                    (entry.size - 1) / limits.maxCompressionRatio < entry.compressedSize)) { "ZIP compression ratio exceeds limit" }
                require(entry.size <= limits.maxTotalBytes - total) { "ZIP expanded size exceeds limit" }
                total += entry.size
                val bytes = zip.getInputStream(entry).use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    var n: Int
                    while (input.read(buffer).also { n = it } != -1) {
                        require(out.size().toLong() + n <= entry.size && out.size().toLong() + n <= limits.maxEntryBytes) { "ZIP size mismatch" }
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                }
                require(bytes.size.toLong() == entry.size && CRC32().apply { update(bytes) }.value == entry.crc) { "ZIP CRC/size mismatch" }
                entries[name] = entry
                contents[name] = bytes
            }
        }
        require(entries.keys.firstOrNull() == "mimetype" && entries["mimetype"]?.method == ZipEntry.STORED &&
            contents["mimetype"]?.contentEquals("application/epub+zip".toByteArray(Charsets.US_ASCII)) == true) { "Invalid EPUB mimetype" }
        require(!contents.containsKey("META-INF/encryption.xml") && !contents.containsKey("META-INF/signatures.xml")) { "Encrypted or signed EPUB is unsupported" }
        val container = parseXml(contents["META-INF/container.xml"] ?: error("Missing container.xml"))
        val roots = container.getElementsByTagNameNS("urn:oasis:names:tc:opendocument:xmlns:container", "rootfile")
        require(roots.length == 1) { "Multiple or missing package documents unsupported" }
        val opfPath = (roots.item(0) as Element).getAttribute("full-path")
        require(validPath(opfPath, false)) { "Unsafe package path" }
        val opf = parseXml(contents[opfPath] ?: error("Missing package document"))
        require(opf.documentElement.namespaceURI == OPF && opf.documentElement.localName == "package") { "Invalid package document" }
        val metadata = opf.getElementsByTagNameNS(OPF, "metadata")
        val manifest = opf.getElementsByTagNameNS(OPF, "manifest")
        require(metadata.length == 1 && manifest.length == 1) { "Invalid OPF metadata or manifest" }
        val meta = metadata.item(0) as Element
        val titles = meta.getElementsByTagNameNS(DC, "title")
        val languages = meta.getElementsByTagNameNS(DC, "language")
        require(titles.length > 0 && languages.length > 0 && !titles.item(0).textContent.isNullOrBlank()) { "Missing title or language" }
        val oldTitle = titles.item(0).textContent
        titles.item(0).textContent = "$oldTitle [Translated: $targetLanguage]"
        for (i in 0 until languages.length) languages.item(i).textContent = targetLanguage
        // Keep the ID attribute used by package unique-identifier and EPUB 3 refinements.
        val uniqueId = opf.documentElement.getAttribute("unique-identifier")
        val ids = meta.getElementsByTagNameNS(DC, "identifier")
        val primaryIds = (0 until ids.length).map { ids.item(it) as Element }.filter { it.getAttribute("id") == uniqueId && uniqueId.isNotBlank() }
        require(primaryIds.size == 1) { "Missing or ambiguous unique identifier" }
        val translatedId = "urn:uuid:${UUID.randomUUID()}"
        primaryIds.single().textContent = translatedId
        if (opf.documentElement.getAttribute("version").startsWith("3")) {
            val opfMeta = meta.getElementsByTagNameNS(OPF, "meta")
            val modified = (0 until opfMeta.length).map { opfMeta.item(it) as Element }
                .filter { it.getAttribute("property") == "dcterms:modified" }
            require(modified.size <= 1) { "Ambiguous modified timestamp" }
            val modifiedNode = modified.singleOrNull() ?: opf.createElementNS(OPF, "meta").also {
                it.setAttribute("property", "dcterms:modified")
                meta.appendChild(it)
            }
            val format = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
            format.timeZone = java.util.TimeZone.getTimeZone("UTC")
            modifiedNode.textContent = format.format(java.util.Date())
        }
        contents[opfPath] = serialize(opf, limits)

        val items = (manifest.item(0) as Element).getElementsByTagNameNS(OPF, "item")
        val docs = linkedMapOf<String, String>()
        val idsSeen = mutableSetOf<String>()
        val allPaths = mutableSetOf<String>()
        for (i in 0 until items.length) {
            val item = items.item(i) as Element
            val id = item.getAttribute("id")
            require(id.isNotBlank() && idsSeen.add(id)) { "Missing or duplicate manifest ID" }
            require(item.getAttribute("properties").split(Regex("\\s+")).none { it == "scripted" }) { "Scripted EPUB resources unsupported" }
            val media = item.getAttribute("media-type")
            require(media != "image/svg+xml" && (!media.startsWith("text/") || media == "text/css") &&
                (media == "application/xhtml+xml" || media == "application/x-dtbncx+xml" ||
                    (!media.contains("xml", ignoreCase = true) && !media.contains("javascript", ignoreCase = true) &&
                     !media.contains("json", ignoreCase = true)))) {
                "Unsupported text, XML, script or SVG resource ($media); cannot guarantee translation coverage"
            }
            val path = resolveManifestPath(opfPath, item.getAttribute("href"))
            require(path != opfPath && path != "mimetype" && !path.startsWith("META-INF/")) { "Manifest points to a reserved resource" }
            require(allPaths.add(path) && contents.containsKey(path)) { "Missing or duplicate manifest resource: $path" }
            if (media == "application/xhtml+xml" || media == "application/x-dtbncx+xml") docs[path] = media
        }
        require(docs.values.any { it == "application/xhtml+xml" }) { "No XHTML documents" }
        val spine = opf.getElementsByTagNameNS(OPF, "itemref")
        require(spine.length > 0) { "Missing reading order" }
        for (i in 0 until spine.length) {
            val idref = (spine.item(i) as Element).getAttribute("idref")
            val match = (0 until items.length).map { items.item(it) as Element }.singleOrNull { it.getAttribute("id") == idref }
            require(match != null && match.getAttribute("media-type") == "application/xhtml+xml") { "Non-XHTML spine unsupported" }
        }
        var count = 0
        var translatedChars = 0L
        var completedDocs = 0
        for ((path, media) in docs) {
            onProgress(completedDocs, docs.size, count)
            val doc = parseXml(contents.getValue(path))
            if (media == "application/xhtml+xml") {
                require(doc.documentElement.namespaceURI == XHTML && doc.documentElement.localName == "html") { "Invalid XHTML: $path" }
                // Scripts can dynamically generate untranslated content and are not safe to rewrite.
                require(doc.getElementsByTagNameNS(XHTML, "script").length == 0) { "Scripted XHTML unsupported" }
                if (doc.documentElement.hasAttribute("lang")) doc.documentElement.setAttribute("lang", targetLanguage)
                if (doc.documentElement.hasAttributeNS(XML, "lang")) doc.documentElement.setAttributeNS(XML, "xml:lang", targetLanguage)
            } else {
                require(doc.documentElement.namespaceURI == NCX && doc.documentElement.localName == "ncx") { "Invalid NCX: $path" }
                val ncxMeta = doc.getElementsByTagNameNS(NCX, "meta")
                for (i in 0 until ncxMeta.length) {
                    val item = ncxMeta.item(i) as Element
                    if (item.getAttribute("name") == "dtb:uid") item.setAttribute("content", translatedId)
                }
            }
            suspend fun translateValue(value: String): String {
                val prefix = value.takeWhile { it.isWhitespace() }
                val suffix = value.takeLastWhile { it.isWhitespace() }
                if (prefix.length == value.length) return value
                val core = value.substring(prefix.length, value.length - suffix.length)
                count++
                require(count <= limits.maxTextNodes && core.length <= limits.maxTranslatedNodeChars) { "Text node limit exceeded" }
                val translated = translator.translate(core, sourceLanguage, targetLanguage)
                require(translated.isNotBlank() && translated.length <= limits.maxTranslatedNodeChars && validXmlText(translated)) { "Invalid engine output" }
                require(translated.length.toLong() <= limits.maxTotalBytes - translatedChars) { "Translation output limit exceeded" }
                translatedChars += translated.length
                return prefix + translated + suffix
            }
            suspend fun visit(node: Node, skip: Boolean = false, depth: Int = 0) {
                require(depth <= 256) { "XML nesting limit exceeded" }
                val element = node as? Element
                if (element != null && media == "application/xhtml+xml") {
                    require(element.namespaceURI == XHTML) { "Embedded non-XHTML content is unsupported: $path" }
                    require(element.localName.lowercase(Locale.ROOT) !in setOf("iframe", "object", "embed", "canvas", "audio", "video")) {
                        "Embedded or dynamic content is unsupported: $path"
                    }
                    val attributes = element.attributes
                    for (a in 0 until attributes.length) {
                        val attribute = attributes.item(a)
                        val name = (attribute.localName ?: attribute.nodeName).lowercase(Locale.ROOT)
                        val value = attribute.nodeValue.trim().lowercase(Locale.ROOT)
                        require(!name.startsWith("on") && !value.startsWith("javascript:")) { "Active content is unsupported: $path" }
                    }
                    if (element.hasAttribute("lang")) element.setAttribute("lang", targetLanguage)
                    if (element.hasAttributeNS(XML, "lang")) element.setAttributeNS(XML, "xml:lang", targetLanguage)
                }
                val ignored = skip || (element != null && element.namespaceURI == XHTML &&
                    element.localName.lowercase(Locale.ROOT) in setOf("style", "script", "code", "pre"))
                if (!ignored && element != null && element.namespaceURI == XHTML) {
                    for (attribute in listOf("alt", "title", "aria-label", "aria-description", "aria-valuetext", "aria-placeholder", "placeholder", "summary", "abbr", "label")) {
                        if (element.hasAttribute(attribute)) element.setAttribute(attribute, translateValue(element.getAttribute(attribute)))
                    }
                }
                if (!ignored && node.nodeType == Node.CDATA_SECTION_NODE && !node.nodeValue.isNullOrBlank()) error("CDATA text unsupported")
                if (!ignored && node.nodeType == Node.TEXT_NODE) {
                    val p = node.parentNode as? Element
                    if ((media == "application/xhtml+xml" && p?.namespaceURI == XHTML) ||
                        (media != "application/xhtml+xml" && p?.namespaceURI == NCX && p.localName == "text")) {
                        node.nodeValue = translateValue(node.nodeValue ?: "")
                    }
                }
                val children = node.childNodes
                for (i in 0 until children.length) visit(children.item(i), ignored, depth + 1)
            }
            visit(doc.documentElement)
            contents[path] = serialize(doc, limits)
            completedDocs++
            onProgress(completedDocs, docs.size, count)
        }
        require(contents.values.sumOf { it.size.toLong() } <= limits.maxTotalBytes) { "Translated EPUB too large" }
        // Claim output exclusively; do not overwrite an existing destination. Trusted directory required.
        require(destination.createNewFile()) { "Destination already exists" }
        try {
            ZipOutputStream(FileOutputStream(destination).buffered()).use { out ->
                for ((name, bytes) in contents) {
                    val original = entries.getValue(name)
                    val e = ZipEntry(name)
                    if (name != "mimetype" && original.time >= 0) e.time = original.time
                    if (name == "mimetype") {
                        e.method = ZipEntry.STORED
                        e.size = bytes.size.toLong(); e.compressedSize = e.size
                        e.crc = CRC32().apply { update(bytes) }.value
                        e.extra = ByteArray(0)
                    }
                    out.putNextEntry(e); out.write(bytes); out.closeEntry()
                }
            }
            require(destination.length() <= limits.maxArchiveBytes) { "Output archive too large" }
            return Result(destination, count, docs.size)
        } catch (t: Throwable) {
            destination.delete()
            throw t
        }
    }

    private const val OPF = "http://www.idpf.org/2007/opf"
    private const val DC = "http://purl.org/dc/elements/1.1/"
    private const val XHTML = "http://www.w3.org/1999/xhtml"
    private const val NCX = "http://www.daisy.org/z3986/2005/ncx/"
    private const val XML = "http://www.w3.org/XML/1998/namespace"

    private fun validPath(path: String, directory: Boolean): Boolean {
        if (path.isEmpty() || path.startsWith('/') || path.contains('\\') || path.contains('\u0000') || path.contains(':')) return false
        val p = if (directory && path.endsWith('/')) path.dropLast(1) else path
        return p.isNotEmpty() && p.split('/').all { it.isNotEmpty() && it != "." && it != ".." }
    }

    private fun resolveManifestPath(opf: String, href: String): String {
        val uri = URI(href)
        require(!uri.isAbsolute && uri.rawAuthority == null && uri.rawQuery == null && uri.rawFragment == null && !href.startsWith('/')) { "External or invalid manifest href" }
        val base = URI(null, null, "/$opf", null)
        val path = base.resolve(uri).normalize().path ?: error("Invalid manifest href")
        require(path.startsWith('/') && validPath(path.substring(1), false)) { "Unsafe manifest href" }
        return path.substring(1)
    }

    private fun parseXml(bytes: ByteArray): Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        // Fail closed if the platform cannot enforce these restrictions.
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        f.setFeature("http://xml.org/sax/features/external-general-entities", false)
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        f.isExpandEntityReferences = false
        val builder = f.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("External entity denied") }
        return builder.parse(InputSource(bytes.inputStream()))
    }

    private fun serialize(doc: Document, limits: Limits): ByteArray {
        val output = ByteArrayOutputStream()
        val bounded = object : OutputStream() {
            override fun write(b: Int) {
                require(output.size() < limits.maxEntryBytes) { "Translated document too large" }
                output.write(b)
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                require(len.toLong() <= limits.maxEntryBytes.toLong() - output.size()) { "Translated document too large" }
                output.write(b, off, len)
            }
        }
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.INDENT, "no")
        transformer.transform(DOMSource(doc), StreamResult(bounded))
        require(output.size() <= limits.maxEntryBytes) { "Translated document too large" }
        return output.toByteArray()
    }

    private fun validXmlText(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return false
                i += 2; continue
            }
            if (Character.isLowSurrogate(c) || !(c == '\t' || c == '\n' || c == '\r' || c in '\u0020'..'\uD7FF' || c in '\uE000'..'\uFFFD')) return false
            i++
        }
        return true
    }
}
