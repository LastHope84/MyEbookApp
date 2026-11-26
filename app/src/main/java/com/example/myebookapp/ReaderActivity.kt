package com.example.myebookapp

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import android.view.ActionMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.css.Color as ReadiumColor
import org.readium.r2.navigator.epub.css.RsProperties
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation
import org.readium.r2.shared.publication.services.positions
import org.readium.r2.shared.publication.services.search.search
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import java.net.URLEncoder
import kotlin.math.roundToInt

data class TocEntry(
    val link: Link,
    val title: String,
    val depth: Int
)

@OptIn(ExperimentalReadiumApi::class)
class ReaderActivity : FragmentActivity() {

    companion object {
        private const val NAV_TAG = "epub_navigator"
        private const val AUTO_HIDE_MS = 6000L
        private const val MENU_DEFINE_ID = 9001
        private const val MENU_TRANSLATE_ID = 9002
        private const val MENU_QUOTE_ID = 9003

        // Readium calls pagination after the resource is ready, but images/fonts can
        // still change its layout. An uncertain rendering must not finish the book.
        private const val READY_SCRIPT = """
            (() => {
                if (document.readyState !== 'complete' || !document.documentElement ||
                    !document.documentElement.clientWidth || !document.documentElement.clientHeight ||
                    (document.fonts && document.fonts.status !== 'loaded')) return false;
                return Array.from(document.images).every(i => i.complete && i.naturalWidth > 0);
            })()
        """
        private const val END_SCROLL_SCRIPT = """
            (() => {
                const e = document.scrollingElement;
                if (document.readyState !== 'complete' || !e || !e.clientWidth || !e.clientHeight ||
                    (document.fonts && document.fonts.status !== 'loaded') ||
                    !Array.from(document.images).every(i => i.complete && i.naturalWidth > 0)) return false;
                // Only conventional vertical scroll, never guess for horizontal overflow.
                return e.scrollWidth <= e.clientWidth + 1 &&
                    Math.ceil(e.scrollTop + e.clientHeight) >= e.scrollHeight;
            })()
        """
        // Returns the text ending at the bottom edge of the visible page, so read-aloud
        // can turn exactly when the voice reaches that line.
        private const val BOTTOM_TEXT_SCRIPT = """
            (() => {
                try {
                    const x = Math.floor(window.innerWidth / 2);
                    const ys = [window.innerHeight - 20, window.innerHeight - 60,
                                Math.floor(window.innerHeight * 0.85)];
                    for (const y of ys) {
                        if (!document.caretRangeFromPoint) continue;
                        const r = document.caretRangeFromPoint(x, y);
                        if (!r || !r.startContainer || !r.startContainer.textContent) continue;
                        const t = r.startContainer.textContent;
                        const end = r.startOffset;
                        const start = Math.max(0, end - 80);
                        const snippet = t.substring(start, end);
                        if (snippet.trim().length > 10) return encodeURIComponent(snippet);
                    }
                    return '';
                } catch (e) { return ''; }
            })()
        """
    }

    private var publication: Publication? = null
    private var ttsState by mutableStateOf(TtsState.IDLE)
    private var ttsController: TtsController? = null
    private var showFinishedCard by mutableStateOf(false)
    private var allPositions: List<Locator> = emptyList()
    private var containerId: Int = View.NO_ID
    private var currentBookUriString: String? = null
    private var bookEntity: BookEntity? = null
    private var bookLanguage: String = "it"
    private var lastPreferenceChangeAt: Long = 0L

    private lateinit var rootLayout: FrameLayout
    private lateinit var warmTintView: View

    private var bookTitle by mutableStateOf("Opening book...")
    private var chapterTitle by mutableStateOf("")
    private var isLoading by mutableStateOf(true)
    private var errorMessage by mutableStateOf<String?>(null)
    private var showControls by mutableStateOf(true)
    private var showToc by mutableStateOf(false)
    private var showQuotes by mutableStateOf(false)
    private var showAppearance by mutableStateOf(false)
    private var showSearch by mutableStateOf(false)
    private var tocEntries by mutableStateOf<List<TocEntry>>(emptyList())

