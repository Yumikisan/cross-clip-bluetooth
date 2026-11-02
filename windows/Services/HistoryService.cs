using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using ClipSyncWindows.Models;
using Newtonsoft.Json;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Persistent clipboard history. Stored as a single JSON file in %APPDATA%\ClipSync.
    /// File *contents* are never persisted, only the path + metadata.
    /// </summary>
    public sealed class HistoryService
    {
        private readonly object _lock = new();
        private readonly object _writeLock = new();
        private readonly List<ClipboardEntry> _entries = new();
        private readonly string _path;
        private int _limit = 300;
        private bool _dirty;

        /// <summary>Newest snapshot waiting to be written, and whether a writer is running.</summary>
        private List<ClipboardEntry>? _pending;
        private bool _writing;

        public HistoryService(int limit = 300)
        {
            _limit = Math.Clamp(limit, 20, 2000);
            var folder = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ClipSync");
            Directory.CreateDirectory(folder);
            _path = Path.Combine(folder, "history.json");
            Load();
        }

        public event Action? Changed;

        public void SetLimit(int limit)
        {
            _limit = Math.Clamp(limit, 20, 2000);
            lock (_lock)
            {
                if (_entries.Count > _limit)
                {
                    _entries.RemoveRange(_limit, _entries.Count - _limit);
                    _dirty = true;
                }
            }
            Save();
            Changed?.Invoke();
        }

        public IReadOnlyList<ClipboardEntry> Snapshot()
        {
            lock (_lock) return _entries.ToList();
        }

        public ClipboardEntry? Add(ClipboardEntry entry)
        {
            lock (_lock)
            {
                if (entry.Kind == ClipKind.Text)
                {
                    // skip a duplicate of the most recent entry (same content, same direction)
                    var top = _entries.FirstOrDefault();
                    if (top != null && top.Kind == ClipKind.Text && top.Text == entry.Text
                        && top.Direction == entry.Direction)
                    {
                        top.Timestamp = entry.Timestamp;
                        _dirty = true;
                        return top;
                    }
                }

                _entries.Insert(0, entry);
                if (_entries.Count > _limit)
                    _entries.RemoveRange(_limit, _entries.Count - _limit);

                _dirty = true;
            }

            Save();
            Changed?.Invoke();
            return entry;
        }

        public void Remove(string id)
        {
            lock (_lock)
            {
                var item = _entries.FirstOrDefault(e => e.Id == id);
                if (item == null) return;
                _entries.Remove(item);
                _dirty = true;
            }
            // The preview belongs to the entry; dropping the record drops the bitmap too.
            ThumbnailCache.Forget(id);
            Save();
            Changed?.Invoke();
        }

        public void Clear()
        {
            lock (_lock)
            {
                _entries.Clear();
                _dirty = true;
            }
            ThumbnailCache.Clear();
            Save();
            Changed?.Invoke();
        }

        public IReadOnlyList<ClipboardEntry> Search(string query, ClipKind? kind = null,
            HistoryFilter? filter = null)
        {
            lock (_lock)
            {
                return _entries
                    // The "files" tab covers folders too: a received folder is file-shaped and
                    // hiding it there would make it look lost.
                    .Where(e => kind == null || e.Kind == kind ||
                                (kind == ClipKind.File && e.Kind == ClipKind.Folder))
                    .Where(e => e.Matches(query))
                    .Where(e => filter == null || filter.Matches(e))
                    .ToList();
            }
        }

        private void Load()
        {
            try
            {
                if (!File.Exists(_path)) return;
                var json = File.ReadAllText(_path);
                var list = JsonConvert.DeserializeObject<List<ClipboardEntry>>(json);
                if (list == null) return;
                lock (_lock)
                {
                    _entries.Clear();
                    _entries.AddRange(list.OrderByDescending(e => e.Timestamp));
                }
            }
            catch
            {
                // corrupted history should never block startup
            }
        }

        public void Save()
        {
            List<ClipboardEntry> rows;
            lock (_lock)
            {
                if (!_dirty) return;
                _dirty = false;
                // Snapshot under the lock (a reference copy, cheap) and convert off the caller's
                // thread: the file holds every entry, so serialising it grew with the history and
                // blocked whichever thread called Add - which is the clipboard tick.
                rows = new List<ClipboardEntry>(_entries);
            }

            lock (_writeLock)
            {
                _pending = rows;
                if (_writing) return;   // the running loop picks the newer snapshot up by itself
                _writing = true;
            }
            _ = Task.Run(DrainWrites);
        }

        /// <summary>
        /// Writes the newest snapshot, then keeps going while newer ones arrive. Collapsing them
        /// this way means a burst of copies costs one write, not one per copy.
        /// </summary>
        private void DrainWrites()
        {
            while (true)
            {
                List<ClipboardEntry> rows;
                lock (_writeLock)
                {
                    if (_pending == null)
                    {
                        _writing = false;
                        return;
                    }
                    rows = _pending;
                    _pending = null;
                }

                try
                {
                    var json = JsonConvert.SerializeObject(rows, Formatting.None);
                    File.WriteAllText(_path, json);
                }
                catch
                {
                    // Leave the data marked dirty so the next change retries; a failed write must
                    // not lose the history or take the caller down with it.
                    lock (_lock) _dirty = true;
                }
            }
        }

        /// <summary>Flushes pending changes; called from the app shutdown path.</summary>
        public void Flush()
        {
            Save();

            // Give the background writer a moment to land the last snapshot. Bounded: refusing to
            // exit is worse than losing the tail of a clipboard history.
            var deadline = Environment.TickCount64 + 2000;
            while (Environment.TickCount64 < deadline)
            {
                lock (_writeLock)
                {
                    if (_pending == null && !_writing) return;
                }
                System.Threading.Thread.Sleep(10);
            }
        }
    }
}
