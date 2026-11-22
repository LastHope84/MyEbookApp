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

data class ScanStatus(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val canResume: Boolean = false,
    val phaseLabel: String = "",
    val filesFoundInPass: Int = 0,
    val metadataProcessed: Int = 0,
    val metadataLeft: Int = 0,
    val queuedFolders: Int = 0,
    val error: String? = null
)

/** The journal records whole roots, not in-memory child queues. Replaying a root is safe:
 * inserts are IGNORE, and it is removed from the journal only after its entire walk commits. */
object LibraryScanner {
    private const val PREF = "scan_session_v1"
    private const val RUNNING = "running"
    private const val PAUSED = "paused"
    private const val INTERRUPTED = "interrupted"
    private const val STOPPED = "stopped"
    private val lock = Any()
    // A cancelled generation may still be in blocking provider I/O or cleanup.
    // Serialize the entire processing loop, including its suspending cleanup.
    private val processingMutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val paused = MutableStateFlow(false)
    private var activeJob: Job? = null
    private var generation = 0L
    // Admission gate spans the cancellation, provider reads, and Room commit.
    private var folderRemovalInProgress = false
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

    fun getSavedFolders(context: Context): Set<String> =
        context.getSharedPreferences("my_ebook_folders", Context.MODE_PRIVATE).getStringSet("folder_uris", emptySet())?.toSet() ?: emptySet()
    fun addFolder(context: Context, treeUri: Uri) = synchronized(lock) {
        check(!folderRemovalInProgress) { "Folder removal is in progress" }
        val updated = getSavedFolders(context).toMutableSet().apply { add(treeUri.toString()) }
        check(context.getSharedPreferences("my_ebook_folders", Context.MODE_PRIVATE).edit().putStringSet("folder_uris", updated).commit())
    }

    /** Called only from visible UI; never starts work automatically on process launch. */
    fun restore(context: Context) = synchronized(lock) {
        if (folderRemovalInProgress) return@synchronized
        if (activeJob?.isActive == true) return@synchronized
        val current = mode(context)
        if (current == RUNNING) save(context, INTERRUPTED, roots(context))
        val savedMode = mode(context)
        if (savedMode == PAUSED || savedMode == INTERRUPTED) {
            paused.value = savedMode == PAUSED
            _status.value = ScanStatus(isPaused = savedMode == PAUSED, canResume = true,
                queuedFolders = roots(context).size,
                phaseLabel = if (savedMode == PAUSED) "Scan paused" else "Scan interrupted — resume to continue")
        }
    }

    fun scanFolders(context: Context, folderUris: List<Uri>) {
        val app = context.applicationContext
        try {
            val shouldStart = synchronized(lock) {
                if (folderRemovalInProgress) return@synchronized false
                val queue = roots(app)
                folderUris.map { it.toString() }.forEach { if (it !in queue) queue.add(it) }
                val old = mode(app)
                // Adding folders must not silently undo an explicit pause.
                val next = if (old == PAUSED) PAUSED else RUNNING
                save(app, next, queue)
                _status.update { it.copy(isScanning = next == RUNNING || it.isScanning, queuedFolders = queue.size, canResume = next == PAUSED, isPaused = next == PAUSED, phaseLabel = if (next == PAUSED) "Scan paused" else "Starting scan…", error = null) }
                next == RUNNING
            }
            if (shouldStart) startForeground(app)
        } catch (e: Exception) { reportStartFailure(app, e) }
    }

    fun resumeMetadataOnly(context: Context) = scanFolders(context, emptyList())

    fun pauseScan(context: Context) {
        val app = context.applicationContext
        try { synchronized(lock) {
            if (folderRemovalInProgress) return
            if (mode(app) != RUNNING) return
            save(app, PAUSED, roots(app)); paused.value = true
            _status.update { it.copy(isPaused = true, canResume = true, phaseLabel = "Scan paused") }
        } } catch (e: Exception) { reportStartFailure(app, e) }
    }

    fun resumeScan(context: Context) {
        val app = context.applicationContext
        try {
            synchronized(lock) {
                if (folderRemovalInProgress) return
                if (mode(app) != PAUSED && mode(app) != INTERRUPTED) return
                save(app, RUNNING, roots(app)); paused.value = false
                _status.update { it.copy(isScanning = true, isPaused = false, canResume = false, phaseLabel = "Resuming…", error = null) }
            }
            startForeground(app)
        } catch (e: Exception) { reportStartFailure(app, e) }
    }

