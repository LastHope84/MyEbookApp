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

/** Tests the actual Room DAO against Android SQLite, not a scanner simulation. */
@RunWith(AndroidJUnit4::class)
class ScannedBatchDatabaseTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: BookDao
    private val root = "content://provider/tree/root"
    private val authority = "provider"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).build()
        dao = db.bookDao()
    }
    @After fun tearDown() { db.close() }

    private fun book(uri: String, name: String, size: Long) = BookEntity(
        uriString = uri, fileName = name, format = "EPUB", fileSize = size, lastModified = 0,
        title = name, author = "", publishingDate = "", publisher = "", language = "",
        coverPath = null, lastLocatorJson = null, progressPercent = 0.0, lastOpenedAt = 0,
        metadataExtracted = false
    )
    private fun item(id: String, book: BookEntity?, path: List<String> = listOf("root"), directory: Boolean = false) =
        ScannedItem(ScanSighting(root, path.last(), id, directory), book, path)

    @Test fun ignoredUriDoesNotReserveChangedKeyInSameOrNextBatch() = runBlocking {
        val existing = book("content://provider/doc/existing", "old.epub", 10)
        assertTrue(dao.insertIgnore(existing) > 0)
        val known = dao.getAllNameSizeKeys().toMutableSet()
        val conflicting = item("existing", existing.copy(fileName = "NEW.epub", fileSize = 20))
        val different = item("different", book("content://provider/doc/different", "new.epub", 20))
        val result = dao.insertScannedBatch(listOf(conflicting, different), known, authority)
        assertEquals(1, result.inserted)
        assertTrue(result.acceptedKeys.isEmpty())
        assertEquals("old.epub", dao.getByUri(existing.uriString)?.fileName)
        val inserted = requireNotNull(dao.getByUri(different.book!!.uriString))
        // This fixture uses a non-tree URI, so it cannot establish scan provenance.
        assertNull(dao.getScanBookOrigin(inserted.id))
        assertNull(dao.getScanBookOrigin(requireNotNull(dao.getByUri(existing.uriString)).id))
        assertEquals(2, dao.getScanSightings(root, "root").size)

        // An ignored URI alone must not contaminate the caller's next-batch keys.
        val ignored = dao.insertScannedBatch(listOf(item("existing", existing.copy(fileName = "later.epub", fileSize = 30))), known, authority)
        assertEquals(0, ignored.inserted)
        assertTrue(ignored.acceptedKeys.isEmpty())
        known.addAll(ignored.acceptedKeys)
        val next = item("next", book("content://provider/doc/next", "later.epub", 30))
        assertEquals(1, dao.insertScannedBatch(listOf(next), known, authority).inserted)
    }

    @Test fun filenameSizeNeverSuppressesDistinctUris() = runBlocking {
        val existing = book("content://provider/doc/old", "Known.epub", 11)
        dao.insertIgnore(existing)
        val known = dao.getAllNameSizeKeys().toSet()
        val items = listOf(
            item("known", book("content://provider/doc/known", "known.epub", 11)),
            item("first", book("content://provider/doc/first", "Fresh.epub", 12)),
            item("second", book("content://provider/doc/second", "fresh.epub", 12)),
            item("zero1", book("content://provider/doc/zero1", "zero.epub", 0)),
            item("zero2", book("content://provider/doc/zero2", "zero.epub", 0)),
            item("negative", book("content://provider/doc/negative", "negative.epub", -1))
        )
        val result = dao.insertScannedBatch(items, known, authority)
        assertEquals(6, result.inserted)
        assertTrue(result.acceptedKeys.isEmpty())
        assertNotNull(dao.getByUri(items[0].book!!.uriString))
        assertNotNull(dao.getByUri(items[2].book!!.uriString))
        assertEquals(7, dao.countAllBooks())
        assertEquals(6, dao.getScanSightings(root, "root").size)
    }

    @Test fun scanCommittedBeforeExclusionRemainsButLaterWritesAreBlocked() = runBlocking {
        val before = item("before", book("content://provider/doc/before", "before.epub", 1), listOf("root", "folder"))
        assertEquals(1, dao.insertScannedBatch(listOf(before), emptySet(), authority).inserted)
        dao.commitRootExclusion(ScanRootExclusion(root, "folder"))
        val after = item("after", book("content://provider/doc/after", "after.epub", 2), listOf("root", "folder"))
        val result = dao.insertScannedBatch(listOf(after), emptySet(), authority)
        assertEquals(0, result.inserted)
        assertTrue(result.acceptedKeys.isEmpty())
        assertNotNull(dao.getByUri(before.book!!.uriString))
        assertNull(dao.getByUri(after.book!!.uriString))
        assertEquals(listOf("before"), dao.getScanSightings(root, "folder").map { it.documentId })
    }

    @Test fun exclusionsFilterBeforeSightingsAndKeyReservation() = runBlocking {
        dao.commitRootExclusion(ScanRootExclusion(root, "root-blocked"))
        dao.commitFolderExclusion(ScanExclusion(authority, "global-blocked"), listOf(root))
        val blockedRoot = item("one", book("content://provider/doc/one", "same.epub", 5), listOf("root", "root-blocked"))
        val blockedGlobal = item("two", book("content://provider/doc/two", "same.epub", 5), listOf("root", "global-blocked"))
        val blockedDirectory = item("global-blocked", null, directory = true)
        val allowed = item("three", book("content://provider/doc/three", "same.epub", 5))
        val result = dao.insertScannedBatch(listOf(blockedRoot, blockedGlobal, blockedDirectory, allowed), emptySet(), authority)
        assertEquals(1, result.inserted)
        assertTrue(result.acceptedKeys.isEmpty())
        assertNull(dao.getByUri(blockedRoot.book!!.uriString))
        assertNull(dao.getByUri(blockedGlobal.book!!.uriString))
        assertNotNull(dao.getByUri(allowed.book!!.uriString))
        assertTrue(dao.getScanSightings(root, "root-blocked").isEmpty())
        assertTrue(dao.getScanSightings(root, "global-blocked").isEmpty())
        assertEquals(listOf("three"), dao.getScanSightings(root, "root").map { it.documentId })
    }
}
