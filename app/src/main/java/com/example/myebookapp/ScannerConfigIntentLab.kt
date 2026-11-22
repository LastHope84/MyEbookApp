package com.example.myebookapp

import android.content.Context
import org.json.JSONArray

/** Experimental replay primitive, deliberately not wired to UI/service.
 * The caller must hold scanner admission AND processing locks for the entire suspend call;
 * no worker, queue control or preference writer may run concurrently. Recovery after process
 * death must happen before admitting any stop/pause/resume/enqueue operation. */
internal object ScannerConfigIntentLab {
    private fun decode(encoded: String): List<String> {
        val a = JSONArray(encoded)
        val out = (0 until a.length()).map { i ->
            require(a.get(i) is String)
            a.getString(i).also { require(it.isNotBlank()) }
        }
        require(out.distinct().size == out.size)
        return out
    }

    /** Idempotent across process death at either preferences commit or before Room completion.
     * Returns true only for a V2 operation. Unknown/legacy operations are never consumed. */
    suspend fun replay(context: Context, dao: BookDao): Boolean {
        val journal = dao.configJournal() ?: return false
        check(journal.operation == "ADD_SCAN_V2") { "Unknown or legacy journal needs manual recovery" }
        val before = decode(journal.beforeJson).toSet()
        val after = decode(journal.afterJson).toSet()
        val root = journal.rootUri
        check(root.isNotBlank() && root !in before && after == before + root)
        check(dao.configState() != null && dao.configuredScanRoots().toSet() == after)
        check(dao.removedScanRoots().none { it.rootUri in after })
        val folders = context.getSharedPreferences("my_ebook_folders", Context.MODE_PRIVATE)
        val scan = context.getSharedPreferences("scan_session_v1", Context.MODE_PRIVATE)
        val saved = folders.getStringSet("folder_uris", emptySet())?.toSet() ?: emptySet()
        check(saved == before || saved == after) { "Unexpected folder preferences" }
        val queue = decode(scan.getString("roots", "[]") ?: "[]")
        val mode = scan.getString("mode", "stopped") ?: "stopped"
        check(mode in setOf("running", "paused", "interrupted", "stopped"))
        check(queue.all { it in after }) { "Unexpected queued root" }
        check(mode != "stopped" || queue.isEmpty() || queue == listOf(root)) { "Stopped session has unexpected work" }
        // No work starts on recovery. Explicit pause remains paused; running is interrupted.
        val nextMode = if (mode == "paused") "paused" else "interrupted"
        val nextQueue = if (root in queue) queue else queue + root
        check(folders.edit().putStringSet("folder_uris", after).commit()) { "Folder checkpoint failed" }
        check(scan.edit().putString("mode", nextMode).putString("roots", JSONArray(nextQueue).toString()).commit()) { "Scan checkpoint failed" }
        // This call is safe only under the admission gate, until Room completes.
        dao.finishRootAddWithScanIntent(journal, after)
        return true
    }
}
