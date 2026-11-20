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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            var themeMode by remember { mutableStateOf(ThemePrefs.getMode(context)) }
            MyEbookTheme(mode = themeMode) {
                LibraryScreen(
                    themeMode = themeMode,
                    onCycleThemeMode = {
                        val next = themeMode.next()
                        themeMode = next
                        ThemePrefs.saveMode(context, next)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    themeMode: AppThemeMode,
    onCycleThemeMode: () -> Unit
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
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var convertPrompt by remember { mutableStateOf<PendingConvertTarget?>(null) }
    var isConverting by remember { mutableStateOf(false) }
    var convertingLabel by remember { mutableStateOf("") }
    var isSharing by remember { mutableStateOf(false) }
    var savedFoldersCount by remember { mutableIntStateOf(LibraryScanner.getSavedFolders(context).size) }

    var selectionModeActive by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBatchRemoveConfirm by remember { mutableStateOf(false) }
    val inSelectionMode = selectionModeActive || selectedIds.isNotEmpty()

    BackHandler(enabled = drawerState.isOpen || inSelectionMode) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else {
            selectedIds = emptySet()
            selectionModeActive = false
        }
    }

    fun toggleSelection(id: Long) {
        val next = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
        selectedIds = next
        if (next.isEmpty()) selectionModeActive = false
    }

    fun runShareBooks(books: List<BookEntity>) {
        if (books.isEmpty()) return
        selectedBook = null
        selectedIds = emptySet()
        selectionModeActive = false
        isSharing = true
        statusMessage = null

        scope.launch(Dispatchers.IO) {
            val result = runCatching { FileOpener.prepareShareIntent(context, books) }
            withContext(Dispatchers.Main) {
                isSharing = false
                result.onSuccess { chooser ->
                    runCatching { context.startActivity(chooser) }
                        .onFailure { statusMessage = "No app available to share files." }
                }.onFailure { err ->
                    statusMessage = "Share failed: ${err.localizedMessage ?: "Could not read file"}"
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
        isConverting = true
        statusMessage = null

        scope.launch(Dispatchers.IO) {
            var okCount = 0
            var failCount = 0
            var lastConverted: BookEntity? = null
            var lastErr: String? = null

            targets.forEachIndexed { idx, target ->
                withContext(Dispatchers.Main) {
                    convertingLabel = if (targets.size == 1) target.fileName
                    else "(${idx + 1}/${targets.size}) ${target.fileName}"
                }

                val result = runCatching {
                    val epubFile = MobiConverter.convertToEpub(context, target.uri, target.fileName)
                    val epubUri = Uri.fromFile(epubFile)
                    val epubUriStr = epubUri.toString()
                    val meta = EpubParser.parseEpub(context, epubUri, epubFile.name)
                    val coverPath = LibraryScanner.saveWebpThumbnail(context, epubUriStr, meta.coverBitmap)

                    dao.insertIgnore(
                        BookEntity(
                            uriString = epubUriStr,
                            fileName = epubFile.name,
                            format = "EPUB",
                            fileSize = epubFile.length(),
                            lastModified = epubFile.lastModified(),
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
                    dao.getByUri(epubUriStr)
                }

                result.onSuccess { book ->
                    okCount++
                    if (book != null) lastConverted = book
                }.onFailure { err ->
                    failCount++
                    lastErr = err.localizedMessage
                }
            }

            withContext(Dispatchers.Main) {
                isConverting = false
                statusMessage = when {
                    targets.size == 1 && okCount == 1 -> "Converted to EPUB! Original file kept safe."
                    targets.size == 1 && failCount == 1 -> "Conversion failed: ${lastErr ?: "Unknown error"}"
                    failCount == 0 -> "Converted $okCount book(s) to EPUB!"
                    else -> "Converted $okCount book(s), $failCount failed."
                }
                if (openLastOnSuccess && lastConverted != null) {
                    selectedBook = lastConverted
                }
            }
        }
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
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        LibraryScanner.addFolder(context, treeUri)
        savedFoldersCount = LibraryScanner.getSavedFolders(context).size
        LibraryScanner.scanFolders(context, listOf(treeUri))
        statusMessage = "Folder added to scan queue."
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val fileName = EpubParser.getFileName(context, uri)
        when (fileName.substringAfterLast('.', "").trim().lowercase()) {
            "mobi", "azw3", "azw", "prc" -> convertPrompt = PendingConvertTarget(uri, fileName)
            "epub" -> {
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
                        result.onSuccess { statusMessage = null; selectedBook = it }
                            .onFailure { statusMessage = it.localizedMessage ?: "Failed to read EPUB." }
                    }
                }
            }
            else -> statusMessage = "Unsupported file ($fileName). Pick an .epub, .mobi, or .azw3."
        }
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
                        label = { Text("Theme: ${themeMode.label}") },
                        selected = false,
                        onClick = { onCycleThemeMode() }
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
                            TextButton(
                                enabled = selectedIds.isNotEmpty(),
                                onClick = {
                                    val ids = selectedIds.toList()
                                    scope.launch(Dispatchers.IO) {
                                        val books = ids.chunked(500).flatMap { dao.getByIds(it) }
                                        withContext(Dispatchers.Main) { runShareBooks(books) }
                                    }
                                }
                            ) { Text("Share") }
                            TextButton(
                                enabled = selectedIds.isNotEmpty(),
                                onClick = {
                                    val idsToCheck = selectedIds.toList()
                                    scope.launch(Dispatchers.IO) {
                                        val books = idsToCheck.chunked(500).flatMap { dao.getByIds(it) }
                                        val convertible = books.filter { it.format != "EPUB" }
                                        withContext(Dispatchers.Main) {
                                            if (convertible.isEmpty()) {
                                                statusMessage = "Selected books are already EPUBs."
                                            } else {
                                                runBatchConversion(
                                                    convertible.map { PendingConvertTarget(Uri.parse(it.uriString), it.fileName, it.id) },
                                                    openLastOnSuccess = false
                                                )
                                            }
                                        }
                                    }
                                }
                            ) { Text("Convert") }
                            TextButton(
                                enabled = selectedIds.isNotEmpty(),
                                onClick = { showBatchRemoveConfirm = true }
                            ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
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
                                TextButton(onClick = { LibraryScanner.stopScan() }) { Text("Stop") }
                            }
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
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
            onDismiss = { selectedBook = null },
            onRead = { selectedBook = null; openInReader(context, book.uriString) },
            onShare = { runShareBooks(listOf(book)) },
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
                        scope.launch(Dispatchers.IO) {
                            toDelete.chunked(500).forEach { dao.deleteByIds(it) }
                        }
                        statusMessage = "Removed ${toDelete.size} book(s) from library."
                    }
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showBatchRemoveConfirm = false }) { Text("Cancel") } }
        )
    }

    if (isConverting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Converting to EPUB...") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(convertingLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {}
        )
    }

    if (isSharing) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Preparing to share...") },
            text = { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) },
            confirmButton = {}
        )
    }
}

