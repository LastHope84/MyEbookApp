package com.example.myebookapp

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.preferences.Color as ReadiumColor
import org.readium.r2.navigator.preferences.FontFamily
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import kotlin.math.abs
import kotlin.math.roundToInt

enum class ReaderPageTheme(
    val label: String,
    val previewBg: Color,
    val previewFg: Color,
    val windowBgInt: Int,
    val isDarkChrome: Boolean
) {
    LIGHT("Light", Color(0xFFFFFFFF), Color(0xFF1A1A1A), 0xFFFFFFFF.toInt(), false),
    SEPIA("Sepia", Color(0xFFF5ECD7), Color(0xFF4A3B28), 0xFFF5ECD7.toInt(), false),
    DARK("Dark", Color(0xFF1A1A1E), Color(0xFFE6E6E6), 0xFF1A1A1E.toInt(), true),
    AMOLED("AMOLED", Color(0xFF000000), Color(0xFFDCDCDC), 0xFF000000.toInt(), true),
    EINK("E-Ink", Color(0xFFFFFFFF), Color(0xFF000000), 0xFFFFFFFF.toInt(), false)
}

@OptIn(ExperimentalReadiumApi::class)
enum class ReaderFontOption(val label: String, val family: FontFamily?) {
    DEFAULT("Default", null),
    SERIF("Serif", FontFamily.SERIF),
    SANS("Sans", FontFamily.SANS_SERIF),
    OPEN_DYSLEXIC("Dyslexic", FontFamily.OPEN_DYSLEXIC),
    ACCESSIBLE_DFA("Accessible", FontFamily.ACCESSIBLE_DFA),
    DUOSPACE("Duospace", FontFamily.IA_WRITER_DUOSPACE),
    MONO("Mono", FontFamily.MONOSPACE),
    CURSIVE("Cursive", FontFamily.CURSIVE)
}

