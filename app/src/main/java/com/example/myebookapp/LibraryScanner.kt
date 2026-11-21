package com.example.myebookapp
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
                if (!pendingFolders.contains(uri)) pendingFolders.addLast(uri)
            }
            _status.update { it.copy(queuedFolders = pendingFolders.size) }
        }
        if (activeJob?.isActive == true) {
            resumeScan()
            return
        }
        startWorkerLoop(appContext)
    }

    fun resumeMetadataOnly(context: Context) {
        if (activeJob?.isActive == true) {
            resumeScan()
            return
        }
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
        if (pausedFlow.value) {
            pausedFlow.first { !it }
        }
    }

    private fun startWorkerLoop(appContext: Context) {
        pausedFlow.value = false
        activeJob = scope.launch {
            _status.value = ScanStatus(
                isScanning = true,
                isPaused = false,
                phaseLabel = "Preparing scan...",
                queuedFolders = synchronized(queueLock) { pendingFolders.size }
            )

            try {
                val dao = AppDatabase.getInstance(appContext).bookDao()
                while (isActive) {
                    waitIfPaused()
                    val walkedAny = drainPendingFolders(appContext, dao)
                    waitIfPaused()
                    val processedAny = if (isActive) processOneMetadataBatch(appContext, dao) else false
                    val moreFolders = synchronized(queueLock) { pendingFolders.isNotEmpty() }
                    if (!walkedAny && !processedAny && !moreFolders) break
                }
                _status.update { it.copy(isScanning = false, isPaused = false, phaseLabel = "", queuedFolders = 0) }
            } catch (e: Exception) {
                _status.update {
                    it.copy(
                        isScanning = false,
                        isPaused = false,
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
        var existingNameSizeKeys: MutableSet<String>? = null

        while (true) {
            waitIfPaused()
            val nextFolder = synchronized(queueLock) {
                if (pendingFolders.isNotEmpty()) pendingFolders.removeFirst() else null
            } ?: break

            if (existingNameSizeKeys == null) {
                existingNameSizeKeys = dao.getAllNameSizeKeys().toMutableSet()
            }

            walkedAny = true
            val remaining = synchronized(queueLock) { pendingFolders.size }
            _status.update {
                it.copy(
                    phaseLabel = if (remaining > 0) "Scanning folder (+$remaining queued)..." else "Scanning folder...",
                    queuedFolders = remaining
                )
            }
            walkTreeAndInsert(context, dao, nextFolder, existingNameSizeKeys)
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
        treeUri: Uri,
        existingNameSizeKeys: MutableSet<String>
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
            waitIfPaused()
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
                            if (!name.startsWith(".")) queue.addLast(docId)
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

                        if (size > 0L) {
                            val key = "${name.lowercase()}|$size"
                            if (!existingNameSizeKeys.add(key)) continue
                        }

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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
                if (!pendingFolders.contains(uri)) pendingFolders.addLast(uri)
            }
            _status.update { it.copy(queuedFolders = pendingFolders.size) }
        }
        if (activeJob?.isActive == true) {
            resumeScan()
            return
        }
        startWorkerLoop(appContext)
    }

    fun resumeMetadataOnly(context: Context) {
        if (activeJob?.isActive == true) {
            resumeScan()
            return
        }
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
        if (pausedFlow.value) {
            pausedFlow.first { !it }
        }
    }

    private fun startWorkerLoop(appContext: Context) {
        pausedFlow.value = false
        activeJob = scope.launch {
            _status.value = ScanStatus(
                isScanning = true,
                isPaused = false,
                phaseLabel = "Preparing scan...",
                queuedFolders = synchronized(queueLock) { pendingFolders.size }
            )

            try {
                val dao = AppDatabase.getInstance(appContext).bookDao()
                while (isActive) {
                    waitIfPaused()
                    val walkedAny = drainPendingFolders(appContext, dao)
                    waitIfPaused()
                    val processedAny = if (isActive) processOneMetadataBatch(appContext, dao) else false
                    val moreFolders = synchronized(queueLock) { pendingFolders.isNotEmpty() }
                    if (!walkedAny && !processedAny && !moreFolders) break
                }
                _status.update { it.copy(isScanning = false, isPaused = false, phaseLabel = "", queuedFolders = 0) }
            } catch (e: Exception) {
                _status.update {
                    it.copy(
                        isScanning = false,
                        isPaused = false,
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
        var existingNameSizeKeys: MutableSet<String>? = null

        while (true) {
            waitIfPaused()
            val nextFolder = synchronized(queueLock) {
                if (pendingFolders.isNotEmpty()) pendingFolders.removeFirst() else null
            } ?: break

            if (existingNameSizeKeys == null) {
                existingNameSizeKeys = dao.getAllNameSizeKeys().toMutableSet()
            }

            walkedAny = true
            val remaining = synchronized(queueLock) { pendingFolders.size }
            _status.update {
                it.copy(
                    phaseLabel = if (remaining > 0) "Scanning folder (+$remaining queued)..." else "Scanning folder...",
                    queuedFolders = remaining
                )
            }
            walkTreeAndInsert(context, dao, nextFolder, existingNameSizeKeys)
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
        treeUri: Uri,
        existingNameSizeKeys: MutableSet<String>
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
            waitIfPaused()
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
                            if (!name.startsWith(".")) queue.addLast(docId)
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

                        if (size > 0L) {
                            val key = "${name.lowercase()}|$size"
                            if (!existingNameSizeKeys.add(key)) continue
                        }

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
                        )hingDate = "Unknown",
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

    private suspend fun processOneMetadataBatch(context: Contex
