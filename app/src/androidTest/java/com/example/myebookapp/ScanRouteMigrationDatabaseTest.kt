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

/** Reconstructed v8 fixture, not an independently exported production schema. */
@RunWith(AndroidJUnit4::class)
class ScanRouteMigrationDatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "scan_route_migration_test.db"
    private var db: AppDatabase? = null
    @Before fun setup() { context.deleteDatabase(name) }
    @After fun cleanup() { db?.close(); context.deleteDatabase(name) }

    private fun assertIdentityIndex(database: AppDatabase) {
        val sql = database.openHelper.writableDatabase
        var found = false
        sql.query("PRAGMA index_list(`scan_book_routes`)").use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == "index_scan_book_routes_authority_documentId") {
                    assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("unique")))
                    found = true
                }
            }
        }
        assertTrue(found)
        val columns = mutableListOf<String>()
        sql.query("PRAGMA index_info(`index_scan_book_routes_authority_documentId`)").use { cursor ->
            while (cursor.moveToNext()) columns.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
        }
        assertEquals(listOf("authority", "documentId"), columns)
    }

    @Test fun migrationKeepsLegacyStateWithoutInferringRoutes() = runBlocking {
        val builder = Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(AppDatabase.M89, AppDatabase.M910, AppDatabase.M1011, AppDatabase.M1112, AppDatabase.M1213)
        val created = builder.build()
        db = created
        val dao = created.bookDao()
        val book = BookEntity(id = 41, uriString = "content://provider/tree/root/document/book",
            fileName = "book.epub", format = "EPUB", fileSize = 22, lastModified = 5,
            title = "Edited title", author = "Author", publishingDate = "", publisher = "", language = "",
            coverPath = null, lastLocatorJson = null, progressPercent = 31.5, lastOpenedAt = 200,
            metadataExtracted = true, isFavorite = true, readingStatus = "READING")
        assertEquals(41L, dao.insertIgnore(book))
        val shelf = dao.createCustomShelf(CustomShelf(name = "Keep"))
        dao.addBookToCustomShelf(CustomShelfBook(shelf, 41))
        dao.insertScanBookOrigins(listOf(ScanBookOrigin(41, "content://provider/tree/root", "parent", "book")))
        assertIdentityIndex(created)
        created.close()
        db = null
        context.openOrCreateDatabase(name, Context.MODE_PRIVATE, null).use { sql ->
            sql.execSQL("DROP TABLE scan_config_journal")
            sql.execSQL("DROP TABLE scan_path_exclusions")
            sql.execSQL("DROP TABLE hidden_scan_books")
            sql.execSQL("DROP TABLE scan_memberships")
            sql.execSQL("DROP TABLE independent_books")
            sql.execSQL("DROP TABLE scan_owned_books")
            sql.execSQL("DROP TABLE scan_inventory_roots")
            sql.execSQL("DROP TABLE scan_inventory_state")
            sql.execSQL("DROP TABLE scan_config_roots")
            sql.execSQL("DROP TABLE scan_config_state")
            sql.execSQL("DROP TABLE removed_scan_roots")
            sql.execSQL("DROP TABLE scan_book_routes")
            sql.execSQL("DROP TABLE scan_unresolved_routes")
            sql.version = 8
        }
        val upgraded = builder.build()
        db = upgraded
        assertEquals(13, upgraded.openHelper.writableDatabase.version)
        assertIdentityIndex(upgraded)
        assertEquals(book, upgraded.bookDao().getByIdOnce(41))
        assertEquals(1, upgraded.bookDao().shelfCountForRemoval(41))
        assertNotNull(upgraded.bookDao().getScanBookOrigin(41))
        assertTrue(upgraded.bookDao().scanBookRoutes(41).isEmpty())
        assertTrue(upgraded.bookDao().unresolvedScanRoutes().isEmpty())
        upgraded.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }
}