@Composable
fun HamburgerIcon() {
    val barColor = LocalContentColor.current
    Column(modifier = Modifier.size(22.dp), verticalArrangement = Arrangement.SpaceEvenly) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
        }
    }
}

private fun openInReader(context: Context, uriString: String) {
    val uri = Uri.parse(uriString)
    val intent = Intent(context, ReaderActivity::class.java).apply {
        putExtra("book_uri", uriString)
        if (uri.scheme == "content") {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    context.startActivity(intent)
}

@Composable
fun BookPlaceholderTile() {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookGridTile(
    book: BookEntity,
    inSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val pct = (book.progressPercent * 100).roundToInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (isSelected) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                    else Modifier
                )
        ) {
            if (!book.coverPath.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(File(book.coverPath)).crossfade(true).build(),
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(book.title, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(book.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }

            if (isSelected) {
                Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)))
            }

            if (inSelectionMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                        )
                        .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Text("✓", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            val badgeText = when {
                book.format != "EPUB" -> book.format
                pct > 0 -> "$pct%"
                else -> null
            }
            if (badgeText != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(book.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(book.author, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailsBottomSheet(
    book: BookEntity,
    onDismiss: () -> Unit,
    onRead: () -> Unit,
    onShare: () -> Unit,
    onConvertNow: () -> Unit,
    onRemoveFromLibrary: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pct = (book.progressPercent * 100).roundToInt()
    var pathNotice by remember(book.id) { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!book.coverPath.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(File(book.coverPath)).crossfade(true).build(),
                    contentDescription = book.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(210.dp).clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 140.dp, height = 190.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) { Text(book.format) }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (book.format == "EPUB") {
                Button(onClick = onRead, modifier = Modifier.fillMaxWidth()) {
                    Text(if (pct > 0) "Continue Reading ($pct%)" else "Read This Book")
                }
            } else {
                Button(onClick = onConvertNow, modifier = Modifier.fillMaxWidth()) {
                    Text("Convert ${book.format} to EPUB")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onShare, modifier = Modifier.fillMaxWidth()) {
                Text("Share Ebook File")
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            MetadataRow("Title", book.title)
            MetadataRow("Author", book.author)
            MetadataRow("Publishing Date", book.publishingDate)
            MetadataRow("Publisher", book.publisher)
            MetadataRow("Language", book.language)
            MetadataRow("Format", book.format)
            MetadataRow("File", book.fileName)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        pathNotice = FileOpener.openContainingFolder(context, book.uriString)
                    }
                    .padding(vertical = 6.dp)
            ) {
                Text("Path (tap to open folder)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = FileOpener.formatReadablePath(book.uriString),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline
                )
                pathNotice?.let { notice ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onRemoveFromLibrary) {
                Text("Remove from Library (keeps file on phone)", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun MetadataRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}
