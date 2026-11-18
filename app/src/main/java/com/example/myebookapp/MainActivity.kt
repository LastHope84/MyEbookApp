package com.example.myebookapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
    var selectedBook by remember { mutableStateOf<BookEntity?>(null) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var converterPromptFile by remember { mutableStateOf<String?>(null) }
    var savedFoldersCount by remember { mutableIntStateOf(LibraryScanner.getSavedFolders(context).size) }

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
                converterPromptFile = fileName
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
                    TextButton(onClick = { folderPicker.launch(null) }) {
                        Text("+ Folder")
                    }
                    TextButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Text("+ File")
                    }
                    TextButton(onClick = onCycleThemeMode) {
                        Text(themeMode.label)
                    }
                }
            )
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
                placeholder = { Text("Search title, author, publisher, language...") },
                singleLine = true,
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        TextButton(onClick = { searchQuery = "" }) {
                            Text("Clear")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
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
                if (recent.format == "EPUB") {
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
                            text = "Found: ${scanStatus.filesFoundInPass} • Covers & metadata: ${scanStatus.metadataProcessed} • Left: $pendingMetadataCount",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            } else if (pendingMetadataCount > 0 || savedFoldersCount > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    if (pendingMetadataCount > 0) {
                        Text(
                            text = "$pendingMetadataCount EPUBs waiting for covers",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(onClick = { LibraryScanner.resumeMetadataOnly(context) }) {
                            Text("Resume Covers")
                        }
                    } else {
                        Text(
                            text = "$savedFoldersCount folder(s) linked",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(
                            onClick = {
                                val uris = LibraryScanner.getSavedFolders(context)
                                    .map { Uri.parse(it) }
                                LibraryScanner.scanFolders(context, uris)
                            }
                        ) {
                            Text("Rescan Folders")
                        }
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
                            else -> "Tap '+ Folder' at the top to scan your ebooks!"
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
                                onClick = { selectedBook = book }
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
            onConvertPrompt = {
                converterPromptFile = book.fileName
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

    converterPromptFile?.let { fileName ->
        AlertDialog(
            onDismissRequest = { converterPromptFile = null },
            title = { Text("Convert to EPUB?") },
            text = {
                Text(
                    "\"$fileName\" is a MOBI/AZW3 file. We'll wire the real offline converter here right after the library & metadata editor!"
                )
            },
            confirmButton = {
                TextButton(onClick = { converterPromptFile = null }) {
                    Text("Got it")
                }
            }
        )
    }
}

private fun openInReader(context: Context, uriString: String) {
    val intent = Intent(context, ReaderActivity::class.java).apply {
        data = Uri.parse(uriString)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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

@Composable
fun BookGridTile(book: BookEntity, onClick: () -> Unit) {
    val context = LocalContext.current
    val pct = (book.progressPercent * 100).roundToInt()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(0.68f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            if (!book.coverPath.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(File(book.coverPath))
                        .crossfade(true)
                        .build(),
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
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
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
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
        Text(
            text = book.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = book.author,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailsBottomSheet(
    book: BookEntity,
    onDismiss: () -> Unit,
    onRead: () -> Unit,
    onConvertPrompt: () -> Unit,
    onRemoveFromLibrary: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pct = (book.progressPercent * 100).roundToInt()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
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
                    model = ImageRequest.Builder(context)
                        .data(File(book.coverPath))
                        .crossfade(true)
                        .build(),
                    contentDescription = book.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(210.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 140.dp, height = 190.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(book.format)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (book.format == "EPUB") {
                Button(
                    onClick = onRead,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (pct > 0) "Continue Reading ($pct%)" else "Read This Book")
                }
            } else {
                Button(
                    onClick = onConvertPrompt,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Convert ${book.format} to EPUB")
                }
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

            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onRemoveFromLibrary) {
                Text(
                    text = "Remove from Library (keeps file on phone)",
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
fun MetadataRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}
