package com.example.myebookapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private var resumeTick by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                BookInspectorScreen(resumeTick = resumeTick)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeTick++
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookInspectorScreen(resumeTick: Int) {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(false) }
    var recentBooks by remember { mutableStateOf<List<BookRecord>>(emptyList()) }
    var selectedBook by remember { mutableStateOf<BookRecord?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var converterPromptFile by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(resumeTick) {
        val loaded = BookHistoryStore.getAll(context)
        recentBooks = loaded
        if (selectedBook == null && loaded.isNotEmpty()) {
            selectedBook = loaded.first()
        } else if (selectedBook != null) {
            selectedBook = loaded.firstOrNull { it.uriString == selectedBook?.uriString }
                ?: loaded.firstOrNull()
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val fileName = EpubParser.getFileName(context, uri)
        val ext = fileName.substringAfterLast('.', "").lowercase()

        when (ext) {
            "mobi", "azw3", "azw", "prc" -> {
                converterPromptFile = fileName
            }
            "epub" -> {
                isLoading = true
                errorMessage = null
                thread {
                    val result = runCatching {
                        val meta = EpubParser.parseEpub(context, uri, fileName)
                        BookHistoryStore.upsertFromMetadata(context, uri.toString(), meta)
                    }
                    ContextCompat.getMainExecutor(context).execute {
                        isLoading = false
                        result.onSuccess { record ->
                            selectedBook = record
                            recentBooks = BookHistoryStore.getAll(context)
                        }
                        result.onFailure {
                            errorMessage = it.localizedMessage ?: "Failed to read EPUB."
                        }
                    }
                }
            }
            else -> {
                errorMessage = "Unsupported file ($fileName). Pick an .epub, .mobi, or .azw3 file."
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("My Ebook Library") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = { filePicker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Pick an Ebook (.epub / .mobi / .azw3)")
            }

            recentBooks.firstOrNull()?.let { lastBook ->
                Spacer(modifier = Modifier.height(8.dp))
                val pct = (lastBook.progressPercent * 100).roundToInt()
                OutlinedButton(
                    onClick = { openInReader(context, lastBook.uriString) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (pct > 0) {
                            "Continue: ${lastBook.title} ($pct%)"
                        } else {
                            "Continue: ${lastBook.title}"
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(8.dp))
                Text("Reading book metadata...")
            }

            errorMessage?.let { msg ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = msg,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(16.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            selectedBook?.let { record ->
                BookDetailsCard(
                    record = record,
                    onReadClick = { openInReader(context, record.uriString) }
                )
            } ?: run {
                if (!isLoading && errorMessage == null) {
                    Text(
                        text = "Pick any EPUB on your phone to inspect or read it!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (recentBooks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    text = "Recently Opened",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                recentBooks.forEach { record ->
                    RecentBookRow(
                        record = record,
                        isSelected = record.uriString == selectedBook?.uriString,
                        onSelect = { selectedBook = record },
                        onRead = { openInReader(context, record.uriString) },
                        onRemove = {
                            BookHistoryStore.remove(context, record.uriString)
                            val updated = BookHistoryStore.getAll(context)
                            recentBooks = updated
                            if (selectedBook?.uriString == record.uriString) {
                                selectedBook = updated.firstOrNull()
                            }
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }

    converterPromptFile?.let { fileName ->
        AlertDialog(
            onDismissRequest = { converterPromptFile = null },
            title = { Text("Convert to EPUB?") },
            text = {
                Text(
                    "\"$fileName\" is a MOBI/AZW3 file. In a later step, we'll plug in the converter here to turn it into an EPUB and import it into your library!"
                )
            },
            confirmButton = {
                TextButton(onClick = { converterPromptFile = null }) {
                    Text("Got it")
                }
            }
        )
    }
}

private fun openInReader(context: Context, uriString: String) {
    val intent = Intent(context, ReaderActivity::class.java).apply {
        data = Uri.parse(uriString)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(intent)
}

@Composable
fun BookDetailsCard(record: BookRecord, onReadClick: () -> Unit) {
    val coverBitmap = remember(record.coverPath) {
        BookHistoryStore.loadCoverBitmap(record.coverPath)
    }
    val pct = (record.progressPercent * 100).roundToInt()

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap.asImageBitmap(),
                    contentDescription = "Book Cover",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(220.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 150.dp, height = 200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No Cover")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onReadClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (pct > 0) "Continue Reading ($pct%)" else "Read This Book")
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            MetadataRow("Title", record.title)
            MetadataRow("Author", record.author)
            MetadataRow("Publishing Date", record.publishingDate)
            MetadataRow("Publisher", record.publisher)
            MetadataRow("Language", record.language)
            MetadataRow("File", record.fileName)
        }
    }
}

@Composable
fun RecentBookRow(
    record: BookRecord,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onRead: () -> Unit,
    onRemove: () -> Unit
) {
    val coverBitmap = remember(record.coverPath) {
        BookHistoryStore.loadCoverBitmap(record.coverPath)
    }
    val pct = (record.progressPercent * 100).roundToInt()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (coverBitmap != null) {
                Image(
                    bitmap = coverBitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 44.dp, height = 64.dp)
                        .clip(RoundedCornerShape(4.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 64.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surface),
                    contentAlignment = Alignment.Center
                ) {
                    Text("EPUB", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = record.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (pct > 0) "Progress: $pct%" else "Not started yet",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            TextButton(onClick = onRead) {
                Text("Read")
            }
            TextButton(onClick = onRemove) {
                Text("✕")
            }
        }
    }
}

@Composable
fun MetadataRow(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}
