package com.example.myebookapp
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.paging.*
import androidx.paging.compose.*
import kotlinx.coroutines.*
import java.io.File

enum class LibraryTab(val title: String) { ALL("All Books"), RECENT("Recent"), FAV("Favorites"), READING("Reading"), TBR("To Read"), DONE("Finished"), CONV("To Convert") }
data class PendingConvertTarget(val uri: Uri, val fileName: String, val existingBookId: Long? = null)
data class ConversionFailure(val book: BookEntity, val reason: String)

class MainActivity : ComponentActivity() {
companion object { var didAutoOpen = false }
override fun onCreate(s: Bundle?) { super.onCreate(s); handleI(intent)
setContent {
val c = LocalContext.current; var m by remember { mutableStateOf(ThemePrefs.getMode(c)) }
var a by remember { mutableStateOf(ThemePrefs.getAccent(c)) }; var am by remember { mutableStateOf(ThemePrefs.isAmoled(c)) }
MyEbookTheme(mode=m, accent=a, amoledDark=am) {
LibraryScreen(m, a, am, { m=it; ThemePrefs.saveMode(c,it) }, { a=it; ThemePrefs.saveAccent(c,it) }, { am=it; ThemePrefs.saveAmoled(c,it) })
}
}
}
override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handleI(i) }

