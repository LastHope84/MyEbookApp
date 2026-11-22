package com.example.myebookapp

import org.junit.Assert.*
import org.junit.Test

class FolderManagementOutcomeTest {
    @Test fun `partial outcome reports known counts and restart need`() {
        val text = FolderManagementOutcome(
            FolderManagementChange.PARTIAL, removedRows = 3, retainedRows = 2,
            scan = FolderManagementScan.RESTART_REQUIRED, detail = "Provider listing incomplete."
        ).toUserMessage()
        assertTrue(text.contains("partially applied"))
        assertTrue(text.contains("Library entries removed: 3."))
        assertTrue(text.contains("Library entries retained: 2."))
        assertTrue(text.contains("A rescan is required."))
        assertTrue(text.contains("Provider listing incomplete."))
    }

    @Test fun `unknown outcome does not invent counts or scan status`() {
        val text = FolderManagementOutcome(FolderManagementChange.UNKNOWN).toUserMessage()
        assertTrue(text.contains("could not be confirmed"))
        assertFalse(text.contains("Library entries removed:"))
        assertFalse(text.contains("Library entries retained:"))
        assertTrue(text.contains("Scan status could not be confirmed."))
    }

    @Test fun `stopped scan is not reported as restarted`() {
        val text = FolderManagementOutcome(FolderManagementChange.NOT_APPLIED, scan = FolderManagementScan.STOPPED).toUserMessage()
        assertTrue(text.contains("not applied"))
        assertTrue(text.contains("has not restarted"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative removed counts are rejected`() {
        FolderManagementOutcome(FolderManagementChange.APPLIED, removedRows = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative retained counts are rejected`() {
        FolderManagementOutcome(FolderManagementChange.APPLIED, retainedRows = -1)
    }
}
