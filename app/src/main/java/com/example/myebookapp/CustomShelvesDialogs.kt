package com.example.myebookapp

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CreateShelfDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit, saving: Boolean) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Create custom shelf") },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Shelf name") },
                singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(enabled = !saving && name.trim().isNotEmpty(), onClick = { onCreate(name.trim()) }) { Text("Create") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun CustomShelfMembershipDialog(
    shelves: List<CustomShelf>, shelfIds: Set<Long>, saving: Boolean,
    onDismiss: () -> Unit, onChange: (CustomShelf, Boolean) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom shelves") },
        text = {
            if (shelves.isEmpty()) Text("No custom shelves yet. Create one from the library menu.")
            else Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                Text("A book can be on more than one shelf.", style = MaterialTheme.typography.bodySmall)
                shelves.forEach { shelf ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = shelf.id in shelfIds, enabled = !saving,
                            onCheckedChange = { onChange(shelf, it) })
                        Text(shelf.name, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Done") } }
    )
}
