package com.example.myebookapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class FolderEntry(val id: String, val name: String)

private fun folderLabel(uri: Uri): String = runCatching {
    val id = DocumentsContract.getTreeDocumentId(uri)
    val name = id.substringAfterLast(':').substringAfterLast('/').ifBlank { id }
    Uri.decode(name)
}.getOrElse { uri.toString() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderManagementScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var roots by remember { mutableStateOf(LibraryScanner.getSavedFolders(ctx).map(Uri::parse).sortedBy(::folderLabel)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingRemove by remember { mutableStateOf<Uri?>(null) }
    var browsing by remember { mutableStateOf<Uri?>(null) }

    fun refresh() {
        roots = LibraryScanner.getSavedFolders(ctx).map(Uri::parse).sortedBy(::folderLabel)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                LibraryScanner.addFolder(ctx, uri)
                LibraryScanner.scanFolders(ctx, listOf(uri))
                refresh()
                message = "Folder added. Scanning it now."
            } catch (e: Exception) {
                message = "Could not add the folder: ${e.localizedMessage ?: "unknown error"}"
            }
        }
    }

    BackHandler(enabled = browsing == null) { onClose() }

    val currentBrowse = browsing
    if (currentBrowse != null) {
        SubfolderRemovalScreen(currentBrowse, onClose = { browsing = null; refresh() })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage folders") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                "Adding a folder also includes all its subfolders. You can add several folders one after another. Removing a folder removes its library entries, not the ebook files.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { picker.launch(null) }, enabled = !busy) { Text("Add folder") }
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(12.dp))
            if (roots.isEmpty()) {
                Text("No folders added yet.", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(roots, key = { it.toString() }) { root ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(folderLabel(root), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(root.toString(), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(enabled = !busy, onClick = {
                                        LibraryScanner.scanFolders(ctx, listOf(root))
                                        message = "Scanning ${folderLabel(root)}…"
                                    }) { Text("Scan") }
                                    TextButton(enabled = !busy, onClick = { browsing = root }) { Text("Subfolders") }
                                    TextButton(enabled = !busy, onClick = { pendingRemove = root }) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    pendingRemove?.let { root ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove this folder?") },
            text = {
                Text(
                    "This removes the library entries from “${folderLabel(root)}”. The original ebook files stay in the folder. Reading progress, edits, notes and shelf membership for those entries will be lost. A later scan can add them again."
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    pendingRemove = null
                    busy = true
                    scope.launch {
                        try {
                            val removed = LibraryScanner.removeFolder(ctx, root)
                            refresh()
                            message = if (removed == 0) "Folder removed. No library entries were found for it."
                            else "Folder removed. $removed library entr${if (removed == 1) "y" else "ies"} removed; the ebook files are still there."
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = "Could not remove the folder: ${e.localizedMessage ?: "unknown error"}"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubfolderRemovalScreen(rootUri: Uri, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootId = remember(rootUri) { DocumentsContract.getTreeDocumentId(rootUri) }
    val rootName = remember(rootUri) { folderLabel(rootUri) }
    var path by remember { mutableStateOf(listOf(FolderEntry(rootId, rootName))) }
    var children by remember { mutableStateOf<List<FolderEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val current = path.last()

    fun load(id: String) {
        loading = true
        error = null
        scope.launch {
            try {
                children = withContext(Dispatchers.IO) { listChildFolders(ctx, rootUri, id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.localizedMessage ?: "Could not read this folder."
                children = emptyList()
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(current.id) { load(current.id) }

    BackHandler {
        if (path.size > 1) path = path.dropLast(1) else onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current.name, maxLines = 1) },
                navigationIcon = {
                    TextButton(onClick = { if (path.size > 1) path = path.dropLast(1) else onClose() }) { Text("Back") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                "Choose a subfolder to remove its library entries. The ebook files are not deleted.",
                style = MaterialTheme.typography.bodySmall
            )
            if (path.size > 1) {
                Text(path.joinToString(" / ") { it.name }, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            }
            Spacer(Modifier.height(8.dp))
            when {
                loading -> CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                children.isEmpty() -> Text("No subfolders here.", style = MaterialTheme.typography.bodyMedium)
                else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(children, key = { it.id }) { child ->
                        Row(
                            Modifier.fillMaxWidth().clickable { path = path + child }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(child.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Text("›", style = MaterialTheme.typography.titleLarge)
                        }
                        HorizontalDivider()
                    }
                }
            }
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                enabled = !busy,
                onClick = { confirmRemove = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Remove this folder from the library") }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove “${current.name}”?") },
            text = {
                Text(
                    "This removes the library entries for this folder and all its subfolders. The original ebook files stay where they are. Reading progress, edits, notes and shelf membership for those entries will be lost."
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    confirmRemove = false
                    busy = true
                    scope.launch {
                        try {
                            val removed = LibraryScanner.removeSubfolder(ctx, rootUri, current.id)
                            if (removed == 0) {
                                message = "No library entries matched this folder. If it was scanned before this update, run Rescan Folders once, then try again."
                            } else {
                                message = "$removed library entr${if (removed == 1) "y" else "ies"} removed. The ebook files are still there."
                                onClose()
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = "Could not remove this folder: ${e.localizedMessage ?: "unknown error"}"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } }
        )
    }
}

private fun listChildFolders(context: Context, rootUri: Uri, parentId: String): List<FolderEntry> {
    val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE
    )
    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentId)
    val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
        ?: throw IllegalStateException("Folder access was lost. Add the folder again, or restore its permission.")
    val out = mutableListOf<FolderEntry>()
    cursor.use { c ->
        val extras = runCatching { c.extras }.getOrNull()
        if (extras?.containsKey(DocumentsContract.EXTRA_ERROR) == true) {
            throw IllegalStateException("The folder provider reported an error while listing this folder.")
        }
        while (c.moveToNext()) {
            val id = c.getString(0) ?: continue
            val name = c.getString(1) ?: continue
            if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR && !name.startsWith(".")) {
                out.add(FolderEntry(id, name))
            }
        }
    }
    return out.sortedBy { it.name.lowercase() }
}
