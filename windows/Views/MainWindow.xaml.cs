using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;
using InTheHand.Net;
using InTheHand.Net.Sockets;
using ClipSyncWindows.Models;
using ClipSyncWindows.Services;
using Microsoft.Win32;

namespace ClipSyncWindows.Views
{
    public partial class MainWindow : Window
    {
        private AppSettings _settings;
        private readonly HistoryService _history;
        private readonly BluetoothLinkManager _links;
        private readonly HotkeyService _hotkey = new();
        private TrayService? _tray;
        private bool _sendingFiles;
        /// <summary>
        /// Set when a file is queued while the pump is running. The pump checks it once more
        /// before it stops, which is what stops an item from being left behind when it lands
        /// in the moment between "queue looks empty" and "pump exits".
        /// </summary>
        private bool _filePumpRequested;

        /// <summary>Clipboard files waiting to be sent, one at a time.</summary>
        private readonly Queue<(string Path, bool KeepOnlyIfSent)> _clipboardFileQueue = new();

        /// <summary>True while that queue is being drained.</summary>
        private bool _pumpingClipboardFiles;

        /// <summary>
        /// Path currently being uploaded. The queue is empty while a send runs, so "is it already
        /// queued" misses the one in flight - and the watcher ticks twice a second, which sent the
        /// same file twice back to back.
        /// </summary>
        private string? _clipboardFileSending;

        /// <summary>How many times the current clipboard file has been tried and failed.</summary>
        private int _clipboardFileAttempts;

        /// <summary>When that last attempt failed, for the retry cooldown.</summary>
        private DateTime _clipboardFileLastFailure = DateTime.MinValue;

        /// <summary>Clipboard text that at least one selected device did not receive yet.</summary>
        private string? _pendingClipText;

        /// <summary>Address keys still waiting for that text; retried when their link is up.</summary>
        private readonly HashSet<string> _pendingClipTargets = new();

        /// <summary>Every paired device, in pairing order. Selection state lives on the rows.</summary>
        private readonly ObservableCollection<DeviceViewModel> _devices = new();

        /// <summary>Same rows, split across the three sections the UI shows.</summary>
        private readonly ObservableCollection<DeviceViewModel> _primaryDevices = new();
        private readonly ObservableCollection<DeviceViewModel> _trustedDevices = new();
        private readonly ObservableCollection<DeviceViewModel> _hiddenDevices = new();
        private readonly ObservableCollection<ClipboardEntry> _visibleHistory = new();

        private System.Windows.Threading.DispatcherTimer? _clipboardTimer;
        private string? _lastClipboardText;

        /// <summary>Coalesces keystrokes in the search box into one refresh.</summary>
        private readonly System.Windows.Threading.DispatcherTimer _searchDebounce = new()
        {
            Interval = TimeSpan.FromMilliseconds(180)
        };

        /// <summary>
        /// Records <paramref name="text"/> as already handled so the watcher will not forward it.
        /// The security wizard copies a generated key to the clipboard; that key must never travel
        /// over the link it protects.
        /// </summary>
        public void SuppressClipboardEcho(string text)
        {
            if (string.IsNullOrEmpty(text)) return;
            _lastClipboardText = text;
            _lastClipboardFilePath = null;
            _lastClipboardImageHash = null;
        }

        private string? _lastClipboardFilePath;
        private string? _lastClipboardImageHash;
        private readonly HashSet<string> _receivedFilePaths = new(StringComparer.OrdinalIgnoreCase);

        /// <summary>
        /// "name|size" of files that arrived from a peer, with the time they landed.
        ///
        /// [_receivedFilePaths] only knows the path we wrote to, and the clipboard path is not
        /// always the same string (the peer's name may gain a "(1)"), so it could not stop the
        /// echo: a received file went onto the clipboard and straight back out.
        /// </summary>
        private readonly Dictionary<string, DateTime> _recentReceivedFiles =
            new(StringComparer.OrdinalIgnoreCase);
        private bool _suppressClipboardSync;
        private bool _reallyClosing;
        private readonly List<string> _pickedFiles = new();

        /// <summary>Files waiting their turn; the pump sends them one at a time.</summary>
        private readonly Queue<string> _fileQueue = new();
        private bool _trayHintShown;
        private int _startRetryCount;

        /// <summary>Applied advanced filter (the panel edits a copy of it).</summary>
        private readonly HistoryFilter _historyFilter = new();

        /// <summary>Keeps the drop hint alive between drag-over pulses; see the drag handlers.</summary>
        private readonly System.Windows.Threading.DispatcherTimer _dropHintTimer;
        private bool _dropHintUp;

        /// <summary>Confirmation requests waiting for their turn in front of the user.</summary>
        private readonly Queue<(string Name, string Code, string Sid)> _authQueue = new();
        private ConfirmCodeWindow? _authWindow;

        public MainWindow(bool startMinimized = false, bool autoStartService = false)
        {
            InitializeComponent();

            var viewModel = new ViewModels.MainViewModel();
            DataContext = viewModel;

            _settings = SettingsService.Current;
            I18n.Instance.Language = _settings.Language;
            ThemeManager.Instance.CurrentTheme = _settings.Theme;
            RestoreWindowPlacement();

            _history = new HistoryService(_settings.HistoryLimit);
            _history.Changed += () => Dispatcher.Invoke(() => RefreshHistory());
            Services.StorageService.SharedHistory = _history;

            _links = new BluetoothLinkManager(_settings);
            _links.TextReceived += OnTextReceived;
            _links.FileReceived += OnFileReceived;
            _links.FolderStarted += OnFolderStarted;
            _links.FolderCompleted += OnFolderCompleted;
            _links.ConnectionChanged += OnConnectionChanged;
            _links.TransferProgress += OnTransferProgress;
            _links.IncomingStarted += OnIncomingStarted;
            _links.IncomingEnded += OnIncomingEnded;
            _links.QualityChanged += (name, rtt, bps) => Dispatcher.BeginInvoke(new Action(RefreshQualityText));
            _links.SecurityFailed += OnSecurityFailed;
            _links.Log += (name, message) => AppLog.Write(string.IsNullOrEmpty(name) ? "link" : name, message);
            _links.AuthRequested += OnAuthRequested;
            _links.AuthCompleted += OnAuthCompleted;

            PrimaryDevicesList.ItemsSource = _primaryDevices;
            TrustedDevicesList.ItemsSource = _trustedDevices;
            HiddenDevicesList.ItemsSource = _hiddenDevices;
            HistoryListView.ItemsSource = _visibleHistory;
            ApplyHistoryViewOptions();
            ApplyHistoryAppearance();

            // The settings page changes these live; without this hook the new values only took
            // effect after the window was reopened.
            Services.HistoryAppearance.Changed +=
                () => Dispatcher.BeginInvoke(new Action(ApplyHistoryAppearance));

            // Rows decide whether to show a preview through HasThumbnail, which is a plain
            // property — flipping the option therefore needs the items to be re-created rather
            // than just repainted.
            Services.HistoryViewOptions.Instance.PropertyChanged += (_, e) =>
            {
                if (e.PropertyName == nameof(Services.HistoryViewOptions.Thumbnails))
                    Dispatcher.BeginInvoke(new Action(() => RefreshHistory(force: true)));
            };

            I18n.Instance.LanguageChanged += OnLanguageChanged;
            SettingsService.Changed += OnSettingsChanged;
            ThemeManager.Instance.PropertyChanged += (_, _) => Dispatcher.BeginInvoke(new Action(RefreshQualityText));

            LoadPairedDevices();
            RefreshHistory();
            RefreshQualityText();
            UpdateFilterBadge();

            _dropHintTimer = new System.Windows.Threading.DispatcherTimer
            {
                Interval = TimeSpan.FromMilliseconds(180)
            };
            _dropHintTimer.Tick += (_, _) =>
            {
                _dropHintTimer.Stop();
                SetDropHint(false);
            };

            _clipboardTimer = new System.Windows.Threading.DispatcherTimer
            {
                Interval = TimeSpan.FromMilliseconds(600)
            };
            _clipboardTimer.Tick += ClipboardTimer_Tick;
            _clipboardTimer.Start();

            _searchDebounce.Tick += (_, _) =>
            {
                _searchDebounce.Stop();
                RefreshHistory();
            };

            // Storage auto-clean: once at startup, then every 30 minutes.
            ViewModels.SettingsViewModel.RunAutoClean();
            var cleanTimer = new System.Windows.Threading.DispatcherTimer { Interval = TimeSpan.FromMinutes(30) };
            cleanTimer.Tick += (_, _) => ViewModels.SettingsViewModel.RunAutoClean();
            cleanTimer.Start();

            // The "connected" badge is polled from the link manager every couple of seconds.
            // Events alone were not trustworthy (a peer's hostname is not its pairing name, and
            // some drops never raise a close callback), which left a live link reading as idle.
            var linkStateTimer = new System.Windows.Threading.DispatcherTimer
            {
                Interval = TimeSpan.FromSeconds(2)
            };
            linkStateTimer.Tick += (_, _) =>
            {
                if (_links.IsRunning) ApplyConnectionStates();
            };
            linkStateTimer.Start();

            if (_settings.StartMinimized || startMinimized)
            {
                WindowState = WindowState.Minimized;
                Loaded += (_, _) => HideToTray(silent: true);
            }
            else if (!_settings.SecurityWizardDone)
            {
                // First real start: explain the layer-2 key before the user runs into a refused
                // connection they have no way to interpret. Skipped when starting hidden.
                Loaded += (_, _) => OpenSecurityWizard(firstRun: true);
            }

            if (autoStartService && _settings.AutoSyncEnabled)
            {
                Loaded += (_, _) => StartService(silent: true);
            }
        }

        /// <summary>
        /// First-run wizard, also reachable from Settings. Changing the key invalidates every
        /// live session (they negotiated with the old policy), so the service is restarted
        /// instead of leaving two devices half-encrypted until the next reconnect.
        /// </summary>
        public void OpenSecurityWizard(bool firstRun = false)
        {
            var before = _settings.PskProtected;
            var wasRunning = _links.IsRunning;

            var wizard = new SecurityWizardWindow(_settings) { Owner = this };
            wizard.ShowDialog();

            if (_settings.PskProtected == before) return;

            if (wasRunning)
            {
                try
                {
                    _links.Stop();
                    _links.Start();
                }
                catch (Exception ex)
                {
                    Services.AppLog.Write("security", "wizard restart failed: " + ex.Message);
                }
            }

            ApplyConnectionStates();
            if (!firstRun && wizard.KeySaved)
            {
                MessageBox.Show(
                    Services.Loc.T("wiz.savedHint"),
                    Services.Loc.T("wiz.title"),
                    MessageBoxButton.OK, MessageBoxImage.Information);
            }
        }

        // ------------------------------------------------------------------ tray

        public void AttachTray(TrayService tray)
        {
            _tray = tray;
            _tray.ShowRequested += () => Dispatcher.Invoke(ShowFromTray);
            _tray.StartRequested += () => Dispatcher.Invoke(() => StartService(silent: false));
            _tray.StopRequested += () => Dispatcher.Invoke(StopService);
            _tray.ExitRequested += () => Dispatcher.Invoke(ExitApplication);
            _tray.TopmostToggled += on => Dispatcher.Invoke(() => OnTrayTopmostToggled(on));
            _tray.SetServiceState(false);
            _tray.SetTopmost(Topmost);
        }

        private void ShowFromTray()
        {
            Show();
            WindowState = WindowState.Normal;
            Activate();
            if (!_settings.AlwaysOnTop)
            {
                Topmost = true;
                Topmost = false;
            }
            else
            {
                Topmost = true;
            }
        }

        private void HideToTray(bool silent)
        {
            Hide();
            // Hide-to-tray is the normal end of a session, so the placement is captured here as
            // well as on exit: a process killed from the tray would otherwise lose it.
            SaveWindowPlacement();
            if (!silent && _tray != null)
                _tray.ShowBalloon("CBB", Loc.T("lbl.trayHint"));
            else if (!_trayHintShown && _tray != null)
            {
                _trayHintShown = true;
                _tray.ShowBalloon("CBB", Loc.T("lbl.trayHint"));
            }
        }

        /// <summary>
        /// Reopens at the last size and position. The saved rectangle is validated against the
        /// screens that exist right now: unplugging the monitor a window was parked on must not
        /// leave it (and the user) staring at nothing.
        /// </summary>
        private void RestoreWindowPlacement()
        {
            if (!_settings.RememberWindowPlacement) return;
            if (_settings.WindowWidth is not double w || _settings.WindowHeight is not double h) return;
            if (w < MinWidth || h < MinHeight) return;

            var left = _settings.WindowLeft ?? double.NaN;
            var top = _settings.WindowTop ?? double.NaN;
            var rect = new Rect(left, top, w, h);

            // A visible sliver is enough: at least the title bar area has to land on a screen.
            var visible = new Rect(
                SystemParameters.VirtualScreenLeft,
                SystemParameters.VirtualScreenTop,
                SystemParameters.VirtualScreenWidth,
                SystemParameters.VirtualScreenHeight);
            var probe = new Rect(rect.Left, rect.Top, Math.Min(rect.Width, 120), Math.Min(rect.Height, 40));
            if (double.IsNaN(rect.Left) || double.IsNaN(rect.Top) || !visible.IntersectsWith(probe))
            {
                // Centre on the primary screen instead of guessing.
                Left = (SystemParameters.WorkArea.Width - w) / 2 + SystemParameters.WorkArea.Left;
                Top = (SystemParameters.WorkArea.Height - h) / 2 + SystemParameters.WorkArea.Top;
            }
            else
            {
                Left = rect.Left;
                Top = rect.Top;
            }

            Width = w;
            Height = h;

            if (_settings.WindowMaximized) WindowState = WindowState.Maximized;
        }

