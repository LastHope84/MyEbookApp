package com.example.myebookapp

import java.security.SecureRandom

/**
 * Process-local authority. All callers use the scanner monitor.
 * Instances start disarmed and must never be persisted. Every call, including the check
 * and the state mutation it guards, must be made under the scanner's existing monitor.
 * Never keep that monitor while suspending. Token injection exists only for tests.
 */
internal class ScanSessionAuthority(
    private val tokenFactory: () -> String = {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
) {
    private var current: String? = null
    private var owner: Any? = null
    // Prevent accidental re-use even if a faulty test/injected generator repeats a token.
    private val issued = mutableSetOf<String>()

    /** Explicit user Start/Resume only, and only after a successful durable checkpoint. */
    fun arm(): String {
        // Disarm first: a generator failure must not leave the former authority alive.
        current = null
        owner = null
        val token = tokenFactory()
        check(token.isNotBlank() && issued.add(token)) { "Invalid or repeated scan session token" }
        current = token
        return token
    }

    /** Accept only the current token; an old service cannot steal an existing binding. */
    fun bind(token: String?, service: Any): Boolean {
        if (token == null || token != current) return false
        if (owner != null && owner !== service) return false
        owner = service
        return true
    }

    fun isCurrent(token: String?): Boolean = token != null && token == current
    fun isDisarmed(): Boolean = current == null

    /** Check a service/worker callback before any mutation, under the shared scanner lock. */
    fun owns(token: String?, service: Any): Boolean =
        token != null && token == current && owner === service

    /** Unexpected destruction/timeout may retire only its own still-current session. */
    fun retire(token: String?, service: Any): Boolean {
        if (!owns(token, service)) return false
        current = null
        owner = null
        return true
    }

    /** Explicit Stop or checkpoint uncertainty revokes the current authority. */
    fun disarm() {
        current = null
        owner = null
    }
}
