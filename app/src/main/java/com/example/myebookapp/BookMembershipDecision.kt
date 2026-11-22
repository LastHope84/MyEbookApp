package com.example.myebookapp

/**
 * Standalone decision about the app's OBSERVED memberships, not a provider snapshot, physical
 * deletion, or an implementation of visibility. The collector must attest complete, successfully
 * traversed coverage of all configured roots and all paths for this exact book, including
 * multiparent paths. Missing access, failed/loading cursors, cancellation, unresolved identity,
 * old name/size deduplication, stale evidence, or an unsafe scan/write makes it incomplete.
 * Android SAF traversal is not atomic: even a completed observation is not a claim about the
 * provider's current physical state. These inputs cannot prove the collector's attestations.
 *
 * Any eventual caller must separately implement reversible visibility, persistence, freshness,
 * invalidation and scan/write coordination. Nothing in this module performs those actions.
 */
internal data class MembershipDocument(val authority: String, val documentId: String)
internal data class MembershipRoot(val id: String, val document: MembershipDocument)

/** Ordered directory identities, starting at this grant's root. No URI/string-prefix inference. */
internal data class MembershipRoute(
    val rootId: String,
    val directories: List<MembershipDocument>,
    val file: MembershipDocument,
    // True only when the collector verified each parent-child edge under this particular grant.
    val edgesVerified: Boolean
)

internal data class MembershipObservation(
    val rootId: String,
    // True only after successful, accessible, exhaustive coverage of this root for this book.
    val coverageComplete: Boolean,
    val routes: List<MembershipRoute>
)

/** Exclude every route through this directory identity within this grant, not other grants. */
internal data class MembershipExclusion(val rootId: String, val directory: MembershipDocument)
internal enum class IndependentMembership { PRESENT, ABSENT_VERIFIED, UNKNOWN }
internal data class MembershipEvidence(
    val document: MembershipDocument?,
    // False for legacy or name/size-deduplicated rows until the association is actually verified.
    val documentAssociationVerified: Boolean,
    // Covers unknown historical or non-scan ownership, not just the current document identity.
    val ownershipProvenanceComplete: Boolean,
    val independent: IndependentMembership,
    val configuredRoots: List<MembershipRoot>,
    val rootInventoryComplete: Boolean,
    val observations: List<MembershipObservation>,
    val observationUsable: Boolean,
    val noUnsafeScanOrWrites: Boolean,
    val exclusions: Set<MembershipExclusion>
)

internal enum class MembershipDecision {
    RETAIN_SHARED, RETAIN_INDEPENDENT, RETAIN_UNKNOWN, ELIGIBLE_TO_HIDE_REVERSIBLY
}

internal object BookMembershipDecision {
    private fun valid(document: MembershipDocument): Boolean =
        document.authority.isNotBlank() && document.documentId.isNotEmpty()

    private fun validPath(path: List<MembershipDocument>, root: MembershipRoot): Boolean =
        path.isNotEmpty() && path.first() == root.document &&
            path.all { valid(it) && it.authority == root.document.authority } &&
            path.toSet().size == path.size

    private fun excluded(route: MembershipRoute, exclusion: MembershipExclusion): Boolean =
        route.rootId == exclusion.rootId && exclusion.directory in route.directories

    // Reject route sets that omit paths implied by their own verified directory edges.
    // This checks internal consistency, not whether the provider supplied every edge.
    private fun routesConsistent(root: MembershipRoot, routes: List<MembershipRoute>, file: MembershipDocument): Boolean {
        if (routes.size > 2048 || routes.sumOf { it.directories.size.toLong() } > 20000L) return false
        val edges = mutableMapOf<MembershipDocument, MutableSet<MembershipDocument>>()
        for (route in routes) {
            for ((parent, child) in route.directories.zipWithNext()) {
                edges.getOrPut(parent) { mutableSetOf() }.add(child)
            }
            edges.getOrPut(route.directories.last()) { mutableSetOf() }.add(file)
        }
        val observed = routes.map { it.directories }.toSet()
        val derived = mutableSetOf<List<MembershipDocument>>()
        val stack = ArrayDeque<List<MembershipDocument>>()
        stack.add(listOf(root.document))
        var visitedPaths = 0
        while (stack.isNotEmpty()) {
            if (++visitedPaths > 20000) return false
            val path = stack.removeLast()
            for (child in edges[path.last()].orEmpty()) {
                if (child == file) {
                    derived.add(path)
                    if (derived.size > 2048) return false
                } else {
                    if (child in path) return false
                    stack.add(path + child)
                }
            }
        }
        return observed == derived
    }

    fun decide(evidence: MembershipEvidence): MembershipDecision {
        // A known manual/import owner is enough to retain even when other evidence is incomplete.
        if (evidence.independent == IndependentMembership.PRESENT) return MembershipDecision.RETAIN_INDEPENDENT
        if (evidence.independent != IndependentMembership.ABSENT_VERIFIED ||
            !evidence.documentAssociationVerified || !evidence.ownershipProvenanceComplete ||
            !evidence.rootInventoryComplete ||
            !evidence.observationUsable || !evidence.noUnsafeScanOrWrites
        ) return MembershipDecision.RETAIN_UNKNOWN
        val document = evidence.document ?: return MembershipDecision.RETAIN_UNKNOWN
        if (!valid(document) || evidence.configuredRoots.isEmpty()) return MembershipDecision.RETAIN_UNKNOWN
        val roots = evidence.configuredRoots.associateBy { it.id }
        if (roots.size != evidence.configuredRoots.size ||
            evidence.configuredRoots.any { it.id.isBlank() || !valid(it.document) || it.document == document }
        ) return MembershipDecision.RETAIN_UNKNOWN
        val observations = evidence.observations.associateBy { it.rootId }
        if (observations.size != evidence.observations.size || observations.keys != roots.keys ||
            evidence.observations.any { !it.coverageComplete }
        ) return MembershipDecision.RETAIN_UNKNOWN

        val routes = mutableListOf<MembershipRoute>()
        for (observation in evidence.observations) {
            val root = roots.getValue(observation.rootId)
            for (route in observation.routes) {
                if (route.rootId != root.id || route.file != document || !route.edgesVerified ||
                    !validPath(route.directories, root) || document.authority != root.document.authority ||
                    document in route.directories
                ) return MembershipDecision.RETAIN_UNKNOWN
                routes.add(route)
            }
            if (!routesConsistent(root, observation.routes, document)) return MembershipDecision.RETAIN_UNKNOWN
        }
        // Duplicate sightings are not separate ownership routes.
        if (routes.size != routes.toSet().size || routes.isEmpty() || evidence.exclusions.isEmpty()) {
            return MembershipDecision.RETAIN_UNKNOWN
        }
        for (exclusion in evidence.exclusions) {
            val root = roots[exclusion.rootId] ?: return MembershipDecision.RETAIN_UNKNOWN
            if (!valid(exclusion.directory) || exclusion.directory.authority != root.document.authority ||
                exclusion.directory == document
            ) return MembershipDecision.RETAIN_UNKNOWN
        }
        val affected = routes.filter { route -> evidence.exclusions.any { excluded(route, it) } }
        if (affected.isEmpty()) return MembershipDecision.RETAIN_UNKNOWN
        return if (affected.size < routes.size) MembershipDecision.RETAIN_SHARED
        else MembershipDecision.ELIGIBLE_TO_HIDE_REVERSIBLY
    }
}
