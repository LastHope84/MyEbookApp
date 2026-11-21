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

enum class LibraryTab(val title: String) { ALL("My Library"), RECENT("Recently Read"), TO_CONVERT("To Convert") }
data class PendingConvertTarget(val uri: Uri, val fileName: String, val existingBookId: Long? = null)

class MainActivity : ComponentActivity() {
    companion object { var didAutoOpenThisSession = false }
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
                    themeMode = themeMode, accent = accent, amoledDark = amoledDark,
                    incomingExternalUri = incomingExternalUri,
                    onConsumeExternalUri = { incomingExternalUri = null },
                    onThemeModeChange = { themeMode = it; ThemePrefs.saveMode(context, it) },
                    onAccentChange = { accent = it; ThemePrefs.saveAccent(context, it) },
                    onAmoledChange = { amoledDark = it; ThemePrefs.saveAmoled(context, it) }
                )
            }
        }
    }
    override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handleIncomingIntent(i) }
    private fun handleIncomingIntent(i: Intent?) { if (i?.action == Intent.ACTION_VIEW && i.data != null) { incomingExternalUri = i.data; didAutoOpenThisSession = true } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(themeMode: AppThemeMode, accent: AppAccent, amoledDark: Boolean, incomingExternalUri: Uri?, onConsumeExternalUri: () -> Unit, onThemeModeChange: (AppThemeMode) -> Unit, onAccentChange: (AppAccent) -> Unit, onAmoledChange: (Boolean) -> Unit) {
    val context = LocalContext.current; val scope = rememberCoroutineScope(); val dao = remember { AppDatabase.getInstance(context).bookDao() }
    val drawerState = rememberDrawerState(DrawerValue.Closed); val totalCount by dao.observeTotalCount().collectAsState(0)
    val pendingMetadataCount by dao.observePendingMetadataCount().collectAsState(0); val toConvertCount by dao.observeToConvertCount().collectAsState(0)
    val lastOpened by dao.observeLastOpened().collectAsState(null); val scanStatus by LibraryScanner.status.collectAsState()
    var activeTab by remember { mutableStateOf(LibraryTab.ALL) }; var searchQuery by remember { mutableStateOf("") }; var debouncedQuery by remember { mutableStateOf("") }
    var isSearchFocused by remember { mutableStateOf(false) }; var selectedBook by remember { mutableStateOf<BookEntity?>(null) }; var editingBook by remember { mutableStateOf<BookEntity?>(null) }
    var showSettings by remember { mutableStateOf(false) }; var statusMessage by remember { mutableStateOf<String?>(null) }; var convertPrompt by remember { mutableStateOf<PendingConvertTarget?>(null) }
    var busyTitle by remember { mutableStateOf<String?>(null) }; var busyDetail by remember { mutableStateOf("") }; var savedFoldersCount by remember { mutableIntStateOf(LibraryScanner.getSavedFolders(context).size) }
    var autoOpenLastBook by remember { mutableStateOf(ThemePrefs.isAutoOpenLastBook(context)) }; var kindleEmail by remember { mutableStateOf(ThemePrefs.getKindleEmail(context)) }; var convertedOutputUri by remember { mutableStateOf(ThemePrefs.getConvertedOutputUri(context)) }
    var selectionModeActive by remember { mutableStateOf(false) }; var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var selectionMoreMenu by remember { mutableStateOf(false) }; var showBatchRemoveConfirm by remember { mutableStateOf(false) }; var showBatchEditDialog by remember { mutableStateOf(false) }
    val inSelectionMode = selectionModeActive || selectedIds.isNotEmpty()

    BackHandler(drawerState.isOpen || inSelectionMode) { if (drawerState.isOpen) scope.launch { drawerState.close() } else { selectedIds = emptySet(); selectionModeActive = false } }
    fun toggleSelection(id: Long) { val next = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id; selectedIds = next; if (next.isEmpty()) selectionModeActive = false }

    fun importOrOpenSingleUri(uri: Uri, open: Boolean) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, rw) }
        val name = EpubParser.getFileName(context, uri); val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in setOf("mobi","azw3","azw","prc")) { convertPrompt = PendingConvertTarget(uri, name) }
        else if (ext == "epub") {
            statusMessage = "Importing $name..."; scope.launch(Dispatchers.IO) {
                val res = runCatching {
                    val m = EpubParser.parseEpub(context, uri, name); val cov = LibraryScanner.saveWebpThumbnail(context, uri.toString(), m.coverBitmap)
                    val ex = dao.getByUri(uri.toString())
                    if (ex == null) dao.insertIgnore(BookEntity(uriString=uri.toString(), fileName=name, format="EPUB", fileSize=0, lastModified=0, title=m.title, author=m.author, publishingDate=m.publishingDate, publisher=m.publisher, language=m.language, coverPath=cov, lastLocatorJson=null, progressPercent=0.0, lastOpenedAt=System.currentTimeMillis(), metadataExtracted=true))
                    else dao.updateExtractedMetadata(ex.id, m.title, m.author, m.publishingDate, m.publisher, m.language, cov, null, null, null)
                    dao.getByUri(uri.toString())
                }
                withContext(Dispatchers.Main) { res.onSuccess { statusMessage = null; if (open && it != null) openInReader(context, it.uriString) else selectedBook = it }.onFailure { statusMessage = it.localizedMessage } }
            }
        }
    }

    fun runSingleMetadataSave(book: BookEntity, req: EpubEditRequest, write: Boolean) {
        editingBook = null; selectedBook = null; busyTitle = "Saving..."; busyDetail = req.title; statusMessage = null
        scope.launch(Dispatchers.IO) {
            val cov = if (req.removeCover) { book.coverPath?.let { File(it).delete() }; dao.clearCover(book.id); null } else { req.newCoverBitmap?.let { LibraryScanner.saveWebpThumbnail(context, book.uriString, it) } }
            dao.updateExtractedMetadata(book.id, req.title, req.author, req.publishingDate, req.publisher, req.language, cov, null, null, null)
            val ws = if (write && book.format == "EPUB") runCatching { EpubMetadataEditor.writeMetadataToEpub(context, Uri.parse(book.uriString), req) }.getOrElse { EpubWriteResult.ReadOnlyFallback("Error writing file") } else null
            val ref = dao.getByUri(book.uriString)
            withContext(Dispatchers.Main) { busyTitle = null; statusMessage = if (ws is EpubWriteResult.WrittenToFile) "Saved to file!" else if (ws is EpubWriteResult.ReadOnlyFallback) ws.reason else "Saved to library!"; selectedBook = ref }
        }
    }

    fun runBatchConversion(targets: List<PendingConvertTarget>, open: Boolean) {
        convertPrompt = null; selectedBook = null; selectedIds = emptySet(); selectionModeActive = false; busyTitle = "Converting..."; statusMessage = null
        scope.launch(Dispatchers.IO) {
            var ok = 0; var last: BookEntity? = null; val custom = convertedOutputUri?.let { Uri.parse(it) }
            targets.forEachIndexed { i, t ->
                withContext(Dispatchers.Main) { busyDetail = "(${i+1}/${targets.size}) ${t.fileName}" }
                runCatching {
                    val out = MobiConverter.convertToEpubOutput(context, t.uri, t.fileName, custom)
                    val m = EpubParser.parseEpub(context, out.uri, out.fileName); val cov = LibraryScanner.saveWebpThumbnail(context, out.uri.toString(), m.coverBitmap)
                    dao.insertIgnore(BookEntity(uriString=out.uri.toString(), fileName=out.fileName, format="EPUB", fileSize=out.size, lastModified=out.lastModified, title=m.title, author=m.author, publishingDate=m.publishingDate, publisher=m.publisher, language=m.language, coverPath=cov, lastLocatorJson=null, progressPercent=0.0, lastOpenedAt=System.currentTimeMillis(), metadataExtracted=true))
                    t.existingBookId?.let { dao.deleteById(it) }; last = dao.getByUri(out.uri.toString()); ok++
                }
            }
            withContext(Dispatchers.Main) { busyTitle = null; statusMessage = "Converted $ok books!"; if (open && last != null) selectedBook = last }
        }
    }

    LaunchedEffect(incomingExternalUri) { val u = incomingExternalUri ?: return@LaunchedEffect; onConsumeExternalUri(); importOrOpenSingleUri(u, true) }
    LaunchedEffect(Unit) {
        if (!didAutoOpenThisSession && autoOpenLastBook && incomingExternalUri == null) {
            didAutoOpenThisSession = true; val last = withContext(Dispatchers.IO) { dao.getLastOpenedOnce() }
            if (last?.format == "EPUB") openInReader(context, last.uriString)
        }
    }
    LaunchedEffect(searchQuery) { delay(250); debouncedQuery = searchQuery }

    val booksFlow = remember(debouncedQuery, activeTab, scope) {
        Pager(PagingConfig(60, 180, true)) {
            val q = toFtsQuery(debouncedQuery)
            when (activeTab) {
                LibraryTab.ALL -> if (q == null) dao.allPaged() else dao.searchAllPaged(q)
                LibraryTab.RECENT -> if (q == null) dao.recentPaged() else dao.searchRecentPaged(q)
                LibraryTab.TO_CONVERT -> if (q == null) dao.toConvertPaged() else dao.searchToConvertPaged(q)
            }
        }.flow.cachedIn(scope)
    }
    val pagedBooks = booksFlow.collectAsLazyPagingItems()

    ModalNavigationDrawer(drawerState=drawerState, gesturesEnabled=!inSelectionMode, drawerContent={
        ModalDrawerSheet(Modifier.width(300.dp)) {
            Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("My Ebook Library", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold); Text("$totalCount books", style=MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(value=searchQuery, onValueChange={searchQuery=it}, placeholder={Text("Search...")}, singleLine=true, keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search), keyboardActions=KeyboardActions(onSearch={scope.launch{drawerState.close()}}), modifier=Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp)); NavigationDrawerItem(label={Text("All Books")}, selected=activeTab==LibraryTab.ALL, onClick={activeTab=LibraryTab.ALL; scope.launch{drawerState.close()}})
                NavigationDrawerItem(label={Text("Recently Read")}, selected=activeTab==LibraryTab.RECENT, onClick={activeTab=LibraryTab.RECENT; scope.launch{drawerState.close()}})
                NavigationDrawerItem(label={Text("To Convert")}, selected=activeTab==LibraryTab.TO_CONVERT, onClick={activeTab=LibraryTab.TO_CONVERT; scope.launch{drawerState.close()}})
                Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                NavigationDrawerItem(label={Text("Add Folder")}, selected=false, onClick={scope.launch{drawerState.close()}; scope.launch{val p = ActivityResultContracts.OpenDocumentTree(); /* folderPicker is handled via launcher below */ } })
                NavigationDrawerItem(label={Text("Settings")}, selected=false, onClick={scope.launch{drawerState.close()}; showSettings=true})
            }
        }
    }) {
        Scaffold(topBar={
            TopAppBar(
                navigationIcon={ IconButton(onClick={scope.launch{drawerState.open()}}){HamburgerIcon()} },
                title={ Text(if (inSelectionMode) "${selectedIds.size} selected" else activeTab.title) },
                actions={
                    if (inSelectionMode) {
                        TextButton(onClick={showBatchEditDialog=true}){Text("Edit")}
                        TextButton(onClick={val ids=selectedIds.toList(); scope.launch(Dispatchers.IO){val b=ids.chunked(500).flatMap{dao.getByIds(it)}; withContext(Dispatchers.Main){FileOpener.prepareShareIntent(context,b)}}}){Text("Share")}
                    } else if (searchQuery.isNotEmpty()) { TextButton(onClick={searchQuery=""}){Text("Clear")} }
                }
            )
        }) { p ->
            Column(Modifier.fillMaxSize().padding(p).padding(horizontal=12.dp)) {
                if (scanStatus.isScanning) {
                    Card(colors=CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer), modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically, horizontalArrangement=Arrangement.SpaceBetween) {
                                Text(scanStatus.phaseLabel, style=MaterialTheme.typography.labelLarge); TextButton(onClick={LibraryScanner.stopScan()}){Text("Cancel")}
                            }
                            if (!scanStatus.isPaused) LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                    }
                }
                LazyVerticalGrid(GridCells.Fixed(3), horizontalArrangement=Arrangement.spacedBy(10.dp), verticalArrangement=Arrangement.spacedBy(12.dp), modifier=Modifier.fillMaxSize()) {
                    items(pagedBooks.itemCount, key=pagedBooks.itemKey{it.id}) { i ->
                        val b = pagedBooks[i]; if (b != null) BookGridTile(b, inSelectionMode, selectedIds.contains(b.id), { if (inSelectionMode) toggleSelection(b.id) else selectedBook = b }, { selectionModeActive=true; toggleSelection(b.id) }) else BookPlaceholderTile()
                    }
                }
            }
        }
    }

    if (showSettings) {
        AppSettingsBottomSheet(themeMode, accent, amoledDark, autoOpenLastBook, kindleEmail, convertedOutputUri, onThemeModeChange, onAccentChange, onAmoledChange, {autoOpenLastBook=it; ThemePrefs.saveAutoOpenLastBook(context,it)}, {kindleEmail=it; ThemePrefs.saveKindleEmail(context,it)}, {}, {}, {}, {showSettings=false})
    }
    selectedBook?.let { b -> BookDetailsBottomSheet(b, kindleEmail.isNotBlank(), {selectedBook=null}, {selectedBook=null; openInReader(context, b.uriString)}, {selectedBook=null; editingBook=b}, {}, {}, {runBatchConversion(listOf(PendingConvertTarget(Uri.parse(b.uriString),b.fileName,b.id)),true)}, {scope.launch(Dispatchers.IO){dao.deleteById(b.id)}; selectedBook=null}) }
}
