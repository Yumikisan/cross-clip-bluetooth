package com.aubynsamuel.clipsync.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The mkdir frame carries the folder id.
 *
 * It did not, and the Windows receiver keys its transfers by id, so every mkdir was dropped:
 * a folder whose sub-directories were all empty arrived as one empty root. The field is optional
 * on the wire so older peers (which ignore it) keep working — hence the two assertions below.
 */
// org.json is a stub on the plain JVM classpath (every put() returns null), so these frames are
// built for real under Robolectric instead.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class WireMkdirTest {

    @Test
    fun `mkdir carries the folder id when one is given`() {
        val json = JSONObject(Wire.mkdir("Photos/2024", "phone", "abc123"))

        assertEquals("mkdir", json.getString("type"))
        assertEquals("Photos/2024", json.getString("path"))
        assertEquals("abc123", json.getString("id"))
    }

    @Test
    fun `mkdir omits the id entirely when there is none`() {
        val json = JSONObject(Wire.mkdir("Photos", "phone"))

        assertFalse(
            "an empty id must not be serialised: older peers parse this frame positionally",
            json.has("id")
        )
        assertEquals("Photos", json.getString("path"))
    }

    @Test
    fun `folder frames agree on the id they describe`() {
        // Guard against the two halves drifting: whatever folderBegin announces is what the
        // follow-up mkdir frames must point at.
        val id = Wire.newId()
        val begin = JSONObject(Wire.folderBegin(id, "Photos", 2, 100, "phone"))
        val mkdir = JSONObject(Wire.mkdir("Photos/sub", "phone", id))

        assertEquals(begin.getString("id"), mkdir.getString("id"))
        assertTrue(mkdir.getString("path").startsWith(begin.getString("name")))
    }
}
