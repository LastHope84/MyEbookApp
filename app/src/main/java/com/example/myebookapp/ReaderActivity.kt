package com.example.myebookapp

import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.navigator.util.DirectionalNavigationAdapter
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File
import kotlin.math.roundToInt

data class TocEntry(
    val link: Link,
    val title: String,
    val depth: Int
)

@OptIn(ExperimentalReadiumApi::class, ExperimentalMaterial3Api::class)
class ReaderActivity : FragmentActivity() {

    companion object {
        private const val NAV_TAG = "epub_navigator"
        private const val AUTO_HIDE_MS = 4000L
    }

    private var publication: Publication? = null
    private var containerId: Int = View.NO_ID
    private var currentBookUriString: String? = null
    private var lastPreferenceChangeAt: Long = 0L

    private var bookTitle by mutableStateOf("Opening book...")
    private var isLoading by mutableStateOf(true)
    private var errorMessage by mutableStateOf<String?>(null)
    private var showControls by mutableStateOf(true)
    private var showToc by mutableStateOf(false)
    private var tocEntries by mutableStateOf<List<TocEntry>>(emptyList())
    private var progressPercent by mutableIntStateOf(0)
    private var interactionTick by mutableIntStateOf(0)
    private var currentTheme by mutableStateOf(Theme.LIGHT)
    private var fontSize by mutableStateOf(1.0)
    private var appThemeMode by mutableStateOf(AppThemeMode.AUTO)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(null)

        val bookUri = intent?.data
        if (bookUri == null) {
            finish()
            return
        }
        currentBookUriString = bookUri.toString()

        appThemeMode = ThemePrefs.getMode(this)
        currentTheme = when (BookHistoryStore.getReaderTheme(this)) {
            "SEPIA" -> Theme.SEPIA
            "DARK" -> Theme.DARK
            else -> Theme.LIGHT
        }
        fontSize = BookHistoryStore.getReaderFontSize(this)

        containerId = View.generateViewId()

        val rootLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
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

