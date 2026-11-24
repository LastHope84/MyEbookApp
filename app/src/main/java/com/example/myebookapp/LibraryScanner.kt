package com.example.myebookapp

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScanStatus(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val canResume: Boolean = false,
    val phaseLabel: String = "",
    val filesFoundInPass: Int = 0,
    val metadataProcessed: Int = 0,
    val metadataLeft: Int = 0,
    val queuedFolders: Int = 0,
    val warning: String? = null,
    val error: String? = null
)

/** Folder scans are journaled as whole roots. Replaying a root is safe because book
 * inserts are IGNORE by URI, and the root is removed only after its walk commits. */
object LibraryScanner {
    private const val PREF = "scan_session_v1"
    private const val IGNORED_PREF = "scan_ignored_folders_v1"
    private const val RUNNING = "running"
    private const val PAUSED = "paused"
    private const val INTERRUPTED = "interrupted"
    private const val STOPPED = "stopped"
    private const val BATCH_SIZE = 200
    private const val METADATA_BATCH = 60

    private val lock = Any()
    // A cancelled worker may still be in blocking provider I/O or cover cleanup.
    // Serialize whole workers so two scans cannot overlap.
    private val processingMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paused = MutableStateFlow(false)
    private var activeJob: Job? = null
    private var generation = 0L
    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun roots(context: Context): MutableList<String> {
        val json = JSONArray(prefs(context).getString("roots", "[]"))
        return MutableList(json.length()) { json.getString(it) }
    }
    private fun mode(context: Context) = prefs(context).getString("mode", STOPPED) ?: STOPPED
    private fun save(context: Context, mode: String, roots: List<String>) {
        check(prefs(context).edit().putString("mode", mode).putString("roots", JSONArray(roots).toString()).commit()) {
            "Could not save the scan checkpoint"
        }
    }

    fun logTts(context: Context, message: String) = log(context, "TTS: $message")

    private fun log(context: Context, message: String) {
        runCatching {
            val file = File(context.filesDir, "scan.log")
            if (file.length() > 256 * 1024) file.writeText("")
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            file.appendText("$stamp  $message\n")
        }
    }

    fun readLog(context: Context, maxLines: Int = 400): String = runCatching {
        val file = File(context.filesDir, "scan.log")
        if (!file.exists()) "No scan log yet."
        else file.readLines().takeLast(maxLines).joinToString("\n")
    }.getOrElse { "Could not read the scan log: ${it.localizedMessage}" }

    fun getSavedFolders(context: Context): Set<String> =
        context.getSharedPreferences("my_ebook_folders", Context.MODE_PRIVATE).getStringSet("folder_uris", emptySet())?.toSet() ?: emptySet()

    fun addFolder(context: Context, treeUri: Uri) {
        val updated = getSavedFolders(context).toMutableSet().apply { add(treeUri.toString()) }
        check(context.getSharedPreferences("my_ebook_folders", Context.MODE_PRIVATE).edit().putStringSet("folder_uris", updated).commit()) {
            "Could not save the folder"
        }
    }

    /** Subfolders the user removed and asked to skip in future scans, keyed by scan root. */
    fun ignoredFolders(context: Context, rootUri: Uri): Set<String> =
        context.getSharedPreferences(IGNORED_PREF, Context.MODE_PRIVATE)
            .getStringSet(rootUri.toString(), emptySet())?.toSet() ?: emptySet()

