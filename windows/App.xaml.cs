using System;
using System.Linq;
using System.Threading;
using System.Windows;
using System.Windows.Media;
using ClipSyncWindows.Services;
using ClipSyncWindows.Views;

namespace ClipSyncWindows
{
    public partial class App : Application
    {
        private Mutex? _singleInstanceMutex;
        private bool _ownsMutex;

        protected override void OnStartup(StartupEventArgs e)
        {
            base.OnStartup(e);

            // Headless verification of the layer-2 security stack (no Bluetooth, no UI):
            //   CBB-Windows.exe --selftest [reportPath]
            var testIndex = Array.IndexOf(e.Args, "--selftest");
            if (testIndex >= 0)
            {
                var outPath = testIndex + 1 < e.Args.Length ? e.Args[testIndex + 1] : null;
                Security.SecuritySelfTest.RunAndWrite(outPath);
                Shutdown();
                return;
            }

            // Crashes that never reach a managed handler (stack overflow, access violation) are
            // invisible on a machine with Windows Error Reporting turned off. Install the native
            // filter and the sampler before anything else can go wrong.
            Services.CrashGuard.Install();
            Services.CrashGuard.StartWatchdog();

            // Never die silently: log to %APPDATA%\ClipSync\error.log and tell the user.
            DispatcherUnhandledException += (_, args) =>
            {
                ReportFatal(args.Exception);
                args.Handled = true;
            };
            AppDomain.CurrentDomain.UnhandledException += (_, args) =>
                ReportFatal(args.ExceptionObject as Exception);
            System.Threading.Tasks.TaskScheduler.UnobservedTaskException += (_, args) =>
            {
                // Log only. A background task nobody awaited (a dial that timed out, a file
                // read that was abandoned) is not a reason to interrupt the user with a modal
                // that looks like a crash -- it reads as "ClipSync 遇到错误" for something the
                // app already recovered from.
                AppLog.Write("task", "unobserved: " + args.Exception);
                args.SetObserved();
            };

            // Only one instance may own the RFCOMM listener on this machine. The name is
            // deliberately unchanged from the pre-rename builds: an old and a new instance must
            // not both hold the listener.
            _singleInstanceMutex = new Mutex(true, @"Local\ClipSync.SingleInstance", out var isFirst);
            _ownsMutex = isFirst;
            if (!isFirst)
            {
                MessageBox.Show(
                    I18n.Instance.IsChinese
                        ? "Cross Clip 已经在运行，请查看系统托盘。"
                        : "Cross Clip is already running — check the notification area.",
                    "Cross Clip", MessageBoxButton.OK, MessageBoxImage.Information);
                Shutdown();
                return;
            }

            // Force ideal text rendering
            TextOptions.TextFormattingModeProperty.OverrideMetadata(
                typeof(Window),
                new FrameworkPropertyMetadata(TextFormattingMode.Ideal));

            TextOptions.TextRenderingModeProperty.OverrideMetadata(
                typeof(Window),
                new FrameworkPropertyMetadata(TextRenderingMode.ClearType));

            RenderOptions.ProcessRenderMode = System.Windows.Interop.RenderMode.Default;

            var startMinimized = e.Args.Any(a =>
                a.Equals("--minimized", StringComparison.OrdinalIgnoreCase)
                || a.Equals("-m", StringComparison.OrdinalIgnoreCase));

            // The tray owns the app lifetime, so closing the window must not exit.
            ShutdownMode = ShutdownMode.OnExplicitShutdown;

            var settings = SettingsService.Current;
            I18n.Instance.Language = settings.Language;

            MainWindow window;
            try
            {
                window = new MainWindow(startMinimized, autoStartService: true);
            }
            catch (Exception ex)
            {
                ReportFatal(ex);
                Shutdown(1);
                return;
            }

            MainWindow = window;

            var tray = new TrayService();
            window.AttachTray(tray);

            if (!startMinimized && !settings.StartMinimized)
                window.Show();
        }

        private static void ReportFatal(Exception? ex)
        {
            if (ex == null) return;

            try
            {
                var dir = System.IO.Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ClipSync");
                System.IO.Directory.CreateDirectory(dir);
                System.IO.File.AppendAllText(
                    System.IO.Path.Combine(dir, "error.log"),
                    $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {ex}{Environment.NewLine}{Environment.NewLine}");
            }
            catch { }

            try
            {
                var head = I18n.Instance.IsChinese ? "Cross Clip 遇到错误：" : "Cross Clip hit an error:";
                MessageBox.Show(
                    head + Environment.NewLine + ex.Message,
                    "Cross Clip", MessageBoxButton.OK, MessageBoxImage.Error);
            }
            catch { }
        }

        protected override void OnExit(ExitEventArgs e)
        {
            // Releasing a mutex this instance never owned throws, and that exception used to be
            // written to error.log on every normal exit — it hid real crashes.
            if (_ownsMutex)
            {
                try { _singleInstanceMutex?.ReleaseMutex(); }
                catch { }
                _ownsMutex = false;
            }

            _singleInstanceMutex?.Dispose();
            base.OnExit(e);
        }
    }
}
