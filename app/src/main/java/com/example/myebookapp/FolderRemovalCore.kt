package com.example.myebookapp

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Exclusion is durable. Rows with uncertain identity/ownership are deliberately retained.
 * The saved-root preference is not rewritten; UI must display exclusions separately. */
data class FolderRemovalResult(val removedRows: Int, val retainedRows: Int, val candidateRows: Int, val authority: String, val documentId: String)

/** Only an unmodified, scanner-default record can be deleted: metadata and reading state
 * have no separate edit provenance, so even automatically extracted metadata is retained. */
internal fun BookEntity.hasPristineScannerState(): Boolean =
    title == fileName.substringBeforeLast('.') && author == "Unknown" && publishingDate == "Unknown" &&
    publisher == "Unknown" && language == "Unknown" && coverPath == null && lastLocatorJson == null &&
    progressPercent == 0.0 && lastOpenedAt == 0L && series == null && seriesIndex == null &&
    tags == null && !isFavorite && readingStatus == "NEW" && metadataExtracted == (format != "EPUB")

internal object FolderRemovalCore {
    private data class Snapshot(val evidence: SafTreeEvidence, val fileKeys: Map<SafDocumentKey, Pair<String, Long>>)
    private suspend fun snapshot(context: Context, tree: Uri, startId: String): Snapshot {
        val authority = requireNotNull(tree.authority)
        val root = SafDocumentKey(authority, startId)
        val dirs = mutableMapOf<SafDocumentKey, SafListing>()
        val todo = ArrayDeque<String>().apply { add(startId) }
        val fileKeys = mutableMapOf<SafDocumentKey, Pair<String, Long>>()
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE)
        while (todo.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val id = todo.removeFirst()
            val key = SafDocumentKey(authority, id)
            if (key in dirs) continue
            val children = mutableListOf<SafChild>()
            val cursor = context.contentResolver.query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), projection, null, null, null)
                ?: error("Provider returned no directory listing")
            cursor.use { c ->
                fun complete() = check(!c.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) && !c.extras.containsKey(DocumentsContract.EXTRA_ERROR)) { "Incomplete provider listing" }
                complete()
                while (c.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val childId = c.getString(0) ?: error("Provider returned a child without an ID")
                    val kind = if (c.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR) SafKind.DIRECTORY else SafKind.FILE
                    children.add(SafChild(SafDocumentKey(authority, childId), kind))
                    if (kind == SafKind.DIRECTORY) todo.add(childId) else {
                        val name = c.getString(2) ?: error("Provider returned a file without a name")
                        val size = if (c.isNull(3)) 0L else c.getLong(3)
                        val value = name.lowercase() to size
                        val prior = fileKeys.put(SafDocumentKey(authority, childId), value)
                        check(prior == null || prior == value) { "Inconsistent file identity" }
                    }
                }
                complete()
            }
            dirs[key] = SafListing(true, children.sortedWith(compareBy({ it.key.authority }, { it.key.documentId }, { it.kind.name })))
        }
        return Snapshot(SafTreeEvidence(root, dirs), fileKeys)
    }

    private suspend fun collect(context: Context, tree: Uri, startId: String): SafTreeEvidence =
        snapshot(context, tree, startId).evidence

    suspend fun remove(context: Context, selectedTree: Uri, directoryId: String, rootUris: List<String>): FolderRemovalResult {
        require(rootUris.isNotEmpty() && selectedTree.toString() in rootUris)
        require(directoryId.isNotEmpty())
        val authority = requireNotNull(selectedTree.authority)
        val selectedDocument = RouteDocument(authority, directoryId)
        suspend fun capture(): List<Snapshot> = rootUris.map { root ->
            val tree = Uri.parse(root)
            snapshot(context, tree, DocumentsContract.getTreeDocumentId(tree))
        }
        // Providers offer no atomic cross-root snapshot. Two identical complete enumerations
        // detect observed changes, but cannot stop a provider changing immediately afterward.
        val first = capture()
        val second = capture()
        check(first == second) { "Folders changed during verification; nothing was removed" }
        val graph = RouteGraph(true, second.mapIndexed { i, snap ->
            val e = snap.evidence
            RouteRoot(rootUris[i], RouteDocument(e.root.authority, e.root.documentId), e.directories.map { (key, listing) ->
                RouteDocument(key.authority, key.documentId) to RouteListing(listing.complete, listing.children.map {
                    RouteChild(RouteDocument(it.key.authority, it.key.documentId),
                        if (it.kind == SafKind.DIRECTORY) RouteNodeKind.DIRECTORY else RouteNodeKind.FILE)
                })
            }.toMap())
        })
        val routes = ScanRouteOwnership.routesByDocument(graph)
        val selectedRoot = graph.roots.single { it.rootId == selectedTree.toString() }
        require(selectedDocument in selectedRoot.directories) { "Selected directory is not in the scan root" }
        // Do not delete if the scan's name/size dedup could have attributed an otherwise
        // independent file to this row. Missing size is also ambiguous.
        val names = mutableMapOf<RouteDocument, Pair<String, Long>>()
        for (snap in second) for ((k, v) in snap.fileKeys) {
            val doc = RouteDocument(k.authority, k.documentId)
            val old = names.put(doc, v)
            check(old == null || old == v) { "Inconsistent file details" }
        }
        val duplicateKeys = names.entries.filter { it.value.second > 0 }.groupBy { it.value }
            .filterValues { entries -> entries.map { it.key }.toSet().size > 1 }.keys
        val dao = AppDatabase.getInstance(context).bookDao()
        val books = dao.allRemovalBooks()
        val origins = dao.allScanBookOrigins().associateBy { it.bookId }
        val candidates = mutableListOf<RemovalCandidate>()
        var affected = 0
        for (book in books) {
            currentCoroutineContext().ensureActive()
            val uri = Uri.parse(book.uriString)
            val doc = try { RouteDocument(requireNotNull(uri.authority), DocumentsContract.getDocumentId(uri)) } catch (_: Exception) { continue }
            val fileRoutes = routes[doc].orEmpty()
            val removedRoutes = fileRoutes.filter { it.rootId == selectedTree.toString() && selectedDocument in it.directories }
            if (removedRoutes.isEmpty()) continue
            affected++
            if (fileRoutes.size != removedRoutes.size) continue
            val origin = origins[book.id] ?: continue
            if (origin.rootUri != selectedTree.toString() || origin.documentId != doc.documentId) continue
            if (book.uriString != DocumentsContract.buildDocumentUriUsingTree(selectedTree, doc.documentId).toString()) continue
            if (removedRoutes.none { it.directories.last().documentId == origin.parentDocumentId }) continue
            val key = names[doc] ?: continue
            if (key.second <= 0 || key in duplicateKeys || key != (book.fileName.lowercase() to book.fileSize)) continue
            if (!book.hasPristineScannerState()) continue
            candidates.add(RemovalCandidate(book, origin))
        }
        currentCoroutineContext().ensureActive()
        // Two matching enumerations still do not attest that another app/provider will not
        // change the graph before the Room commit. A move or new shared route in that gap
        // could make deleting a row wrong. SAF has no general cross-provider transaction or
        // graph-generation token. Fail closed rather than treating repetition as proof.
        error("Folder removal unavailable: the provider cannot guarantee a stable ownership snapshot; no rows or exclusions changed")
    }

    suspend fun exclude(context: Context, selectedTree: Uri, directoryId: String, rootUris: List<String>): FolderRemovalResult {
        require(rootUris.isNotEmpty()) { "No scan roots are configured" }
        require(selectedTree.toString() in rootUris) { "Selection must use a configured root's grant" }
        require(directoryId.isNotEmpty())
        val dao = AppDatabase.getInstance(context).bookDao()
        // Until cleanup is implemented, one traversal is sufficient only to validate the
        // target under this grant; it is NOT evidence of exclusive book ownership.
        val grantRoot = collect(context, selectedTree, DocumentsContract.getTreeDocumentId(selectedTree))
        val authority = requireNotNull(selectedTree.authority)
        check(SafDocumentKey(authority, directoryId) in grantRoot.directories) {
            "Selected directory is not inside the chosen scan root"
        }
        currentCoroutineContext().ensureActive()
        dao.commitRootExclusion(ScanRootExclusion(selectedTree.toString(), directoryId))
        return FolderRemovalResult(0, dao.countAllBooks(), 0, authority, directoryId)
    }
}
