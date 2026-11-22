package com.example.myebookapp

import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element

class EpubOfflineTranslatorTest {
    @get:Rule val temp = TemporaryFolder()

    private val mimetype = "application/epub+zip".toByteArray()
    private val container = """<?xml version="1.0"?><container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles><rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
    private val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:identifier id="book-id">original-id</dc:identifier><dc:title>Original &amp; Title</dc:title><dc:language>en</dc:language></metadata><manifest><item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"/><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="css" href="style.css" media-type="text/css"/><item id="pic" href="images/pic.bin" media-type="application/octet-stream"/></manifest><spine><itemref idref="chapter"/></spine></package>"""
    private val chapter = """<?xml version="1.0"?><html xmlns="http://www.w3.org/1999/xhtml" xml:lang="en"><head><title>Chapter</title><link href="style.css" rel="stylesheet"/></head><body><p id="p">Hello <a href="https://example.org/?a=1&amp;b=2"><em>world</em></a> &amp; friends.</p><img src="images/pic.bin" alt="A cat"/><pre>code &amp; data</pre></body></html>"""
    private val nav = """<html xmlns="http://www.w3.org/1999/xhtml"><head><title>Contents</title></head><body><nav><a href="chapter.xhtml#p">Start</a></nav></body></html>"""
    private val css = "p { font-style: italic; }".toByteArray()
    private val pic = byteArrayOf(0, 1, 2, -1)

    private fun fixture(file: File, extra: Map<String, ByteArray> = emptyMap(), chapterXml: String = chapter, packageXml: String = opf) {
        ZipOutputStream(file.outputStream()).use { out ->
            val m = ZipEntry("mimetype").apply { method = ZipEntry.STORED; size = mimetype.size.toLong(); compressedSize = size; crc = CRC32().apply { update(mimetype) }.value }
            out.putNextEntry(m); out.write(mimetype); out.closeEntry()
            val entries = linkedMapOf(
                "META-INF/container.xml" to container.toByteArray(), "OPS/package.opf" to packageXml.toByteArray(),
                "OPS/chapter.xhtml" to chapterXml.toByteArray(), "OPS/nav.xhtml" to nav.toByteArray(),
                "OPS/style.css" to css, "OPS/images/pic.bin" to pic
            )
            entries.putAll(extra)
            for ((name, data) in entries) { out.putNextEntry(ZipEntry(name)); out.write(data); out.closeEntry() }
        }
    }

    private fun <T> runSuspend(block: suspend () -> T): T {
        var result: Result<T>? = null
        block.startCoroutine(object : Continuation<T> {
            override val context = EmptyCoroutineContext
            override fun resumeWith(value: Result<T>) { result = value }
        })
        return checkNotNull(result) { "Test translator unexpectedly suspended" }.getOrThrow()
    }

    private fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(bytes.inputStream())
    private fun read(zip: ZipFile, name: String) = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
    private fun translate(src: File, out: File, translator: EpubTextTranslator = EpubTextTranslator { text, _, _ -> "IT[$text]" }, limits: EpubOfflineTranslator.Limits = EpubOfflineTranslator.Limits()) =
        runSuspend { EpubOfflineTranslator.translate(src, out, "en", "it", translator, limits) }

    @Test fun translatesNewEpubAndPreservesMarkupLinksAndOtherResources() {
        val src = temp.newFile("original.epub"); fixture(src)
        val before = src.readBytes(); val out = File(temp.root, "translated.epub")
        val result = translate(src, out)
        assertEquals(2, result.translatedDocuments); assertTrue(result.translatedNodes > 0)
        assertArrayEquals(before, src.readBytes())
        ZipFile(out).use { z ->
            val entries = z.entries(); val first = entries.nextElement()
            assertEquals("mimetype", first.name); assertEquals(ZipEntry.STORED, first.method); assertTrue(first.extra == null || first.extra.isEmpty()); assertArrayEquals(mimetype, read(z, "mimetype"))
            assertArrayEquals(css, read(z, "OPS/style.css")); assertArrayEquals(pic, read(z, "OPS/images/pic.bin"))
            val doc = xml(read(z, "OPS/chapter.xhtml")); val html = "http://www.w3.org/1999/xhtml"
            val link = doc.getElementsByTagNameNS(html, "a").item(0) as Element
            assertEquals("https://example.org/?a=1&b=2", link.getAttribute("href"))
            assertEquals("IT[world]", link.getElementsByTagNameNS(html, "em").item(0).textContent)
            val img = doc.getElementsByTagNameNS(html, "img").item(0) as Element
            assertEquals("images/pic.bin", img.getAttribute("src")); assertEquals("IT[A cat]", img.getAttribute("alt"))
            assertEquals("code & data", doc.getElementsByTagNameNS(html, "pre").item(0).textContent)
            val metadata = xml(read(z, "OPS/package.opf")); val dc = "http://purl.org/dc/elements/1.1/"
            assertEquals("Original & Title [Translated: it]", metadata.getElementsByTagNameNS(dc, "title").item(0).textContent)
            assertEquals("it", metadata.getElementsByTagNameNS(dc, "language").item(0).textContent)
            assertTrue(metadata.getElementsByTagNameNS(dc, "identifier").item(0).textContent.startsWith("urn:uuid:"))
            val metas = metadata.getElementsByTagNameNS("http://www.idpf.org/2007/opf", "meta")
            assertTrue((0 until metas.length).any { (metas.item(it) as Element).getAttribute("property") == "dcterms:modified" })
            val navDoc = xml(read(z, "OPS/nav.xhtml"))
            assertEquals("IT[Start]", navDoc.getElementsByTagNameNS(html, "a").item(0).textContent)
            assertEquals("chapter.xhtml#p", (navDoc.getElementsByTagNameNS(html, "a").item(0) as Element).getAttribute("href"))
        }
    }

