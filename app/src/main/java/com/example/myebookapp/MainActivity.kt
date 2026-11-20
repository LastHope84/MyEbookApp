package com.example.myebookapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextAlign
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

enum class LibraryTab {
    ALL,
    RECENT,
    TO_CONVERT
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

    val totalCount by dao.observeTotalCount().collectAsState(initial = 0)
    val pendingMetadataCount by dao.observePendingMetadataCount().collectAsState(initial = 0)
    val toConvertCount by dao.observeToConvertCount().collectAsState(initial = 0)
    val lastOpened by dao.observeLastOpened().collectAsState(initial = null)
    val scanStatus by LibraryScanner.status.collectAsState()

    var activeTab by remember { mutableStateOf(LibraryTab.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var selectedBook by remember { mutableStateOf<BookEntity?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var convertPrompt by remember { mutableStateOf<PendingConvertTarget?>(null) }
    var isConverting by remember { mutableStateOf(false) }
    var convertingLabel by remember { mutableStateOf("") }
    var savedFoldersCount by remember { mutableIntStateOf(LibraryScanner.getSavedFolders(context).size) }

    var selectionModeActive by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBatchRemoveConfirm by remember { mutableStateOf(false) }
    val inSelectionMode = selectionModeActive || selectedIds.isNotEmpty()

    BackHandler(enabled = inSelectionMode) {
        selectedIds = emptySet()
        selectionModeActive = false
    }

    fun toggleSelection(id: Long) {
        val next = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id
        selectedIds = next
        if (next.isEmpty()) selectionModeActive = false
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
                    convertingLabel = if (targets.size == 1) {
                        target.fileName
                    } else {
                        "(${idx + 1}/${targets.size}) ${target.fileName}"
                    }
                }

                val result = runCatching {
                    val epubFile = MobiConverter.convertToEpub(context, target.uri, target.fileName)
                    val epubUri = Uri.fromFile(epubFile)
                    val epubUriStr = epubUri.toString()

                    val meta = EpubParser.parseEpub(context, epubUri, epubFile.name)
                    val coverPath = LibraryScanner.saveWebpThumbnail(
                        context,
                        epubUriStr,
                        meta.coverBitmap
                    )

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

                    target.existingBookId?.let { oldId ->
                        dao.deleteById(oldId)
                    }

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
                    targets.size == 1 && okCount == 1 ->
                        "Converted to EPUB! Original file kept safe."
                    targets.size == 1 && failCount == 1 ->
                        "Conversion failed: ${lastErr ?: "Unknown error"}"
                    failCount == 0 ->
                        "Converted $okCount book(s) to EPUB!"
                    else ->
                        "Converted $okCount book(s), $failCount failed."
                }
                if (openLastOnSuccess && lastConverted != null) {
                    selectedBook = lastConverted
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val legacy = BookHistoryStore.getAll(context)
            for (old in legacy) {
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
            config = PagingConfig(
                pageSize = 60,
                initialLoadSize = 180,
                prefetchDistance = 30,
                enablePlaceholders = true
            ),
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

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        LibraryScanner.addFolder(context, treeUri)
        savedFoldersCount = LibraryScanner.getSavedFolders(context).size
        LibraryScanner.scanFolders(context, listOf(treeUri))
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        val fileName = EpubParser.getFileName(context, uri)
        val ext = fileName.substringAfterLast('.', "").trim().lowercase()

        when (ext) {
            "mobi", "azw3", "azw", "prc" -> {
                convertPrompt = PendingConvertTarget(uri = uri, fileName = fileName)
            }
            "epub" -> {
                statusMessage = "Importing $fileName..."
                scope.launch(Dispatchers.IO) {
                    val result = runCatching {
                        val meta = EpubParser.parseEpub(context, uri, fileName)
                        val coverPath = LibraryScanner.saveWebpThumbnail(
                            context,
                            uri.toString(),
                            meta.coverBitmap
                        )
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
                                id = existing.id,
                                title = meta.title,
                                author = meta.author,
                                publishingDate = meta.publishingDate,
                                publisher = meta.publisher,
                                language = meta.language,
                                coverPath = coverPath
                            )
                        }
                        dao.getByUri(uri.toString())
                    }
                    withContext(Dispatchers.Main) {
                        result.onSuccess { book ->
                            statusMessage = null
                            selectedBook = book
                        }.onFailure { err ->
                            statusMessage = err.localizedMessage ?: "Failed to read EPUB."
                        }
                    }
                }
            }
            else -> {
                statusMessage = "Unsupported file ($fileName). Pick an .epub, .mobi, or .azw3."
            }
        }
    }

    Scaffold(
        topBar = {
            if (inSelectionMode) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    navigationIcon = {
                        TextButton(
                            onClick = {
                                selectedIds = emptySet()
                                selectionModeActive = false
                            }
                        ) {
                            Text("Cancel")
                        }
                    },
                    title = {
                        Text(
                            text = "${selectedIds.size} selected",
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    actions = {
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
                                                targets = convertible.map {
                                                    PendingConvertTarget(
                                                        uri = Uri.parse(it.uriString),
                                                        fileName = it.fileName,
                                                        existingBookId = it.id
                                                    )
                                                },
                                                openLastOnSuccess = false
                                            )
                                        }
                                    }
                                }
                            }
                        ) {
                            Text("Convert")
                        }
                        TextButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = { showBatchRemoveConfirm = true }
                        ) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    }
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text("My Library")
                            Text(
                                text = "$totalCount books",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menuExpanded = true }) {
                                HamburgerIcon()
                            }
                            DropdownMenu(
                                expanded = menuExpanded,
                                onDismissRequest = { menuExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Add / Scan Folder") },
                                    onClick = {
                                        menuExpanded = false
                                        folderPicker.launch(null)
                                    }
                                )
                                if (savedFoldersCount > 0 && !scanStatus.isScanning) {
                                    DropdownMenuItem(
                                        text = { Text("Rescan Folders ($savedFoldersCount)") },
                                        onClick = {
                                            menuExpanded = false
                                            val uris = LibraryScanner.getSavedFolders(context)
                                                .map { Uri.parse(it) }
                                            LibraryScanner.scanFolders(context, uris)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text("Pick Single File") },
                                    onClick = {
                                        menuExpanded = false
                                        filePicker.launch(arrayOf("*/*"))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Select Multiple Books") },
                                    onClick = {
                                        menuExpanded = false
                                        selectionModeActive = true
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Theme: ${themeMode.label}") },
                                    onClick = {
                                        onCycleThemeMode()
                                    }
                                )
                            }
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
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    if (!isSearchFocused && searchQuery.isEmpty()) {
                        Text(
                            text = "Search title, author, publisher...",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                singleLine = true,
                maxLines = 1,
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        TextButton(onClick = { searchQuery = "" }) {
                            Text("Clear")
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { isSearchFocused = it.isFocused }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = activeTab == LibraryTab.ALL,
                    onClick = { activeTab = LibraryTab.ALL },
                    label = { Text("All ($totalCount)") }
                )
                FilterChip(
                    selected = activeTab == LibraryTab.RECENT,
                    onClick = { activeTab = LibraryTab.RECENT },
                    label = { Text("Recent") }
                )
                FilterChip(
                    selected = activeTab == LibraryTab.TO_CONVERT,
                    onClick = { activeTab = LibraryTab.TO_CONVERT },
                    label = { Text("Convert ($toConvertCount)") }
                )
            }

            lastOpened?.let { recent ->
                if (recent.format == "EPUB" && !inSelectionMode) {
                    val pct = (recent.progressPercent * 100).roundToInt()
                    OutlinedButton(
                        onClick = { openInReader(context, recent.uriString) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                    ) {
                        Text(
                            text = if (pct > 0) {
                                "Continue: ${recent.title} ($pct%)"
                            } else {
                                "Continue: ${recent.title}"
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (scanStatus.isScanning) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = scanStatus.phaseLabel,
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { LibraryScanner.stopScan() }) {
                                Text("Stop")
                            }
                        }
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Found: ${scanStatus.filesFoundInPass} • Done: ${scanStatus.metadataProcessed} • Left: ${scanStatus.metadataLeft}",
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
                    Text(
                        text = "$pendingMetadataCount EPUBs waiting for covers",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    TextButton(onClick = { LibraryScanner.resumeMetadataOnly(context) }) {
                        Text("Resume Covers")
                    }
                }
            }

            statusMessage?.let { msg ->
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (pagedBooks.itemCount == 0 && !scanStatus.isScanning) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            debouncedQuery.isNotBlank() -> "No matches for \"$debouncedQuery\""
                            activeTab == LibraryTab.RECENT -> "No books opened yet."
                            activeTab == LibraryTab.TO_CONVERT -> "No MOBI/AZW3 files found yet."
                            else -> "Tap the ☰ menu at the top-right to add a folder!"
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
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        count = pagedBooks.itemCount,
                        key = pagedBooks.itemKey { it.id }
                    ) { index ->
                        val book = pagedBooks[index]
                        if (book != null) {
                            BookGridTile(
                                book = book,
                                inSelectionMode = inSelectionMode,
                                isSelected = selectedIds.contains(book.id),
                                onClick = {
                                    if (inSelectionMode) {
                                        toggleSelection(book.id)
                                    } else {
                                        selectedBook = book
                                    }
                                },
                                onLongClick = {
                                    selectionModeActive = true
                                    toggleSelection(book.id)
                                }
                            )
                        } else {
                            BookPlaceholderTile()
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
            onRead = {
                selectedBook = null
                openInReader(context, book.uriString)
            },
            onConvertNow = {
                runBatchConversion(
                    targets = listOf(
                        PendingConvertTarget(
                            uri = Uri.parse(book.uriString),
                            fileName = book.fileName,
                            existingBookId = book.id
                        )
                    ),
                    openLastOnSuccess = true
                )
            },
            onRemoveFromLibrary = {
                val toDelete = book.id
                selectedBook = null
                scope.launch(Dispatchers.IO) {
                    dao.deleteById(toDelete)
                }
            }
        )
    }

    convertPrompt?.let { target ->
        AlertDialog(
            onDismissRequest = { convertPrompt = null },
            title = { Text("Convert to EPUB?") },
            text = {
                Text(
                    "\"${target.fileName}\" is a MOBI/AZW3 file. Convert it offline into an EPUB and add it to your library? (Your original file will stay untouched.)"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        runBatchConversion(
                            targets = listOf(target),
                            openLastOnSuccess = true
                        )
                    }
                ) {
                    Text("Convert Now")
                }
            },
            dismissButton = {
                TextButton(onClick = { convertPrompt = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showBatchRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showBatchRemoveConfirm = false },
            title = { Text("Remove ${selectedIds.size} book(s)?") },
            text = {
                Text("This only removes them from your library database. The actual files on your phone will NOT be deleted.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val toDelete = selectedIds.toList()
                        showBatchRemoveConfirm = false
                        selectedIds = emptySet()
                        selectionModeActive = false
                        scope.launch(Dispatchers.IO) {
                            toDelete.chunked(500).forEach { chunk ->
                                dao.deleteByIds(chunk)
                            }
                        }
                        statusMessage = "Removed ${toDelete.size} book(s) from library."
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatchRemoveConfirm = false }) {
                    Text("Cancel")
                }
            }
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
}

@Composable
fun HamburgerIcon() {
    val barColor = LocalContentColor.current
    Column(
        modifier = Modifier.size(22.dp),
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(barColor)
            )
        }