enum class ReaderOrientationMode(val label: String, val androidMode: Int) {
    AUTO("Auto", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED),
    PORTRAIT("Portrait", ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
    LANDSCAPE("Landscape", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
}

@OptIn(ExperimentalReadiumApi::class)
data class ReaderSettings(
    val pageTheme: ReaderPageTheme = ReaderPageTheme.LIGHT,
    val font: ReaderFontOption = ReaderFontOption.DEFAULT,
    val fontSize: Double = 1.0,
    val lineHeight: Double = 1.4,
    val pageMargins: Double = 1.0,
    val scrollMode: Boolean = false,
    val showMiniFooter: Boolean = true,
    val brightness: Float = -1f,
    val warmTint: Float = 0f,
    val volumeKeysTurnPages: Boolean = true,
    val orientation: ReaderOrientationMode = ReaderOrientationMode.AUTO,
    val immersiveMode: Boolean = false,
    val keepScreenAwake: Boolean = true
) {
    fun toEpubPreferences(): EpubPreferences {
        val customTypography = font != ReaderFontOption.DEFAULT || abs(lineHeight - 1.4) > 0.05
        val rTheme = when (pageTheme) {
            ReaderPageTheme.LIGHT, ReaderPageTheme.EINK -> Theme.LIGHT
            ReaderPageTheme.SEPIA -> Theme.SEPIA
            ReaderPageTheme.DARK, ReaderPageTheme.AMOLED -> Theme.DARK
        }
        val customBg = when (pageTheme) {
            ReaderPageTheme.AMOLED -> ReadiumColor(0xFF000000.toInt())
            ReaderPageTheme.EINK -> ReadiumColor(0xFFFFFFFF.toInt())
            else -> null
        }
        val customText = when (pageTheme) {
            ReaderPageTheme.AMOLED -> ReadiumColor(0xFFDCDCDC.toInt())
            ReaderPageTheme.EINK -> ReadiumColor(0xFF000000.toInt())
            else -> null
        }
        return EpubPreferences(
            theme = rTheme,
            backgroundColor = customBg,
            textColor = customText,
            fontFamily = font.family,
            fontSize = fontSize,
            lineHeight = if (customTypography) lineHeight else null,
            pageMargins = pageMargins,
            scroll = scrollMode,
            publisherStyles = if (customTypography) false else null
        )
    }
}

object ReaderSettingsStore {
    private const val PREFS = "my_ebook_reader_prefs_v2"

    fun load(context: Context): ReaderSettings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val themeName = p.getString("pageTheme", BookHistoryStore.getReaderTheme(context)) ?: "LIGHT"
        val pageTheme = runCatching { ReaderPageTheme.valueOf(themeName) }.getOrDefault(ReaderPageTheme.LIGHT)
        val fontName = p.getString("font", ReaderFontOption.DEFAULT.name) ?: ReaderFontOption.DEFAULT.name
        val font = runCatching { ReaderFontOption.valueOf(fontName) }.getOrDefault(ReaderFontOption.DEFAULT)
        val oriName = p.getString("orientation", ReaderOrientationMode.AUTO.name) ?: ReaderOrientationMode.AUTO.name
        val orientation = runCatching { ReaderOrientationMode.valueOf(oriName) }.getOrDefault(ReaderOrientationMode.AUTO)

        return ReaderSettings(
            pageTheme = pageTheme,
            font = font,
            fontSize = p.getFloat("fontSize", BookHistoryStore.getReaderFontSize(context).toFloat()).toDouble(),
            lineHeight = p.getFloat("lineHeight", 1.4f).toDouble(),
            pageMargins = p.getFloat("pageMargins", 1.0f).toDouble(),
            scrollMode = p.getBoolean("scrollMode", false),
            showMiniFooter = p.getBoolean("showMiniFooter", true),
            brightness = p.getFloat("brightness", -1f),
            warmTint = p.getFloat("warmTint", 0f),
            volumeKeysTurnPages = p.getBoolean("volumeKeysTurnPages", true),
            orientation = orientation,
            immersiveMode = p.getBoolean("immersiveMode", false),
            keepScreenAwake = p.getBoolean("keepScreenAwake", true)
        )
    }

    fun save(context: Context, s: ReaderSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("pageTheme", s.pageTheme.name)
            .putString("font", s.font.name)
            .putString("orientation", s.orientation.name)
            .putFloat("fontSize", s.fontSize.toFloat())
            .putFloat("lineHeight", s.lineHeight.toFloat())
            .putFloat("pageMargins", s.pageMargins.toFloat())
            .putBoolean("scrollMode", s.scrollMode)
            .putBoolean("showMiniFooter", s.showMiniFooter)
            .putFloat("brightness", s.brightness)
            .putFloat("warmTint", s.warmTint)
            .putBoolean("volumeKeysTurnPages", s.volumeKeysTurnPages)
            .putBoolean("immersiveMode", s.immersiveMode)
            .putBoolean("keepScreenAwake", s.keepScreenAwake)
            .apply()
    }
}

@Composable
fun ReaderMinimalTopBar(
    title: String,
    chapterTitle: String,
    isLoading: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenToc: () -> Unit,
    onToggleOrientation: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenQuotes: (() -> Unit)? = null,
    ttsState: TtsState = TtsState.IDLE,
    onToggleTts: () -> Unit = {}
) {
    Surface(tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text("←", style = MaterialTheme.typography.titleLarge)
                }
                Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (chapterTitle.isNotBlank()) {
                        Text(
                            text = chapterTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onOpenSearch, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Search, contentDescription = "Find", modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onOpenToc, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.List, contentDescription = "Table of contents", modifier = Modifier.size(22.dp))
                }
                IconButton(onClick = onToggleOrientation, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Rotate", modifier = Modifier.size(22.dp))
                }
                if (onOpenQuotes != null) {
                    IconButton(
                        onClick = onOpenQuotes,
                        modifier = Modifier.size(36.dp).semantics { contentDescription = "Quotes" }
                    ) {
                        Text("❝", style = MaterialTheme.typography.titleMedium)
                    }
                }
                IconButton(onClick = onToggleTts, modifier = Modifier.size(36.dp)) {
                    if (ttsState == TtsState.PLAYING) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(4.dp, 16.dp).background(LocalContentColor.current, RoundedCornerShape(1.dp)))
                            Box(Modifier.size(4.dp, 16.dp).background(LocalContentColor.current, RoundedCornerShape(1.dp)))
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Read aloud",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                FilledTonalButton(
                    onClick = onOpenAppearance,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Text("Aa", fontWeight = FontWeight.Bold)
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

@Composable
fun ReaderBottomScrubberBar(
    currentPosition: Int,
    totalPositions: Int,
    progressPercent: Int,
    onSeekFraction: (Float) -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit
) {
    var sliderPos by remember(progressPercent) { mutableFloatStateOf(progressPercent / 100f) }
    var isDragging by remember { mutableStateOf(false) }
    // A tap on the track ends the gesture before the new position is reported, so
    // keep showing the chosen spot until the reader confirms it.
    var pendingSeek by remember { mutableStateOf<Float?>(null) }
    val reported = (progressPercent / 100f).coerceIn(0f, 1f)
    LaunchedEffect(reported) { pendingSeek = null }
    LaunchedEffect(pendingSeek) {
        if (pendingSeek != null) {
            delay(1500)
            pendingSeek = null
        }
    }
    val shown = when {
        isDragging -> sliderPos
        pendingSeek != null -> pendingSeek ?: reported
        else -> reported
    }

    Surface(tonalElevation = 6.dp, shadowElevation = 6.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onPrevPage) { Text("‹", style = MaterialTheme.typography.titleLarge) }
                Slider(
                    value = shown,
                    onValueChange = { isDragging = true; sliderPos = it },
                    onValueChangeFinished = {
                        isDragging = false
                        pendingSeek = sliderPos
                        onSeekFraction(sliderPos)
                    },
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onNextPage) { Text("›", style = MaterialTheme.typography.titleLarge) }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val pageLabel = if (totalPositions > 0 && currentPosition > 0) {
                    "Page $currentPosition / $totalPositions"
                } else "Reading"
                Text(pageLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val displayPct = (shown * 100).roundToInt()
                Text("$displayPct%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun ReaderQuietFooter(
    chapterTitle: String,
    currentPosition: Int,
    totalPositions: Int,
    progressPercent: Int,
    pageTheme: ReaderPageTheme
) {
    val textColor = when (pageTheme) {
        ReaderPageTheme.AMOLED, ReaderPageTheme.DARK -> Color(0xFF9E9E9E)
        ReaderPageTheme.EINK -> Color(0xFF000000)
        else -> Color(0xFF666666)
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = chapterTitle,
            style = MaterialTheme.typography.labelSmall,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 12.dp)
        )
        val right = if (totalPositions > 0 && currentPosition > 0) {
            "$currentPosition/$totalPositions • $progressPercent%"
        } else "$progressPercent%"
        Text(text = right, style = MaterialTheme.typography.labelSmall, color = textColor)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceBottomSheet(
    settings: ReaderSettings,
    onUpdate: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Text("Reading Appearance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(12.dp))
            AppearancePreview(settings)
            Spacer(modifier = Modifier.height(16.dp))

            Text("Page Theme", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReaderPageTheme.entries.forEach { t ->
                    Box(
                        modifier = Modifier
                            .width(74.dp)
                            .height(44.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(t.previewBg)
                            .border(
                                width = if (settings.pageTheme == t) 3.dp else 1.dp,
                                color = if (settings.pageTheme == t) MaterialTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.45f),
                                shape = RoundedCornerShape(10.dp)
                            )
                            .clickable { onUpdate(settings.copy(pageTheme = t)) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(t.label, color = t.previewFg, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text("Typeface", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ReaderFontOption.entries.forEach { option ->
                    FilterChip(
                        selected = settings.font == option,
                        onClick = { onUpdate(settings.copy(font = option)) },
                        label = { Text(option.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text("Screen Orientation", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReaderOrientationMode.entries.forEach { ori ->
                    FilterChip(
                        selected = settings.orientation == ori,
                        onClick = { onUpdate(settings.copy(orientation = ori)) },
                        label = { Text(ori.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            SliderHeader("Font Size", "${(settings.fontSize * 100).roundToInt()}%")
            Slider(
                value = settings.fontSize.toFloat(),
                onValueChange = { onUpdate(settings.copy(fontSize = it.toDouble())) },
                valueRange = 0.7f..2.2f,
                steps = 14
            )

            SliderHeader("Line Spacing", "${String.format("%.1f", settings.lineHeight)}x")
            Slider(
                value = settings.lineHeight.toFloat(),
                onValueChange = { onUpdate(settings.copy(lineHeight = it.toDouble())) },
                valueRange = 1.0f..2.2f,
                steps = 11
            )

            SliderHeader("Page Margins", "${String.format("%.1f", settings.pageMargins)}x")
            Slider(
                value = settings.pageMargins.toFloat(),
                onValueChange = { onUpdate(settings.copy(pageMargins = it.toDouble())) },
                valueRange = 0.5f..2.0f,
                steps = 5
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Brightness", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                if (settings.brightness >= 0f) {
                    TextButton(onClick = { onUpdate(settings.copy(brightness = -1f)) }) {
                        Text("Reset to Auto (${(settings.brightness * 100).roundToInt()}%)")
                    }
                } else {
                    Text("System Auto", style = MaterialTheme.typography.labelSmall)
                }
            }
            Slider(
                value = if (settings.brightness >= 0f) settings.brightness else 0.5f,
                onValueChange = { onUpdate(settings.copy(brightness = it.coerceIn(0.05f, 1.0f))) },
                valueRange = 0.05f..1.0f
            )

            SliderHeader("Warm Blue-Light Filter", "${(settings.warmTint * 100).roundToInt()}%")
            Slider(
                value = settings.warmTint,
                onValueChange = { onUpdate(settings.copy(warmTint = it.coerceIn(0f, 0.5f))) },
                valueRange = 0f..0.5f
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            ToggleRow("Volume Keys Turn Pages", settings.volumeKeysTurnPages) {
                onUpdate(settings.copy(volumeKeysTurnPages = it))
            }
            ToggleRow("Vertical Scroll Mode", settings.scrollMode) {
                onUpdate(settings.copy(scrollMode = it))
            }
            ToggleRow("Fullscreen (Hide Status Bar)", settings.immersiveMode) {
                onUpdate(settings.copy(immersiveMode = it))
            }
            ToggleRow("Keep Screen Awake", settings.keepScreenAwake) {
                onUpdate(settings.copy(keepScreenAwake = it))
            }
            ToggleRow("Show Mini Footer While Reading", settings.showMiniFooter) {
                onUpdate(settings.copy(showMiniFooter = it))
            }
        }
    }
}

@Composable
private fun SliderHeader(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InBookSearchSheet(
    query: String,
    isSearching: Boolean,
    results: List<Locator>,
    onQueryChange: (String) -> Unit,
    onRunSearch: () -> Unit,
    onSelectResult: (Locator) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(horizontal = 16.dp)) {
            Text("Search Inside Book", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Word or character name...") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onRunSearch() }),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onRunSearch, enabled = query.isNotBlank() && !isSearching) {
                    Text("Find")
                }
            }
            if (isSearching) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Spacer(modifier = Modifier.height(10.dp))
            if (!isSearching && results.isEmpty() && query.isNotBlank()) {
                Text("Tap Find or try a different word.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp)) {
                items(results) { loc ->
                    val snippet = buildString {
                        loc.text.before?.let { append(it) }
                        loc.text.highlight?.let { append("【$it】") }
                        loc.text.after?.let { append(it) }
                    }.trim().ifBlank { loc.title ?: "Match" }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectResult(loc) }
                            .padding(vertical = 10.dp)
                    ) {
                        if (!loc.title.isNullOrBlank()) {
                            Text(loc.title!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider(thickness = 0.5.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryBottomSheet(
    word: String,
    isLoading: Boolean,
    entry: DictionaryEntry?,
    onOpenInBrowser: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(word, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            if (isLoading) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                Text("Looking up definition...", style = MaterialTheme.typography.bodySmall)
            } else if (entry != null) {
                Text(entry.source, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(10.dp))
                entry.definitions.forEach { def ->
                    val isHeader = def.startsWith("[") && def.endsWith("]")
                    Text(
                        text = if (isHeader) def.removeSurrounding("[", "]") else "• $def",
                        style = if (isHeader) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isHeader) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isHeader) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text("No definition found in Wikizionario / Dictionary for \"$word\".", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(onClick = onOpenInBrowser, modifier = Modifier.fillMaxWidth()) {
                Text("Search / Translate in Browser")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocBottomSheet(
    entries: List<TocEntry>,
    onDismiss: () -> Unit,
    onSelect: (TocEntry) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Table of Contents", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            HorizontalDivider()
            if (entries.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text("No chapters found in this book.")
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(entries) { entry ->
                        val startPadding = (16 + (entry.depth.coerceAtMost(4) * 16)).dp
                        Text(
                            text = entry.title,
                            style = if (entry.depth == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                            fontWeight = if (entry.depth == 0) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(entry) }
                                .padding(start = startPadding, end = 16.dp, top = 12.dp, bottom = 12.dp)
                        )
                        HorizontalDivider(thickness = 0.5.dp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTextApi::class)
@Composable
private fun AppearancePreview(settings: ReaderSettings) {
    val context = LocalContext.current
    val background = when (settings.pageTheme) {
        ReaderPageTheme.AMOLED -> Color.Black
        ReaderPageTheme.EINK -> Color.White
        else -> settings.pageTheme.previewBg
    }
    val foreground = when (settings.pageTheme) {
        ReaderPageTheme.AMOLED -> Color(0xFFDCDCDC)
        ReaderPageTheme.EINK -> Color.Black
        else -> settings.pageTheme.previewFg
    }
    val family = when (settings.font) {
        ReaderFontOption.SERIF -> ComposeFontFamily.Serif
        ReaderFontOption.SANS -> ComposeFontFamily.SansSerif
        ReaderFontOption.MONO -> ComposeFontFamily.Monospace
        ReaderFontOption.CURSIVE -> ComposeFontFamily.Cursive
        // The reader engine ships these exact fonts as assets.
        ReaderFontOption.OPEN_DYSLEXIC -> ComposeFontFamily(
            Font("readium/fonts/OpenDyslexic-Regular.otf", context.assets)
        )
        ReaderFontOption.ACCESSIBLE_DFA -> ComposeFontFamily(
            Font("readium/readium-css/fonts/AccessibleDfA.otf", context.assets)
        )
        ReaderFontOption.DUOSPACE -> ComposeFontFamily(
            Font("readium/readium-css/fonts/iAWriterDuospace-Regular.ttf", context.assets)
        )
        else -> ComposeFontFamily.Default
    }
    val size = (16f * settings.fontSize.toFloat()).sp
    val line = (16f * settings.fontSize.toFloat() * settings.lineHeight.toFloat()).sp
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(
                horizontal = (14f * settings.pageMargins.toFloat()).dp,
                vertical = 14.dp
            )
    ) {
        Text(
            "The quick brown fox jumps over the lazy dog. Questa è un'anteprima: cambia tema, carattere e spaziatura per vedere subito il risultato.",
            color = foreground,
            fontFamily = family,
            fontSize = size,
            lineHeight = line
        )
    }
}