    fun ignoreFolder(context: Context, rootUri: Uri, directoryId: String) {
        val prefs = context.getSharedPreferences(IGNORED_PREF, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(rootUri.toString(), emptySet())?.toMutableSet() ?: mutableSetOf()
        current.add(directoryId)
        prefs.edit().putStringSet(rootUri.toString(), current).apply()
    }

    fun unignoreFolder(context: Context, rootUri: Uri, directoryId: String) {
        val prefs = context.getSharedPreferences(IGNORED_PREF, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(rootUri.toString(), emptySet())?.toMutableSet() ?: return
        if (current.remove(directoryId)) prefs.edit().putStringSet(rootUri.toString(), current).apply()
    }

    private fun clearIgnoredFolders(context: Context, rootUri: Uri) {
        context.getSharedPreferences(IGNORED_PREF, Context.MODE_PRIVATE)
            .edit().remove(rootUri.toString()).apply()
    }

    /** Called from visible UI only; never starts work automatically on process launch. */
    fun restore(context: Context) = synchronized(lock) {
        if (activeJob?.isActive == true) return@synchronized
        val current = mode(context)
        if (current == RUNNING) save(context, INTERRUPTED, roots(context))
        val savedMode = mode(context)
        if (savedMode == PAUSED || savedMode == INTERRUPTED) {
            paused.value = savedMode == PAUSED
            _status.value = ScanStatus(
                isPaused = savedMode == PAUSED,
                canResume = true,
                queuedFolders = roots(context).size,
                phaseLabel = if (savedMode == PAUSED) "Scan paused" else "Scan interrupted — resume to continue"
            )
        }
    }

    fun scanFolders(context: Context, folderUris: List<Uri>) {
        val app = context.applicationContext
        try {
            val shouldStart = synchronized(lock) {
                val queue = roots(app)
                folderUris.map { it.toString() }.forEach { if (it !in queue) queue.add(it) }
                val old = mode(app)
                // Adding a folder must not silently undo an explicit pause.
                val next = if (old == PAUSED) PAUSED else RUNNING
                save(app, next, queue)
                log(app, "Scan requested: ${queue.size} folder(s), mode=$next")
                _status.update {
                    it.copy(
                        isScanning = next == RUNNING || it.isScanning,
                        queuedFolders = queue.size,
                        canResume = next == PAUSED,
                        isPaused = next == PAUSED,
                        phaseLabel = if (next == PAUSED) "Scan paused" else "Starting scan…",
                        warning = null,
                        error = null
                    )
                }
                next == RUNNING
            }
            if (shouldStart) startForeground(app)
        } catch (e: Exception) { reportStartFailure(app, e) }
    }

    fun pauseScan(context: Context) {
        val app = context.applicationContext
        try {
            synchronized(lock) {
                if (mode(app) != RUNNING) return
                save(app, PAUSED, roots(app))
                paused.value = true
                _status.update { it.copy(isPaused = true, canResume = true, phaseLabel = "Scan paused") }
            }
        } catch (e: Exception) { reportStartFailure(app, e) }
    }

    fun resumeScan(context: Context) {
        val app = context.applicationContext
        try {
            synchronized(lock) {
                if (mode(app) != PAUSED && mode(app) != INTERRUPTED) return
                save(app, RUNNING, roots(app))
                paused.value = false
                _status.update { it.copy(isScanning = true, isPaused = false, canResume = false, phaseLabel = "Resuming…", warning = null, error = null) }
            }
            startForeground(app)
        } catch (e: Exception) { reportStartFailure(app, e) }
    }

    fun stopScan(context: Context) {
        val app = context.applicationContext
        try {
            synchronized(lock) {
                save(app, STOPPED, emptyList())
                generation++
                paused.value = false
                activeJob?.cancel()
                _status.value = ScanStatus(phaseLabel = "Scan stopped")
            }
        } catch (e: Exception) { reportStartFailure(app, e) }
    }

    /** Removes a saved root and its library entries. Source ebook files are never touched. */
    suspend fun removeFolder(context: Context, treeUri: Uri): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        synchronized(lock) {
            check(activeJob?.isActive != true) { "Stop the current scan before removing a folder." }
            val saved = getSavedFolders(app) - treeUri.toString()
            check(app.getSharedPreferences("my_ebook_folders", Context.MODE_PRIVATE).edit()
                .putStringSet("folder_uris", saved).commit()) { "Could not save the folder list." }
            val queue = roots(app).filterNot { it == treeUri.toString() }
            val nextMode = if (queue.isEmpty() && mode(app) == RUNNING) STOPPED else mode(app)
            save(app, nextMode, queue)
            _status.update { it.copy(queuedFolders = queue.size) }
        }
        val prefix = treeUri.toString().trimEnd('/') + "/document/"
        val deleted = AppDatabase.getInstance(app).bookDao().deleteByUriPrefix(prefix)
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        clearIgnoredFolders(app, treeUri)
        log(app, "Removed folder $treeUri: $deleted library entries")
        deleted
    }

    /** Removes the selected subfolder and its descendants from the library. Files are untouched. */
    suspend fun removeSubfolder(context: Context, rootUri: Uri, directoryId: String): Int = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        synchronized(lock) { check(activeJob?.isActive != true) { "Stop the current scan before removing a folder." } }
        val ids = collectDirectoryIds(app, rootUri, directoryId)
        val dao = AppDatabase.getInstance(app).bookDao()
        var deleted = 0
        for (chunk in ids.chunked(500)) deleted += dao.deleteByScanFolders(rootUri.toString(), chunk)
        ignoreFolder(app, rootUri, directoryId)
        log(app, "Removed subfolder $directoryId under $rootUri: $deleted library entries; skipped in future scans")
        deleted
    }

