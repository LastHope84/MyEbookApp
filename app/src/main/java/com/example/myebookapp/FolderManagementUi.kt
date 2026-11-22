package com.example.myebookapp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Opaque identifiers supplied and interpreted only by the host. A directory's ID can be
 * a provider document URI or another stable token; it is NOT a filesystem path. The root
 * token must uniquely identify a saved tree, even when two trees overlap or names repeat.
 */
data class FolderManagementRoot(val id: String, val name: String, val directoryId: String)
data class FolderManagementDirectory(val id: String, val name: String)
data class FolderManagementLocation(val rootId: String, val directoryId: String)

/** A record ID identifies the exact saved exclusion to restore, not a display path. */
data class FolderManagementExclusion(val id: String, val rootName: String, val folderName: String)

/**
 * Keep existing items visible during loading/errors. complete=false means the provider or
 * host returned only part of the list; the UI never treats a partial empty list as complete.
 * A host that supports pagination can load more data through the appropriate retry callback.
 */
data class FolderManagementList<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val complete: Boolean = true,
    val error: String? = null
)

/** The adapter reports facts, not a promise that every book was removed. Counts may be unknown. */
enum class FolderManagementChange { APPLIED, PARTIAL, NOT_APPLIED, UNKNOWN }
enum class FolderManagementScan { UNCHANGED, RESTARTED, RESTART_REQUIRED, STOPPED, UNKNOWN }
data class FolderManagementOutcome(
    val change: FolderManagementChange,
    val removedRows: Int? = null,
    val retainedRows: Int? = null,
    val scan: FolderManagementScan = FolderManagementScan.UNKNOWN,
    val detail: String? = null
) {
    init {
        require(removedRows == null || removedRows >= 0)
        require(retainedRows == null || retainedRows >= 0)
    }

    fun toUserMessage(): String = buildList {
        add(when (change) {
            FolderManagementChange.APPLIED -> "Folder change applied."
            FolderManagementChange.PARTIAL -> "Folder change partially applied; check the library."
            FolderManagementChange.NOT_APPLIED -> "Folder change was not applied."
            FolderManagementChange.UNKNOWN -> "The result of the folder change could not be confirmed; check the library."
        })
        removedRows?.let { add("Library entries removed: $it.") }
        retainedRows?.let { add("Library entries retained: $it.") }
        add(when (scan) {
            FolderManagementScan.UNCHANGED -> "Scan status unchanged."
            FolderManagementScan.RESTARTED -> "Scanning restarted."
            FolderManagementScan.RESTART_REQUIRED -> "A rescan is required."
            FolderManagementScan.STOPPED -> "Scanning stopped and has not restarted."
            FolderManagementScan.UNKNOWN -> "Scan status could not be confirmed."
        })
        detail?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString("\n")
}

/** location must match the requested root AND directory; ignore stale asynchronous replies. */
data class FolderManagementBrowseResult(
    val location: FolderManagementLocation,
    val directories: FolderManagementList<FolderManagementDirectory>
)

data class FolderManagementState(
    val roots: FolderManagementList<FolderManagementRoot> = FolderManagementList(loading = true, complete = false),
    val exclusions: FolderManagementList<FolderManagementExclusion> = FolderManagementList(loading = true, complete = false),
    val browse: FolderManagementBrowseResult? = null,
    val operationInProgress: Boolean = false,
    val canRemove: Boolean = false,
    val canRestore: Boolean = false,
    val operationMessage: String? = null,
    val operationError: String? = null
)

/** Remove a saved root, or exclude one provider directory inside that particular root. */
sealed interface FolderManagementRemoval {
    val root: FolderManagementRoot
    data class Root(override val root: FolderManagementRoot) : FolderManagementRemoval
    data class Subfolder(
        override val root: FolderManagementRoot,
        val directory: FolderManagementDirectory
    ) : FolderManagementRemoval
}

/**
 * Independent, side-effect-free presentation. The host owns I/O, permissions, safety checks,
 * scan coordination and state. Opening this UI does not start, pause or stop a scan.
 *
 * onBrowse requests direct child DIRECTORIES only, asynchronously. On each request the host
 * should set a matching loading result immediately and discard old responses (including older
 * requests for the same location). Retry must replace/merge the host's page as appropriate.
 * onRemove and onRestore must revalidate the exact target and apply a safe operation; they
 * MUST NOT delete files and must preserve unknown/shared provenance. A callback is invoked
 * only after a user's confirmation. Populate operationInProgress until the operation finishes.
 */
@Composable
fun FolderManagementUi(
    state: FolderManagementState,
    onBrowse: (FolderManagementLocation) -> Unit,
    onRefreshRoots: () -> Unit,
    onRefreshExclusions: () -> Unit,
    onRemove: (FolderManagementRemoval) -> Unit,
    onRestore: (FolderManagementExclusion) -> Unit,
    modifier: Modifier = Modifier
) {
    var openRoot by remember { mutableStateOf<FolderManagementRoot?>(null) }
    var path by remember { mutableStateOf<List<FolderManagementDirectory>>(emptyList()) }
    var removal by remember { mutableStateOf<FolderManagementRemoval?>(null) }
    var restoring by remember { mutableStateOf<FolderManagementExclusion?>(null) }

    val root = openRoot
    val rootStillPresent = root != null && state.roots.items.any { it.id == root.id }
    val location = root?.let {
        FolderManagementLocation(it.id, path.lastOrNull()?.id ?: it.directoryId)
    }
    val matchingBrowse = state.browse?.takeIf { it.location == location }
    val busy = state.operationInProgress

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text("Manage library folders", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Folder browsing does not pause or stop a scan. Original files are never deleted by folder management.",
                style = MaterialTheme.typography.bodySmall
            )
            if (!state.canRemove) Text("Removal is not available yet; safety integration is incomplete.", style = MaterialTheme.typography.bodySmall)
            if (!state.canRestore) Text("Restoring exclusions is not available yet.", style = MaterialTheme.typography.bodySmall)
            if (busy) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Text("Applying folder change…", style = MaterialTheme.typography.bodySmall)
                }
            }
            state.operationMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.operationError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }

        if (root == null) {
            item {
                HorizontalDivider()
                Text("Scan roots", style = MaterialTheme.typography.titleMedium)
                FolderListStatus(state.roots, "No scan folders saved.", onRefreshRoots, !busy)
            }
            items(state.roots.items) { savedRoot ->
                Column {
                    Text(savedRoot.name, style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = {
                            openRoot = savedRoot
                            path = emptyList()
                            onBrowse(FolderManagementLocation(savedRoot.id, savedRoot.directoryId))
                        }) { Text("Browse") }
                        TextButton(enabled = !busy && state.canRemove, onClick = { removal = FolderManagementRemoval.Root(savedRoot) }) {
                            Text("Remove root")
                        }
                    }
                }
            }
        } else {
            item {
                HorizontalDivider()
                TextButton(enabled = !busy, onClick = {
                    if (path.isEmpty()) {
                        openRoot = null
                    } else {
                        val parentPath = path.dropLast(1)
                        path = parentPath
                        onBrowse(FolderManagementLocation(root.id, parentPath.lastOrNull()?.id ?: root.directoryId))
                    }
                }) { Text(if (path.isEmpty()) "Back to roots" else "Back to parent") }
                Text(root.name, style = MaterialTheme.typography.titleMedium)
                if (path.isNotEmpty()) {
                    // Names are for display only. No path parsing or ancestry inference.
                    Text(path.joinToString(" › ") { it.name }, style = MaterialTheme.typography.bodySmall)
                }
                if (!rootStillPresent && state.roots.complete && !state.roots.loading && state.roots.error == null) {
                    Text("This scan root is no longer available. Return to roots.", color = MaterialTheme.colorScheme.error)
                }
                val target = path.lastOrNull()
                TextButton(enabled = !busy && rootStillPresent && state.canRemove, onClick = {
                    removal = if (target == null) FolderManagementRemoval.Root(root)
                    else FolderManagementRemoval.Subfolder(root, target)
                }) { Text(if (target == null) "Remove this root" else "Remove this folder") }
                Text("Subfolders", style = MaterialTheme.typography.titleMedium)
                if (matchingBrowse == null) {
                    Text("Folder contents not loaded.", style = MaterialTheme.typography.bodySmall)
                    TextButton(enabled = !busy && rootStillPresent, onClick = {
                        location?.let(onBrowse)
                    }) { Text("Load folders") }
                } else {
                    FolderListStatus(matchingBrowse.directories, "No subfolders found.", {
                        location?.let(onBrowse)
                    }, !busy && rootStillPresent)
                }
            }
            items(matchingBrowse?.directories?.items.orEmpty()) { directory ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(directory.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    TextButton(enabled = !busy && rootStillPresent, onClick = {
                        path = path + directory
                        onBrowse(FolderManagementLocation(root.id, directory.id))
                    }) { Text("Open") }
                    TextButton(enabled = !busy && rootStillPresent && state.canRemove, onClick = {
                        removal = FolderManagementRemoval.Subfolder(root, directory)
                    }) { Text("Remove") }
                }
            }
        }

        item {
            HorizontalDivider()
            Text("Excluded folders", style = MaterialTheme.typography.titleMedium)
            Text("Restoring an exclusion allows that folder to be scanned again; previous library details may not return.", style = MaterialTheme.typography.bodySmall)
            FolderListStatus(state.exclusions, "No excluded folders.", onRefreshExclusions, !busy)
        }
        items(state.exclusions.items) { exclusion ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(exclusion.folderName, style = MaterialTheme.typography.bodyLarge)
                    Text(exclusion.rootName, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(enabled = !busy && state.canRestore, onClick = { restoring = exclusion }) { Text("Restore") }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    removal?.let { target ->
        val valid = state.roots.items.any { it.id == target.root.id }
        val name = when (target) {
            is FolderManagementRemoval.Root -> target.root.name
            is FolderManagementRemoval.Subfolder -> target.directory.name
        }
        AlertDialog(
            onDismissRequest = { removal = null },
            title = { Text(if (target is FolderManagementRemoval.Root) "Remove scan root?" else "Remove folder from library?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$name\nScan root: ${target.root.name}")
                    Text("Original ebook files stay where they are. Removed library entries may lose reading progress, notes, edits and other library details.")
                    Text(if (target is FolderManagementRemoval.Root) "This stops using the selected scan root." else "This excludes the selected folder under this scan root. Other scan roots may still include it.")
                    Text("Entries with uncertain or shared origins will be retained, so not every book may disappear from the library.")
                    Text("An ongoing scan may be stopped and restarted to make the change safely.")
                    if (!valid) Text("This scan root is no longer available; refresh and try again.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && valid && state.canRemove, onClick = {
                    removal = null
                    onRemove(target)
                }) { Text("Remove from library") }
            },
            dismissButton = { TextButton(onClick = { removal = null }) { Text("Cancel") } }
        )
    }

    restoring?.let { exclusion ->
        val valid = state.exclusions.items.any { it.id == exclusion.id }
        AlertDialog(
            onDismissRequest = { restoring = null },
            title = { Text("Restore excluded folder?") },
            text = {
                Text("${exclusion.folderName}\nScan root: ${exclusion.rootName}\n\nThis permits future scanning, but does not guarantee books or library details will return. An ongoing scan may be stopped and restarted.")
            },
            confirmButton = {
                TextButton(enabled = !busy && valid && state.canRestore, onClick = {
                    restoring = null
                    onRestore(exclusion)
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { restoring = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun <T> FolderListStatus(
    list: FolderManagementList<T>,
    emptyMessage: String,
    onRetry: () -> Unit,
    retryEnabled: Boolean
) {
    if (list.loading) Text("Loading…", style = MaterialTheme.typography.bodySmall)
    list.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    if (!list.complete) Text("Results may be incomplete.", style = MaterialTheme.typography.bodySmall)
    if (!list.loading && list.error == null && list.complete && list.items.isEmpty()) {
        Text(emptyMessage, style = MaterialTheme.typography.bodySmall)
    }
    if (!list.loading && (list.error != null || !list.complete)) {
        TextButton(enabled = retryEnabled, onClick = onRetry) { Text("Retry / load more") }
    }
}
