using System;
using System.Text.RegularExpressions;
using System.Windows.Media;

namespace ClipSyncWindows.Models
{
    /// <summary>Content class of a text entry, drives the identifier bar colour.</summary>
    public enum TextFormat
    {
        Markdown,
        Code,
        Url,
        Number,
        Letter,
        Plain
    }

    /// <summary>Content class of a file entry, drives the identifier bar colour.</summary>
    public enum FileKind
    {
        Image,
        Video,
        Audio,
        Document,
        Archive,
        Package,
        Other,

        /// <summary>A whole received folder; never produced by DetectFile.</summary>
        Folder
    }

    /// <summary>
    /// Heuristic classification of history entries. Runs while the list is being built, so
    /// every regex is anchored or bounded and the sample is capped.
    /// </summary>
    public static class EntryFormat
    {
        private const int SampleLimit = 4096;

        private static readonly Regex UrlRegex =
            new Regex(@"^(https?://|ftp://|www\.)\S+$", RegexOptions.IgnoreCase | RegexOptions.Compiled);

        private static readonly Regex NumberRegex =
            new Regex(@"^[-+]?[\d,.\s]+$", RegexOptions.Compiled);

        private static readonly Regex LatinRegex =
            new Regex(@"^[A-Za-z\s]+$", RegexOptions.Compiled);

        private static readonly Regex MarkdownRegex = new Regex(
            @"(^|\n)\s{0,3}(#{1,6}\s|[-*+]\s|\d+\.\s|>\s)" +
            @"|```|\[[^\]]*\]\([^)]+\)|\*\*[^*\n]+\*\*|(^|\n)\s*\|.*\|",
            RegexOptions.Compiled);

        private static readonly Regex CodeRegex = new Regex(
            @"(^|\n)\s*(fun |def |class |import |from \w+ import|#include|const |let |var |public |private |func |package |namespace )" +
            @"|[{};]\s*$|=>|->|::|</?[a-zA-Z][\w-]*>",
            RegexOptions.Compiled);

        public static TextFormat DetectText(string text)
        {
            var s = (text ?? "").Trim();
            if (s.Length == 0) return TextFormat.Plain;

            var sample = s.Length > SampleLimit ? s.Substring(0, SampleLimit) : s;

            if (sample.Length <= 2048 && sample.IndexOf(' ') < 0 && sample.IndexOf('\n') < 0
                && UrlRegex.IsMatch(sample))
                return TextFormat.Url;

            if (sample.Length <= 40 && NumberRegex.IsMatch(sample)) return TextFormat.Number;
            if (sample.Length <= 200 && LatinRegex.IsMatch(sample)) return TextFormat.Letter;
            if (MarkdownRegex.IsMatch(sample)) return TextFormat.Markdown;
            if (CodeRegex.IsMatch(sample)) return TextFormat.Code;
            return TextFormat.Plain;
        }

        public static FileKind DetectFile(string fileName)
        {
            var name = fileName ?? "";
            var dot = name.LastIndexOf('.');
            var ext = dot >= 0 && dot < name.Length - 1 ? name.Substring(dot + 1).ToLowerInvariant() : "";

            switch (ext)
            {
                case "png": case "jpg": case "jpeg": case "gif": case "webp":
                case "bmp": case "heic": case "svg": case "avif":
                    return FileKind.Image;
                case "mp4": case "mkv": case "mov": case "avi": case "webm":
                case "3gp": case "flv": case "ts":
                    return FileKind.Video;
                case "mp3": case "wav": case "flac": case "aac": case "ogg":
                case "m4a": case "opus": case "amr":
                    return FileKind.Audio;
                case "pdf": case "doc": case "docx": case "xls": case "xlsx": case "ppt":
                case "pptx": case "txt": case "md": case "rtf": case "csv": case "json":
                case "xml": case "html": case "epub":
                    return FileKind.Document;
                case "zip": case "rar": case "7z": case "tar": case "gz":
                case "bz2": case "xz": case "zst":
                    return FileKind.Archive;
                case "apk": case "aab": case "xapk": case "apks": case "exe":
                case "msi": case "dmg": case "deb": case "rpm":
                    return FileKind.Package;
                default:
                    return FileKind.Other;
            }
        }

        // ---------------------------------------------------------------- look & feel

        private static SolidColorBrush Frozen(byte r, byte g, byte b)
        {
            var brush = new SolidColorBrush(Color.FromRgb(r, g, b));
            brush.Freeze();
            return brush;
        }

        // Text formats and file kinds share one wheel, so neighbouring hues must stay far
        // apart: the old pink "image" sat right next to the red "package" bar and the two were
        // indistinguishable at a glance. Keep this table in sync with the Android build.
        private static readonly Brush MarkdownBrush = Frozen(0x7B, 0x61, 0xFF);
        private static readonly Brush CodeBrush = Frozen(0x00, 0xA8, 0x7E);
        private static readonly Brush UrlBrush = Frozen(0x1E, 0x7B, 0xE0);
        private static readonly Brush NumberBrush = Frozen(0xE0, 0x7A, 0x1F);
        private static readonly Brush LetterBrush = Frozen(0xA8, 0x86, 0x2D);
        private static readonly Brush PlainBrush = Frozen(0x7A, 0x87, 0x94);

        private static readonly Brush ImageBrush = Frozen(0x0E, 0x9A, 0xA7);
        private static readonly Brush VideoBrush = Frozen(0x3F, 0x51, 0xB5);
        private static readonly Brush AudioBrush = Frozen(0xC0, 0x26, 0x9B);
        private static readonly Brush DocumentBrush = Frozen(0x5B, 0x7C, 0x99);
        private static readonly Brush ArchiveBrush = Frozen(0x8D, 0x6E, 0x3A);
        private static readonly Brush PackageBrush = Frozen(0xC0, 0x39, 0x2B);
        private static readonly Brush FolderBrush = Frozen(0x54, 0x6E, 0x7A);

