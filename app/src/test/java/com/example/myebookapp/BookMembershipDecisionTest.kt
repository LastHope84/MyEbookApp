package com.example.myebookapp

import org.junit.Assert.assertEquals
import org.junit.Test

class BookMembershipDecisionTest {
    private fun d(id: String, authority: String = "provider") = MembershipDocument(authority, id)
    private val file = d("book-id")
    private val root = MembershipRoot("outer-grant", d("root"))
    private val selected = d("selected")
    private val other = d("other")
    private val selectedRoute = MembershipRoute(root.id, listOf(root.document, selected), file, true)
    private val otherRoute = MembershipRoute(root.id, listOf(root.document, other), file, true)
    private val selectedExclusion = MembershipExclusion(root.id, selected)
    private fun evidence() = MembershipEvidence(
        document = file,
        documentAssociationVerified = true,
        ownershipProvenanceComplete = true,
        independent = IndependentMembership.ABSENT_VERIFIED,
        configuredRoots = listOf(root),
        rootInventoryComplete = true,
        observations = listOf(MembershipObservation(root.id, true, listOf(selectedRoute))),
        observationUsable = true,
        noUnsafeScanOrWrites = true,
        exclusions = setOf(selectedExclusion)
    )
    private fun decision(e: MembershipEvidence) = BookMembershipDecision.decide(e)
    private fun unknown(vararg cases: MembershipEvidence) {
        cases.forEach { assertEquals(MembershipDecision.RETAIN_UNKNOWN, decision(it)) }
    }

    @Test fun `fresh complete sole route is eligible only for reversible hiding`() {
        assertEquals(MembershipDecision.ELIGIBLE_TO_HIDE_REVERSIBLY, decision(evidence()))
    }

    @Test fun `same provider identity seen through separate grants is shared`() {
        val inner = MembershipRoot("inner-grant", selected)
        val innerRoute = MembershipRoute(inner.id, listOf(selected), file, true)
        val both = evidence().copy(
            configuredRoots = listOf(root, inner),
            observations = evidence().observations + MembershipObservation(inner.id, true, listOf(innerRoute))
        )
        assertEquals(MembershipDecision.RETAIN_SHARED, decision(both))
        assertEquals(MembershipDecision.ELIGIBLE_TO_HIDE_REVERSIBLY, decision(both.copy(
            exclusions = both.exclusions + MembershipExclusion(inner.id, selected)
        )))
    }

    @Test fun `another parent in same grant survives selected path exclusion`() {
        val e = evidence().copy(observations = listOf(MembershipObservation(root.id, true, listOf(selectedRoute, otherRoute))))
        assertEquals(MembershipDecision.RETAIN_SHARED, decision(e))
        assertEquals(MembershipDecision.ELIGIBLE_TO_HIDE_REVERSIBLY, decision(e.copy(
            exclusions = setOf(MembershipExclusion(root.id, root.document))
        )))
    }

    @Test fun `directory identity is exact and no matching route is unknown`() {
        val nested = MembershipRoute(root.id, listOf(root.document, selected, d("nested")), file, true)
        val sibling = MembershipRoute(root.id, listOf(root.document, d("selected-suffix")), file, true)
        val e = evidence().copy(observations = listOf(MembershipObservation(root.id, true, listOf(nested, sibling))))
        assertEquals(MembershipDecision.RETAIN_SHARED, decision(e))
        val tooDeep = MembershipExclusion(root.id, d("nonexistent"))
        unknown(e.copy(exclusions = setOf(tooDeep)))
    }

    @Test fun `multiparent directory exclusion applies through every parent in one grant`() {
        val a = d("a"); val b = d("b"); val common = d("common")
        val left = MembershipRoute(root.id, listOf(root.document, a, common), file, true)
        val right = MembershipRoute(root.id, listOf(root.document, b, common), file, true)
        val e = evidence().copy(
            observations = listOf(MembershipObservation(root.id, true, listOf(left, right))),
            exclusions = setOf(MembershipExclusion(root.id, common))
        )
        assertEquals(MembershipDecision.ELIGIBLE_TO_HIDE_REVERSIBLY, decision(e))
        // Excluding one distinct parent leaves the route through the other parent.
        assertEquals(MembershipDecision.RETAIN_SHARED,
            decision(e.copy(exclusions = setOf(MembershipExclusion(root.id, a)))))
        // Identical directory under another grant remains an independent surviving route.
        val second = MembershipRoot("second", root.document)
        val secondRoute = left.copy(rootId = second.id)
        val crossGrant = e.copy(configuredRoots = listOf(root, second), observations = e.observations +
            MembershipObservation(second.id, true, listOf(secondRoute)))
        assertEquals(MembershipDecision.RETAIN_SHARED, decision(crossGrant))
        assertEquals(MembershipDecision.ELIGIBLE_TO_HIDE_REVERSIBLY,
            decision(crossGrant.copy(exclusions = e.exclusions + MembershipExclusion(second.id, common))))
    }

