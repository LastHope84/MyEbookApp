package com.example.myebookapp

import org.junit.Assert.*
import org.junit.Test

class ScanRouteOwnershipTest {
    private fun d(id: String) = RouteDocument("provider", id)
    private fun dir(id: String) = RouteChild(d(id), RouteNodeKind.DIRECTORY)
    private fun file(id: String) = RouteChild(d(id), RouteNodeKind.FILE)
    private fun listing(vararg c: RouteChild) = RouteListing(true, c.toList())
    // The same file has two paths in one grant and another path in an overlapping grant.
    private val outer = RouteRoot("outer-grant", d("root"), mapOf(
        d("root") to listing(dir("selected"), dir("other")),
        d("selected") to listing(file("book")),
        d("other") to listing(file("book"))))
    private val inner = RouteRoot("inner-grant", d("selected"), mapOf(
        d("selected") to listing(file("book"))))
    private val graph = RouteGraph(true, listOf(outer, inner))
    private val a = ScanRoute("outer-grant", listOf(d("root"), d("selected")), d("book"))
    private val b = ScanRoute("outer-grant", listOf(d("root"), d("other")), d("book"))
    private val c = ScanRoute("inner-grant", listOf(d("selected")), d("book"))
    private val book = RouteBook(1, d("book"), listOf(a, b, c), true, false, false)
    private val excludeSelected = RouteExclusion("outer-grant", d("selected"))
    private fun input(books: List<RouteBook> = listOf(book), exclusions: Set<RouteExclusion> = setOf(excludeSelected), g: RouteGraph = graph) =
        RouteOwnershipInput(g, true, books, exclusions)
    private fun decision(i: RouteOwnershipInput = input()) = ScanRouteOwnership.assess(i).single()
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `root scoped exclusion preserves other routes`() {
        val result = decision()
        assertEquals(setOf(a), result.excludedRoutes)
        assertEquals(setOf(b, c), result.survivingRoutes)
        assertEquals(RouteRetention.KEEP_SCANNED_ROUTE, result.retention)
    }
    @Test fun `removing all scanned routes is diagnostic only`() {
        val ex = setOf(RouteExclusion("outer-grant", d("root")), RouteExclusion("inner-grant", d("selected")))
        assertEquals(RouteRetention.NO_REMAINING_SCANNED_ROUTE, decision(input(exclusions = ex)).retention)
        assertEquals(RouteRetention.KEEP_INDEPENDENT, decision(input(listOf(book.copy(hasIndependentOwner = true)), ex)).retention)
        assertEquals(RouteRetention.KEEP_UNKNOWN, decision(input(listOf(book.copy(hasUnknownLegacyOwner = true)), ex)).retention)
    }
    @Test fun `legacy and unobserved books are retained`() {
        val legacy = RouteBook(2, null, emptyList(), true, false, true)
        val outside = RouteBook(3, d("unobserved"), emptyList(), true, false, false)
        assertEquals(RouteRetention.KEEP_UNKNOWN, decision(input(listOf(legacy))).retention)
        assertEquals(RouteRetention.KEEP_NO_PROVEN_ROUTE, decision(input(listOf(outside))).retention)
    }
    @Test fun `transaction order exclusion then stale scan recheck cannot resurrect path`() {
        val before = emptySet<RouteExclusion>()
        assertTrue(ScanRouteOwnership.mayRecordRoute(graph, before, a))
        // Scan discovered 'a' before exclusion, but its write must recheck after commit.
        val after = setOf(excludeSelected)
        assertFalse(ScanRouteOwnership.mayRecordRoute(graph, after, a))
        assertTrue(ScanRouteOwnership.mayRecordRoute(graph, after, b))
        assertTrue(ScanRouteOwnership.mayRecordRoute(graph, after, c))
    }
    @Test fun `transaction order scan then exclusion preserves only other paths`() {
        assertTrue(ScanRouteOwnership.mayRecordRoute(graph, emptySet(), a))
        // A committed sighting must be reconciled when the exclusion commits.
        assertEquals(setOf(a), decision().excludedRoutes)
        assertEquals(setOf(b, c), decision().survivingRoutes)
    }
    @Test fun `all graph and ownership inventories must be complete`() {
        rejected { decision(input(g = graph.copy(rootsComplete = false))) }
        rejected { decision(input().copy(booksComplete = false)) }
        rejected { decision(input(listOf(book.copy(routesComplete = false)))) }
        rejected { decision(input(listOf(book.copy(routes = listOf(a, c))))) }
        rejected { decision(input(g = graph.copy(roots = listOf(outer.copy(directories = outer.directories - d("other")), inner)))) }
        rejected { decision(input(g = graph.copy(roots = listOf(outer.copy(directories = outer.directories + (d("other") to RouteListing(false, emptyList()))), inner)))) }
    }
    @Test fun `ambiguous identities and unproven paths are rejected`() {
        rejected { decision(input(listOf(book, book.copy(bookId = 2)))) }
        rejected { decision(input(listOf(book, book.copy(document = d("other-book"), routes = emptyList())))) }
        rejected { decision(input(listOf(book.copy(document = d("root"), routes = emptyList())))) }
        rejected { decision(input(listOf(book.copy(routes = listOf(a, a, b, c))))) }
        rejected { decision(input(listOf(book.copy(routes = listOf(a.copy(directories = listOf(d("selected"))), b, c))))) }
        rejected { decision(input(listOf(RouteBook(2, null, emptyList(), true, false, false)))) }
        rejected { ScanRouteOwnership.mayRecordRoute(graph, emptySet(), a.copy(file = d("missing"))) }
        rejected { decision(input(exclusions = setOf(RouteExclusion("wrong-root", d("selected"))))) }
        rejected { decision(input(exclusions = setOf(RouteExclusion("outer-grant", d("absent"))))) }
    }
    @Test fun `cycles duplicate children inconsistent listings and cross provider fail closed`() {
        val bad = listOf(
            outer.copy(directories = outer.directories + (d("other") to listing(dir("root")))),
            outer.copy(directories = outer.directories + (d("other") to listing(file("book"), file("book")))),
            outer.copy(directories = outer.directories + (d("other") to listing(RouteChild(RouteDocument("alien", "x"), RouteNodeKind.FILE)))),
            outer.copy(directories = outer.directories + (d("unused") to listing())),
            outer.copy(directories = outer.directories + (d("selected") to listing()))
        )
        for (r in bad) rejected { decision(input(g = RouteGraph(true, listOf(r, inner)))) }
        rejected { decision(input(g = RouteGraph(true, listOf(outer, outer)))) }
    }
    @Test fun `twenty five thousand simple files have complete route decisions`() {
        val children = (0 until 25_000).map { file("file-$it") }
        val root = RouteRoot("large", d("root"), mapOf(d("root") to RouteListing(true, children)))
        val entries = (0 until 25_000).map {
            val identity = d("file-$it")
            RouteBook(it.toLong(), identity, listOf(ScanRoute("large", listOf(d("root")), identity)), true, false, false)
        }
        val result = ScanRouteOwnership.assess(RouteOwnershipInput(RouteGraph(true, listOf(root)), true, entries,
            setOf(RouteExclusion("large", d("root")))))
        assertEquals(25_000, result.size)
        assertTrue(result.all { it.retention == RouteRetention.NO_REMAINING_SCANNED_ROUTE })
    }
    @Test fun `opaque ids never act as prefixes`() {
        val r = RouteRoot("grant", d("a"), mapOf(d("a") to listing(dir("a-b"), file("a-b-book")), d("a-b") to listing()))
        val route = ScanRoute("grant", listOf(d("a")), d("a-b-book"))
        val g = RouteGraph(true, listOf(r))
        assertTrue(ScanRouteOwnership.mayRecordRoute(g, setOf(RouteExclusion("grant", d("a-b"))), route))
    }
}
