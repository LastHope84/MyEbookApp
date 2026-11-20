package com.example.myebookapp

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import kotlin.math.roundToInt

data class ScanStatus(
    val isScanning: Boolean = false,
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
    private var activeJob: Job? = null

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    fun getSavedFolders(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS_FOLDERS, Context.MODE_PRIVATE)
            .getStringSet(KEY_FOLDERS, emptySet()) ?: emptySet()
    }

    fun addFolder(context: Context, treeUri: Uri) {
        val prefs = context.getSharedPreferences(PREFS_FOLDERS, Context.MODE_PRIVATE)
        val updated = getSavedFolders(context).toMutableSet()
        updated.add(treeUri.toString())
        prefs.edit().putStringSet(KEY_FOLDERS, updated).apply()
    }

    fun scanFolders(context: Context, folderUris: List<Uri>) {
        if (folderUris.isEmpty()) return
        val appContext = context.applicationContext
        synchronized(queueLock) {
            for (uri in folderUris) {
                if (!pendingFolders.contains(uri)) {
                    pendingFolders.addLast(uri)
                }
            }
            _status.update { it.copy(queuedFolders = pendingFolders.size) }
        }
        if (activeJob?.isActive == true) return
        startWorkerLoop(appContext)
    }

    fun resumeMetadataOnly(context: Context) {
        if (activeJob?.isActive == true) return
        startWorkerLoop(context.applicationContext)
    }

    fun stopScan() {
        synchronized(queueLock) {
            pendingFolders.clear()
        }
        activeJob?.cancel()
        activeJob = null
        _status.update { it.copy(isScanning = false, phaseLabel = "", queuedFolders = 0) }
    }

    private fun startWorkerLoop(appContext: Context) {
        activeJob = scope.launch {
            _status.value = ScanStatus(
                isScanning = true,
                phaseLabel = "Preparing scan...",
                queuedFolders = synchronized(queueLock) { pendingFolders.size }
            )

            try {
                val dao = AppDatabase.getInstance(appContext).bookDao()
                while (isActive) {
                    val walkedAny = drainPendingFolders(appContext, dao)
                    val processedAny = if (isActive) processOneMetadataBatch(appContext, dao) else false
                    val moreFolders = synchronized(queueLock) { pendingFolders.isNotEmpty() }
                    if (!walkedAny && !processedAny && !moreFolders) {
                        break
                    }
                }
                _status.update { it.copy(isScanning = false, phaseLabel = "", queuedFolders = 0) }
            } catch (e: Exception) {
                _status.update {
                    it.copy(
                        isScanning = false,
                        phaseLabel = "",
                        queuedFolders = 0,
                        error = e.localizedMessage ?: "Scan interrupted."
                    )
                }
            }
        }
    }

    private suspend fun drainPendingFolders(context: Context, dao: BookDao): Boolean {
        var walkedAny = false
        while (true) {
            val nextFolder = synchronized(queueLock) {
                if (pendingFolders.isNotEmpty()) pendingFolders.removeFirst() else null
            } ?: break

            walkedAny = true
            val remaining = synchronized(queueLock) { pendingFolders.size }
            _status.update {
                it.copy(
                    phaseLabel = if (remaining > 0) "Scanning folder (+$remaining queued)..." else "Scanning folder...",
                    queuedFolders = remaining
                )
            }
            walkTreeAndInsert(context, dao, nextFolder)
        }
        if (walkedAny) {
            val left = dao.countPendingEpubs()
            _status.update { it.copy(metadataLeft = left) }
        }
        return walkedAny
    }

    private suspend fun walkTreeAndInsert(
        context: Context,
        dao: BookDao,
        treeUri: Uri
    ) {
        val resolver = context.contentResolver
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val queue = ArrayDeque<String>()
        queue.addLast(rootDocId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val batch = ArrayList<BookEntity>(200)

        while (queue.isNotEmpty()) {
            if (!scope.coroutineContext.isActive) break
            val parentId = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId)

            runCatching {
                resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    while (cursor.moveToNext()) {
                        if (idIdx < 0 || nameIdx < 0) continue
                        val docId = cursor.getString(idIdx) ?: continue
                        val name = cursor.getString(nameIdx) ?: continue
                        val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null

                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (!name.startsWith(".")) {
                                queue.addLast(docId)
                            }
                            continue
                        }

                        val ext = name.substringAfterLast('.', "").trim().lowercase()
                        val format = when (ext) {
                            "epub" -> "EPUB"
                            "mobi" -> "MOBI"
                            "azw3", "azw", "prc" -> "AZW3"
                            else -> null
                        } ?: continue

                        val size = if (sizeIdx >= 0) cursor.getLong(sizeIdx) else 0L
                        val lastMod = if (modIdx >= 0) cursor.getLong(modIdx) else 0L
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val (guessedAuthor, guessedTitle) = guessFromFileName(name)

                        batch.add(
                            BookEntity(
                                uriString = fileUri.toString(),
                                fileName = name,
                                format = format,
                                fileSize = size,
                                lastModified = lastMod,
                                title = guessedTitle,
                                author = guessedAuthor,
                                publishingDate = "Unknown",
                                publisher = "Unknown",
                                language = "Unknown",
                                coverPath = null,
                                lastLocatorJson = null,
                                progressPercent = 0.0,
                                lastOpenedAt = 0L,
                                metadataExtracted = (format != "EPUB")
                            )
                        )

                        if (batch.size >= 200) {
                            dao.insertIgnoreBatch(batch)
                            val count = batch.size
                            _status.update { it.copy(filesFoundInPass = it.filesFoundInPass + count) }
                            batch.clear()
                        }
                    }
                }
            }
        }

        if (batch.isNotEmpty()) {
            dao.insertIgnoreBatch(batch)
            val count = batch.size
            _status.update { it.copy(filesFoundInPass = it.filesFoundInPass + count) }
            batch.clear()
        }
    }

    private suspend fun processOneMetadataBatch(context: Context, dao: BookDao): Boolean {
        val pending = dao.getPendingEpubs(limit = 60)
        if (pending.isEmpty()) return false

        val totalLeft = dao.countPendingEpubs()
        val queued = synchronized(queueLock) { pendingFolders.size }
        _status.update {
            it.copy(
                phaseLabel = if (queued > 0) "Extracting covers (+$queued folder queued)..." else "Extracting EPUB covers & metadata...",
                metadataLeft = totalLeft,
                queuedFolders = queued
            )
        }

        val updates = Collections.synchronizedList(mutableListOf<ExtractedBookMeta>())

        coroutineScope {
            val channel = Channel<BookEntity>(Channel.UNLIMITED)
            for (item in pending) channel.trySend(item)
            channel.close()

            repeat(4) {
                launch(Dispatchers.IO) {
                    for (book in channel) {
                        if (!isActive) break
                        val uri = Uri.parse(book.uriString)
                        val parsed = runCatching {
                            EpubParser.parseEpub(context, uri, book.fileName)
                        }.getOrNull()

                        if (parsed != null) {
                            val coverPath = saveWebpThumbnail(context, book.uriString, parsed.coverBitmap)
                            updates.add(
                                ExtractedBookMeta(
                                    id = book.id,
                                    title = parsed.title,
                                    author = parsed.author,
                                    publishingDate = parsed.publishingDate,
                                    publisher = parsed.publisher,
                                    language = parsed.language,
                                    coverPath = coverPath
                                )
                            )
                        } else {
                            updates.add(
                                ExtractedBookMeta(
                                    id = book.id,
                                    title = book.title,
                                    author = book.author,
                                    publishingDate = book.publishingDate,
                                    publisher = book.publisher,
                                    language = book.language,
                                    coverPath = null
                                )
                            )
                        }

                        _status.update {
                            it.copy(
                                metadataProcessed = it.metadataProcessed + 1,
                                metadataLeft = (it.metadataLeft - 1).coerceAtLeast(0)
                            )
                        }
                    }
                }
            }
        }

        if (updates.isNotEmpty()) {
            dao.updateExtractedMetadataBatch(updates.toList())
        }
        return true
    }

    fun saveWebpThumbnail(context: Context, uriString: String, bitmap: Bitmap?): String? {
        if (bitmap == null) return null
        return runCatching {
            val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
            val file = File(coversDir, "${sha256(uriString)}.webp")

            val maxHeight = 280f
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

            file.outputStream().use { out ->
                target.compress(format, 75, out)
            }
            file.absolutePath
        }.getOrNull()
    }

    fun guessFromFileName(fileName: String): Pair<String, String> {
        val base = fileName.substringBeforeLast('.').trim()
        val parts = base.split(" - ", limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            parts[0].trim() to parts[1].trim()
        } else {
            "Unknown" to base.ifBlank { "Unknown" }
        }
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }.take(24)
    }
}
