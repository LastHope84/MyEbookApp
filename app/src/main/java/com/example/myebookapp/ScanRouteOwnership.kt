package com.example.myebookapp

/**
 * Pure, fail-closed provenance calculations. This is NOT integrated with scanning or removal;
 * no result is authority to delete a row or a provider file. The caller must obtain a coherent,
 * fresh provider/ownership snapshot and serialize exclusion and scan writes in one transaction.
 * Provider IDs are opaque; a path is an observed sequence of identities, never a string prefix.
 * Completeness flags are attestations by the collector, not something this code can establish.
 */
internal data class RouteDocument(val authority: String, val documentId: String) {
    init { require(authority.isNotBlank() && documentId.isNotEmpty()) }
}
internal enum class RouteNodeKind { DIRECTORY, FILE }
internal data class RouteChild(val document: RouteDocument, val kind: RouteNodeKind)
internal data class RouteListing(val complete: Boolean, val children: List<RouteChild>)
internal data class RouteRoot(
    // The grant/configured-root identity is distinct even when two grants start at the same document.
    val rootId: String,
    val document: RouteDocument,
    val directories: Map<RouteDocument, RouteListing>
)
internal data class RouteGraph(val rootsComplete: Boolean, val roots: List<RouteRoot>)
internal data class ScanRoute(val rootId: String, val directories: List<RouteDocument>, val file: RouteDocument)
internal data class RouteExclusion(val rootId: String, val directory: RouteDocument)
internal data class RouteBook(
    val bookId: Long,
    // Null is allowed only for explicitly unknown legacy ownership; names/sizes are not identities.
    val document: RouteDocument?,
    val routes: List<ScanRoute>,
    val routesComplete: Boolean,
    val hasIndependentOwner: Boolean,
    val hasUnknownLegacyOwner: Boolean
)
internal data class RouteOwnershipInput(
    val graph: RouteGraph,
    val booksComplete: Boolean,
    val books: List<RouteBook>,
    val exclusions: Set<RouteExclusion>
)
internal enum class RouteRetention {
    KEEP_SCANNED_ROUTE, KEEP_INDEPENDENT, KEEP_UNKNOWN, KEEP_NO_PROVEN_ROUTE,
    NO_REMAINING_SCANNED_ROUTE // A diagnostic only, NOT permission to remove anything.
}
internal data class RouteBookDecision(
    val bookId: Long,
    val excludedRoutes: Set<ScanRoute>,
    val survivingRoutes: Set<ScanRoute>,
    val retention: RouteRetention
)

internal object ScanRouteOwnership {
    // A pathological multiparent graph can have exponentially many paths. Reject rather than
    // return a partial graph/ownership answer. This is a safety limit, not a scan implementation.
    private const val MAX_PATH_STEPS = 100_000
    private data class ValidGraph(
        val roots: Map<String, RouteRoot>,
        val allRoutes: Set<ScanRoute>,
        val kinds: Map<RouteDocument, RouteNodeKind>
    )

    private fun graph(input: RouteGraph): ValidGraph {
        require(input.rootsComplete && input.roots.isNotEmpty()) { "Root inventory missing or incomplete" }
        require(input.roots.all { it.rootId.isNotBlank() }) { "Empty root identity" }
        require(input.roots.map { it.rootId }.toSet().size == input.roots.size) { "Duplicate root identity" }
        val kinds = mutableMapOf<RouteDocument, RouteNodeKind>()
        val listings = mutableMapOf<RouteDocument, Set<RouteChild>>()
        val routes = mutableSetOf<ScanRoute>()
        var steps = 0
        for (root in input.roots) {
            val visited = mutableSetOf<RouteDocument>()
            val stack = ArrayDeque<Pair<RouteDocument, List<RouteDocument>>>()
            stack.add(root.document to emptyList())
            while (stack.isNotEmpty()) {
                require(++steps <= MAX_PATH_STEPS) { "Graph path limit exceeded" }
                val (dir, parents) = stack.removeLast()
                require(dir !in parents) { "Directory cycle" }
                val path = parents + dir
                require(dir.authority == root.document.authority) { "Cross-provider directory" }
                val priorKind = kinds.putIfAbsent(dir, RouteNodeKind.DIRECTORY)
                require(priorKind == null || priorKind == RouteNodeKind.DIRECTORY) { "Conflicting kinds" }
                val listing = requireNotNull(root.directories[dir]) { "Missing listing" }
                require(listing.complete) { "Incomplete listing" }
                require(listing.children.size == listing.children.toSet().size) { "Duplicate child" }
                val childSet = listing.children.toSet()
                val previousListing = listings.putIfAbsent(dir, childSet)
                require(previousListing == null || previousListing == childSet) { "Inconsistent listings" }
                visited.add(dir)
                for (child in listing.children) {
                    require(child.document.authority == root.document.authority) { "Cross-provider child" }
                    val old = kinds.putIfAbsent(child.document, child.kind)
                    require(old == null || old == child.kind) { "Conflicting kinds" }
                    when (child.kind) {
                        RouteNodeKind.DIRECTORY -> stack.add(child.document to path)
                        RouteNodeKind.FILE -> {
                            require(++steps <= MAX_PATH_STEPS) { "Graph path limit exceeded" }
                            routes.add(ScanRoute(root.rootId, path, child.document))
                        }
                    }
                }
            }
            require(visited == root.directories.keys) { "Unreachable directory evidence" }
        }
        return ValidGraph(input.roots.associateBy { it.rootId }, routes, kinds)
    }

