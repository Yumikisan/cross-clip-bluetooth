using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using ClipSyncWindows.Models;

namespace ClipSyncWindows.Services
{
    /// <summary>One row of the storage statistics table (type / count / size).</summary>
    public class StorageStat
    {
        /// <summary>Raw bucket key ("kind.text", "kind.image", ...) used for per-type deletes.</summary>
        public string KindKey { get; set; } = "";

        public string Label { get; set; } = "";
        public int Count { get; set; }
        public string SizeText { get; set; } = "";

        /// <summary>Localised "delete all of this type", for the row button's tooltip.</summary>
        public string DeleteHint { get; set; } = "";
    }

    /// <summary>
    /// Storage accounting + cleanup for received files. Text entries carry no payload
    /// beyond the JSON metadata, so only file entries own disk space.
    /// </summary>
    public static class StorageService
    {
        /// <summary>Wired up by MainWindow so the settings page can read the live history.</summary>
        public static HistoryService? SharedHistory { get; set; }

        private static readonly string[] ImageExt =
            { ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".heic", ".tiff", ".svg" };
        private static readonly string[] VideoExt = { ".mp4", ".mkv", ".mov", ".avi", ".webm", ".flv" };
        private static readonly string[] AudioExt = { ".mp3", ".flac", ".wav", ".ogg", ".m4a", ".aac" };
        private static readonly string[] DocExt =
            { ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".md", ".csv", ".epub" };
        private static readonly string[] ArchiveExt = { ".zip", ".rar", ".7z", ".tar", ".gz" };
        private static readonly string[] InstallerExt = { ".apk", ".exe", ".msi", ".appx" };

        /// <summary>Which bucket one entry belongs to. Shared by the table and the per-type delete
        /// so a row always deletes exactly what it counted.</summary>
        public static string KindKeyOf(ClipboardEntry e)
        {
            if (e.Kind == ClipKind.Text)
            {
                var t = (e.Text ?? "").TrimStart();
                return t.StartsWith("http://", StringComparison.OrdinalIgnoreCase)
                       || t.StartsWith("https://", StringComparison.OrdinalIgnoreCase)
                       || t.StartsWith("www.", StringComparison.OrdinalIgnoreCase)
                    ? "kind.link" : "kind.text";
            }

            var ext = Path.GetExtension(e.FileName ?? "").ToLowerInvariant();
            return
                ImageExt.Contains(ext) ? "kind.image" :
                VideoExt.Contains(ext) ? "kind.video" :
                AudioExt.Contains(ext) ? "kind.audio" :
                DocExt.Contains(ext) ? "kind.doc" :
                ArchiveExt.Contains(ext) ? "kind.archive" :
                InstallerExt.Contains(ext) ? "kind.installer" : "kind.fileother";
        }

        /// <summary>Builds the type/count/size table shown in the storage page.</summary>
        public static List<StorageStat> GetStats()
        {
            var entries = SharedHistory?.Snapshot() ?? new List<ClipboardEntry>();

            // ordered buckets: text, link, then file kinds
            var buckets = new Dictionary<string, (int Count, long Bytes)>
            {
                ["kind.text"] = (0, 0), ["kind.link"] = (0, 0),
                ["kind.image"] = (0, 0), ["kind.video"] = (0, 0), ["kind.audio"] = (0, 0),
                ["kind.doc"] = (0, 0), ["kind.archive"] = (0, 0), ["kind.installer"] = (0, 0),
                ["kind.fileother"] = (0, 0)
            };

            foreach (var e in entries)
            {
                var key = KindKeyOf(e);
                var bucket = buckets.TryGetValue(key, out var existing)
                    ? existing
                    : (Count: 0, Bytes: 0L);
                buckets[key] = (bucket.Count + 1,
                    bucket.Bytes + (e.Kind == ClipKind.Text ? 0 : Math.Max(0, e.Size)));
            }

            return buckets
                .Where(kv => kv.Value.Count > 0)
                .Select(kv => new StorageStat
                {
                    KindKey = kv.Key,
                    Label = kv.Key,
                    Count = kv.Value.Count,
                    SizeText = kv.Value.Bytes > 0 ? FormatBytes(kv.Value.Bytes) : ""
                })
                .ToList();
        }

        /// <summary>Total bytes of the files this app actually stores (received ones).</summary>
        public static long TotalBytes()
        {
            var entries = SharedHistory?.Snapshot() ?? new List<ClipboardEntry>();
            return entries
                .Where(IsOwned)
                .Where(e => File.Exists(e.FilePath))
                .Sum(e => Math.Max(0, e.Size));
        }