        public static Brush AccentFor(TextFormat format)
        {
            switch (format)
            {
                case TextFormat.Markdown: return MarkdownBrush;
                case TextFormat.Code: return CodeBrush;
                case TextFormat.Url: return UrlBrush;
                case TextFormat.Number: return NumberBrush;
                case TextFormat.Letter: return LetterBrush;
                default: return PlainBrush;
            }
        }

        public static Brush AccentFor(FileKind kind)
        {
            switch (kind)
            {
                case FileKind.Image: return ImageBrush;
                case FileKind.Video: return VideoBrush;
                case FileKind.Audio: return AudioBrush;
                case FileKind.Document: return DocumentBrush;
                case FileKind.Archive: return ArchiveBrush;
                case FileKind.Package: return PackageBrush;
                case FileKind.Folder: return FolderBrush;
                default: return PlainBrush;
            }
        }

        public static string LabelKeyFor(TextFormat format)
        {
            switch (format)
            {
                case TextFormat.Markdown: return "fmt.markdown";
                case TextFormat.Code: return "fmt.code";
                case TextFormat.Url: return "fmt.url";
                case TextFormat.Number: return "fmt.number";
                case TextFormat.Letter: return "fmt.letter";
                default: return "fmt.plain";
            }
        }

        // Type-tile icons moved to MaterialIcons.cs: the Windows icon font only offered
        // lookalikes of what Android draws, and "almost the same" is what the user notices.

        public static string LabelKeyFor(FileKind kind)
        {
            switch (kind)
            {
                case FileKind.Image: return "kind.image.one";
                case FileKind.Video: return "kind.video.one";
                case FileKind.Audio: return "kind.audio.one";
                case FileKind.Document: return "kind.document";
                case FileKind.Archive: return "kind.archive.one";
                case FileKind.Package: return "kind.package";
                case FileKind.Folder: return "kind.folder";
                default: return "kind.other";
            }
        }

        // ---- stable keys for user-chosen colours ----
        // The custom colour map is persisted as JSON and must survive reordering of the enums,
        // so keys are strings, not enum values.

        public static string KeyFor(TextFormat format) => format switch
        {
            TextFormat.Markdown => "text.markdown",
            TextFormat.Code => "text.code",
            TextFormat.Url => "text.url",
            TextFormat.Number => "text.number",
            TextFormat.Letter => "text.letter",
            _ => "text.plain"
        };

        public static string KeyFor(FileKind kind) => kind switch
        {
            FileKind.Image => "file.image",
            FileKind.Video => "file.video",
            FileKind.Audio => "file.audio",
            FileKind.Document => "file.document",
            FileKind.Archive => "file.archive",
            FileKind.Package => "file.package",
            FileKind.Folder => "file.folder",
            _ => "file.other"
        };

        /// <summary>Everything the colour editor lists, in display order.</summary>
        public static string[] AllKeys()
        {
            var keys = new System.Collections.Generic.List<string>();
            foreach (TextFormat f in Enum.GetValues(typeof(TextFormat))) keys.Add(KeyFor(f));
            foreach (FileKind k in Enum.GetValues(typeof(FileKind))) keys.Add(KeyFor(k));
            return keys.ToArray();
        }

        /// <summary>Default accent colour for a key, as #RRGGBB.</summary>
        public static string DefaultHexFor(string key)
        {
            if (key.StartsWith("file."))
            {
                var kind = key switch
                {
                    "file.image" => FileKind.Image,
                    "file.video" => FileKind.Video,
                    "file.audio" => FileKind.Audio,
                    "file.document" => FileKind.Document,
                    "file.archive" => FileKind.Archive,
                    "file.package" => FileKind.Package,
                    "file.folder" => FileKind.Folder,
                    _ => FileKind.Other
                };
                return HexOf(AccentFor(kind));
            }

            var format = key switch
            {
                "text.markdown" => TextFormat.Markdown,
                "text.code" => TextFormat.Code,
                "text.url" => TextFormat.Url,
                "text.number" => TextFormat.Number,
                "text.letter" => TextFormat.Letter,
                _ => TextFormat.Plain
            };
            return HexOf(AccentFor(format));
        }

        /// <summary>Localisation key for a type key, reusing the labels shown in the list.</summary>
        public static string LabelKeyFor(string key)
        {
            if (key.StartsWith("file."))
            {
                var kind = key switch
                {
                    "file.image" => FileKind.Image,
                    "file.video" => FileKind.Video,
                    "file.audio" => FileKind.Audio,
                    "file.document" => FileKind.Document,
                    "file.archive" => FileKind.Archive,
                    "file.package" => FileKind.Package,
                    "file.folder" => FileKind.Folder,
                    _ => FileKind.Other
                };
                return LabelKeyFor(kind);
            }

            var format = key switch
            {
                "text.markdown" => TextFormat.Markdown,
                "text.code" => TextFormat.Code,
                "text.url" => TextFormat.Url,
                "text.number" => TextFormat.Number,
                "text.letter" => TextFormat.Letter,
                _ => TextFormat.Plain
            };
            return LabelKeyFor(format);
        }

        private static string HexOf(Brush brush) =>
            brush is SolidColorBrush solid
                ? $"#{solid.Color.R:X2}{solid.Color.G:X2}{solid.Color.B:X2}"
                : "#808080";
    }
}
