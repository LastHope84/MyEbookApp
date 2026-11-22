package com.example.myebookapp

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Instrumentation test only. No provider calls or user files. */
@RunWith(AndroidJUnit4::class)
class ScannerSafetyGateTest {
    @Test fun stopOrPauseInvalidatesSuspendedRestoreAndResumeIsNotAdmitted() {
        val gate = ScannerSafetyGate(); val lock = Any()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        var staleApplied = false
        val worker = Thread {
            val token = synchronized(lock) { gate.beginRestore()!! }
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            synchronized(lock) {
                staleApplied = gate.restoreCurrent(token)
                gate.endRestore()
            }
        }
        worker.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        synchronized(lock) {
            assertFalse(gate.canAdmit())
            gate.changed() // stop/pause is admitted and invalidates restore
        }
        release.countDown(); worker.join(5000)
        assertFalse(worker.isAlive); assertFalse(staleApplied)
        synchronized(lock) {
            assertFalse(gate.canAdmit()) // stale restore cannot verify configuration
            val retry = gate.beginRestore()!!
            gate.markVerified(retry); gate.endRestore()
            assertTrue(gate.canAdmit())
        }
    }


    @Test fun staleRestoreRetriesWithoutOpeningAdmission() {
        val gate = ScannerSafetyGate()
        val old = gate.beginRestore()!!
        gate.changed()
        assertFalse(gate.restoreCurrent(old))
        val retry = gate.retryRestore()!!
        assertFalse(gate.canAdmit())
        gate.markVerified(retry); gate.endRestore()
        assertTrue(gate.canAdmit())
    }

    @Test fun failedCommitPoisonsAdmissionEvenAfterRestoreEnds() {
        val gate = ScannerSafetyGate(); val token = gate.beginRestore()!!
        gate.failedCommit()
        assertFalse(gate.restoreCurrent(token)); gate.endRestore()
        assertFalse(gate.canAdmit()); assertFalse(gate.canPersist())
        assertNull(gate.beginRestore())
    }

    @Test fun actualStopDiscardsQueuedRootsAndNeverAdmitsWorker() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = UUID.randomUUID().toString()
        val ctx = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = base.getSharedPreferences(prefix + name, mode)
        }
        val prefs = ctx.getSharedPreferences("scan_session_v1", Context.MODE_PRIVATE)
        val queue = "[\"content://p/tree/old\"]"
        assertTrue(prefs.edit().putString("mode", "running").putString("roots", queue).commit())
        LibraryScanner.stopScan(ctx)
        assertEquals("stopped", prefs.getString("mode", null))
        assertEquals("[]", prefs.getString("roots", null))
        assertEquals("stop", prefs.getString("last_control", null))
        assertFalse(LibraryScanner.status.value.canResume)
        LibraryScanner.stopScan(ctx) // repeated stop remains stopped with no queued roots
        assertEquals("[]", prefs.getString("roots", null))
        assertEquals("stop", prefs.getString("last_control", null))
        assertFalse(LibraryScanner.startWorker(ctx) { fail("stopped worker must not start") })
    }
}
