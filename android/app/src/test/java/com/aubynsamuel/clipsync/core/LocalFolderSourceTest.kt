package com.aubynsamuel.clipsync.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Guards the scan half of a folder transfer.
 *
 * The scan decides three things the wire depends on: which directories exist (empty ones must
 * survive), which files go (and with what relative path), and what the totals are. A regression
 * here shows up on the other side as files in the wrong place or a progress row that never ends,
 * which is expensive to debug from a phone log — so it is pinned here instead.
 */
class LocalFolderSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun tree(): File {
        val root = tmp.newFolder("photos")
        File(root, "a.txt").writeText("hello-a")
        val sub = File(root, "sub").apply { mkdirs() }
        File(sub, "b.txt").writeText("hello-b")
        val deep = File(sub, "deep").apply { mkdirs() }
        File(deep, "c.bin").writeBytes(ByteArray(2000) { it.toByte() })
        // An empty directory must still be announced, or the receiver never creates it.
        File(root, "empty").mkdirs()
        return root
    }

    @Test
    fun `relative paths carry the root name and the file name`() {
        val scan = LocalFolderSource(tree()).scan(Long.MAX_VALUE)

        assertEquals("photos", scan.rootName)
        val paths = scan.files.map { it.relativePath }.sorted()
        assertEquals(
            listOf("photos/a.txt", "photos/sub/b.txt", "photos/sub/deep/c.bin"),
            paths
        )
        // The name is what the receiver writes; the path is what routes it into a directory.
        assertEquals("c.bin", scan.files.first { it.relativePath.endsWith("c.bin") }.name)
    }

    @Test
    fun `empty directories are kept so the tree shape survives`() {
        val scan = LocalFolderSource(tree()).scan(Long.MAX_VALUE)

        assertTrue("root missing: ${scan.dirs}", scan.dirs.contains("photos"))
        assertTrue("empty dir dropped: ${scan.dirs}", scan.dirs.contains("photos/empty"))
        assertTrue("nested dir dropped: ${scan.dirs}", scan.dirs.contains("photos/sub/deep"))
    }

    @Test
    fun `totals match the members`() {
        val scan = LocalFolderSource(tree()).scan(Long.MAX_VALUE)

        assertEquals(3, scan.files.size)
        assertEquals(7 + 7 + 2000L, scan.totalBytes)
        assertEquals(0, scan.skipped)
    }

    @Test
    fun `oversized members are skipped and counted, not silently dropped`() {
        val scan = LocalFolderSource(tree()).scan(limit = 10)

        // Both text files are 7 bytes and only the 2000-byte binary is over the cap.
        assertEquals(1, scan.skipped)
        assertEquals(listOf("photos/a.txt", "photos/sub/b.txt"), scan.files.map { it.relativePath }.sorted())
        assertTrue(scan.totalBytes < 2000)
    }

    @Test
    fun `members open lazily and read their real content`() {
        val scan = LocalFolderSource(tree()).scan(Long.MAX_VALUE)

        val a = scan.files.single { it.name == "a.txt" }
        assertEquals(7L, a.size)
        val text = a.open()!!.bufferedReader().use { it.readText() }
        assertEquals("hello-a", text)
    }

    @Test
    fun `a member whose file disappeared returns null instead of throwing`() {
        val root = tree()
        val scan = LocalFolderSource(root).scan(Long.MAX_VALUE)
        val a = scan.files.single { it.name == "a.txt" }
        File(root, "a.txt").delete()

        // A revoked grant or a file deleted mid-transfer must surface as "unreadable", never as
        // an exception escaping the send loop.
        assertEquals(null, a.open())
    }

    @Test
    fun `depth cap matches the receiver`() {
        // Wire.MAX_FOLDER_DEPTH counts the root as one level; anything at that depth is dropped
        // on both sides, so the two implementations cannot disagree.
        val root = tmp.newFolder("top")
        var dir = root
        repeat(Wire.MAX_FOLDER_DEPTH + 2) { i ->
            dir = File(dir, "d$i").apply { mkdirs() }
            File(dir, "f$i.txt").writeText("x")
        }

        val scan = LocalFolderSource(root).scan(Long.MAX_VALUE)
        val deepest = scan.files.maxOf { it.relativePath.count { c -> c == '/' } }
        assertTrue(
            "file at depth $deepest exceeds cap ${Wire.MAX_FOLDER_DEPTH}",
            deepest < Wire.MAX_FOLDER_DEPTH
        )
        assertNotNull(scan.files.firstOrNull())
    }
}