        /// <summary>
        /// True when the entry points at a file this app wrote, i.e. one it may delete.
        ///
        /// A history row for a file the user *copied* also carries that file's path (that is how
        /// the list can open or reveal it), so deleting every row's FilePath deleted the user's
        /// own documents: the threshold cleanup wiped the oldest ones and there was no way to get
        /// them back. Only received files are ours to remove.
        /// </summary>
        private static bool IsOwned(ClipboardEntry e) =>
            e.Kind == ClipKind.File &&
            e.Direction == ClipDirection.Incoming &&
            !string.IsNullOrEmpty(e.FilePath);

        /// <summary>Age-based cleanup: drop file entries older than their retention window.</summary>
        public static int CleanExpired(AppSettings s)
        {
            if (!s.AutoCleanExpired) return 0;
            var cutoffImage = s.ImageRetentionDays > 0 ? DateTimeOffset.Now.AddDays(-s.ImageRetentionDays) : DateTimeOffset.MaxValue;
            var cutoffFile = s.FileRetentionDays > 0 ? DateTimeOffset.Now.AddDays(-s.FileRetentionDays) : DateTimeOffset.MaxValue;

            var victims = (SharedHistory?.Snapshot() ?? new List<ClipboardEntry>())
                .Where(e => e.Kind == ClipKind.File)
                .Where(e =>
                {
                    var isImage = ImageExt.Contains(Path.GetExtension(e.FileName ?? "").ToLowerInvariant());
                    var cutoff = isImage ? cutoffImage : cutoffFile;
                    return e.Timestamp < cutoff;
                })
                .ToList();
            return RemoveEntries(victims);
        }

        /// <summary>
        /// Threshold cleanup: when stored bytes exceed MaxStorageMb, delete oldest file
        /// entries until usage falls CleanupPercent below the threshold.
        /// </summary>
        public static int CleanThreshold(AppSettings s)
        {
            if (!s.ThresholdCleanupEnabled || s.MaxStorageMb <= 0) return 0;
            var max = (long)s.MaxStorageMb * 1024 * 1024;
            var floor = max * Math.Clamp(100 - Math.Clamp(s.CleanupPercent, 1, 90), 10, 99) / 100;

            // Only the files we stored count towards the budget; a copied-from-Explorer file
            // lives on the user's disk and must not push the app into "clean up now" mode.
            var files = (SharedHistory?.Snapshot() ?? new List<ClipboardEntry>())
                .Where(IsOwned)
                .Where(e => File.Exists(e.FilePath))
                .OrderBy(e => e.Timestamp)
                .ToList();

            long total = files.Sum(e => Math.Max(0, e.Size));
            if (total <= max) return 0;

            var victims = new List<ClipboardEntry>();
            foreach (var e in files)
            {
                if (total <= floor) break;
                victims.Add(e);
                total -= Math.Max(0, e.Size);
            }
            return RemoveEntries(victims);
        }

        /// <summary>Manual cleanup from the storage page: delete every stored file payload.</summary>
        public static int CleanAllFiles()
        {
            var victims = (SharedHistory?.Snapshot() ?? new List<ClipboardEntry>())
                .Where(e => e.Kind == ClipKind.File)
                .ToList();
            return RemoveEntries(victims);
        }

        /// <summary>Deletes every entry of one type (one row of the storage table).</summary>
        public static int CleanKind(string kindKey)
        {
            if (string.IsNullOrEmpty(kindKey)) return 0;
            var victims = (SharedHistory?.Snapshot() ?? new List<ClipboardEntry>())
                .Where(e => string.Equals(KindKeyOf(e), kindKey, StringComparison.OrdinalIgnoreCase))
                .ToList();
            return RemoveEntries(victims);
        }

        private static int RemoveEntries(IReadOnlyList<ClipboardEntry> victims)
        {
            var history = SharedHistory;
            if (history == null || victims.Count == 0) return 0;

            foreach (var e in victims)
            {
                try
                {
                    // The row always goes; the file only when this app put it there.
                    if (IsOwned(e) && File.Exists(e.FilePath))
                        File.Delete(e.FilePath);
                }
                catch
                {
                    // a locked file must not abort the whole cleanup
                }
                history.Remove(e.Id);
            }
            return victims.Count;
        }

        public static string FormatBytes(long bytes)
        {
            double b = bytes;
            string[] units = { "B", "KB", "MB", "GB" };
            int i = 0;
            while (b >= 1024 && i < units.Length - 1) { b /= 1024; i++; }
            return $"{b:0.#} {units[i]}";
        }
    }
}
