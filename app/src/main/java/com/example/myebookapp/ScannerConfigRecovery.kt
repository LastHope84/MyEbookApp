package com.example.myebookapp

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Test-only V3 replay. Do not wire into production: scanLock does not span the suspending
 * Room finish or serialize controls, and stopped queues can now retain work. V2 must not enter. */
internal object ScannerConfigRecovery {
    private fun strings(a: JSONArray): List<String> {
        val values = (0 until a.length()).map { i ->
            require(a.get(i) is String)
            a.getString(i).also { require(it.isNotBlank()) }
        }
        require(values.distinct().size == values.size)
        return values
    }

    suspend fun replay(app: Context, dao: BookDao, scanLock: Any): Boolean {
        val j = dao.configJournal() ?: return false
        check(j.operation == "ADD_SCAN_V3") { "Ambiguous older folder operation requires manual recovery" }
        val before = JSONObject(j.beforeJson)
        val after = JSONObject(j.afterJson)
        val oldFolders = strings(before.getJSONArray("folders")).toSet()
        val newFolders = strings(after.getJSONArray("folders")).toSet()
        val oldQueue = strings(before.getJSONArray("queue"))
        val oldMode = before.getString("mode")
        val oldRevision = before.getString("revision")
        val id = after.getString("id").also { require(it.isNotBlank()) }
        val inventoryGeneration = after.getLong("generation")
        check(j.rootUri.isNotBlank() && j.rootUri !in oldFolders && newFolders == oldFolders + j.rootUri)
        check(oldMode in setOf("running", "paused", "interrupted", "stopped"))
        check(oldQueue.all { it in oldFolders } && (oldMode != "stopped" || oldQueue.isEmpty()))
        check(dao.configState() != null && dao.configuredScanRoots().toSet() == newFolders)
        check(dao.inventoryState()?.generation == inventoryGeneration)
        check(dao.removedScanRoots().none { it.rootUri in newFolders })
        val folders = app.getSharedPreferences("my_ebook_folders", Context.MODE_PRIVATE)
        val scan = app.getSharedPreferences("scan_session_v1", Context.MODE_PRIVATE)
        val saved = folders.getStringSet("folder_uris", emptySet())?.toSet() ?: emptySet()
        check(saved == oldFolders || saved == newFolders) { "Folder checkpoint diverged" }
        check(folders.edit().putStringSet("folder_uris", newFolders).commit()) { "Folder checkpoint failed" }
        synchronized(scanLock) {
            val mode = scan.getString("mode", "stopped") ?: "stopped"
            val queue = strings(JSONArray(scan.getString("roots", "[]") ?: "[]"))
            val revision = scan.getString("revision", "") ?: ""
            val marker = scan.getString("config_replay", "") ?: ""
            val control = scan.getString("last_control", "") ?: ""
            check(queue.all { it in newFolders }) { "Queue checkpoint diverged" }
            if (marker == id) {
                // Our earlier queue commit succeeded. Never enqueue the root twice.
                check(queue == oldQueue + j.rootUri && mode in setOf("paused", "interrupted"))
                // commit(false) may still change the process-local preference cache. Recommit
                // before clearing Room; never mistake an in-memory value for a disk checkpoint.
                check(scan.edit().putString("mode", mode).putString("roots", JSONArray(queue).toString())
                    .putString("revision", revision).putString("config_replay", id)
                    .putString("last_control", control).commit()) { "Scan checkpoint failed" }
            } else if (revision != oldRevision && control == "stop" && mode == "stopped" && queue.isEmpty()) {
                // Explicit stop supersedes this scan intent, including after a failed finish.
                check(scan.edit().putString("mode", mode).putString("roots", "[]")
                    .putString("revision", revision).putString("last_control", "stop")
                    .remove("config_replay").commit()) { "Stop checkpoint failed" }
            } else {
                val original = revision == oldRevision && mode == oldMode && queue == oldQueue
                val laterPause = revision != oldRevision && control == "pause" && mode == "paused" &&
                    (queue == oldQueue || queue == oldQueue + j.rootUri)
                check(original || laterPause) { "Scan queue changed; recovery requires review" }
                val next = if (j.rootUri in queue) queue else queue + j.rootUri
                val nextMode = if (mode == "paused") "paused" else "interrupted"
                check(scan.edit().putString("mode", nextMode).putString("roots", JSONArray(next).toString())
                    .putString("revision", java.util.UUID.randomUUID().toString())
                    .putString("config_replay", id).putString("last_control", "").commit()) { "Scan checkpoint failed" }
            }
        }
        dao.finishRootAddV3(j, newFolders, inventoryGeneration)
        return true
    }
}
