package com.example.myebookapp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Search results are only a draft: the user chooses fields and explicitly saves later. */
@Composable
fun OnlineMetadataPicker(
    candidates: List<OnlineBookCandidate>,
    onDismiss: () -> Unit,
    onApply: (OnlineBookCandidate, Set<String>) -> Unit
) {
    var selected by remember { mutableStateOf<OnlineBookCandidate?>(null) }
    val candidate = selected
    if (candidate == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Choose a match") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("Check the edition carefully. Online catalogues can contain incomplete or mixed-edition information.", style = MaterialTheme.typography.bodySmall)
                    candidates.forEach { item ->
                        Column(Modifier.fillMaxWidth().clickable { selected = item }.padding(vertical = 10.dp)) {
                            Text(item.title, style = MaterialTheme.typography.titleSmall)
                            Text(listOf(item.author, item.source, item.publishingDate, item.publisher).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
    } else {
        var fields by remember(candidate) {
            mutableStateOf(buildSet {
                if (candidate.title.isNotBlank()) add("title")
                if (candidate.author.isNotBlank()) add("author")
                // Open Library search can combine work and edition fields.
                if (candidate.source != "Open Library") {
                    if (candidate.publishingDate.isNotBlank()) add("date")
                    if (candidate.publisher.isNotBlank()) add("publisher")
                    if (candidate.language.isNotBlank()) add("language")
                }
                if (!candidate.coverUrl.isNullOrBlank()) add("cover")
            })
        }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Choose fields to apply") },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    Text("Source: ${candidate.source}. Unchecked and unavailable fields stay as they are. Cover is downloaded only if selected.", style = MaterialTheme.typography.bodySmall)
                    if (candidate.source == "Open Library") Text("Date, publisher and language may refer to different editions and are unchecked by default.", style = MaterialTheme.typography.bodySmall)
                    val options = listOf(
                        Triple("title", "Title", candidate.title),
                        Triple("author", "Author", candidate.author),
                        Triple("date", "Publication date", candidate.publishingDate),
                        Triple("publisher", "Publisher", candidate.publisher),
                        Triple("language", "Language", candidate.language),
                        Triple("cover", "Cover", if (candidate.coverUrl.isNullOrBlank()) "" else "Download cover")
                    )
                    options.filter { it.third.isNotBlank() }.forEach { (key, label, value) ->
                        Row(Modifier.fillMaxWidth().clickable {
                            fields = if (key in fields) fields - key else fields + key
                        }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = key in fields, onCheckedChange = { checked ->
                                fields = if (checked) fields + key else fields - key
                            })
                            Column(Modifier.weight(1f)) {
                                Text(label, style = MaterialTheme.typography.labelMedium)
                                Text(value, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { onApply(candidate, fields) }) { Text("Apply to draft") } },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Back") } }
        )
    }
}
