package com.example.myebookapp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Run this class in a separate instrumentation process: it intentionally poisons the singleton.
 * Simulates a false return AFTER the cache changes, not a real disk failure. */
@RunWith(AndroidJUnit4::class)
@LargeTest // exclude from the normal suite; run this class alone in a fresh process
class ScannerFailedCommitIsolatedTest {
    @Test fun failedStopCancelsAdmissionAndCannotBeRecommittedFromCache() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = UUID.randomUUID().toString()
        var shouldFail = false
        var commits = 0
        val ctx = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
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
                                commits++
                                val result = editor.commit()
                                if (shouldFail) { shouldFail = false; return false }
                                return result
                            }
                        }
                    }
                }
            }
        }
        val prefs = ctx.getSharedPreferences("scan_session_v1", Context.MODE_PRIVATE)
        val queue = "[\"content://p/tree/old\"]"
        assertTrue(prefs.edit().putString("mode", "running").putString("roots", queue).commit())
        shouldFail = true
        LibraryScanner.stopScan(ctx)
        assertEquals("stopped", prefs.getString("mode", null)) // false result still changed cache
        assertEquals("[]", prefs.getString("roots", null))
        assertEquals("Could not confirm scan stopped", LibraryScanner.status.value.phaseLabel)
        assertTrue(LibraryScanner.status.value.error!!.contains("may not have been saved"))
        assertFalse(LibraryScanner.status.value.canResume)
        assertFalse(LibraryScanner.startWorker(ctx, Any(), "invalid-session") { fail("worker admitted") })
        val afterFailure = commits
        LibraryScanner.resumeScan(ctx)
        LibraryScanner.pauseScan(ctx)
        LibraryScanner.stopScan(ctx)
        LibraryScanner.scanFolders(ctx, listOf(Uri.parse("content://p/tree/new")))
        assertEquals(afterFailure, commits)
        assertEquals("[]", prefs.getString("roots", null))
    }
}
