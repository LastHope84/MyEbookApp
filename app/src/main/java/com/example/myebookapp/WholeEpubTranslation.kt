package com.example.myebookapp

import android.content.Context
import android.net.Uri
import android.system.Os
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** One operation, in app-private storage. An indexed EPUB is never a conversion link. */
object WholeEpubTranslation {
    suspend fun translate(context: Context, book: BookEntity, source: String, target: String,
                          progress: suspend (String) -> Unit): BookEntity = withContext(Dispatchers.IO) {
        check(ThemePrefs.isOfflineTranslationEnabled(context)) { "Offline translation is disabled or unavailable." }
        require(book.format.equals("EPUB", true)) { "Only EPUB books can be translated." }
        val tag = Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*")
        require(tag.matches(source) && tag.matches(target) && !source.equals(target, true)) { "Choose different valid source and target language codes." }
        val dir = File(context.filesDir, "translated_epubs")
        check(dir.isDirectory || dir.mkdirs()) { "Could not create translation storage." }
        val token = UUID.randomUUID().toString()
        val workDir = File(dir, ".$token.work")
        check(workDir.mkdir()) { "Could not claim private translation workspace." }
        val input = File(workDir, "source")
        val stage = File(workDir, "result.staging")
        val final = File(dir, "translated-$token.epub")
        var published = false
        var indexed = false
        try {
            check(input.createNewFile()) { "Could not create private staging file." }
            progress("Copying original EPUB…")
            val uri = Uri.parse(book.uriString)
            val stream = if (uri.scheme == "file") File(requireNotNull(uri.path)).inputStream()
                         else context.contentResolver.openInputStream(uri) ?: error("Cannot read the original EPUB.")
            stream.use { src -> input.outputStream().use { dst ->
                val buf = ByteArray(32768); var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = src.read(buf); if (n < 0) break
                    total += n; require(total <= 128L * 1024 * 1024) { "Source archive exceeds 128 MiB." }
                    dst.write(buf, 0, n)
                }
            } }
            EpubOfflineTranslator.translate(input, stage, source, target,
                EpubTextTranslator { text, src, dst ->
                    currentCoroutineContext().ensureActive()
                    check(ThemePrefs.isOfflineTranslationEnabled(context)) { "Offline translation was disabled." }
                    TranslationRuntime.translate(context, requireNotNull(src), dst, text)
                }, onProgress = { done, total, nodes ->
                    currentCoroutineContext().ensureActive()
                    progress("Translated $done of $total documents ($nodes text segments)…")
                })
            currentCoroutineContext().ensureActive()
            progress("Saving translated copy…")
            // No suspension/cancellation between publication and indexing. Hard-link creation is
            // atomic and fails if the UUID path exists; stage and output share a filesystem.
            withContext(NonCancellable) {
                try {
                    Os.link(stage.absolutePath, final.absolutePath)
                    published = true
                    val uriOut = Uri.fromFile(final).toString()
                    val entry = BookEntity(uriString = uriOut, fileName = final.name, format = "EPUB",
                        fileSize = final.length(), lastModified = final.lastModified(),
                        title = "${book.title} [Translated: $target]", author = book.author,
                        publishingDate = book.publishingDate, publisher = book.publisher, language = target,
                        coverPath = book.coverPath, lastLocatorJson = null, progressPercent = 0.0,
                        lastOpenedAt = 0L, metadataExtracted = true, series = book.series,
                        seriesIndex = book.seriesIndex, tags = book.tags)
                    val id = AppDatabase.getInstance(context).bookDao().insertIgnore(entry)
                    check(id != -1L) { "Could not add translated copy to the library." }
                    indexed = true
                    entry.copy(id = id)
                } catch (t: Throwable) {
                    if (published && !indexed) final.delete()
                    throw t
                }
            }
        } finally {
            input.delete()
            stage.delete()
            workDir.delete()
            if (published && !indexed) final.delete()
        }
    }
}