private fun handleI(i: Intent?) {
    val uri = when (i?.action) {
        Intent.ACTION_VIEW -> i.data
        Intent.ACTION_SEND -> sentFile(i)
        else -> null
    } ?: return
    didAutoOpen = true
    lifecycleScope.launch {
        try {
            val book = withContext(Dispatchers.IO) { ExternalImport.import(applicationContext, uri) }
            when (book.format) {
                "EPUB", "CBZ", "CBR", "PDF" -> openInReader(this@MainActivity, book)
                else -> Toast.makeText(
                    this@MainActivity,
                    "${book.title} added to your library.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Toast.makeText(
                this@MainActivity,
                e.localizedMessage ?: "Could not open the file.",
                Toast.LENGTH_LONG
            ).show()
        }
    }
}

@Suppress("DEPRECATION")
private fun sentFile(i: Intent): Uri? =
    if (android.os.Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    else i.getParcelableExtra(Intent.EXTRA_STREAM)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(tm: AppThemeMode, acc: AppAccent, am: Boolean, onT: (AppThemeMode)->Unit, onA: (AppAccent)->Unit, onAm: (Boolean)->Unit) {
val ctx = LocalContext.current; val scp = rememberCoroutineScope(); val dao = remember { AppDatabase.getInstance(ctx).bookDao() }
val visibilityPrefs = remember { ctx.getSharedPreferences("library_visibility", Context.MODE_PRIVATE) }
var showConvertedOriginals by remember { mutableStateOf(visibilityPrefs.getBoolean("show_converted_originals", false)) }
val drw = rememberDrawerState(DrawerValue.Closed); val total by remember(showConvertedOriginals) { dao.observeTotalCount(showConvertedOriginals) }.collectAsState(0)
val toConv by remember(showConvertedOriginals) { dao.observeToConvertCount(showConvertedOriginals) }.collectAsState(0); val scanS by LibraryScanner.status.collectAsState()
val favoriteCount by remember(showConvertedOriginals) { dao.observeFavoriteCount(showConvertedOriginals) }.collectAsState(0)
val readingCount by remember(showConvertedOriginals) { dao.observeStatusCount("READING", showConvertedOriginals) }.collectAsState(0)
val toReadCount by remember(showConvertedOriginals) { dao.observeStatusCount("TBR", showConvertedOriginals) }.collectAsState(0)
val finishedCount by remember(showConvertedOriginals) { dao.observeStatusCount("FINISHED", showConvertedOriginals) }.collectAsState(0)
var tab by remember { mutableStateOf(LibraryTab.ALL) }; var qry by remember { mutableStateOf("") }; var dbQry by remember { mutableStateOf("") }
var selB by remember { mutableStateOf<BookEntity?>(null) }; var edtB by remember { mutableStateOf<BookEntity?>(null) }
var deleteFileBook by remember { mutableStateOf<BookEntity?>(null) }
var showSet by remember { mutableStateOf(false) }; var showVisibility by remember { mutableStateOf(false) }; var showFolders by remember { mutableStateOf(false) }; var showGuide by rememberSaveable { mutableStateOf(false) }; var selMode by remember { mutableStateOf(false) }; var selIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
var showBulkActions by remember { mutableStateOf(false) }
var showScanLog by remember { mutableStateOf(false) }
var showBulkMove by remember { mutableStateOf(false) }
var showBulkShelves by remember { mutableStateOf(false) }
var bulkFavoriteAction by remember { mutableStateOf<Boolean?>(null) }
var selectedFavoriteCount by remember { mutableStateOf<Int?>(null) }
var showBulkShareConfirm by remember { mutableStateOf(false) }
var preparingBulkShare by remember { mutableStateOf(false) }
var sortMode by remember { mutableStateOf(ThemePrefs.getSort(ctx)) }
var showSortMenu by remember { mutableStateOf(false) }
var listMode by remember { mutableStateOf(ThemePrefs.getLibraryView(ctx) == "list") }
var bulkSaving by remember { mutableStateOf(false) }
var selectingAll by remember { mutableStateOf(false) }
var selectAllJob by remember { mutableStateOf<Job?>(null) }
var selectAllGeneration by remember { mutableIntStateOf(0) }
fun cancelSelectAll() {
    selectAllGeneration++
    selectAllJob?.cancel()
    selectAllJob = null
    selectingAll = false
}
val customShelves by dao.observeCustomShelves().collectAsState(initial = emptyList())
val customShelvesWithCounts by remember(showConvertedOriginals) { dao.observeCustomShelvesWithCounts(showConvertedOriginals) }.collectAsState(initial = emptyList())
var selectedCustomId by remember { mutableStateOf<Long?>(null) }
var showCreateShelf by remember { mutableStateOf(false) }
var shelfToDelete by remember { mutableStateOf<CustomShelf?>(null) }
var membershipBook by remember { mutableStateOf<BookEntity?>(null) }
var customSaving by remember { mutableStateOf(false) }
var shelfSaving by remember { mutableStateOf(false) }
fun updateShelf(bookId: Long, favorite: Boolean? = null, status: String? = null) {
if (shelfSaving) return
if ((favorite == null) == (status == null)) return
if (status != null && status !in setOf("NEW", "TBR", "READING", "FINISHED")) return
shelfSaving = true
scp.launch {
try {
val count = withContext(Dispatchers.IO) {
if (favorite != null) dao.setFavorite(bookId, favorite)
else dao.setReadingStatus(bookId, status!!)
}
check(count == 1) { "Book is no longer in the library." }
} catch (e: CancellationException) {
throw e
} catch (e: Exception) {
Toast.makeText(ctx, "Could not update shelf: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
} finally { shelfSaving = false }
}
}
var translatingBook by remember { mutableStateOf<BookEntity?>(null) }
var translationJob by remember { mutableStateOf<Job?>(null) }
var translationProgress by remember { mutableStateOf<String?>(null) }
var translationError by remember { mutableStateOf<String?>(null) }
var translatingSource by remember { mutableStateOf("") }
var translatingTarget by remember { mutableStateOf("") }
var converting by remember { mutableStateOf(false) }
var bulkConversionFailures by remember { mutableStateOf<List<ConversionFailure>>(emptyList()) }
var preparingKindle by remember { mutableStateOf(false) }
val folders = LibraryScanner.getSavedFolders(ctx)
LaunchedEffect(Unit) {
    LibraryScanner.restore(ctx)
    if (ThemePrefs.isAutoScanOnOpen(ctx)) {
        val folders = LibraryScanner.getSavedFolders(ctx)
        val now = System.currentTimeMillis()
        if (folders.isNotEmpty() && now - ThemePrefs.lastAutoScan(ctx) > 24L * 60L * 60L * 1000L) {
            ThemePrefs.saveLastAutoScan(ctx, now)
            LibraryScanner.scanFolders(ctx, folders.map(Uri::parse))
        }
    }
}
fun sendToKindle(book: BookEntity) {
if (preparingKindle) return
val email = ThemePrefs.getKindleEmail(ctx)
if (email.isBlank()) {
Toast.makeText(ctx, "Add your Kindle email in Settings first.", Toast.LENGTH_LONG).show()
return
}
preparingKindle = true
scp.launch {
try {
val intent = withContext(Dispatchers.IO) {
FileOpener.prepareKindleEmailIntent(ctx, listOf(book), email)
}
ctx.startActivity(intent)
} catch (e: CancellationException) {
throw e
} catch (e: Exception) {
Toast.makeText(ctx, "Could not prepare email: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
} finally {
preparingKindle = false
}
}
}

fun convertBooks(books: List<BookEntity>, showConverted: Boolean) {
if (converting || books.isEmpty()) return
// A conversion can hide an original; never leave an invisible book selected.
cancelSelectAll(); selIds = emptySet(); selMode = false
showBulkActions = false; showBulkMove = false; showBulkShelves = false
bulkFavoriteAction = null; showBulkShareConfirm = false
converting = true
if (showConverted) selB = null
Toast.makeText(ctx, if (showConverted) "Converting ${books.first().fileName}..." else "Converting ${books.size} book${if (books.size == 1) "" else "s"}...", Toast.LENGTH_SHORT).show()
scp.launch {
var convertedCount = 0
val failures = mutableListOf<ConversionFailure>()
try {
for (book in books) {
    try {
        val (converted, savedToCustom) = withContext(Dispatchers.IO) {
            val folder = ThemePrefs.getConvertedOutputUri(ctx)?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
            val out = MobiConverter.convertToEpubOutput(ctx, Uri.parse(book.uriString), book.fileName, folder)
            val meta = EpubParser.parseEpub(ctx, out.uri, out.fileName)
            // A unique cover key cannot overwrite a cover belonging to an existing output.
            val cover = LibraryScanner.saveWebpThumbnail(ctx, out.uri.toString() + "|conversion|" + java.util.UUID.randomUUID(), meta.coverBitmap)
            val entry = BookEntity(uriString = out.uri.toString(), fileName = out.fileName, format = "EPUB", fileSize = out.size, lastModified = out.lastModified, title = meta.title, author = meta.author, publishingDate = meta.publishingDate, publisher = meta.publisher, language = meta.language, coverPath = cover, lastLocatorJson = null, progressPercent = 0.0, lastOpenedAt = 0L, metadataExtracted = true, series = meta.series, seriesIndex = meta.seriesIndex, tags = meta.tags)
            val converted = try {
                // The DAO resolves the exact URIs and commits indexing, shelf state and link together.
                dao.indexConvertedOutput(book.id, book.uriString, entry)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                throw IllegalStateException("EPUB was created, but could not be indexed and linked; original visibility was not changed.", e)
            }
            converted to out.savedToCustomFolder
        }
        convertedCount++
        if (showConverted) {
            selB = converted
            val location = if (savedToCustom) "chosen folder" else "app storage"
            Toast.makeText(ctx, "Converted to EPUB and saved in $location. Original kept.", Toast.LENGTH_LONG).show()
        }
    } catch (e: CancellationException) { throw e
    } catch (e: Exception) {
        val reason = e.localizedMessage ?: "unknown error"
        if (showConverted) { selB = book; Toast.makeText(ctx, "Conversion failed: $reason", Toast.LENGTH_LONG).show() }
        else failures.add(ConversionFailure(book, reason))
    } catch (e: LinkageError) {
        if (showConverted) { selB = book; Toast.makeText(ctx, "Conversion engine could not be loaded.", Toast.LENGTH_LONG).show() }
        else failures.add(ConversionFailure(book, "Conversion engine could not be loaded."))
    }
}
if (!showConverted) {
    bulkConversionFailures = failures.toList()
    Toast.makeText(ctx, "Converted $convertedCount book${if (convertedCount == 1) "" else "s"}. Original files kept.", Toast.LENGTH_LONG).show()
}
} finally { converting = false }
}
}
fun convertBook(book: BookEntity) = convertBooks(listOf(book), true)

suspend fun idsOnShelf(tab: LibraryTab, custom: Long?, f: String?): List<Long> {
    return when {
        custom != null -> if (f == null) dao.getCustomShelfBookIds(custom, showConvertedOriginals) else dao.searchCustomShelfIds(custom, f, showConvertedOriginals)
        tab == LibraryTab.ALL -> if (f == null) dao.getAllIds(showConvertedOriginals) else dao.searchAllIds(f, showConvertedOriginals)
        tab == LibraryTab.RECENT -> if (f == null) dao.getRecentlyAddedIds(showConvertedOriginals) else dao.searchRecentlyAddedIds(f, showConvertedOriginals)
        tab == LibraryTab.FAV -> if (f == null) dao.getFavoriteIds(showConvertedOriginals) else dao.searchFavoriteIds(f, showConvertedOriginals)
        tab in listOf(LibraryTab.READING, LibraryTab.TBR, LibraryTab.DONE) -> {
            val status = when (tab) { LibraryTab.READING -> "READING"; LibraryTab.TBR -> "TBR"; else -> "FINISHED" }
            if (f == null) dao.getStatusIds(status, showConvertedOriginals) else dao.searchStatusIds(status, f, showConvertedOriginals)
        }
        else -> if (f == null) dao.getToConvertIds(showConvertedOriginals) else dao.searchToConvertIds(f, showConvertedOriginals)
    }
}

fun selectAllOnShelf() {
    cancelSelectAll()
    val generation = selectAllGeneration
    val selectedTab = tab
    val custom = selectedCustomId
    val search = toFtsQuery(dbQry)
    selectingAll = true
    selectAllJob = scp.launch {
        try {
            val ids = withContext(Dispatchers.IO) { idsOnShelf(selectedTab, custom, search) }
            if (generation == selectAllGeneration && selMode) selIds = ids.toSet()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (generation == selectAllGeneration) Toast.makeText(ctx, "Could not select books on this shelf.", Toast.LENGTH_LONG).show()
        } finally {
            if (generation == selectAllGeneration) { selectingAll = false; selectAllJob = null }
        }
    }
}

var savingMetadata by remember { mutableStateOf(false) }
fun runSave(b: BookEntity, r: EpubEditRequest) {
if (savingMetadata) return
savingMetadata = true
scp.launch {
try {
val updated = withContext(Dispatchers.IO) {
val changeCover = r.removeCover || r.newCoverBitmap != null
val cover = r.newCoverBitmap?.let {
    LibraryScanner.saveWebpThumbnail(ctx, b.uriString + "|edit|" + java.util.UUID.randomUUID(), it)
        ?: error("Could not save the cover.")
}
val exportCover = r.newCoverBitmap?.let {
    LibraryScanner.saveExportCover(ctx, it) ?: error("Could not save the cover.")
}
val previousExportCover = b.exportCoverPath
val count = dao.saveEditedMetadata(b.id, r.title, r.author, r.publishingDate, r.publisher, r.language, changeCover, cover, exportCover, r.series, r.seriesIndex, r.tags)
check(count == 1) { "Book is no longer in the library." }
if (changeCover && previousExportCover != null && previousExportCover != exportCover) {
    runCatching { java.io.File(previousExportCover).delete() }
}
dao.getByUri(b.uriString)
}
edtB = null
selB = updated
Toast.makeText(ctx, "Saved in the app. Original file unchanged.", Toast.LENGTH_LONG).show()
} catch (e: CancellationException) {
throw e
} catch (e: Exception) {
Toast.makeText(ctx, "Could not save: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
} finally {
savingMetadata = false
}
}
}

// Save values rather than closures so the permission result can survive recreation.
var pendingScanAction by rememberSaveable { mutableStateOf<String?>(null) }
var pendingScanFolder by rememberSaveable { mutableStateOf<String?>(null) }
fun runScanAction(action: String, folder: String?) {
    when (action) {
        "folder" -> if (folder != null) LibraryScanner.scanFolders(ctx, listOf(Uri.parse(folder)))
        "rescan" -> LibraryScanner.scanFolders(ctx, LibraryScanner.getSavedFolders(ctx).map { Uri.parse(it) })
        "resume" -> LibraryScanner.resumeScan(ctx)
    }
}
val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
    if (!granted) {
        Toast.makeText(
            ctx,
            "Allow notifications for MyEbookApp, or Android may stop the scan in the background.",
            Toast.LENGTH_LONG
        ).show()
    }
    val action = pendingScanAction
    val folder = pendingScanFolder
    pendingScanAction = null
    pendingScanFolder = null
    if (action != null) runScanAction(action, folder)
}
fun requestScan(action: String, folder: String? = null) {
    if (android.os.Build.VERSION.SDK_INT >= 33 &&
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
    ) {
        pendingScanAction = action
        pendingScanFolder = folder
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    } else runScanAction(action, folder)
}
val folderP = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
    if (u != null) {
        try {
            ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            LibraryScanner.addFolder(ctx, u)
            requestScan("folder", u.toString())
        } catch (e: Exception) {
            Toast.makeText(ctx, "Could not save folder permission: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
        }
    }
}
var importingFiles by remember { mutableStateOf(false) }
val fileP = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
    if (uris.isNullOrEmpty() || importingFiles) return@rememberLauncherForActivityResult
    importingFiles = true
    scp.launch {
        val added = mutableListOf<BookEntity>()
        var failed = 0
        try {
            for (uri in uris) {
                try {
                    added += withContext(Dispatchers.IO) { ExternalImport.import(ctx, uri) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed++
                }
            }
            when {
                added.isEmpty() -> Toast.makeText(ctx, "No files could be added.", Toast.LENGTH_LONG).show()
                else -> {
                    Toast.makeText(
                        ctx,
                        "Added ${added.size} file${if (added.size == 1) "" else "s"}${if (failed > 0) "; $failed failed" else ""}.",
                        Toast.LENGTH_LONG
                    ).show()
                    if (added.size == 1) {
                        val book = added.first()
                        if (book.format in setOf("EPUB", "CBZ", "CBR", "PDF")) openInReader(ctx, book) else selB = book
                    }
                }
            }
        } finally {
            importingFiles = false
        }
    }
}
if (showFolders) {
    FolderManagementScreen(onClose = { showFolders = false })
    return
}
if (showGuide) {
    UserGuideScreen(onClose = { showGuide = false })
    return
}
BackHandler(drw.isOpen || selMode) { if (!bulkSaving) { if (drw.isOpen) scp.launch { drw.close() } else { cancelSelectAll(); selIds = emptySet(); selMode = false } } }
LaunchedEffect(qry) { delay(250); if (dbQry != qry) { cancelSelectAll(); dbQry = qry } }

val customId = selectedCustomId
val paged = remember(dbQry, tab, customId, showConvertedOriginals, sortMode, scp) { Pager(PagingConfig(60, 180, true)) {
val f = toFtsQuery(dbQry)
if (customId != null) {
if (f == null) dao.customShelfPaged(customId, showConvertedOriginals, sortMode) else dao.searchCustomShelfPaged(customId, f, showConvertedOriginals, sortMode)
} else when (tab) {
LibraryTab.ALL -> if (f == null) dao.allPaged(showConvertedOriginals, sortMode) else dao.searchAllPaged(f, showConvertedOriginals, sortMode)
LibraryTab.RECENT -> if (f == null) dao.recentPaged(showConvertedOriginals) else dao.searchRecentPaged(f, showConvertedOriginals)
LibraryTab.FAV -> if (f == null) dao.favoritePaged(showConvertedOriginals, sortMode) else dao.searchFavoritePaged(f, showConvertedOriginals, sortMode)
LibraryTab.READING -> if (f == null) dao.statusPaged("READING", showConvertedOriginals, sortMode) else dao.searchStatusPaged("READING", f, showConvertedOriginals, sortMode)
LibraryTab.TBR -> if (f == null) dao.statusPaged("TBR", showConvertedOriginals, sortMode) else dao.searchStatusPaged("TBR", f, showConvertedOriginals, sortMode)
LibraryTab.DONE -> if (f == null) dao.statusPaged("FINISHED", showConvertedOriginals, sortMode) else dao.searchStatusPaged("FINISHED", f, showConvertedOriginals, sortMode)
LibraryTab.CONV -> if (f == null) dao.toConvertPaged(showConvertedOriginals, sortMode) else dao.searchToConvertPaged(f, showConvertedOriginals, sortMode)
}
}.flow.cachedIn(scp) }.collectAsLazyPagingItems()

ModalNavigationDrawer(drawerState=drw, gesturesEnabled=!selMode, drawerContent={
ModalDrawerSheet(Modifier.width(300.dp)) {
Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
Text("My Ebook Library", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold); Text("$total books", style=MaterialTheme.typography.labelMedium)
Spacer(Modifier.height(12.dp)); OutlinedTextField(value=qry, onValueChange={cancelSelectAll(); qry=it}, placeholder={Text("Search...")}, singleLine=true, modifier=Modifier.fillMaxWidth())
Spacer(Modifier.height(12.dp)); LibraryTab.entries.forEach { t ->
    val count = when (t) { LibraryTab.ALL -> total; LibraryTab.RECENT -> minOf(total, 50); LibraryTab.FAV -> favoriteCount; LibraryTab.READING -> readingCount; LibraryTab.TBR -> toReadCount; LibraryTab.DONE -> finishedCount; LibraryTab.CONV -> toConv }
    NavigationDrawerItem(label={Text(t.title)}, badge={Text("$count")}, selected=selectedCustomId == null && tab==t, onClick={cancelSelectAll(); selectedCustomId=null; tab=t; selIds=emptySet(); selMode=false; scp.launch{drw.close()}})
}
Spacer(Modifier.height(8.dp)); HorizontalDivider()
Text("Custom shelves", style=MaterialTheme.typography.titleSmall, modifier=Modifier.padding(top=12.dp, bottom=4.dp))
customShelvesWithCounts.forEach { shelf ->
    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
        NavigationDrawerItem(label={Text(shelf.name, maxLines=1)}, badge={Text("${shelf.bookCount}")}, selected=selectedCustomId==shelf.id,
            onClick={cancelSelectAll(); selectedCustomId=shelf.id; selIds=emptySet(); selMode=false; scp.launch{drw.close()}}, modifier=Modifier.weight(1f))
        TextButton(onClick={shelfToDelete=CustomShelf(shelf.id, shelf.name)}) { Text("Delete") }
    }
}
NavigationDrawerItem(label={Text("+ Create shelf")}, selected=false, onClick={showCreateShelf=true})
Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
NavigationDrawerItem(label={Text("Add Folder")}, selected=false, onClick={scp.launch{drw.close()}; folderP.launch(null)})
NavigationDrawerItem(label={Text("Add File")}, selected=false, onClick={scp.launch{drw.close()}; fileP.launch(arrayOf("*/*"))})
NavigationDrawerItem(label={Text("Manage folders")}, selected=false, onClick={scp.launch{drw.close()}; showFolders=true})
if (folders.isNotEmpty()) NavigationDrawerItem(label={Text("Rescan Folders")}, selected=false, onClick={scp.launch{drw.close()}; requestScan("rescan")})
NavigationDrawerItem(label={Text("Settings")}, selected=false, onClick={scp.launch{drw.close()}; showSet=true})
NavigationDrawerItem(label={Text("Activity log")}, selected=false, onClick={scp.launch{drw.close()}; showScanLog=true})
NavigationDrawerItem(label={Text("Converted original visibility")}, selected=false, onClick={scp.launch{drw.close()}; showVisibility=true})
NavigationDrawerItem(label={Text("User Guide")}, selected=false, onClick={scp.launch { drw.close(); showGuide=true }})
}
}
}) {
Scaffold(topBar={ TopAppBar(navigationIcon={ IconButton(enabled=!bulkSaving, onClick={ if (selMode) { cancelSelectAll(); selIds=emptySet(); selMode=false } else scp.launch{drw.open()} }) { if (selMode) Text("×", style=MaterialTheme.typography.headlineSmall) else HamburgerIcon() } }, title={ Text(if (selMode) "${selIds.size} selected" else customShelves.firstOrNull { it.id == selectedCustomId }?.name ?: tab.title) }, actions={ if (selMode) {
    TextButton(enabled=!bulkSaving && !selectingAll, onClick={ selectAllOnShelf() }) { Text(if (selectingAll) "Selecting…" else "All") }
    TextButton(enabled=!bulkSaving && !selectingAll && selIds.isNotEmpty(), onClick={
        showBulkActions=true
        selectedFavoriteCount=null
        val ids = selIds.toList()
        scp.launch {
            selectedFavoriteCount = withContext(Dispatchers.IO) { dao.getByIds(ids).count { it.isFavorite } }
        }
    }) { Text("Actions") }
} else {
    IconButton(onClick = {
        listMode = !listMode
        ThemePrefs.saveLibraryView(ctx, if (listMode) "list" else "grid")
    }) {
        if (listMode) {
            GridViewIcon()
        } else {
            Icon(Icons.Filled.List, contentDescription = "List view")
        }
    }
    Box {
        val sortLabel = when (sortMode) { "author" -> "Author"; "recent" -> "Recent"; else -> "Title" }
        TextButton(onClick = { showSortMenu = true }) { Text(sortLabel) }
        DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
            listOf("title" to "Title", "author" to "Author", "recent" to "Recently opened").forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label) }, onClick = {
                    sortMode = key
                    ThemePrefs.saveSort(ctx, key)
                    showSortMenu = false
                })
            }
        }
    }
} }) }) { p ->
Column(Modifier.fillMaxSize().padding(p)) {
if (scanS.isScanning || scanS.canResume || scanS.warning != null || scanS.error != null) {
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer), modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(8.dp)) {
            Text(scanS.phaseLabel, style = MaterialTheme.typography.labelLarge)
            Text(
                "${scanS.filesFoundInPass} books added • ${scanS.metadataProcessed} details read • ${scanS.metadataLeft} pending • ${scanS.queuedFolders} folders queued",
                style = MaterialTheme.typography.bodySmall
            )
            if (scanS.isScanning && !scanS.isPaused) {
                val progressTotal = scanS.metadataProcessed + scanS.metadataLeft
                if (scanS.phaseLabel.startsWith("Reading") && progressTotal > 0) {
                    LinearProgressIndicator(progress = { scanS.metadataProcessed.toFloat() / progressTotal }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
            }
            scanS.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            scanS.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row {
                if (scanS.isScanning && !scanS.isPaused) TextButton(onClick = { LibraryScanner.pauseScan(ctx) }) { Text("Pause") }
                if (scanS.canResume) TextButton(onClick = { requestScan("resume") }) { Text("Resume") }
                TextButton(onClick = { LibraryScanner.stopScan(ctx) }) { Text("Stop") }
                if (scanS.error != null || scanS.warning != null) {
                    TextButton(onClick = { showScanLog = true }) { Text("Scan details") }
                }
            }
        }
    }
}
if (showScanLog) {
    val logText = remember(showScanLog) { LibraryScanner.readLog(ctx) }
    AlertDialog(
        onDismissRequest = { showScanLog = false },
        title = { Text("Activity log") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(logText, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { showScanLog = false }) { Text("Close") } }
    )
}
if (paged.loadState.refresh is LoadState.Loading && paged.itemCount == 0) {
LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
} else if (paged.loadState.refresh is LoadState.Error && paged.itemCount == 0) {
Column(Modifier.fillMaxWidth().padding(20.dp)) {
    Text("Could not load this shelf.")
    TextButton(onClick = { paged.retry() }) { Text("Try again") }
}
} else if (paged.itemCount == 0) {
val message = if (dbQry.isNotBlank()) "No books match this search on this shelf."
    else if (selectedCustomId != null) "No books on this shelf yet. Open a book in All Books and choose Custom shelves."
    else if (tab == LibraryTab.FAV) "No favorites yet. Open a book to add it to Favorites."
    else if (tab in listOf(LibraryTab.READING, LibraryTab.TBR, LibraryTab.DONE)) "No books on this shelf yet. Open a book in All Books to choose its shelf."
    else "No books here yet."
Text(message, modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
} else if (listMode) {
LazyColumn(contentPadding = PaddingValues(vertical = 6.dp), modifier = Modifier.fillMaxSize()) {
items(paged.itemCount, key=paged.itemKey{it.id}) { i ->
    val b = paged[i]
    if (b != null) {
        BookListRow(b, selMode, selIds.contains(b.id),
            { if (!bulkSaving && !converting) { cancelSelectAll(); if (selMode) { selIds = if (selIds.contains(b.id)) selIds - b.id else selIds + b.id; if (selIds.isEmpty()) selMode=false } else selB = b } },
            { if (!bulkSaving && !converting) { cancelSelectAll(); selMode=true; selIds = selIds + b.id } },
            { if (!bulkSaving && !converting) scp.launch { withContext(Dispatchers.IO) { dao.setFavorite(b.id, !b.isFavorite) } } })
    } else {
        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp).height(92.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)))
    }
}
}
} else LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxSize()) {
items(paged.itemCount, key=paged.itemKey{it.id}) { i ->
    val b = paged[i]; if (b != null) BookGridTile(b, selMode, selIds.contains(b.id), { if (!bulkSaving && !converting) { cancelSelectAll(); if (selMode) { selIds = if (selIds.contains(b.id)) selIds - b.id else selIds + b.id; if (selIds.isEmpty()) selMode=false } else selB = b } }, { if (!bulkSaving && !converting) { cancelSelectAll(); selMode=true; selIds = selIds + b.id } }, { if (!bulkSaving && !converting) scp.launch { withContext(Dispatchers.IO) { dao.setFavorite(b.id, !b.isFavorite) } } }) else BookPlaceholderTile()
}
}
}
}
}
if (showSet) AppSettingsBottomSheet(tm, acc, am, ThemePrefs.isAutoOpenLastBook(ctx), ThemePrefs.getKindleEmail(ctx), ThemePrefs.getConvertedOutputUri(ctx), onT, onA, onAm, {ThemePrefs.saveAutoOpenLastBook(ctx,it)}, {ThemePrefs.saveKindleEmail(ctx,it)}, {}, {}, {}, {showSet=false})
if (showVisibility) AlertDialog(
    onDismissRequest={showVisibility=false},
    title={Text("Converted original visibility")},
    text={Column {
        Text("Successfully converted MOBI and AZW3 originals are hidden by default. Their files are never deleted. Older conversions cannot be linked automatically.")
        Row(Modifier.fillMaxWidth().padding(top=12.dp), verticalAlignment=Alignment.CenterVertically) {
            Text("Show converted originals", modifier=Modifier.weight(1f))
            Switch(checked=showConvertedOriginals, onCheckedChange={ visible ->
                cancelSelectAll(); selIds=emptySet(); selMode=false
                visibilityPrefs.edit().putBoolean("show_converted_originals", visible).apply()
                showConvertedOriginals=visible
            })
        }
    }},
    confirmButton={TextButton(onClick={showVisibility=false}) { Text("Done") }}
)
selB?.let { selected ->
key(selected.id) {
val liveBook by remember(selected.id) { dao.observeById(selected.id) }.collectAsState(initial = selected)
liveBook?.let { b ->
BookDetailsBottomSheet(
b = b, hasK = ThemePrefs.getKindleEmail(ctx).isNotBlank(),
onD = { selB = null },
onR = { selB = null; openInReader(ctx, b) },
onE = { if (!b.metadataExtracted) { Toast.makeText(ctx, "Wait for this book's metadata scan to finish before editing.", Toast.LENGTH_LONG).show() } else { selB = null; edtB = b } },
onS = { val i = FileOpener.prepareShareIntent(ctx, listOf(b)); ctx.startActivity(i) },
onK = { sendToKindle(b) }, onC = { convertBook(b) },
onDel = { scp.launch(Dispatchers.IO) { dao.deleteById(b.id) }; selB = null },
onFavoriteChange = { updateShelf(b.id, favorite = it) },
onStatusChange = { updateShelf(b.id, status = it) },
onCustomShelves = { membershipBook = b },
statusChangeEnabled = !shelfSaving,
hasCustomShelves = customShelves.isNotEmpty(),
translationEnabled = ThemePrefs.isOfflineTranslationEnabled(ctx) && translationJob == null,
onTranslate = { translatingSource = ""; translatingTarget = ""; translatingBook = b },
canDeleteFile = AppFiles.isAppOwned(ctx, b.uriString),
onDeleteFile = { selB = null; deleteFileBook = b }
)
}
}
}
deleteFileBook?.let { target ->
    AlertDialog(
        onDismissRequest = { deleteFileBook = null },
        title = { Text("Delete file?") },
        text = {
            Text(
                "This permanently deletes the app's copy of \"${target.title}\" and removes its library entry. " +
                    "Your original download or source folder file is not touched. This cannot be undone."
            )
        },
        confirmButton = {
            TextButton(onClick = {
                deleteFileBook = null
                scp.launch {
                    val deleted = withContext(Dispatchers.IO) {
                        if (!AppFiles.delete(ctx, target)) {
                            false
                        } else {
                            try {
                                dao.deleteById(target.id)
                                true
                            } catch (e: Exception) {
                                false
                            }
                        }
                    }
                    Toast.makeText(
                        ctx,
                        if (deleted) "File deleted." else "Could not delete the file.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = { deleteFileBook = null }) { Text("Cancel") }
        }
    )
}
translatingBook?.let { book ->
    AlertDialog(onDismissRequest = { translatingBook = null }, title = { Text("Translate EPUB offline") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("The original stays unchanged; a separate copy is added to your library. Enter the source and target language codes for installed packs (for example en and it).")
            OutlinedTextField(value = translatingSource, onValueChange = { translatingSource = it.trim() }, label = { Text("Source language code") }, singleLine = true)
            OutlinedTextField(value = translatingTarget, onValueChange = { translatingTarget = it.trim() }, label = { Text("Target language code") }, singleLine = true)
            Text("Text is translated one markup segment at a time, so context and quality can vary. Text in images, CSS-generated text, code/preformatted text and book metadata other than language are not translated. SVG, scripts, embedded multimedia, encrypted or signed EPUBs and DOCTYPEs are unsupported. Some valid EPUBs may be refused.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        } }, confirmButton = { TextButton(enabled = translatingSource.isNotBlank() && translatingTarget.isNotBlank(), onClick = {
            val src = translatingSource.trim(); val dst = translatingTarget.trim()
            translatingBook = null
            translationProgress = "Preparing…"
            translationJob = scp.launch {
                try {
                    val result = WholeEpubTranslation.translate(ctx.applicationContext, book, src, dst) { status ->
                        withContext(Dispatchers.Main) { translationProgress = status }
                    }
                    selB = result
                    Toast.makeText(ctx, "Translated copy added. Original kept.", Toast.LENGTH_LONG).show()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { translationError = e.localizedMessage ?: "Unknown error." }
                catch (e: LinkageError) { translationError = "Translation engine could not be loaded." }
                finally { translationProgress = null; translationJob = null }
            }
        }) { Text("Translate") } }, dismissButton = { TextButton(onClick = { translatingBook = null }) { Text("Cancel") } })
}
translationProgress?.let { progress ->
    AlertDialog(onDismissRequest = {}, title = { Text("Translating EPUB") }, text = { Column { Text(progress); LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp)) } },
        confirmButton = { TextButton(enabled = progress != "Saving translated copy…" && progress != "Cancelling…", onClick = { translationProgress = "Cancelling…"; translationJob?.cancel() }) { Text("Cancel") } })
}
translationError?.let { reason ->
    AlertDialog(onDismissRequest = { translationError = null }, title = { Text("Translation not completed") },
        text = { Text("$reason\n\nNo partial copy was added. The original remains unchanged.") },
        confirmButton = { TextButton(onClick = { translationError = null }) { Text("Close") } })
}
if (showCreateShelf) CreateShelfDialog(onDismiss = { showCreateShelf = false }, onCreate = { name ->
    if (!customSaving) {
        customSaving = true
        scp.launch {
            try {
                val id = withContext(Dispatchers.IO) { dao.createCustomShelf(CustomShelf(name = name)) }
                if (id == -1L) Toast.makeText(ctx, "A shelf with that name already exists.", Toast.LENGTH_LONG).show()
                else { showCreateShelf = false; selectedCustomId = id; drw.close() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(ctx, "Could not create shelf: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show() }
            finally { customSaving = false }
        }
    }
}, saving = customSaving)
shelfToDelete?.let { shelf ->
    AlertDialog(onDismissRequest = { if (!customSaving) shelfToDelete = null },
        title = { Text("Delete ${shelf.name}?") },
        text = { Text("This removes the shelf, not its books or their reading progress.") },
        confirmButton = { TextButton(enabled = !customSaving, onClick = {
            customSaving = true
            scp.launch {
                try {
                    withContext(Dispatchers.IO) { dao.deleteCustomShelf(shelf.id) }
                    if (selectedCustomId == shelf.id) { selectedCustomId = null; tab = LibraryTab.ALL }
                    shelfToDelete = null
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Toast.makeText(ctx, "Could not delete shelf: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show() }
                finally { customSaving = false }
            }
        }) { Text("Delete") } },
        dismissButton = { TextButton(enabled = !customSaving, onClick = { shelfToDelete = null }) { Text("Cancel") } })
}
showBulkActions.let { if (it) {
    AlertDialog(onDismissRequest={showBulkActions=false}, title={Text("${selIds.size} selected")}, text={ Column {
        TextButton(onClick={showBulkActions=false; showBulkMove=true}, modifier=Modifier.fillMaxWidth()) { Text("Move to reading shelf") }
        if (customShelves.isNotEmpty()) TextButton(onClick={showBulkActions=false; showBulkShelves=true}, modifier=Modifier.fillMaxWidth()) { Text("Manage custom shelves") }
        val favCount = selectedFavoriteCount
        when {
            favCount == null -> TextButton(enabled=false, onClick={}, modifier=Modifier.fillMaxWidth()) { Text("Checking favorites…") }
            favCount == 0 -> TextButton(onClick={showBulkActions=false; bulkFavoriteAction=true}, modifier=Modifier.fillMaxWidth()) { Text("Add to favorites") }
            favCount == selIds.size -> TextButton(onClick={showBulkActions=false; bulkFavoriteAction=false}, modifier=Modifier.fillMaxWidth()) { Text("Remove from favorites") }
            else -> {
                TextButton(onClick={showBulkActions=false; bulkFavoriteAction=true}, modifier=Modifier.fillMaxWidth()) { Text("Add all to favorites") }
                TextButton(onClick={showBulkActions=false; bulkFavoriteAction=false}, modifier=Modifier.fillMaxWidth()) { Text("Remove all from favorites") }
            }
        }
        TextButton(onClick={showBulkActions=false; showBulkShareConfirm=true}, modifier=Modifier.fillMaxWidth()) { Text("Share selected files") }
        TextButton(enabled=!converting,onClick={
            val ids=selIds.toList(); showBulkActions=false; bulkSaving=true
            scp.launch {
                try {
                    val books=withContext(Dispatchers.IO) {
                        val selected=dao.getByIds(ids)
                        check(selected.isNotEmpty() && selected.size == ids.size) { "Some selected books are no longer in the library. No books were converted." }
                        selected.filter { it.format == "MOBI" || it.format == "AZW3" }
                    }
                    if (books.isEmpty()) Toast.makeText(ctx,"The selected books are already readable in the app.",Toast.LENGTH_SHORT).show()
                    else { selIds=emptySet(); selMode=false; convertBooks(books, false) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { Toast.makeText(ctx,"Could not load selected books: ${e.localizedMessage ?: "unknown error"}",Toast.LENGTH_LONG).show() }
                finally { bulkSaving=false }
            }
        }, modifier=Modifier.fillMaxWidth()) { Text("Convert MOBI / AZW3 books") }
    } }, confirmButton={TextButton(onClick={showBulkActions=false}){Text("Done")}})
} }
showBulkMove.let { if (it) {
    AlertDialog(onDismissRequest={ if (!bulkSaving) showBulkMove=false }, title={Text("Move ${selIds.size} books")}, text={ Column {
        Text("Choose a reading shelf. This keeps files, progress and custom-shelf memberships unchanged.", style=MaterialTheme.typography.bodySmall)
        listOf("NEW" to "Unclassified", "TBR" to "To Read", "READING" to "Reading", "FINISHED" to "Finished").forEach { (status,label) ->
            TextButton(enabled=!bulkSaving, onClick={ val ids=selIds.toList(); bulkSaving=true; scp.launch { try { withContext(Dispatchers.IO){dao.setReadingStatusForIds(ids,status)}; showBulkMove=false; selIds=emptySet(); selMode=false } catch (e: CancellationException) { throw e } catch (e: Exception) { Toast.makeText(ctx,"Could not update reading shelf.",Toast.LENGTH_LONG).show() } finally { bulkSaving=false } } }, modifier=Modifier.fillMaxWidth()) { Text(label) }
        }
    } }, confirmButton={ TextButton(enabled=!bulkSaving,onClick={showBulkMove=false}){Text("Cancel")} })
} }
showBulkShelves.let { if (it) {
    AlertDialog(onDismissRequest={ if (!bulkSaving) showBulkShelves=false }, title={Text("Custom shelves")}, text={
        Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
            if (customShelves.isEmpty()) {
                Text("Create a custom shelf first.")
            } else {
                Text("Add the ${selIds.size} selected books to a shelf. Books can belong to more than one custom shelf.", style=MaterialTheme.typography.bodySmall)
                customShelves.forEach { shelf -> Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
                    Text(shelf.name, modifier=Modifier.weight(1f))
                    TextButton(enabled=!bulkSaving,onClick={ val ids=selIds.toList(); bulkSaving=true; scp.launch { try { withContext(Dispatchers.IO) { dao.addBooksToCustomShelf(shelf.id, ids) }; Toast.makeText(ctx,"Added to ${shelf.name}.",Toast.LENGTH_SHORT).show() } catch (e: CancellationException) { throw e } catch (e: Exception) { Toast.makeText(ctx,"Could not update ${shelf.name}.",Toast.LENGTH_LONG).show() } finally { bulkSaving=false } } }) { Text("Add") }
                    if (selectedCustomId == shelf.id) TextButton(enabled=!bulkSaving,onClick={ val ids=selIds.toList(); bulkSaving=true; scp.launch { try { withContext(Dispatchers.IO) { dao.removeBooksFromCustomShelf(shelf.id, ids) }; showBulkShelves=false; selIds=emptySet(); selMode=false } catch (e: CancellationException) { throw e } catch (e: Exception) { Toast.makeText(ctx,"Could not remove books from ${shelf.name}.",Toast.LENGTH_LONG).show() } finally { bulkSaving=false } } }) { Text("Remove") }
                } }
            }
            Spacer(Modifier.height(6.dp))
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.End) {
                TextButton(enabled=!bulkSaving, onClick={ showBulkShelves=false }) { Text("Done") }
            }
        }
    }, confirmButton={})
} }
bulkFavoriteAction?.let { add ->
    AlertDialog(onDismissRequest={ if (!bulkSaving) bulkFavoriteAction=null },
        title={Text(if (add) "Add to favorites" else "Remove from favorites")},
        text={Text(if (add) "Add all ${selIds.size} selected books to Favorites?" else "Remove all ${selIds.size} selected books from Favorites?")},
        confirmButton={
            TextButton(enabled=!bulkSaving,onClick={ val ids=selIds.toList(); bulkSaving=true; scp.launch { try { withContext(Dispatchers.IO){dao.setFavoriteForIds(ids, add)}; bulkFavoriteAction=null; selIds=emptySet(); selMode=false } catch (e: CancellationException) { throw e } catch (e: Exception) { Toast.makeText(ctx,"Could not update favorites.",Toast.LENGTH_LONG).show() } finally { bulkSaving=false } } }) {Text(if (add) "Add" else "Remove")}
        },
        dismissButton={ TextButton(enabled=!bulkSaving,onClick={bulkFavoriteAction=null}) {Text("Cancel")} })
}
if (bulkConversionFailures.isNotEmpty()) {
    AlertDialog(onDismissRequest={ if (!converting) bulkConversionFailures=emptyList() },
        title={Text("${bulkConversionFailures.size} conversion${if (bulkConversionFailures.size == 1) "" else "s"} failed")},
        text={ Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
            Text("Original files were kept. Retry the failed books or close this report.")
            bulkConversionFailures.forEach { failure ->
                Text(failure.book.fileName, fontWeight=FontWeight.Bold, modifier=Modifier.padding(top=8.dp))
                Text(failure.reason, style=MaterialTheme.typography.bodySmall)
            }
        } },
        confirmButton={ TextButton(enabled=!converting, onClick={ convertBooks(bulkConversionFailures.map { it.book }, false) }) { Text(if (converting) "Converting…" else "Retry failed") } },
        dismissButton={ TextButton(enabled=!converting, onClick={bulkConversionFailures=emptyList()}) { Text("Close") } })
}
showBulkShareConfirm.let { if (it) {
    AlertDialog(onDismissRequest={if (!preparingBulkShare) showBulkShareConfirm=false}, title={Text("Share ${selIds.size} books?")}, text={Text("The selected book files will be attached to a share request.")}, confirmButton={
        TextButton(enabled=!preparingBulkShare, onClick={
            if (!preparingBulkShare) {
                preparingBulkShare=true
                val ids = selIds.toList()
                scp.launch {
                    try {
                        val intent = withContext(Dispatchers.IO) {
                            val books = dao.getByIds(ids)
                            check(books.isNotEmpty() && books.size == ids.size) { "Some selected books are no longer in the library." }
                            FileOpener.prepareShareIntent(ctx, books)
                        }
                        ctx.startActivity(intent)
                        showBulkShareConfirm=false
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Toast.makeText(ctx,"Could not prepare sharing: ${e.localizedMessage ?: "unknown error"}",Toast.LENGTH_LONG).show() }
                    finally { preparingBulkShare=false }
                }
            }
        }) {Text(if (preparingBulkShare) "Preparing…" else "Share")}
    }, dismissButton={TextButton(enabled=!preparingBulkShare,onClick={showBulkShareConfirm=false}){Text("Cancel")}})
} }
membershipBook?.let { book ->
    val shelfIds by remember(book.id) { dao.observeShelfIdsForBook(book.id) }.collectAsState(initial = null)
    CustomShelfMembershipDialog(shelves = customShelves, shelfIds = shelfIds?.toSet() ?: emptySet(), saving = customSaving || shelfIds == null,
        onDismiss = { if (!customSaving) membershipBook = null }, onChange = { shelf, checked ->
            if (!customSaving) {
                customSaving = true
                scp.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            if (checked) dao.addBookToCustomShelf(CustomShelfBook(shelf.id, book.id))
                            else dao.removeBookFromCustomShelf(shelf.id, book.id)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { Toast.makeText(ctx, "Could not update shelf: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show() }
                    finally { customSaving = false }
                }
            }
        })
}
edtB?.let { b -> MetadataEditorBottomSheet(b, {edtB=null}, {r -> runSave(b,r)}) }
}
