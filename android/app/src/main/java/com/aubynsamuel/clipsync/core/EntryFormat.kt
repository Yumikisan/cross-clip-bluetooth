package com.aubynsamuel.clipsync.core

/**
 * Automatic content classification for history entries.
 *
 * The result drives the identifier bar (colour + label) on every history row/card, and later
 * the format filters. Detection is deliberately cheap and heuristic: it runs on every visible
 * row while scrolling, so no regex is allowed to backtrack over a 100 KB paste.
 */
enum class TextFormat {
    MARKDOWN,
    CODE,
    URL,
    NUMBER,
    LETTER,
    PLAIN
}

enum class FileKind {
    IMAGE,
    VIDEO,
    AUDIO,
    DOCUMENT,
    ARCHIVE,
    PACKAGE,
    OTHER,

    /** A whole received folder; never produced by [EntryFormat.detectFile]. */
    FOLDER
}

object EntryFormat {

    /** Only the head of a paste matters for classification; the rest is scanned, not matched. */
    private const val SAMPLE_LIMIT = 4096

    private val urlRegex = Regex("""^(https?://|ftp://|www\.)\S+$""", RegexOption.IGNORE_CASE)
    private val numberRegex = Regex("""^[-+]?[\d,.\s]+$""")
    private val latinRegex = Regex("""^[A-Za-z\s]+$""")

    private val markdownRegex = Regex(
        """(^|\n)\s{0,3}(#{1,6}\s|[-*+]\s|\d+\.\s|>\s)""" +
            """|```|\[[^\]]*\]\([^)]+\)|\*\*[^*\n]+\*\*|(^|\n)\s*\|.*\|"""
    )

    private val codeRegex = Regex(
        """(^|\n)\s*(fun |def |class |import |from \w+ import|#include|const |let |var |public |private |func |package |namespace )""" +
            """|[{};]\s*$|=>|->|::|</?[a-zA-Z][\w-]*>"""
    )

    fun detectText(text: String): TextFormat {
        val s = text.trim()
        if (s.isEmpty()) return TextFormat.PLAIN
        val sample = if (s.length > SAMPLE_LIMIT) s.substring(0, SAMPLE_LIMIT) else s

        // A URL is a URL only when it is the whole paste -- "see https://x" is plain text.
        if (sample.length <= 2048 && !sample.contains(' ') && !sample.contains('\n') &&
            urlRegex.matches(sample)
        ) {
            return TextFormat.URL
        }
        if (sample.length <= 40 && numberRegex.matches(sample)) return TextFormat.NUMBER
        if (sample.length <= 200 && latinRegex.matches(sample)) return TextFormat.LETTER
        if (markdownRegex.containsMatchIn(sample)) return TextFormat.MARKDOWN
        if (codeRegex.containsMatchIn(sample)) return TextFormat.CODE
        return TextFormat.PLAIN
    }

    fun detectFile(fileName: String): FileKind {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "svg", "avif" -> FileKind.IMAGE
            "mp4", "mkv", "mov", "avi", "webm", "3gp", "flv", "ts" -> FileKind.VIDEO
            "mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "amr" -> FileKind.AUDIO
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "md", "rtf",
            "csv", "json", "xml", "html", "epub" -> FileKind.DOCUMENT
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst" -> FileKind.ARCHIVE
            "apk", "aab", "xapk", "apks", "exe", "msi", "dmg", "deb", "rpm" -> FileKind.PACKAGE
            else -> FileKind.OTHER
        }
    }

    // ---- stable keys for user-chosen colours ----
    // The custom colour map is persisted as JSON, so these strings must not change even if the
    // enums are reordered. Windows uses exactly the same keys.

    fun keyFor(format: TextFormat): String = when (format) {
        TextFormat.MARKDOWN -> "text.markdown"
        TextFormat.CODE -> "text.code"
        TextFormat.URL -> "text.url"
        TextFormat.NUMBER -> "text.number"
        TextFormat.LETTER -> "text.letter"
        TextFormat.PLAIN -> "text.plain"
    }

    fun keyFor(kind: FileKind): String = when (kind) {
        FileKind.IMAGE -> "file.image"
        FileKind.VIDEO -> "file.video"
        FileKind.AUDIO -> "file.audio"
        FileKind.DOCUMENT -> "file.document"
        FileKind.ARCHIVE -> "file.archive"
        FileKind.PACKAGE -> "file.package"
        FileKind.OTHER -> "file.other"
        FileKind.FOLDER -> "file.folder"
    }

    /** Everything the colour editor lists, in display order. */
    fun allKeys(): List<String> =
        TextFormat.entries.map { keyFor(it) } + FileKind.entries.map { keyFor(it) }

    /** Default bar colour for a key, matching the built-in palette. */
    fun defaultHexFor(key: String): String = when (key) {
        "text.markdown" -> "#7B61FF"
        "text.code" -> "#00A87E"
        "text.url" -> "#1E7BE0"
        "text.number" -> "#E07A1F"
        "text.letter" -> "#A8862D"
        "text.plain" -> "#7A8794"
        "file.image" -> "#0E9AA7"
        "file.video" -> "#3F51B5"
        "file.audio" -> "#C0269B"
        "file.document" -> "#5B7C99"
        "file.archive" -> "#8D6E3A"
        "file.package" -> "#C0392B"
        "file.folder" -> "#546E7A"
        else -> "#7A8794"
    }

    /** I18n key of the label shown in the type filter and the colour editor. */
    fun labelKeyFor(key: String): String = when (key) {
        "text.markdown" -> "fmt_markdown"
        "text.code" -> "fmt_code"
        "text.url" -> "fmt_url"
        "text.number" -> "fmt_number"
        "text.letter" -> "fmt_letter"
        "text.plain" -> "fmt_plain"
        "file.image" -> "kind_image"
        "file.video" -> "kind_video"
        "file.audio" -> "kind_audio"
        "file.document" -> "kind_document"
        "file.archive" -> "kind_archive"
        "file.package" -> "kind_package"
        "file.folder" -> "kind_folder"
        else -> "kind_other"
    }
}
