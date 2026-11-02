using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Last-resort forensics for the failures the managed hooks cannot see.
    ///
    /// AppDomain.UnhandledException only fires for managed exceptions. A stack overflow, an
    /// access violation inside WindowsCodecs or a fail-fast kills the process outright, and with
    /// Windows Error Reporting switched off (Disabled=1 on this machine) not even the event log
    /// keeps a record - which is exactly how a crash looked like a user closing the window.
    ///
    /// So: catch the native side ourselves, and write the dump from a pre-created thread with a
    /// deep stack. A worker has to exist up front because by the time the filter runs on a
    /// stack-overflowed thread there is no stack left to start one.
    /// </summary>
    internal static class CrashGuard
    {
        private const int MaxDumps = 5;

        private sealed class CrashInfo
        {
            public uint Code;
            public IntPtr Address;
            public string? ModuleName;
            public long Offset;
        }

        private static CrashInfo? _info;
        private static readonly object Gate = new();
        private static readonly ManualResetEventSlim Trigger = new(false);
        private static readonly ManualResetEventSlim Finished = new(false);
        private static Thread? _worker;

        public static string Dir => Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ClipSync");

        /// <summary>Call once, first thing on startup. Never throws.</summary>
        public static void Install()
        {
            try
            {
                _worker = new Thread(WorkerLoop, 4 * 1024 * 1024)
                {
                    IsBackground = true,
                    Name = "crash-dump"
                };
                _worker.Start();

                SetUnhandledExceptionFilter(Filter);
            }
            catch
            {
                // Forensics must never be the reason the app fails to start.
            }
        }

        private static IntPtr Filter(IntPtr info)
        {
            try
            {
                // EXCEPTION_POINTERS { PEXCEPTION_RECORD, PCONTEXT }
                var recordPtr = Marshal.ReadIntPtr(info, 0);
                var ci = new CrashInfo
                {
                    Code = (uint)Marshal.ReadInt32(recordPtr, 0),
                    Address = Marshal.ReadIntPtr(recordPtr, 16)
                };

                var offset = 0L;
                var module = DescribeModule(ci.Address, out offset);
                ci.ModuleName = module;
                ci.Offset = offset;

                lock (Gate) _info = ci;
                Trigger.Set();

                // Give the worker a moment; a dump of a dead process is still worth the wait.
                Finished.Wait(4000);
            }
            catch
            {
                // fall through: let the process die as it was going to
            }

            // EXCEPTION_EXECUTE_HANDLER: unwind and terminate. Returning CONTINUE_SEARCH here
            // would loop forever once nobody handles it.
            return new IntPtr(1);
        }

        private static string? DescribeModule(IntPtr address, out long offset)
        {
            offset = 0;
            try
            {
                var process = Process.GetCurrentProcess();
                foreach (ProcessModule m in process.Modules)
                {
                    long start = m.BaseAddress.ToInt64();
                    long end = start + m.ModuleMemorySize;
                    long a = address.ToInt64();
                    if (a >= start && a < end)
                    {
                        offset = a - start;
                        return m.ModuleName;
                    }
                }
            }
            catch
            {
                // Modules can disappear while we walk them; the address alone still helps.
            }
            return null;
        }

        private static void WorkerLoop()
        {
            Trigger.Wait();

            CrashInfo? ci;
            lock (Gate) ci = _info;

            var code = ci?.Code ?? 0;
            var known = DescribeCode(code);

            try
            {
                Append("native", string.Format(
                    "crash code=0x{0:X8} ({1}) address=0x{2:X} module={3}+0x{4:X}",
                    code, known,
                    ci?.Address.ToInt64() ?? 0,
                    ci?.ModuleName ?? "unknown",
                    ci?.Offset ?? 0));
            }
            catch { }

            try
            {
                var path = Path.Combine(Dir, $"crash-{DateTime.Now:yyyyMMdd-HHmmss}.dmp");
                if (WriteDump(path, ci?.Address ?? IntPtr.Zero))
                    Append("native", "dump written: " + path);
                else
                    Append("native", "dump failed (dbghelp unavailable or no permission)");
            }
            catch (Exception ex)
            {
                try { Append("native", "dump threw: " + ex.Message); } catch { }
            }

            PruneDumps();
            Finished.Set();
        }

        private static string DescribeCode(uint code) => code switch
        {
            0xC0000005 => "access violation",
            0xC00000FD => "stack overflow",
            0xC0000409 => "stack buffer overrun / fail fast",
            0xC0000374 => "heap corruption",
            0x80000003 => "breakpoint",
            0xE0434352 => "managed exception (should have been logged)",
            0xE06D7363 => "C++ exception (should have been logged)",
            _ => "unknown"
        };

        private static void PruneDumps()
        {
            try
            {
                var dumps = new DirectoryInfo(Dir).GetFiles("crash-*.dmp");
                if (dumps.Length <= MaxDumps) return;
                Array.Sort(dumps, (a, b) => a.LastWriteTimeUtc.CompareTo(b.LastWriteTimeUtc));
                for (int i = 0; i < dumps.Length - MaxDumps; i++)
                {
                    try { dumps[i].Delete(); } catch { }
                }
            }
            catch { }
        }

        private static void Append(string category, string message)
        {
            Directory.CreateDirectory(Dir);
            File.AppendAllText(
                Path.Combine(Dir, "crash.log"),
                $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] {category}: {message}{Environment.NewLine}");
        }

        // ---- MiniDumpWriteDump -------------------------------------------------------------

        private static bool WriteDump(string path, IntPtr faultAddress)
        {
            using var process = Process.GetCurrentProcess();
            using var file = new FileStream(path, FileMode.Create, FileAccess.Write, FileShare.None);

            // The filter does not get the faulting context here, so no MINIDUMP_EXCEPTION_INFORMATION:
            // MiniDumpWithThreadInfo still writes every thread's stack, which is what identifies
            // the recursive frame. WithIndirectlyReferencedMemory keeps string/buffer data.
            const uint type = 0x1000 /*WithThreadInfo*/ | 0x40 /*WithIndirectlyReferencedMemory*/;

            bool ok = MiniDumpWriteDump(
                process.Handle,
                (uint)process.Id,
                file.SafeFileHandle.DangerousGetHandle(),
                type,
                IntPtr.Zero,
                IntPtr.Zero,
                IntPtr.Zero);

            _ = faultAddress;
            return ok;
        }

        private delegate IntPtr UnhandledExceptionFilter(IntPtr exceptionInfo);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr SetUnhandledExceptionFilter(UnhandledExceptionFilter filter);

        [DllImport("dbghelp.dll", SetLastError = true)]
        private static extern bool MiniDumpWriteDump(
            IntPtr hProcess,
            uint processId,
            IntPtr hFile,
            uint dumpType,
            IntPtr exceptionParam,
            IntPtr userStreamParam,
            IntPtr callbackParam);

        // ---- runtime watch ----------------------------------------------------------------

        /// <summary>
        /// Samples the process every few seconds into runtime-watch.log. When the app dies
        /// without a dump, the last sample says whether it was threads, handles or memory that
        /// was running away - and each entry carries a "beat" so an idle app stays quiet.
        /// </summary>
        public static void StartWatchdog()
        {
            try
            {
                var t = new Thread(() =>
                {
                    var lastThreads = -1;
                    var lastHandles = -1;
                    var quiet = 0;

                    while (true)
                    {
                        try
                        {
                            Thread.Sleep(5000);
                            using var p = Process.GetCurrentProcess();
                            p.Refresh();

                            int threads = p.Threads.Count;
                            int handles = p.HandleCount;
                            double ws = Math.Round(p.WorkingSet64 / 1048576.0, 1);
                            double priv = Math.Round(p.PrivateMemorySize64 / 1048576.0, 1);

                            // Only log when something actually changed: a stable app must not
                            // produce a line every five seconds for weeks.
                            if (threads == lastThreads && handles == lastHandles)
                            {
                                if (++quiet < 60) continue;
                                quiet = 0;
                            }
                            lastThreads = threads;
                            lastHandles = handles;

                            Append("watch", $"threads={threads} handles={handles} ws={ws}MB private={priv}MB");
                        }
                        catch
                        {
                            // sampling must never take the app down
                        }
                    }
                })
                {
                    IsBackground = true,
                    Name = "runtime-watch"
                };
                t.Start();
            }
            catch { }
        }
    }
}
