package com.example.myebookapp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Integration boundary. Never enable a mutation until it atomically coordinates with the
 * scanner (including in-flight work), revalidates identities and retains uncertain/shared
 * books. Mutations must not touch ebook files. Returned text must reflect actual outcomes,
 * including retained rows, failures and whether a rescan is required. An implementation
 * may stop and restart scanning to reach a safe point. Never substitute the experimental
 * exclusion-only core for a complete coordinated operation. Operations must handle lifecycle
 * cancellation safely and leave persisted state recoverable; on an ambiguous/partial failure,
 * return UNKNOWN/PARTIAL with factual details rather than claiming it was not applied.
 */
interface FolderManagementAdapter {
    val canRemove: Boolean
    val canRestore: Boolean
    suspend fun roots(): FolderManagementList<FolderManagementRoot>
    suspend fun exclusions(): FolderManagementList<FolderManagementExclusion>
    suspend fun browse(location: FolderManagementLocation): FolderManagementList<FolderManagementDirectory>
    suspend fun remove(target: FolderManagementRemoval): FolderManagementOutcome
    suspend fun restore(target: FolderManagementExclusion): FolderManagementOutcome
}

/** Read-only bridge; intentionally does not use the experimental exclusion-only core. */
class ReadOnlyFolderManagementAdapter(context: Context) : FolderManagementAdapter {
    private val context = context.applicationContext
    override val canRemove = false
    override val canRestore = false

    override suspend fun roots(): FolderManagementList<FolderManagementRoot> = withContext(Dispatchers.IO) {
        val roots = mutableListOf<FolderManagementRoot>()
        var invalid = false
        for (saved in LibraryScanner.getSavedFolders(context)) {
            try {
                val uri = Uri.parse(saved)
                require(uri.authority != null)
                val id = DocumentsContract.getTreeDocumentId(uri)
                require(id.isNotEmpty())
                // The tree document ID is a provider label, not a filesystem path.
                roots.add(FolderManagementRoot(saved, id, id))
            } catch (_: Exception) { invalid = true }
        }
        FolderManagementList(roots, complete = !invalid, error = if (invalid) "Some saved roots could not be read." else null)
    }

    override suspend fun exclusions(): FolderManagementList<FolderManagementExclusion> =
        FolderManagementList(complete = false, error = "Exclusion listing is not integrated yet.")

    override suspend fun browse(location: FolderManagementLocation): FolderManagementList<FolderManagementDirectory> = withContext(Dispatchers.IO) {
        require(location.rootId in LibraryScanner.getSavedFolders(context)) { "Scan root is no longer saved." }
        val tree = Uri.parse(location.rootId)
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, location.directoryId)
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        val found = mutableListOf<FolderManagementDirectory>()
        val cursor = context.contentResolver.query(uri, columns, null, null, null)
            ?: error("Provider returned no folder listing.")
        cursor.use { c ->
            fun isComplete() = !c.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) && !c.extras.containsKey(DocumentsContract.EXTRA_ERROR)
            var complete = isComplete()
            while (c.moveToNext()) {
                if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val id = c.getString(0) ?: error("Provider returned a folder without an ID.")
                    found.add(FolderManagementDirectory(id, c.getString(1) ?: id))
                }
            }
            complete = complete && isComplete()
            FolderManagementList(found.sortedBy { it.name.lowercase() }, complete = complete,
                error = if (!complete) "Provider returned an incomplete folder listing." else null)
        }
    }

    override suspend fun remove(target: FolderManagementRemoval): FolderManagementOutcome = error("Folder removal is not integrated.")
    override suspend fun restore(target: FolderManagementExclusion): FolderManagementOutcome = error("Restoring exclusions is not integrated.")
}

@Composable
fun FolderManagementDialog(adapter: FolderManagementAdapter, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember(adapter) { mutableStateOf(FolderManagementState(canRemove = adapter.canRemove, canRestore = adapter.canRestore)) }
    var rootsGeneration by remember(adapter) { mutableIntStateOf(0) }
    var exclusionsGeneration by remember(adapter) { mutableIntStateOf(0) }
    var browseGeneration by remember(adapter) { mutableIntStateOf(0) }
    fun message(e: Exception) = e.localizedMessage ?: "Unknown error"
    fun refreshRoots() {
        val generation = ++rootsGeneration
        state = state.copy(roots = state.roots.copy(loading = true, error = null))
        scope.launch {
            val result = try { adapter.roots() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                FolderManagementList(state.roots.items, complete = false, error = message(e))
            }
            if (generation == rootsGeneration) state = state.copy(roots = result.copy(loading = false))
        }
    }
    fun refreshExclusions() {
        val generation = ++exclusionsGeneration
        state = state.copy(exclusions = state.exclusions.copy(loading = true, error = null))
        scope.launch {
            val result = try { adapter.exclusions() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                FolderManagementList(state.exclusions.items, complete = false, error = message(e))
            }
            if (generation == exclusionsGeneration) state = state.copy(exclusions = result.copy(loading = false))
        }
    }
    fun browse(location: FolderManagementLocation) {
        val generation = ++browseGeneration
        val old = state.browse?.takeIf { it.location == location }?.directories?.items.orEmpty()
        state = state.copy(browse = FolderManagementBrowseResult(location, FolderManagementList(old, loading = true, complete = false)))
        scope.launch {
            val result = try { adapter.browse(location) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                FolderManagementList(old, complete = false, error = message(e))
            }
            if (generation == browseGeneration) state = state.copy(browse = FolderManagementBrowseResult(location, result.copy(loading = false)))
        }
    }
    fun mutate(action: suspend () -> FolderManagementOutcome) {
        if (state.operationInProgress) return
        // Invalidate in-flight reads, which may have observed pre-mutation state.
        rootsGeneration++
        exclusionsGeneration++
        browseGeneration++
        state = state.copy(operationInProgress = true, operationMessage = null, operationError = null,
            browse = null, roots = state.roots.copy(loading = false),
            exclusions = state.exclusions.copy(loading = false))
        scope.launch {
            try {
                val result = action()
                state = state.copy(operationMessage = result.toUserMessage())
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                // An exception may follow a partial commit; never claim nothing changed.
                state = state.copy(operationError = "Folder change could not be confirmed. Some changes may have occurred. Refresh and check the library; scan status could not be confirmed. ${message(e)}")
            } finally {
                state = state.copy(operationInProgress = false)
                // Also refresh after failure, since an operation may have partly committed.
                refreshRoots()
                refreshExclusions()
            }
        }
    }
    LaunchedEffect(adapter) { refreshRoots(); refreshExclusions() }
    Dialog(onDismissRequest = { if (!state.operationInProgress) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
            Column(Modifier.fillMaxWidth().heightIn(max = 650.dp).padding(16.dp)) {
                FolderManagementUi(state, ::browse, ::refreshRoots, ::refreshExclusions,
                    onRemove = { target -> if (adapter.canRemove) mutate { adapter.remove(target) } },
                    onRestore = { target -> if (adapter.canRestore) mutate { adapter.restore(target) } },
                    modifier = Modifier.weight(1f, fill = false))
                TextButton(enabled = !state.operationInProgress, onClick = onDismiss) { Text("Done") }
            }
        }
    }
}
