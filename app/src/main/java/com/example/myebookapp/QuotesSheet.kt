package com.example.myebookapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotesBottomSheet(
    book: BookEntity,
    quotes: List<QuoteEntity>,
    onOpen: (QuoteEntity) -> Unit,
    onDelete: (QuoteEntity) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val sheetS = rememberModalBottomSheetState(true)
    var pendingDelete by remember { mutableStateOf<QuoteEntity?>(null) }
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetS) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                when (quotes.size) {
                    0 -> "No saved quotes yet"
                    1 -> "1 saved quote"
                    else -> "${quotes.size} saved quotes"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            if (quotes.isEmpty()) {
                Text(
                    "While reading, select a passage and choose Save quote in an EPUB or Quote in a PDF. Saved passages are listed here.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            quotes.forEach { quote ->
                Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(quote.text, style = MaterialTheme.typography.bodyMedium, maxLines = 8, overflow = TextOverflow.Ellipsis)
                        val meta = listOfNotNull(
                            quote.label?.takeIf { it.isNotBlank() },
                            dateFormat.format(Date(quote.createdAt))
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) {
                            Text(
                                meta,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { copyQuote(ctx, book, quote) }) { Text("Copy") }
                            TextButton(onClick = { shareQuote(ctx, book, quote) }) { Text("Share") }
                            TextButton(onClick = { onOpen(quote) }) { Text("Open") }
                            TextButton(onClick = { pendingDelete = quote }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    pendingDelete?.let { quote ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this quote?") },
            text = { Text("The passage stays in the book; only the saved copy is removed.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(quote)
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

private fun quoteShareText(book: BookEntity, quote: QuoteEntity): String {
    val source = listOf(book.title, book.author.takeIf { it.isNotBlank() && it != "Unknown" })
        .filterNotNull()
        .joinToString(" — ")
    return "\"${quote.text}\"\n\n— $source"
}

private fun copyQuote(ctx: Context, book: BookEntity, quote: QuoteEntity) {
    val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Quote", quoteShareText(book, quote)))
    Toast.makeText(ctx, "Quote copied", Toast.LENGTH_SHORT).show()
}

private fun shareQuote(ctx: Context, book: BookEntity, quote: QuoteEntity) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, quoteShareText(book, quote))
    }
    ctx.startActivity(Intent.createChooser(intent, "Share quote"))
}
