package com.example.myebookapp

import android.content.Context
import android.net.Uri
import android.util.LruCache
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

data class PdfWord(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

data class PdfMatch(val range: IntRange, val snippet: String)

class PdfPageText(
    val pageWidth: Float,
    val pageHeight: Float,
    val words: List<PdfWord>
) {
    val text: String
    val wordStarts: IntArray
    val wordEnds: IntArray

    init {
        val builder = StringBuilder()
        val starts = IntArray(words.size)
        val ends = IntArray(words.size)
        words.forEachIndexed { index, word ->
            if (builder.isNotEmpty()) builder.append(' ')
            starts[index] = builder.length
            builder.append(word.text)
            ends[index] = builder.length
        }
        text = builder.toString()
        wordStarts = starts
        wordEnds = ends
    }

    fun wordText(index: Int): String = words.getOrNull(index)?.text ?: ""

    fun textForRange(range: IntRange): String {
        if (words.isEmpty()) return ""
        val first = range.first.coerceIn(0, words.lastIndex)
        val last = range.last.coerceIn(first, words.lastIndex)
        return text.substring(wordStarts[first], wordEnds[last])
    }

    fun sentenceRangeFor(wordIndex: Int): IntRange {
        if (words.isEmpty()) return 0..0
        val index = wordIndex.coerceIn(0, words.lastIndex)
        val offset = wordStarts[index]
        var start = offset
        while (start > 0) {
            val ch = text[start - 1]
            if (ch == '.' || ch == '!' || ch == '?' || ch == '…' || ch == '\n') break
            start--
        }
        var end = offset
        while (end < text.length) {
            val ch = text[end]
            end++
            if (ch == '.' || ch == '!' || ch == '?' || ch == '…' || ch == '\n') break
        }
        return wordRangeForOffsets(start, end) ?: (index..index)
    }

    fun wordRangeForOffsets(start: Int, end: Int): IntRange? {
        if (words.isEmpty() || end <= start) return null
        var first = -1
        var last = -1
        for (i in words.indices) {
            if (wordEnds[i] <= start) continue
            if (wordStarts[i] >= end) break
            if (first < 0) first = i
            last = i
        }
        return if (first in 0..last) first..last else null
    }

    fun findMatches(query: String, limit: Int = 60): List<PdfMatch> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        val haystack = text.lowercase()
        val target = needle.lowercase()
        val out = mutableListOf<PdfMatch>()
        var from = 0
        while (out.size < limit) {
            val at = haystack.indexOf(target, from)
            if (at < 0) break
            val range = wordRangeForOffsets(at, at + target.length)
            if (range != null) {
                val snippetStart = (at - 32).coerceAtLeast(0)
                val snippetEnd = (at + target.length + 32).coerceAtMost(text.length)
                out.add(PdfMatch(range, text.substring(snippetStart, snippetEnd).trim()))
            }
            from = at + target.length.coerceAtLeast(1)
        }
        return out
    }
}

/**
 * Extracts word positions with PDFBox. Rendering stays with the native PdfRenderer;
 * this is only the selectable/searchable text layer. PDFBox is single-threaded,
 * so every document call runs on one dedicated thread.
 */
