package com.aubynsamuel.clipsync.security

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Runs the same on-device self-check the Settings screen exposes, on the JVM.
 *
 * The known-answer vectors here are identical to the Windows ones and were produced by an
 * independent implementation, so a green run proves this build speaks exactly the protocol the
 * desktop build speaks — no phone or PC required.
 */
class SecuritySelfTestTest {

    @Test
    fun everySecurityCheckPasses() {
        val results = SecuritySelfTest.run()
        val failed = results.filter { !it.passed }
        assertTrue(
            "failing checks: " + failed.joinToString("; ") { "${it.name} -> ${it.detail}" },
            failed.isEmpty()
        )
    }

    @Test
    fun replayWindowRejectsOldFrames() {
        val results = SecuritySelfTest.run().associateBy { it.name }
        assertTrue("replay window check missing", results.containsKey("replay-window"))
        assertTrue(
            "replay window: " + results["replay-window"]?.detail,
            results["replay-window"]?.passed == true
        )
    }
}
