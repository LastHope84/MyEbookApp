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
                LibraryScreen(themeMode, accent, amoledDark, incomingExternalUri, { incomingExternalUri = null },
                    { themeMode = it; ThemePrefs.saveMode(context, it) },
                    { accent = it; ThemePrefs.saveAccent(context, it) },
                    { amoledDark = it; ThemePrefs.saveAmoled(context, it) }
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
    var selectedBook by remember { mutableStateOf<BookEntity?>(null) }; var editingBook by remember { mutableStateOf<BookEntity?>(null) }
    var showSettings by remember { mutableStateOf(false) }; var statusMessage by remember { mutableStateOf<String?>(null) }; var convertPrompt by remember { mutableStateOf<PendingConvertTarget?>(null) }
    var busyTitle by remember { mutableStateOf<String?>(null) }; var busyDetail by remember { mutableStateOf("") }
    var autoOpenLastBook by remember { mutableStateOf(ThemePrefs.isAutoOpenLastBook(context)) }; var kindleEmail by remember { mutableStateOf(ThemePrefs.getKindleEmail(context)) }; var convertedOutputUri by remember { mutableStateOf(ThemePrefs.getConvertedOutputUri(context)) }
    var selectionModeActive by remember { mutableStateOf(false) }; var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBatchEditDialog by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) { val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION; runCatching { context.contentResolver.takePersistableUriPermission(u, rw) }; LibraryScanner.addFolder(context, u); LibraryScanner.scanFolders(context, listOf(u)) } }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) { val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION; runCatching { context.contentResolver.takePersistableUriPermission(u, rw) }; val name = EpubParser.getFileName(context, u); val ext = name.substringAfterLast('.').lowercase(); if (ext in setOf("mobi","azw3","azw","prc")) convertPrompt = PendingConvertTarget(u, name) else if (ext == "epub") scope.launch(Dispatchers.IO) { val m = EpubParser.parseEpub(context, u, name); val cov = LibraryScanner.saveWebpThumbnail(context, u.toString(), m.coverBitmap); dao.insertIgnore(BookEntity(uriString=u.toString(), fileName=name, format="EPUB", fileSize=0, lastModified=0, title=m.title, author=m.author, publishingDate=m.publishingDate, publisher=m.publisher, language=m.language, coverPath=cov, lastOpenedAt=System.currentTimeMillis(), metadataExtracted=true)) } } }

    fun toggleSelection(id: Long) { val next = if (selectedIds.contains(id)) selectedIds - id else selectedIds + id; selectedIds = next; if (next.isEmpty()) selectionModeActive = false }

    LaunchedEffect(incomingExternalUri) { val u = incomingExternalUri ?: return@LaunchedEffect; onConsumeExternalUri(); val name = EpubParser.getFileName(context, u); if (name.endsWith(".epub", true)) openInReader(context, u.toString()) }
    LaunchedEffect(Unit) { if (!MainActivity.didAutoOpenThisSession && autoOpenLastBook && incomingExternalUri == null) { MainActivity.didAutoOpenThisSession = true; val last = withContext(Dispatchers.IO) { dao.getLastOpenedOnce() }; if (last?.format == "EPUB") openInReader(context, last.uriString) } }
    LaunchedEffect(searchQuery) { delay(250); debouncedQuery = searchQuery }

    val pagedBooks = remember(debouncedQuery, activeTab, scope) { Pager(PagingConfig(60, 180, true)) { val q = toFtsQuery(debouncedQuery); when (activeTab) { LibraryTab.ALL -> if (q == null) dao.allPaged() else dao.searchAllPaged(q); LibraryTab.RECENT -> if (q == null) dao.recentPaged() else dao.searchRecentPaged(q); LibraryTab.TO_CONVERT -> dao.toConvertPaged() } }.flow.cachedIn(scope) }.collectAsLazyPagingItems()

    ModalNavigationDrawer(drawerState=drawerState, gesturesEnabled=!(selectionModeActive || selectedIds.isNotEmpty()), drawerContent={
        ModalDrawerSheet(Modifier.width(300.dp)) {
            Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("My Ebook Library", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold); Text("$totalCount books", style=MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(value=searchQuery, onValueChange={searchQuery=it}, placeholder={Text("Search...")}, singleLine=true, modifier=Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp)); NavigationDrawerItem(label={Text("All Books")}, selected=activeTab==LibraryTab.ALL, onClick={activeTab=LibraryTab.ALL; scope.launch{drawerState.close()}})
                NavigationDrawerItem(label={Text("Recently Read")}, selected=activeTab==LibraryTab.RECENT, onClick={activeTab=LibraryTab.RECENT; scope.launch{drawerState.close()}})
                NavigationDrawerItem(label={Text("To Convert")}, selected=activeTab==LibraryTab.TO_CONVERT, onClick={activeTab=LibraryTab.TO_CONVERT; scope.launch{drawerState.close()}})
                Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                NavigationDrawerItem(label={Text("Add Folder")}, selected=false, onClick={scope.launch{drawerState.close()}; folderPicker.launch(null)})
                NavigationDrawerItem(label={Text("Settings")}, selected=false, onClick={scope.launch{drawerState.close()}; showSettings=true})
            }
        }
    }) {
        Scaffold(topBar={ TopAppBar(navigationIcon={ IconButton(onClick={scope.launch{drawerState.open()}}){HamburgerIcon()} }, title={ Text(activeTab.title) }) }) { p ->
            Column(Modifier.fillMaxSize().padding(p).padding(horizontal=12.dp)) {
                if (scanStatus.isScanning) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(scanStatus.phaseLabel, style=MaterialTheme.typography.labelSmall) }
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize()) {
                    items(pagedBooks.itemCount, key=pagedBooks.itemKey{it.id}) { i ->
                        val b = pagedBooks[i]; if (b != null) BookGridTile(b, selectionModeActive, selectedIds.contains(b.id), { if (selectionModeActive) toggleSelection(b.id) else selectedBook = b }, { selectionModeActive=true; toggleSelection(b.id) }) else BookPlaceholderTile()
                    }
                }
            }
        }
    }
    if (showSettings) AppSettingsBottomSheet(themeMode, accent, amoledDark, autoOpenLastBook, kindleEmail, convertedOutputUri, onThemeModeChange, onAccentChange, onAmoledChange, {autoOpenLastBook=it; ThemePrefs.saveAutoOpenLastBook(context,it)}, {kindleEmail=it; ThemePrefs.saveKindleEmail(context,it)}, {}, {}, {}, {showSettings=false})
    selectedBook?.let { b -> BookDetailsBottomSheet(b, kindleEmail.isNotBlank(), {selectedBook=null}, {selectedBook=null; openInReader(context, b.uriString)}, {selectedBook=null; editingBook=b}, {selectedBook=null; val i = FileOpener.prepareShareIntent(context, listOf(b)); context.startActivity(i)}, {}, {}, {scope.launch(Dispatchers.IO){dao.deleteById(b.id)}; selectedBook=null}) }
}
