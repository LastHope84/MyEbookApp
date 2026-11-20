package com.example.myebookapp

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class BatchMetadataRequest(
    val author: String?,
    val publishingDate: String?,
    val publisher: String?,
    val language: String?,
    val writeToEpubFiles: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MetadataEditorBottomSheet(
    book: BookEntity,
    onDismiss: () -> Unit,
    onSave: (request: EpubEditRequest, writeToFile: Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun editableValue(raw: String): String =
        if (raw.equals("Unknown", ignoreCase = true)) "" else raw

    var title by remember(book.id) { mutableStateOf(editableValue(book.title)) }
    var author by remember(book.id) { mutableStateOf(editableValue(book.author)) }
    var publishingDate by remember(book.id) { mutableStateOf(editableValue(book.publishingDate)) }
    var publisher by remember(book.id) { mutableStateOf(editableValue(book.publisher)) }
    var language by remember(book.id) { mutableStateOf(editableValue(book.language)) }
    var writeToFile by remember(book.id) { mutableStateOf(book.format == "EPUB") }

    var newCoverBitmap by remember(book.id) { mutableStateOf<Bitmap?>(null) }
    var removeCover by remember(book.id) { mutableStateOf(false) }

    var isFetchingOnline by remember(book.id) { mutableStateOf(false) }
    var onlineCandidates by remember(book.id) { mutableStateOf<List<OnlineBookCandidate>?>(null) }
    var fetchNotice by remember(book.id) { mutableStateOf<String?>(null) }

    val hasVisibleCover = !removeCover && (newCoverBitmap != null || !book.coverPath.isNullOrBlank())

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val decoded = EpubMetadataEditor.decodeSampledImageUri(context, uri)
            withContext(Dispatchers.Main) {
                if (decoded != null) {
                    newCoverBitmap = decoded
                    removeCover = false
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Edit Metadata & Cover", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(12.dp))

            when {
                removeCover -> {
                    Box(
                        modifier = Modifier
                            .size(width = 120.dp, height = 165.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Cover\nRemoved",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                newCoverBitmap != null -> {
                    Image(
                        bitmap = newCoverBitmap!!.asImageBitmap(),
                        contentDescription = "New Cover",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .height(170.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
                !book.coverPath.isNullOrBlank() -> {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(File(book.coverPath))
                            .crossfade(true)
                            .build(),
                        contentDescription = "Current Cover",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .height(170.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .size(width = 120.dp, height = 165.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No Cover")
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (hasVisibleCover) "Change Cover" else "Add Cover")
                }
                if (hasVisibleCover) {
                    OutlinedButton(
                        onClick = {
                            newCoverBitmap = null
                            removeCover = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Remove Cover", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            FilledTonalButton(
                enabled = !isFetchingOnline,
                onClick = {
                    isFetchingOnline = true
                    fetchNotice = null
                    scope.launch(Dispatchers.IO) {
                        val found = runCatching {
                            OnlineMetadataFetcher.search(title, author)
                        }.getOrElse { emptyList() }
                        withContext(Dispatchers.Main) {
                            isFetchingOnline = false
                            if (found.isEmpty()) {
                                fetchNotice = "No online matches found — try shortening Title or Author."
                            } else {
                                onlineCandidates = found
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isFetchingOnline) "Searching online..." else "Auto-Fetch Metadata & Cover")
            }

            if (isFetchingOnline) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            fetchNotice?.let { msg ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = author,
                onValueChange = { author = it },
                label = { Text("Author (use ; for multiple)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))

            val hasIsoTimeTail = publishingDate.length >= 10 && publishingDate.contains("T")
            OutlinedTextField(
                value = publishingDate,
                onValueChange = { publishingDate = it },
                label = { Text("Publishing Date (YYYY-MM-DD)") },
                singleLine = true,
                trailingIcon = {
                    if (hasIsoTimeTail) {
                        TextButton(onClick = { publishingDate = publishingDate.take(10) }) {
                            Text("Clean")
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = publisher,
                onValueChange = { publisher = it },
                label = { Text("Publisher") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = language,
                onValueChange = { language = it },
                label = { Text("Language (e.g. it, en, fr)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (book.format == "EPUB") {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Write into .epub file", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Saves changes inside the actual ebook file too",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = writeToFile, onCheckedChange = { writeToFile = it })
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        onSave(
                            EpubEditRequest(
                                title = title.trim().ifBlank { book.fileName.substringBeforeLast('.') },
                                author = author.trim().ifBlank { "Unknown" },
                                publishingDate = publishingDate.trim().ifBlank { "Unknown" },
                                publisher = publisher.trim().ifBlank { "Unknown" },
                                language = language.trim().ifBlank { "Unknown" },
                                newCoverBitmap = if (removeCover) null else newCoverBitmap,
                                removeCover = removeCover
                            ),
                            writeToFile && book.format == "EPUB"
                        )
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }
        }
    }

    onlineCandidates?.let { list ->
        AlertDialog(
            onDismissRequest = { onlineCandidates = null },
            title = { Text("Pick Online Match") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    list.forEach { candidate ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onlineCandidates = null
                                    if (candidate.title.isNotBlank()) title = candidate.title
                                    if (candidate.author.isNotBlank()) author = candidate.author
                                    if (candidate.publishingDate.isNotBlank()) publishingDate = candidate.publishingDate
                                    if (candidate.publisher.isNotBlank()) publisher = candidate.publisher
                                    if (candidate.language.isNotBlank()) language = candidate.language

                                    val coverUrl = candidate.coverUrl
                                    if (!coverUrl.isNullOrBlank()) {
                                        isFetchingOnline = true
                                        fetchNotice = "Downloading cover..."
                                        scope.launch(Dispatchers.IO) {
                                            val bmp = OnlineMetadataFetcher.downloadCoverBitmap(coverUrl)
                                            withContext(Dispatchers.Main) {
                                                isFetchingOnline = false
                                                if (bmp != null) {
                                                    newCoverBitmap = bmp
                                                    removeCover = false
                                                    fetchNotice = "Filled metadata & cover from ${candidate.source}! Tap Save to apply."
                                                } else {
                                                    fetchNotice = "Filled metadata from ${candidate.source} (cover unavailable)."
                                                }
                                            }
                                        }
                                    } else {
                                        fetchNotice = "Filled metadata from ${candidate.source}! Tap Save to apply."
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!candidate.coverUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(context)
                                            .data(candidate.coverUrl)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(width = 46.dp, height = 68.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(width = 46.dp, height = 68.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("?", style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = candidate.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (candidate.author.isNotBlank()) {
                                        Text(
                                            text = candidate.author,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    val extra = listOf(
                                        candidate.publisher,
                                        candidate.publishingDate,
                                        candidate.language
                                    ).filter { it.isNotBlank() }.joinToString(" • ")
                                    if (extra.isNotBlank()) {
                                        Text(
                                            text = extra,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = candidate.source,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { onlineCandidates = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun BatchMetadataDialog(
    selectedCount: Int,
    onDismiss: () -> Unit,
    onApply: (BatchMetadataRequest) -> Unit
) {
    var author by remember { mutableStateOf("") }
    var publishingDate by remember { mutableStateOf("") }
    var publisher by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("") }
    var writeToFiles by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bulk Edit ($selectedCount books)") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "Leave any field blank to keep each book's current value.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = publishingDate,
                    onValueChange = { publishingDate = it },
                    label = { Text("Publishing Date") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = publisher,
                    onValueChange = { publisher = it },
                    label = { Text("Publisher") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = language,
                    onValueChange = { language = it },
                    label = { Text("Language") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Write into .epub files", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = writeToFiles, onCheckedChange = { writeToFiles = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(
                        BatchMetadataRequest(
                            author = author.trim().ifBlank { null },
                            publishingDate = publishingDate.trim().ifBlank { null },
                            publisher = publisher.trim().ifBlank { null },
                            language = language.trim().ifBlank { null },
                            writeToEpubFiles = writeToFiles
                        )
                    )
                }
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
