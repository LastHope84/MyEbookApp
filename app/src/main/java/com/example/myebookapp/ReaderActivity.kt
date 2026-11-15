package com.example.myebookapp

import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser
import java.io.File

@OptIn(ExperimentalReadiumApi::class)
class ReaderActivity : FragmentActivity() {

    companion object {
        private const val NAV_TAG = "epub_navigator"
    }

    private var publication: Publication? = null
    private var containerId: Int = View.NO_ID

    private var bookTitle by mutableStateOf("Opening book...")
    private var isLoading by mutableStateOf(true)
    private var errorMessage by mutableStateOf<String?>(null)
    private var currentTheme by mutableStateOf(Theme.LIGHT)
    private var fontSize by mutableStateOf(1.0)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Pass null so Android doesn't try restoring the Readium fragment before the book re-opens
        super.onCreate(null)

        val bookUri = intent?.data
        if (bookUri == null) {
            finish()
            return
        }

        containerId = View.generateViewId()

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val topBar = ComposeView(this).apply {
            setContent {
                MaterialTheme {
                    ReaderTopBar(
                        title = bookTitle,
                        theme = currentTheme,
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onBack = { finish() },
                        onCycleTheme = {
                            currentTheme = when (currentTheme) {
                                Theme.LIGHT -> Theme.SEPIA
                                Theme.SEPIA -> Theme.DARK
                                Theme.DARK -> Theme.LIGHT
                            }
                            applyPreferences()
                        },
                        onDecreaseFont = {
                            fontSize = (fontSize - 0.15).coerceAtLeast(0.7)
                            applyPreferences()
                        },
                        onIncreaseFont = {
                            fontSize = (fontSize + 0.15).coerceAtMost(2.2)
                            applyPreferences()
                        }
                    )
                }
            }
        }

        val fragmentContainer = FragmentContainerView(this).apply {
            id = containerId
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        rootLayout.addView(topBar)
        rootLayout.addView(fragmentContainer)
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
                    } ?: error("Could not read the selected file.")

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
                    opener.open(asset, allowUserInteraction = false).getOrElse { err ->
                        error("Could not open EPUB: ${err.message}")
                    }
                }
            }

            openResult.onSuccess { pub ->
                if (isFinishing || isDestroyed) {
                    pub.close()
                    return@onSuccess
                }
                publication = pub
                bookTitle = pub.metadata.title ?: "Reading"

                val navigatorFactory = EpubNavigatorFactory(pub)
                supportFragmentManager.fragmentFactory = navigatorFactory.createFragmentFactory(
                    initialLocator = null,
                    initialPreferences = currentPreferences()
                )
                supportFragmentManager.beginTransaction()
                    .replace(containerId, EpubNavigatorFragment::class.java, Bundle(), NAV_TAG)
                    .commitAllowingStateLoss()

                isLoading = false
            }.onFailure { err ->
                isLoading = false
                errorMessage = err.localizedMessage ?: "Failed to open EPUB."
            }
        }
    }

    private fun currentPreferences(): EpubPreferences {
        return EpubPreferences(
            theme = currentTheme,
            fontSize = fontSize
        )
    }

    private fun applyPreferences() {
        val navigator = supportFragmentManager.findFragmentByTag(NAV_TAG) as? EpubNavigatorFragment
        navigator?.submitPreferences(currentPreferences())
    }

    override fun onDestroy() {
        super.onDestroy()
        publication?.close()
    }
}

@Composable
private fun ReaderTopBar(
    title: String,
    theme: Theme,
    isLoading: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onCycleTheme: () -> Unit,
    onDecreaseFont: () -> Unit,
    onIncreaseFont: () -> Unit
) {
    val themeLabel = when (theme) {
        Theme.LIGHT -> "Light"
        Theme.SEPIA -> "Sepia"
        Theme.DARK -> "Dark"
    }

    Surface(tonalElevation = 2.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
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
                TextButton(onClick = onDecreaseFont) {
                    Text("A-")
                }
                TextButton(onClick = onIncreaseFont) {
                    Text("A+")
                }
                TextButton(onClick = onCycleTheme) {
                    Text(themeLabel)
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
