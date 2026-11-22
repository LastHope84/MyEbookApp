package com.example.myebookapp

import org.junit.Assert.assertEquals
import org.junit.Test

class RootRemovalCandidateDecisionTest {
    private fun doc(id: String, authority: String = "provider") = MembershipDocument(authority, id)
    private val file = doc("book")
    private val root = MembershipRoot("removed-grant", doc("root"))
    private val other = MembershipRoot("other-grant", doc("other-root"))
    private val route = MembershipRoute(root.id, listOf(root.document, doc("folder")), file, true)
    private fun base() = RootRemovalCandidateEvidence(
        bookId = 7,
        removedRootId = root.id,
        membership = MembershipEvidence(
            document = file,
            documentAssociationVerified = true,
            ownershipProvenanceComplete = true,
            independent = IndependentMembership.ABSENT_VERIFIED,
            configuredRoots = listOf(root, other),
            rootInventoryComplete = true,
            observations = listOf(
                MembershipObservation(root.id, true, listOf(route)),
                MembershipObservation(other.id, true, emptyList())
            ),
            observationUsable = true,
            noUnsafeScanOrWrites = true,
            exclusions = emptySet()
        ),
        currentReadGrants = mapOf(root.id to true, other.id to true),
        uniqueDocumentAssociationVerified = true
    )
    private fun decide(e: RootRemovalCandidateEvidence) = RootRemovalCandidateDecision.decide(e)
    private fun unknown(vararg cases: RootRemovalCandidateEvidence) = cases.forEach {
        assertEquals(RootRemovalCandidateResult.RETAIN_UNKNOWN, decide(it))
    }

    @Test fun `exclusive complete observed membership nominates reversible visibility only`() {
        assertEquals(RootRemovalCandidateResult.CANDIDATE_FOR_REVERSIBLE_VISIBILITY, decide(base()))
    }

    @Test fun `other grant remains shared even if previously excluded`() {
        val e = base()
        val second = MembershipRoute(other.id, listOf(other.document), file, true)
        val m = e.membership.copy(
            observations = listOf(e.membership.observations[0], MembershipObservation(other.id, true, listOf(second))),
            exclusions = setOf(MembershipExclusion(other.id, other.document))
        )
        assertEquals(RootRemovalCandidateResult.RETAIN_SHARED, decide(e.copy(membership = m)))
    }

    @Test fun `different grant to same root is still shared`() {
        val e = base()
        val overlap = other.copy(document = root.document)
        val m = e.membership.copy(configuredRoots = listOf(root, overlap), observations = listOf(
            e.membership.observations[0], MembershipObservation(overlap.id, true, listOf(route.copy(rootId = overlap.id)))
        ))
        assertEquals(RootRemovalCandidateResult.RETAIN_SHARED, decide(e.copy(membership = m)))
    }

    @Test fun `multiple routes inside removed root can be candidates`() {
        val e = base()
        val alternate = route.copy(directories = listOf(root.document, doc("another")))
        val m = e.membership.copy(observations = listOf(
            MembershipObservation(root.id, true, listOf(route, alternate)), e.membership.observations[1]
        ))
        assertEquals(RootRemovalCandidateResult.CANDIDATE_FOR_REVERSIBLE_VISIBILITY, decide(e.copy(membership = m)))
    }

    @Test fun `manual owner retains even if evidence incomplete`() {
        val e = base()
        assertEquals(RootRemovalCandidateResult.RETAIN_INDEPENDENT, decide(e.copy(
            currentReadGrants = emptyMap(), membership = e.membership.copy(independent = IndependentMembership.PRESENT)
        )))
    }

    @Test fun `unknown ownership or identity cannot nominate`() {
        val e = base(); val m = e.membership
        unknown(e.copy(bookId = 0), e.copy(bookId = -1), e.copy(uniqueDocumentAssociationVerified = false),
            e.copy(membership = m.copy(document = null)),
            e.copy(membership = m.copy(document = doc(""))),
            e.copy(membership = m.copy(documentAssociationVerified = false)),
            e.copy(membership = m.copy(ownershipProvenanceComplete = false)),
            e.copy(membership = m.copy(independent = IndependentMembership.UNKNOWN)),
            e.copy(membership = m.copy(observations = listOf(
                MembershipObservation(root.id, true, listOf(route.copy(file = doc("different-id")))),
                m.observations[1]))))
    }

    @Test fun `missing invalid and extra grants or root selection cannot nominate`() {
        val e = base()
        unknown(e.copy(removedRootId = ""), e.copy(removedRootId = "missing"),
            e.copy(currentReadGrants = emptyMap()), e.copy(currentReadGrants = mapOf(root.id to true)),
            e.copy(currentReadGrants = mapOf(root.id to true, other.id to false)),
            e.copy(currentReadGrants = e.currentReadGrants + ("extra" to true)),
            e.copy(membership = e.membership.copy(configuredRoots = listOf(root, root))))
    }

    @Test fun `incomplete or stale observation and unsafe writes fail closed`() {
        val e = base(); val m = e.membership
        unknown(e.copy(membership = m.copy(rootInventoryComplete = false)),
            e.copy(membership = m.copy(observationUsable = false)),
            e.copy(membership = m.copy(noUnsafeScanOrWrites = false)),
            e.copy(membership = m.copy(observations = listOf(m.observations[0]))),
            e.copy(membership = m.copy(observations = listOf(m.observations[0], m.observations[1].copy(coverageComplete = false)))),
            e.copy(membership = m.copy(observations = listOf(
                MembershipObservation(root.id, true, emptyList()), m.observations[1]))))
    }

    @Test fun `unverified edges and missing implied multiparent route fail closed`() {
        val e = base(); val m = e.membership
        val bad = route.copy(edgesVerified = false)
        val a = doc("a"); val b = doc("b"); val common = doc("common"); val child = doc("child")
        val left = route.copy(directories = listOf(root.document, a, common))
        val right = route.copy(directories = listOf(root.document, b, common, child))
        unknown(e.copy(membership = m.copy(observations = listOf(
            MembershipObservation(root.id, true, listOf(bad)), m.observations[1]))),
            e.copy(membership = m.copy(observations = listOf(
                MembershipObservation(root.id, true, listOf(left, right)), m.observations[1]))))
    }
}
