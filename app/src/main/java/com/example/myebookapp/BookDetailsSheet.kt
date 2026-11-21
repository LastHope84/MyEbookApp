package com.example.myebookapp
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailsBottomSheet(b: BookEntity, hasK: Boolean, onD: ()->Unit, onR: ()->Unit, onE: ()->Unit, onS: ()->Unit, onK: ()->Unit, onC: ()->Unit, onDel: ()->Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val sheetS = rememberModalBottomSheetState(true)
    val pct = (b.progressPercent * 100).roundToInt()
    var pathMsg by remember(b.id) { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onD, sheetState = sheetS) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!b.coverPath.isNullOrBlank()) {
                AsyncImage(ImageRequest.Builder(ctx).data(File(b.coverPath)).crossfade(true).build(), null, Modifier.height(200.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Fit)
            } else {
                Box(Modifier.size(120.dp, 180.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)), Alignment.Center) { Text(b.format) }
            }
            Spacer(Modifier.height(16.dp))
            if (b.format == "EPUB") {
                Button(onClick = onR, Modifier.fillMaxWidth()) { Text(if (pct > 0) "Continue ($pct%)" else "Read Book") }
            } else {
                Button(onClick = onC, Modifier.fillMaxWidth()) { Text("Convert ${b.format} to EPUB") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onE, Modifier.weight(1f)) { Text("Edit") }
                OutlinedButton(onClick = onS, Modifier.weight(1f)) { Text("Share") }
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
