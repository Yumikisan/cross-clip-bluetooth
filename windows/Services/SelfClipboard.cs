using System;
using System.IO;
using System.Security.Cryptography;
using System.Text;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Remembers, on disk, what this app itself put on the system clipboard.
    ///
    /// The clipboard outlives the process while the in-memory "already handled" markers do not.
    /// A file received from the peer is placed on the clipboard on purpose, so that it can be
    /// pasted straight away; after a restart the watcher used to see a file sitting there, call
    /// it "the user copied something" and send the phone's own file back to the phone. Storing
    /// the fingerprint next to the settings closes that hole, and it never blocks a genuine copy
    /// of anything else because the record is dropped the moment the clipboard holds something
    /// different.
    /// </summary>
    public static class SelfClipboard
    {
        private static readonly object Gate = new();

        private static string StatePath => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "ClipSync",
            "selfclip.txt");

        public static void RecordFile(string path, long size) =>
            Write("f|" + Path.GetFileName(path) + "|" + size);

        public static void RecordText(string text) =>
            Write("t|" + text.Length + "|" + Digest(text));

        /// <summary>True while the clipboard still holds exactly the file we wrote ourselves.</summary>
        public static bool MatchesFile(string path, long size) =>
            Check("f|" + Path.GetFileName(path) + "|" + size);

        /// <summary>True while the clipboard still holds exactly the text we wrote ourselves.</summary>
        public static bool MatchesText(string text) =>
            Check("t|" + text.Length + "|" + Digest(text));

        private static bool Check(string fingerprint)
        {
            var stored = Read();
            if (stored == null) return false;
            if (string.Equals(stored, fingerprint, StringComparison.Ordinal)) return true;

            // Something else on the clipboard: our record no longer describes it.
            Clear();
            return false;
        }

        private static string? Read()
        {
            try
            {
                if (!File.Exists(StatePath)) return null;
                var text = File.ReadAllText(StatePath, Encoding.UTF8).Trim();
                return text.Length == 0 ? null : text;
            }
            catch
            {
                return null;
            }
        }

        private static void Write(string fingerprint)
        {
            lock (Gate)
            {
                try
                {
                    Directory.CreateDirectory(Path.GetDirectoryName(StatePath)!);
                    File.WriteAllText(StatePath, fingerprint, Encoding.UTF8);
                }
                catch
                {
                    // Losing the marker only costs a possible echo; never let it break clipboard flow.
                }
            }
        }

        private static void Clear()
        {
            lock (Gate)
            {
                try
                {
                    if (File.Exists(StatePath)) File.Delete(StatePath);
                }
                catch { }
            }
        }

        /// <summary>Stable across runs, unlike <see cref="string.GetHashCode()"/>.</summary>
        private static string Digest(string text)
        {
            using var sha = SHA256.Create();
            var bytes = sha.ComputeHash(Encoding.UTF8.GetBytes(text));
            return Convert.ToHexString(bytes, 0, 8);
        }
    }
}
