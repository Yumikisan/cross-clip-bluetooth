using System;
using System.Drawing;
using System.IO;
using System.Windows.Forms;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Notification-area icon. The original build had no tray at all, so closing the window
    /// killed the service and the taskbar button was always in the way.
    /// </summary>
    public sealed class TrayService : IDisposable
    {
        private readonly NotifyIcon _icon;
        private readonly ContextMenuStrip _menu;
        private readonly ToolStripMenuItem _showItem;
        private readonly ToolStripMenuItem _toggleItem;
        private readonly ToolStripMenuItem _topmostItem;
        private readonly ToolStripMenuItem _versionItem;
        private readonly ToolStripMenuItem _exitItem;

        public TrayService()
        {
            _menu = new ContextMenuStrip();

            _showItem = new ToolStripMenuItem { Font = new Font(_menu.Font, System.Drawing.FontStyle.Bold) };
            _showItem.Click += (_, _) => ShowRequested?.Invoke();

            _toggleItem = new ToolStripMenuItem();
            _toggleItem.Click += (_, _) =>
            {
                if (IsServiceRunning) StopRequested?.Invoke();
                else StartRequested?.Invoke();
            };

            // Mirrors the window's right-click "always on top": one state, two entry points.
            _topmostItem = new ToolStripMenuItem { CheckOnClick = true };
            _topmostItem.Click += (_, _) => TopmostToggled?.Invoke(_topmostItem.Checked);

            // Version stamp: the only way to tell two builds apart from the tray.
            _versionItem = new ToolStripMenuItem { Enabled = false };

            _exitItem = new ToolStripMenuItem();
            _exitItem.Click += (_, _) => ExitRequested?.Invoke();

            _menu.Items.Add(_showItem);
            _menu.Items.Add(_toggleItem);
            _menu.Items.Add(new ToolStripSeparator());
            _menu.Items.Add(_topmostItem);
            _menu.Items.Add(new ToolStripSeparator());
            _menu.Items.Add(_versionItem);
            _menu.Items.Add(_exitItem);

            _icon = new NotifyIcon
            {
                Icon = LoadIcon(),
                Text = AppInfo.Display,
                Visible = true,
                ContextMenuStrip = _menu
            };
            _icon.DoubleClick += (_, _) => ShowRequested?.Invoke();
            _icon.MouseClick += (_, e) =>
            {
                if (e.Button == MouseButtons.Left) ShowRequested?.Invoke();
            };

            I18n.Instance.LanguageChanged += (_, _) => ApplyText();
            ApplyText();
        }

        public bool IsServiceRunning { get; set; }

        public event Action? ShowRequested;
        public event Action? StartRequested;
        public event Action? StopRequested;
        public event Action? ExitRequested;
        public event Action<bool>? TopmostToggled;

        /// <summary>Reflects the window's real topmost state without re-raising the event.</summary>
        public void SetTopmost(bool on)
        {
            if (_topmostItem.Checked == on) return;
            _topmostItem.Checked = on;
        }

        private static Icon LoadIcon()
        {
            // 1) Embedded WPF resource — works for both normal and single-file builds.
            try
            {
                var info = System.Windows.Application.GetResourceStream(
                    new Uri("pack://application:,,,/Assets/app.ico"));
                if (info?.Stream != null)
                {
                    using var stream = info.Stream;
                    return new Icon(stream);
                }
            }
            catch { }

            // 2) Loose file shipped next to the exe (framework-dependent build).
            try
            {
                var path = Path.Combine(AppContext.BaseDirectory, "Assets", "app.ico");
                if (File.Exists(path)) return new Icon(path);
            }
            catch { }

            // 3) Icon embedded into the PE image itself.
            try
            {
                var exe = Environment.ProcessPath;
                if (!string.IsNullOrEmpty(exe) && File.Exists(exe))
                {
                    var extracted = Icon.ExtractAssociatedIcon(exe);
                    if (extracted != null) return extracted;
                }
            }
            catch { }

            return SystemIcons.Application;
        }

        public void ApplyText()
        {
            _showItem.Text = Loc.T("dlg.trayShow");
            _toggleItem.Text = IsServiceRunning ? Loc.T("dlg.trayStop") : Loc.T("dlg.trayStart");
            _topmostItem.Text = Loc.T("lbl.alwaysOnTop");
            _exitItem.Text = Loc.T("dlg.trayExit");
            _versionItem.Text = AppInfo.Display;
        }

        public void SetServiceState(bool running, string? detail = null)
        {
            IsServiceRunning = running;
            ApplyText();

            var text = running ? Loc.T("status.listening") : Loc.T("status.stopped");
            if (!string.IsNullOrWhiteSpace(detail)) text = detail!;
            if (text.Length > 63) text = text[..60] + "…";
            _icon.Text = AppInfo.Display + " — " + text;
        }

        public void ShowBalloon(string title, string message)
        {
            try
            {
                _icon.BalloonTipTitle = title;
                _icon.BalloonTipText = message.Length > 250 ? message[..250] + "…" : message;
                _icon.ShowBalloonTip(3000);
            }
            catch { }
        }

        public void Dispose()
        {
            _icon.Visible = false;
            _icon.Dispose();
            _menu.Dispose();
        }
    }
}
