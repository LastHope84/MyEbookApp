package com.example.myebookapp

/** All methods are called with LibraryScanner's lock held. This is process-local, not a
 * cross-process lock or a durable transaction. A failed commit poisons this process because
 * SharedPreferences can expose a value in memory even when commit() returned false. */
internal class ScannerSafetyGate {
    private var restoreActive = false
    private var controlEpoch = 0L
    private var uncertain = false
    private var configurationVerified = false

    fun beginRestore(): Long? {
        if (uncertain || restoreActive) return null
        restoreActive = true
        configurationVerified = false
        return controlEpoch
    }
    fun restoreCurrent(token: Long): Boolean = restoreActive && !uncertain && token == controlEpoch
    // Keep admission closed during a fresh reconciliation.
    fun retryRestore(): Long? = if (restoreActive && !uncertain) controlEpoch else null
    fun markVerified(token: Long) { check(restoreCurrent(token)); configurationVerified = true }
    fun unverify() { configurationVerified = false }
    fun endRestore() { restoreActive = false }
    fun canAdmit(): Boolean = !uncertain && !restoreActive && configurationVerified
    fun canPersist(): Boolean = !uncertain
    fun changed() { controlEpoch++ }
    fun failedCommit() { uncertain = true; controlEpoch++ }
    fun isUncertain(): Boolean = uncertain
}
