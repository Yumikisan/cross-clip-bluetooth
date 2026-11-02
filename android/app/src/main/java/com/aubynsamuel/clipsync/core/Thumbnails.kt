package com.aubynsamuel.clipsync.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Preview thumbnails for image entries.
 *
 * Why a cache at all: the history list must never decode a full-size photo while scrolling. A
 * phone screenshot is ~1080×2400 and a camera JPEG is 12 MP — decoding those on the fly allocates
 * tens of megabytes per row. So a small WebP is generated once, stored under `filesDir/thumbs`,
 * and afterwards only that file is read. The in-memory layer on top keeps the hot rows cheap.
 *
 * The long side is 480 px: the list shows the thumbnail at ~4:3 across the entry width
 * (≤ 200 dp on a phone, ~600 px at 3×), so 480 is enough to stay sharp without paying for pixels
 * nobody sees. The **aspect ratio is not baked in** — the view crops to 4:3 with
 * [androidx.compose.ui.layout.ContentScale.Crop], which keeps one file usable in both the
 * single-column and the two-column layout.
 */
object Thumbnails {

    /** Long side of the stored file, in pixels. */
    private const val LONG_SIDE = 480

    /** WebP quality; visually clean at thumbnail size and roughly a third of JPEG's bytes. */
    private const val QUALITY = 80

    /** 16 MB of decoded bitmaps — about 20 entries at 480 px. */
    private val memory = object : LruCache<String, Bitmap>(16 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun dir(context: Context): File = File(context.filesDir, "thumbs")

    private fun fileFor(context: Context, id: String): File = File(dir(context), "$id.webp")

    /** True when this entry is worth a thumbnail: a file entry that decodes as an image. */
    fun isThumbSource(entry: HistoryEntry): Boolean =
        entry.kind == HistoryKind.FILE &&
            entry.uri.isNotBlank() &&
            EntryFormat.detectFile(entry.fileName) == FileKind.IMAGE

    /** Memory, then disk. Never touches the original file, so it is safe on the UI thread. */
    fun cached(context: Context, entry: HistoryEntry): Bitmap? {
        memory.get(entry.id)?.let { return it }
        val file = fileFor(context, entry.id)
        if (!file.exists() || file.length() == 0L) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null
        memory.put(entry.id, bitmap)
        return bitmap
    }

    /** Generates the thumbnail if needed and returns it; null when the source is unreadable. */
    suspend fun load(context: Context, entry: HistoryEntry): Bitmap? = withContext(Dispatchers.IO) {
        cached(context, entry)?.let { return@withContext it }
        if (!isThumbSource(entry)) return@withContext null

        val bitmap = runCatching { decode(context, entry.uri) }.getOrNull()
            ?: return@withContext null

        runCatching {
            val file = fileFor(context, entry.id)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                @Suppress("DEPRECATION")
                bitmap.compress(Bitmap.CompressFormat.WEBP, QUALITY, out)
            }
        }

        memory.put(entry.id, bitmap)
        bitmap
    }

    /** Drops one entry's thumbnail, called when the payload itself is deleted. */
    fun forget(context: Context, id: String) {
        memory.remove(id)
        runCatching { fileFor(context, id).delete() }
    }

    fun clear(context: Context) {
        memory.evictAll()
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }

    fun sizeBytes(context: Context): Long =
        runCatching { dir(context).listFiles()?.sumOf { it.length() } ?: 0L }.getOrDefault(0L)

    // ------------------------------------------------------------------ decoding

    private fun decode(context: Context, uriString: String): Bitmap? {
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver

        // Pass 1: dimensions only, so the decode below can subsample instead of allocating the
        // full bitmap. `inJustDecodeBounds` returns without allocating anything.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_SIDE) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = runCatching {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        }.getOrNull() ?: return null

        val scaled = scaleToLongSide(decoded)
        return rotateIfNeeded(context, uri, scaled)
    }

    private fun scaleToLongSide(source: Bitmap): Bitmap {
        val long = maxOf(source.width, source.height)
        if (long <= LONG_SIDE) return source
        val factor = LONG_SIDE.toFloat() / long
        val scaled = Bitmap.createScaledBitmap(
            source,
            (source.width * factor).toInt().coerceAtLeast(1),
            (source.height * factor).toInt().coerceAtLeast(1),
            true
        )
        if (scaled !== source) source.recycle()
        return scaled
    }

    /**
     * Applies the EXIF orientation. Photos sent from the computer keep theirs, so without this a
     * portrait shot shows up lying on its side — a thumbnail that lies about the file is worse
     * than no thumbnail.
     */
    private fun rotateIfNeeded(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val degrees = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                when (ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        }.getOrDefault(0f)

        if (degrees == 0f) return bitmap
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** Kept for the "how much does this cost me" line in storage management. */
    fun describeSize(context: Context): String =
        ReceivedFileSink.formatSize(sizeBytes(context))
}