    @Test fun `malformed exclusions and file identity fail closed even with a matching exclusion`() {
        val e = evidence()
        val invalid = listOf(
            MembershipExclusion("", selected),
            MembershipExclusion("unknown-grant", selected),
            MembershipExclusion(root.id, d("", "provider")),
            MembershipExclusion(root.id, d("selected", "")),
            MembershipExclusion(root.id, d("selected", "other-provider")),
            MembershipExclusion(root.id, file)
        )
        invalid.forEach { unknown(e.copy(exclusions = e.exclusions + it)) }
        // A well-formed but unrelated identity cannot authorize hiding.
        unknown(e.copy(exclusions = setOf(MembershipExclusion(root.id, d("not-present")))))
    }

    @Test fun `manual or other independent ownership always retains`() {
        val e = evidence().copy(independent = IndependentMembership.PRESENT, observationUsable = false)
        assertEquals(MembershipDecision.RETAIN_INDEPENDENT, decision(e))
    }

    @Test fun `old rows and ambiguous ownership remain unknown`() {
        val e = evidence()
        unknown(e.copy(document = null), e.copy(documentAssociationVerified = false),
            e.copy(ownershipProvenanceComplete = false),
            e.copy(independent = IndependentMembership.UNKNOWN),
            e.copy(observations = listOf(MembershipObservation(root.id, true, emptyList()))))
    }

    @Test fun `filename and size collision cannot stand in for provider identity`() {
        // Names and sizes do not exist in the decision API: different IDs (or authorities)
        // cannot match even if the collector reports the same filename and byte count.
        val e = evidence()
        val wrongId = selectedRoute.copy(file = d("different-document-same-name-and-size"))
        val wrongAuthority = selectedRoute.copy(file = d("book-id", "other-provider"))
        unknown(e.copy(observations = listOf(MembershipObservation(root.id, true, listOf(wrongId)))),
            e.copy(observations = listOf(MembershipObservation(root.id, true, listOf(wrongAuthority)))))
    }

    @Test fun `missing access incomplete inventory stale observation and unsafe scan fail closed`() {
        val e = evidence()
        unknown(e.copy(rootInventoryComplete = false), e.copy(observations = emptyList()),
            e.copy(observations = listOf(MembershipObservation(root.id, false, emptyList()))),
            e.copy(observationUsable = false), e.copy(noUnsafeScanOrWrites = false),
            e.copy(configuredRoots = emptyList(), observations = emptyList()))
        val inaccessible = MembershipRoot("inaccessible", d("another-root"))
        unknown(e.copy(configuredRoots = listOf(root, inaccessible)),
            e.copy(configuredRoots = listOf(root, inaccessible),
                observations = e.observations + MembershipObservation(inaccessible.id, false, emptyList())))
    }

    @Test fun `malformed inconsistent and unverified routes fail closed`() {
        val e = evidence()
        fun withRoute(route: MembershipRoute) = e.copy(observations = listOf(MembershipObservation(root.id, true, listOf(route))))
        unknown(withRoute(selectedRoute.copy(edgesVerified = false)),
            withRoute(selectedRoute.copy(rootId = "wrong")),
            withRoute(selectedRoute.copy(directories = listOf(selected))),
            withRoute(selectedRoute.copy(directories = listOf(root.document, selected, selected))),
            withRoute(selectedRoute.copy(directories = listOf(root.document, file))),
            e.copy(observations = listOf(MembershipObservation(root.id, true, listOf(selectedRoute, selectedRoute)))),
            e.copy(observations = e.observations + e.observations),
            e.copy(configuredRoots = listOf(root, root)),
            e.copy(exclusions = setOf(MembershipExclusion("unknown-grant", root.document))),
            e.copy(exclusions = emptySet()))
    }

    @Test fun `missing route implied by shared parent is unknown`() {
        val a = d("a"); val b = d("b"); val x = d("x"); val y = d("y")
        val left = MembershipRoute(root.id, listOf(root.document, a, x), file, true)
        val right = MembershipRoute(root.id, listOf(root.document, b, x, y), file, true)
        val e = evidence().copy(
            observations = listOf(MembershipObservation(root.id, true, listOf(left, right))),
            exclusions = setOf(MembershipExclusion(root.id, a),
                MembershipExclusion(root.id, y))
        )
        unknown(e)
        val implied = MembershipRoute(root.id, listOf(root.document, b, x), file, true)
        val alsoImplied = MembershipRoute(root.id, listOf(root.document, a, x, y), file, true)
        val complete = e.copy(observations = listOf(MembershipObservation(root.id, true,
            listOf(left, right, implied, alsoImplied))))
        assertEquals(MembershipDecision.RETAIN_SHARED, decision(complete))
    }

    @Test fun `a document cannot also be a configured directory root`() {
        val second = MembershipRoot("file-as-root", file)
        val e = evidence().copy(configuredRoots = listOf(root, second),
            observations = evidence().observations + MembershipObservation(second.id, true, emptyList()))
        unknown(e)
    }

    @Test fun `two grants to same root remain distinct even if one has no access`() {
        val another = MembershipRoot("second-grant", root.document)
        val e = evidence().copy(configuredRoots = listOf(root, another), observations = evidence().observations +
            MembershipObservation(another.id, false, emptyList()))
        unknown(e)
        val route = selectedRoute.copy(rootId = another.id)
        val completed = e.copy(observations = evidence().observations + MembershipObservation(another.id, true, listOf(route)))
        assertEquals(MembershipDecision.RETAIN_SHARED, decision(completed))
    }
}
