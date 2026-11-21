package com.example.myebookapp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.roundToInt

data class ScanStatus(val isScanning: Boolean = false, val isPaused: Boolean = false, val phaseLabel: String = "", val filesFoundInPass: Int = 0, val metadataProcessed: Int = 0, val metadataLeft: Int = 0, val queuedFolders: Int = 0, val error: String? = null)

object LibraryScanner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueLock = Any(); private val pendingFolders = ArrayDeque<Uri>()
    private val pausedFlow = MutableStateFlow(false); var activeJob: Job? = null
    private val _status = MutableStateFlow(ScanStatus()); val status: StateFlow<ScanStatus> = _status.asStateFlow()

    fun getSavedFolders(context: Context): Set<String> = context.getSharedPreferences("my_ebook_folders", Context.MODE_PRIVATE).getStringSet("folder_uris", emptySet()) ?: emptySet()

    fun addFolder(context: Context, treeUri: Uri) {
        val updated = getSavedFolders(context).toMutableSet().apply { add(treeUri.toString()) }
        context.getSharedPreferences("my_ebook_folders", Context.MODE_PRIVATE).edit().putStringSet("folder_uris", updated).apply()
    }

    fun scanFolders(context: Context, folderUris: List<Uri>) {
        synchronized(queueLock) { for (uri in folderUris) if (!pendingFolders.contains(uri)) pendingFolders.addLast(uri) ; _status.update { it.copy(queuedFolders = pendingFolders.size) } }
        if (activeJob?.isActive != true) startWorkerLoop(context.applicationContext)
    }

    fun resumeMetadataOnly(context: Context) { if (activeJob?.isActive != true) startWorkerLoop(context.applicationContext) }
    fun pauseScan() { pausedFlow.value = true; _status.update { it.copy(isPaused = true, phaseLabel = "Scan paused") } }
    fun resumeScan() { pausedFlow.value = false; _status.update { it.copy(isPaused = false, phaseLabel = "Resuming scan...") } }
    fun stopScan() { pausedFlow.value = false; synchronized(queueLock) { pendingFolders.clear() }; activeJob?.cancel(); activeJob = null; _status.update { it.copy(isScanning = false, isPaused = false, phaseLabel = "", queuedFolders = 0) } }

    private fun startWorkerLoop(appContext: Context) {
        activeJob = scope.launch {
            _status.value = ScanStatus(isScanning = true, phaseLabel = "Preparing...", queuedFolders = synchronized(queueLock) { pendingFolders.size })
            try {
                val dao = AppDatabase.getInstance(appContext).bookDao()
                while (isActive) {
                    if (pausedFlow.value) pausedFlow.first { !it }
                    val walked = drainFolders(appContext, dao)
                    if (pausedFlow.value) pausedFlow.first { !it }
                    val processed = processMetadataBatch(appContext, dao)
                    if (!walked && !processed && synchronized(queueLock) { pendingFolders.isEmpty() }) break
                }
                _status.update { it.copy(isScanning = false, phaseLabel = "") }
            } catch (e: Exception) { _status.update { it.copy(isScanning = false, error = e.localizedMessage) } }
        }
    }

    private suspend fun drainFolders(context: Context, dao: BookDao): Boolean {
        var walked = false; var keys: MutableSet<String>? = null
        while (true) {
            val next = synchronized(queueLock) { if (pendingFolders.isNotEmpty()) pendingFolders.removeFirst() else null } ?: break
            if (keys == null) keys = dao.getAllNameSizeKeys().toMutableSet()
            walked = true; walkTree(context, dao, next, keys!!)
        }
        if (walked) _status.update { it.copy(metadataLeft = dao.countPendingEpubs()) }
        return walked
    }

    private suspend fun walkTree(context: Context, dao: BookDao, treeUri: Uri, keys: MutableSet<String>) {
        val queue = ArrayDeque<String>().apply { addLast(DocumentsContract.getTreeDocumentId(treeUri)) }
        val proj = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
        val batch = ArrayList<BookEntity>(200)
        while (queue.isNotEmpty()) {
            if (!scope.coroutineContext.isActive) break
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, queue.removeFirst())
            runCatching { context.contentResolver.query(childrenUri, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { if (!name.startsWith(".")) queue.addLast(c.getString(0)) ; continue }
                    val format = when (name.substringAfterLast('.', "").lowercase()) { "epub"->"EPUB"; "mobi"->"MOBI"; "azw3"->"AZW3"; "azw"->"AZW3"; "prc"->"AZW3"; else->null } ?: continue
                    val size = c.getLong(3); if (size > 0L && !keys.add("${name.lowercase()}|$size")) continue
                    batch.add(BookEntity(uriString = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0)).toString(), fileName = name, format = format, fileSize = size, lastModified = c.getLong(4), title = name.substringBeforeLast('.'), author = "Unknown", publishingDate = "Unknown", publisher = "Unknown", language = "Unknown", coverPath = null, lastLocatorJson = null, progressPercent = 0.0, lastOpenedAt = 0L, metadataExtracted = (format != "EPUB")))
                    if (batch.size >= 200) { dao.insertIgnoreBatch(batch); _status.update { it.copy(filesFoundInPass = it.filesFoundInPass + batch.size) } ; batch.clear() }
                }
            }}
        }
        if (batch.isNotEmpty()) { dao.insertIgnoreBatch(batch); _status.update { it.copy(filesFoundInPass = it.filesFoundInPass + batch.size) } }
    }

    private suspend fun processMetadataBatch(context: Context, dao: BookDao): Boolean {
        val pending = dao.getPendingEpubs(60); if (pending.isEmpty()) return false
        val updates = Collections.synchronizedList(mutableListOf<ExtractedBookMeta>())
        coroutineScope {
            val ch = Channel<BookEntity>(Channel.UNLIMITED); pending.forEach { ch.trySend(it) }; ch.close()
            repeat(4) { launch { for (book in ch) {
                val p = runCatching { EpubParser.parseEpub(context, Uri.parse(book.uriString), book.fileName) }.getOrNull()
                val cover = p?.let { saveWebpThumbnail(context, book.uriString, it.coverBitmap) }
                updates.add(ExtractedBookMeta(book.id, p?.title ?: book.title, p?.author ?: book.author, p?.publishingDate ?: "Unknown", p?.publisher ?: "Unknown", p?.language ?: "Unknown", cover, p?.series, p?.seriesIndex, p?.tags))
                _status.update { it.copy(metadataProcessed = it.metadataProcessed + 1, metadataLeft = (it.metadataLeft - 1).coerceAtLeast(0)) }
            }}}
        }
        dao.updateExtractedMetadataBatch(updates.toList()); return true
    }

    fun saveWebpThumbnail(context: Context, uri: String, bmp: Bitmap?): String? {
        if (bmp == null) return null
        val file = File(File(context.filesDir, "covers").apply { mkdirs() }, "${sha256(uri)}.webp")
        val s = if (bmp.height > 280f) 280f / bmp.height else 1f
        val t = if (s < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true) else bmp
        val fmt = if (Build.VERSION.SDK_INT >= 29) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        file.outputStream().use { t.compress(fmt, 75, it) }; return file.absolutePath
    }

    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}