        val topBar = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP
            )
            setContent {
                MyEbookTheme(
                    mode = appThemeMode,
                    forceDark = (currentTheme == Theme.DARK)
                ) {
                    LaunchedEffect(showControls, showToc, isLoading, errorMessage, interactionTick) {
                        if (showControls && !showToc && !isLoading && errorMessage == null) {
                            delay(AUTO_HIDE_MS)
                            showControls = false
                        }
                    }

                    AnimatedVisibility(
                        visible = showControls || isLoading || errorMessage != null,
                        enter = slideInVertically { -it } + fadeIn(),
                        exit = slideOutVertically { -it } + fadeOut()
                    ) {
                        ReaderTopBar(
                            title = bookTitle,
                            progressPercent = progressPercent,
                            theme = currentTheme,
                            isLoading = isLoading,
                            errorMessage = errorMessage,
                            onBack = { finish() },
                            onOpenToc = {
                                interactionTick++
                                showToc = true
                            },
                            onCycleTheme = {
                                interactionTick++
                                currentTheme = when (currentTheme) {
                                    Theme.LIGHT -> Theme.SEPIA
                                    Theme.SEPIA -> Theme.DARK
                                    Theme.DARK -> Theme.LIGHT
                                }
                                applyPreferences()
                            },
                            onDecreaseFont = {
                                interactionTick++
                                fontSize = (fontSize - 0.15).coerceAtLeast(0.7)
                                applyPreferences()
                            },
                            onIncreaseFont = {
                                interactionTick++
                                fontSize = (fontSize + 0.15).coerceAtMost(2.2)
                                applyPreferences()
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
                                val navigator = supportFragmentManager
                                    .findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment
                                navigator?.go(entry.link, animated = true)
                            }
                        )
                    }
                }
            }
        }

        rootLayout.addView(fragmentContainer)
        rootLayout.addView(topBar)
        setContentView(rootLayout)

        openBook(bookUri)
    }

    private fun openBook(bookUri: Uri) {
        lifecycleScope.launch {
            val openResult = withContext(Dispatchers.IO) {
                runCatching {
                    val tempFile = File(cacheDir, "current_book.epub")
                    contentResolver.openInputStream(bookUri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    } ?: error("Permission expired or file moved — please pick this book again.")

                    val dao = AppDatabase.getInstance(this@ReaderActivity).bookDao()
                    val dbBook = dao.getByUri(bookUri.toString())
                    val savedLocatorJson = dbBook?.lastLocatorJson
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

                    pub to savedLocator
                }
            }

            openResult.onSuccess { (pub, savedLocator) ->
                if (isFinishing || isDestroyed) {
                    pub.close()
                    return@onSuccess
                }
                publication = pub
                bookTitle = pub.metadata.title ?: "Reading"
                progressPercent = ((savedLocator?.locations?.totalProgression ?: 0.0) * 100).roundToInt()

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

                val navigatorFactory = EpubNavigatorFactory(pub)
                supportFragmentManager.fragmentFactory = navigatorFactory.createFragmentFactory(
                    initialLocator = savedLocator,
                    initialPreferences = currentPreferences()
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
                            return true
                        }
                    })

                    lifecycleScope.launch {
                        currentLocator.drop(1).collect { locator ->
                            val recentlyChangedPrefs =
                                System.currentTimeMillis() - lastPreferenceChangeAt < 1000L
                            if (showControls && !recentlyChangedPrefs) {
                                showControls = false
                            }
                            progressPercent = ((locator.locations.totalProgression ?: 0.0) * 100).roundToInt()
                            saveLocatorNow(locator)
                        }
                    }
                }

                isLoading = false
                showControls = false
            }.onFailure { err ->
                isLoading = false
                showControls = true
                errorMessage = err.localizedMessage ?: "Failed to open EPUB."
            }
        }
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

    private fun saveLocatorNow(locator: Locator) {
        val uriStr = currentBookUriString ?: return
        val json = locator.toJSON().toString()
        val progress = (locator.locations.totalProgression ?: 0.0).coerceIn(0.0, 1.0)
        val now = System.currentTimeMillis()
        val appCtx = applicationContext

        CoroutineScope(Dispatchers.IO).launch {
            AppDatabase.getInstance(appCtx).bookDao()
                .updateReadingPosition(uriStr, json, progress, now)
            BookHistoryStore.saveLocator(appCtx, uriStr, json, progress)
        }
    }

    private fun currentPreferences(): EpubPreferences {
        return EpubPreferences(
            theme = currentTheme,
            fontSize = fontSize
        )
    }

    private fun applyPreferences() {
        lastPreferenceChangeAt = System.currentTimeMillis()
        BookHistoryStore.saveReaderPrefs(this, currentTheme.name, fontSize)
        val navigator = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment
        navigator?.submitPreferences(currentPreferences())
    }

    override fun onPause() {
        super.onPause()
        val navigator = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment
        navigator?.currentLocator?.value?.let { saveLocatorNow(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        publication?.close()
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    progressPercent: Int,
    theme: Theme,
    isLoading: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onCycleTheme: () -> Unit,
    onDecreaseFont: () -> Unit,
    onIncreaseFont: () -> Unit
) {
    val themeLabel = when (theme) {
        Theme.LIGHT -> "Light"
        Theme.SEPIA -> "Sepia"
        Theme.DARK -> "Dark"
    }

    Surface(tonalElevation = 4.dp, shadowElevation = 4.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("Back")
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                )
                Text(
                    text = "$progressPercent%",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                TextButton(onClick = onOpenToc) {
                    Text("Chapters")
                }
                Row {
                    TextButton(onClick = onDecreaseFont) {
                        Text("A-")
                    }
                    TextButton(onClick = onIncreaseFont) {
                        Text("A+")
                    }
                    TextButton(onClick = onCycleTheme) {
                        Text("Theme: $themeLabel")
                    }
                }
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            errorMessage?.let { msg ->
                Text(
                    text = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TocBottomSheet(
    entries: List<TocEntry>,
    onDismiss: () -> Unit,
    onSelect: (TocEntry) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Table of Contents",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onDismiss) {
                    Text("Close")
                }
            }

            HorizontalDivider()

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No chapters found in this book.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(entries) { entry ->
                        val startPadding = (16 + (entry.depth.coerceAtMost(4) * 16)).dp
                        Text(
                            text = entry.title,
                            style = if (entry.depth == 0) {
                                MaterialTheme.typography.bodyLarge
                            } else {
                                MaterialTheme.typography.bodyMedium
                            },
                            fontWeight = if (entry.depth == 0) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(entry) }
                                .padding(
                                    start = startPadding,
                                    end = 16.dp,
                                    top = 12.dp,
                                    bottom = 12.dp
                                )
                        )
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}
