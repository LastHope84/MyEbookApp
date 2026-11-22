package com.example.myebookapp

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room coverage for exclusion only. It does not test a SAF provider or removal. */
@RunWith(AndroidJUnit4::class)
class FolderRemovalDatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: BookDao
    private val root = "content://provider/tree/root"
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        dao = db.bookDao()
    }
    @After fun close() = db.close()
    private fun book(n: String) = BookEntity(uriString = "content://provider/tree/root/document/$n", fileName = "$n.epub", format = "EPUB", fileSize = 100,
        lastModified = 0, title = n, author = "Unknown", publishingDate = "Unknown", publisher = "Unknown", language = "Unknown", coverPath = null,
        lastLocatorJson = null, progressPercent = 0.0, lastOpenedAt = 0, metadataExtracted = false)
    private fun item(n: String) = ScannedItem(ScanSighting(root, "folder", n, false), book(n), listOf("root", "folder"))

    @Test fun exclusionRetainsExistingRowAndBlocksFutureBatch() = runBlocking {
        assertEquals(1, dao.insertScannedBatch(listOf(item("one")), emptySet(), "provider").inserted)
        val existing = requireNotNull(dao.getByUri(book("one").uriString))
        dao.commitRootExclusion(ScanRootExclusion(root, "folder"))
        assertEquals(existing, dao.getByIdOnce(existing.id))
        assertNotNull(dao.getScanBookOrigin(existing.id))
        assertEquals(0, dao.insertScannedBatch(listOf(item("two")), emptySet(), "provider").inserted)
        assertNull(dao.getByUri(book("two").uriString))
    }

    @Test fun checkedExclusionRejectsStaleBatchesAndRestoresWithoutDeletingBooks() = runBlocking {
        val roots = listOf(root)
        dao.initializeAndCheckConfig(roots)
        assertEquals(1, dao.insertScannedBatch(listOf(item("kept")), emptySet(), "provider", scanGeneration = 0L).inserted)
        dao.commitCheckedRootExclusion(ScanRootExclusion(root, "folder"), roots, 0L)
        try {
            dao.insertScannedBatch(listOf(item("stale")), emptySet(), "provider", scanGeneration = 0L)
            fail("stale batch accepted")
        } catch (_: IllegalStateException) { }
        val gen = requireNotNull(dao.inventoryState()).generation
        assertEquals(0, dao.insertScannedBatch(listOf(item("blocked")), emptySet(), "provider", scanGeneration = gen).inserted)
        assertEquals(1, dao.restoreCheckedRootExclusion(ScanRootExclusion(root, "folder"), roots, gen))
        val restoredGen = requireNotNull(dao.inventoryState()).generation
        assertEquals(1, dao.insertScannedBatch(listOf(item("restored")), emptySet(), "provider", scanGeneration = restoredGen).inserted)
        assertNotNull(dao.getByUri(book("kept").uriString))
    }

    @Test fun exclusionPreservesFavoriteAndShelf() = runBlocking {
        assertEquals(1, dao.insertScannedBatch(listOf(item("kept")), emptySet(), "provider").inserted)
        val row = requireNotNull(dao.getByUri(book("kept").uriString))
        dao.setFavorite(row.id, true)
        val shelfId = dao.createCustomShelf(CustomShelf(name = "Keep"))
        dao.addBookToCustomShelf(CustomShelfBook(shelfId, row.id))
        dao.commitRootExclusion(ScanRootExclusion(root, "folder"))
        assertTrue(requireNotNull(dao.getByIdOnce(row.id)).isFavorite)
        assertEquals(1, dao.shelfCountForRemoval(row.id))
    }
}
