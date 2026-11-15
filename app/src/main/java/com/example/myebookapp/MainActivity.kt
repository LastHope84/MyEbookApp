package com.example.myebookapp

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                BookInspectorScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookInspectorScreen() {
    val context = LocalContext.current
    var isLoading by remember { mutableStateOf(false) }
    var book by remember { mutableStateOf<EpubMetadata?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var converterPromptFile by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult

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
                        EpubParser.parseEpub(context, uri, fileName)
                    }
                    ContextCompat.getMainExecutor(context).execute {
                        isLoading = false
                        result.onSuccess { book = it }
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
            TopAppBar(title = { Text("My Ebook Inspector") })
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

            book?.let { meta ->
                BookDetailsCard(meta)
            } ?: run {
                if (!isLoading && errorMessage == null) {
                    Text(
                        text = "Pick any EPUB on your phone to inspect its cover and metadata!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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

@Composable
fun BookDetailsCard(meta: EpubMetadata) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (meta.coverBitmap != null) {
                Image(
                    bitmap = meta.coverBitmap.asImageBitmap(),
                    contentDescription = "Book Cover",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .height(240.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(width = 160.dp, height = 220.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No Cover")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            MetadataRow("Title", meta.title)
            MetadataRow("Author", meta.author)
            MetadataRow("Publishing Date", meta.publishingDate)
            MetadataRow("Publisher", meta.publisher)
            MetadataRow("Language", meta.language)
            MetadataRow("File", meta.fileName)
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
