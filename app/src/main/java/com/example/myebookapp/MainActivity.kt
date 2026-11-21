package com.example.myebookapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
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
                    !req.writeToEpubFiles -> "Updated ${books.size} book(s) in library!"
                    filesReadOnly == 0 -> "Updated ${books.size} book(s) & wrote $filesWritten .epub file(s)!"
                    else -> "Updated ${books.size} book(s) ($filesWritten .epub written, $filesReadOnly read-only)."
                }
            }
        }
    }

    fun runBatchConversion(targets: List<PendingConvertTarget>, openLastOnSuccess: Boolean) {
        if (targets.isEmpty()) return
        convertPrompt = null
        selectedBook = null
        selectedIds = emptySet()
        selectionModeActive = false
        busyTitle = "Converting to EPUB..."
        statusMessage = null

        scope.launch(Dispatchers.IO) {
            var okCount = 0
            var failCount = 0
            var savedCustomCount = 0
            var lastConverted: BookEntity? = null
            var lastErr: String? = null
            val customTree = convertedOutputUri?.let { Uri.parse(it) }

            targets.forEachIndexed { idx, target ->
                withContext(Dispatchers.Main) {
                    busyDetail = if (targets.size == 1) target.fileName
                    else "(${idx + 1}/${targets.size}) ${target.fileName}"
                }

                val result = runCatching {
                    val out = MobiConverter.convertToEpubOutput(context, target.uri, target.fileName, customTree)
                    val epubUriStr = out.uri.toString()
                    val meta = EpubParser.parseEpub(context, out.uri, out.fileName)
                    val coverPath = LibraryScanner.saveWebpThumbnail(context, epubUriStr, meta.coverBitmap)

                    dao.insertIgnore(
                        BookEntity(
                            uriString = epubUriStr,
                            fileName = out.fileName,
                            format = "EPUB",
                            fileSize = out.size,
                            lastModified = out.lastModified,
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
                    target.existingBookId?.let { dao.deleteById(it) }
                    out.savedToCustomFolder to dao.getByUri(epubUriStr)
                }

                result.onSuccess { (toCustom, book) ->
                    okCount++
                    if (toCustom) savedCustomCount++
                    if (book != null) lastConverted = book
                }.onFailure { err ->
                    failCount++
                    lastErr = err.localizedMessage
                }
            }

            withContext(Dispatchers.Main) {
                busyTitle = null
                val destNote = if (savedCustomCount > 0) " (saved to chosen folder)" else ""
                statusMessage = when {
                    targets.size == 1 && okCount == 1 -> "Converted to EPUB$destNote! Original kept safe."
                    targets.size == 1 && failCount == 1 -> "Conversion failed: ${lastErr ?: "Unknown error"}"
                    failCount == 0 -> "Converted $okCount book(s) to EPUB$destNote!"
                    else -> "Converted $okCount book(s), $failCount failed."
                }
                if (openLastOnSuccess && lastConverted != null) selectedBook = lastConverted
            }
        }
    }

    LaunchedEffect(incomingExternalUri) {
        val extUri = incomingExternalUri ?: return@LaunchedEffect
        onConsumeExternalUri()
        importOrOpenSingleUri(extUri, openDirectlyIfEpub = true)
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            for (old in BookHistoryStore.getAll(context)) {
                if (dao.getByUri(old.uriString) == null) {
                    dao.insertIgnore(
                        BookEntity(
                            uriString = old.uriString,
                            fileName = old.fileName,
                            format = "EPUB",
                            fileSize = 0L,
                            lastModified = 0L,
                            title = old.title,
                            author = old.author,
                            publishingDate = old.publishingDate,
                            publisher = old.publisher,
                            language = old.language,
                            coverPath = old.coverPath,
                            lastLocatorJson = old.lastLocatorJson,
                            progressPercent = old.progressPercent,
                            lastOpenedAt = old.lastOpenedAt,
                            metadataExtracted = true
                        )
                    )
                }
            }
        }
        if (!MainActivity.didAutoOpenThisSession && autoOpenLastBook && incomingExternalUri == null) {
            MainActivity.didAutoOpenThisSession = true
            val last = withContext(Dispatchers.IO) { dao.getLastOpenedOnce() }
            if (last != null && last.format == "EPUB") {
                openInReader(context, last.uriString)
            }
        }
    }

    LaunchedEffect(searchQuery) {
        delay(250)
        debouncedQuery = searchQuery
    }

    val booksFlow = remember(debouncedQuery, activeTab, scope) {
        Pager(
            config = PagingConfig(pageSize = 60, initialLoadSize = 180, prefetchDistance = 30, enablePlaceholders = true),
            pagingSourceFactory = {
                val fts = toFtsQuery(debouncedQuery)
                when (activeTab) {
                    LibraryTab.ALL -> if (fts == null) dao.allPaged() else dao.searchAllPaged(fts)
                    LibraryTab.RECENT -> if (fts == null) dao.recentPaged() else dao.searchRecentPaged(fts)
                    LibraryTab.TO_CONVERT -> if (fts == null) dao.toConvertPaged() else dao.searchToConvertPaged(fts)
                }
            }
        ).flow.cachedIn(scope)
    }
    val pagedBooks = booksFlow.collectAsLazyPagingItems()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        takeReadWritePermission(treeUri)
        LibraryScanner.addFolder(context, treeUri)
        savedFoldersCount = LibraryScanner.getSavedFolders(context).size
        LibraryScanner.scanFolders(context, listOf(treeUri))
        statusMessage = "Folder added to scan queue."
    }

    val convertOutputPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        takeReadWritePermission(treeUri)
        convertedOutputUri = treeUri.toString()
        ThemePrefs.saveConvertedOutputUri(context, treeUri.toString())
        statusMessage = "Converted EPUBs will now save to your chosen folder!"
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importOrOpenSingleUri(uri, openDirectlyIfEpub = false)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = !inSelectionMode,
        drawerContent = {
            ModalDrawerSheet(modifier = Modifier.width(300.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text("My Ebook Library", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("$totalCount books", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Spacer(modifier = Modifier.height(14.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            if (!isSearchFocused && searchQuery.isEmpty()) {
                                Text("Search books...", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        singleLine = true,
                        maxLines = 1,
                        shape = RoundedCornerShape(12.dp),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { scope.launch { drawerState.close() } }),
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                TextButton(onClick = { searchQuery = "" }) { Text("Clear") }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isSearchFocused = it.isFocused }
                    )

                    lastOpened?.let { recent ->
                        if (recent.format == "EPUB") {
                            Spacer(modifier = Modifier.height(10.dp))
                            val pct = (recent.progressPercent * 100).roundToInt()
                            Button(
                                onClick = {
                                    scope.launch { drawerState.close() }
                                    openInReader(context, recent.uriString)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = if (pct > 0) "Continue: ${recent.title} ($pct%)" else "Continue: ${recent.title}",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    NavigationDrawerItem(
                        label = { Text("All Books ($totalCount)") },
                        selected = activeTab == LibraryTab.ALL,
                        onClick = { activeTab = LibraryTab.ALL; scope.launch { drawerState.close() } }
                    )
                    NavigationDrawerItem(
                        label = { Text("Recently Read") },
                        selected = activeTab == LibraryTab.RECENT,
                        onClick = { activeTab = LibraryTab.RECENT; scope.launch { drawerState.close() } }
                    )
                    NavigationDrawerItem(
                        label = { Text("To Convert ($toConvertCount)") },
                        selected = activeTab == LibraryTab.TO_CONVERT,
                        onClick = { activeTab = LibraryTab.TO_CONVERT; scope.launch { drawerState.close() } }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    NavigationDrawerItem(
                        label = { Text("Add / Scan Folder") },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; folderPicker.launch(null) }
                    )
                    if (savedFoldersCount > 0) {
                        NavigationDrawerItem(
                            label = { Text("Rescan Folders ($savedFoldersCount)") },
                            selected = false,
                            onClick = {
                                scope.launch { drawerState.close() }
                                LibraryScanner.scanFolders(context, LibraryScanner.getSavedFolders(context).map { Uri.parse(it) })
                                statusMessage = "Queued $savedFoldersCount folder(s) for rescan."
                            }
                        )
                    }
                    NavigationDrawerItem(
                        label = { Text("Pick Single File") },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; filePicker.launch(arrayOf("*/*")) }
                    )
                    NavigationDrawerItem(
                        label = { Text("Select Multiple Books") },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; selectionModeActive = true }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))

                    NavigationDrawerItem(
                        label = { Text("Settings") },
                        selected = false,
                        onClick = { scope.launch { drawerState.close() }; showSettings = true }
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                if (inSelectionMode) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        navigationIcon = {
                            TextButton(onClick = { selectedIds = emptySet(); selectionModeActive = false }) { Text("✕") }
                        },
                        title = { Text("${selectedIds.size}", style = MaterialTheme.typography.titleMedium) },
                        actions = {
                            TextButton(enabled = selectedIds.isNotEmpty(), onClick = { showBatchEditDialog = true }) { Text("Edit") }
                            TextButton(
                                enabled = selectedIds.isNotEmpty(),
                                onClick = {
                                    val ids = selectedIds.toList()
                                    scope.launch(Dispatchers.IO) {
                                        val books = ids.chunked(500).flatMap { dao.getByIds(it) }
                                        withContext(Dispatchers.Main) { runShareBooks(books, sendToKindle = false) }
                                    }
                                }
                            ) { Text("Share") }
                            Box {
                                TextButton(onClick = { selectionMoreMenu = true }) {
                                    Text("⋮", style = MaterialTheme.typography.titleMedium)
                                }
                                DropdownMenu(expanded = selectionMoreMenu, onDismissRequest = { selectionMoreMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text("Select All in View") },
                                        onClick = {
                                            selectionMoreMenu = false
                                            scope.launch(Dispatchers.IO) {
                                                val fts = toFtsQuery(debouncedQuery)
                                                val allIds = when (activeTab) {
                                                    LibraryTab.ALL -> if (fts == null) dao.getAllIds() else dao.searchAllIds(fts)
                                                    LibraryTab.RECENT -> if (fts == null) dao.getRecentIds() else dao.searchRecentIds(fts)
                                                    LibraryTab.TO_CONVERT -> if (fts == null) dao.getToConvertIds() else dao.searchToConvertIds(fts)
                                                }
                                                withContext(Dispatchers.Main) {
                                                    selectedIds = allIds.toSet()
                                                    selectionModeActive = true
                                                }
                                            }
                                        }
                                    )
                                    if (selectedIds.isNotEmpty()) {
                                        DropdownMenuItem(
                                            text = { Text("Send to Kindle") },
                                            onClick = {
                                                selectionMoreMenu = false
                                                val ids = selectedIds.toList()
                                                scope.launch(Dispatchers.IO) {
                                                    val books = ids.chunked(500).flatMap { dao.getByIds(it) }
                                                    withContext(Dispatchers.Main) { runShareBooks(books, sendToKindle = true) }
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Convert to EPUB") },
                                            onClick = {
                                                selectionMoreMenu = false
                                                val ids = selectedIds.toList()
                                                scope.launch(Dispatchers.IO) {
                                                    val books = ids.chunked(500).flatMap { dao.getByIds(it) }
                                                    val convertible = books.filter { it.format != "EPUB" }
                                                    withContext(Dispatchers.Main) {
                                                        if (convertible.isEmpty()) statusMessage = "Selected books are already EPUBs."
                                                        else runBatchConversion(
                                                            convertible.map { PendingConvertTarget(Uri.parse(it.uriString), it.fileName, it.id) },
                                                            openLastOnSuccess = false
                                                        )
                                                    }
                                                }
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Remove from Library", color = MaterialTheme.colorScheme.error) },
                                            onClick = { selectionMoreMenu = false; showBatchRemoveConfirm = true }
                                        )
                                    }
                                }
                            }
                        }
                    )
                } else {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) { HamburgerIcon() }
                        },
                        title = {
                            Column {
                                Text(activeTab.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val sub = if (debouncedQuery.isNotBlank()) "Search: \"$debouncedQuery\"" else "$totalCount books"
                                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        },
                        actions = {
                            if (searchQuery.isNotEmpty()) {
                                TextButton(onClick = { searchQuery = "" }) { Text("Clear") }
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp)
            ) {
                if (scanStatus.isScanning) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(scanStatus.phaseLabel, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                if (scanStatus.isPaused) {
                                    TextButton(onClick = { LibraryScanner.resumeScan() }) { Text("Resume") }
                                } else {
                                    TextButton(onClick = { LibraryScanner.pauseScan() }) { Text("Pause") }
                                }
                                TextButton(onClick = { LibraryScanner.stopScan() }) { Text("Cancel") }
                            }
                            if (!scanStatus.isPaused) {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Found: ${scanStatus.filesFoundInPass} • Done: ${scanStatus.metadataProcessed} • Left: ${scanStatus.metadataLeft}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                } else if (pendingMetadataCount > 0) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("$pendingMetadataCount EPUBs waiting for covers", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { LibraryScanner.resumeMetadataOnly(context) }) { Text("Resume Covers") }
                    }
                }

                statusMessage?.let { msg ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                        TextButton(onClick = { statusMessage = null }) { Text("✕") }
                    }
                }

                if (pagedBooks.itemCount == 0 && !scanStatus.isScanning) {
                    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = when {
                                debouncedQuery.isNotBlank() -> "No matches for \"$debouncedQuery\""
                                activeTab == LibraryTab.RECENT -> "No books opened yet."
                                activeTab == LibraryTab.TO_CONVERT -> "No MOBI/AZW3 files found yet."
                                else -> "Tap ☰ at the top-left to add a folder!"
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(count = pagedBooks.itemCount, key = pagedBooks.itemKey { it.id }) { index ->
                            val book = pagedBooks[index]
                            if (book != null) {
                                BookGridTile(
                                    book = book,
                                    inSelectionMode = inSelectionMode,
                                    isSelected = selectedIds.contains(book.id),
                                    onClick = { if (inSelectionMode) toggleSelection(book.id) else selectedBook = book },
                                    onLongClick = { selectionModeActive = true; toggleSelection(book.id) }
                                )
                            } else {
                                BookPlaceholderTile()
                            }
                        }
                    }
                }
            }
        }
    }

    selectedBook?.let { book ->
        BookDetailsBottomSheet(
            book = book,
            hasKindleEmail = kindleEmail.isNotBlank(),
            onDismiss = { selectedBook = null },
            onRead = { selectedBook = null; openInReader(context, book.uriString) },
            onEdit = { selectedBook = null; editingBook = book },
            onShare = { runShareBooks(listOf(book), sendToKindle = false) },
            onSendToKindle = { runShareBooks(listOf(book), sendToKindle = true) },
            onConvertNow = {
                runBatchConversion(
                    listOf(PendingConvertTarget(Uri.parse(book.uriString), book.fileName, book.id)),
                    openLastOnSuccess = true
                )
            },
            onRemoveFromLibrary = {
                val id = book.id
                selectedBook = null
                scope.launch(Dispatchers.IO) { dao.deleteById(id) }
            }
        )
    }

    editingBook?.let { book ->
        MetadataEditorBottomSheet(
            book = book,
            onDismiss = { editingBook = null },
            onSave = { req, writeToFile -> runSingleMetadataSave(book, req, writeToFile) }
        )
    }

    if (showSettings) {
        AppSettingsBottomSheet(
            themeMode = themeMode,
            accent = accent,
            amoledDark = amoledDark,
            autoOpenLastBook = autoOpenLastBook,
            kindleEmail = kindleEmail,
            convertedOutputUri = convertedOutputUri,
            onThemeModeChange = onThemeModeChange,
            onAccentChange = onAccentChange,
            onAmoledChange = onAmoledChange,
            onAutoOpenChange = { autoOpenLastBook = it; ThemePrefs.saveAutoOpenLastBook(context, it) },
            onKindleEmailSave = {
                kindleEmail = it.trim()
                ThemePrefs.saveKindleEmail(context, it)
                statusMessage = if (it.isBlank()) "Kindle email cleared." else "Saved Kindle email: ${it.trim()}"
            },
            onPickConvertedFolder = { showSettings = false; convertOutputPicker.launch(null) },
            onResetConvertedFolder = {
                convertedOutputUri = null
                ThemePrefs.saveConvertedOutputUri(context, null)
                statusMessage = "Converted EPUBs reset to App Storage."
            },
            onCleanDuplicates = {
                showSettings = false
                busyTitle = "Cleaning duplicates..."
                busyDetail = "Checking library..."
                scope.launch(Dispatchers.IO) {
                    val removed = removeDuplicateBooks(dao)
                    withContext(Dispatchers.Main) {
                        busyTitle = null
                        statusMessage = if (removed > 0) "Removed $removed duplicate entry(s)!" else "No duplicates found!"
                    }
                }
            },
            onDismiss = { showSettings = false }
        )
    }

    if (showBatchEditDialog) {
        BatchMetadataDialog(
            selectedCount = selectedIds.size,
            onDismiss = { showBatchEditDialog = false },
            onApply = { req -> runBatchMetadataSave(selectedIds.toList(), req) }
        )
    }

    convertPrompt?.let { target ->
        AlertDialog(
            onDismissRequest = { convertPrompt = null },
            title = { Text("Convert to EPUB?") },
            text = { Text("\"${target.fileName}\" is a MOBI/AZW3 file. Convert it offline into an EPUB and add it to your library? (Your original file will stay untouched.)") },
            confirmButton = {
                TextButton(onClick = { runBatchConversion(listOf(target), openLastOnSuccess = true) }) { Text("Convert Now") }
            },
            dismissButton = { TextButton(onClick = { convertPrompt = null }) { Text("Cancel") } }
        )
    }

    if (showBatchRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchRemoveConfirm = false },
            title = { Text("Remove ${selectedIds.size} book(s)?") },
            text = { Text("This only removes them from your library database. The actual files on your phone will NOT be deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = selectedIds.toList()
                        showBatchRemoveConfirm = false
                        selectedIds = emptySet()
                        selectionModeActive = false
                        scope.launch(Dispatchers.IO) { toDelete.chunked(500).forEach { dao.deleteByIds(it) } }
                        statusMessage = "Removed ${toDelete.size} book(s) from library."
                    }
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showBatchRemoveConfirm = false }) { Text("Cancel") } }
        )
    }

    busyTitle?.let { title ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(title) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    if (busyDetail.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(busyDetail, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
            confirmButton = {}
        )
    }
}
