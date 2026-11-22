package com.example.myebookapp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real Room/preferences in one process; not a process-kill or SAF provider test. */
@RunWith(AndroidJUnit4::class)
class ScannerConfigV3IntegrationTest {
    private val old = "content://p/tree/old"
    private val added = "content://p/tree/added"
    private fun isolated(base: Context): Context {
        val prefix = UUID.randomUUID().toString()
        return object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(prefix + name, mode)
        }
    }
    @Test fun postReconciliationWorkerAdmissionRequiresRunningAndUnchangedQueueAndGeneration() {
        val queue = listOf(old, added)
        val validated = LibraryScanner.ValidatedQueue("revision-1", queue)
        fun admit(mode: String, generation: Long = 7, revision: String = "revision-1", roots: List<String> = queue) =
            LibraryScanner.workerAdmissionValid(mode, generation, 7, revision, roots, validated)
        // Reconciliation may have turned a previously running session into interrupted.
        assertFalse(admit("interrupted"))
        assertFalse(admit("paused"))
        assertFalse(admit("stopped"))
        assertFalse(admit("running", generation = 8))
        assertFalse(admit("running", revision = "revision-2"))
        assertFalse(admit("running", roots = listOf(old)))
        assertTrue(admit("running"))
    }

    @Test fun scannerReconciliationRetainsPendingV3WithoutChangingPreferences() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val ctx = isolated(base); val dao = db.bookDao()
            val folders = ctx.getSharedPreferences("my_ebook_folders", Context.MODE_PRIVATE)
            val scan = ctx.getSharedPreferences("scan_session_v1", Context.MODE_PRIVATE)
            assertTrue(folders.edit().putStringSet("folder_uris", setOf(old)).commit())
            assertTrue(scan.edit().putString("mode", "running").putString("roots", "[\"$old\"]").putString("revision", "r1").commit())
            dao.initializeAndCheckConfig(listOf(old))
            dao.stageRootAddV3(added, listOf(old), listOf(old), "running", "r1")
            val journal = dao.configJournal()
            try { LibraryScanner.reconcileFolderConfig(ctx, dao); fail("pending V3 must fail closed") }
            catch (_: IllegalStateException) { }
            assertEquals(journal, dao.configJournal())
            assertEquals(setOf(old), folders.getStringSet("folder_uris", null))
            assertEquals("running", scan.getString("mode", null))
            assertEquals("[\"$old\"]", scan.getString("roots", null))
        } finally { db.close() }
    }

    @Test fun pausedRecoveryRetriesAfterRoomFinishFailureWithoutDuplicates() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val ctx = isolated(base); val dao = db.bookDao(); val lock = Any()
            val folders = ctx.getSharedPreferences("my_ebook_folders", Context.MODE_PRIVATE)
            val scan = ctx.getSharedPreferences("scan_session_v1", Context.MODE_PRIVATE)
            assertTrue(folders.edit().putStringSet("folder_uris", setOf(old)).commit())
            assertTrue(scan.edit().putString("mode", "paused").putString("roots", "[\"$old\"]").putString("revision", "r1").commit())
            dao.initializeAndCheckConfig(listOf(old)); dao.stageRootAddV3(added, listOf(old), listOf(old), "paused", "r1")
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_finish BEFORE DELETE ON scan_config_journal BEGIN SELECT RAISE(ABORT, 'fail'); END")
            try { ScannerConfigRecovery.replay(ctx, dao, lock); fail("finish must fail") } catch (_: android.database.SQLException) { }
            assertNotNull(dao.configJournal())
            assertEquals("paused", scan.getString("mode", null))
            assertEquals("[\"$old\",\"$added\"]", scan.getString("roots", null))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_finish")
            assertTrue(ScannerConfigRecovery.replay(ctx, dao, lock)); assertNull(dao.configJournal())
            assertFalse(ScannerConfigRecovery.replay(ctx, dao, lock))
            assertEquals("[\"$old\",\"$added\"]", scan.getString("roots", null))
        } finally { db.close() }
    }
    @Test fun explicitStopAfterStagingWinsAndOldServiceCannotInterrupt() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val ctx = isolated(base); val dao = db.bookDao()
            val scan = ctx.getSharedPreferences("scan_session_v1", Context.MODE_PRIVATE)
            dao.initializeAndCheckConfig(emptyList()); dao.stageRootAddV3(added, emptyList(), emptyList(), "stopped", "")
            assertTrue(scan.edit().putString("mode", "stopped").putString("roots", "[]")
                .putString("revision", "stop2").putString("last_control", "stop").commit())
            assertTrue(ScannerConfigRecovery.replay(ctx, dao, Any()))
            assertEquals("stopped", scan.getString("mode", null)); assertEquals("[]", scan.getString("roots", null))
            val stale = Any()
            assertFalse(LibraryScanner.bindService(stale, "old-session"))
            LibraryScanner.interruptFromService(ctx, stale, "old-session", "stale")
            assertFalse(LibraryScanner.isServiceOwner(stale, "old-session")); assertEquals("stopped", scan.getString("mode", null))
        } finally { db.close() }
    }
    @Test fun unknownQueueRevisionRetainsIntent() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val ctx = isolated(base); val dao = db.bookDao()
            dao.initializeAndCheckConfig(emptyList()); dao.stageRootAddV3(added, emptyList(), emptyList(), "stopped", "")
            val scan = ctx.getSharedPreferences("scan_session_v1", Context.MODE_PRIVATE)
            assertTrue(scan.edit().putString("mode", "running").putString("roots", "[]").putString("revision", "foreign").commit())
            try { ScannerConfigRecovery.replay(ctx, dao, Any()); fail("divergence") } catch (_: IllegalStateException) { }
            assertNotNull(dao.configJournal()); assertEquals("running", scan.getString("mode", null))
        } finally { db.close() }
    }
    @Test fun failedPreferenceCommitLeavesJournalForRetry() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(base, AppDatabase::class.java).build()
        try {
            val prefix = UUID.randomUUID().toString()
            var failOnce = false
            val ctx = object : ContextWrapper(base) {
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                    val delegate = base.getSharedPreferences(prefix + name, mode)
                    if (name != "scan_session_v1") return delegate
                    return object : SharedPreferences by delegate {
                        override fun edit(): SharedPreferences.Editor {
                            val editor = delegate.edit()
                            return object : SharedPreferences.Editor by editor {
                                override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                                    editor.putString(key, value); return this
                                }
                                override fun remove(key: String?): SharedPreferences.Editor {
                                    editor.remove(key); return this
                                }
                                override fun commit(): Boolean {
                                    val written = editor.commit()
                                    if (failOnce) { failOnce = false; return false }
                                    return written
                                }
                            }
                        }
                    }
                }
            }
            val dao = db.bookDao()
            dao.initializeAndCheckConfig(emptyList())
            dao.stageRootAddV3(added, emptyList(), emptyList(), "stopped", "")
            failOnce = true
            try { ScannerConfigRecovery.replay(ctx, dao, Any()); fail("commit failure") } catch (_: IllegalStateException) { }
            assertNotNull(dao.configJournal())
            assertTrue(ScannerConfigRecovery.replay(ctx, dao, Any()))
            assertNull(dao.configJournal())
            val scan = ctx.getSharedPreferences("scan_session_v1", Context.MODE_PRIVATE)
            assertEquals("interrupted", scan.getString("mode", null))
            assertEquals("[\"$added\"]", scan.getString("roots", null))
        } finally { db.close() }
    }

}
