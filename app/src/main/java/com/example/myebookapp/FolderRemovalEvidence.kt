package com.example.myebookapp

/**
 * Read-only groundwork, NOT a removal implementation. Nothing here persists an exclusion,
 * modifies a library, or touches a provider. A plan is only a preview, never deletion authority.
 *
 * An Android collector must obtain authority/documentId with DocumentsContract, enumerate all
 * children (including hidden/unsupported files) with the correct tree grant, and mark a listing
 * complete ONLY after a successful cursor traversal with no loading/error extras before or after.
 * A null cursor, lost permission, provider error, cancellation, or unresolved identity is not an
 * empty listing. All configured roots and the selected directory need their own evidence.
 * Provider IDs are opaque: never parse them, compare prefixes, or use names/sizes as identity.
 *
 * Before using a preview to change state, callers still need durable exclusions, atomic ownership
 * updates, a scan/write barrier spanning in-flight batches, snapshot freshness and provider-change
 * handling, and safe crash recovery. Legacy/name-size-deduplicated records need special handling.
 */
internal data class SafDocumentKey(val authority: String, val documentId: String) {
    init { require(authority.isNotBlank() && documentId.isNotEmpty()) }
}

internal enum class SafKind { DIRECTORY, FILE }
internal data class SafChild(val key: SafDocumentKey, val kind: SafKind)
internal data class SafListing(val complete: Boolean, val children: List<SafChild>)
internal data class SafTreeEvidence(
    val root: SafDocumentKey,
    // A completed listing must be supplied for EVERY reachable directory, including empty ones.
    val directories: Map<SafDocumentKey, SafListing>
)

internal data class FolderBookEvidence(
    val bookId: Long,
    val document: SafDocumentKey?,
    // True only if ownership and dedup provenance are actually established; false for legacy,
    // manually imported, name/size-deduplicated, or otherwise uncertain records.
    val provenanceVerified: Boolean
)

internal enum class FolderPreviewDisposition {
    CANDIDATE_ONLY, KEEP_SHARED, KEEP_OUTSIDE, KEEP_AMBIGUOUS
}
internal data class FolderRemovalPreview(
    val books: Map<Long, FolderPreviewDisposition>,
    val selectedDocuments: Set<SafDocumentKey>
)

internal object FolderRemovalEvidence {
    private data class Walk(val kinds: Map<SafDocumentKey, SafKind>, val children: Map<SafDocumentKey, Set<SafChild>>)

    private fun walk(tree: SafTreeEvidence): Walk {
        val kinds = mutableMapOf(tree.root to SafKind.DIRECTORY)
        val children = mutableMapOf<SafDocumentKey, Set<SafChild>>()
        val queue = ArrayDeque<SafDocumentKey>()
        queue.add(tree.root)
        while (queue.isNotEmpty()) {
            val dir = queue.removeFirst()
            if (children.containsKey(dir)) continue
            val listing = requireNotNull(tree.directories[dir]) { "Missing directory listing" }
            require(listing.complete) { "Incomplete directory listing" }
            require(listing.children.size == listing.children.toSet().size) { "Duplicate child" }
            for (child in listing.children) {
                require(child.key.authority == tree.root.authority) { "Cross-provider child" }
                val previous = kinds.putIfAbsent(child.key, child.kind)
                require(previous == null || previous == child.kind) { "Conflicting document kind" }
                if (child.kind == SafKind.DIRECTORY && !children.containsKey(child.key)) queue.add(child.key)
            }
            children[dir] = listing.children.toSet()
        }
        // Ignore no dangling evidence: it could otherwise mask an incorrectly collected graph.
        require(tree.directories.keys == children.keys) { "Unreachable directory evidence" }
        // A directory cycle can make a selected subtree include an ancestor. Do not guess.
        val incoming = children.keys.associateWith { 0 }.toMutableMap()
        children.values.flatten().filter { it.kind == SafKind.DIRECTORY }.forEach {
            incoming[it.key] = incoming.getValue(it.key) + 1
        }
        val ready = ArrayDeque<SafDocumentKey>()
        incoming.filterValues { it == 0 }.keys.forEach { ready.add(it) }
        var visited = 0
        while (ready.isNotEmpty()) {
            val dir = ready.removeFirst()
            visited++
            children.getValue(dir).filter { it.kind == SafKind.DIRECTORY }.forEach {
                val remaining = incoming.getValue(it.key) - 1
                incoming[it.key] = remaining
                if (remaining == 0) ready.add(it.key)
            }
        }
        require(visited == children.size) { "Directory cycle; ancestry is ambiguous" }
        return Walk(kinds, children)
    }

    /** All roots and selected evidence must describe consistent, complete provider snapshots. */
    fun preview(selected: SafTreeEvidence, roots: List<SafTreeEvidence>, books: List<FolderBookEvidence>): FolderRemovalPreview {
        require(roots.isNotEmpty()) { "No configured roots" }
        require(roots.map { it.root }.toSet().size == roots.size) { "Duplicate root identity" }
        require(books.map { it.bookId }.toSet().size == books.size) { "Duplicate book ID" }
        val selectedWalk = walk(selected)
        val rootWalks = roots.map(::walk)
        val allWalks = listOf(selectedWalk) + rootWalks
        val globalKinds = mutableMapOf<SafDocumentKey, SafKind>()
        val globalListings = mutableMapOf<SafDocumentKey, Set<SafChild>>()
        for (walk in allWalks) {
            for ((key, kind) in walk.kinds) {
                val previous = globalKinds.putIfAbsent(key, kind)
                require(previous == null || previous == kind) { "Conflicting document kind across snapshots" }
            }
            for ((key, listing) in walk.children) {
                val previous = globalListings.putIfAbsent(key, listing)
                require(previous == null || previous == listing) { "Inconsistent provider snapshots" }
            }
        }
        val excluded = selectedWalk.kinds.keys
        require(rootWalks.any { selected.root in it.kinds } || roots.any { it.root in excluded }) {
            "Selection is not proven related to a configured root"
        }
        // A separately configured root inside the selection remains an independent surviving route.
        // For other roots prune the selected directory itself, not all its descendants:
        // a provider may expose the same document through another parent outside it.
        val reachableOutside = mutableSetOf<SafDocumentKey>()
        for ((index, root) in roots.withIndex()) {
            if (root.root == selected.root) continue
            val queue = ArrayDeque<SafDocumentKey>()
            queue.add(root.root)
            val seen = mutableSetOf<SafDocumentKey>()
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node == selected.root || !seen.add(node)) continue
                reachableOutside.add(node)
                rootWalks[index].children[node]?.forEach { queue.add(it.key) }
            }
        }
        val reachableFromRoots = rootWalks.flatMap { it.kinds.keys }.toSet()
        val result = books.associate { book ->
            val key = book.document
            val disposition = when {
                key == null || !book.provenanceVerified -> FolderPreviewDisposition.KEEP_AMBIGUOUS
                key !in reachableFromRoots || globalKinds[key] != SafKind.FILE -> FolderPreviewDisposition.KEEP_AMBIGUOUS
                key !in excluded -> FolderPreviewDisposition.KEEP_OUTSIDE
                key in reachableOutside -> FolderPreviewDisposition.KEEP_SHARED
                else -> FolderPreviewDisposition.CANDIDATE_ONLY
            }
            book.bookId to disposition
        }
        return FolderRemovalPreview(result, excluded.toSet())
    }
}
