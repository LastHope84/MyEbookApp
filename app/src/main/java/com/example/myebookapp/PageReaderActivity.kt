package com.example.myebookapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PageReaderActivity : FragmentActivity() {
    companion object {
        // Saves must finish even when the reader is closing.
        private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private data class OpenedDocument(
        val book: BookEntity,
        val source: PagedImageSource,
        val text: PdfTextSource?,
        val start: Int
    )

    private val loading = mutableStateOf(true)
    private val loadError = mutableStateOf<String?>(null)
    private var book: BookEntity? = null
    private var source: PagedImageSource? = null
    private var textSource: PdfTextSource? = null
    private var loader: PageLoader? = null
    private var pageCount = 0
    private var startPage = 0
    private var requestedPage = -1
    private var currentPage = 0
    private var lastSavedPage = -1
    private val dao by lazy { AppDatabase.getInstance(this).bookDao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.BLACK
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.BLACK
        window.decorView.setBackgroundColor(android.graphics.Color.BLACK)

        val uriString = intent.getStringExtra("book_uri")
        requestedPage = intent.getIntExtra("book_page", -1)
        if (uriString.isNullOrBlank()) {
            loadError.value = "No document was selected."
            loading.value = false
        } else {
            openDocument(uriString)
        }

        setContent {
            MyEbookTheme(mode = AppThemeMode.DARK, amoledDark = true, forceDark = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    val error = loadError.value
                    val pageLoader = loader
                    when {
                        loading.value -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                            CircularProgressIndicator(color = Color.White)
                        }
                        error != null -> ErrorScreen(error) { finish() }
                        pageLoader != null && pageCount > 0 -> PageReaderScreen(
                            title = book?.title ?: "Document",
                            pageCount = pageCount,
                            startPage = startPage,
                            loader = pageLoader,
                            textSource = textSource,
                            language = book?.language ?: "Unknown",
                            initialRtl = ThemePrefs.isComicRtl(this@PageReaderActivity),
                            book = book,
                            onRtlChange = { ThemePrefs.saveComicRtl(this@PageReaderActivity, it) },
                            onPageSettled = {
                                currentPage = it
                                savePage(it)
                            },
                            onSaveQuote = { text, page -> saveQuote(text, page) },
                            onBack = { finish() }
                        )
                    }
                }
            }
        }
    }

    private fun openDocument(uriString: String) {
        lifecycleScope.launch {
            val result: Result<OpenedDocument> = withContext(Dispatchers.IO) {
                try {
                    val stored = dao.getByUri(uriString)
                        ?: throw IllegalStateException("This document is not in the library anymore.")
                    val opened: PagedImageSource = when (stored.format) {
                        "PDF" -> PdfPageSource.open(
                            this@PageReaderActivity, uriString, stored.fileSize, stored.lastModified
                        )
                        "CBR" -> if (ArchiveSniffer.kind(this@PageReaderActivity, uriString) == "ZIP") {
                            ComicPageSource.open(
                                this@PageReaderActivity, uriString, stored.fileSize, stored.lastModified
                            )
                        } else {
                            CbrPageSource.open(
                                this@PageReaderActivity, uriString, stored.fileSize, stored.lastModified
                            )
                        }
                        else -> ComicPageSource.open(
                            this@PageReaderActivity, uriString, stored.fileSize, stored.lastModified
                        )
                    }
                    val text = if (stored.format == "PDF") {
                        try {
                            PdfTextSource.open(
                                this@PageReaderActivity, uriString, stored.fileSize, stored.lastModified
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                    } else null
                    val saved = stored.lastLocatorJson?.let {
                        runCatching { JSONObject(it).optInt("page", -1) }.getOrNull()
                    } ?: -1
                    val total = opened.pageCount
                    if (total <= 0) {
                        text?.close()
                        opened.close()
                        throw IllegalStateException("This document has no readable pages.")
                    }
                    val start = when {
                        requestedPage in 0 until total -> requestedPage
                        saved in 0 until total -> saved
                        stored.progressPercent > 0.0 -> ((stored.progressPercent * total).toInt() - 1).coerceIn(0, total - 1)
                        else -> 0
                    }
                    val now = System.currentTimeMillis()
                    dao.touchOpened(uriString, now)
                    dao.markStarted(uriString)
                    Result.success(OpenedDocument(stored, opened, text, start))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            result.onSuccess { opened ->
                if (isFinishing || isDestroyed) {
                    opened.source.close()
                    opened.text?.close()
                    return@onSuccess
                }
                book = opened.book
                source = opened.source
                textSource = opened.text
                pageCount = opened.source.pageCount
                startPage = opened.start
                currentPage = opened.start
                val metrics = resources.displayMetrics
                loader = PageLoader(opened.source, metrics.widthPixels, metrics.heightPixels)
                loading.value = false
            }.onFailure { error ->
                loadError.value = error.localizedMessage ?: "Could not open this document."
                loading.value = false
            }
        }
    }

    private fun savePage(index: Int) {
        val stored = book ?: return
        val total = pageCount
        if (total == 0 || index !in 0 until total || index == lastSavedPage) return
        lastSavedPage = index
        val percent = (index + 1).toDouble() / total
        val locator = JSONObject().put("page", index).toString()
        val uri = stored.uriString
        saveScope.launch {
            dao.updateReadingPosition(uri, locator, percent, System.currentTimeMillis())
            if (index == total - 1) dao.markFinished(uri)
        }
    }

    private fun saveQuote(raw: String, pageIndex: Int) {
        val stored = book ?: return
        val text = QuoteText.normalize(raw) ?: return
        saveScope.launch {
            dao.insertQuote(
                QuoteEntity(
                    bookId = stored.id,
                    text = text,
                    pageIndex = pageIndex,
                    label = "Page ${pageIndex + 1}"
                )
            )
            withContext(Dispatchers.Main) {
                Toast.makeText(this@PageReaderActivity, "Quote saved", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onPause() {
        savePage(currentPage)
        super.onPause()
    }

    override fun onDestroy() {
        source?.close()
        textSource?.close()
        loader?.clear()
        super.onDestroy()
    }
}

private data class PdfSelection(
    val pageIndex: Int,
    val startWord: Int,
    val endWord: Int,
    val text: String
)

private data class PdfSearchResult(val page: Int, val snippet: String)

private sealed class PageState {
    object Loading : PageState()
    class Ready(val bitmap: Bitmap) : PageState()
    object Failed : PageState()
}

@Composable
private fun PageReaderScreen(
    title: String,
    pageCount: Int,
    startPage: Int,
    loader: PageLoader,
    textSource: PdfTextSource?,
    language: String,
    initialRtl: Boolean,
    book: BookEntity?,
    onRtlChange: (Boolean) -> Unit,
    onPageSettled: (Int) -> Unit,
    onSaveQuote: (String, Int) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context.findFragmentActivity()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    var rtl by remember { mutableStateOf(initialRtl) }
    var chromeVisible by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<PdfSelection?>(null) }
    var currentText by remember { mutableStateOf<PdfPageText?>(null) }
    var speechState by remember { mutableStateOf(TtsState.IDLE) }
    var speechRange by remember { mutableStateOf<IntRange?>(null) }
    var autoAdvance by remember { mutableStateOf(false) }
    var selectionSpeech by remember { mutableStateOf(false) }
    var showQuotes by remember { mutableStateOf(false) }

    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<PdfSearchResult>>(emptyList()) }
    var searchRanges by remember { mutableStateOf<Map<Int, List<IntRange>>>(emptyMap()) }
    var searching by remember { mutableStateOf(false) }
    var searchProgress by remember { mutableStateOf(0) }
    var searchJob by remember { mutableStateOf<Job?>(null) }

    var dictWord by remember { mutableStateOf<String?>(null) }
    var dictEntry by remember { mutableStateOf<DictionaryEntry?>(null) }
    var dictLoading by remember { mutableStateOf(false) }

    val lastIndex = (pageCount - 1).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startPage.coerceIn(0, lastIndex)) { pageCount }
    val speechHolder = remember { arrayOfNulls<PdfSpeech>(1) }
    val speech = remember {
        PdfSpeech(
            context = context,
            scope = scope,
            onState = { speechState = it },
            onSentence = { start, end, _ ->
                speechRange = if (start >= 0 && end > start) {
                    currentText?.wordRangeForOffsets(start, end)
                } else null
            },
            onFinished = {
                if (selectionSpeech) {
                    speechHolder[0]?.stop()
                } else if (pagerState.currentPage < lastIndex) {
                    autoAdvance = true
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                } else {
                    speechHolder[0]?.stop()
                }
            },
            onError = { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
        ).also { speechHolder[0] = it }
    }
    DisposableEffect(Unit) {
        onDispose {
            speech.release()
            searchJob?.cancel()
        }
    }

    LaunchedEffect(pagerState.currentPage, textSource) {
        selection = null
        speechRange = null
        currentText = textSource?.page(pagerState.currentPage)
        if (speechState != TtsState.IDLE && !autoAdvance) speech.stop()
    }

    LaunchedEffect(currentText) {
        val text = currentText
        if (autoAdvance && text != null) {
            autoAdvance = false
            selectionSpeech = false
            speech.speak(text.text)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { page ->
            zoomed = false
            onPageSettled(page)
        }
    }

    LaunchedEffect(chromeVisible) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chromeVisible) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    fun turn(delta: Int) {
        val target = (pagerState.currentPage + delta).coerceIn(0, lastIndex)
        if (target != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(target) }
    }

    fun next() = turn(if (rtl) -1 else 1)
    fun previous() = turn(if (rtl) 1 else -1)

    fun toggleSpeech() {
        when (speechState) {
            TtsState.PLAYING -> speech.pause()
            TtsState.PAUSED -> speech.resume()
            TtsState.IDLE -> {
                val text = currentText
                if (text == null || text.text.isBlank()) {
                    Toast.makeText(context, "This page has no selectable text.", Toast.LENGTH_SHORT).show()
                } else {
                    selectionSpeech = false
                    autoAdvance = false
                    speech.speak(text.text)
                }
            }
        }
    }

    fun startSearch() {
        val source = textSource ?: return
        val query = searchQuery.trim()
        searchJob?.cancel()
        searchResults = emptyList()
        searchRanges = emptyMap()
        searchProgress = 0
        if (query.isEmpty()) {
            searching = false
            return
        }
        searching = true
        searchJob = scope.launch {
            val found = mutableListOf<PdfSearchResult>()
            val ranges = mutableMapOf<Int, List<IntRange>>()
            for (page in 0 until pageCount) {
                if (!isActive) break
                val text = source.page(page) ?: continue
                val matches = text.findMatches(query)
                if (matches.isNotEmpty()) {
                    ranges[page] = matches.map { it.range }
                    matches.forEach { found.add(PdfSearchResult(page, it.snippet)) }
                    searchRanges = ranges.toMap()
                    searchResults = found.toList()
                }
                searchProgress = page + 1
                if (found.size >= 80) break
            }
            searching = false
        }
    }

    val translationAvailable = ThemePrefs.isOfflineTranslationEnabled(context) && TranslationRuntime.isAvailable()
    val canSpeak = textSource != null

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            reverseLayout = rtl,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize()
        ) { index ->
            DocumentPage(
                loader = loader,
                index = index,
                active = pagerState.currentPage == index,
                pageText = if (pagerState.currentPage == index) currentText else null,
                selection = selection?.takeIf { it.pageIndex == index },
                speechRange = if (pagerState.currentPage == index) speechRange else null,
                searchRanges = if (pagerState.currentPage == index) searchRanges[index].orEmpty() else emptyList(),
                onSelection = { selection = it },
                onZoomChanged = { isZoomed -> if (pagerState.currentPage == index) zoomed = isZoomed },
                onTap = { fraction ->
                    when {
                        fraction < 0.32f -> if (rtl) next() else previous()
                        fraction > 0.68f -> if (rtl) previous() else next()
                        else -> chromeVisible = !chromeVisible
                    }
                }
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            ReaderTopBar(
                title = title,
                page = pagerState.currentPage + 1,
                total = pageCount,
                onBack = onBack,
                onSearch = if (textSource != null) ({ showSearch = true }) else null,
                onOpenQuotes = if (book != null) ({ showQuotes = true }) else null,
                speechState = if (canSpeak) speechState else null,
                onToggleSpeech = if (canSpeak) ({ toggleSpeech() }) else null
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ReaderBottomBar(
                page = pagerState.currentPage,
                total = pageCount,
                rtl = rtl,
                onSeek = { scope.launch { pagerState.scrollToPage(it) } },
                onToggleRtl = {
                    rtl = !rtl
                    onRtlChange(rtl)
                    zoomed = false
                }
            )
        }

        selection?.let { selected ->
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .padding(bottom = if (chromeVisible) 104.dp else 20.dp)
            ) {
                SelectionActions(
                    canTranslate = translationAvailable,
                    onCopy = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.setPrimaryClip(ClipData.newPlainText("PDF text", selected.text))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                        selection = null
                    },
                    onDefine = {
                        dictWord = selected.text
                        dictEntry = null
                        dictLoading = true
                        scope.launch {
                            val entry = withContext(Dispatchers.IO) {
                                WordDictionary.lookup(selected.text, language)
                            }
                            dictEntry = entry
                            dictLoading = false
                        }
                    },
                    onTranslate = { activity?.let { ReaderTranslationDialog(it).show(selected.text) } },
                    onSpeak = {
                        val text = currentText
                        if (text != null) {
                            selectionSpeech = true
                            autoAdvance = false
                            speech.speak(
                                selected.text,
                                baseOffset = text.wordStarts.getOrElse(selected.startWord) { 0 }
                            )
                        }
                    },
                    onQuote = {
                        onSaveQuote(selected.text, selected.pageIndex)
                        selection = null
                    },
                    onSentence = {
                        val text = currentText
                        if (text != null) {
                            val range = text.sentenceRangeFor(selected.startWord)
                            selection = PdfSelection(
                                selected.pageIndex,
                                range.first,
                                range.last,
                                text.textForRange(range)
                            )
                        }
                    },
                    onDismiss = { selection = null }
                )
            }
        }
    }

    if (showSearch) {
        PdfSearchDialog(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            searching = searching,
            progress = searchProgress,
            pageCount = pageCount,
            results = searchResults,
            onSearch = { startSearch() },
            onJump = { page ->
                showSearch = false
                scope.launch { pagerState.scrollToPage(page) }
            },
            onDismiss = {
                searchJob?.cancel()
                searching = false
                showSearch = false
            }
        )
    }

    dictWord?.let { word ->
        DictionaryBottomSheet(
            word = word,
            isLoading = dictLoading,
            entry = dictEntry,
            onOpenInBrowser = {
                val encoded = URLEncoder.encode(word, "UTF-8")
                val prefix = if (language.lowercase().startsWith("it")) "it" else "en"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://$prefix.wiktionary.org/wiki/$encoded"))
                runCatching {
                    if (activity != null) activity.startActivity(intent)
                    else context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
            onDismiss = {
                dictWord = null
                dictEntry = null
            }
        )
    }

    if (showQuotes && book != null) {
        val dao = remember { AppDatabase.getInstance(context).bookDao() }
        val quotes by remember(book.id) { dao.observeQuotes(book.id) }.collectAsState(initial = emptyList())
        QuotesBottomSheet(
            book = book,
            quotes = quotes,
            onOpen = { quote ->
                showQuotes = false
                quote.pageIndex?.let { target -> scope.launch { pagerState.scrollToPage(target) } }
            },
            onDelete = { quote -> scope.launch(Dispatchers.IO) { dao.deleteQuote(quote.id) } },
            onDismiss = { showQuotes = false }
        )
    }
}

@Composable
private fun SelectionActions(
    canTranslate: Boolean,
    onCopy: () -> Unit,
    onDefine: () -> Unit,
    onTranslate: () -> Unit,
    onSpeak: () -> Unit,
    onSentence: () -> Unit,
    onQuote: () -> Unit,
    onDismiss: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onCopy) { Text("Copy") }
            TextButton(onClick = onDefine) { Text("Define") }
            TextButton(onClick = onSentence) { Text("Sentence") }
            TextButton(onClick = onQuote) { Text("Quote") }
            if (canTranslate) TextButton(onClick = onTranslate) { Text("Translate") }
            TextButton(onClick = onSpeak) { Text("Speak") }
            TextButton(onClick = onDismiss) { Text("×") }
        }
    }
}

@Composable
private fun PdfSearchDialog(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    progress: Int,
    pageCount: Int,
    results: List<PdfSearchResult>,
    onSearch: () -> Unit,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find in PDF") },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    label = { Text("Word or phrase") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onSearch, enabled = query.isNotBlank() && !searching) { Text("Search") }
                    Spacer(Modifier.width(12.dp))
                    when {
                        searching -> Text(
                            "Page $progress of $pageCount",
                            style = MaterialTheme.typography.labelMedium
                        )
                        results.isNotEmpty() -> Text(
                            "${results.size} match${if (results.size == 1) "" else "es"}",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
                if (results.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                        results.forEach { result ->
                            TextButton(
                                onClick = { onJump(result.page) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                                    Text(
                                        "Page ${result.page + 1}",
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Text(
                                        result.snippet,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

@Composable
private fun ReaderTopBar(
    title: String,
    page: Int,
    total: Int,
    onBack: () -> Unit,
    onSearch: (() -> Unit)?,
    onOpenQuotes: (() -> Unit)?,
    speechState: TtsState?,
    onToggleSpeech: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.78f))
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
        }
        Text(
            title,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        if (onSearch != null) {
            IconButton(onClick = onSearch) {
                Icon(Icons.Filled.Search, contentDescription = "Find", tint = Color.White)
            }
        }
        if (onOpenQuotes != null) {
            IconButton(
                onClick = onOpenQuotes,
                modifier = Modifier.semantics { contentDescription = "Quotes" }
            ) {
                Text("❝", color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
        }
        if (onToggleSpeech != null) {
            IconButton(onClick = onToggleSpeech) {
                if (speechState == TtsState.PLAYING) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(4.dp, 16.dp).background(Color.White, RoundedCornerShape(1.dp)))
                        Box(Modifier.size(4.dp, 16.dp).background(Color.White, RoundedCornerShape(1.dp)))
                    }
                } else {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = "Read aloud",
                        tint = Color.White
                    )
                }
            }
        }
        Text(
            "$page / $total",
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 12.dp)
        )
    }
}

@Composable
private fun ReaderBottomBar(
    page: Int,
    total: Int,
    rtl: Boolean,
    onSeek: (Int) -> Unit,
    onToggleRtl: () -> Unit
) {
    var dragging by remember { mutableStateOf(false) }
    var sliderValue by remember(page) { mutableFloatStateOf(page.toFloat()) }
    // Keep the tapped page until the pager reports it, so the thumb does not
    // snap back to the old position on a tap.
    var pendingPage by remember { mutableStateOf<Float?>(null) }
    val lastIndex = (total - 1).coerceAtLeast(1)
    LaunchedEffect(page) { pendingPage = null }
    LaunchedEffect(pendingPage) {
        if (pendingPage != null) {
            delay(1200)
            pendingPage = null
        }
    }
    val shown = when {
        dragging -> sliderValue
        pendingPage != null -> pendingPage ?: page.toFloat()
        else -> page.toFloat()
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.82f))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Slider(
            value = shown,
            onValueChange = {
                sliderValue = it
                dragging = true
            },
            onValueChangeFinished = {
                dragging = false
                pendingPage = sliderValue
                onSeek(sliderValue.roundToInt())
            },
            valueRange = 0f..lastIndex.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onToggleRtl) {
                Text(if (rtl) "Right to left" else "Left to right", color = Color.White)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${shown.roundToInt().coerceIn(0, (total - 1).coerceAtLeast(0)) + 1} / $total",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun DocumentPage(
    loader: PageLoader,
    index: Int,
    active: Boolean,
    pageText: PdfPageText?,
    selection: PdfSelection?,
    speechRange: IntRange?,
    searchRanges: List<IntRange>,
    onSelection: (PdfSelection?) -> Unit,
    onZoomChanged: (Boolean) -> Unit,
    onTap: (Float) -> Unit
) {
    val state by produceState<PageState>(initialValue = PageState.Loading, index) {
        value = loader.load(index)?.let { PageState.Ready(it) } ?: PageState.Failed
    }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var pan by remember(index) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val context = LocalContext.current
    val activeState by rememberUpdatedState(active)
    val onZoomState by rememberUpdatedState(onZoomChanged)
    val onTapState by rememberUpdatedState(onTap)
    val onSelectionState by rememberUpdatedState(onSelection)
    val selectionState by rememberUpdatedState(selection)

    fun clampPan(candidate: Offset, currentScale: Float): Offset {
        if (currentScale <= 1f || size.width == 0) return Offset.Zero
        val maxX = size.width * (currentScale - 1f) / 2f
        val maxY = size.height * (currentScale - 1f) / 2f
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    fun reset() {
        scale = 1f
        pan = Offset.Zero
        if (activeState) onZoomState(false)
    }

    fun pagePoint(position: Offset): Offset? {
        val text = pageText ?: return null
        if (size.width == 0 || size.height == 0 || text.pageWidth <= 0f || text.pageHeight <= 0f) return null
        val fit = min(size.width / text.pageWidth, size.height / text.pageHeight)
        val dx = (size.width - text.pageWidth * fit) / 2f
        val dy = (size.height - text.pageHeight * fit) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val content = if (scale <= 1f) {
            position
        } else {
            Offset(
                (position.x - center.x - pan.x) / scale + center.x,
                (position.y - center.y - pan.y) / scale + center.y
            )
        }
        return Offset((content.x - dx) / fit, (content.y - dy) / fit)
    }

    fun wordAt(position: Offset): Int? {
        val text = pageText ?: return null
        val point = pagePoint(position) ?: return null
        var best = -1
        var bestDistance = Float.MAX_VALUE
        text.words.forEachIndexed { i, word ->
            val dx = max(0f, max(word.x - point.x, point.x - (word.x + word.width)))
            val dy = max(0f, max(word.y - point.y, point.y - (word.y + word.height)))
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return if (best >= 0 && bestDistance <= 48f * 48f) best else null
    }

    fun selectWordAt(position: Offset): Boolean {
        val text = pageText ?: return false
        val word = wordAt(position) ?: return false
        onSelectionState(PdfSelection(index, word, word, text.textForRange(word..word)))
        return true
    }

    LaunchedEffect(active) {
        if (!active) reset()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            // The tap detector is outermost so its long-press sees the down
            // before inner detectors consume it. The zoom detector sits in the
            // middle; the selection drag detector is innermost.
            .pointerInput(index, pageText) {
                detectTapGestures(
                    onTap = { position ->
                        if (scale <= 1.01f && size.width > 0) {
                            if (selectionState != null) onSelectionState(null)
                            else onTapState(position.x / size.width)
                        }
                    },
                    onDoubleTap = { position ->
                        if (scale > 1.01f) {
                            reset()
                        } else {
                            val target = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = target
                            pan = clampPan((center - position) * (target - 1f), target)
                            if (activeState) onZoomState(true)
                        }
                    },
                    onLongPress = { position ->
                        val text = pageText
                        when {
                            text == null -> Toast.makeText(
                                context,
                                "Text is still loading or unavailable for this page.",
                                Toast.LENGTH_SHORT
                            ).show()
                            text.words.isEmpty() -> Toast.makeText(
                                context,
                                "This page has no selectable text.",
                                Toast.LENGTH_SHORT
                            ).show()
                            !selectWordAt(position) -> Toast.makeText(
                                context,
                                "No word found there. Hold directly on a word.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
            .pointerInput(index) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var event: PointerEvent
                    do {
                        event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed > 1 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                            val newPan = clampPan(pan + event.calculatePan(), newScale)
                            scale = newScale
                            pan = newPan
                            if (activeState) onZoomState(newScale > 1.01f)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = pan.x
                    translationY = pan.y
                }
        ) {
            when (val current = state) {
                is PageState.Ready -> Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
                PageState.Loading -> CircularProgressIndicator(
                    color = Color.White.copy(alpha = 0.6f),
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(28.dp).align(Alignment.Center)
                )
                PageState.Failed -> Text(
                    "This page could not be read.",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            val text = pageText
            if (text != null && text.words.isNotEmpty()) {
                Canvas(Modifier.fillMaxSize()) {
                    if (text.pageWidth <= 0f || text.pageHeight <= 0f) return@Canvas
                    val fit = min(size.width / text.pageWidth, size.height / text.pageHeight)
                    val dx = (size.width - text.pageWidth * fit) / 2f
                    val dy = (size.height - text.pageHeight * fit) / 2f
                    fun drawRange(range: IntRange, color: Color) {
                        val first = range.first.coerceIn(0, text.words.lastIndex)
                        val last = range.last.coerceIn(first, text.words.lastIndex)
                        for (i in first..last) {
                            val word = text.words[i]
                            drawRect(
                                color = color,
                                topLeft = Offset(dx + word.x * fit, dy + word.y * fit),
                                size = Size(word.width * fit, word.height * fit)
                            )
                        }
                    }
                    searchRanges.forEach { drawRange(it, Color(0x55FF9800)) }
                    speechRange?.let { drawRange(it, Color(0x66FFEB3B)) }
                    selection?.let { drawRange(it.startWord..it.endWord, Color(0x663B82F6)) }
                }
            }
        }
    }
}

private fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}

@Composable
private fun ErrorScreen(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Could not open this file", color = Color.White, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onBack) { Text("Back to library") }
    }
}