    private fun validateExclusions(graph: ValidGraph, exclusions: Set<RouteExclusion>) {
        for (exclusion in exclusions) {
            val root = requireNotNull(graph.roots[exclusion.rootId]) { "Unknown excluded root" }
            require(exclusion.directory in root.directories) { "Excluded directory not in root graph" }
        }
    }

    private fun isExcluded(route: ScanRoute, exclusions: Set<RouteExclusion>): Boolean =
        exclusions.any { it.rootId == route.rootId && it.directory in route.directories }

    /** Recheck a single in-flight route against CURRENT exclusions, inside the caller's write barrier. */
    fun mayRecordRoute(graph: RouteGraph, exclusions: Set<RouteExclusion>, route: ScanRoute): Boolean {
        val valid = graph(graph)
        validateExclusions(valid, exclusions)
        require(route in valid.allRoutes) { "Unproven or ambiguous route" }
        return !isExcluded(route, exclusions)
    }

    /** Complete routes from validated, exhaustive graph; IDs are opaque. */
    fun routesByDocument(input: RouteGraph): Map<RouteDocument, Set<ScanRoute>> =
        graph(input).allRoutes.groupBy { it.file }.mapValues { it.value.toSet() }

    /** All possible routes for each known document must be supplied, not merely the first sighting. */
    fun assess(input: RouteOwnershipInput): List<RouteBookDecision> {
        val valid = graph(input.graph)
        validateExclusions(valid, input.exclusions)
        require(input.booksComplete) { "Book ownership inventory incomplete" }
        require(input.books.map { it.bookId }.toSet().size == input.books.size) { "Duplicate book identity" }
        val documents = input.books.mapNotNull { it.document }
        require(documents.toSet().size == documents.size) { "Document mapped to multiple books" }
        val routesByFile = valid.allRoutes.groupBy { it.file }
        return input.books.map { book ->
            require(book.routesComplete) { "Incomplete book routes" }
            require(book.routes.toSet().size == book.routes.size) { "Duplicate route" }
            if (book.document == null) {
                require(book.hasUnknownLegacyOwner && book.routes.isEmpty()) { "Ambiguous book identity" }
            } else {
                require(valid.kinds[book.document] != RouteNodeKind.DIRECTORY) { "Book identity is a directory" }
                val expected = routesByFile[book.document]?.toSet() ?: emptySet()
                require(book.routes.toSet() == expected) { "Missing, conflicting or unproven routes" }
            }
            val removed = book.routes.filter { isExcluded(it, input.exclusions) }.toSet()
            val remaining = book.routes.toSet() - removed
            val reason = when {
                book.hasUnknownLegacyOwner -> RouteRetention.KEEP_UNKNOWN
                book.hasIndependentOwner -> RouteRetention.KEEP_INDEPENDENT
                remaining.isNotEmpty() -> RouteRetention.KEEP_SCANNED_ROUTE
                book.routes.isEmpty() -> RouteRetention.KEEP_NO_PROVEN_ROUTE
                else -> RouteRetention.NO_REMAINING_SCANNED_ROUTE
            }
            RouteBookDecision(book.bookId, removed, remaining, reason)
        }
    }
}
