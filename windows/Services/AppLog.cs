using System;
using System.IO;
using System.Text;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Tiny append-only log used for transport diagnostics.
    ///
    /// Kept deliberately simple: transfers are the one area where "nothing happened" gives
    /// the user nothing to report, so every link event lands in
    /// <c>%APPDATA%\ClipSync\transfer.log</c> where it can be read or sent along with a bug
    /// report. Files are capped so the log can never grow without bound.
    /// </summary>
    public static class AppLog
    {
        private const long MaxBytes = 512 * 1024;
        private static readonly object Gate = new();

        public static string FilePath => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
            "ClipSync",
            "transfer.log");

        public static void Write(string category, string message)
        {
            try
            {
                lock (Gate)
                {
                    var path = FilePath;
                    Directory.CreateDirectory(Path.GetDirectoryName(path)!);

                    var info = new FileInfo(path);
                    if (info.Exists && info.Length > MaxBytes)
                    {
                        // Rotate once, keep the previous run for comparison.
                        var backup = path + ".1";
                        try
                        {
                            if (File.Exists(backup)) File.Delete(backup);
                            File.Move(path, backup);
                        }
                        catch { }
                    }

                    var line = $"[{DateTime.Now:HH:mm:ss.fff}] {category}: {message}{Environment.NewLine}";
                    File.AppendAllText(path, line, Encoding.UTF8);
                }
            }
            catch
            {
                // Logging must never take the app down.
            }
        }
    }
}
