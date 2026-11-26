package com.example.myebookapp

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemovedBooksScreen(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember { AppDatabase.getInstance(ctx).bookDao() }
    var rows by remember { mutableStateOf<List<RemovedBook>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    suspend fun refresh() {
        rows = withContext(Dispatchers.IO) { dao.getRemovedBooks() }
    }

    LaunchedEffect(Unit) { refresh() }
    BackHandler { onClose() }

    fun rescan() {
        val folders = LibraryScanner.getSavedFolders(ctx)
        if (folders.isNotEmpty()) LibraryScanner.scanFolders(ctx, folders.map(Uri::parse))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Removed books") },
                navigationIcon = { TextButton(onClick = onClose) { Text("Back") } }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text(
                "Books you remove from the library stay removed; folder scans skip them. Restore one here, or add the file again with Add File, to bring it back.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(12.dp))
            message?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }
            if (rows.isEmpty()) {
                Text("Nothing has been removed.", style = MaterialTheme.typography.bodyMedium)
            } else {
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            try {
                                busy = true
                                withContext(Dispatchers.IO) { dao.clearAllRemoved() }
                                refresh()
                                rescan()
                                message = "All removed books will be added back by the next scan."
                            } catch (e: CancellationException) {
                                throw e
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Restore all") }
                Spacer(Modifier.height(12.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(rows, key = { it.uriString }) { row ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    row.fileName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2
                                )
                                Text(
                                    "Removed ${dateFormat.format(Date(row.removedAt))}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    TextButton(
                                        enabled = !busy,
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    busy = true
                                                    withContext(Dispatchers.IO) { dao.clearRemoved(row.uriString) }
                                                    refresh()
                                                    rescan()
                                                    message = "“${row.fileName}” will be added back by the next scan."
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } finally {
                                                    busy = false
                                                }
                                            }
                                        }
                                    ) { Text("Restore") }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
