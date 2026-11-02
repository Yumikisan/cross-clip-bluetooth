using System;
using System.Collections.Generic;
using System.Linq;

namespace ClipSyncWindows.Models
{
    /// <summary>How a lower/upper bound pair is interpreted. Both bounds empty means "any".</summary>
    public enum BoundMode
    {
        Any,
        AtMost,
        AtLeast,
        Between
    }

    /// <summary>Coarse time buckets for "synced at" filtering.</summary>
    public enum TimeRange
    {
        Any,
        Last5Min,
        LastHour,
        Today,
        Yesterday,
        Custom
    }

    /// <summary>
    /// Advanced history filter. Mirrors the Android HistoryFilter one-for-one so both ends answer
    /// the same query the same way; text and file conditions stay apart on purpose, because a
    /// text condition must never hide a file entry (or the other way round).
    /// </summary>
    public sealed class HistoryFilter
    {
        // text
        public HashSet<TextFormat> TextFormats { get; } = new();
        public BoundMode TextLengthMode { get; set; } = BoundMode.Any;
        public int TextMinLength { get; set; }
        public int TextMaxLength { get; set; }
        public string Keyword { get; set; } = "";
        public bool IgnoreCase { get; set; } = true;
        public TimeRange TextTime { get; set; } = TimeRange.Any;
        public DateTime? TextCustomDay { get; set; }

        // file
        public string FileExt { get; set; } = "";
        public BoundMode FileSizeMode { get; set; } = BoundMode.Any;
        public long FileMinKb { get; set; }
        public long FileMaxKb { get; set; }
        public TimeRange FileTime { get; set; } = TimeRange.Any;
        public DateTime? FileCustomDay { get; set; }

        /// <summary>True when at least one condition is set: the bar shows a badge then.</summary>
        public bool IsActive => ActiveCount > 0;

        /// <summary>True when anything under "text filter" is set.</summary>
        public bool TextSideActive =>
            TextFormats.Count > 0 || TextLengthMode != BoundMode.Any ||
            !string.IsNullOrWhiteSpace(Keyword) || TextTime != TimeRange.Any;

        /// <summary>True when anything under "file filter" is set.</summary>
        public bool FileSideActive =>
            !string.IsNullOrWhiteSpace(FileExt) || FileSizeMode != BoundMode.Any ||
            FileTime != TimeRange.Any;

        /// <summary>Back to a pristine filter, used by the panel's Reset button.</summary>
        public void Reset()
        {
            TextFormats.Clear();
            TextLengthMode = BoundMode.Any;
            TextMinLength = 0;
            TextMaxLength = 0;
            Keyword = "";
            IgnoreCase = true;
            TextTime = TimeRange.Any;
            TextCustomDay = null;
            FileExt = "";
            FileSizeMode = BoundMode.Any;
            FileMinKb = 0;
            FileMaxKb = 0;
            FileTime = TimeRange.Any;
            FileCustomDay = null;
        }

        public int ActiveCount
        {
            get
            {
                var n = 0;
                if (TextFormats.Count > 0) n++;
                if (TextLengthMode != BoundMode.Any) n++;
                if (!string.IsNullOrWhiteSpace(Keyword)) n++;
                if (TextTime != TimeRange.Any) n++;
                if (!string.IsNullOrWhiteSpace(FileExt)) n++;
                if (FileSizeMode != BoundMode.Any) n++;
                if (FileTime != TimeRange.Any) n++;
                return n;
            }
        }

        public HistoryFilter Clone()
        {
            var copy = new HistoryFilter
            {
                TextLengthMode = TextLengthMode,
                TextMinLength = TextMinLength,
                TextMaxLength = TextMaxLength,
                Keyword = Keyword,
                IgnoreCase = IgnoreCase,
                TextTime = TextTime,
                TextCustomDay = TextCustomDay,
                FileExt = FileExt,
                FileSizeMode = FileSizeMode,
                FileMinKb = FileMinKb,
                FileMaxKb = FileMaxKb,
                FileTime = FileTime,
                FileCustomDay = FileCustomDay
            };
            foreach (var f in TextFormats) copy.TextFormats.Add(f);
            return copy;
        }

        public bool Matches(ClipboardEntry entry, DateTimeOffset? now = null)
        {
            var moment = now ?? DateTimeOffset.Now;

            // Under the "All" tab both kinds are on screen, so a one-sided filter has to hide the
            // other side instead of letting it through untouched: setting "Markdown" while files
            // are visible must hide those files, otherwise the filter looks like it did nothing.
            if (entry.Kind != ClipKind.Text)
                return FileSideActive ? MatchesFile(entry, moment) : !TextSideActive;

            return TextSideActive ? MatchesText(entry, moment) : !FileSideActive;
        }

        private bool MatchesText(ClipboardEntry entry, DateTimeOffset now)
        {
            if (TextFormats.Count > 0 && !TextFormats.Contains(EntryFormat.DetectText(entry.Text)))
                return false;

            if (!WithinBounds((entry.Text ?? "").Length, TextLengthMode, TextMinLength, TextMaxLength))
                return false;

            if (!string.IsNullOrWhiteSpace(Keyword))
            {
                var needle = Keyword.Trim();
                var haystack = entry.Text ?? "";
                if (haystack.IndexOf(needle,
                        IgnoreCase ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal) < 0)
                    return false;
            }

            return InTimeRange(entry.Timestamp, TextTime, TextCustomDay, now);
        }

        private bool MatchesFile(ClipboardEntry entry, DateTimeOffset now)
        {
            // An explicit extension wins: while it is set every other file condition is ignored,
            // otherwise "only .png" would still be narrowed down by size and date.
            if (!string.IsNullOrWhiteSpace(FileExt))
            {
                var ext = FileExt.Trim();
                if (!ext.StartsWith(".")) ext = "." + ext;
                return (entry.FileName ?? "").EndsWith(ext, StringComparison.OrdinalIgnoreCase);
            }

            if (!WithinBounds(entry.Size / 1024, FileSizeMode, FileMinKb, FileMaxKb))
                return false;

            return InTimeRange(entry.Timestamp, FileTime, FileCustomDay, now);
        }

        private static bool WithinBounds(long value, BoundMode mode, long min, long max) =>
            mode switch
            {
                BoundMode.AtMost => value <= max,
                BoundMode.AtLeast => value >= min,
                BoundMode.Between => value >= min && value <= max,
                _ => true
            };

        private static bool InTimeRange(DateTimeOffset timestamp, TimeRange range, DateTime? customDay,
            DateTimeOffset now)
        {
            if (range == TimeRange.Any) return true;

            switch (range)
            {
                case TimeRange.Last5Min:
                    return timestamp >= now.AddMinutes(-5);
                case TimeRange.LastHour:
                    return timestamp >= now.AddHours(-1);
                case TimeRange.Today:
                    return timestamp.LocalDateTime >= now.LocalDateTime.Date;
                case TimeRange.Yesterday:
                    var today = now.LocalDateTime.Date;
                    return timestamp.LocalDateTime >= today.AddDays(-1)
                        && timestamp.LocalDateTime < today;
                case TimeRange.Custom:
                    return customDay.HasValue
                        && timestamp.LocalDateTime >= customDay.Value.Date
                        && timestamp.LocalDateTime < customDay.Value.Date.AddDays(1);
                default:
                    return true;
            }
        }
    }
}