class PdfTextSource private constructor(
    private val document: PDDocument,
    private val executor: ExecutorService,
    private val dispatcher: ExecutorCoroutineDispatcher,
    val pageCount: Int
) : Closeable {
    private val cache = LruCache<Int, PdfPageText>(16)

    suspend fun page(index: Int): PdfPageText? = withContext(dispatcher) {
        if (index !in 0 until pageCount) return@withContext null
        cache.get(index)?.let { return@withContext it }
        runCatching { extract(index) }.getOrNull()?.also { cache.put(index, it) }
    }

    private fun extract(index: Int): PdfPageText {
        val page = document.getPage(index)
        val rotation = ((page.rotation % 360) + 360) % 360
        val box = page.cropBox ?: page.mediaBox
        val baseWidth = box.width
        val baseHeight = box.height
        val words = mutableListOf<PdfWord>()

        val stripper = object : PDFTextStripper() {
            override fun writeString(text: String, textPositions: List<TextPosition>) {
                val value = text.trim()
                if (value.isEmpty() || textPositions.isEmpty()) return
                var left = Float.MAX_VALUE
                var top = Float.MAX_VALUE
                var right = -Float.MAX_VALUE
                var bottom = -Float.MAX_VALUE
                for (position in textPositions) {
                    val width = position.widthDirAdj
                    val height = position.heightDir
                    if (width <= 0f && height <= 0f) continue
                    val x = position.xDirAdj
                    // yDirAdj is the baseline; the glyph box sits mostly above it.
                    val baseline = position.yDirAdj
                    left = min(left, x)
                    top = min(top, baseline - height * 0.85f)
                    right = max(right, x + width)
                    bottom = max(bottom, baseline + height * 0.15f)
                }
                if (left == Float.MAX_VALUE) return
                words.add(
                    PdfWord(
                        text = value,
                        x = left,
                        y = top,
                        width = (right - left).coerceAtLeast(1f),
                        height = (bottom - top).coerceAtLeast(1f)
                    )
                )
            }
        }
        stripper.sortByPosition = true
        stripper.startPage = index + 1
        stripper.endPage = index + 1
        stripper.getText(document)

        val rotated = words.map { rotate(it, rotation, baseWidth, baseHeight) }
        val displayWidth = if (rotation == 90 || rotation == 270) baseHeight else baseWidth
        val displayHeight = if (rotation == 90 || rotation == 270) baseWidth else baseHeight
        return PdfPageText(displayWidth, displayHeight, rotated)
    }

    private fun rotate(word: PdfWord, rotation: Int, pageWidth: Float, pageHeight: Float): PdfWord =
        when (rotation) {
            90 -> PdfWord(word.text, pageHeight - (word.y + word.height), word.x, word.height, word.width)
            180 -> PdfWord(
                word.text,
                pageWidth - (word.x + word.width),
                pageHeight - (word.y + word.height),
                word.width,
                word.height
            )
            270 -> PdfWord(word.text, word.y, pageWidth - (word.x + word.width), word.height, word.width)
            else -> word
        }

    override fun close() {
        runCatching {
            executor.submit { runCatching { document.close() } }.get(3, TimeUnit.SECONDS)
        }
        runCatching { dispatcher.close() }
    }

    companion object {
        suspend fun open(
            context: Context,
            uriString: String,
            fileSize: Long,
            lastModified: Long
        ): PdfTextSource {
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "pdf-text") }
            val dispatcher = executor.asCoroutineDispatcher()
            return withContext(dispatcher) {
                try {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    val file = textFile(context, uriString, fileSize, lastModified)
                    val tempDir = File(context.cacheDir, "pdfbox").apply { mkdirs() }
                    val document = PDDocument.load(
                        file,
                        MemoryUsageSetting.setupMixed(32L * 1024 * 1024).setTempDir(tempDir)
                    )
                    PdfTextSource(document, executor, dispatcher, document.numberOfPages)
                } catch (e: CancellationException) {
                    runCatching { dispatcher.close() }
                    throw e
                } catch (e: Exception) {
                    runCatching { dispatcher.close() }
                    throw IllegalStateException("This PDF has no readable text layer.", e)
                }
            }
        }

        private fun textFile(
            context: Context,
            uriString: String,
            fileSize: Long,
            lastModified: Long
        ): File {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") {
                val direct = uri.path?.let { File(it) }
                if (direct != null && direct.isFile) return direct
            }
            val (size, modified) = queryDocumentVersion(context, uri, fileSize, lastModified)
            return cachedDocumentCopy(context, uriString, size, modified, "pdfs", "pdf")
        }
    }
}
