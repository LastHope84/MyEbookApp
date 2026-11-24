package com.example.myebookapp

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.roundToInt

class PageReaderActivity : ComponentActivity() {
    companion object {
        // Saves must finish even when the reader is closing.
        private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private val loading = mutableStateOf(true)
    private val loadError = mutableStateOf<String?>(null)
    private var book: BookEntity? = null
    private var source: PagedImageSource? = null
    private var loader: PageLoader? = null
    private var pageCount = 0
    private var startPage = 0
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
                            initialRtl = ThemePrefs.isComicRtl(this@PageReaderActivity),
                            onRtlChange = { ThemePrefs.saveComicRtl(this@PageReaderActivity, it) },
                            onPageSettled = {
                                currentPage = it
                                savePage(it)
                            },
                            onBack = { finish() }
                        )
                    }
                }
            }
        }
    }

    private fun openDocument(uriString: String) {
        lifecycleScope.launch {
            val result: Result<Triple<BookEntity, PagedImageSource, Int>> = withContext(Dispatchers.IO) {
                try {
                    val stored = dao.getByUri(uriString)
                        ?: throw IllegalStateException("This document is not in the library anymore.")
                    val opened: PagedImageSource = when (stored.format) {
                        "PDF" -> PdfPageSource.open(
                            this@PageReaderActivity, uriString, stored.fileSize, stored.lastModified
                        )
                        else -> ComicPageSource.open(
                            this@PageReaderActivity, uriString, stored.fileSize, stored.lastModified
                        )
                    }
                    val saved = stored.lastLocatorJson?.let {
                        runCatching { JSONObject(it).optInt("page", -1) }.getOrNull()
                    } ?: -1
                    val total = opened.pageCount
                    if (total <= 0) throw IllegalStateException("This document has no readable pages.")
                    val start = when {
                        saved in 0 until total -> saved
                        stored.progressPercent > 0.0 -> ((stored.progressPercent * total).toInt() - 1).coerceIn(0, total - 1)
                        else -> 0
                    }
                    val now = System.currentTimeMillis()
                    dao.touchOpened(uriString, now)
                    dao.markStarted(uriString)
                    Result.success(Triple(stored, opened, start))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
            result.onSuccess { (stored, opened, start) ->
                if (isFinishing || isDestroyed) {
                    opened.close()
                    return@onSuccess
                }
                book = stored
                source = opened
                pageCount = opened.pageCount
                startPage = start
                currentPage = start
                val metrics = resources.displayMetrics
                loader = PageLoader(opened, metrics.widthPixels, metrics.heightPixels)
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

    override fun onPause() {
        savePage(currentPage)
        super.onPause()
    }

    override fun onDestroy() {
        source?.close()
        loader?.clear()
        super.onDestroy()
    }
}

@Composable
private fun PageReaderScreen(
    title: String,
    pageCount: Int,
    startPage: Int,
    loader: PageLoader,
    initialRtl: Boolean,
    onRtlChange: (Boolean) -> Unit,
    onPageSettled: (Int) -> Unit,
    onBack: () -> Unit
) {
    var rtl by remember { mutableStateOf(initialRtl) }
    var chromeVisible by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    val lastIndex = (pageCount - 1).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = startPage.coerceIn(0, lastIndex)) { pageCount }
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val activity = LocalContext.current as? Activity

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
            ReaderTopBar(title, pagerState.currentPage + 1, pageCount, onBack)
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
    }
}

@Composable
private fun ReaderTopBar(title: String, page: Int, total: Int, onBack: () -> Unit) {
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
    val lastIndex = (total - 1).coerceAtLeast(1)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.82f))
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Slider(
            value = if (dragging) sliderValue else page.toFloat(),
            onValueChange = {
                sliderValue = it
                dragging = true
            },
            onValueChangeFinished = {
                dragging = false
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
                "${page + 1} / $total",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

private sealed class PageState {
    object Loading : PageState()
    class Ready(val bitmap: Bitmap) : PageState()
    object Failed : PageState()
}

@Composable
private fun DocumentPage(
    loader: PageLoader,
    index: Int,
    active: Boolean,
    onZoomChanged: (Boolean) -> Unit,
    onTap: (Float) -> Unit
) {
    val state by produceState<PageState>(initialValue = PageState.Loading, index) {
        value = loader.load(index)?.let { PageState.Ready(it) } ?: PageState.Failed
    }
    var scale by remember(index) { mutableFloatStateOf(1f) }
    var offset by remember(index) { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val activeState by rememberUpdatedState(active)
    val onZoomState by rememberUpdatedState(onZoomChanged)
    val onTapState by rememberUpdatedState(onTap)

    fun clampOffset(candidate: Offset, currentScale: Float): Offset {
        if (currentScale <= 1f || size.width == 0) return Offset.Zero
        val maxX = size.width * (currentScale - 1f) / 2f
        val maxY = size.height * (currentScale - 1f) / 2f
        return Offset(candidate.x.coerceIn(-maxX, maxX), candidate.y.coerceIn(-maxY, maxY))
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
        if (activeState) onZoomState(false)
    }

    LaunchedEffect(active) {
        if (!active) reset()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            // One finger belongs to the pager until a pinch starts; after zooming,
            // this detector takes the drags so the page can be panned.
            .pointerInput(index) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var event: PointerEvent
                    do {
                        event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed > 1 || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 4f)
                            val newOffset = clampOffset(offset + event.calculatePan(), newScale)
                            scale = newScale
                            offset = newOffset
                            if (activeState) onZoomState(newScale > 1.01f)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(index) {
                detectTapGestures(
                    onTap = { position ->
                        if (scale <= 1.01f && size.width > 0) onTapState(position.x / size.width)
                    },
                    onDoubleTap = { position ->
                        if (scale > 1.01f) {
                            reset()
                        } else {
                            val target = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            scale = target
                            offset = clampOffset((center - position) * (target - 1f), target)
                            if (activeState) onZoomState(true)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        when (val current = state) {
            is PageState.Ready -> androidx.compose.foundation.Image(
                bitmap = current.bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )
            PageState.Loading -> CircularProgressIndicator(
                color = Color.White.copy(alpha = 0.6f),
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp)
            )
            PageState.Failed -> Text(
                "This page could not be read.",
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
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
