package com.example.myebookapp
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailsBottomSheet(
    b: BookEntity, hasK: Boolean, onD: ()->Unit, onR: ()->Unit, onE: ()->Unit,
    onS: ()->Unit, onK: ()->Unit, onC: ()->Unit, onDel: ()->Unit,
    onFavoriteChange: (Boolean) -> Unit, onStatusChange: (String) -> Unit, onCustomShelves: () -> Unit,
    statusChangeEnabled: Boolean, translationEnabled: Boolean = false,
    hasCustomShelves: Boolean = true,
    onTranslate: () -> Unit = {},
    canDeleteFile: Boolean = false,
    onDeleteFile: () -> Unit = {},
    quoteCount: Int = 0,
    onQuotes: () -> Unit = {}
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sheetS = rememberModalBottomSheetState(true)
    val pct = displayedProgressPercent(b)
    var pathMsg by remember(b.id) { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onD, sheetState = sheetS) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(contentAlignment = Alignment.Center) {
                if (!b.coverPath.isNullOrBlank()) {
                    AsyncImage(ImageRequest.Builder(ctx).data(File(b.coverPath)).crossfade(true).build(), null, Modifier.height(200.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
                } else {
                    Box(Modifier.size(120.dp, 180.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), Alignment.Center) { Text(b.format) }
                }
                FavoriteHeart(
                    isFavorite = b.isFavorite,
                    enabled = statusChangeEnabled,
                    onToggle = { onFavoriteChange(!b.isFavorite) },
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            val isPaged = b.format == "CBZ" || b.format == "CBR" || b.format == "PDF"
            val convertible = b.format == "MOBI" || b.format == "AZW3"
            if (b.format == "EPUB" || isPaged) {
                Button(onClick = onR, Modifier.fillMaxWidth()) {
                    Text(
                        when {
                            pct > 0 -> "Continue ($pct%)"
                            b.format == "CBZ" || b.format == "CBR" -> "Read Comic"
                            b.format == "PDF" -> "Read PDF"
                            else -> "Read Book"
                        }
                    )
                }
            } else if (convertible) {
                Button(onClick = onC, Modifier.fillMaxWidth()) { Text("Convert ${b.format} to EPUB") }
            }
            if (b.readingStatus != "FINISHED") {
                OutlinedButton(
                    onClick = { onStatusChange("FINISHED") },
                    enabled = statusChangeEnabled,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                ) { Text("Mark as read") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), Arrangement.spacedBy(8.dp)) {
                if (!isPaged) OutlinedButton(onClick = onE, Modifier.weight(1f)) { Text("Edit") }
                OutlinedButton(onClick = onS, Modifier.weight(1f)) { Text("Share") }
            }
            if (b.format == "EPUB" || b.format == "PDF") {
                OutlinedButton(onClick = onQuotes, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text(if (quoteCount > 0) "Quotes ($quoteCount)…" else "Quotes…")
                }
            }
            if (b.format == "EPUB") {
                if (translationEnabled) OutlinedButton(onClick = onTranslate, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Translate EPUB offline…") }
                OutlinedButton(onClick = onK, enabled = hasK, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Send to Kindle")
                }
                if (!hasK) {
                    Text("Add your Kindle email in Settings first.", style = MaterialTheme.typography.labelSmall)
                }
            } else if (convertible) {
                Text("Convert to EPUB before sending to Kindle.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
            if (hasCustomShelves) {
                OutlinedButton(onClick = onCustomShelves, modifier = Modifier.fillMaxWidth()) { Text("Custom shelves…") }
            }
            Text("Reading shelf", modifier = Modifier.fillMaxWidth().padding(top = 8.dp), style = MaterialTheme.typography.labelLarge)
            Text("Choose a shelf if you like. Your reading progress stays unchanged.", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall)
            listOf(
                listOf("NEW" to "Unclassified", "TBR" to "To Read"),
                listOf("READING" to "Reading", "FINISHED" to "Finished")
            ).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (value, label) ->
                        FilterChip(
                            selected = b.readingStatus == value,
                            onClick = { onStatusChange(value) },
                            enabled = statusChangeEnabled,
                            label = { Text(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(12.dp))
            MetadataRow("Title", b.title)
            MetadataRow("Author", b.author)
            if (!b.series.isNullOrBlank()) {
                val idx = b.seriesIndex?.let { if(it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "?"
                MetadataRow("Series", "${b.series} [#$idx]")
            }
            if (!b.tags.isNullOrBlank()) MetadataRow("Tags", b.tags.replace(";", " • "))
            MetadataRow("Date", b.publishingDate)
            MetadataRow("Publisher", b.publisher)
            MetadataRow("Lang", b.language)
            
            Column(Modifier.fillMaxWidth().clickable { pathMsg = FileOpener.openContainingFolder(ctx, b.uriString) }.padding(vertical = 4.dp)) {
                Text("Path (tap to open)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(FileOpener.formatReadablePath(b.uriString), style = MaterialTheme.typography.bodyMedium, textDecoration = TextDecoration.Underline, maxLines = 2, overflow = TextOverflow.Ellipsis)
                pathMsg?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            }
            Spacer(Modifier.height(12.dp))
            if (canDeleteFile) {
                TextButton(onClick = onDeleteFile) {
                    Text("Delete file from device", color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "Deletes the app's copy of this file. Your original download or source file is not touched.",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
            }
            TextButton(onClick = onDel) { Text("Remove from Library", color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
fun MetadataRow(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun FavoriteHeart(
    isFavorite: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(
        targetValue = if (isFavorite) 1f else 0.85f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "favorite-heart"
    )
    IconButton(
        onClick = onToggle,
        enabled = enabled,
        modifier = modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), CircleShape)
    ) {
        Icon(
            imageVector = if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
            contentDescription = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
            tint = if (isFavorite) Color(0xFFE11D48) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp).scale(scale)
        )
    }
}