    private fun collectDirectoryIds(context: Context, rootUri: Uri, startId: String): List<String> {
        val out = mutableListOf<String>()
        val queue = ArrayDeque<String>().apply { addLast(startId) }
        val seen = mutableSetOf<String>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            out.add(id)
            if (out.size > 5000) throw IllegalStateException("This folder has too many subfolders to remove safely. Remove its parent folder instead.")
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, id)
            val cursor = context.contentResolver.query(children, projection, null, null, null)
                ?: throw IllegalStateException("Folder access was lost. Add the folder again, or restore its permission.")
            cursor.use { c ->
                val extras = runCatching { c.extras }.getOrNull()
                if (extras?.containsKey(DocumentsContract.EXTRA_ERROR) == true) {
                    throw IllegalStateException("The folder provider reported an error while listing this folder. Nothing was removed.")
                }
                while (c.moveToNext()) {
                    val childId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: ""
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR && !name.startsWith(".")) queue.addLast(childId)
                }
            }
        }
        return out
    }

    private fun startForeground(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, ScanService::class.java))
    }

    private fun reportStartFailure(context: Context, e: Exception) {
        synchronized(lock) {
            try { if (mode(context) == RUNNING) save(context, INTERRUPTED, roots(context)) } catch (_: Exception) { }
            paused.value = false
            activeJob?.cancel()
            _status.update {
                it.copy(
                    isScanning = false,
                    canResume = true,
                    phaseLabel = "Scan interrupted",
                    error = e.localizedMessage ?: "Could not start scan"
                )
            }
        }
    }

    /** The foreground service is the only worker owner. */
    fun startWorker(context: Context, resumeInterrupted: Boolean = false, finished: () -> Unit): Boolean = synchronized(lock) {
        // Android can restart the service after killing it. Resume only a scan the
        // system interrupted; never override an explicit pause or stop.
        if (resumeInterrupted && mode(context) == INTERRUPTED) save(context, RUNNING, roots(context))
        if (mode(context) != RUNNING || activeJob?.isActive == true) return@synchronized activeJob?.isActive == true
        val token = ++generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                processingMutex.withLock {
                    synchronized(lock) {
                        ensureActive()
                        if (generation != token) throw CancellationException("Scan session changed")
                        val savedMode = mode(context)
                        if (savedMode != RUNNING && savedMode != PAUSED) throw CancellationException("Scan no longer active")
                        val isPaused = savedMode == PAUSED
                        _status.value = ScanStatus(
                            isScanning = true,
                            isPaused = isPaused,
                            canResume = isPaused,
                            phaseLabel = if (isPaused) "Scan paused" else "Preparing…"
                        )
                    }
                    while (true) {
                        runScan(context, token)
                        val complete = synchronized(lock) {
                            if (generation == token && mode(context) == RUNNING && roots(context).isEmpty()) {
                                save(context, STOPPED, emptyList())
                                _status.update { it.copy(isScanning = false, phaseLabel = "Scan complete", queuedFolders = 0) }
                                true
                            } else false
                        }
                        if (complete) {
                            try {
                                AppDatabase.getInstance(context).bookDao().optimizeSearchIndex()
                            } catch (_: Exception) { }
                            break
                        }
                        checkpoint()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log(context, "Scan failed: ${e.stackTraceToString()}")
                synchronized(lock) {
                    if (generation == token) {
                        try { if (mode(context) == RUNNING) save(context, INTERRUPTED, roots(context)) } catch (_: Exception) { }
                        _status.update {
                            it.copy(
                                isScanning = false,
                                canResume = true,
                                phaseLabel = "Scan interrupted",
                                error = e.localizedMessage ?: "Scan failed"
                            )
                        }
                    }
                }
            }
        }
        activeJob = job
        job.invokeOnCompletion {
            synchronized(lock) { if (generation == token) activeJob = null }
            finished()
        }
        job.start()
        true
    }

    /** Service timeout or destruction leaves the current roots in the journal. */
    fun interrupt(context: Context, reason: String) = synchronized(lock) {
        try {
            if (mode(context) == RUNNING) save(context, INTERRUPTED, roots(context))
            if (mode(context) == RUNNING || mode(context) == INTERRUPTED) {
                generation++
                activeJob?.cancel()
                paused.value = false
                val existingError = _status.value.error
                _status.update {
                    it.copy(
                        isScanning = false,
                        canResume = true,
                        phaseLabel = "Scan interrupted",
                        // Keep the worker's specific folder error instead of replacing it
                        // with the service's generic shutdown message.
                        error = existingError ?: reason
                    )
                }
            } else if (mode(context) == PAUSED) {
                generation++
                activeJob?.cancel()
                _status.update { it.copy(isScanning = false, isPaused = true, canResume = true, phaseLabel = "Scan paused") }
            }
        } catch (e: Exception) {
            generation++
            activeJob?.cancel()
            _status.update { it.copy(isScanning = false, canResume = true, error = "Could not save scan checkpoint") }
        }
    }

    private suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
        if (paused.value) paused.first { !it }
        currentCoroutineContext().ensureActive()
    }

    private suspend fun runScan(context: Context, token: Long) {
        val dao = AppDatabase.getInstance(context).bookDao()
        while (true) {
            checkpoint()
            val next = synchronized(lock) { roots(context).firstOrNull() }
            if (next != null) {
                log(context, "Scanning folder: $next")
                _status.update { if (it.isPaused) it else it.copy(phaseLabel = "Scanning folders…") }
                walkTree(context, dao, Uri.parse(next))
                checkpoint()
                synchronized(lock) {
                    if (generation != token) throw CancellationException("Scan session changed")
                    val queue = roots(context)
                    queue.remove(next)
                    save(context, mode(context), queue)
                    log(context, "Folder complete: $next (${queue.size} left)")
                    _status.update { it.copy(queuedFolders = queue.size) }
                }
                continue
            }
            processMetadata(context, dao)
            checkpoint()
            if (synchronized(lock) { roots(context).isEmpty() }) return
        }
    }

    private suspend fun walkTree(context: Context, dao: BookDao, treeUri: Uri) {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri).also { require(it.isNotEmpty()) }
        val ignored = ignoredFolders(context, treeUri)
        val queue = ArrayDeque<String>().apply { addLast(rootId) }
        val seen = mutableSetOf<String>()
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val batch = ArrayList<BookEntity>(BATCH_SIZE)
        // A document ID identifies the file even when the same file is reachable through
        // another selected root. Name and size alone do not: two different books can share them.
        val storedUriByIdentity = HashMap<String, String>()
        for (stored in dao.getAllUris()) {
            runCatching {
                val uri = Uri.parse(stored)
                val authority = uri.authority ?: return@runCatching
                storedUriByIdentity["$authority|${DocumentsContract.getDocumentId(uri)}"] = stored
            }
        }
        val knownIdentities = storedUriByIdentity.keys.toMutableSet()

        suspend fun flush() {
            if (batch.isEmpty()) return
            checkpoint()
            val inserted = dao.insertIgnoreBatch(batch).count { it != -1L }
            // Fill provenance for books scanned before this field existed.
            dao.updateScanProvenanceBatch(batch)
            batch.clear()
            _status.update { it.copy(filesFoundInPass = it.filesFoundInPass + inserted) }
        }

        while (queue.isNotEmpty()) {
            checkpoint()
            val parentId = queue.removeFirst()
            if (!seen.add(parentId)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val cursor = context.contentResolver.query(children, projection, null, null, null)
                ?: run {
                    log(context, "Folder access lost: $children")
                    throw IllegalStateException("Folder access was lost. Add the folder again, or restore its permission.")
                }
            cursor.use { c ->
                fun requireCompleteResults() {
                    val extras = runCatching { c.extras }.getOrNull()
                    val providerError = extras?.getString(DocumentsContract.EXTRA_ERROR)
                    if (extras?.containsKey(DocumentsContract.EXTRA_ERROR) == true) {
                        log(context, "Provider error for $children: ${providerError ?: "unknown"}")
                        throw IllegalStateException(
                            "The folder provider reported an error${if (providerError.isNullOrBlank()) "" else ": $providerError"}. Resume to retry."
                        )
                    }
                    // EXTRA_LOADING means the provider is still filling the cursor, not
                    // that the entries already returned are wrong. Keep scanning them.
                }
                requireCompleteResults()
                while (c.moveToNext()) {
                    checkpoint()
                    val name = c.getString(1) ?: continue
                    val documentId = c.getString(0)?.takeIf { it.isNotEmpty() }
                        ?: run {
                            log(context, "Folder entry without an ID under $parentId")
                            throw IllegalStateException("The folder provider returned a file without an ID. Resume to retry.")
                        }
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".") && documentId !in seen && documentId !in ignored) queue.addLast(documentId)
                        continue
                    }
                    val identity = "${treeUri.authority}|$documentId"
                    if (!knownIdentities.add(identity)) {
                        // Already in the library. A rescan still records which root and
                        // folder it came from so subfolder removal can find it later.
                        storedUriByIdentity[identity]?.let { stored ->
                            dao.updateScanProvenance(stored, treeUri.toString(), parentId)
                        }
                        continue
                    }
                    val format = when (name.substringAfterLast('.', "").lowercase()) {
                        "epub" -> "EPUB"
                        "cbz" -> "CBZ"
                        "cbr" -> "CBR"
                        "pdf" -> "PDF"
                        "mobi" -> "MOBI"
                        "azw3", "azw", "prc" -> "AZW3"
                        else -> null
                    } ?: continue
                    val size = c.getLong(3)
                    batch.add(
                        BookEntity(
                            uriString = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString(),
                            fileName = name,
                            format = format,
                            fileSize = size,
                            lastModified = c.getLong(4),
                            title = name.substringBeforeLast('.'),
                            author = "Unknown",
                            publishingDate = "Unknown",
                            publisher = "Unknown",
                            language = "Unknown",
                            coverPath = null,
                            lastLocatorJson = null,
                            progressPercent = 0.0,
                            lastOpenedAt = 0L,
                            metadataExtracted = format !in setOf("EPUB", "CBZ", "CBR", "PDF"),
                            scanRootUri = treeUri.toString(),
                            scanParentId = parentId
                        )
                    )
                    if (batch.size >= BATCH_SIZE) flush()
                }
                requireCompleteResults()
            }
        }
        flush()
    }

    private suspend fun processMetadata(context: Context, dao: BookDao) {
        var afterId = 0L
        var failed = 0
        val initialPending = dao.countPendingMetadata()
        _status.update {
            it.copy(
                metadataLeft = initialPending,
                phaseLabel = if (it.isPaused) it.phaseLabel else "Reading book details and covers… ${it.metadataProcessed}"
            )
        }
        while (true) {
            checkpoint()
            val pending = dao.getPendingMetadataAfter(afterId, METADATA_BATCH)
            if (pending.isEmpty()) break
            for (book in pending) {
                checkpoint()
                afterId = book.id
                try {
                    val uri = Uri.parse(book.uriString)
                    val parsed = when (book.format) {
                        "CBZ" -> ComicParser.parse(context, uri, book.fileName).let {
                            EpubMetadata(
                                fileName = book.fileName,
                                title = it.title,
                                author = it.author,
                                publishingDate = it.publishingDate,
                                publisher = it.publisher,
                                language = it.language,
                                coverBitmap = it.coverBitmap,
                                series = it.series,
                                seriesIndex = it.seriesIndex,
                                tags = it.tags
                            )
                        }
                        "PDF" -> PdfParser.parse(
                            context, book.uriString, book.fileName, book.fileSize, book.lastModified
                        )
                        "CBR" -> CbrParser.parse(
                            context, uri, book.fileName, book.fileSize, book.lastModified
                        )
                        else -> EpubParser.parseEpub(context, uri, book.fileName)
                    }
                    checkpoint()
                    val cover = parsed.coverBitmap?.let { saveWebpThumbnail(context, book.uriString, it) }
                    checkpoint()
                    // A late extraction must not overwrite an edit the user made meanwhile.
                    val changed = withContext(NonCancellable) {
                        dao.updatePendingMetadata(
                            book.id, parsed.title, parsed.author, parsed.publishingDate,
                            parsed.publisher, parsed.language, cover, parsed.series,
                            parsed.seriesIndex, parsed.tags
                        )
                    }
                    val left = dao.countPendingMetadata()
                    _status.update {
                        it.copy(
                            metadataProcessed = it.metadataProcessed + changed,
                            metadataLeft = left,
                            phaseLabel = if (it.isPaused) it.phaseLabel else "Reading book details and covers… ${it.metadataProcessed + changed}"
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: SecurityException) {
                    // A revoked folder grant is not a bad book. Keep it pending so a
                    // later resume can read it after the folder is restored.
                    throw e
                } catch (e: Exception) {
                    // A single unreadable book must not block the whole library forever.
                    // Keep it with basic details instead of retrying it on every resume.
                    withContext(NonCancellable) { dao.markMetadataFailed(book.id) }
                    failed++
                }
            }
        }
        val remaining = dao.countPendingMetadata()
        _status.update { it.copy(metadataLeft = remaining) }
        if (failed > 0) {
            val noun = if (failed == 1) "book" else "books"
            val pronoun = if (failed == 1) "it was" else "they were"
            _status.update {
                it.copy(warning = "$failed $noun could not be read; $pronoun added with basic details.")
            }
        }
    }

    fun saveWebpThumbnail(context: Context, uri: String, bmp: Bitmap?): String? {
        if (bmp == null) return null
        val file = File(File(context.filesDir, "covers").apply { mkdirs() }, "${sha256(uri)}.webp")
        writeWebpThumbnail(file, bmp)
        return file.absolutePath
    }

    /** Full-size cover for exported copies, so a replaced cover is not the 280px grid thumbnail. */
    fun saveExportCover(context: Context, bmp: Bitmap): String? = runCatching {
        val longest = maxOf(bmp.width, bmp.height)
        val s = if (longest > 1600) 1600f / longest else 1f
        val scaled = if (s < 1f) {
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * s).toInt().coerceAtLeast(1),
                (bmp.height * s).toInt().coerceAtLeast(1),
                true
            )
        } else bmp
        val dir = File(context.filesDir, "covers_export").apply { mkdirs() }
        val file = File(dir, "${sha256(System.nanoTime().toString() + bmp.hashCode())}.jpg")
        file.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.JPEG, 95, it)) { "Could not save cover" } }
        file.absolutePath
    }.getOrNull()

    private fun writeWebpThumbnail(file: File, bmp: Bitmap) {
        val s = if (bmp.height > 420f) 420f / bmp.height else 1f
        val t = if (s < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true)
        } else bmp
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        file.outputStream().use { check(t.compress(fmt, 80, it)) { "Could not save cover" } }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}
