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

data class ScanStatus(
    val isScanning: Boolean = false,
    val isPaused: Boolean = false,
    val phaseLabel: String = "",
    val filesFoundInPass: Int = 0,
    val metadataProcessed: Int = 0,
    val metadataLeft: Int = 0,
    val queuedFolders: Int = 0,
    val error: String? = null
)

object LibraryScanner {

    private const val PREFS_FOLDERS = "my_ebook_folders"
    private const val KEY_FOLDERS = "folder_uris"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueLock = Any()
    private val pendingFolders = ArrayDeque<Uri>()
    private val pausedFlow = MutableStateFlow(false)
    private var activeJob: Job? = null

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    fun getSavedFolders(context: Context): Set<String> =
        context.getSharedPreferences(PREFS_FOLDERS, Context.MODE_PRIVATE)
            .getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()

    fun addFolder(context: Context, treeUri: Uri) {
        val updated = getSavedFolders(context).toMutableSet().apply { add(treeUri.toString()) }
        context.getSharedPreferences(PREFS_FOLDERS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_FOLDERS, updated).apply()
    }

    fun scanFolders(context: Context, folderUris: List<Uri>) {
        if (folderUris.isEmpty()) return
        synchronized(queueLock) {
            for (uri in folderUris) if (!pendingFolders.contains(uri)) pendingFolders.addLast(uri)
            _status.update { it.copy(queuedFolders = pendingFolders.size) }
        }
        if (activeJob?.isActive == true) { resumeScan(); return }
        startWorkerLoop(context.applicationContext)
    }

    fun resumeMetadataOnly(context: Context) {
        if (activeJob?.isActive == true) { resumeScan(); return }
        startWorkerLoop(context.applicationContext)
    }

    fun pauseScan() {
        if (activeJob?.isActive != true) return
        pausedFlow.value = true
        _status.update { it.copy(isPaused = true, phaseLabel = "Scan paused") }
    }

    fun resumeScan() {
        if (activeJob?.isActive != true) return
        pausedFlow.value = false
        _status.update { it.copy(isPaused = false, phaseLabel = "Resuming scan...") }
    }

    fun stopScan() {
        pausedFlow.value = false
        synchronized(queueLock) { pendingFolders.clear() }
        activeJob?.cancel()
        activeJob = null
        _status.update { it.copy(isScanning = false, isPaused = false, phaseLabel = "", queuedFolders = 0) }
    }

    private suspend fun waitIfPaused() {
        if (pausedFlow.value) pausedFlow.first { !it }
    }

    private fun startWorkerLoop(appContext: Context) {
        pausedFlow.value = false
        activeJob = scope.launch {
            _status.value = ScanStatus(
                isScanning = true,
                phaseLabel = "Preparing scan...",
                queuedFolders = synchronized(queueLock) { pendingFolders.size }
            )
            try {
                val dao = AppDatabase.getInstance(appContext).bookDao()
                while (isActive) {
                    waitIfPaused()
                    val walked = drainPendingFolders(appContext, dao)
                    waitIfPaused()
                    val processed = if (isActive) processOneMetadataBatch(appContext, dao) else false
                    val more = synchronized(queueLock) { pendingFolders.isNotEmpty() }
                    if (!walked && !processed && !more) break
                }
                _status.update { it.copy(isScanning = false, isPaused = false, phaseLabel = "", queuedFolders = 0) }
            } catch (e: Exception) {
                _status.update { it.copy(isScanning = false, isPaused = false, phaseLabel = "", queuedFolders = 0, error = e.localizedMessage) }
            }
        }
    }

    private suspend fun drainPendingFolders(context: Context, dao: BookDao): Boolean {
        var walkedAny = false
        var existingKeys: MutableSet<String>? = null
        while (true) {
            waitIfPaused()
            val next = synchronized(queueLock) { if (pendingFolders.isNotEmpty()) pendingFolders.removeFirst() else null } ?: break
            if (existingKeys == null) existingKeys = dao.getAllNameSizeKeys().toMutableSet()
            walkedAny = true
            val rem = synchronized(queueLock) { pendingFolders.size }
            _status.update { it.copy(phaseLabel = if (rem > 0) "Scanning folder (+$rem queued)..." else "Scanning folder...", queuedFolders = rem) }
            walkTreeAndInsert(context, dao, next, existingKeys)
        }
        if (walkedAny) _status.update { it.copy(metadataLeft = dao.countPendingEpubs()) }
        return walkedAny
    }

