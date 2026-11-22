package com.example.myebookapp

import org.junit.Assert.*
import org.junit.Test

class FolderRemovalEvidenceTest {
    private fun k(id: String, a: String = "provider") = SafDocumentKey(a, id)
    private fun d(id: String) = SafChild(k(id), SafKind.DIRECTORY)
    private fun f(id: String) = SafChild(k(id), SafKind.FILE)
    private fun listing(vararg children: SafChild) = SafListing(true, children.toList())
    private val selected = SafTreeEvidence(k("opaque:selection"), mapOf(
        k("opaque:selection") to listing(d("no-prefix-child"), f("opaque-file")),
        k("no-prefix-child") to listing(f("nested-file"))))
    private val root = SafTreeEvidence(k("root"), mapOf(
        k("root") to listing(d("opaque:selection"), f("opaque:selection/trick")),
        k("opaque:selection") to listing(d("no-prefix-child"), f("opaque-file")),
        k("no-prefix-child") to listing(f("nested-file"))))
    private fun b(id: Long, doc: String?, verified: Boolean = true) = FolderBookEvidence(id, doc?.let(::k), verified)
    private fun preview(books: List<FolderBookEvidence>, roots: List<SafTreeEvidence> = listOf(root)) =
        FolderRemovalEvidence.preview(selected, roots, books).books

    @Test fun `only exact verified selected documents are candidates`() {
        assertEquals(mapOf(
            1L to FolderPreviewDisposition.CANDIDATE_ONLY,
            2L to FolderPreviewDisposition.CANDIDATE_ONLY,
            3L to FolderPreviewDisposition.KEEP_OUTSIDE,
            4L to FolderPreviewDisposition.KEEP_AMBIGUOUS,
            5L to FolderPreviewDisposition.KEEP_AMBIGUOUS),
            preview(listOf(b(1, "opaque-file"), b(2, "nested-file"),
                b(3, "opaque:selection/trick"), b(4, "opaque-file", false), b(5, null))))
    }

    @Test fun `unknown and directory identities are ambiguous`() {
        val results = FolderRemovalEvidence.preview(selected, listOf(root), listOf(
            b(1, "opaque:selection"), b(2, "missing"),
            FolderBookEvidence(3, k("opaque-file", "other-provider"), true)))
        assertTrue(results.books.values.all { it == FolderPreviewDisposition.KEEP_AMBIGUOUS })
    }

    @Test fun `alternate path through another root preserves shared record`() {
        val aliasRoot = SafTreeEvidence(k("outside"), mapOf(k("outside") to listing(f("opaque-file"))))
        assertEquals(FolderPreviewDisposition.KEEP_SHARED,
            preview(listOf(b(1, "opaque-file")), listOf(root, aliasRoot))[1])
    }

    @Test fun `alternate parent in same root preserves shared record`() {
        val multiparentRoot = root.copy(directories = root.directories +
            (k("root") to listing(d("opaque:selection"), f("opaque-file"))))
        assertEquals(FolderPreviewDisposition.KEEP_SHARED,
            preview(listOf(b(1, "opaque-file")), listOf(multiparentRoot))[1])
    }

    @Test fun `independent root inside selection preserves its surviving path`() {
        val nestedRoot = SafTreeEvidence(k("no-prefix-child"), mapOf(k("no-prefix-child") to listing(f("nested-file"))))
        assertEquals(FolderPreviewDisposition.KEEP_SHARED,
            preview(listOf(b(1, "nested-file")), listOf(root, nestedRoot))[1])
    }

    @Test fun `root below selected folder can establish relation`() {
        val nestedRoot = SafTreeEvidence(k("no-prefix-child"), mapOf(k("no-prefix-child") to listing(f("nested-file"))))
        assertEquals(FolderPreviewDisposition.KEEP_SHARED,
            preview(listOf(b(1, "nested-file")), listOf(nestedRoot))[1])
        assertEquals(FolderPreviewDisposition.KEEP_AMBIGUOUS,
            preview(listOf(b(2, "opaque-file")), listOf(nestedRoot))[2])
    }

    @Test fun `missing or incomplete listings and inconsistent snapshots fail closed`() {
        val badTrees = listOf(
            root.copy(directories = root.directories - k("no-prefix-child")),
            root.copy(directories = root.directories + (k("no-prefix-child") to SafListing(false, emptyList()))),
            root.copy(directories = root.directories + (k("no-prefix-child") to listing())),
            root.copy(directories = root.directories + (k("unused") to listing())),
            root.copy(directories = root.directories + (k("root") to listing(SafChild(k("x", "other"), SafKind.FILE))))
        )
        for (bad in badTrees) {
            try { preview(listOf(b(1, "opaque-file")), listOf(bad)); fail("Bad evidence accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `unrelated selection and conflicting kinds fail closed`() {
        val unrelated = SafTreeEvidence(k("unrelated"), mapOf(k("unrelated") to listing()))
        try { FolderRemovalEvidence.preview(selected, listOf(unrelated), emptyList()); fail() }
        catch (_: IllegalArgumentException) { }
        val conflict = SafTreeEvidence(k("outside"), mapOf(k("outside") to listing(f("no-prefix-child"))))
        try { preview(emptyList(), listOf(root, conflict)); fail() }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun `cycle and duplicate ids are rejected`() {
        val cycle = SafTreeEvidence(k("root"), mapOf(
            k("root") to listing(d("opaque:selection")),
            k("opaque:selection") to listing(d("no-prefix-child"), f("opaque-file")),
            k("no-prefix-child") to listing(f("nested-file"))))
        val selectedCycle = selected.copy(directories = selected.directories + (k("no-prefix-child") to listing(f("nested-file"), d("opaque:selection"))))
        val rootCycle = cycle.copy(directories = cycle.directories + (k("no-prefix-child") to listing(f("nested-file"), d("opaque:selection"))))
        try { FolderRemovalEvidence.preview(selectedCycle, listOf(rootCycle), listOf(b(1, "nested-file"))); fail() }
        catch (_: IllegalArgumentException) { }
        try { preview(listOf(b(1, "opaque-file"), b(1, "nested-file"))); fail() }
        catch (_: IllegalArgumentException) { }
    }
}
