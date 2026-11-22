package com.example.myebookapp

/** A single walk queues each provider directory identity at most once. */
internal class ScanDirectoryQueue(rootId: String) {
    private val pending = ArrayDeque<String>().apply { addLast(rootId) }
    private val seen = mutableSetOf(rootId)

    fun isNotEmpty(): Boolean = pending.isNotEmpty()
    fun removeFirst(): String = pending.removeFirst()
    fun add(id: String) {
        if (seen.add(id)) pending.addLast(id)
    }
}