    @Test fun epub2NcxLabelsAndIdentifierAreUpdated() {
        val ncx = """<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><head><meta name="dtb:uid" content="original-id"/></head><docTitle><text>Original</text></docTitle><navMap><navPoint id="n1" playOrder="1"><navLabel><text>Start</text></navLabel><content src="chapter.xhtml#p"/></navPoint></navMap></ncx>"""
        val opf2 = opf.replace("version=\"3.0\"", "version=\"2.0\"")
            .replace("<manifest>", "<manifest><item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>")
            .replace("<spine>", "<spine toc=\"ncx\">")
        val src = temp.newFile("epub2.epub"); fixture(src, mapOf("OPS/toc.ncx" to ncx.toByteArray()), packageXml = opf2)
        val out = File(temp.root, "epub2-out.epub"); translate(src, out)
        ZipFile(out).use { z ->
            val d = xml(read(z, "OPS/toc.ncx")); val ns = "http://www.daisy.org/z3986/2005/ncx/"
            val uid = (d.getElementsByTagNameNS(ns, "meta").item(0) as Element).getAttribute("content")
            val packageDoc = xml(read(z, "OPS/package.opf"))
            assertEquals(packageDoc.getElementsByTagNameNS("http://purl.org/dc/elements/1.1/", "identifier").item(0).textContent, uid)
            assertEquals("IT[Start]", d.getElementsByTagNameNS(ns, "navLabel").item(0).textContent)
            assertEquals("chapter.xhtml#p", (d.getElementsByTagNameNS(ns, "content").item(0) as Element).getAttribute("src"))
        }
    }

    @Test fun engineFailureLeavesSourceAndNoDestination() {
        val src = temp.newFile("original.epub"); fixture(src); val before = src.readBytes(); val out = File(temp.root, "out.epub")
        try { translate(src, out, EpubTextTranslator { _, _, _ -> error("engine failed") }); fail("expected engine failure") } catch (_: IllegalStateException) {}
        assertFalse(out.exists()); assertArrayEquals(before, src.readBytes())
    }

    @Test fun existingDestinationNeverOverwritten() {
        val src = temp.newFile("original.epub"); fixture(src)
        val out = temp.newFile("existing.epub"); out.writeText("keep me")
        try { translate(src, out); fail("expected refusal") } catch (_: IllegalArgumentException) {}
        assertEquals("keep me", out.readText())
    }

    @Test fun refusesUnsafeZipPathAndEncryptionAndOversizedEntries() {
        for ((name, extra, limits) in listOf(
            Triple("traversal", mapOf("../escape" to byteArrayOf(1)), EpubOfflineTranslator.Limits()),
            Triple("encrypted", mapOf("META-INF/encryption.xml" to byteArrayOf(1)), EpubOfflineTranslator.Limits()),
            Triple("small-limit", emptyMap(), EpubOfflineTranslator.Limits(maxEntryBytes = 32))
        )) {
            val src = temp.newFile("$name.epub"); fixture(src, extra); val out = File(temp.root, "$name-out.epub")
            try { translate(src, out, limits = limits); fail("expected refusal: $name") } catch (_: IllegalArgumentException) {}
            assertFalse(out.exists())
        }
    }

    @Test fun refusesEmbeddedSvgAndReportsProgress() {
        val src = temp.newFile("svg.epub")
        val xml = chapter.replace("<p id=\"p\">", "<svg xmlns=\"http://www.w3.org/2000/svg\"><text>Words</text></svg><p id=\"p\">")
        fixture(src, chapterXml = xml)
        val out = File(temp.root, "svg-out.epub")
        try { translate(src, out); fail("expected SVG refusal") } catch (_: IllegalArgumentException) {}
        assertFalse(out.exists())
        val clean = temp.newFile("progress.epub"); fixture(clean)
        val done = mutableListOf<Int>()
        runSuspend { EpubOfflineTranslator.translate(clean, File(temp.root, "progress-out.epub"), "en", "it",
            EpubTextTranslator { t, _, _ -> "IT[$t]" }, onProgress = { completed, _, _ -> done.add(completed) }) }
        assertEquals(listOf(0, 1, 1, 2), done)
    }

    @Test fun refusesDoctypeAndInvalidTranslatorOutput() {
        val src = temp.newFile("doctype.epub"); fixture(src, chapterXml = "<!DOCTYPE html [<!ENTITY evil SYSTEM 'file:///etc/passwd'>]>" + chapter.substringAfter("?>"))
        val out = File(temp.root, "doctype-out.epub")
        try { translate(src, out); fail("expected DOCTYPE refusal") } catch (_: Exception) {}
        assertFalse(out.exists())
        val clean = temp.newFile("clean.epub"); fixture(clean); val bad = File(temp.root, "invalid-out.epub")
        try { translate(clean, bad, EpubTextTranslator { _, _, _ -> "\u0000" }); fail("expected invalid output refusal") } catch (_: IllegalArgumentException) {}
        assertFalse(bad.exists())
    }
}
