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
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Isolated preferences + real Room. These do not simulate a process kill or provider I/O. */
@RunWith(AndroidJUnit4::class)
class ScannerConfigIntentLabTest {
    private val old = "content://p/tree/old"
    private val added = "content://p/tree/added"
    private fun isolated(base: Context): Context {
        val prefix = UUID.randomUUID().toString()
        return object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences(prefix + name, mode)
        }
    }
    @Test fun replayAtEveryPersistedBoundaryPreservesPausedQueue() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        for (boundary in 0..2) {
            val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
            try {
                val dao = db.bookDao(); val ctx = isolated(base)
                val folders = ctx.getSharedPreferences("my_ebook_folders", Context.MODE_PRIVATE)
                val scan = ctx.getSharedPreferences("scan_session_v1", Context.MODE_PRIVATE)
                assertTrue(folders.edit().putStringSet("folder_uris", setOf(old)).commit())
                assertTrue(scan.edit().putString("mode", "paused").putString("roots", JSONArray(listOf(old)).toString()).commit())
                dao.initializeAndCheckConfig(listOf(old))
                dao.stageRootAddWithScanIntent(added, listOf(old))
                if (boundary >= 1) assertTrue(folders.edit().putStringSet("folder_uris", setOf(old, added)).commit())
                if (boundary >= 2) assertTrue(scan.edit().putString("mode", "paused").putString("roots", JSONArray(listOf(old, added)).toString()).commit())
                assertTrue(ScannerConfigIntentLab.replay(ctx, dao))
                assertNull(dao.configJournal())
                assertEquals(setOf(old, added), folders.getStringSet("folder_uris", emptySet()))
                assertEquals("paused", scan.getString("mode", null))
                assertEquals(JSONArray(listOf(old, added)).toString(), scan.getString("roots", null))
                assertFalse(ScannerConfigIntentLab.replay(ctx, dao))
            } finally { db.close() }
        }
    }
    @Test fun stoppedRecoveryRequiresExplicitResumeAndForeignQueueIsRejected() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val dao = db.bookDao(); val ctx = isolated(base)
            val scan = ctx.getSharedPreferences("scan_session_v1", Context.MODE_PRIVATE)
            dao.initializeAndCheckConfig(emptyList()); dao.stageRootAddWithScanIntent(added, emptyList())
            assertTrue(scan.edit().putString("roots", "[\"foreign\"]").commit())
            try { ScannerConfigIntentLab.replay(ctx, dao); fail("foreign queue") } catch (_: IllegalStateException) { }
            assertNotNull(dao.configJournal())
            assertTrue(scan.edit().putString("roots", "[]").commit())
            assertTrue(ScannerConfigIntentLab.replay(ctx, dao))
            assertEquals("interrupted", scan.getString("mode", null))
            assertEquals(JSONArray(listOf(added)).toString(), scan.getString("roots", null))
        } finally { db.close() }
    }
    @Test fun concurrentStagingAdmitsOnlyOneIntent() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val dao = db.bookDao(); dao.initializeAndCheckConfig(listOf(old))
            val results = listOf(added, "content://p/tree/other").map { root ->
                async(Dispatchers.IO) {
                    try { dao.stageRootAddWithScanIntent(root, listOf(old)); true }
                    catch (_: IllegalStateException) { false }
                }
            }.awaitAll()
            assertEquals(1, results.count { it })
            assertEquals(2, dao.configuredScanRoots().size)
            assertEquals("ADD_SCAN_V2", dao.configJournal()?.operation)
        } finally { db.close() }
    }
    @Test fun legacyJournalCannotBeReplayedAsIntent() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val dao = db.bookDao(); val ctx = isolated(base)
            dao.initializeAndCheckConfig(listOf(old))
            dao.beginJournaledRootAdd(added, listOf(old), JSONArray(listOf(old)).toString(), JSONArray(listOf(old, added)).toString())
            val pending = dao.configJournal()
            try { ScannerConfigIntentLab.replay(ctx, dao); fail("legacy intent is ambiguous") } catch (_: IllegalStateException) { }
            assertEquals(pending, dao.configJournal())
            assertTrue(LibraryScanner.getSavedFolders(ctx).isEmpty())
        } finally { db.close() }
    }
    @Test fun stagingRollsBackWhenRootInsertFails() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val dao = db.bookDao(); dao.initializeAndCheckConfig(listOf(old))
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_v2 BEFORE INSERT ON scan_config_roots WHEN NEW.rootUri = 'content://p/tree/added' BEGIN SELECT RAISE(ABORT, 'injected'); END")
            try { dao.stageRootAddWithScanIntent(added, listOf(old)); fail("transaction must fail") } catch (_: android.database.SQLException) { }
            assertNull(dao.configJournal()); assertNull(dao.inventoryState())
            assertEquals(setOf(old), dao.configuredScanRoots().toSet())
        } finally { db.close() }
    }
}
