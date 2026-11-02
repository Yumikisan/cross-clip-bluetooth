package com.aubynsamuel.clipsync.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Turns "it does not work" into something a developer can act on: an environment summary the user
 * can paste, and a zip of the log files the user can choose to attach.
 *
 * Local only by design — nothing is uploaded, no server exists. Clipboard content never enters the
 * report: it can contain anything, and a bug report must not become a leak.
 */
object SupportReport {

    private const val TAG = "SupportReport"

    const val UPSTREAM_URL = "https://github.com/aubynsamuel"
    const val FORK_URL = "https://github.com/Yumikisan"

    /// Sponsorship for this fork. The upstream author has his own link (Buy Me a Coffee, bundled
    /// as a string resource); the two are shown side by side so it is never ambiguous who is being
    /// paid - the original app is his work, the changes in this build are not.
    const val FORK_SPONSOR_URL = "https://afdian.com/a/Yumikisan239AFD"
    const val ISSUES_URL = "https://github.com/aubynsamuel/clipsync-android/issues"
    const val HELP_URL = "https://github.com/aubynsamuel/clipsync-android#readme"

    /**
     * Everything a maintainer asks for first. Version + Android version + which clipboard channel
     * is active explains most reports on its own.
     */
    fun environmentInfo(
        context: Context,
        versionName: String,
        versionCode: Int,
        securityMode: String,
        category: String,
        detail: String
    ): String = buildString {
        appendLine("Cross Clip Bluetooth (CCB) — 反馈信息")
        appendLine("版本: $versionName ($versionCode)")
        appendLine("系统: Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("机型: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("语言: ${Locale.getDefault()}")
        appendLine("安全模式: $securityMode")
        if (category.isNotBlank()) appendLine("类别: $category")
        appendLine("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        if (detail.isNotBlank()) {
            appendLine()
            appendLine("问题描述:")
            appendLine(detail.trim())
        }
    }

    /** Log files this app writes: security log lives in external files, transfer log beside it. */
    private fun logFiles(context: Context): List<File> {
        val dirs = listOfNotNull(
            context.getExternalFilesDir(null),
            context.filesDir
        )
        return dirs.flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.name.endsWith(".log") }?.toList().orEmpty()
        }.filter { it.length() > 0 }
    }

    fun hasLogs(context: Context): Boolean = logFiles(context).isNotEmpty()

    /**
     * Packs the logs into a zip under cache/ so [share] can hand it to any app through the
     * existing FileProvider. Returns null when there is nothing to package or writing failed.
     */
    fun exportLogs(context: Context, versionName: String): File? {
        val logs = logFiles(context)
        if (logs.isEmpty()) return null

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val dir = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        // One bundle at a time: stale zips would pile up in the cache otherwise.
        dir.listFiles()?.forEach { it.delete() }
        val zip = File(dir, "CCB-diagnostics-$stamp.zip")

        return try {
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                logs.forEach { log ->
                    out.putNextEntry(ZipEntry(log.name))
                    log.inputStream().use { it.copyTo(out) }
                    out.closeEntry()
                }
                out.putNextEntry(ZipEntry("summary.txt"))
                out.write(
                    environmentInfo(context, versionName, 0, "-", "", "诊断包（仅本地生成，未上传）")
                        .toByteArray()
                )
                out.closeEntry()
            }
            zip
        } catch (e: Exception) {
            Log.w(TAG, "cannot write diagnostics zip", e)
            zip.delete()
            null
        }
    }

    /** Hands the zip to whichever app the user picks (mail, chat, file manager). */
    fun share(context: Context, file: File, title: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    fun copy(context: Context, text: String) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(ClipData.newPlainText("CCB report", text))
    }

    fun open(context: Context, url: String): Boolean = try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: Exception) {
        Log.w(TAG, "cannot open $url", e)
        false
    }
}
