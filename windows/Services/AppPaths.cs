using System;
using System.IO;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Every file the app writes lives under one folder.
    ///
    /// The folder was renamed from "ClipSync" to "CrossClip" so that this build and the official
    /// ClipSync can be installed side by side: sharing the folder meant the two of them overwrote
    /// each other's settings, history and pairing key.
    ///
    /// A one-time migration copies the legacy folder across on first launch, so a user upgrading
    /// from the pre-rename builds keeps their passphrase and history. The legacy folder is left
    /// untouched — the official build still owns it.
    /// </summary>
    internal static class AppPaths
    {
        /// <summary>Folder name under %APPDATA%. Never contains a path separator.</summary>
        internal const string FolderName = "CrossClip";

        private const string LegacyFolderName = "ClipSync";

        private static readonly string AppDataRoot =
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);

        /// <summary>%APPDATA%\CrossClip. Created on first access, legacy data migrated once.</summary>
        internal static string DataDir { get; } = Prepare();

        internal static string Combine(string fileName) => Path.Combine(DataDir, fileName);

        private static string Prepare()
        {
            var target = Path.Combine(AppDataRoot, FolderName);
            var legacy = Path.Combine(AppDataRoot, LegacyFolderName);

            try
            {
                // Only when the new folder is absent: a later launch must never roll the user's
                // current settings back to whatever the legacy folder happened to hold.
                if (!Directory.Exists(target) && Directory.Exists(legacy))
                {
                    CopyDirectory(legacy, target);
                }
            }
            catch
            {
                // Migration is a convenience, not a requirement. Failing it must not stop the
                // app from starting; the user simply begins with default settings.
            }

            try { Directory.CreateDirectory(target); } catch { }

            return target;
        }

        private static void CopyDirectory(string source, string destination)
        {
            Directory.CreateDirectory(destination);

            foreach (var file in Directory.GetFiles(source))
            {
                // One unreadable file (a log still held open by the official build) must not
                // abort the whole migration.
                try { File.Copy(file, Path.Combine(destination, Path.GetFileName(file)), true); }
                catch { }
            }

            foreach (var dir in Directory.GetDirectories(source))
            {
                CopyDirectory(dir, Path.Combine(destination, Path.GetFileName(dir)));
            }
        }
    }
}
