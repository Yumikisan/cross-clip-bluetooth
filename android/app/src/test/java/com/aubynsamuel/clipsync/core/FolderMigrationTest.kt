package com.aubynsamuel.clipsync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The path rewrite is the core of the 2.4.10 migration: everything else is plumbing around it.
 * These cases are the spellings that actually occurred on device.
 */
class FolderMigrationTest {

    private val legacy = listOf("Documents/.clipsync", "Documents/_.clipsync")
    private val target = "Documents/crossclip"

    private fun move(stored: String) = FolderMigration.rewrite(stored, legacy, target)

    @Test
    fun `hidden root folder becomes the visible one`() {
        assertEquals("Documents/crossclip/", move("Documents/.clipsync/"))
    }

    @Test
    fun `rom spelling with an underscore is moved too`() {
        assertEquals("Documents/crossclip/", move("Documents/_.clipsync/"))
    }

    @Test
    fun `nested folders keep their hierarchy`() {
        assertEquals("Documents/crossclip/a/b/", move("Documents/_.clipsync/a/b/"))
    }

    @Test
    fun `trailing slash is optional`() {
        assertEquals("Documents/crossclip/x.txt", move("Documents/.clipsync/x.txt"))
    }

    @Test
    fun `paths outside the legacy folder are never touched`() {
        assertNull(move("Documents/notes/"))
        assertNull(move("Download/crossclip/a/"))
        assertNull(move("Documents/_.clipsync"))
    }

    @Test
    fun `result is a plain folder the gallery can see`() {
        val moved = move("Documents/_.clipsync/shot.png")!!
        assertFalse(moved.substringAfter("Documents/").startsWith("."))
    }
}