    private suspend fun walkTreeAndInsert(context: Context, dao: BookDao, treeUri: Uri, existingKeys: MutableSet<String>) {
        val resolver = context.contentResolver
        val queue = ArrayDeque<String>().apply { addLast(DocumentsContract.getTreeDocumentId(treeUri)) }
        val proj = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val batch = ArrayList<BookEntity>(200)

        while (queue.isNotEmpty()) {
            if (!scope.coroutineContext.isActive) break
            waitIfPaused()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, queue.removeFirst())
            runCatching {
                resolver.query(childrenUri, proj, null, null, null)?.use { c ->
                    val idI = c.getColumnIndex(proj[0]); val nameI = c.getColumnIndex(proj[1])
                    val mimeI = c.getColumnIndex(proj[2]); val sizeI = c.getColumnIndex(proj[3]); val modI = c.getColumnIndex(proj[4])
                    while (c.moveToNext()) {
                        if (idI < 0 || nameI < 0) continue
                        val docId = c.getString(idI) ?: continue
                        val name = c.getString(nameI) ?: continue
                        if (mimeI >= 0 && c.getString(mimeI) == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (!name.startsWith(".")) queue.addLast(docId)
                            continue
                        }
                        val format = when (name.substringAfterLast('.', "").trim().lowercase()) {
                            "epub" -> "EPUB"; "mobi" -> "MOBI"; "azw3", "azw", "prc" -> "AZW3"; else -> null
                        } ?: continue
                        val size = if (sizeI >= 0) c.getLong(sizeI) else 0L
                        val mod = if (modI >= 0) c.getLong(modI) else 0L
                        if (size > 0L && !existingKeys.add("${name.lowercase()}|$size")) continue
                        val (author, title) = guessFromFileName(name)
                        batch.add(
                            BookEntity(
                                uriString = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId).toString(),
                                fileName = name, format = format, fileSize = size, lastModified = mod,
                                title = title, author = author, publishingDate = "Unknown",
                                publisher = "Unknown", language = "Unknown", coverPath = null,
                                lastLocatorJson = null, progressPercent = 0.0, lastOpenedAt = 0L,
                                metadataExtracted = (format != "EPUB")
                            )
                        )
                        if (batch.size >= 200) {
                            dao.insertIgnoreBatch(batch)
                            val n = batch.size; batch.clear()
                            _status.update { it.copy(filesFoundInPass = it.filesFoundInPass + n) }
                        }
                    }
                }
            }
        }
        if (batch.isNotEmpty()) {
            dao.insertIgnoreBatch(batch)
            val n = batch.size; batch.clear()
            _status.update { it.copy(filesFoundInPass = it.filesFoundInPass + n) }
        }
    }

    private suspend fun processOneMetadataBatch(context: Context, dao: BookDao): Boolean {
        waitIfPaused()
        val pending = dao.getPendingEpubs(limit = 60)
        if (pending.isEmpty()) return false
        val left = dao.countPendingEpubs()
        val q = synchronized(queueLock) { pendingFolders.size }
        _status.update { it.copy(phaseLabel = if (q > 0) "Extracting covers (+$q queued)..." else "Extracting covers & metadata...", metadataLeft = left, queuedFolders = q) }
        val updates = Collections.synchronizedList(mutableListOf<ExtractedBookMeta>())
        coroutineScope {
            val ch = Channel<BookEntity>(Channel.UNLIMITED)
            pending.forEach { ch.trySend(it) }; ch.close()
            repeat(4) {
                launch(Dispatchers.IO) {
                    for (book in ch) {
                        if (!isActive) break
                        waitIfPaused()
                        val parsed = runCatching { EpubParser.parseEpub(context, Uri.parse(book.uriString), book.fileName) }.getOrNull()
                        if (parsed != null) {
                            val cover = saveWebpThumbnail(context, book.uriString, parsed.coverBitmap)
                            updates.add(ExtractedBookMeta(book.id, parsed.title, parsed.author, parsed.publishingDate, parsed.publisher, parsed.language, cover))
                        } else {
                            updates.add(ExtractedBookMeta(book.id, book.title, book.author, book.publishingDate, book.publisher, book.language, null))
                        }
                        _status.update { it.copy(metadataProcessed = it.metadataProcessed + 1, metadataLeft = (it.metadataLeft - 1).coerceAtLeast(0)) }
                    }
                }
            }
        }
        if (updates.isNotEmpty()) dao.updateExtractedMetadataBatch(updates.toList())
        return true
    }

    fun saveWebpThumbnail(context: Context, uriString: String, bitmap: Bitmap?): String? {
        if (bitmap == null) return null
        return runCatching {
            val dir = File(context.filesDir, "covers").apply { mkdirs() }
            val file = File(dir, "${sha256(uriString)}.webp")
            val scale = if (bitmap.height > 280f) 280f / bitmap.height else 1f
            val target = if (scale < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1), (bitmap.height * scale).roundToInt().coerceAtLeast(1), true) else bitmap
            val fmt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
            file.outputStream().use { target.compress(fmt, 75, it) }
            file.absolutePath
        }.getOrNull()
    }

    fun guessFromFileName(fileName: String): Pair<String, String> {
        val base = fileName.substringBeforeLast('.').trim()
        val parts = base.split(" - ", limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) parts[0].trim() to parts[1].trim()
        else "Unknown" to base.ifBlank { "Unknown" }
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }.take(24)
}
