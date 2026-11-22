package com.example.myebookapp
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.paging.*
import androidx.paging.compose.*
import kotlinx.coroutines.*
import java.io.File

enum class LibraryTab(val title: String) { ALL("All Books"), FAV("Favorites"), READING("Reading"), TBR("To Read"), DONE("Finished"), CONV("To Convert") }
data class PendingConvertTarget(val uri: Uri, val fileName: String, val existingBookId: Long? = null)

class MainActivity : ComponentActivity() {
companion object { var didAutoOpen = false }
private var incUri by mutableStateOf<Uri?>(null)
override fun onCreate(s: Bundle?) { super.onCreate(s); handleI(intent)
setContent {
val c = LocalContext.current; var m by remember { mutableStateOf(ThemePrefs.getMode(c)) }
var a by remember { mutableStateOf(ThemePrefs.getAccent(c)) }; var am by remember { mutableStateOf(ThemePrefs.isAmoled(c)) }
MyEbookTheme(mode=m, accent=a, amoledDark=am) {
LibraryScreen(m, a, am, incUri, { incUri = null }, { m=it; ThemePrefs.saveMode(c,it) }, { a=it; ThemePrefs.saveAccent(c,it) }, { am=it; ThemePrefs.saveAmoled(c,it) })
}
}
}
override fun onNewIntent(i: Intent) { super.onNewIntent(i); setIntent(i); handleI(i) }
private fun handleI(i: Intent?) { if (i?.action == Intent.ACTION_VIEW && i.data != null) { incUri = i.data; didAutoOpen = true } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(tm: AppThemeMode, acc: AppAccent, am: Boolean, incU: Uri?, onCons: () -> Unit, onT: (AppThemeMode)->Unit, onA: (AppAccent)->Unit, onAm: (Boolean)->Unit) {
val ctx = LocalContext.current; val scp = rememberCoroutineScope(); val dao = remember { AppDatabase.getInstance(ctx).bookDao() }
val drw = rememberDrawerState(DrawerValue.Closed); val total by dao.observeTotalCount().collectAsState(0)
val toConv by dao.observeToConvertCount().collectAsState(0); val scanS by LibraryScanner.status.collectAsState()
var tab by remember { mutableStateOf(LibraryTab.ALL) }; var qry by remember { mutableStateOf("") }; var dbQry by remember { mutableStateOf("") }
var selB by remember { mutableStateOf<BookEntity?>(null) }; var edtB by remember { mutableStateOf<BookEntity?>(null) }
var showSet by remember { mutableStateOf(false) }; var showGuide by rememberSaveable { mutableStateOf(false) }; var selMode by remember { mutableStateOf(false) }; var selIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
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
var converting by remember { mutableStateOf(false) }
var preparingKindle by remember { mutableStateOf(false) }
val folders = LibraryScanner.getSavedFolders(ctx)
LaunchedEffect(Unit) { LibraryScanner.restore(ctx) }

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

fun convertBook(book: BookEntity) {
if (converting) return
converting = true
selB = null
Toast.makeText(ctx, "Converting ${book.fileName}...", Toast.LENGTH_SHORT).show()
scp.launch {
try {
val (converted, savedToCustom) = withContext(Dispatchers.IO) {
val folder = ThemePrefs.getConvertedOutputUri(ctx)?.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }
val out = MobiConverter.convertToEpubOutput(ctx, Uri.parse(book.uriString), book.fileName, folder)
val meta = EpubParser.parseEpub(ctx, out.uri, out.fileName)
val cover = LibraryScanner.saveWebpThumbnail(ctx, out.uri.toString(), meta.coverBitmap)
val entry = BookEntity(
    uriString = out.uri.toString(), fileName = out.fileName, format = "EPUB",
    fileSize = out.size, lastModified = out.lastModified,
    title = meta.title, author = meta.author, publishingDate = meta.publishingDate,
    publisher = meta.publisher, language = meta.language, coverPath = cover,
    lastLocatorJson = null, progressPercent = 0.0, lastOpenedAt = 0L,
    metadataExtracted = true, series = meta.series, seriesIndex = meta.seriesIndex, tags = meta.tags
)
dao.insertIgnore(entry)
(dao.getByUri(out.uri.toString()) ?: error("Could not add the converted book to the library.")) to out.savedToCustomFolder
}
selB = converted
val location = if (savedToCustom) "chosen folder" else "app storage"
Toast.makeText(ctx, "Converted to EPUB and saved in $location. Original kept.", Toast.LENGTH_LONG).show()
} catch (e: CancellationException) {
throw e
} catch (e: Exception) {
Toast.makeText(ctx, "Conversion failed: ${e.localizedMessage ?: "unknown error"}", Toast.LENGTH_LONG).show()
selB = book
} catch (e: LinkageError) {
Toast.makeText(ctx, "Conversion engine could not be loaded.", Toast.LENGTH_LONG).show()
selB = book
} finally {
converting = false
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
val count = dao.saveEditedMetadata(b.id, r.title, r.author, r.publishingDate, r.publisher, r.language, changeCover, cover, r.series, r.seriesIndex, r.tags)
check(count == 1) { "Book is no longer in the library." }
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
val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
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
if (showGuide) {
    UserGuideScreen(onClose = { showGuide = false })
    return
}
BackHandler(drw.isOpen || selMode) { if (drw.isOpen) scp.launch { drw.close() } else { selIds = emptySet(); selMode = false } }
LaunchedEffect(qry) { delay(250); dbQry = qry }

val paged = remember(dbQry, tab, scp) { Pager(PagingConfig(60, 180, true)) { 
val f = toFtsQuery(dbQry)
when (tab) {
LibraryTab.ALL -> if (f == null) dao.allPaged() else dao.searchAllPaged(f)
LibraryTab.FAV -> if (f == null) dao.favoritePaged() else dao.searchFavoritePaged(f)
LibraryTab.READING -> if (f == null) dao.statusPaged("READING") else dao.searchStatusPaged("READING", f)
LibraryTab.TBR -> if (f == null) dao.statusPaged("TBR") else dao.searchStatusPaged("TBR", f)
LibraryTab.DONE -> if (f == null) dao.statusPaged("FINISHED") else dao.searchStatusPaged("FINISHED", f)
LibraryTab.CONV -> if (f == null) dao.toConvertPaged() else dao.searchToConvertPaged(f)
}
}.flow.cachedIn(scp) }.collectAsLazyPagingItems()

ModalNavigationDrawer(drawerState=drw, gesturesEnabled=!selMode, drawerContent={
ModalDrawerSheet(Modifier.width(300.dp)) {
Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
Text("My Ebook Library", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold); Text("$total books", style=MaterialTheme.typography.labelMedium)
Spacer(Modifier.height(12.dp)); OutlinedTextField(value=qry, onValueChange={qry=it}, placeholder={Text("Search...")}, singleLine=true, modifier=Modifier.fillMaxWidth())
Spacer(Modifier.height(12.dp)); LibraryTab.entries.forEach { t -> NavigationDrawerItem(label={Text(t.title + (if(t==LibraryTab.CONV) " ($toConv)" else ""))}, selected=tab==t, onClick={tab=t; scp.launch{drw.close()}}) }
Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
NavigationDrawerItem(label={Text("Add Folder")}, selected=false, onClick={scp.launch{drw.close()}; folderP.launch(null)})
if (folders.isNotEmpty()) NavigationDrawerItem(label={Text("Rescan Folders")}, selected=false, onClick={scp.launch{drw.close()}; requestScan("rescan")})
NavigationDrawerItem(label={Text("Settings")}, selected=false, onClick={scp.launch{drw.close()}; showSet=true})
NavigationDrawerItem(label={Text("User Guide")}, selected=false, onClick={scp.launch { drw.close(); showGuide=true }})
}
}
}) {
Scaffold(topBar={ TopAppBar(navigationIcon={ IconButton(onClick={scp.launch{drw.open()}}){HamburgerIcon()} }, title={ Text(if (selMode) "${selIds.size} selected" else tab.title) }) }) { p ->
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
            }
        }
    }
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
    else if (tab == LibraryTab.FAV) "No favorites yet. Open a book to add it to Favorites."
    else if (tab in listOf(LibraryTab.READING, LibraryTab.TBR, LibraryTab.DONE)) "No books on this shelf yet. Open a book in All Books to choose its shelf."
    else "No books here yet."
Text(message, modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.bodyMedium)
} else LazyVerticalGrid(columns = GridCells.Fixed(3), contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.fillMaxSize()) {
items(paged.itemCount, key=paged.itemKey{it.id}) { i ->
    val b = paged[i]; if (b != null) BookGridTile(b, selMode, selIds.contains(b.id), { if (selMode) { selIds = if (selIds.contains(b.id)) selIds - b.id else selIds + b.id; if (selIds.isEmpty()) selMode=false } else selB = b }, { selMode=true; selIds = selIds + b.id }) else BookPlaceholderTile()
}
}
}
}
}
if (showSet) AppSettingsBottomSheet(tm, acc, am, ThemePrefs.isAutoOpenLastBook(ctx), ThemePrefs.getKindleEmail(ctx), ThemePrefs.getConvertedOutputUri(ctx), onT, onA, onAm, {ThemePrefs.saveAutoOpenLastBook(ctx,it)}, {ThemePrefs.saveKindleEmail(ctx,it)}, {}, {}, {}, {showSet=false})
selB?.let { selected ->
key(selected.id) {
val liveBook by remember(selected.id) { dao.observeById(selected.id) }.collectAsState(initial = selected)
liveBook?.let { b ->
BookDetailsBottomSheet(
b = b, hasK = ThemePrefs.getKindleEmail(ctx).isNotBlank(),
onD = { selB = null },
onR = { selB = null; openInReader(ctx, b.uriString) },
onE = { if (!b.metadataExtracted) { Toast.makeText(ctx, "Wait for this book's metadata scan to finish before editing.", Toast.LENGTH_LONG).show() } else { selB = null; edtB = b } },
onS = { val i = FileOpener.prepareShareIntent(ctx, listOf(b)); ctx.startActivity(i) },
onK = { sendToKindle(b) }, onC = { convertBook(b) },
onDel = { scp.launch(Dispatchers.IO) { dao.deleteById(b.id) }; selB = null },
onFavoriteChange = { updateShelf(b.id, favorite = it) },
onStatusChange = { updateShelf(b.id, status = it) },
statusChangeEnabled = !shelfSaving
)
}
}
}
edtB?.let { b -> MetadataEditorBottomSheet(b, {edtB=null}, {r -> runSave(b,r)}) }
}
