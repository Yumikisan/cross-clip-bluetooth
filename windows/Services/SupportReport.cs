using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;
using ClipSyncWindows.Services;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Everything the settings page needs to turn "it does not work" into a report a developer can
    /// act on: a pasteable environment summary, a diagnostic bundle of the log files, and the
    /// links where those belong.
    ///
    /// Deliberately local only: nothing is uploaded. The user copies the text or picks up the zip
    /// and decides where it goes.
    /// </summary>
    public static class SupportReport
    {
        public const string UpstreamUrl = "https://github.com/aubynsamuel";
        public const string ForkUrl = "https://github.com/Yumikisan";
        public const string IssuesUrl = "https://github.com/aubynsamuel/clipsync-windows/issues";
        public const string HelpUrl = "https://github.com/aubynsamuel/clipsync-windows#readme";

        /// <summary>
        /// The two sponsor links, deliberately separate: the original app is someone else's work and
        /// the changes in this build are not. Keeping them apart is what stops "sponsor" from
        /// implying the upstream author is being paid for this fork.
        /// </summary>
        public const string UpstreamSponsorUrl = "https://www.buymeacoffee.com/aubynsamuel";
        public const string ForkSponsorUrl = "https://afdian.com/a/Yumikisan239AFD";

        /// <summary>Folder the app keeps its configuration and logs in.</summary>
        public static string DataFolder => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ClipSync");

        /// <summary>
        /// Environment block pasted at the top of every report. Kept short on purpose: the useful
        /// facts are version, OS and which security mode was active, not the full settings dump
        /// (which would carry the passphrase fingerprint).
        /// </summary>
        public static string EnvironmentInfo(string appVersion, string securityMode, string extra)
        {
            var sb = new StringBuilder();
            sb.AppendLine("Cross Clip Bluetooth (CCB) — 反馈信息");
            sb.AppendLine("版本: " + appVersion);
            sb.AppendLine("系统: " + Environment.OSVersion.VersionString + " · " + (Environment.Is64BitProcess ? "x64" : "x86"));
            sb.AppendLine("语言: " + System.Globalization.CultureInfo.CurrentUICulture.Name);
            sb.AppendLine("安全模式: " + securityMode);
            sb.AppendLine(".NET: " + Environment.Version);
            sb.AppendLine("时间: " + DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss"));
            if (!string.IsNullOrWhiteSpace(extra)) sb.AppendLine("补充: " + extra.Trim());
            return sb.ToString();
        }

        /// <summary>
        /// Packs the log files into a zip next to the executable's data folder. Returns the zip
        /// path, or null when there is nothing to pack.
        ///
        /// Logs are rotated and capped by the app itself, so the bundle stays small; a user who
        /// has run the app for months still produces a couple of hundred kilobytes.
        /// </summary>
        /// <summary>True when there is at least one non-empty log file to package.</summary>
        public static bool HasLogs()
        {
            try
            {
                return Directory.Exists(DataFolder) &&
                       Directory.GetFiles(DataFolder, "*.log").Any(f => new FileInfo(f).Length > 0);
            }
            catch { return false; }
        }

        public static string? ExportLogs(string appVersion)
        {
            var folder = DataFolder;
            if (!Directory.Exists(folder)) return null;

            var logs = Directory.GetFiles(folder, "*.log", SearchOption.TopDirectoryOnly)
                .Where(f => new FileInfo(f).Length > 0)
                .ToList();
            if (logs.Count == 0) return null;

            var stamp = DateTime.Now.ToString("yyyyMMdd-HHmmss");
            var desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
            var target = Path.Combine(desktop, $"CCB-diagnostics-{stamp}.zip");

            try
            {
                using var zip = ZipFile.Open(target, ZipArchiveMode.Create);
                foreach (var log in logs)
                {
                    // Copy first: the app holds the live log open for append.
                    var entry = zip.CreateEntry(Path.GetFileName(log), CompressionLevel.Optimal);
                    using var output = entry.Open();
                    using var input = new FileStream(log, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                    input.CopyTo(output);
                }

                var summary = zip.CreateEntry("summary.txt", CompressionLevel.NoCompression);
                using (var writer = new StreamWriter(summary.Open(), Encoding.UTF8))
                {
                    writer.WriteLine(EnvironmentInfo(appVersion, "-", "诊断包（仅本地生成，未上传）"));
                    writer.WriteLine();
                    writer.WriteLine("包含文件: " + string.Join(", ", logs.Select(Path.GetFileName)));
                }

                return target;
            }
            catch (Exception)
            {
                // A locked file or a read-only desktop must not surface as a crash: the caller
                // reports "export failed" and the user can still copy the text report.
                try { if (File.Exists(target)) File.Delete(target); } catch { }
                return null;
            }
        }

        public static bool OpenUrl(string url)
        {
            try
            {
                Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
                return true;
            }
            catch { return false; }
        }

        public static void Copy(string text)
        {
            try { System.Windows.Clipboard.SetText(text); } catch { }
        }

        /// <summary>Last few lines of a log, for the "attach some context" hint in the UI.</summary>
        public static string Tail(string fileName, int lines)
        {
            try
            {
                var path = Path.Combine(DataFolder, fileName);
                if (!File.Exists(path)) return "";
                using var fs = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite);
                using var reader = new StreamReader(fs, Encoding.UTF8);
                var all = reader.ReadToEnd().Split('\n');
                return string.Join("\n", all.Skip(Math.Max(0, all.Length - lines - 1)).Take(lines));
            }
            catch { return ""; }
        }
    }
}
