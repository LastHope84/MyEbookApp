package com.example.myebookapp

import org.junit.Assert.*
import org.junit.Test

class ScanDirectoryQueueTest {
    @Test fun `self loops cycles and duplicate parents terminate without dropping other directories`() {
        val graph = mapOf("root" to listOf("root", "a", "b"),
            "a" to listOf("root", "shared"), "b" to listOf("shared"),
            "shared" to listOf("a", "leaf"), "leaf" to emptyList())
        val queue = ScanDirectoryQueue("root")
        val visited = mutableListOf<String>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            visited.add(id)
            check(visited.size <= graph.size) { "Repeated directory" }
            graph.getValue(id).forEach(queue::add)
        }
        assertEquals(listOf("root", "a", "b", "shared", "leaf"), visited)
    }

    @Test fun `document ids are opaque and case sensitive`() {
        val queue = ScanDirectoryQueue("a")
        queue.add("a/b")
        queue.add("A")
        queue.add("a")
        assertEquals("a", queue.removeFirst())
        assertEquals("a/b", queue.removeFirst())
        assertEquals("A", queue.removeFirst())
        assertFalse(queue.isNotEmpty())
    }
}
