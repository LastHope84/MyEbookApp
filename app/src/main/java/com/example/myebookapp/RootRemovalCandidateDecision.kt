package com.example.myebookapp

/**
 * A diagnostic nomination for reversible app-library visibility, never a deletion instruction.
 * No database, provider, scan, exclusion, or UI is changed here.
 *
 * The collector must attest that the inventory and all routes are current, exhaustive observations
 * across every configured root and grant (including multiparent paths), that each persisted read
 * grant is still valid, and that this book's association and ownership provenance are unambiguous.
 * Historical positive scan routes, name/size matches, and failed or partial listings are not proof.
 * The decision cannot verify those attestations. SAF enumeration is not an atomic provider
 * snapshot; even complete observations can become stale immediately. A future caller must provide
 * freshness/invalidation, scan/write coordination and safe reversible persistence separately.
 */
internal data class RootRemovalCandidateEvidence(
    val bookId: Long,
    val removedRootId: String,
    val membership: MembershipEvidence,
    // Exactly one entry per configured root, true only after verifying its current read grant.
    val currentReadGrants: Map<String, Boolean>,
    // True only if the exact document-to-book association is known to be unique, not deduplicated.
    val uniqueDocumentAssociationVerified: Boolean
)

internal enum class RootRemovalCandidateResult {
    CANDIDATE_FOR_REVERSIBLE_VISIBILITY, RETAIN_SHARED, RETAIN_INDEPENDENT, RETAIN_UNKNOWN
}

internal object RootRemovalCandidateDecision {
    fun decide(input: RootRemovalCandidateEvidence): RootRemovalCandidateResult {
        // Independent ownership is enough to retain even if the rest is unusable.
        if (input.membership.independent == IndependentMembership.PRESENT) {
            return RootRemovalCandidateResult.RETAIN_INDEPENDENT
        }
        val roots = input.membership.configuredRoots
        val rootIds = roots.map { it.id }.toSet()
        if (input.bookId <= 0L || input.removedRootId.isBlank() ||
            !input.uniqueDocumentAssociationVerified ||
            roots.size != rootIds.size || input.removedRootId !in rootIds ||
            input.currentReadGrants.keys != rootIds || input.currentReadGrants.values.any { !it }
        ) return RootRemovalCandidateResult.RETAIN_UNKNOWN

        val removedRoot = roots.single { it.id == input.removedRootId }
        // Assess only the requested root. Existing directory/root exclusions must not accidentally
        // turn membership in a different configured root into an apparently exclusive candidate.
        val onlyRemovedRoot = input.membership.copy(
            exclusions = setOf(MembershipExclusion(removedRoot.id, removedRoot.document))
        )
        return when (BookMembershipDecision.decide(onlyRemovedRoot)) {
            MembershipDecision.ELIGIBLE_TO_HIDE_REVERSIBLY -> RootRemovalCandidateResult.CANDIDATE_FOR_REVERSIBLE_VISIBILITY
            MembershipDecision.RETAIN_SHARED -> RootRemovalCandidateResult.RETAIN_SHARED
            MembershipDecision.RETAIN_INDEPENDENT -> RootRemovalCandidateResult.RETAIN_INDEPENDENT
            MembershipDecision.RETAIN_UNKNOWN -> RootRemovalCandidateResult.RETAIN_UNKNOWN
        }
    }
}