    private var currentPosition by mutableIntStateOf(0)
    private var totalPositions by mutableIntStateOf(0)
    private var progressPercent by mutableIntStateOf(0)
    private var interactionTick by mutableIntStateOf(0)

    private var settings by mutableStateOf(ReaderSettings())
    private var appThemeMode by mutableStateOf(AppThemeMode.AUTO)

    private var searchQuery by mutableStateOf("")
    private var isSearchingBook by mutableStateOf(false)
    private var searchResults by mutableStateOf<List<Locator>>(emptyList())

    private var dictWord by mutableStateOf<String?>(null)
    private var dictLoading by mutableStateOf(false)
    private var dictEntry by mutableStateOf<DictionaryEntry?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)

        val uriStr = intent?.getStringExtra("book_uri") ?: intent?.dataString
        val bookUri = uriStr?.let { Uri.parse(it) }
        if (bookUri == null) {
            finish()
            return
        }
        currentBookUriString = bookUri.toString()

        appThemeMode = ThemePrefs.getMode(this)
        settings = ReaderSettingsStore.load(this)
        containerId = View.generateViewId()

        rootLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(settings.pageTheme.windowBgInt)
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val fragmentContainer = FragmentContainerView(this).apply {
            id = containerId
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        warmTintView = View(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
        }

        val topOverlay = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
            setContent {
                val forceDark = settings.pageTheme.isDarkChrome
                MyEbookTheme(
                    mode = if (forceDark) AppThemeMode.DARK else AppThemeMode.LIGHT,
                    forceDark = forceDark
                ) {
                    val anySheetOpen = showToc || showAppearance || showSearch || showQuotes || dictWord != null

                    LaunchedEffect(showControls, anySheetOpen, isLoading, errorMessage, interactionTick) {
                        applyImmersiveSystemBars()
                        if (showControls && !anySheetOpen && !isLoading && errorMessage == null) {
                            delay(AUTO_HIDE_MS)
                            showControls = false
                            applyImmersiveSystemBars()
                        }
                    }

                    AnimatedVisibility(
                        visible = showControls || isLoading || errorMessage != null,
                        enter = slideInVertically { -it } + fadeIn(),
                        exit = slideOutVertically { -it } + fadeOut()
                    ) {
                        ReaderMinimalTopBar(
                            title = bookTitle,
                            chapterTitle = chapterTitle,
                            isLoading = isLoading,
                            errorMessage = errorMessage,
                            onBack = { finish() },
                            onOpenSearch = {
                                interactionTick++
                                showSearch = true
                            },
                            onOpenToc = {
                                interactionTick++
                                showToc = true
                            },
                            onToggleOrientation = {
                                val next = if (settings.orientation == ReaderOrientationMode.LANDSCAPE) {
                                    ReaderOrientationMode.PORTRAIT
                                } else {
                                    ReaderOrientationMode.LANDSCAPE
                                }
                                val updated = settings.copy(orientation = next)
                                settings = updated
                                ReaderSettingsStore.save(this@ReaderActivity, updated)
                                applyAllSettings()
                            },
                            onOpenAppearance = {
                                interactionTick++
                                showAppearance = true
                            },
                            onOpenQuotes = if (bookEntity != null) ({ interactionTick++; showQuotes = true }) else null,
                            ttsState = ttsState,
                            onToggleTts = { toggleTts() }
                        )
                    }

                    val savedBook = bookEntity
                    if (showQuotes && savedBook != null) {
                        val quotes by remember(savedBook.id) {
                            AppDatabase.getInstance(this@ReaderActivity).bookDao().observeQuotes(savedBook.id)
                        }.collectAsState(initial = emptyList())
                        QuotesBottomSheet(
                            book = savedBook,
                            quotes = quotes,
                            onOpen = { quote ->
                                showQuotes = false
                                jumpToQuote(quote)
                            },
                            onDelete = { quote ->
                                lifecycleScope.launch(Dispatchers.IO) {
                                    AppDatabase.getInstance(this@ReaderActivity).bookDao().deleteQuote(quote.id)
                                }
                            },
                            onDismiss = {
                                showQuotes = false
                                interactionTick++
                            }
                        )
                    }

                    if (showAppearance) {
                        AppearanceBottomSheet(
                            settings = settings,
                            onUpdate = { updated ->
                                settings = updated
                                ReaderSettingsStore.save(this@ReaderActivity, updated)
                                applyAllSettings()
                            },
                            onDismiss = {
                                showAppearance = false
                                interactionTick++
                            }
                        )
                    }

                    if (showToc) {
                        TocBottomSheet(
                            entries = tocEntries,
                            onDismiss = {
                                showToc = false
                                interactionTick++
                            },
                            onSelect = { entry ->
                                showToc = false
                                showControls = false
                                val nav = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment
                                nav?.go(entry.link, animated = settings.pageTheme != ReaderPageTheme.EINK)
                            }
                        )
                    }

                    if (showSearch) {
                        InBookSearchSheet(
                            query = searchQuery,
                            isSearching = isSearchingBook,
                            results = searchResults,
                            onQueryChange = { searchQuery = it },
                            onRunSearch = { runInBookSearch(searchQuery) },
                            onSelectResult = { loc ->
                                showSearch = false
                                showControls = false
                                val nav = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment
                                nav?.go(loc, animated = settings.pageTheme != ReaderPageTheme.EINK)
                            },
                            onDismiss = {
                                showSearch = false
                                interactionTick++
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
                                val langPrefix = if (bookLanguage.lowercase().startsWith("it")) "it" else "en"
                                val url = "https://$langPrefix.wiktionary.org/wiki/$encoded"
                                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                            },
                            onDismiss = {
                                dictWord = null
                                interactionTick++
                            }
                        )
                    }
                }
            }
        }

        val bottomOverlay = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            )
            setContent {
                val forceDark = settings.pageTheme.isDarkChrome
                MyEbookTheme(
                    mode = if (forceDark) AppThemeMode.DARK else AppThemeMode.LIGHT,
                    forceDark = forceDark
                ) {
                    if (showFinishedCard) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    "You finished this book",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { finish() }) { Text("Back to library") }
                                    TextButton(onClick = { showFinishedCard = false }) { Text("Keep reading") }
                                }
                            }
                        }
                    }
                    if (showControls && !isLoading && errorMessage == null) {
                        ReaderBottomScrubberBar(
                            currentPosition = currentPosition,
                            totalPositions = totalPositions,
                            progressPercent = progressPercent,
                            onSeekFraction = { frac ->
                                interactionTick++
                                seekToFraction(frac)
                            },
                            onPrevPage = {
                                interactionTick++
                                val nav = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment
                                nav?.goBackward(animated = settings.pageTheme != ReaderPageTheme.EINK)
                            },
                            onNextPage = {
                                interactionTick++
                                val nav = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment
                                nav?.goForward(animated = settings.pageTheme != ReaderPageTheme.EINK)
                            }
                        )
                    } else if (!isLoading && errorMessage == null && settings.showMiniFooter) {
                        ReaderQuietFooter(
                            chapterTitle = chapterTitle,
                            currentPosition = currentPosition,
                            totalPositions = totalPositions,
                            progressPercent = progressPercent,
                            pageTheme = settings.pageTheme
                        )
                    }
                }
            }
        }

        rootLayout.addView(fragmentContainer)
        rootLayout.addView(warmTintView)
        rootLayout.addView(topOverlay)
        rootLayout.addView(bottomOverlay)
        setContentView(rootLayout)

        applyWindowSettings()
        openBook(bookUri)
    }

    private fun openBook(bookUri: Uri) {
        lifecycleScope.launch {
            val openResult = withContext(Dispatchers.IO) {
                runCatching {
                    val tempFile = File(cacheDir, "current_book.epub")
                    val inputStream = if (bookUri.scheme == "file") {
                        bookUri.path?.let { File(it).inputStream() }
                    } else {
                        contentResolver.openInputStream(bookUri)
                    } ?: error("Permission expired or file moved — please pick this book again.")

                    inputStream.use { input ->
                        tempFile.outputStream().use { output -> input.copyTo(output) }
                    }

                    val dao = AppDatabase.getInstance(this@ReaderActivity).bookDao()
                    val dbBook = dao.getByUri(bookUri.toString())
                    bookEntity = dbBook
                    if (dbBook != null && !dbBook.language.equals("Unknown", ignoreCase = true)) {
                        bookLanguage = dbBook.language
                    }

                    // A saved quote reopens at its exact passage instead of the last position.
                    val savedLocatorJson = intent?.getStringExtra("quote_locator")
                        ?: dbBook?.lastLocatorJson
                        ?: BookHistoryStore.getLocatorJson(this@ReaderActivity, bookUri.toString())
                    val savedLocator = savedLocatorJson?.let { json ->
                        runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
                    }

                    val now = System.currentTimeMillis()
                    dao.touchOpened(bookUri.toString(), now)
                    BookHistoryStore.touchOpened(this@ReaderActivity, bookUri.toString())

                    val url = AbsoluteUrl(Uri.fromFile(tempFile).toString())
                        ?: error("Invalid book file URL.")

                    val httpClient = DefaultHttpClient()
                    val assetRetriever = AssetRetriever(contentResolver, httpClient)
                    val asset = assetRetriever.retrieve(url).getOrElse { err ->
                        error("Could not load EPUB: ${err.message}")
                    }

                    val opener = PublicationOpener(
                        publicationParser = DefaultPublicationParser(
                            context = this@ReaderActivity,
                            httpClient = httpClient,
                            assetRetriever = assetRetriever,
                            pdfFactory = null
                        )
                    )
                    val pub = opener.open(asset, allowUserInteraction = false).getOrElse { err ->
                        error("Could not open EPUB: ${err.message}")
                    }

                    val positions = runCatching { pub.positions() }.getOrElse { emptyList() }
                    Triple(pub, savedLocator, positions)
                }
            }

            openResult.onSuccess { (pub, savedLocator, positions) ->
                if (isFinishing || isDestroyed) {
                    pub.close()
                    return@onSuccess
                }
                publication = pub
                allPositions = positions
                totalPositions = positions.size
                bookTitle = pub.metadata.title ?: "Reading"
                pub.metadata.languages.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
                    bookLanguage = it
                }

                tocEntries = if (pub.tableOfContents.isNotEmpty()) {
                    flattenToc(pub.tableOfContents)
                } else {
                    pub.readingOrder.mapIndexed { idx, link ->
                        TocEntry(
                            link = link,
                            title = link.title?.takeIf { it.isNotBlank() } ?: "Section ${idx + 1}",
                            depth = 0
                        )
                    }
                }

                savedLocator?.let { updateProgressState(it, pub) }

                val selectionCallback = object : ActionMode.Callback {
                    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                        menu.add(0, MENU_DEFINE_ID, 0, "Define")
                        menu.add(0, MENU_QUOTE_ID, 1, "Save quote")
                        if (ThemePrefs.isOfflineTranslationEnabled(this@ReaderActivity) && TranslationRuntime.isAvailable()) {
                            menu.add(0, MENU_TRANSLATE_ID, 2, "Translate offline")
                        }
                        return true
                    }

                    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
                        menu.findItem(MENU_TRANSLATE_ID)?.isVisible =
                            ThemePrefs.isOfflineTranslationEnabled(this@ReaderActivity) && TranslationRuntime.isAvailable()
                        return true
                    }

                    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                        if (item.itemId == MENU_DEFINE_ID) {
                            lookupSelectedWord()
                            mode.finish()
                            return true
                        }
                        if (item.itemId == MENU_QUOTE_ID) {
                            saveSelectionQuote(mode)
                            return true
                        }
                        if (item.itemId == MENU_TRANSLATE_ID) {
                            if (ThemePrefs.isOfflineTranslationEnabled(this@ReaderActivity) && TranslationRuntime.isAvailable()) {
                                translateSelection(mode)
                            } else {
                                mode.finish()
                            }
                            return true
                        }
                        return false
                    }

                    override fun onDestroyActionMode(mode: ActionMode) {}
                }

                // Do this only after the publication has opened successfully. Finished books
                // stay finished when reopened; the user can still change a shelf manually.
                withContext(Dispatchers.IO) {
                    AppDatabase.getInstance(this@ReaderActivity).bookDao().markStarted(bookUri.toString())
                }

                // The final reading-order resource, not the last TOC entry or an approximate
                // positions/progress value, includes any back matter in the EPUB spine.
                val lastHref = ShelfEndDetector.uniqueLastHref(pub.readingOrder.map { it.href.toString() })
                val fixedLayout = pub.metadata.presentation.layout == EpubLayout.FIXED
                var finishedHere = false
                var endCheck: Job? = null

                fun isLastResource(locator: Locator): Boolean =
                    ShelfEndDetector.isLastResource(lastHref, locator.href.toString())

                fun finishOnce() {
                    if (finishedHere) return
                    finishedHere = true
                    showFinishedCard = true
                    lifecycleScope.launch(Dispatchers.IO) {
                        AppDatabase.getInstance(this@ReaderActivity).bookDao().markFinished(bookUri.toString())
                    }
                }

                val paginationListener = object : EpubNavigatorFragment.PaginationListener {
                    override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                        if (fixedLayout) return
                        endCheck?.cancel()
                        if (!isLastResource(locator)) {
                            finishedHere = false
                            return
                        }
                        val nav = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment ?: return
                        val config = nav.settings.value
                        // Readium's page index is physical, so RTL ends at index zero.
                        // Vertical writing uses a different axis; fail closed for that case.
                        if (config.verticalText) return
                        if (!config.scroll) {
                            val lastPage = ShelfEndDetector.isLastPage(
                                pageIndex, totalPages, config.readingProgression == ReadingProgression.RTL
                            )
                            if (!lastPage) {
                                finishedHere = false
                                return
                            }
                        }
                        endCheck = lifecycleScope.launch {
                            // Give Readium's location and layout time to settle, then ensure the
                            // currently rendered resource is still the one from this callback.
                            delay(200)
                            if (nav.currentLocator.value != locator || nav.settings.value != config) return@launch
                            val script = if (config.scroll) END_SCROLL_SCRIPT else READY_SCRIPT
                            val rendered = try {
                                nav.evaluateJavascript(script)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            }
                            if (rendered != "true") {
                                if (config.scroll) finishedHere = false
                                return@launch
                            }
                            val visible = try {
                                nav.firstVisibleElementLocator()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                null
                            } ?: return@launch
                            if (isLastResource(visible) && nav.currentLocator.value == locator && nav.settings.value == config) {
                                finishOnce()
                            }
                        }
                    }
                }

                val navigatorFactory = EpubNavigatorFactory(pub)
                supportFragmentManager.fragmentFactory = navigatorFactory.createFragmentFactory(
                    initialLocator = savedLocator,
                    initialPreferences = settings.toEpubPreferences(),
                    paginationListener = paginationListener,
                    configuration = EpubNavigatorFragment.Configuration(
                        readiumCssRsProperties = RsProperties(
                            // Translucent so the page's own text colour stays readable
                            // on light, sepia and dark reader themes alike.
                            selectionBackgroundColor = ReadiumColor.Int(0x663B82F6)
                        ),
                        selectionActionModeCallback = selectionCallback
                    )
                )
                supportFragmentManager.beginTransaction()
                    .replace(containerId, EpubNavigatorFragment::class.java, Bundle(), NAV_TAG)
                    .commitNowAllowingStateLoss()

                val navigator = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment
                navigator?.apply {
                    addInputListener(DirectionalNavigationAdapter(this))
                    addInputListener(object : InputListener {
                        override fun onTap(event: TapEvent): Boolean {
                            showControls = !showControls
                            if (showControls) interactionTick++
                            applyImmersiveSystemBars()
                            return true
                        }
                    })

                    lifecycleScope.launch {
                        currentLocator.drop(1).collect { locator ->
                            if (fixedLayout) {
                                // Fixed-layout resources are pages. Readium reports the left
                                // resource of a spread, so a final right-hand page may be missed.
                                if (isLastResource(locator)) finishOnce() else finishedHere = false
                            }
                            val recentlyChangedPrefs =
                                System.currentTimeMillis() - lastPreferenceChangeAt < 1000L
                            if (showControls && !recentlyChangedPrefs) {
                                showControls = false
                                applyImmersiveSystemBars()
                            }
                            val (_, progressRatio) = updateProgressState(locator, pub)
                            saveLocatorNow(locator, progressRatio)
                        }
                    }
                }

                isLoading = false
                showControls = false
                applyImmersiveSystemBars()
            }.onFailure { err ->
                isLoading = false
                showControls = true
                errorMessage = err.localizedMessage ?: "Failed to open EPUB."
            }
        }
    }

    private fun updateProgressState(locator: Locator, pub: Publication): Pair<Int, Double> {
        val pos = locator.locations.position ?: 0
        val rawTotal = locator.locations.totalProgression ?: 0.0
        val lastIndex = pub.readingOrder.lastIndex
        val curResIdx = pub.readingOrder.indexOfFirst { it.href.toString() == locator.href.toString() }
        val chapterProg = locator.locations.progression ?: 0.0

        val atEnd = (totalPositions > 0 && pos >= totalPositions) ||
            rawTotal >= 0.992 ||
            (lastIndex >= 0 && curResIdx == lastIndex && chapterProg >= 0.85)

        val pct = if (atEnd) 100 else (rawTotal * 100).roundToInt().coerceIn(0, 99)
        val ratio = if (atEnd) 1.0 else rawTotal.coerceIn(0.0, 0.99)

        currentPosition = if (atEnd && totalPositions > 0) totalPositions else pos
        progressPercent = pct
        chapterTitle = locator.title?.takeIf { it.isNotBlank() }
            ?: tocEntries.firstOrNull { it.link.href.toString().substringBefore('#') == locator.href.toString().substringBefore('#') }?.title
            ?: ""

        return pct to ratio
    }

    private fun seekToFraction(fraction: Float) {
        val nav = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment ?: return
        if (allPositions.isNotEmpty()) {
            val idx = (fraction * (allPositions.size - 1)).roundToInt().coerceIn(0, allPositions.lastIndex)
            nav.go(allPositions[idx], animated = false)
        }
    }

    private fun runInBookSearch(query: String) {
        val pub = publication ?: return
        val trimmed = query.trim()
        if (trimmed.isBlank()) return

        isSearchingBook = true
        searchResults = emptyList()

        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                val collected = mutableListOf<Locator>()
                runCatching {
                    val iter = pub.search(trimmed) ?: return@runCatching
                    try {
                        while (collected.size < 60) {
                            val page = iter.next().getOrNull() ?: break
                            collected.addAll(page.locators)
                        }
                    } finally {
                        iter.close()
                    }
                }
                collected
            }
            isSearchingBook = false
            searchResults = found
        }
    }

    private fun jumpToQuote(quote: QuoteEntity) {
        val nav = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment ?: return
        val locator = quote.locatorJson?.let { json ->
            runCatching { Locator.fromJSON(JSONObject(json)) }.getOrNull()
        } ?: return
        nav.go(locator)
        showControls = false
        applyImmersiveSystemBars()
    }

    private fun saveSelectionQuote(mode: ActionMode) {
        lifecycleScope.launch {
            val nav = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment
            val locator = try {
                nav?.currentSelection()?.locator
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                mode.finish()
            }
            val text = QuoteText.normalize(locator?.text?.highlight ?: "")
            if (locator == null || text == null) {
                android.widget.Toast.makeText(this@ReaderActivity, "Could not read the selected text.", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            nav?.clearSelection()
            val saved = withContext(Dispatchers.IO) {
                val dao = AppDatabase.getInstance(this@ReaderActivity).bookDao()
                val book = currentBookUriString?.let { dao.getByUri(it) } ?: return@withContext false
                dao.insertQuote(
                    QuoteEntity(
                        bookId = book.id,
                        text = text,
                        locatorJson = locator.toJSON().toString(),
                        label = locator.title?.takeIf { it.isNotBlank() }
                            ?: chapterTitle.takeIf { it.isNotBlank() }
                    )
                )
                true
            }
            android.widget.Toast.makeText(
                this@ReaderActivity,
                if (saved) "Quote saved" else "This book is not in the library anymore.",
                android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun translateSelection(mode: ActionMode) {
        lifecycleScope.launch {
            val nav = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment
            val selected = try {
                nav?.currentSelection()?.locator?.text?.highlight?.trim()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                mode.finish()
            }
            if (selected.isNullOrBlank()) {
                android.widget.Toast.makeText(this@ReaderActivity, "Could not read the selected text.", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            nav?.clearSelection()
            if (!ThemePrefs.isOfflineTranslationEnabled(this@ReaderActivity) || !TranslationRuntime.isAvailable()) return@launch
            ReaderTranslationDialog(this@ReaderActivity).show(selected)
        }
    }

    private fun lookupSelectedWord() {
        lifecycleScope.launch {
            val nav = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment ?: return@launch
            val selected = nav.currentSelection()?.locator?.text?.highlight?.trim()
            nav.clearSelection()
            if (selected.isNullOrBlank()) return@launch

            dictWord = selected
            dictLoading = true
            dictEntry = null

            val result = withContext(Dispatchers.IO) {
                WordDictionary.lookup(selected, bookLanguage)
            }
            dictLoading = false
            dictEntry = result
        }
    }

    private fun applyAllSettings() {
        lastPreferenceChangeAt = System.currentTimeMillis()
        applyWindowSettings()
        val nav = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment
        nav?.submitPreferences(settings.toEpubPreferences())
    }

    private fun applyWindowSettings() {
        rootLayout.setBackgroundColor(settings.pageTheme.windowBgInt)

        @Suppress("DEPRECATION")
        window.statusBarColor = settings.pageTheme.windowBgInt
        @Suppress("DEPRECATION")
        window.navigationBarColor = settings.pageTheme.windowBgInt
        window.decorView.setBackgroundColor(settings.pageTheme.windowBgInt)

        val alpha = (settings.warmTint * 165).roundToInt().coerceIn(0, 120)
        warmTintView.setBackgroundColor(AndroidColor.argb(alpha, 255, 145, 35))

        val lp = window.attributes
        lp.screenBrightness = if (settings.brightness >= 0f) {
            settings.brightness.coerceIn(0.05f, 1.0f)
        } else {
            WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        window.attributes = lp

        if (settings.keepScreenAwake) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        requestedOrientation = settings.orientation.androidMode
        applyImmersiveSystemBars()
    }

    private fun applyImmersiveSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !settings.pageTheme.isDarkChrome
        controller.isAppearanceLightNavigationBars = !settings.pageTheme.isDarkChrome

        val anySheet = showToc || showAppearance || showSearch || dictWord != null
        if (settings.immersiveMode && !showControls && !anySheet && !isLoading) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val anySheet = showToc || showAppearance || showSearch || dictWord != null
        if (settings.volumeKeysTurnPages && !anySheet && !isLoading) {
            val nav = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment
            val animate = settings.pageTheme != ReaderPageTheme.EINK
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> {
                    nav?.goForward(animated = animate)
                    if (showControls) showControls = false
                    return true
                }
                KeyEvent.KEYCODE_VOLUME_UP -> {
                    nav?.goBackward(animated = animate)
                    if (showControls) showControls = false
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        val anySheet = showToc || showAppearance || showSearch || dictWord != null
        if (settings.volumeKeysTurnPages && !anySheet && !isLoading &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun flattenToc(links: List<Link>, depth: Int = 0): List<TocEntry> {
        val result = mutableListOf<TocEntry>()
        for (link in links) {
            val label = link.title?.takeIf { it.isNotBlank() } ?: "Chapter"
            result.add(TocEntry(link = link, title = label, depth = depth))
            if (link.children.isNotEmpty()) {
                result.addAll(flattenToc(link.children, depth + 1))
            }
        }
        return result
    }

    private fun saveLocatorNow(locator: Locator, progressRatio: Double) {
        val uriStr = currentBookUriString ?: return
        val json = locator.toJSON().toString()
        val now = System.currentTimeMillis()
        val appCtx = applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getInstance(appCtx).bookDao()
                .updateReadingPosition(uriStr, json, progressRatio, now)
            BookHistoryStore.saveLocator(appCtx, uriStr, json, progressRatio)
        }
    }

    override fun onPause() {
        super.onPause()
        val nav = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment
        val pub = publication
        val loc = nav?.currentLocator?.value
        if (loc != null && pub != null) {
            val (_, ratio) = updateProgressState(loc, pub)
            saveLocatorNow(loc, ratio)
        }
    }

    private fun toggleTts() {
        val nav = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment ?: return
        val pub = publication ?: return
        val controller = ttsController ?: TtsController(
            context = this,
            publication = pub,
            scope = lifecycleScope,
            onStateChanged = { state ->
                ttsState = state
                TtsPlayback.state = state
                syncTtsService(state)
            },
            onTurnPage = { nav.goForward(animated = false) },
            onBottomText = {
                runCatching {
                    val raw = nav.evaluateJavascript(BOTTOM_TEXT_SCRIPT).orEmpty().trim().trim('"')
                    if (raw.isEmpty()) null else java.net.URLDecoder.decode(raw, "UTF-8")
                }.getOrNull()
            },
            onChapterChanged = { link -> nav.go(link, animated = false) },
            onHighlight = { locator ->
                val decorations = locator?.let {
                    listOf(
                        org.readium.r2.navigator.Decoration(
                            "tts-highlight",
                            it,
                            org.readium.r2.navigator.Decoration.Style.Highlight(tint = 0x55FFC107)
                        )
                    )
                } ?: emptyList()
                lifecycleScope.launch {
                    runCatching { nav.applyDecorations(decorations, "tts") }
                }
            },
            onError = { message ->
                ttsState = TtsState.IDLE
                TtsPlayback.state = TtsState.IDLE
                syncTtsService(TtsState.IDLE)
                android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
            }
        ).also { ttsController = it }
        TtsPlayback.controller = controller
        TtsPlayback.title = bookTitle
        TtsPlayback.bookUri = currentBookUriString
        when (controller.currentState) {
            TtsState.PLAYING -> controller.pause()
            TtsState.PAUSED -> controller.resume()
            TtsState.IDLE -> controller.start(nav.currentLocator.value)
        }
    }

    private fun syncTtsService(state: TtsState) {
        if (state == TtsState.IDLE) {
            if (TtsPlayback.serviceRunning) TtsService.stop(this)
        } else if (TtsPlayback.serviceRunning) {
            startService(Intent(this, TtsService::class.java).setAction(TtsService.ACTION_UPDATE))
        } else {
            TtsService.start(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        TtsPlayback.controller = null
        TtsPlayback.state = TtsState.IDLE
        if (TtsPlayback.serviceRunning) TtsService.stop(this)
        ttsController?.release()
        ttsController = null
        publication?.close()
    }
}