    fun stopScan(context: Context) {
        val app = context.applicationContext
        try { synchronized(lock) {
            if (folderRemovalInProgress) return
            save(app, STOPPED, emptyList()); generation++; paused.value = false
            activeJob?.cancel(); _status.value = ScanStatus(phaseLabel = "Scan stopped")
        } } catch (e: Exception) { reportStartFailure(app, e) }
    }

    private fun startForeground(context: Context) {
        ContextCompat.startForegroundService(context, Intent(context, ScanService::class.java))
    }

    private fun reportStartFailure(context: Context, e: Exception) {
        synchronized(lock) {
            try { if (mode(context) == RUNNING) save(context, INTERRUPTED, roots(context)) } catch (_: Exception) { }
            paused.value = false
            activeJob?.cancel()
            _status.update { it.copy(isScanning = false, canResume = true, phaseLabel = "Scan interrupted", error = e.localizedMessage ?: "Could not start scan") }
        }
    }

    /** Service is the sole worker owner. Repeated starts never create overlapping workers. */
    fun startWorker(context: Context, finished: () -> Unit): Boolean = synchronized(lock) {
        // A paused existing worker still owns a completion callback; an absent one does not.
        if (folderRemovalInProgress || mode(context) != RUNNING || activeJob?.isActive == true) return@synchronized activeJob?.isActive == true
        val token = ++generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                processingMutex.withLock {
                    synchronized(lock) {
                        // Cancellation or a newer generation may have won while we waited.
                        ensureActive()
                        if (generation != token) throw CancellationException("Scan session changed")
                        val savedMode = mode(context)
                        if (savedMode != RUNNING && savedMode != PAUSED) throw CancellationException("Scan no longer active")
                        val isPaused = savedMode == PAUSED
                        _status.update { it.copy(isScanning = true, isPaused = isPaused, canResume = isPaused,
                            phaseLabel = if (isPaused) "Scan paused" else "Preparing…", error = null) }
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
                        if (complete) break
                        checkpoint()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                synchronized(lock) {
                    if (generation == token) {
                        try { if (mode(context) == RUNNING) save(context, INTERRUPTED, roots(context)) } catch (_: Exception) { }
                        _status.update { it.copy(isScanning = false, canResume = true, phaseLabel = "Scan interrupted", error = e.localizedMessage ?: "Scan failed") }
                    }
                }
            }
        }
        activeJob = job
        // invokeOnCompletion also runs if cancellation happens before the body starts.
        job.invokeOnCompletion {
            synchronized(lock) { if (generation == token) activeJob = null }
            finished()
        }
        job.start()
        true
    }

