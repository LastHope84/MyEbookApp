package com.example.myebookapp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Exercises the real Room journal and real SharedPreferences, with isolated preferences. */
@RunWith(AndroidJUnit4::class)
class ScannerConfigRecoveryTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: BookDao
    private lateinit var ctx: Context
    private val old = "content://p/tree/old"
    private val added = "content://p/tree/added"
    private val before = "[\"content://p/tree/old\"]"
    private val after = "[\"content://p/tree/added\",\"content://p/tree/old\"]"
    private fun folderPrefs() = ctx.getSharedPreferences("my_ebook_folders", Context.MODE_PRIVATE)
    private fun setFolders(value: Set<String>) { assertTrue(folderPrefs().edit().putStringSet("folder_uris", value).commit()) }
    private fun folders() = LibraryScanner.getSavedFolders(ctx)

    @Before fun setup() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = UUID.randomUUID().toString()
        ctx = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(prefix + name, mode)
        }
        db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        dao = db.bookDao()
    }
    @After fun close() = db.close()
    private suspend fun begin() {
        setFolders(setOf(old))
        dao.initializeAndCheckConfig(listOf(old))
        dao.beginJournaledRootAdd(added, listOf(old), before, after)
    }
    @Test fun pendingAddNeverChangesPreferencesJournalOrPausedQueue() = runBlocking {
        begin()
        val scan = ctx.getSharedPreferences("scan_session_v1", Context.MODE_PRIVATE)
        assertTrue(scan.edit().putString("mode", "paused").putString("roots", before).commit())
        val journal = dao.configJournal()
        for (preferences in listOf(setOf(old), setOf(old, added))) {
            setFolders(preferences)
            try { LibraryScanner.reconcileFolderConfig(ctx, dao); fail("must fail closed") } catch (_: IllegalStateException) { }
            assertEquals(preferences, folders())
            assertEquals(journal, dao.configJournal())
            assertEquals(setOf(old, added), dao.configuredScanRoots().toSet())
            assertEquals("paused", scan.getString("mode", null))
            assertEquals(before, scan.getString("roots", null))
        }
    }
    @Test fun newAddIsRejectedWithoutPreferencesOrQueueWrites() = runBlocking {
        setFolders(setOf(old)); dao.initializeAndCheckConfig(listOf(old))
        val scan = ctx.getSharedPreferences("scan_session_v1", Context.MODE_PRIVATE)
        assertTrue(scan.edit().putString("mode", "paused").putString("roots", before).commit())
        try { LibraryScanner.addFolder(ctx, android.net.Uri.parse(added)); fail("must fail closed") } catch (_: IllegalStateException) { }
        assertEquals(setOf(old), folders())
        assertNull(dao.configJournal())
        assertEquals(listOf(old), dao.configuredScanRoots())
        assertEquals("paused", scan.getString("mode", null))
        assertEquals(before, scan.getString("roots", null))
    }
    @Test fun interruptedRoomTransactionRollsBackJournalAndInventory() = runBlocking {
        setFolders(setOf(old)); dao.initializeAndCheckConfig(listOf(old))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_root BEFORE INSERT ON scan_config_roots WHEN NEW.rootUri = 'content://p/tree/added' BEGIN SELECT RAISE(ABORT, 'injected failure'); END")
        try { dao.beginJournaledRootAdd(added, listOf(old), before, after); fail("must fail") } catch (_: android.database.SQLException) { }
        assertNull(dao.configJournal())
        assertNull(dao.inventoryState())
        assertEquals(setOf(old), dao.configuredScanRoots().toSet())
        assertEquals(setOf(old), folders())
    }
}
