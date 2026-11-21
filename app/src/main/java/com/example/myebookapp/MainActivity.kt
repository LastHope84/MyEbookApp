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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
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
    companion object { var didAutoOpen = false }
    private var incUri by mutableStateOf<Uri?>(null)
    override fun onCreate(s: Bundle?) { super.onCreate(s); handleI(intent)
        setContent {
            val c = LocalContext.current
            var m by remember { mutableStateOf(ThemePrefs.getMode(c)) }
            var a by remember { mutableStateOf(ThemePrefs.getAccent(c)) }
            var am by remember { mutableStateOf(ThemePrefs.isAmoled(c)) }
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
    var showSet by remember { mutableStateOf(false) }; var msg by remember { mutableStateOf<String?>(null) }
    var selMode by remember { mutableStateOf(false) }; var selIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val lastO by dao.observeLastOpened().collectAsState(null)

    val folderP = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u -> if (u != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }; LibraryScanner.addFolder(ctx, u); LibraryScanner.scanFolders(ctx, listOf(u)) } }
    val fileP = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) {
        val name = EpubParser.getFileName(ctx, u); if (name.endsWith(".epub", true)) scp.launch(Dispatchers.IO) {
            val m = EpubParser.parseEpub(ctx, u, name); val cov = LibraryScanner.saveWebpThumbnail(ctx, u.toString(), m.coverBitmap)
            dao.insertIgnore(BookEntity(uriString=u.toString(), fileName=name, format="EPUB", fileSize=0, lastModified=0, title=m.title, author=m.author, publishingDate=m.publishingDate, publisher=m.publisher, language=m.language, coverPath=cov, lastLocatorJson=null, progressPercent=0.0, lastOpenedAt=System.currentTimeMillis(), metadataExtracted=true))
            withContext(Dispatchers.Main) { selB = dao.getByUri(u.toString()) }
        }
    } }

    BackHandler(drw.isOpen || selMode) { if (drw.isOpen) scp.launch { drw.close() } else { selIds = emptySet(); selMode = false } }
    LaunchedEffect(incU) { incU?.let { onCons(); val n = EpubParser.getFileName(ctx, it); if (n.endsWith(".epub", true)) openInReader(ctx, it.toString()) } }
    LaunchedEffect(Unit) { if (!MainActivity.didAutoOpen && ThemePrefs.isAutoOpenLastBook(ctx) && incU == null) { MainActivity.didAutoOpen = true; val l = withContext(Dispatchers.IO) { dao.getLastOpenedOnce() }; if (l?.format == "EPUB") openInReader(ctx, l.uriString) } }
    LaunchedEffect(qry) { delay(250); dbQry = qry }

    val paged = remember(dbQry, tab, scp) { Pager(PagingConfig(60, 180, true)) { val f = toFtsQuery(dbQry); when (tab) { LibraryTab.ALL -> if (f == null) dao.allPaged() else dao.searchAllPaged(f); LibraryTab.RECENT -> if (f == null) dao.recentPaged() else dao.searchRecentPaged(f); LibraryTab.TO_CONVERT -> dao.toConvertPaged() } }.flow.cachedIn(scp) }.collectAsLazyPagingItems()

    ModalNavigationDrawer(drawerState=drw, gesturesEnabled=!selMode, drawerContent={
        ModalDrawerSheet(Modifier.width(300.dp)) {
            Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("My Ebook Library", style=MaterialTheme.typography.titleLarge, fontWeight=FontWeight.Bold)
                Text("$total books", style=MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(value=qry, onValueChange={qry=it}, placeholder={Text("Search...")}, singleLine=true, modifier=Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                NavigationDrawerItem(label={Text("All Books")}, selected=tab==LibraryTab.ALL, onClick={tab=LibraryTab.ALL; scp.launch{drw.close()}})
                NavigationDrawerItem(label={Text("Recent")}, selected=tab==LibraryTab.RECENT, onClick={tab=LibraryTab.RECENT; scp.launch{drw.close()}})
                NavigationDrawerItem(label={Text("To Convert ($toConv)")}, selected=tab==LibraryTab.TO_CONVERT, onClick={tab=LibraryTab.TO_CONVERT; scp.launch{drw.close()}})
                Spacer(Modifier.height(8.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                NavigationDrawerItem(label={Text("Add Folder")}, selected=false, onClick={scp.launch{drw.close()}; folderP.launch(null)})
                NavigationDrawerItem(label={Text("Settings")}, selected=false, onClick={scp.launch{drw.close()}; showSet=true})
            }
        }
    }) {
        Scaffold(topBar={ TopAppBar(navigationIcon={ IconButton(onClick={scp.launch{drw.open()}}){HamburgerIcon()} }, title={ Text(if (selMode) "${selIds.size}" else tab.title) }) }) { p ->
            Column(Modifier.fillMaxSize().padding(p).padding(horizontal=12.dp)) {
                if (scanS.isScanning) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(scanS.phaseLabel, style=MaterialTheme.typography.labelSmall) }
                LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize()) {
                    items(paged.itemCount, key=paged.itemKey{it.id}) { i ->
                        val b = paged[i]; if (b != null) BookGridTile(b, selMode, selIds.contains(b.id), { if (selMode) { selIds = if (selIds.contains(b.id)) selIds - b.id else selIds + b.id; if (selIds.isEmpty()) selMode=false } else selB = b }, { selMode=true; selIds = selIds + b.id }) else BookPlaceholderTile()
                    }
                }
            }
        }
    }

    if (showSet) AppSettingsBottomSheet(tm, acc, am, ThemePrefs.isAutoOpenLastBook(ctx), ThemePrefs.getKindleEmail(ctx), ThemePrefs.getConvertedOutputUri(ctx), onT, onA, onAm, {ThemePrefs.saveAutoOpenLastBook(ctx,it)}, {ThemePrefs.saveKindleEmail(ctx,it)}, {}, {}, {}, {showSet=false})
    selB?.let { b -> BookDetailsBottomSheet(b, ThemePrefs.getKindleEmail(ctx).isNotBlank(), {selB=null}, {selB=null; openInReader(ctx, b.uriString)}, {selB=null; edtB=b}, {val i=FileOpener.prepareShareIntent(ctx,listOf(b)); ctx.startActivity(i)}, {}, {}, {scp.launch(Dispatchers.IO){dao.deleteById(b.id)}; selB=null}) }
}
