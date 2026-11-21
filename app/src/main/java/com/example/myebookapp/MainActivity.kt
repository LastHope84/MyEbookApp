package com.example.myebookapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

enum class LibraryTab(val title: String) {
    ALL("My Library"),
    RECENT("Recently Read"),
    TO_CONVERT("To Convert")
}

data class PendingConvertTarget(
    val uri: Uri,
    val fileName: String,
    val existingBookId: Long? = null
)

class MainActivity : ComponentActivity() {

    companion object {
        var didAutoOpenThisSession = false
    }

    private var incomingExternalUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        setContent {
            val context = LocalContext.current
            var themeMode by remember { mutableStateOf(ThemePrefs.getMode(context)) }
            var accent by remember { mutableStateOf(ThemePrefs.getAccent(context)) }
            var amoledDark by remember { mutableStateOf(ThemePrefs.isAmoled(context)) }

            MyEbookTheme(mode = themeMode, accent = accent, amoledDark = amoledDark) {
                LibraryScreen(
                    themeMode = themeMode,
                    accent = accent,
                    amoledDark = amoledDark,
                    incomingExternalUri = incomingExternalUri,
                    onConsumeExternalUri = { incomingExternalUri = null },
                    onThemeModeChange = { themeMode = it; ThemePrefs.saveMode(context, it) },
                    onAccentChange = { accent = it; ThemePrefs.saveAccent(context, it) },
                    onAmoledChange = { amoledDark = it; ThemePrefs.saveAmoled(context, it) }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW && intent.data != null) {
            incomingExternalUri = intent.data
            didAutoOpenThisSession = true
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    themeMode: AppThemeMode,
    accent: AppAccent,
    amoledDark: Boolean,
    incomingExternalUri: Uri?,
    onConsumeExternalUri: () -> Unit,
    onThemeModeChange: (AppThemeMode) -> Unit,
    onAccentChange: (AppAccent) -> Unit,
    onAmoledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getInstance(context).bookDao() }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val totalCount by dao.observeTotalCount().collectAsState(initial = 0)
    val pendingMetadataCount by dao.observePendingMetadataCount().collectAsState(initial = 0)
    val toConvertCount by dao.observeToConvertCount().collectAsState(initial = 0)
    val lastOpened by dao.observeLastOpened().collectAsState(initial = null)
    val scanStatus by LibraryScanner.status.collectAsState()

    var activeTab by remember { mutableStateOf(LibraryTab.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    var selectedBook by remember { mutableStateOf<BookEntity?>(null) }
    var editingBook by remember { mutableStateOf<BookEntity?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var convertPrompt by remember { mutableStateOf<PendingConvertTarget?>(null) }
    var busyTitle by remember { mutableStateOf<String?>(null) }
    var busyDetail by remember { mutableStateOf("") }
    var savedFoldersCount by remember { mutableIntStateOf(LibraryScanner.getSavedFolders(context).size) }

    var autoOpenLastBook by remember { mutableStateOf(ThemePrefs.isAutoOpenLastBook(context)) }
    var kindleEmail by remember { mutableStateOf(ThemePrefs.getKindleEmail(context)) }
    var convertedOutputUri by remember { mutableStateOf(ThemePrefs.getConvertedOutputUri(context)) }

    var selectionModeActive by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectionMoreMenu by remember { mutableStateOf(false) }
    var showBatchRemoveConfirm by remember { mutableStateOf(false) }
    var showBatchEditDialog by remember { mutableStateOf(false) }
    val inSelectionMode = selectionModeActive || selectedIds.isNotEmpty()

    BackHandler(enabled = drawerState.isOpen || inSelectionMode) {
        if (drawerState.isOpen) scope.launch { drawerState.close() }
        else { selectedIds = emptySet(); selectionModeActive = false }
    }

    fun toggleSelection(id: Long) {
        val next = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
        selectedIds = next
        if (next.isEmpty()) selectionModeActive = false
    }

    fun takeReadWritePermission(uri: Uri) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, rw) }
            .onFailure {
                runCatching {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
    }

    fun importOrOpenSingleUri(uri: Uri, openDirectlyIfEpub: Boolean) {
        takeReadWritePermission(uri)
        val fileName = EpubParser.getFileName(context, uri)
        val ext = fileName.substringAfterLast('.', "").trim().lowercase()
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: ""
        val isEpub = ext == "epub" || mime.contains("epub", ignoreCase = true)
        val isMobi = ext in setOf("mobi", "azw3", "azw", "prc") || mime.contains("mobi", ignoreCase = true)

        when {
            isMobi -> convertPrompt = PendingConvertTarget(uri, fileName)
            isEpub -> {
                statusMessage = "Importing $fileName..."
                scope.launch(Dispatchers.IO) {
                    val result = runCatching {
                        val meta = EpubParser.parseEpub(context, uri, fileName)
                        val coverPath = LibraryScanner.saveWebpThumbnail(context, uri.toString(), meta.coverBitmap)
                        val existing = dao.getByUri(uri.toString())
                        if (existing == null) {
                            dao.insertIgnore(
                                BookEntity(
                                    uriString = uri.toString(),
                                    fileName = fileName,
                                    format = "EPUB",
                                    fileSize = 0L,
                                    lastModified = 0L,
                                    title = meta.title,
                                    author = meta.author,
                                    publishingDate = meta.publishingDate,
                                    publisher = meta.publisher,
                                    language = meta.language,
                                    coverPath = coverPath,
                                    lastLocatorJson = null,
                                    progressPercent = 0.0,
                                    lastOpenedAt = System.currentTimeMillis(),
                                    metadataExtracted = true
                                )
                            )
                        } else {
                            dao.updateExtractedMetadata(
                                existing.id, meta.title, meta.author,
                                meta.publishingDate, meta.publisher, meta.language, coverPath
                            )
                        }
                        dao.getByUri(uri.toString())
                    }
                    withContext(Dispatchers.Main) {
                        result.onSuccess { book ->
                            statusMessage = null
                            if (openDirectlyIfEpub && book != null) {
                                openInReader(context, book.uriString)
                            } else {
                                selectedBook = book
                            }
                        }.onFailure { statusMessage = it.localizedMessage ?: "Failed to read EPUB." }
                    }
                }
            }
            else -> statusMessage = "Unsupported file ($fileName). Pick an .epub, .mobi, or .azw3."
        }
    }

    fun runShareBooks(books: List<BookEntity>, sendToKindle: Boolean = false) {
        if (books.isEmpty()) return
        if (sendToKindle && kindleEmail.isBlank()) {
            statusMessage = "Set your @kindle.com email in ☰ → Settings first!"
            showSettings = true
            return
        }
        selectedBook = null
        selectedIds = emptySet()
        selectionModeActive = false
        busyTitle = if (sendToKindle) "Preparing Send to Kindle..." else "Preparing to share..."
        busyDetail = if (books.size == 1) books.first().title else "${books.size} ebooks"
        statusMessage = null

        scope.launch(Dispatchers.IO) {
            val result = runCatching {
                if (sendToKindle) FileOpener.prepareKindleEmailIntent(context, books, kindleEmail)
                else FileOpener.prepareShareIntent(context, books)
            }
            withContext(Dispatchers.Main) {
                busyTitle = null
                result.onSuccess { chooser ->
                    runCatching { context.startActivity(chooser) }
                        .onFailure { statusMessage = "No app available to share/email files." }
                }.onFailure { err ->
                    statusMessage = "Failed: ${err.localizedMessage ?: "Could not read file"}"
                }
            }
        }
    }

    fun runSingleMetadataSave(book: BookEntity, req: EpubEditRequest, writeToFile: Boolean) {
        editingBook = null
        selectedBook = null
        busyTitle = "Saving metadata..."
        busyDetail = req.title
        statusMessage = null

        scope.launch(Dispatchers.IO) {
            val newCoverPath = if (req.removeCover) {
                book.coverPath?.let { path -> runCatching { File(path).delete() } }
                dao.clearCover(book.id)
                null
            } else {
                req.newCoverBitmap?.let { bmp ->
                    EpubMetadataEditor.saveFreshWebpCover(context, book.uriString, book.coverPath, bmp)
                }
            }

            dao.updateExtractedMetadata(
                book.id, req.title, req.author, req.publishingDate,
                req.publisher, req.language, newCoverPath
            )

            val writeStatus = if (writeToFile && book.format == "EPUB") {
                runCatching {
                    EpubMetadataEditor.writeMetadataToEpub(context, Uri.parse(book.uriString), req)
                }.getOrElse {
                    EpubWriteResult.ReadOnlyFallback("Saved in library! File update skipped: ${it.localizedMessage}")
                }
            } else null

            val refreshed = dao.getByUri(book.uriString)
            withContext(Dispatchers.Main) {
                busyTitle = null
                statusMessage = when (writeStatus) {
                    is EpubWriteResult.WrittenToFile -> "Saved to library & updated .epub file!"
                    is EpubWriteResult.ReadOnlyFallback -> writeStatus.reason
                    null -> "Saved metadata to library!"
                }
                selectedBook = refreshed
            }
        }
    }

    fun runBatchMetadataSave(ids: List<Long>, req: BatchMetadataRequest) {
        if (ids.isEmpty()) return
        showBatchEditDialog = false
        selectedIds = emptySet()
        selectionModeActive = false
        busyTitle = "Bulk updating metadata..."
        busyDetail = "${ids.size} book(s)"
        statusMessage = null

        scope.launch(Dispatchers.IO) {
            val books = ids.chunked(500).flatMap { dao.getByIds(it) }
            var filesWritten = 0
            var filesReadOnly = 0

            books.forEachIndexed { idx, book ->
                val newAuthor = req.author ?: book.author
                val newDate = req.publishingDate ?: book.publishingDate
                val newPublisher = req.publisher ?: book.publisher
                val newLang = req.language ?: book.language

                withContext(Dispatchers.Main) { busyDetail = "(${idx + 1}/${books.size}) ${book.title}" }

                dao.updateExtractedMetadata(book.id, book.title, newAuthor, newDate, newPublisher, newLang, null)

                if (req.writeToEpubFiles && book.format == "EPUB") {
                    val editReq = EpubEditRequest(book.title, newAuthor, newDate, newPublisher, newLang, null)
                    val res = runCatching {
                        EpubMetadataEditor.writeMetadataToEpub(context, Uri.parse(book.uriString), editReq)
                    }.getOrNull()
                    if (res is EpubWriteResult.WrittenToFile) filesWritten++ else filesReadOnly++
                }
            }

            withContext(Dispatchers.Main) {
                busyTitle = null
                statusMessage = when {
                    !req.writeToEpubFiles -> "Up
