package com.example.myebookapp

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * PdfRenderer is documented as single-threaded, so every renderer call runs on
 * one dedicated thread. Pages render to white because PDFs may be transparent.
 */
class PdfPageSource private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    private val executor: ExecutorService,
    private val dispatcher: ExecutorCoroutineDispatcher,
    override val pageCount: Int
) : PagedImageSource {

    override suspend fun decode(index: Int, maxWidth: Int, maxHeight: Int): Bitmap? =
        withContext(dispatcher) {
            if (index !in 0 until pageCount) return@withContext null
            runCatching {
                val page = renderer.openPage(index)
                try {
                    val target = min(
                        (maxWidth * 2.0) / page.width,
                        (maxHeight * 2.0) / page.height
                    ).coerceIn(0.5, 4.0)
                    val cap = 4096.0 / max(page.width, page.height)
                    val scale = min(target, cap)
                    val width = (page.width * scale).toInt().coerceAtLeast(1)
                    val height = (page.height * scale).toInt().coerceAtLeast(1)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                } finally {
                    runCatching { page.close() }
                }
            }.getOrNull()
        }

    override fun close() {
        runCatching {
            executor.submit {
                runCatching { renderer.close() }
                runCatching { descriptor.close() }
            }.get(3, TimeUnit.SECONDS)
        }
        runCatching { dispatcher.close() }
    }

    companion object {
        suspend fun open(
            context: Context,
            uriString: String,
            fileSize: Long,
            lastModified: Long
        ): PdfPageSource {
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "pdf-renderer") }
            val dispatcher = executor.asCoroutineDispatcher()
            return withContext(dispatcher) {
                try {
                    val (descriptor, renderer) = openRenderer(context, uriString, fileSize, lastModified)
                    PdfPageSource(descriptor, renderer, executor, dispatcher, renderer.pageCount)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    runCatching { dispatcher.close() }
                    throw e
                } catch (e: Exception) {
                    runCatching { dispatcher.close() }
                    throw IllegalStateException(
                        "This PDF could not be opened. It may be damaged or password-protected.", e
                    )
                }
            }
        }

        private fun openRenderer(
            context: Context,
            uriString: String,
            fileSize: Long,
            lastModified: Long
        ): Pair<ParcelFileDescriptor, PdfRenderer> {
            val uri = Uri.parse(uriString)
            if (uri.scheme == "file") {
                val path = uri.path ?: throw IllegalStateException("Invalid file path.")
                val descriptor = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    return descriptor to PdfRenderer(descriptor)
                } catch (e: Exception) {
                    runCatching { descriptor.close() }
                    throw e
                }
            }

            // Prefer the provider's own descriptor. Pipes (some cloud providers)
            // are not seekable, and then a cached copy is required.
            val direct = context.contentResolver.openFileDescriptor(uri, "r")
            if (direct != null) {
                val seekable = runCatching { direct.statSize >= 0 }.getOrDefault(false)
                if (seekable) {
                    try {
                        return direct to PdfRenderer(direct)
                    } catch (e: Exception) {
                        runCatching { direct.close() }
                    }
                } else {
                    runCatching { direct.close() }
                }
            }

            val (size, modified) = queryDocumentVersion(context, uri, fileSize, lastModified)
            val file = cachedDocumentCopy(context, uriString, size, modified, "pdfs", "pdf")
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                return descriptor to PdfRenderer(descriptor)
            } catch (e: Exception) {
                runCatching { descriptor.close() }
                throw e
            }
        }
    }
}

object PdfParser {
    /** PDF metadata is not exposed by PdfRenderer; use the file name and page 1 as the cover. */
    suspend fun parse(
        context: Context,
        uriString: String,
        fileName: String,
        fileSize: Long,
        lastModified: Long
    ): EpubMetadata {
        val source = PdfPageSource.open(context, uriString, fileSize, lastModified)
        return try {
            EpubMetadata(
                fileName = fileName,
                title = fileName.substringBeforeLast('.').ifBlank { "Untitled PDF" },
                author = "Unknown",
                publishingDate = "Unknown",
                publisher = "Unknown",
                language = "Unknown",
                coverBitmap = source.decode(0, 512, 512),
                series = null,
                seriesIndex = null,
                tags = null
            )
        } finally {
            source.close()
        }
    }
}