        /// <summary>
        /// Stores the current bounds. <see cref="Window.RestoreBounds"/> is used while maximised
        /// or minimised so the window comes back at its real size, not filling the screen.
        /// </summary>
        private void SaveWindowPlacement()
        {
            if (!_settings.RememberWindowPlacement) return;

            var bounds = WindowState == WindowState.Normal
                ? new Rect(Left, Top, Width, Height)
                : RestoreBounds;
            if (bounds.Width <= 0 || bounds.Height <= 0) return;
            if (double.IsNaN(bounds.Left) || double.IsNaN(bounds.Top)) return;

            var maximized = WindowState == WindowState.Maximized;
            // Nothing moved: skip the write (and the settings-changed broadcast behind it).
            if (_settings.WindowLeft == bounds.Left && _settings.WindowTop == bounds.Top
                && _settings.WindowWidth == bounds.Width && _settings.WindowHeight == bounds.Height
                && _settings.WindowMaximized == maximized) return;

            _settings.WindowLeft = bounds.Left;
            _settings.WindowTop = bounds.Top;
            _settings.WindowWidth = bounds.Width;
            _settings.WindowHeight = bounds.Height;
            _settings.WindowMaximized = maximized;
            SettingsService.Save();
        }

        public void ExitApplication()
        {
            _reallyClosing = true;
            SaveWindowPlacement();
            _history.Flush();
            _links.Dispose();
            _tray?.Dispose();
            _clipboardTimer?.Stop();
            Application.Current.Shutdown();
        }

        protected override void OnStateChanged(EventArgs e)
        {
            base.OnStateChanged(e);
            if (WindowState == WindowState.Minimized && _settings.MinimizeToTray)
                HideToTray(silent: true);
        }

        protected override void OnClosing(System.ComponentModel.CancelEventArgs e)
        {
            if (!_reallyClosing)
            {
                // First close ever: ask once what closing should mean (checkbox pre-checked),
                // remember the answer, then behave the same on every later close.
                if (!_settings.CloseActionAsked)
                {
                    var ask = new CloseConfirmWindow { Owner = this };
                    ask.ShowDialog();
                    if (!ask.Confirmed)
                    {
                        e.Cancel = true;
                        return;
                    }
                    _settings.CloseToTray = ask.ToTray;
                    _settings.CloseActionAsked = true;
                    SettingsService.Save();
                }

                if (_settings.CloseToTray)
                {
                    e.Cancel = true;
                    HideToTray(silent: false);
                    return;
                }
            }

            base.OnClosing(e);

            // ShutdownMode is OnExplicitShutdown because the tray owns the lifetime, so closing
            // the last window has to end the process explicitly when the user asked for it.
            if (!_reallyClosing) ExitApplication();
        }

        // ------------------------------------------------------------------ tabs

        private void Tab_Checked(object sender, RoutedEventArgs e)
        {
            if (DevicesPanel == null) return;
            var index = int.TryParse((sender as FrameworkElement)?.Tag?.ToString(), out var i) ? i : 0;

            DevicesPanel.Visibility = index == 0 ? Visibility.Visible : Visibility.Collapsed;
            HistoryPanel.Visibility = index == 1 ? Visibility.Visible : Visibility.Collapsed;
            FilesPanel.Visibility = index == 2 ? Visibility.Visible : Visibility.Collapsed;
            if (HistoryToolsPanel != null)
                HistoryToolsPanel.Visibility = index == 1 ? Visibility.Visible : Visibility.Collapsed;
            SettingsPanel.Visibility = Visibility.Collapsed;
            _settingsOpen = false;
        }

        // ------------------------------------------------------------------ header

        private void ThemeToggleButton_Click(object sender, RoutedEventArgs e)
        {
            ThemeManager.Instance.ToggleTheme();
        }

        private void LanguageButton_Click(object sender, RoutedEventArgs e)
        {
            var next = I18n.Instance.IsChinese ? I18n.English : I18n.Chinese;
            I18n.Instance.Language = next;
            _settings.Language = next;
            SettingsService.Save();
        }

        private bool _settingsOpen;

        private void SettingsButton_Click(object sender, RoutedEventArgs e)
        {
            if (_settingsOpen) CloseSettingsPage();
            else OpenSettingsPage();
        }

        private void OpenSettingsPage()
        {
            _settingsOpen = true;
            DevicesPanel.Visibility = Visibility.Collapsed;
            HistoryPanel.Visibility = Visibility.Collapsed;
            FilesPanel.Visibility = Visibility.Collapsed;
            TabRow.Visibility = Visibility.Collapsed;
            SettingsPanel.Visibility = Visibility.Visible;
            SettingsPanel.Reload();
        }

        private void CloseSettingsPage()
        {
            _settingsOpen = false;
            SettingsPanel.Visibility = Visibility.Collapsed;
            TabRow.Visibility = Visibility.Visible;

            // restore whichever tab pill was selected before entering settings
            if (DevicesTab.IsChecked == true) DevicesPanel.Visibility = Visibility.Visible;
            else if (HistoryTab.IsChecked == true) HistoryPanel.Visibility = Visibility.Visible;
            else if (FilesTab.IsChecked == true) FilesPanel.Visibility = Visibility.Visible;
            else DevicesTab.IsChecked = true;
        }

        private void OnLanguageChanged(object? sender, EventArgs e)
        {
            Dispatcher.Invoke(() =>
            {
                foreach (var d in _devices) d.RefreshTexts();
                RegroupDevices();
                RefreshQualityText();
                UpdateFileLimitText();
                // Localisation is cached per entry (format label, accent), so the rows have to be
                // built again for a language switch to show up.
                RefreshHistory(force: true);
                UpdateFilterBadge();
                UpdateHistoryViewToggle();
                _tray?.ApplyText();
            });
        }

        private void OnSettingsChanged()
        {
            if (_reallyClosing) return;
            Dispatcher.Invoke(() =>
            {
                // Always look at the live object: a stale reference here used to compare
                // against the old theme and revert what the user had just toggled.
                _settings = SettingsService.Current;

                if (I18n.Instance.Language != _settings.Language)
                    I18n.Instance.Language = _settings.Language;

                if (ThemeManager.Instance.CurrentTheme != _settings.Theme)
                    ThemeManager.Instance.CurrentTheme = _settings.Theme;

                UpdateFileLimitText();
                RefreshQualityText();
                _history.SetLimit(_settings.HistoryLimit);
                ApplyHistoryViewOptions();
                ApplyHistoryAppearance();
                ApplyHotkey();
                ApplyAlwaysOnTop();
                RegroupDevices();
            });
        }

        /// <summary>Mirrors the persisted layout into the live view and picks the items panel.</summary>
        private void ApplyHistoryViewOptions()
        {
            var options = Services.HistoryViewOptions.Instance;
            options.LoadFrom(_settings);

            var key = options.CardMode ? "HistoryCardPanel" : "HistoryListPanel";
            if (FindResource(key) is ItemsPanelTemplate panel && HistoryListView.ItemsPanel != panel)
                HistoryListView.ItemsPanel = panel;

            UpdateHistoryViewToggle();
        }

        /// <summary>
        /// Publishes the personalisation settings as window resources. The history template binds
        /// these with DynamicResource, so every visible row restyles from one pass here — no view
        /// rebuild, and the row and card layouts cannot disagree.
        /// </summary>
        private void ApplyHistoryAppearance()
        {
            Resources["HistoryBodyFontSize"] = Services.HistoryAppearance.BodyFontSize(_settings);
            Resources["HistoryMetaFontSize"] = Services.HistoryAppearance.MetaFontSize(_settings);
            Resources["HistoryItemMargin"] = Services.HistoryAppearance.ItemMargin(_settings);
            Resources["HistoryItemPadding"] = Services.HistoryAppearance.ItemPadding(_settings);
            Resources["HistoryColumnGap"] = Services.HistoryAppearance.ColumnGap(_settings);
            Resources["HistoryAccentWidth"] = 3.0;

            // Accent brushes are resolved per entry and not observable, so a colour-mode change
            // only shows up after the items are rebound.
            HistoryListView?.Items.Refresh();
        }

        /// <summary>
        /// Switches between one entry per row and a two-column card grid — the button the Android
        /// history top bar has. Persisted so the settings page shows the same state.
        /// </summary>
        private void HistoryViewToggle_Click(object sender, RoutedEventArgs e)
        {
            var options = Services.HistoryViewOptions.Instance;
            options.CardMode = !options.CardMode;
            options.SaveTo(_settings);
            Services.SettingsService.SaveSettings(_settings);
            ApplyHistoryViewOptions();
        }

        /// <summary>The icon advertises the mode a click switches to, like on Android.</summary>
        private void UpdateHistoryViewToggle()
        {
            if (HistoryViewToggle == null) return;

            var card = Services.HistoryViewOptions.Instance.CardMode;
            ViewGridIcon.Visibility = card ? Visibility.Collapsed : Visibility.Visible;
            ViewListIcon.Visibility = card ? Visibility.Visible : Visibility.Collapsed;
            HistoryViewToggle.ToolTip = Loc.T(card ? "lbl.viewList" : "lbl.viewCard");
        }

        // ------------------------------------------------------------------ hotkey / pinning

        protected override void OnSourceInitialized(EventArgs e)
        {
            base.OnSourceInitialized(e);
            _hotkey.Pressed += OnHotkeyPressed;
            _hotkey.Attach(this);
            ApplyHotkey();
            ApplyAlwaysOnTop();
        }

        /// <summary>(Re)registers the global hotkey from settings; silent when disabled.</summary>
        private void ApplyHotkey()
        {
            var desired = _settings.HotkeyEnabled ? (_settings.HotkeyGesture ?? "") : "";
            if (_hotkeyApplied == desired && (desired.Length == 0 || _hotkey.IsRegistered)) return;

            _hotkey.Unregister();
            _hotkeyApplied = desired;
            if (desired.Length == 0) return;

            if (!_hotkey.Register(desired))
            {
                AppLog.Write("hotkey", $"register failed: {desired}");
                _hotkeyApplied = "";
            }
        }

        private string _hotkeyApplied = "";

        private void ApplyAlwaysOnTop()
        {
            Topmost = _settings.AlwaysOnTop;
            if (PinMenuItem != null) PinMenuItem.IsChecked = Topmost;
            SyncPinButton();
            _tray?.SetTopmost(Topmost);
        }

        /// <summary>Caption pin: mirrors the right-click menu item and the tray toggle.</summary>
        private void PinButton_Click(object sender, RoutedEventArgs e)
            => SetAlwaysOnTop(!Topmost, persist: true);

        private void MinimizeButton_Click(object sender, RoutedEventArgs e)
            => WindowState = WindowState.Minimized;

        private void MaximizeButton_Click(object sender, RoutedEventArgs e)
            => WindowState = WindowState == WindowState.Maximized
                ? WindowState.Normal
                : WindowState.Maximized;

        private void CloseButton_Click(object sender, RoutedEventArgs e) => Close();

        private void Window_StateChanged(object sender, EventArgs e)
        {
            if (MaxButton != null)
                MaxButton.Content = WindowState == WindowState.Maximized ? "\uE923" : "\uE922";
            if (MaxButton != null)
                MaxButton.ToolTip = Loc.T(WindowState == WindowState.Maximized ? "lbl.restore" : "lbl.maximize");

            // WindowChrome keeps a resize border even maximised; without this the content hangs
            // over the screen edge by exactly that amount.
            if (RootBorder != null)
                RootBorder.Margin = WindowState == WindowState.Maximized
                    ? new Thickness(7)
                    : new Thickness(0);
        }

        /// <summary>Filled pin while on top, hollow while normal: state readable at a glance.</summary>
        private void SyncPinButton()
        {
            if (PinButton == null) return;
            PinButton.Content = Topmost ? "\uE840" : "\uE718";
            if (Topmost)
                PinButton.Foreground = ThemeManager.Instance.PrimaryText;
            else
                PinButton.ClearValue(ForegroundProperty);
        }

        private void PinMenuItem_Click(object sender, RoutedEventArgs e)
        {
            SetAlwaysOnTop(PinMenuItem.IsChecked, persist: true);
        }

        /// <summary>Tray menu tick: same state as the window's right-click "always on top".</summary>
        private void OnTrayTopmostToggled(bool on) => SetAlwaysOnTop(on, persist: true);