    /** Timeout or unexpected service destruction leaves the current root in the journal. */
    fun interrupt(context: Context, reason: String) = synchronized(lock) {
        if (folderRemovalInProgress) return@synchronized
        try {
            if (mode(context) == RUNNING) save(context, INTERRUPTED, roots(context))
            if (mode(context) == RUNNING || mode(context) == INTERRUPTED) {
                generation++; activeJob?.cancel(); paused.value = false
                _status.update { it.copy(isScanning = false, canResume = true, phaseLabel = "Scan interrupted", error = reason) }
            } else if (mode(context) == PAUSED) {
                generation++; activeJob?.cancel()
                _status.update { it.copy(isScanning = false, isPaused = true, canResume = true, phaseLabel = "Scan paused") }
            }
        } catch (e: Exception) {
            generation++; activeJob?.cancel()
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
                _status.update { if (it.isPaused) it else it.copy(phaseLabel = "Scanning folders…") }
                walkTree(context, dao, Uri.parse(next), dao.getAllNameSizeKeys().toMutableSet())
                checkpoint()
                synchronized(lock) {
                    if (generation != token) throw CancellationException("Scan session changed")
                    val queue = roots(context)
                    queue.remove(next)
                    save(context, mode(context), queue)
                    _status.update { it.copy(queuedFolders = queue.size) }
                }
                continue
            }
            processMetadata(context, dao)
            checkpoint()
            // An added root must not be lost between the last query and completion.
            if (synchronized(lock) { roots(context).isEmpty() }) return
        }
    }

    /** Interrupt without clearing the journal. While the gate is held no new worker/root can
     * start. The processing mutex also waits for cancelled provider I/O and non-cancellable
     * metadata cleanup, not merely Job.isActive becoming false. */
    suspend fun removeSubfolder(context: Context, treeUri: Uri, directoryId: String): FolderRemovalResult {
        val app = context.applicationContext
        synchronized(lock) {
            check(!folderRemovalInProgress) { "Folder removal already in progress" }
            // Save first: if persistence fails, leave the running job and admission state alone.
            val old = mode(app)
            if (old == RUNNING) save(app, INTERRUPTED, roots(app))
            folderRemovalInProgress = true
            generation++
            activeJob?.cancel()
            paused.value = false
            if (old == RUNNING || old == PAUSED || old == INTERRUPTED) {
                _status.update { it.copy(isScanning = false, isPaused = old == PAUSED,
                    canResume = true, phaseLabel = if (old == PAUSED) "Scan paused" else "Scan interrupted — resume to continue") }
            }
        }
        try {
            return processingMutex.withLock {
                val rootUris = synchronized(lock) { (getSavedFolders(app) + roots(app)).toList().sorted() }
                FolderRemovalCore.remove(app, treeUri, directoryId, rootUris)
            }
        } finally {
            synchronized(lock) { folderRemovalInProgress = false }
        }
    }

    /** Provisional exclusion only: does NOT remove library rows or the saved root. */
    suspend fun excludeFolderFromScans(context: Context, treeUri: Uri, directoryId: String): FolderRemovalResult {
        val app = context.applicationContext
        val rootUris = synchronized(lock) { (getSavedFolders(app) + roots(app)).toList().sorted() }
        return FolderRemovalCore.exclude(app, treeUri, directoryId, rootUris)
    }

    private suspend fun walkTree(context: Context, dao: BookDao, treeUri: Uri, keys: MutableSet<String>) {
        val authority = requireNotNull(treeUri.authority)
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        // Paths are actual observed SAF edges, never derived from opaque document IDs.
        // Each directory is visited at most once per walk; an alternate route may need a rescan.
        val queue = ArrayDeque<List<String>>().apply { add(listOf(rootId)) }
        val seen = mutableSetOf<String>()
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val batch = ArrayList<ScannedItem>(200)
        suspend fun flush() {
            if (batch.isEmpty()) return
            checkpoint()
            val result = dao.insertScannedBatch(batch, keys, authority)
            keys.addAll(result.acceptedKeys)
            batch.clear()
            _status.update { it.copy(filesFoundInPass = it.filesFoundInPass + result.inserted) }
        }
        while (queue.isNotEmpty()) {
            checkpoint()
            val path = queue.removeFirst()
            val blocked = dao.rootExclusions(treeUri.toString()).map { it.documentId }.toSet() +
                dao.getScanExclusions().filter { it.authority == authority }.map { it.documentId }
            if (path.any { it in blocked }) continue
            val parentId = path.last()
            if (!seen.add(parentId)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)
            val cursor = context.contentResolver.query(children, projection, null, null, null)
                ?: throw IllegalStateException("Folder provider returned no results; permission may have been lost")
            cursor.use { c ->
                fun requireCompleteResults() {
                    val extras = c.extras
                    check(!extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) && !extras.containsKey(DocumentsContract.EXTRA_ERROR)) {
                        "Folder provider returned incomplete results; resume to retry"
                    }
                }
                requireCompleteResults()
                while (c.moveToNext()) {
                    checkpoint()
                    val name = c.getString(1) ?: continue
                    val documentId = c.getString(0) ?: throw IllegalStateException("Folder provider returned a document without an ID; resume to retry")
                    val isDirectory = c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR
                    val sighting = ScanSighting(treeUri.toString(), parentId, documentId, isDirectory)
                    if (isDirectory) {
                        if (!name.startsWith(".") && documentId !in blocked && documentId !in seen && documentId !in path) queue.addLast(path + documentId)
                        batch.add(ScannedItem(sighting, null, path))
                    } else {
                        val format = when (name.substringAfterLast('.', "").lowercase()) { "epub" -> "EPUB"; "mobi" -> "MOBI"; "azw3", "azw", "prc" -> "AZW3"; else -> null } ?: continue
                        val size = c.getLong(3)
                        // Dedup is decided after transactional exclusion filtering; a rejected
                        // in-flight book must never poison the name/size set for another route.
                        val book = BookEntity(uriString = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId).toString(), fileName = name, format = format, fileSize = size, lastModified = c.getLong(4), title = name.substringBeforeLast('.'), author = "Unknown", publishingDate = "Unknown", publisher = "Unknown", language = "Unknown", coverPath = null, lastLocatorJson = null, progressPercent = 0.0, lastOpenedAt = 0L, metadataExtracted = format != "EPUB")
                        batch.add(ScannedItem(sighting, book, path))
                    }
                    if (batch.size >= 200) flush()
                }
                // Providers may update extras while enumerating.
                requireCompleteResults()
            }
        }
        flush()
    }

    private suspend fun processMetadata(context: Context, dao: BookDao) {
        var afterId = 0L
        var failures = 0
        val initialPending = dao.countPendingEpubs()
        _status.update { it.copy(metadataLeft = initialPending, phaseLabel = if (it.isPaused) it.phaseLabel else "Reading book details and covers… ${it.metadataProcessed}") }
        while (true) {
            checkpoint()
            val pending = dao.getPendingEpubsAfter(afterId, 60)
            if (pending.isEmpty()) break
            for (book in pending) {
                checkpoint()
                afterId = book.id
                var coverFile: File? = null
                try {
                    val parsed = EpubParser.parseEpub(context, Uri.parse(book.uriString), book.fileName)
                    checkpoint()
                    // Reserve a new file; never overwrite an existing cover.
                    val cover = parsed.coverBitmap?.let { bitmap ->
                        val dir = File(context.filesDir, "covers").apply { mkdirs() }
                        val file = File.createTempFile("scan-", ".webp", dir)
                        coverFile = file
                        writeWebpThumbnail(file, bitmap)
                        file.absolutePath
                    }
                    checkpoint()
                    // Do not abandon an in-flight database write: cleanup must never
                    // race a commit whose outcome was hidden by coroutine cancellation.
                    val changed = withContext(NonCancellable) {
                        dao.updatePendingMetadata(book.id, parsed.title, parsed.author, parsed.publishingDate, parsed.publisher, parsed.language, cover, parsed.series, parsed.seriesIndex, parsed.tags)
                    }
                    val left = dao.countPendingEpubs()
                    _status.update { it.copy(metadataProcessed = it.metadataProcessed + changed, metadataLeft = left, phaseLabel = if (it.isPaused) it.phaseLabel else "Reading book details and covers… ${it.metadataProcessed + changed}") }
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { failures++ }
                finally {
                    // A cancelled Room call may have committed. Delete only after a
                    // successful non-cancellable reference check of our unique file.
                    coverFile?.let { file -> withContext(NonCancellable) {
                        try { if (dao.countCoverReferences(file.absolutePath) == 0) file.delete() }
                        catch (_: Exception) { /* keep it if ownership is uncertain */ }
                    } }
                }
            }
        }
        val remaining = dao.countPendingEpubs()
        _status.update { it.copy(metadataLeft = remaining) }
        if (failures > 0) throw IllegalStateException("$failures book(s) could not be read; $remaining remain pending. Resume to retry.")
    }

    fun saveWebpThumbnail(context: Context, uri: String, bmp: Bitmap?): String? {
        if (bmp == null) return null
        val file = File(File(context.filesDir, "covers").apply { mkdirs() }, "${sha256(uri)}.webp")
        writeWebpThumbnail(file, bmp)
        return file.absolutePath
    }
    private fun writeWebpThumbnail(file: File, bmp: Bitmap) {
        val s = if (bmp.height > 280f) 280f / bmp.height else 1f
        val t = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true) else bmp
        val fmt = if (Build.VERSION.SDK_INT >= 29) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        file.outputStream().use { check(t.compress(fmt, 75, it)) { "Could not save cover" } }
    }
    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}
