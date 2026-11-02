package com.aubynsamuel.clipsync

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.aubynsamuel.clipsync.core.ShareIntake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The system share sheet path: whatever the file manager hands us must end up as a local
 * file we own, because the uri read grant dies with the activity that received it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.S])
class ShareIntakeTest {

    private lateinit var context: Context
    private val limit = 5L * 1024 * 1024

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `plain text share is passed through untouched`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://example.com")
        }

        val prepared = ShareIntake.prepare(context, intent, limit)

        assertEquals("https://example.com", prepared.text)
        assertTrue(prepared.files.isEmpty())
        assertFalse(prepared.isEmpty)
    }

    @Test
    fun `single shared file is copied into the outbox`() {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        val uri = registerFile("content://sharetest/file/1", bytes)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        val prepared = ShareIntake.prepare(context, intent, limit)

        assertEquals(1, prepared.files.size)
        val copy = prepared.files.first()
        assertEquals(bytes.size.toLong(), copy.length())
        assertTrue("copy must live in our own cache", copy.absolutePath.contains("outbox"))
        assertTrue(copy.readBytes().contentEquals(bytes))
        assertTrue(prepared.skipped.isEmpty())

        // Whatever we send, we also clean up.
        ShareIntake.cleanup(copy)
        assertFalse(copy.exists())
    }

    @Test
    fun `multiple files are all copied`() {
        val first = registerFile("content://sharetest/file/11", ByteArray(128) { 1 })
        val second = registerFile("content://sharetest/file/12", ByteArray(256) { 2 })

        val clip = ClipData.newRawUri("", first).apply { addItem(ClipData.Item(second)) }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            clipData = clip
        }

        val prepared = ShareIntake.prepare(context, intent, limit)

        assertEquals(2, prepared.files.size)
        assertEquals(listOf(128L, 256L), prepared.files.map { it.length() }.sorted())
    }

    @Test
    fun `file above the limit is skipped without being copied`() {
        val uri = registerFile("content://sharetest/file/2", ByteArray(64 * 1024) { 3 })

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
        }

        val prepared = ShareIntake.prepare(context, intent, 1024)

        assertTrue(prepared.files.isEmpty())
        assertEquals(1, prepared.skipped.size)
        assertTrue(prepared.isEmpty)
    }

    @Test
    fun `a share with nothing usable is empty`() {
        val prepared = ShareIntake.prepare(context, Intent(Intent.ACTION_SEND), limit)

        assertTrue(prepared.isEmpty)
        assertTrue(prepared.files.isEmpty())
    }

    @Test
    fun `stale outbox folders are purged`() {
        val staleDir = File(context.cacheDir, "outbox/stale-run").apply { mkdirs() }
        val stale = File(staleDir, "file.bin").apply { writeBytes(ByteArray(8)) }
        staleDir.setLastModified(1L)

        val uri = registerFile("content://sharetest/file/3", ByteArray(16))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        ShareIntake.prepare(context, intent, limit)

        assertFalse(stale.exists())
    }

    private fun registerFile(uri: String, bytes: ByteArray): Uri {
        val parsed = Uri.parse(uri)
        shadowOf(context.contentResolver)
            .registerInputStream(parsed, ByteArrayInputStream(bytes))
        return parsed
    }
}
