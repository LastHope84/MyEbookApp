package com.example.myebookapp

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises Room's real upgrade path and final schema validation on Android SQLite.
 * No exported historical schemas exist (exportSchema=false), so these v5-v8 fixtures are
 * reconstructed from a freshly generated v9 database by removing later additive tables.
 * They are not independent snapshots of an actual older app installation.
 */
@RunWith(AndroidJUnit4::class)
class FolderMigrationDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "folder_migration_instrumentation_test.db"
    private val root = "content://provider/tree/root"
    private var room: AppDatabase? = null

    @Before fun setUp() { context.deleteDatabase(databaseName) }
    @After fun tearDown() {
        room?.close()
        room = null
        context.deleteDatabase(databaseName)
    }

    private fun builder() = Room.databaseBuilder(context, AppDatabase::class.java, databaseName)
        .addMigrations(AppDatabase.M56, AppDatabase.M67, AppDatabase.M78, AppDatabase.M89, AppDatabase.M910, AppDatabase.M1011, AppDatabase.M1112, AppDatabase.M1213)

    private fun original() = BookEntity(
        id = 41, uriString = "content://provider/document/original", fileName = "original.mobi",
        format = "MOBI", fileSize = 12345, lastModified = 987, title = "Migration Quokka",
        author = "An Author", publishingDate = "2020", publisher = "Publisher", language = "en",
        coverPath = "/covers/41", lastLocatorJson = "{\"position\":3}", progressPercent = 42.5,
        lastOpenedAt = 123456, metadataExtracted = true, series = "Series", seriesIndex = 2.5,
        tags = "tag1,tag2", isFavorite = true, readingStatus = "READING"
    )
    private fun converted() = original().copy(
        id = 42, uriString = "content://provider/document/converted", fileName = "converted.epub",
        format = "EPUB", title = "Converted Copy"
    )

    private fun makeFixture(version: Int) = runBlocking {
        require(version in 5..9)
        // Generate Room's own schema, seed representative state, then remove only tables
        // introduced after the chosen version. Close Room before manipulating SQLite.
        val created = builder().build()
        room = created
        val dao = created.bookDao()
        assertEquals(41L, dao.insertIgnore(original()))
        assertEquals(42L, dao.insertIgnore(converted()))
        val shelfId = dao.createCustomShelf(CustomShelf(name = "Keep Shelf"))
        assertTrue(shelfId > 0)
        dao.addBookToCustomShelf(CustomShelfBook(shelfId, 41))
        dao.putConversionLink(ConversionLink(41, 42))
        // The converted book has scanner evidence; the original deliberately does not.
        dao.insertScanBookOrigins(listOf(ScanBookOrigin(42, root, "parent", "converted")))
        dao.insertScanSightings(listOf(ScanSighting(root, "parent", "converted", false)))
        dao.commitFolderExclusion(ScanExclusion("provider", "global-blocked"), listOf(root))
        dao.commitRootExclusion(ScanRootExclusion(root, "existing-root-blocked"))
        created.close()
        room = null
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("DROP TABLE scan_config_journal")
            db.execSQL("DROP TABLE scan_path_exclusions")
            db.execSQL("DROP TABLE hidden_scan_books")
            db.execSQL("DROP TABLE scan_memberships")
            db.execSQL("DROP TABLE independent_books")
            db.execSQL("DROP TABLE scan_owned_books")
            db.execSQL("DROP TABLE scan_inventory_roots")
            db.execSQL("DROP TABLE scan_inventory_state")
            db.execSQL("DROP TABLE scan_config_roots")
            db.execSQL("DROP TABLE scan_config_state")
            db.execSQL("DROP TABLE removed_scan_roots")
            if (version < 9) {
                db.execSQL("DROP TABLE scan_book_routes")
                db.execSQL("DROP TABLE scan_unresolved_routes")
            }
            if (version < 8) db.execSQL("DROP TABLE scan_root_exclusions")
            if (version < 7) {
                db.execSQL("DROP TABLE scan_exclusions")
                db.execSQL("DROP TABLE scan_allowed_roots")
            }
            if (version < 6) {
                db.execSQL("DROP TABLE scan_book_origins")
                db.execSQL("DROP TABLE scan_sightings")
            }
            db.version = version
        }
    }

    private fun verifyMigration(version: Int) = runBlocking {
        makeFixture(version)
        val upgraded = builder().build()
        room = upgraded
        // Forces open, migration, and Room's generated schema validation.
        val sql = upgraded.openHelper.writableDatabase
        assertEquals(13, sql.version)
        assertTrue(upgraded.bookDao().removedScanRoots().isEmpty())
        val dao = upgraded.bookDao()
        assertEquals(original(), dao.getByIdOnce(41))
        assertEquals(converted(), dao.getByIdOnce(42))
        assertEquals(2, dao.countAllBooks())
        assertEquals(1, dao.shelfCountForRemoval(41))
        assertEquals(1, dao.conversionCountForRemoval(41))
        assertNull(dao.getScanBookOrigin(41)) // no ownership invented for a legacy row
        if (version >= 6) {
            assertEquals(ScanBookOrigin(42, root, "parent", "converted"), dao.getScanBookOrigin(42))
            assertEquals(listOf(ScanSighting(root, "parent", "converted", false)), dao.getScanSightings(root, "parent"))
        } else {
            assertNull(dao.getScanBookOrigin(42))
            assertTrue(dao.getScanSightings(root, "parent").isEmpty())
        }
        if (version >= 7) {
            assertEquals(listOf(ScanExclusion("provider", "global-blocked")), dao.getScanExclusions())
            assertEquals(listOf(root), dao.getAllowedScanRoots())
        } else {
            assertTrue(dao.getScanExclusions().isEmpty())
            assertTrue(dao.getAllowedScanRoots().isEmpty())
        }
        assertTrue(dao.scanBookRoutes(41).isEmpty())
        assertTrue(dao.scanBookRoutes(42).isEmpty()) // historical origins are not full routes
        assertTrue(dao.unresolvedScanRoutes().isEmpty())
        if (version >= 8) {
            assertEquals(listOf(ScanRootExclusion(root, "existing-root-blocked")), dao.rootExclusions(root))
        } else {
            assertTrue(dao.rootExclusions(root).isEmpty())
        }
        // Confirm that migrated tables can store new exclusions, not merely exist.
        dao.commitRootExclusion(ScanRootExclusion(root, "root-blocked"))
        assertTrue(dao.rootExclusions(root).contains(ScanRootExclusion(root, "root-blocked")))
        assertEquals(if (version >= 8) 2 else 1, dao.rootExclusions(root).size)
        sql.query("SELECT COUNT(*) FROM books_fts WHERE books_fts MATCH 'Quokka'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        sql.query("PRAGMA foreign_key_check").use { cursor -> assertFalse(cursor.moveToFirst()) }
    }

    @Test fun version5To9PreservesBooksAndDoesNotInventOrigins() = verifyMigration(5)
    @Test fun version6To9PreservesScannerEvidence() = verifyMigration(6)
    @Test fun version7To9PreservesGlobalExclusionsAndAllowedRoots() = verifyMigration(7)
    @Test fun version8To9PreservesRootExclusionsAndDoesNotInventRoutes() = verifyMigration(8)
    @Test fun version9To10PreservesBooksAndCreatesNoTombstones() = verifyMigration(9)

    @Test fun invalidHistoricalSchemaFailsClosedAndRollsBack() {
        makeFixture(7)
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL("DROP INDEX index_books_title")
        }
        val upgraded = builder().build()
        room = upgraded
        try {
            upgraded.openHelper.writableDatabase
            fail("Room should reject an invalid historical schema")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Migration didn't properly handle"))
        } finally {
            upgraded.close()
            room = null
        }
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            assertEquals(7, db.version)
            db.rawQuery("SELECT title FROM books WHERE id = 41", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Migration Quokka", cursor.getString(0))
            }
            db.rawQuery("SELECT documentId FROM scan_exclusions WHERE authority = 'provider'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("global-blocked", cursor.getString(0))
            }
        }
    }
}
