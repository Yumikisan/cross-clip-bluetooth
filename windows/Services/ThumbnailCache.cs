using System;
using System.Collections.Generic;
using System.IO;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using ClipSyncWindows.Models;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Small decoded previews for image entries.
    ///
    /// The list must never decode a full photo while scrolling: a 12 MP JPEG is ~48 MB as a
    /// WPF bitmap, and a few of those in a row is enough to make the whole window stutter.
    /// So each image is decoded once at 480 px on its long side and kept as a frozen
    /// <see cref="BitmapSource"/> keyed by entry id + file timestamp. 480 px is plenty: the
    /// preview is shown at most ~200 dp wide, i.e. ~600 device pixels at 3× scaling.
    ///
    /// Failures are remembered too — a missing or unreadable file must not be retried on every
    /// re-render.
    /// </summary>
    public static class ThumbnailCache
    {
        /// <summary>Long side of the decoded bitmap, in pixels.</summary>
        private const int LongSide = 480;

        /// <summary>Above this many bytes the file is certainly worth subsampling.</summary>
        private const long SubSampleAbove = 400 * 1024;

        /// <summary>
        /// How many previews are kept decoded. Each is a 480 px bitmap (roughly 0.9 MB once
        /// frozen), so this is the memory ceiling: 40 entries ≈ 36 MB, and scrolling back one
        /// screen past it just costs a re-decode.
        ///
        /// The cache had no bound at all: history trims itself to its own limit, but the
        /// bitmaps of the entries it dropped stayed here forever. With screenshot sync on, the
        /// phone pushes dozens of images and the app grew by every one it had ever shown.
        /// </summary>
        private const int MaxEntries = 40;

        private sealed class Slot
        {
            public long Stamp;
            public BitmapSource? Bitmap;
            /// <summary>Monotonic use counter; the smallest value is the first to go.</summary>
            public long Used;
        }

        private static readonly Dictionary<string, Slot> Cache = new();
        private static readonly HashSet<string> Failed = new();
        private static readonly object Gate = new();
        private static long _clock;

        /// <summary>True when this entry is a file that the format detector calls an image.</summary>
        public static bool IsImageEntry(ClipboardEntry entry) =>
            entry.Kind == ClipKind.File &&
            !string.IsNullOrEmpty(entry.FilePath) &&
            EntryFormat.DetectFile(entry.FileName) == FileKind.Image;

        /// <summary>Returns the cached preview, decoding it on first use. Never throws.</summary>
        public static BitmapSource? Get(ClipboardEntry entry)
        {
            if (!IsImageEntry(entry)) return null;

            string path = entry.FilePath;
            if (!File.Exists(path)) return null;

            long stamp;
            try { stamp = File.GetLastWriteTimeUtc(path).Ticks ^ new FileInfo(path).Length; }
            catch { return null; }

            lock (Gate)
            {
                if (Cache.TryGetValue(entry.Id, out var slot) && slot.Stamp == stamp)
                {
                    // Touch it: the list is scrolled top-down, so the visible window is what
                    // stays warm and the tail is what gets evicted.
                    slot.Used = ++_clock;
                    return slot.Bitmap;
                }
                if (Failed.Contains(entry.Id)) return null;
            }

            BitmapSource? bitmap = null;
            try
            {
                var info = new FileInfo(path);
                var image = new BitmapImage();
                image.BeginInit();
                // OnLoad closes the file handle immediately: the list keeps the bitmap, not a lock.
                image.CacheOption = BitmapCacheOption.OnLoad;

                // Ask the decoder to downscale while decoding, which is far cheaper than
                // decoding full size and then scaling the pixels.
                if (info.Length > SubSampleAbove)
                {
                    var frame = BitmapFrame.Create(
                        new Uri(path), BitmapCreateOptions.DelayCreation, BitmapCacheOption.None);
                    if (frame.PixelWidth >= frame.PixelHeight) image.DecodePixelWidth = LongSide;
                    else image.DecodePixelHeight = LongSide;
                }

                image.UriSource = new Uri(path);
                image.EndInit();
                image.Freeze();

                // Very small files skip the subsampling above; scale them explicitly so every
                // preview has the same weight in memory.
                bitmap = Normalise(image);
            }
            catch
            {
                lock (Gate) { Failed.Add(entry.Id); }
                return null;
            }

            lock (Gate)
            {
                Cache[entry.Id] = new Slot { Stamp = stamp, Bitmap = bitmap, Used = ++_clock };
                Evict();
            }
            return bitmap;
        }

        /// <summary>
        /// Drops the least recently used previews once the cache is over its cap. Called under
        /// [Gate]; the entries themselves stay in history, only their decoded pixels go.
        /// </summary>
        private static void Evict()
        {
            if (Cache.Count <= MaxEntries) return;

            var doomed = Cache
                .OrderBy(pair => pair.Value.Used)
                .Take(Cache.Count - MaxEntries)
                .Select(pair => pair.Key)
                .ToList();
            foreach (var key in doomed) Cache.Remove(key);

            // Same reasoning for the failure list: one dead file per received image would
            // otherwise accumulate for the life of the process.
            if (Failed.Count > MaxEntries * 4)
            {
                Failed.Clear();
            }
        }

        private static BitmapSource Normalise(BitmapSource source)
        {
            int longSide = Math.Max(source.PixelWidth, source.PixelHeight);
            if (longSide <= LongSide) return source;

            double scale = (double)LongSide / longSide;
            var scaled = new TransformedBitmap(source, new ScaleTransform(scale, scale));
            scaled.Freeze();
            return scaled;
        }

        /// <summary>Drops one entry's preview (the entry or its file is gone).</summary>
        public static void Forget(string id)
        {
            lock (Gate)
            {
                Cache.Remove(id);
                Failed.Remove(id);
            }
        }

        public static void Clear()
        {
            lock (Gate)
            {
                Cache.Clear();
                Failed.Clear();
            }
        }
    }
}
