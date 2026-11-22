package com.example.myebookapp

import org.junit.Assert.*
import org.junit.Test

class ScanSessionAuthorityTest {
    @Test fun `new process rejects delayed or absent intents`() {
        val before = ScanSessionAuthority { "old" }
        val token = before.arm()
        val restarted = ScanSessionAuthority { "new" }
        val service = Any()
        assertFalse(restarted.bind(token, service))
        assertFalse(restarted.bind(null, service))
        assertFalse(restarted.owns(token, service))
        val newToken = restarted.arm()
        assertFalse(restarted.bind(token, service))
        assertTrue(restarted.bind(newToken, service))
    }

    @Test fun `new session fences old owner and callbacks even on same service instance`() {
        var i = 0
        val gate = ScanSessionAuthority { "session-${++i}" }
        val service = Any()
        val old = gate.arm()
        assertTrue(gate.bind(old, service))
        val next = gate.arm()
        assertTrue(gate.bind(next, service))
        assertFalse(gate.owns(old, service))
        assertFalse(gate.retire(old, service))
        assertFalse(gate.bind(old, service))
        assertTrue(gate.owns(next, service))
        assertTrue(gate.retire(next, service))
        assertFalse(gate.bind(next, service))
    }

    @Test fun `owner cannot be stolen and stopped session cannot be resurrected`() {
        val gate = ScanSessionAuthority { "token" }
        val first = Any(); val second = Any()
        val token = gate.arm()
        assertTrue(gate.bind(token, first))
        assertTrue(gate.bind(token, first))
        assertFalse(gate.bind(token, second))
        assertFalse(gate.retire(token, second))
        gate.disarm()
        assertFalse(gate.bind(token, first))
        assertFalse(gate.retire(token, first))
    }

    @Test fun `generator failure and repeated token fail closed`() {
        var i = 0
        val gate = ScanSessionAuthority { if (++i < 3) "same" else throw IllegalStateException("entropy") }
        val service = Any()
        val old = gate.arm()
        assertTrue(gate.bind(old, service))
        try { gate.arm(); fail("reused token admitted") } catch (_: IllegalStateException) { }
        assertFalse(gate.owns(old, service))
        assertFalse(gate.bind(old, service))
        try { gate.arm(); fail("failed generator admitted") } catch (_: IllegalStateException) { }
        assertFalse(gate.bind(old, service))
    }

    @Test fun `random production tokens are long and differ`() {
        val gate = ScanSessionAuthority()
        val first = gate.arm(); val second = gate.arm()
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertTrue(second.matches(Regex("[0-9a-f]{64}")))
        assertNotEquals(first, second)
    }
    @Test fun `old completion timeout and destruction cannot retire newer session`() {
        var next = 0
        val gate = ScanSessionAuthority { "token-${++next}" }
        val oldService = Any(); val newService = Any()
        val old = gate.arm(); assertTrue(gate.bind(old, oldService))
        val current = gate.arm(); assertTrue(gate.bind(current, newService))
        repeat(3) { assertFalse(gate.retire(old, oldService)); assertFalse(gate.owns(old, oldService)) }
        assertFalse(gate.bind(old, oldService)); assertTrue(gate.owns(current, newService))
    }

    @Test fun `stop pause and failed launch revoke authority without rearming`() {
        var next = 0
        val gate = ScanSessionAuthority { "token-${++next}" }
        val service = Any()
        for (control in listOf("stop", "pause", "launch failure")) {
            val old = gate.arm(); assertTrue(gate.bind(old, service))
            gate.disarm()
            assertTrue(gate.isDisarmed()); assertFalse(gate.bind(old, service))
            assertFalse(gate.retire(old, service))
        }
        val fresh = gate.arm(); assertTrue(gate.bind(fresh, service))
    }

    @Test fun `stale failure cannot revoke fresh unbound request`() {
        var n = 0
        val gate = ScanSessionAuthority { "r-${++n}" }
        val first = gate.arm(); val service = Any(); assertTrue(gate.bind(first, service))
        val fresh = gate.arm()
        assertFalse(gate.retire(first, service)); assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(fresh)); assertTrue(gate.bind(fresh, service))
    }
}