        private void SetAlwaysOnTop(bool on, bool persist)
        {
            Topmost = on;
            if (PinMenuItem != null) PinMenuItem.IsChecked = on;
            SyncPinButton();
            _tray?.SetTopmost(on);
            if (!persist) return;
            _settings.AlwaysOnTop = on;
            SettingsService.Save();
        }

        private void ShowHotkeyHint_Click(object sender, RoutedEventArgs e)
        {
            var gesture = _hotkey.IsRegistered ? _hotkey.CurrentGesture : Loc.T("lbl.hotkeyUnset");
            MessageBox.Show(gesture, Loc.T("lbl.openHotkey"), MessageBoxButton.OK, MessageBoxImage.Information);
        }

        /// <summary>
        /// Global hotkey = a summon key, not a pin.
        ///
        /// First press restores the window and brings it to the front for this summon only: it
        /// must not latch on top, otherwise it fights the right-click / tray "always on top"
        /// state and the user has no way to un-pin. Pressing it again while the window is
        /// already in front sends it back to the tray.
        /// </summary>
        private void OnHotkeyPressed()
        {
            var inFront = IsVisible && WindowState != WindowState.Minimized && IsActive;
            if (inFront)
            {
                HideToTray(silent: true);
                return;
            }

            ShowInTaskbar = true;
            Show();
            if (WindowState == WindowState.Minimized) WindowState = WindowState.Normal;

            // Raise for this summon only. Topmost must stay on across Activate() to beat the
            // foreground lock, so it is cleared again once the window is genuinely in front —
            // that is what makes "shows in front" different from "stays pinned".
            var pinned = _settings.AlwaysOnTop;
            if (!pinned) Topmost = true;
            Activate();
            if (!pinned)
            {
                Dispatcher.BeginInvoke(new Action(() =>
                {
                    if (!_settings.AlwaysOnTop) Topmost = false;
                }), System.Windows.Threading.DispatcherPriority.ApplicationIdle);
            }

            ApplyOpenPageOnShow();
        }

        private void ApplyOpenPageOnShow()
        {
            HistoryTab.IsChecked = true;
            switch ((_settings.OpenPageOnShow ?? "all").ToLowerInvariant())
            {
                case "text":
                    FilterText.IsChecked = true;
                    break;
                case "file":
                    FilterFile.IsChecked = true;
                    break;
                default:
                    FilterAll.IsChecked = true;
                    break;
            }
            RefreshHistory();
        }

        // ------------------------------------------------------------------ devices

        private void RefreshDevicesButton_Click(object sender, RoutedEventArgs e)
        {
            LoadPairedDevices();
            // A refresh is the user asking for another attempt: clear the once-only marks.
            _links.ResetDialAttempts();
            UpdateLinkTargets();
        }

        private void LoadPairedDevices()
        {
            try
            {
                SetStatus("status.loading");

                // Restore the saved selection (survives app restarts) plus whatever the user
                // picked since the list was last filled.
                var previouslySelected = SettingsService.Current.SelectedDeviceAddresses.ToHashSet();
                foreach (var d in _devices.Where(d => d.IsSelected))
                    previouslySelected.Add(d.Key);

                _loadingDevices = true;
                foreach (var d in _devices)
                {
                    d.SelectionChanged -= OnDeviceSelectionChanged;
                    d.GroupingChanged -= OnDeviceGroupingChanged;
                }
                _devices.Clear();

                using var client = new BluetoothClient();
                var prefs = SettingsService.Devices;
                foreach (var info in client.PairedDevices)
                {
                    var vm = DeviceViewModel.FromInfo(info, prefs.Get(info.DeviceAddress.ToString()));
                    vm.InferredPeripheral = DeviceKinds.LooksLikePeripheral(info);
                    vm.SelectionChanged += OnDeviceSelectionChanged;
                    vm.GroupingChanged += OnDeviceGroupingChanged;
                    vm.IsSelected = previouslySelected.Contains(vm.Key);
                    _devices.Add(vm);
                }

                RegroupDevices();
                ApplyConnectionStates();

                SetStatus(_devices.Count == 0 ? "status.noPairedDevices" : "status.loaded", _devices.Count);
                AppLog.Write("devices",
                    $"loaded {_devices.Count}, selected {SelectedDevices().Count()}, " +
                    $"saved=[{string.Join(",", SettingsService.Current.SelectedDeviceAddresses)}], " +
                    $"first={_devices.Select(d => d.Key).FirstOrDefault()}");
                UpdateLinkTargets();
            }
            catch (Exception ex)
            {
                if (BluetoothError.IsRadioUnavailable(ex))
                    SetStatusText(BluetoothError.Explain(ex));
                else
                    SetStatus("status.error", BluetoothError.Explain(ex));
            }
            finally
            {
                _loadingDevices = false;
            }
        }

        /// <summary>Splits the rows across "plain", "always trusted" and "hidden", and refreshes
        /// the section headers. Cheap enough to run on every grouping change.</summary>
        private void RegroupDevices()
        {
            _primaryDevices.Clear();
            _trustedDevices.Clear();
            _hiddenDevices.Clear();

            foreach (var d in _devices.OrderBy(d => d.Name, StringComparer.CurrentCultureIgnoreCase))
            {
                // The auto-hide setting tucks audio / keyboard / mouse devices (inferred, never
                // a user override) into the hidden group so the sync targets stay on top.
                var autoHidden = _settings.AutoHidePeripheralDevices
                    && d.InferredPeripheral && string.IsNullOrEmpty(d.CustomKind);
                if (d.Hidden || autoHidden) _hiddenDevices.Add(d);
                else if (d.AlwaysTrusted) _trustedDevices.Add(d);
                else _primaryDevices.Add(d);
            }

            TrustedSection.Visibility = _trustedDevices.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            HiddenSection.Visibility = _hiddenDevices.Count > 0 ? Visibility.Visible : Visibility.Collapsed;

            TrustedGroupLabel.Text = $"{Loc.T("dev.trusted")} ({_trustedDevices.Count})";
            HiddenGroupLabel.Text = $"{Loc.T("dev.hidden")} ({_hiddenDevices.Count})";

            TrustedDevicesList.Visibility =
                TrustedGroupToggle.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            HiddenDevicesList.Visibility =
                HiddenGroupToggle.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;

            DevicesEmptyText.Visibility = _devices.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
            UpdateDevicesHeader();
        }

        private void UpdateDevicesHeader()
        {
            if (DevicesCountText == null) return;
            DevicesCountText.Text = Loc.T("dev.count", _devices.Count, SelectedDevices().Count());
            ShareButton.IsEnabled = _devices.Any(d => d.IsSelected);
            SendFileButton.IsEnabled = _pickedFiles.Count > 0 && ShareButton.IsEnabled;
            // A folder does not need a prior file pick, only a target device.
            SendFolderButton.IsEnabled = ShareButton.IsEnabled;
        }

        private void DeviceGroupToggle_Click(object sender, RoutedEventArgs e)
        {
            TrustedDevicesList.Visibility =
                TrustedGroupToggle.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
            HiddenDevicesList.Visibility =
                HiddenGroupToggle.IsChecked == true ? Visibility.Visible : Visibility.Collapsed;
        }

        private void OnDeviceGroupingChanged(DeviceViewModel device) => RegroupDevices();

        /// <summary>True while the device list is being rebuilt, so the intermediate empty
        /// selection is not persisted over the user's saved choice.</summary>
        private bool _loadingDevices;

        private void OnDeviceSelectionChanged(DeviceViewModel device)
        {
            UpdateDevicesHeader();

            // While the list is being rebuilt the selection is momentarily partial, and pushing
            // that to the link manager would tear down the link of every device that has not
            // been re-added yet -- the link then had to be re-dialled for no reason, which read
            // as "connected / not connected" flicker right after a refresh.
            if (_loadingDevices) return;

            UpdateLinkTargets();

            // Remember the choice so the next launch reconnects by itself; an app restart must
            // not turn into "sync silently does nothing until a device is re-picked".
            // Default ToString() keeps the same format the link manager uses for its keys.
            SettingsService.Current.SelectedDeviceAddresses = SelectedDevices()
                .Select(d => d.Key).ToList();
            SettingsService.Save();
        }

        // ------------------------------------------------------------------ per-device settings

        private void DeviceSettings_Click(object sender, RoutedEventArgs e)
        {
            if ((sender as FrameworkElement)?.Tag is not DeviceViewModel device) return;

            DeviceSettingsOverlay.DataContext = device;
            DeviceSettingsName.Text = device.Name;
            DeviceSettingsMeta.Text = $"{device.KindText} · {device.AddressText}";
            SelectKindPill(device.CustomKind);
            DeviceSettingsOverlay.Visibility = Visibility.Visible;
        }

        /// <summary>Mirrors the device's CustomKind onto the pill group ("" = auto).</summary>
        private void SelectKindPill(string kind)
        {
            foreach (var child in DeviceKindPanel.Children)
            {
                if (child is not System.Windows.Controls.RadioButton pill) continue;
                pill.IsChecked = (pill.Tag as string) == kind;
            }
        }

        private void DeviceKind_Click(object sender, RoutedEventArgs e)
        {
            if (DeviceSettingsOverlay.DataContext is not DeviceViewModel device) return;
            if ((sender as FrameworkElement)?.Tag is not string kind) return;
            device.CustomKind = kind;
            DeviceSettingsMeta.Text = $"{device.KindText} · {device.AddressText}";
        }

        private void DeviceSettingsDismiss_Click(object sender, RoutedEventArgs e) =>
            CloseDeviceSettings();

        private void CloseDeviceSettings()
        {
            DeviceSettingsOverlay.Visibility = Visibility.Collapsed;
            DeviceSettingsOverlay.DataContext = null;
        }

        private void DeviceForget_Click(object sender, RoutedEventArgs e)
        {
            if (DeviceSettingsOverlay.DataContext is not DeviceViewModel device) return;

            if (MessageBox.Show(Loc.T("dev.forgetConfirm", device.Name), "CBB",
                    MessageBoxButton.OKCancel, MessageBoxImage.Question) != MessageBoxResult.OK) return;

            // Only forgets the record inside this app: the OS pairing stays, so the device can be
            // used for other things and can be added back by simply refreshing the list.
            SettingsService.Devices.Remove(device.Key);
            SettingsService.Current.SelectedDeviceAddresses.Remove(device.Key);
            SettingsService.Save();

            CloseDeviceSettings();
            device.SelectionChanged -= OnDeviceSelectionChanged;
            device.GroupingChanged -= OnDeviceGroupingChanged;
            device.IsSelected = false;
            _devices.Remove(device);
            RegroupDevices();
            UpdateLinkTargets();
            SetStatus("status.deviceForgotten", device.Name);
        }

        private void UpdateLinkTargets()
        {
            var targets = SelectedDevices()
                .Select(d => (d.Address, d.Name, d.AutoReconnect))
                .ToList();
            _links.SetTargets(targets);
        }

        private IEnumerable<DeviceViewModel> SelectedDevices() =>
            _devices.Where(d => d.IsSelected);

        /// <summary>
        /// A security rejection is the one class of failure the user has to fix by hand, so it is
        /// stated on the device row and announced once, instead of the link silently flapping.
        /// </summary>
        private void OnSecurityFailed(string addressKey, string name, string reason)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                var text = DescribeSecurityFailure(reason);
                AppLog.Write("security", $"{name}: {reason}");

                var device = _devices.FirstOrDefault(d => d.Key == addressKey);
                if (device != null) device.SecurityError = text;

