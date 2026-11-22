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
seale