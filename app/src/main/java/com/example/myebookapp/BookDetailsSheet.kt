package com.example.myebookapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailsBottomSheet(
    book: BookEntity,
    hasKindleEmail: Boolean,
    onDismiss: () -> Unit,
    onRead: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onSendToKindle: () -> Unit,
    onConvertNow: () -> Unit,
    onRemoveFromLibrary: () -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pct = (book.progressPercent * 100).roundToInt()
    var pathNotice by remember(book.id) { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!book.coverPath.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(File(book.coverPath)).crossfade(true).build(),
                    contentDescription = book.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(210.dp).clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 140.dp, height = 190.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) { Text(book.format) }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (book.format == "EPUB") {
                Button(onClick = onRead, modifier = Modifier.fillMaxWidth()) {
                    Text(if (pct > 0) "Continue Reading ($pct%)" else "Read This Book")
                }
            } else {
                Button(onClick = onConvertNow, modifier = Modifier.fillMaxWidth()) {
                    Text("Convert ${book.format} to EPUB")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Edit Metadata") }
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f)) { Text("Share File") }
            }

            if (book.format == "EPUB" && hasKindleEmail) {
                Spacer(modifier = Modifier.height(8.dp))
                FilledTonalButton(onClick = onSendToKindle, modifier = Modifier.fillMaxWidth()) {
                    Text("Send to Kindle")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            MetadataRow("Title", book.title)
            MetadataRow("Author", book.author)
            MetadataRow("Publishing Date", book.publishingDate)
            MetadataRow("Publisher", book.publisher)
            MetadataRow("Language", book.language)
            MetadataRow("Format", book.format)
            MetadataRow("File", book.fileName)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { pathNotice = FileOpener.openContainingFolder(context, book.uriString) }
                    .padding(vertical = 6.dp)
            ) {
                Text("Path (tap to open folder)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(
                    text = FileOpener.formatReadablePath(book.uriString),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline
                )
                pathNotice?.let { notice ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onRemoveFromLibrary) {
                Text("Remove from Library (keeps file on phone)", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun MetadataRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}