                if (_announcedSecurityFailures.Add(addressKey + "|" + reason))
                {
                    _tray?.ShowBalloon("CBB", string.Format(Loc.T("status.secFailed"), name + " — " + text));
                }
            }));
        }

        /// <summary>Maps the channel's machine reason onto something a user can act on.</summary>
        private static string DescribeSecurityFailure(string reason)
        {
            if (reason.Contains("no-psk")) return Loc.T("sec.fail.noPsk");
            if (reason.Contains("psk-mismatch")) return Loc.T("sec.fail.mismatch");
            if (reason.Contains("legacy")) return Loc.T("sec.fail.legacyPeer");
            if (reason.Contains("timeout")) return Loc.T("sec.fail.timeout");
            return Loc.T("sec.fail.other");
        }

        /// <summary>One balloon per address+reason: a reconnect storm must not become a toast storm.</summary>
        private readonly HashSet<string> _announcedSecurityFailures = new();

        private void OnConnectionChanged(string addressKey, string name, bool connected)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                // Derive every row from the live sockets rather than from this one event: the
                // event's name is the peer's self-declared hostname, which never matches the
                // Windows pairing name shown in the list (that mismatch is why a working link
                // kept reading as "not connected").
                ApplyConnectionStates();
                RefreshQualityText();
                if (connected) RetryPendingClipboard();
            }));
        }

        /// <summary>
        /// Copies the link manager's live socket state onto the rows. Cheap (a couple of
        /// dictionary lookups per device) so it also runs on a timer: a dropped link whose
        /// close event never surfaces then still clears, instead of lying until the next click.
        /// </summary>
        private void ApplyConnectionStates()
        {
            foreach (var d in _devices)
            {
                d.IsConnected = _links.HasLiveLink(d.Key);
                d.IsEncrypted = _links.IsLinkEncrypted(d.Key);
                // A live link supersedes whatever went wrong last time.
                if (d.IsConnected) d.SecurityError = "";
            }
        }

        // ------------------------------------------------------------------ confirmation code

        private void OnAuthRequested(string name, string code, string sid)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                // A device the user marked "always trust" has already been vouched for, so the
                // per-connection dialog would only be noise. The SAS code is still verified --
                // this skips the click, not the handshake.
                var trusted = _devices.FirstOrDefault(d =>
                    string.Equals(d.Name, name, StringComparison.OrdinalIgnoreCase))?.AlwaysTrusted == true;

                if (trusted)
                {
                    AppLog.Write("auth", $"auto-accepted for always-trusted device {name}");
                    _links.RespondToAuth(sid, true);
                    SetStatus("status.authAutoAccepted", name);
                    return;
                }

                _authQueue.Enqueue((name, code, sid));
                SetStatus("status.authWaiting", name);
                ShowNextAuthDialog();
            }));
        }

        /// <summary>
        /// Shows one dialog at a time: several devices may ask at once, and stacking modal
        /// windows on top of each other is how users end up confirming the wrong one.
        /// </summary>
        private void ShowNextAuthDialog()
        {
            if (_authWindow != null || _authQueue.Count == 0) return;

            var (name, code, sid) = _authQueue.Dequeue();
            var window = new ConfirmCodeWindow(name, code, sid);
            _authWindow = window;

            window.Answered += (_, accepted) =>
            {
                _authWindow = null;
                _links.RespondToAuth(sid, accepted);

                if (accepted) SetStatus("status.authWaiting", name);
                else SetStatus("status.authRejected", Loc.T("status.authRejectedLocal"));

                Dispatcher.BeginInvoke(new Action(ShowNextAuthDialog));
            };

            window.Show();
            window.Activate();
        }

        private void OnAuthCompleted(string name, bool ok, string? reason)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                if (ok)
                {
                    SetStatus("status.authOk", name);
                    return;
                }

                // One unreachable device must not paint the whole app red while another one is
                // syncing happily -- that is what made the status line look like it was
                // flickering between success and failure in a multi-device setup. The row itself
                // already shows "not connected", and the reason is in the log.
                if (_links.HasAnyLiveLink)
                {
                    AppLog.Write("auth", $"{name}: {reason}");
                    return;
                }

                if (reason == "peer-unsupported")
                    SetStatusText(Loc.T("status.authPeerUnsupported"));
                else
                    SetStatus("status.authRejected", ExplainAuthReason(reason));
            }));
        }

        private static string ExplainAuthReason(string? reason) => reason switch
        {
            "peer-rejected" => Loc.T("status.authRejectedPeer"),
            "user-timeout" or "handshake-timeout" => Loc.T("status.authTimeout"),
            "mismatch" => Loc.T("status.authMismatch"),
            "peer-unsupported" => Loc.T("status.authPeerUnsupported"),
            _ => Loc.T("status.authRejectedLocal")
        };

        // ------------------------------------------------------------------ service

        private void StartServiceButton_Click(object sender, RoutedEventArgs e) => StartService(silent: false);

        private void StopServiceButton_Click(object sender, RoutedEventArgs e) => StopService();

        /// <summary>
        /// Starts the RFCOMM listener. <paramref name="silent"/> is used for the
        /// automatic start / tray path: if the local radio is not ready yet we keep
        /// retrying in the background instead of throwing a modal at the user.
        /// </summary>
        private void StartService(bool silent)
        {
            if (_links.IsRunning) return;
            try
            {
                SetStatus("status.starting");
                _links.Start();
                _links.SetTargets(SelectedDevices()
                    .Select(d => (d.Address, d.Name, d.AutoReconnect)).ToList());
                AppLog.Write("service", $"targets: {string.Join(",", SelectedDevices().Select(d => d.Address))}");

                _startRetryCount = 0;
                StartServiceButton.IsEnabled = false;
                StopServiceButton.IsEnabled = true;
                SetStatus("status.listening");
                _tray?.SetServiceState(true);
            }
            catch (Exception ex) when (BluetoothError.IsRadioUnavailable(ex))
            {
                var text = BluetoothError.Explain(ex);
                SetStatusText(text);

                if (silent)
                {
                    ScheduleStartRetry();
                    return;
                }

                MessageBox.Show(text, Loc.T("dlg.error"), MessageBoxButton.OK, MessageBoxImage.Warning);
            }
            catch (Exception ex)
            {
                var text = BluetoothError.Explain(ex);
                SetStatusText(text);
                MessageBox.Show(text, Loc.T("dlg.error"), MessageBoxButton.OK, MessageBoxImage.Error);
            }
        }

        /// <summary>
        /// Windows often reports NetworkDown for a few seconds after wake-up or while the
        /// Bluetooth service is still starting. Poll quietly until the radio answers.
        /// </summary>
        private void ScheduleStartRetry()
        {
            if (_startRetryCount >= 20) return;      // ~1 minute, then give up and keep the hint
            _startRetryCount++;
            SetStatusText(Loc.T("status.btNotReadyRetry"));

            var timer = new System.Windows.Threading.DispatcherTimer
            {
                Interval = TimeSpan.FromSeconds(3)
            };
            timer.Tick += (_, _) =>
            {
                timer.Stop();
                if (!_links.IsRunning) StartService(silent: true);
            };
            timer.Start();
        }

        private void StopService()
        {
            try
            {
                _links.Stop();
                StartServiceButton.IsEnabled = true;
                StopServiceButton.IsEnabled = false;
                foreach (var d in _devices) d.IsConnected = false;
                SetStatus("status.stopped");
                _tray?.SetServiceState(false);
            }
            catch (Exception ex)
            {
                SetStatus("status.error", BluetoothError.Explain(ex));
            }
        }

        // ------------------------------------------------------------------ clipboard

        private void ShareButton_Click(object sender, RoutedEventArgs e)
        {
            if (!System.Windows.Clipboard.ContainsText())
            {
                SetStatus("status.clipboardEmpty");
                return;
            }
            var text = System.Windows.Clipboard.GetText();
            if (string.IsNullOrEmpty(text))
            {
                SetStatus("status.clipboardEmpty");
                return;
            }
            _ = SendTextAsync(text, manual: true);
        }

        private async Task SendTextAsync(string text, bool manual)
        {
            if (!_links.IsRunning)
            {
                if (manual) MessageBox.Show(Loc.T("status.btOff"), Loc.T("dlg.error"));
                return;
            }

            var devices = SelectedDevices().ToList();
            if (devices.Count == 0)
            {
                if (manual) SetStatus("status.noSelection");
                return;
            }

            SetStatus("status.sending");

            // Each target gets its own task and its own deadline. Sending in a single loop meant
            // a phone with a stale socket held the other phone's text hostage for the length of
            // a dial attempt (12s), which is what made clipboard sync look dead for a device
            // that was working fine.
            var budget = TimeSpan.FromSeconds(manual ? 8 : 2);
            var results = await Task.WhenAll(devices.Select(async device =>
            {
                try
                {
                    using var cts = new CancellationTokenSource(budget);
                    var sent = await _links.SendTextAsync(device.Address, device.Name, text,
                        cts.Token, allowDial: manual);
                    return (device, sent);
                }
                catch (Exception)
                {
                    return (device, sent: false);
                }
            }));

            var failed = results.Where(r => !r.sent).Select(r => r.device).ToList();
            int ok = results.Count(r => r.sent);

            if (failed.Count > 0)
            {
                // Remember which devices missed this clip: the "already seen this text" guard
                // would otherwise drop it forever, and the text would never arrive even after
                // the link came back.
                _pendingClipText = text;
                foreach (var device in failed) _pendingClipTargets.Add(device.Key);
            }

            if (ok > 0)
            {
                SetStatus("status.sent", ok);
                _history.Add(new ClipboardEntry
                {
                    Kind = ClipKind.Text,
                    Text = text,
                    Direction = ClipDirection.Outgoing,
                    Source = devices.Count == 1 ? devices[0].Name : $"{ok} devices"
                });
            }
            else
            {
                SetStatus("status.connFailed", devices.FirstOrDefault()?.Name ?? "");
            }
        }

        /// <summary>
        /// Pushes the last clipboard text to devices that missed it, as soon as their link is
        /// confirmed. Without this a clip copied while a phone was reconnecting was simply lost:
        /// the timer's "same text as last time" check keeps it from ever being offered again.
        /// </summary>
        private void RetryPendingClipboard()
        {
            var text = _pendingClipText;
            if (string.IsNullOrEmpty(text) || _pendingClipTargets.Count == 0) return;

            var waiting = SelectedDevices()
                .Where(d => _pendingClipTargets.Contains(d.Key) && d.IsConnected)
                .ToList();

            foreach (var device in waiting)
            {
                _pendingClipTargets.Remove(device.Key);
                AppLog.Write("clipboard", $"retry to {device.Name} after reconnect");
                _ = Task.Run(async () =>
                {
                    try
                    {
                        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(5));
                        await _links.SendTextAsync(device.Address, device.Name, text, cts.Token);
                    }
                    catch (Exception)
                    {
                        // A second failure is not retried again; the next copy will carry the data.
                    }
                });
            }

            if (_pendingClipTargets.Count == 0) _pendingClipText = null;
        }

        private void ClipboardTimer_Tick(object? sender, EventArgs e)
        {
            if (_suppressClipboardSync) return;

            try
            {
                // 1) A file copied in Explorer (CF_HDROP). This is the thing CrossPaste users
                //    expect: copy a file on the PC, it shows up on the phone as a file.
                if (System.Windows.Clipboard.ContainsFileDropList())
                {
                    var files = System.Windows.Clipboard.GetFileDropList();
                    var path = files != null && files.Count > 0 ? files[0] : null;
                    if (!string.IsNullOrEmpty(path) && File.Exists(path))
                    {
                        OnClipboardFile(path!);
                        return;
                    }
                }

                // 2) An image on the clipboard (screenshot, "copy image", snip). Sent as PNG.
                if (System.Windows.Clipboard.ContainsImage())
                {
                    OnClipboardImage();
                    return;
                }

                // 3) Plain text, the classic case.
                if (!System.Windows.Clipboard.ContainsText()) return;
                var text = System.Windows.Clipboard.GetText();
                if (string.IsNullOrWhiteSpace(text)) return;
                if (text == _lastClipboardText) return;

                // Survives a restart: the clipboard can still be holding what we received.
                if (SelfClipboard.MatchesText(text))
                {
                    _lastClipboardText = text;
                    return;
                }

                _lastClipboardText = text;
                _lastClipboardFilePath = null;
                _lastClipboardImageHash = null;

                // Always remember what was copied locally.
                _history.Add(new ClipboardEntry
                {
                    Kind = ClipKind.Text,
                    Text = text,
                    Direction = ClipDirection.Local,
                    Source = Loc.T("lbl.dir.local")
                });

                if (_settings.AutoSyncEnabled && _links.IsRunning && SelectedDevices().Any())
                    _ = SendTextAsync(text, manual: false);
            }
            catch
            {
                // clipboard can be locked by another process; retry on next tick
            }
        }

        /// <summary>Handles a file that is currently on the clipboard.</summary>
        private void OnClipboardFile(string path)
        {
            if (path == _lastClipboardFilePath)
            {
                // Zero attempts means this clipboard was already handled (sent, or deliberately
                // skipped): the watcher ticks twice a second and must not treat the same file
                // as new work every time.
                if (_clipboardFileAttempts == 0) return;

                if (_clipboardFileAttempts >= 3)
                {
                    // Out of budget while the failure is fresh: stay quiet. Once the cooldown
                    // passes a re-copy is honoured again with a clean budget.
                    var cooling = (DateTime.UtcNow - _clipboardFileLastFailure).TotalSeconds < 60;
                    if (cooling) return;
                    _clipboardFileAttempts = 0;
                }
            }
            else
            {
                _clipboardFileAttempts = 0;
                _lastClipboardFilePath = path;
            }

            _lastClipboardText = null;
            _lastClipboardImageHash = null;

            // Never bounce a file we just received straight back to the sender.
            if (_receivedFilePaths.Contains(path)) return;

            // The watcher ticks twice a second: while a retry for this same file is still queued
            // or in flight, queueing it again would send the file twice back to back.
            if (_clipboardFileQueue.Any(item =>
                    string.Equals(item.Path, path, StringComparison.OrdinalIgnoreCase)))
                return;
            if (string.Equals(_clipboardFileSending, path, StringComparison.OrdinalIgnoreCase))
                return;

            var info = new FileInfo(path);

            // Still exactly what we put there ourselves. The in-memory guards below cover the
            // same case, but they die with the process: a restart while the received file is
            // still on the clipboard used to send it back to the phone that had sent it.
            if (SelfClipboard.MatchesFile(info.Name, info.Length))
            {
                AppLog.Write("clipboard", $"clipboard file ignored (our own write): {info.Name}");
                return;
            }

            // Identity check, not just path: the peer's copy of a file lands here under a
            // different path, so comparing paths alone let an echo loop run - PC sends a file,
            // the phone stores it, the phone's screenshot watcher treats the new picture as a
            // fresh capture and sends it back, this side puts it on the clipboard, and the
            // watcher sends it out again. Name + size + a short window breaks that chain.
            if (_recentReceivedFiles.TryGetValue(
                    $"{info.Name}|{info.Length}", out var justArrived)
                && (DateTime.UtcNow - justArrived).TotalSeconds < 180)
            {
                AppLog.Write("clipboard",
                    $"clipboard file ignored (received {(int)(DateTime.UtcNow - justArrived).TotalSeconds}s ago): {info.Name}");
                return;
            }

            // One local row per copy, not one per retry.
            if (_clipboardFileAttempts == 0)
            {
                _history.Add(new ClipboardEntry
                {
                    Kind = ClipKind.File,
                    FileName = info.Name,
                    FilePath = info.FullName,
                    Size = info.Length,
                    Direction = ClipDirection.Local,
                    Source = Loc.T("lbl.dir.local")
                });
            }

            if (!_settings.AutoFileSync) return;
            if (!_settings.AutoSyncEnabled || !_links.IsRunning || !SelectedDevices().Any()) return;

            // Devices with file sync switched off only take clipboard text.
            if (!SelectedDevices().Any(d => d.FileSyncEnabled))
            {
                SetStatus("status.fileSyncOff");
                return;
            }

            var limit = _links.EffectiveFileLimit();
            if (info.Length > limit)
            {
                SetStatus("status.fileSkippedLarge", info.Name, FormatSize(limit));
                return;
            }

            _ = SendClipboardFileAsync(path);
        }

        /// <summary>Handles an image that is currently on the clipboard (encoded to PNG).</summary>
        private void OnClipboardImage()
        {
            var source = System.Windows.Clipboard.GetImage();
            if (source == null) return;

            var hash = ImageHash(source);
            if (hash == null || hash == _lastClipboardImageHash) return;
            _lastClipboardImageHash = hash;
            _lastClipboardFilePath = null;
            _lastClipboardText = null;

            // Gate first, then touch the disk: an image that is not going anywhere should not
            // leave a file behind in the user's folder.
            if (!_settings.AutoFileSync) return;
            if (!_settings.AutoSyncEnabled || !_links.IsRunning || !SelectedDevices().Any()) return;

            if (!SelectedDevices().Any(d => d.FileSyncEnabled))
            {
                SetStatus("status.fileSyncOff");
                return;
            }

            var png = EncodePng(source);
            if (png == null) return;

            var size = new FileInfo(png).Length;
            var limit = _links.EffectiveFileLimit();
            if (size > limit)
            {
                SetStatus("status.fileSkippedLarge", Path.GetFileName(png), FormatSize(limit));
                try { File.Delete(png); } catch { }
                return;
            }

            // The file is kept when the peer confirms it stored it (the history entry points at
            // it), and removed when nothing was sent so no dangling record is left behind.
            _ = SendClipboardFileAsync(png, keepOnlyIfSent: true);
        }

        /// <summary>
        /// Queues a clipboard file and drains the queue one at a time.
        ///
        /// The single flight guard used to make a second copy vanish outright: the caller had
        /// already recorded "this path was handled", the send itself returned immediately because
        /// one was in progress, and nothing told the user. Copying two files in a row (or one
        /// while the phone was busy pushing screenshots) silently lost the second.
        /// </summary>
        private async Task SendClipboardFileAsync(string path, bool keepOnlyIfSent = false)
        {
            _clipboardFileQueue.Enqueue((path, keepOnlyIfSent));
            if (_pumpingClipboardFiles) return;

            _pumpingClipboardFiles = true;
            try
            {
                while (_clipboardFileQueue.Count > 0)
                {
                    var (next, keep) = _clipboardFileQueue.Dequeue();
                    _clipboardFileSending = next;
                    try
                    {
                        await SendClipboardFileNowAsync(next, keep).ConfigureAwait(true);
                    }
                    finally
                    {
                        _clipboardFileSending = null;
                    }
                }
            }
            finally
            {
                _pumpingClipboardFiles = false;
                _clipboardFileSending = null;
            }
        }

        private async Task SendClipboardFileNowAsync(string path, bool keepOnlyIfSent)
        {
            // Master gate again: the global switch wins over every path and per-device switch.
            if (!_settings.AutoFileSync)
            {
                SetStatus("status.fileSendDisabled");
                return;
            }

            var sent = 0;

            var devices = SelectedDevices().Where(d => d.FileSyncEnabled).ToList();
            if (devices.Count == 0)
            {
                SetStatus("status.fileSyncOff");
                return;
            }
            try
            {
                SetStatus("status.fileSending", Path.GetFileName(path));

                // Clipboard files are transparent: no modal dialogs, only the status bar.
                // They still get the progress row, because a 40 MB screenshot is not instant.
                var clipInfo = new FileInfo(path);
                var clipToken = BeginTransfer(clipInfo.Length);
                var clipProgress = new Progress<double>(ReportTransfer);
                int ok = 0;
                try
                {
                    foreach (var device in devices)
                    {
                        if (await _links.SendFileAsync(device.Address, device.Name, path, clipProgress, clipToken))
                            ok++;
                        else if (clipToken.IsCancellationRequested)
                            break;
                    }
                }
                finally
                {
                    EndTransfer();
                }
                sent = ok;

                AppLog.Write("clip-file",
                    $"{Path.GetFileName(path)} sent to {ok}/{devices.Count} device(s)" +
                    (ok == 0 ? " :: " + (_links.LastTransferError ?? "no reason") : ""));

                if (ok > 0)
                {
                    SetStatus("status.fileSent", ok);
                    var info = new FileInfo(path);
                    _history.Add(new ClipboardEntry
                    {
                        Kind = ClipKind.File,
                        FileName = info.Name,
                        FilePath = info.FullName,
                        Size = info.Length,
                        Direction = ClipDirection.Outgoing,
                        Source = devices.Count == 1 ? devices[0].Name : $"{ok} devices"
                    });
                }
                else
                {
                    SetStatusText(_links.LastTransferError ?? Loc.T("status.connFailed",
                        devices.Count > 0 ? devices[0].Name : ""));
                }
            }
            catch (Exception ex)
            {
                AppLog.Write("clip-file", ex.Message);
            }
            finally
            {
                if (keepOnlyIfSent && sent == 0)
                {
                    // Nobody took it: drop the local copy rather than leaving a file (and a
                    // history entry) that claims a transfer which never happened.
                    try { File.Delete(path); } catch { }
                }

                // Nothing arrived: count it so the retry stays bounded. The path marker has to
                // stay, otherwise the next tick looks like a *new* clipboard, resets the counter
                // and retries forever - which is how one copy became a burst of sends.
                if (sent == 0)
                {
                    _clipboardFileAttempts++;
                    _clipboardFileLastFailure = DateTime.UtcNow;
                }
                else
                {
                    _clipboardFileAttempts = 0;
                }
            }
        }

        /// <summary>Cheap perceptual hash so the same image is not sent every 600 ms.</summary>
        private static string? ImageHash(System.Windows.Media.Imaging.BitmapSource source)
        {
            try
            {
                if (source.PixelWidth <= 0 || source.PixelHeight <= 0) return null;

                var scale = new System.Windows.Media.ScaleTransform(
                    16.0 / source.PixelWidth, 16.0 / source.PixelHeight);
                var scaled = new System.Windows.Media.Imaging.TransformedBitmap(source, scale);

                var converted = new System.Windows.Media.Imaging.FormatConvertedBitmap(
                    scaled, System.Windows.Media.PixelFormats.Bgra32, null, 0);

                const int stride = 16 * 4;
                var pixels = new byte[stride * 16];
                converted.CopyPixels(pixels, stride, 0);

                using var sha = System.Security.Cryptography.SHA1.Create();
                return Convert.ToHexString(sha.ComputeHash(pixels));
            }
            catch
            {
                return null;
            }
        }

        /// <summary>
        /// Saves a clipboard image next to the received files and returns the path.
        ///
        /// It used to go to %TEMP% and be deleted once sent, which left the history entry pointing
        /// at a file that no longer existed: no thumbnail, no "open containing folder", and nothing
        /// left to resend. Any file the app puts in the history must outlive the transfer.
        /// </summary>
        private string? EncodePng(System.Windows.Media.Imaging.BitmapSource source)
        {
            try
            {
                var dir = _settings.ResolvedDownloadFolder;
                Directory.CreateDirectory(dir);
                var path = Path.Combine(dir, $"clip-{DateTime.Now:yyyyMMdd-HHmmss-fff}.png");

                var encoder = new System.Windows.Media.Imaging.PngBitmapEncoder();
                encoder.Frames.Add(System.Windows.Media.Imaging.BitmapFrame.Create(source));

                using var stream = File.Create(path);
                encoder.Save(stream);
                return path;
            }
            catch
            {
                return null;
            }
        }

        private void OnTextReceived(string from, string text)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                _lastClipboardText = text;

                if (_settings.AutoCopyReceived)
                {
                    try
                    {
                        _suppressClipboardSync = true;
                        System.Windows.Clipboard.SetText(text);
                        SelfClipboard.RecordText(text);
                    }
                    catch { }
                    finally
                    {
                        _suppressClipboardSync = false;
                    }
                }

                _history.Add(new ClipboardEntry
                {
                    Kind = ClipKind.Text,
                    Text = text,
                    Direction = ClipDirection.Incoming,
                    Source = string.IsNullOrWhiteSpace(from) ? "Android" : from
                });

                SetStatus("status.received", string.IsNullOrWhiteSpace(from) ? "Android" : from);

                if (_settings.ShowNotifications)
                {
                    var preview = text.Length > 60 ? text[..60] + "…" : text;
                    NotificationHelper.ShowSimpleNotification("CBB", preview);
                }
            }));
        }

        // ------------------------------------------------------------------ files

        private void PickFile_Click(object sender, RoutedEventArgs e)
        {
            var dialog = new OpenFileDialog
            {
                Title = Loc.T("dlg.fileTitle"),
                Filter = $"{Loc.T("dlg.allFiles")}|*.*",
                CheckFileExists = true,
                // Multi-select: the picks are queued and sent one after another, so the user can
                // hand over a handful of files in one go instead of repeating the dialog.
                Multiselect = true
            };
            if (dialog.ShowDialog() != true) return;

            _pickedFiles.Clear();
            _pickedFiles.AddRange(dialog.FileNames);

            if (_pickedFiles.Count == 1)
            {
                var info = new FileInfo(_pickedFiles[0]);
                SelectedFileText.Text = $"{info.Name}  ·  {FormatSize(info.Length)}";
            }
            else
            {
                long total = 0;
                foreach (var p in _pickedFiles)
                {
                    try { total += new FileInfo(p).Length; } catch { }
                }
                SelectedFileText.Text = Loc.T("lbl.filesPicked", _pickedFiles.Count, FormatSize(total));
            }

            SendFileButton.IsEnabled = SelectedDevices().Any();
            SendFolderButton.IsEnabled = SelectedDevices().Any();
            UpdateFileLimitText();
        }

        /// <summary>
        /// Picks a folder and sends it whole: the hierarchy is announced with mkdir frames and
        /// every file travels as an ordinary transfer carrying its relative path, so a receiver
        /// that only understands single files still gets them (just flat).
        /// </summary>
        private async void SendFolder_Click(object sender, RoutedEventArgs e)
        {
            using var dialog = new System.Windows.Forms.FolderBrowserDialog
            {
                Description = Loc.T("dlg.folderTitle"),
                UseDescriptionForTitle = true,
                ShowNewFolderButton = false
            };
            if (dialog.ShowDialog() != System.Windows.Forms.DialogResult.OK) return;

            await SendFoldersAsync(new[] { dialog.SelectedPath });
        }

        /// <summary>
        /// Sends whole folders to every selected device. The picker and drag &amp; drop both come
        /// through here so a dropped folder behaves exactly like a picked one.
        /// </summary>
        private async Task SendFoldersAsync(IReadOnlyList<string> folders)
        {
            if (folders.Count == 0) return;

            if (!_settings.AutoFileSync)
            {
                SetStatus("status.fileSendDisabled");
                return;
            }

            var devices = SelectedDevices().Where(d => d.FileSyncEnabled).ToList();
            if (devices.Count == 0)
            {
                SetStatus("status.fileSyncOff");
                return;
            }

            TransferProgressBar.Visibility = Visibility.Visible;
            TransferProgressBar.Value = 0;

            // Folders report progress per file, so there is no byte total to turn into a rate;
            // the cancel button is what matters here.
            var folderToken = BeginTransfer(0);

            int ok = 0;
            int skipped = 0;
            foreach (var folder in folders)
            {
                if (folderToken.IsCancellationRequested) break;
                foreach (var device in devices)
                {
                    if (folderToken.IsCancellationRequested) break;
                    SetStatus("status.folderSending", new DirectoryInfo(folder).Name);
                    var progress = new Progress<(int Done, int Total, string File)>(p =>
                    {
                        TransferProgressBar.Value = p.Total == 0 ? 1 : (double)p.Done / p.Total;
                        SelectedFileText.Text = $"{p.Done}/{p.Total}  ·  {p.File}";
                    });

                    if (await _links.SendFolderAsync(device.Address, device.Name, folder, progress, folderToken)) ok++;
                    else if (!string.IsNullOrEmpty(_links.LastTransferError))
                        SetStatusText(_links.LastTransferError!);
                    skipped += _links.LastFolderSkipped;
                }
            }

            EndTransfer();
            if (ok > 0)
            {
                SetStatus("status.folderSent", ok);
                SelectedFileText.Text = "";
                if (skipped > 0)
                {
                    SetStatus("status.folderSkipped", skipped,
                        BluetoothLinkManager.HumanSize(_links.EffectiveFileLimit()));
                }
            }
        }

        private async void SendFile_Click(object sender, RoutedEventArgs e)
        {
            if (_pickedFiles.Count == 0) return;
            await SendFilesAsync(_pickedFiles.ToList());
        }

        // ------------------------------------------------------------------ drag & drop

        private void Window_PreviewDragOver(object sender, DragEventArgs e)
        {
            var hasFiles = e.Data.GetDataPresent(DataFormats.FileDrop);

            if (!hasFiles)
            {
                // A text drag belongs to the paste box; no hint, no swallowing.
                SetDropHint(false);
                return;
            }

            SetDropHint(true);
            e.Effects = DragDropEffects.Copy;
            e.Handled = true;

            // DragLeave fires for *every* child element the cursor crosses, so it cannot tell
            // "left the window" from "moved onto the next row" — reacting to it made the overlay
            // blink as the pointer travelled. The drag-over pulses themselves keep it alive now.
            _dropHintTimer.Stop();
            _dropHintTimer.Start();
        }

        private void Window_PreviewDragLeave(object sender, DragEventArgs e)
        {
            // Deliberately empty: the pulse timer hides the hint once the drag really leaves,
            // because no further DragOver events arrive after that.
        }

        /// <summary>Idempotent show/hide so a flickering pointer cannot queue layout passes.</summary>
        private void SetDropHint(bool on)
        {
            if (DropHint == null || _dropHintUp == on) return;
            _dropHintUp = on;
            DropHint.Visibility = on ? Visibility.Visible : Visibility.Collapsed;
        }

        private async void Window_PreviewDrop(object sender, DragEventArgs e)
        {
            _dropHintTimer.Stop();
            SetDropHint(false);
            e.Handled = true;

            if (!e.Data.GetDataPresent(DataFormats.FileDrop)) return;
            if (e.Data.GetData(DataFormats.FileDrop) is not string[] paths || paths.Length == 0) return;

            // A dropped folder is a folder transfer, not a list of files: it keeps its tree.
            // Both kinds can arrive in one gesture, so each is routed separately.
            var folders = paths.Where(Directory.Exists).ToList();
            if (folders.Count > 0) await SendFoldersAsync(folders);

            var files = paths.Where(File.Exists).ToList();
            if (files.Count > 0) await SendFilesAsync(files);
        }

        /// <summary>
        /// Queues files for every selected device. Several files (multi-select, or a drop) go out
        /// one at a time through a single pump: an RFCOMM socket carries one transfer at a time,
        /// so firing them together would only interleave the streams and corrupt both.
        /// The file panel and drag &amp; drop both come through here so they behave identically.
        /// </summary>
        private Task SendFilesAsync(IReadOnlyList<string> paths)
        {
            // The global file switch is the master gate: off means no file leaves this machine,
            // no matter which sending path (drag & drop, file tab, clipboard) was used.
            if (!_settings.AutoFileSync)
            {
                SetStatus("status.fileSendDisabled");
                return Task.CompletedTask;
            }

            var files = paths.Where(p => !string.IsNullOrEmpty(p) && File.Exists(p)).ToList();
            if (files.Count == 0) return Task.CompletedTask;

            if (!SelectedDevices().Any())
            {
                SetStatus("status.noSelection");
                _tray?.ShowBalloon("CBB", Loc.T("lbl.dropNoDevice"));
                return Task.CompletedTask;
            }

            // Devices with their per-device file switch off only take clipboard text.
            if (!SelectedDevices().Any(d => d.FileSyncEnabled))
            {
                SetStatus("status.fileSyncOff");
                return Task.CompletedTask;
            }

            // Skip what is already queued: a second drop of the same file (or the same file
            // listed twice in one drop) used to send it twice, which is indistinguishable from
            // an echo on the receiving side.
            foreach (var path in files)
            {
                if (_fileQueue.Any(q => string.Equals(q, path, StringComparison.OrdinalIgnoreCase)))
                    continue;
                _fileQueue.Enqueue(path);
            }
            if (files.Count > 1) SetStatus("status.filesQueued", files.Count);

            return PumpFileQueueAsync();
        }

        /// <summary>Drains <see cref="_fileQueue"/>; a no-op when the pump is already running
        /// (the running loop picks the new items up itself).</summary>
        private async Task PumpFileQueueAsync()
        {
            // An enqueue that lands while the pump is finishing used to be lost outright: the
            // caller saw "already running" and returned, the pump loop had already tested the
            // queue as empty, and the finally cleared everything left in it. Under the stress
            // run that silently dropped a clipped file. The flag closes that window - the pump
            // re-checks the queue once more after it would otherwise have stopped.
            if (_sendingFiles)
            {
                _filePumpRequested = true;
                return;
            }

            _sendingFiles = true;
            SendFileButton.IsEnabled = false;
            SendFolderButton.IsEnabled = false;
            PickFileButton.IsEnabled = false;

            int ok = 0, tooBig = 0, failed = 0, cancelled = 0;
            string? lastError = null;
            var limit = _links.EffectiveFileLimit();
            var progress = new Progress<double>(ReportTransfer);

            try
            {
                do
                {
                    _filePumpRequested = false;

                    while (_fileQueue.Count > 0)
                    {
                        var path = _fileQueue.Dequeue();

                        // Re-read the switches per file: the queue can outlive a settings change
                        // or a deselection between two items.
                        var devices = SelectedDevices().Where(d => d.FileSyncEnabled).ToList();
                        if (devices.Count == 0 || !_settings.AutoFileSync)
                        {
                            // Nothing would be sent anyway; drop the batch instead of letting it
                            // sit until some unrelated file happens to arrive.
                            _fileQueue.Clear();
                            break;
                        }

                        var info = new FileInfo(path);
                        if (!info.Exists) continue;
                        if (info.Length > limit)
                        {
                            tooBig++;
                            continue;
                        }

                        TransferProgressBar.Value = 0;
                        SetStatus("status.fileSending", info.Name);

                        var token = BeginTransfer(info.Length);
                        var sent = false;
                        var aborted = false;
                        foreach (var device in devices)
                        {
                            if (await _links.SendFileAsync(device.Address, device.Name, path, progress, token))
                            {
                                sent = true;
                            }
                            else if (token.IsCancellationRequested)
                            {
                                aborted = true;
                                break;
                            }
                        }

                        if (aborted)
                        {
                            cancelled++;
                            break;                       // the user asked to stop; drop the rest too
                        }

                        if (!sent)
                        {
                            failed++;
                            lastError = _links.LastTransferError ?? Loc.T("status.connFailed", devices[0].Name);
                            continue;
                        }

                        ok++;
                        _history.Add(new ClipboardEntry
                        {
                            Kind = ClipKind.File,
                            FileName = info.Name,
                            FilePath = info.FullName,
                            Size = info.Length,
                            Direction = ClipDirection.Outgoing,
                            Source = devices.Count == 1 ? devices[0].Name : $"{ok} devices"
                        });
                    }
                }
                while (cancelled == 0 && _filePumpRequested && _fileQueue.Count > 0);
            }
            finally
            {
                _sendingFiles = false;
                // Never a blanket Clear() here: anything still queued arrived while this pump was
                // shutting down, and it is exactly the item the old code lost.
                if (cancelled > 0) _fileQueue.Clear();

                // Nothing got through (link was down, all devices deselected, every send failed):
                // forget what the clipboard held, otherwise copying the same file again is
                // silently ignored because it "was already handled" - and the user has no way to
                // retry short of copying a different file first.
                if (ok == 0 && (failed > 0 || cancelled > 0)) _lastClipboardFilePath = null;

                EndTransfer();
                SendFileButton.IsEnabled = true;
                SendFolderButton.IsEnabled = ShareButton.IsEnabled;
                PickFileButton.IsEnabled = true;
                RefreshQualityText();
            }

            if (cancelled > 0)
            {
                SetStatus("status.transferCancelled");
                return;
            }

            if (ok > 0)
            {
                SetStatus("status.fileSent", ok);
                return;
            }

            if (tooBig > 0)
            {
                var msg = Loc.T("status.fileTooLarge", FormatSize(limit));
                SetStatusText(msg);
                MessageBox.Show(msg, Loc.T("dlg.error"), MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            if (failed > 0 && lastError != null)
            {
                SetStatusText(lastError);
                MessageBox.Show(lastError, Loc.T("dlg.error"), MessageBoxButton.OK, MessageBoxImage.Warning);
            }
        }

        private void OnFileReceived(string from, string path, long size)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                var name = Path.GetFileName(path);

                // Mark it so the clipboard monitor does not send it straight back.
                if (_receivedFilePaths.Count > 64) _receivedFilePaths.Clear();
                _receivedFilePaths.Add(path);
                _recentReceivedFiles[$"{name}|{size}"] = DateTime.UtcNow;
                if (_recentReceivedFiles.Count > 128) _recentReceivedFiles.Clear();
                _lastClipboardFilePath = path;
                _lastClipboardImageHash = null;

                _history.Add(new ClipboardEntry
                {
                    Kind = ClipKind.File,
                    FileName = name,
                    FilePath = path,
                    Size = size,
                    Direction = ClipDirection.Incoming,
                    Source = string.IsNullOrWhiteSpace(from) ? "Android" : from
                });

                try
                {
                    var collection = new System.Collections.Specialized.StringCollection { path };
                    System.Windows.Clipboard.SetFileDropList(collection);
                    _lastClipboardText = null;
                    // On disk as well, so a restart cannot turn this into "the user copied it".
                    SelfClipboard.RecordFile(path, size);
                }
                catch { }

                SetStatus("status.receivedFile", name);
                if (_settings.ShowNotifications)
                {
                    // The toast doubles as the shortcut to the file: clicking anywhere on it
                    // opens the folder with the file already selected.
                    NotificationHelper.ShowSimpleNotification("CBB",
                        Loc.T("status.receivedFile", name),
                        () => RevealInExplorer(path),
                        Loc.T("lbl.openInFolder"),
                        _settings.NotificationSeconds);
                }
            }));
        }

        /// <summary>A folder is on its way: one history row for the whole thing, not one per file.</summary>
        private void OnFolderStarted(string from, string name, string path, int files, long size)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                _history.Add(new ClipboardEntry
                {
                    Kind = ClipKind.Folder,
                    FileName = name,
                    FilePath = path,
                    Size = size,
                    ItemCount = files,
                    Direction = ClipDirection.Incoming,
                    Source = string.IsNullOrWhiteSpace(from) ? "Android" : from
                });

                SetStatus("status.folderStarted", name);

                // Show the folder itself while it travels — one bar, "0/12 files", instead of
                // leaving the label blank until the first file happens to report.
                SelectedFileText.Text = $"{name}  0/{files}";
                TransferProgressBar.Visibility = Visibility.Visible;
                TransferProgressBar.Value = 0;
                // A folder can take minutes too, so it gets the same way out as a single file.
                if (_transferCts == null) CancelTransferButton.Visibility = Visibility.Visible;
            }));
        }

        private void OnFolderCompleted(string from, string name, string path, int files)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                SetStatus("status.folderReceived", name);
                // The folder owns the bar while its members stream in, so it is also the thing
                // that takes the row away — a member finishing must not do it mid-folder.
                if (_transferCts == null) RetireTransferRow();
                if (_settings.ShowNotifications)
                {
                    NotificationHelper.ShowSimpleNotification("CBB",
                        Loc.T("status.folderReceived", name),
                        () => RevealInExplorer(path),
                        Loc.T("lbl.openInFolder"),
                        _settings.NotificationSeconds);
                }
            }));
        }

        // ------------------------------------------------------------------ transfer progress

        private CancellationTokenSource? _transferCts;
        private Stopwatch? _transferClock;
        private long _transferTotal;
        private bool _transferActive;

        /// <summary>
        /// Puts the progress row into "sending" state and hands back the token every send call
        /// has to carry. A file that takes minutes needs a way out, and the speed is what tells
        /// the user whether waiting is worth it at all.
        /// </summary>
        private CancellationToken BeginTransfer(long totalBytes)
        {
            _transferCts?.Dispose();
            _transferCts = new CancellationTokenSource();
            _transferTotal = totalBytes;
            _transferClock = Stopwatch.StartNew();
            _transferActive = true;

            TransferProgressBar.Visibility = Visibility.Visible;
            TransferProgressBar.Value = 0;
            CancelTransferButton.Visibility = Visibility.Visible;
            TransferSpeedText.Text = "";
            return _transferCts.Token;
        }

        private void ReportTransfer(double fraction)
        {
            if (!_transferActive) return;

            var done = Math.Clamp(fraction, 0, 1) * _transferTotal;
            TransferProgressBar.Value = Math.Clamp(fraction, 0, 1);

            var clock = _transferClock;
            if (clock == null || _transferTotal <= 0) return;

            var seconds = clock.Elapsed.TotalSeconds;
            if (seconds < 0.5) return;               // too early for a meaningful rate

            var speed = done / seconds;
            if (speed < 1) return;

            var left = TimeSpan.FromSeconds(Math.Max(0, (_transferTotal - done) / speed));
            TransferSpeedText.Text =
                $"{BluetoothLinkManager.HumanSize((long)done)} / {BluetoothLinkManager.HumanSize(_transferTotal)}" +
                $"  ·  {BluetoothLinkManager.HumanSize((long)speed)}/s" +
                $"  ·  {Loc.T("lbl.timeLeft")} {(int)left.TotalMinutes}:{left.Seconds:00}";
        }

        private void EndTransfer()
        {
            _transferActive = false;
            TransferProgressBar.Visibility = Visibility.Collapsed;
            CancelTransferButton.Visibility = Visibility.Collapsed;
            TransferSpeedText.Text = "";
            _transferClock?.Stop();
            _transferClock = null;
            _transferCts?.Dispose();
            _transferCts = null;
        }

        private void CancelTransfer_Click(object sender, RoutedEventArgs e)
        {
            // One button, two meanings: whichever side is actually moving bytes gets stopped.
            // An upload has a token to cancel; a download must be told to the sender by id.
            if (_transferCts != null)
            {
                try { _transferCts.Cancel(); } catch (ObjectDisposedException) { }
                return;
            }

            if (_incomingId != null) _links.CancelIncoming(_incomingId);
        }

        /// <summary>Id of the receive currently on the bar, or null when nothing is arriving.</summary>
        private string? _incomingId;
        private Stopwatch? _incomingClock;
        private long _incomingTotal;
        private bool _incomingFolder;

        private void OnIncomingStarted(string id, string name, long size, bool inFolder)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                // An upload owns the bar while it runs; a stray download must not steal the
                // button out from under it.
                if (_transferCts != null) return;

                // A folder member only lends its id: the folder opened the bar and keeps it
                // across members, so tearing it down per file would blink on every file.
                _incomingId = id;
                _incomingFolder = inFolder;
                _incomingTotal = size;
                _incomingClock = Stopwatch.StartNew();
                CancelTransferButton.Visibility = Visibility.Visible;
                if (!inFolder) TransferSpeedText.Text = "";
            }));
        }

        private void OnIncomingEnded(string id)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                if (_incomingId != id) return;
                _incomingId = null;
                _incomingClock = null;
                _incomingTotal = 0;
                if (_transferCts != null) return;   // an upload is using the row now
                if (_incomingFolder) return;        // the folder retires the bar, not the member

                // Nothing else retires the bar for a receive: no percent ever reaches 1 when
                // the sender cancels, so it used to sit frozen at the last chunk it saw.
                RetireTransferRow();
            }));
        }

        private void RetireTransferRow()
        {
            TransferProgressBar.Visibility = Visibility.Collapsed;
            CancelTransferButton.Visibility = Visibility.Collapsed;
            TransferSpeedText.Text = "";
            SelectedFileText.Text = "";
        }

        private void OnTransferProgress(string name, string phase, double percent)
        {
            Dispatcher.BeginInvoke(new Action(() =>
            {
                // A folder reports "代办  3/12" here, a single file reports its own name, so the
                // label is set from whatever the transfer calls itself.
                if (!string.IsNullOrWhiteSpace(name) && phase != "done") SelectedFileText.Text = name;

                TransferProgressBar.Visibility = Visibility.Visible;
                TransferProgressBar.Value = Math.Clamp(percent, 0, 1);

                // Downloads get the same rate hint as uploads. A folder member is skipped:
                // its percent belongs to the whole folder, so a per-file rate would lie.
                if (_incomingId != null && !_incomingFolder && _incomingTotal > 0)
                {
                    var seconds = _incomingClock?.Elapsed.TotalSeconds ?? 0;
                    var done = Math.Clamp(percent, 0, 1) * _incomingTotal;
                    if (seconds >= 0.5 && done / seconds >= 1)
                    {
                        var speed = done / seconds;
                        var left = TimeSpan.FromSeconds(Math.Max(0, (_incomingTotal - done) / speed));
                        TransferSpeedText.Text =
                            $"{BluetoothLinkManager.HumanSize((long)done)} / {BluetoothLinkManager.HumanSize(_incomingTotal)}" +
                            $"  ·  {BluetoothLinkManager.HumanSize((long)speed)}/s" +
                            $"  ·  {Loc.T("lbl.timeLeft")} {(int)left.TotalMinutes}:{left.Seconds:00}";
                    }
                }

                if (percent >= 1)
                {
                    TransferProgressBar.Visibility = Visibility.Collapsed;
                    CancelTransferButton.Visibility = Visibility.Collapsed;
                    TransferSpeedText.Text = "";
                    SelectedFileText.Text = "";
                }
            }));
        }

        private void OpenDownloadFolder_Click(object sender, RoutedEventArgs e)
        {
            try
            {
                var folder = _settings.ResolvedDownloadFolder;
                Directory.CreateDirectory(folder);
                Process.Start(new ProcessStartInfo("explorer.exe", $"\"{folder}\"") { UseShellExecute = true });
            }
            catch { }
        }

        /// <summary>
        /// Opens Explorer with the file selected, falling back to its folder. False when neither
        /// exists any more (a sending record whose local copy is gone).
        /// </summary>
        private static bool RevealInExplorer(string path)
        {
            try
            {
                if (File.Exists(path))
                {
                    Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{path}\"")
                    {
                        UseShellExecute = true
                    });
                    return true;
                }

                var folder = Path.GetDirectoryName(path);
                if (string.IsNullOrEmpty(folder) || !Directory.Exists(folder)) return false;
                Process.Start(new ProcessStartInfo("explorer.exe", $"\"{folder}\"") { UseShellExecute = true });
                return true;
            }
            catch { return false; }
        }

        // ------------------------------------------------------------------ history

        /// <summary>
        /// Back to the newest entry.
        ///
        /// Goes through the inner scroller rather than ScrollIntoView: the list is virtualised,
        /// and ScrollIntoView does nothing when the first row is realised but scrolled away.
        /// </summary>
        private void HistoryTopButton_Click(object sender, RoutedEventArgs e)
        {
            if (HistoryListView == null) return;
            if (FindDescendant<ScrollViewer>(HistoryListView) is { } viewer)
            {
                viewer.ScrollToTop();
                return;
            }
            if (HistoryListView.Items.Count > 0)
                HistoryListView.ScrollIntoView(HistoryListView.Items[0]);
        }

        /// <summary>First descendant of the given type; ListView keeps its scroller inside a template.</summary>
        private static T? FindDescendant<T>(DependencyObject? root) where T : DependencyObject
        {
            if (root == null) return null;
            var count = VisualTreeHelper.GetChildrenCount(root);
            for (var i = 0; i < count; i++)
            {
                var child = VisualTreeHelper.GetChild(root, i);
                if (child is T match) return match;
                if (FindDescendant<T>(child) is { } nested) return nested;
            }
            return null;
        }

        private void SearchBox_TextChanged(object sender, TextChangedEventArgs e)
        {
            SearchPlaceholder.Visibility = string.IsNullOrEmpty(SearchBox.Text)
                ? Visibility.Visible : Visibility.Collapsed;

            // Every character used to rebuild the whole list, and the card panel lays out all of
            // its rows at once - typing at speed meant one full rebuild per key. The delay is
            // short enough to still feel live.
            _searchDebounce.Stop();
            _searchDebounce.Start();
        }

        private void Filter_Checked(object sender, RoutedEventArgs e) => RefreshHistory();

        /// <summary>
        /// Rebuilds the visible rows.
        ///
        /// [force] re-creates every container instead of reusing the ones that already match:
        /// text, theme and thumbnail appearance are cached on the entry itself, so those changes
        /// only reach the screen after a fresh template pass.
        /// </summary>
        private void RefreshHistory(bool force = false)
        {
            if (HistoryListView == null) return;

            var query = SearchBox?.Text ?? "";
            ClipKind? kind = null;
            if (FilterText?.IsChecked == true) kind = ClipKind.Text;
            else if (FilterFile?.IsChecked == true) kind = ClipKind.File;

            var started = System.Diagnostics.Stopwatch.StartNew();
            var next = _history.Search(query, kind, _historyFilter);
            var (removed, inserted) = ApplyHistoryRows(next, force);
            // Force the layout pass now instead of letting it land on a later frame: with the card
            // panel every row is realised, so a rebuild means laying all of them out again, and
            // that cost is what the user actually feels. Measuring without it only counted the
            // collection bookkeeping.
            HistoryListView.UpdateLayout();
            started.Stop();

            UpdateHistoryEmptyText();
            if (HistoryCountText != null)
                HistoryCountText.Text = Loc.T("lbl.historyTotal", _visibleHistory.Count);

            // Only the slow case is worth a line: this runs on every clipboard event.
            if (started.Elapsed.TotalMilliseconds >= 40)
                AppLog.Write("perf",
                    $"history refresh: {next.Count} rows, -{removed}/+{inserted}, vis={HistoryListView.IsVisible}, {started.Elapsed.TotalMilliseconds:0.0} ms");
        }

        /// <summary>
        /// Pushes [next] into the bound collection with the smallest change that gets there.
        ///
        /// Clearing and re-adding rebuilt every container. The card panel is hand-drawn and
        /// therefore cannot virtualise, so that tore down and rebuilt several hundred visual trees
        /// on every clipboard event and every keystroke - the cost grew with the history size,
        /// which is exactly what "it got slower the longer I use it" looks like.
        ///
        /// The diff is by id, not by position: history is newest-first with the tail trimmed, so a
        /// single copy shifts every existing row down by one. A prefix/suffix comparison sees that
        /// as a complete replacement and rebuilds everything, which is what it did at first.
        /// </summary>
        private (int Removed, int Inserted) ApplyHistoryRows(IReadOnlyList<ClipboardEntry> next, bool force)
        {
            var oldCount = _visibleHistory.Count;
            var newCount = next.Count;

            if (force)
            {
                _visibleHistory.Clear();
                foreach (var entry in next) _visibleHistory.Add(entry);
                return (oldCount, newCount);
            }

            if (oldCount == 0 && newCount == 0) return (0, 0);

            var keep = new HashSet<string>(newCount);
            foreach (var entry in next) keep.Add(entry.Id);

            // Drop what is gone, walking backwards so the indices stay valid.
            var removed = 0;
            for (var i = _visibleHistory.Count - 1; i >= 0; i--)
            {
                if (keep.Contains(_visibleHistory[i].Id)) continue;
                _visibleHistory.RemoveAt(i);
                removed++;
            }

            // Add what is new, at the place it occupies in the new order.
            var have = new HashSet<string>(_visibleHistory.Count);
            foreach (var entry in _visibleHistory) have.Add(entry.Id);

            var inserted = 0;
            for (var i = 0; i < newCount; i++)
            {
                if (have.Contains(next[i].Id)) continue;
                _visibleHistory.Insert(i, next[i]);
                inserted++;
            }

            // A reorder would break the insert-only assumption; rebuilding is the safe answer.
            if (_visibleHistory.Count != newCount)
            {
                _visibleHistory.Clear();
                foreach (var entry in next) _visibleHistory.Add(entry);
                return (oldCount, newCount);
            }

            return (removed, inserted);
        }

        private void UpdateHistoryEmptyText()
        {
            if (HistoryEmptyText == null) return;
            var empty = _visibleHistory.Count == 0;
            HistoryEmptyText.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
            var noConditions = string.IsNullOrWhiteSpace(SearchBox?.Text) && !_historyFilter.IsActive;
            HistoryEmptyText.Text = noConditions
                ? Loc.T("lbl.historyEmpty")
                : Loc.T("lbl.historyNoMatch");
        }

        /// <summary>
        /// Double click reveals the entry in Explorer (the single click already copied it, and
        /// fired on the first press of the same gesture). Folders open themselves instead.
        /// </summary>
        private void HistoryRow_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            if (e.ClickCount < 2) return;
            if ((sender as FrameworkElement)?.Tag is not string id) return;
            if (!_settings.OpenFileLocationOnClick) return;

            var entry = _history.Snapshot().FirstOrDefault(x => x.Id == id);
            if (entry == null || string.IsNullOrEmpty(entry.FilePath))
            {
                SetStatus("status.fileGone");
                return;
            }
            if (!File.Exists(entry.FilePath) && !Directory.Exists(entry.FilePath))
            {
                // Silence here looked like a broken double-click; say why nothing opened.
                SetStatus("status.fileGone");
                return;
            }

            RevealInExplorer(entry.FilePath);
            e.Handled = true;
        }

        private void HistoryCopy_Click(object sender, RoutedEventArgs e)
        {
            if ((sender as FrameworkElement)?.Tag is not string id) return;
            var entry = _history.Snapshot().FirstOrDefault(x => x.Id == id);
            if (entry != null) ApplyEntry(entry);
        }

        private void ApplyEntry(ClipboardEntry entry)
        {
            try
            {
                if (entry.Kind == ClipKind.Text)
                {
                    _lastClipboardText = entry.Text;
                    System.Windows.Clipboard.SetText(entry.Text);
                    SelfClipboard.RecordText(entry.Text);
                }
                // Folders go on the clipboard the same way files do — Explorer accepts both.
                else if (!string.IsNullOrEmpty(entry.FilePath) &&
                         (File.Exists(entry.FilePath) || Directory.Exists(entry.FilePath)))
                {
                    System.Windows.Clipboard.SetFileDropList(
                        new System.Collections.Specialized.StringCollection { entry.FilePath });
                    // Copying an entry means "put it on my clipboard", not "send it again".
                    // Folders never reach the watcher (it only looks at files), so only files
                    // need the marker.
                    if (File.Exists(entry.FilePath))
                        SelfClipboard.RecordFile(entry.FilePath, new FileInfo(entry.FilePath).Length);

                    // Copying stays the single-click action; revealing the file is a *double*
                    // click, so a stray click never throws an Explorer window at the user.
                }
                SetStatus("status.copied");
            }
            catch { }
        }

        private void HistoryDelete_Click(object sender, RoutedEventArgs e)
        {
            if ((sender as FrameworkElement)?.Tag is not string id) return;
            _history.Remove(id);
        }

        private void ClearHistoryLink_Click(object sender, MouseButtonEventArgs e)
        {
            if (MessageBox.Show(Loc.T("dlg.clearHistory"), "CBB", MessageBoxButton.OKCancel,
                    MessageBoxImage.Question) != MessageBoxResult.OK) return;
            _history.Clear();
            SetStatus("status.historyCleared");
        }

        // ------------------------------------------------------------------ advanced filter

        private void AdvancedFilterButton_Click(object sender, RoutedEventArgs e)
        {
            // The pill's checked state is a badge for "a filter is active", never "the panel is
            // open": undo the click the ToggleButton just applied, then show the panel.
            UpdateFilterBadge();
            LoadFilterIntoPanel(_historyFilter);
            FilterOverlay.Visibility = Visibility.Visible;
        }

        private void CloseFilterPanel_Click(object sender, RoutedEventArgs e) =>
            FilterOverlay.Visibility = Visibility.Collapsed;

        private void FilterOverlay_ScrimDown(object sender, MouseButtonEventArgs e) =>
            FilterOverlay.Visibility = Visibility.Collapsed;

        /// <summary>
        /// Clears the filter for real and closes the panel, the same thing the Android sheet's
        /// reset does. Keeping it as a draft-only reset left the list filtered after the panel was
        /// dismissed, which read as a bug.
        /// </summary>
        private void FilterReset_Click(object sender, RoutedEventArgs e)
        {
            _historyFilter.Reset();
            LoadFilterIntoPanel(_historyFilter);
            // Closing is a choice: with the panel left open the user can reset and then build a
            // different filter in one go, which is how the Android sheet behaves when the option
            // is off.
            if (_settings.CloseFilterOnReset) FilterOverlay.Visibility = Visibility.Collapsed;
            UpdateFilterBadge();
            RefreshHistory();
        }

        private void FilterApply_Click(object sender, RoutedEventArgs e)
        {
            ReadFilterFromPanel(_historyFilter);
            FilterOverlay.Visibility = Visibility.Collapsed;
            UpdateFilterBadge();
            RefreshHistory();
        }

        private void UpdateFilterBadge()
        {
            var count = _historyFilter.ActiveCount;
            var text = Loc.T("lbl.filterMore");
            if (count > 0) text = $"{text} {count}";

            if (AdvancedFilterLabel != null) AdvancedFilterLabel.Text = text;
            if (AdvancedFilterButton != null) AdvancedFilterButton.IsChecked = count > 0;
        }

        private void LoadFilterIntoPanel(HistoryFilter f)
        {
            SetFormatChips(TextFormatPanel, f.TextFormats);
            SetRadioTag(TextLengthModePanel, f.TextLengthMode.ToString());
            SetRadioTag(FileSizeModePanel, f.FileSizeMode.ToString());
            SetRadioTag(TextTimePanel, f.TextTime.ToString());
            SetRadioTag(FileTimePanel, f.FileTime.ToString());

            TextMinBox.Text = f.TextMinLength > 0 ? f.TextMinLength.ToString() : "";
            TextMaxBox.Text = f.TextMaxLength > 0 ? f.TextMaxLength.ToString() : "";
            FileMinBox.Text = f.FileMinKb > 0 ? f.FileMinKb.ToString() : "";
            FileMaxBox.Text = f.FileMaxKb > 0 ? f.FileMaxKb.ToString() : "";
            KeywordBox.Text = f.Keyword;
            IgnoreCaseBox.IsChecked = f.IgnoreCase;
            FileExtBox.Text = f.FileExt;
            TextCustomDayBox.Text = f.TextCustomDay?.ToString("yyyy-MM-dd") ?? "";
            FileCustomDayBox.Text = f.FileCustomDay?.ToString("yyyy-MM-dd") ?? "";
        }

        private void ReadFilterFromPanel(HistoryFilter f)
        {
            f.TextFormats.Clear();
            foreach (var child in TextFormatPanel.Children)
            {
                if (child is not System.Windows.Controls.Primitives.ToggleButton tb) continue;
                if (tb.IsChecked != true) continue;
                if (tb.Tag is string tag && Enum.TryParse<TextFormat>(tag, out var fmt))
                    f.TextFormats.Add(fmt);
            }

            f.TextLengthMode = ReadMode(TextLengthModePanel);
            f.FileSizeMode = ReadMode(FileSizeModePanel);
            f.TextTime = ReadTime(TextTimePanel);
            f.FileTime = ReadTime(FileTimePanel);

            // Only the boxes the chosen pill actually shows are read. Otherwise a value left in
            // the hidden "between" box would keep narrowing results in "at most" mode, with no
            // visible input to explain why entries are missing.
            switch (f.TextLengthMode)
            {
                case BoundMode.AtMost:
                    f.TextMinLength = 0;
                    f.TextMaxLength = ParsePositiveInt(TextMaxBox.Text);
                    break;
                case BoundMode.AtLeast:
                    f.TextMinLength = ParsePositiveInt(TextMinBox.Text);
                    f.TextMaxLength = 0;
                    break;
                case BoundMode.Between:
                    f.TextMinLength = ParsePositiveInt(TextMinBox.Text);
                    f.TextMaxLength = ParsePositiveInt(TextMaxBox.Text);
                    break;
                default:
                    f.TextMinLength = 0;
                    f.TextMaxLength = 0;
                    break;
            }

            switch (f.FileSizeMode)
            {
                case BoundMode.AtMost:
                    f.FileMinKb = 0;
                    f.FileMaxKb = ParsePositiveInt(FileMaxBox.Text);
                    break;
                case BoundMode.AtLeast:
                    f.FileMinKb = ParsePositiveInt(FileMinBox.Text);
                    f.FileMaxKb = 0;
                    break;
                case BoundMode.Between:
                    f.FileMinKb = ParsePositiveInt(FileMinBox.Text);
                    f.FileMaxKb = ParsePositiveInt(FileMaxBox.Text);
                    break;
                default:
                    f.FileMinKb = 0;
                    f.FileMaxKb = 0;
                    break;
            }
            f.Keyword = KeywordBox.Text ?? "";
            f.IgnoreCase = IgnoreCaseBox.IsChecked != false;
            f.FileExt = (FileExtBox.Text ?? "").Trim();
            f.TextCustomDay = ParseDay(TextCustomDayBox.Text);
            f.FileCustomDay = ParseDay(FileCustomDayBox.Text);
        }

        private static void SetFormatChips(Panel panel, ICollection<TextFormat> selected)
        {
            foreach (var child in panel.Children)
            {
                if (child is not System.Windows.Controls.Primitives.ToggleButton tb) continue;
                tb.IsChecked = tb.Tag is string tag
                    && Enum.TryParse<TextFormat>(tag, out var fmt)
                    && selected.Contains(fmt);
            }
        }

        private static void SetRadioTag(Panel panel, string tag)
        {
            foreach (var child in panel.Children)
            {
                if (child is not System.Windows.Controls.Primitives.ToggleButton tb) continue;
                tb.IsChecked = (tb.Tag as string) == tag;
            }
        }

        private static BoundMode ReadMode(Panel panel)
        {
            foreach (var child in panel.Children)
            {
                if (child is not System.Windows.Controls.Primitives.ToggleButton tb) continue;
                if (tb.IsChecked != true) continue;
                if (tb.Tag is string tag && Enum.TryParse<BoundMode>(tag, out var mode)) return mode;
            }
            return BoundMode.Any;
        }

        private static TimeRange ReadTime(Panel panel)
        {
            foreach (var child in panel.Children)
            {
                if (child is not System.Windows.Controls.Primitives.ToggleButton tb) continue;
                if (tb.IsChecked != true) continue;
                if (tb.Tag is string tag && Enum.TryParse<TimeRange>(tag, out var range)) return range;
            }
            return TimeRange.Any;
        }

        private static int ParsePositiveInt(string? text) =>
            int.TryParse((text ?? "").Trim(), out var value) && value > 0 ? value : 0;

        /// <summary>
        /// Accepts the two formats the field advertises. "10-27" means the 27th of October in the
        /// year the app is currently showing, which is what the placeholder promises; anything
        /// else falls back to the culture parser so a typed-out month still works.
        /// </summary>
        private static DateTime? ParseDay(string? text)
        {
            var raw = (text ?? "").Trim();
            if (raw.Length == 0) return null;

            string[] full = { "yyyy-MM-dd", "yyyy-M-d", "yyyy/MM/dd", "yyyy/M/d" };
            foreach (var fmt in full)
                if (DateTime.TryParseExact(raw, fmt, System.Globalization.CultureInfo.InvariantCulture,
                        System.Globalization.DateTimeStyles.None, out var exact))
                    return exact.Date;

            string[] shortFormats = { "MM-dd", "M-d", "MM/dd", "M/d" };
            foreach (var fmt in shortFormats)
                if (DateTime.TryParseExact(raw, fmt, System.Globalization.CultureInfo.InvariantCulture,
                        System.Globalization.DateTimeStyles.None, out var part))
                    return new DateTime(DateTime.Now.Year, part.Month, part.Day);

            return DateTime.TryParse(raw, out var any) ? any.Date : null;
        }

        // ------------------------------------------------------------------ helpers

        private void SetStatus(string key, params object[] args) => SetStatusText(Loc.T(key, args));

        /// <summary>
        /// Lets the settings page report "copied" / "exported" without owning the status bar.
        /// Child views must not reach into the tray or the window chrome themselves.
        /// </summary>
        public void ShowStatus(string text) => SetStatusText(text);

        private void SetStatusText(string text)
        {
            if (StatusTextBlock == null) return;
            StatusTextBlock.Text = text;
            _tray?.SetServiceState(_links.IsRunning, text);
        }

        private void UpdateFileLimitText()
        {
            if (FileLimitText == null) return;
            var limit = _links.EffectiveFileLimit();
            FileLimitText.Text = limit == long.MaxValue
                ? $"{Loc.T("lbl.maxFileSize")}: {Loc.T("lbl.unlimited")}"
                : $"{Loc.T("lbl.maxFileSize")}: {FormatSize(limit)}"
                + (_settings.AdaptiveFileSizeLimit ? $"  ·  {Loc.T("lbl.adaptive")}" : "");
        }

        private void RefreshQualityText()
        {
            UpdateFileLimitText();
            if (FileQualityText == null) return;

            var session = _links.IsRunning ? Loc.T("lbl.quality.unknown") : "-";
            var rtt = -1L;
            double bps = 0;

            if (_links.IsRunning)
            {
                // best effort: use the last measured value from the tray status text
                rtt = _links.LastRtt;
                bps = _links.LastThroughput;
                session = rtt <= 0 ? Loc.T("lbl.quality.unknown")
                    : rtt < 150 ? Loc.T("lbl.quality.good")
                    : rtt < 500 ? Loc.T("lbl.quality.fair")
                    : Loc.T("lbl.quality.poor");
            }

            var parts = new List<string> { $"{Loc.T("lbl.linkQuality")}: {session}" };
            if (rtt > 0) parts.Add($"{rtt} ms");
            if (bps > 0) parts.Add($"{FormatSize((long)bps)}/s");
            FileQualityText.Text = string.Join("  ·  ", parts);
        }

        private static string FormatSize(long bytes)
        {
            double b = bytes;
            string[] units = { "B", "KB", "MB", "GB" };
            int i = 0;
            while (b >= 1024 && i < units.Length - 1) { b /= 1024; i++; }
            return $"{b:0.#} {units[i]}";
        }
    }
}
