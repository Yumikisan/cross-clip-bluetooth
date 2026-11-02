using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using ClipSyncWindows.Models;
using ClipSyncWindows.Services;

namespace ClipSyncWindows.ViewModels
{
    public class RetentionChoice
    {
        public int Days { get; set; }
        public override string ToString() =>
            Days == 0 ? Loc.T("ret.never")
            : Days == 1 ? Loc.T("ret.1d")
            : Days == 7 ? Loc.T("ret.1w")
            : Days == 30 ? Loc.T("ret.1m")
            : Loc.T("ret.3m");
    }

    public class FileSizeChoice
    {
        public long Bytes { get; set; }

        /// <summary>Plain label: these are unit strings ("256 KB") and never change with language.</summary>
        public string Label { get; set; } = "";

        public override string ToString() => Label;
    }

    public class SettingsViewModel : INotifyPropertyChanged
    {
        private readonly AppSettings _settings = SettingsService.Current;

        public SettingsViewModel()
        {
            MaxFileSizeChoices = new List<FileSizeChoice>
            {
                new() { Bytes = 256L * 1024, Label = "256 KB" },
                new() { Bytes = 512L * 1024, Label = "512 KB" },
                new() { Bytes = 1024L * 1024, Label = "1 MB" },
                new() { Bytes = 2L * 1024 * 1024, Label = "2 MB" },
                new() { Bytes = 5L * 1024 * 1024, Label = "5 MB" },
                new() { Bytes = 10L * 1024 * 1024, Label = "10 MB" },
                new() { Bytes = 20L * 1024 * 1024, Label = "20 MB" },
                new() { Bytes = 50L * 1024 * 1024, Label = "50 MB" }
            };

            // Built once, like MaxFileSizeChoices. Rebuilding on every read made the security
            // mode field render blank even though the setting itself was correct.
            SecurityModeOptions = new List<SecurityModeOption>
            {
                new() { Value = "auto", Label = Loc.T("lbl.secModeAuto") },
                new() { Value = "require", Label = Loc.T("lbl.secModeRequire") },
                new() { Value = "off", Label = Loc.T("lbl.secModeOff") }
            };
        }

        public IReadOnlyList<FileSizeChoice> MaxFileSizeChoices { get; }

        public IReadOnlyList<SecurityModeOption> SecurityModeOptions { get; }

        private void RefreshSecurityModeLabels()
        {
            foreach (var option in SecurityModeOptions)
                option.Label = option.Value switch
                {
                    "auto" => Loc.T("lbl.secModeAuto"),
                    "require" => Loc.T("lbl.secModeRequire"),
                    _ => Loc.T("lbl.secModeOff")
                };
        }

        public static string AppVersion => $"v{Services.AppInfo.Version}";

        public AppTheme Theme
        {
            get => _settings.Theme;
            set
            {
                if (_settings.Theme == value) return;
                _settings.Theme = value;
                ThemeManager.Instance.CurrentTheme = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public bool IsDark
        {
            get => _settings.Theme == AppTheme.Dark;
            set
            {
                Theme = value ? AppTheme.Dark : AppTheme.Light;
                OnPropertyChanged();
            }
        }

        public bool IsChinese        {
            get => _settings.Language == I18n.Chinese;
            set
            {
                var lang = value ? I18n.Chinese : I18n.English;
                if (_settings.Language == lang) return;
                _settings.Language = lang;
                I18n.Instance.Language = lang;
                SettingsService.Save();
                RefreshSecurityModeLabels();
                OnPropertyChanged();
            }
        }

        public bool AutoSyncEnabled
        {
            get => _settings.AutoSyncEnabled;
            set
            {
                if (_settings.AutoSyncEnabled == value) return;
                _settings.AutoSyncEnabled = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        // ------------------------------------------------------------------ storage

        public ObservableCollection<StorageStat> StorageStats { get; } = new();

        public string StorageTotalText
        {
            get
            {
                var bytes = StorageService.TotalBytes();
                return bytes > 0 ? StorageService.FormatBytes(bytes) : "";
            }
        }

        public bool HasStorage => StorageStats.Count > 0;

        public bool AutoCleanExpired
        {
            get => _settings.AutoCleanExpired;
            set
            {
                if (_settings.AutoCleanExpired == value) return;
                _settings.AutoCleanExpired = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        /// <summary>Retention choices in days; 0 means "keep forever".</summary>
        public IReadOnlyList<RetentionChoice> RetentionChoices { get; } = new List<RetentionChoice>
        {
            new() { Days = 1 }, new() { Days = 7 }, new() { Days = 30 }, new() { Days = 90 }, new() { Days = 0 }
        };

        public int ImageRetentionDays
        {
            get => _settings.ImageRetentionDays;
            set
            {
                if (_settings.ImageRetentionDays == value) return;
                _settings.ImageRetentionDays = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public int FileRetentionDays
        {
            get => _settings.FileRetentionDays;
            set
            {
                if (_settings.FileRetentionDays == value) return;
                _settings.FileRetentionDays = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public bool ThresholdCleanupEnabled
        {
            get => _settings.ThresholdCleanupEnabled;
            set
            {
                if (_settings.ThresholdCleanupEnabled == value) return;
                _settings.ThresholdCleanupEnabled = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public int MaxStorageMb
        {
            get => _settings.MaxStorageMb;
            set
            {
                var clamped = Math.Clamp(value, 16, 65536);
                if (_settings.MaxStorageMb == clamped) return;
                _settings.MaxStorageMb = clamped;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public int CleanupPercent
        {
            get => _settings.CleanupPercent;
            set
            {
                var clamped = Math.Clamp(value, 5, 90);
                if (_settings.CleanupPercent == clamped) return;
                _settings.CleanupPercent = clamped;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public void RefreshStorage()
        {
            StorageStats.Clear();
            foreach (var row in StorageService.GetStats())
            {
                row.Label = Loc.T(row.Label);
                row.DeleteHint = Loc.T("storage.clearKind", row.Label);
                StorageStats.Add(row);
            }
            OnPropertyChanged(nameof(HasStorage));
            OnPropertyChanged(nameof(StorageTotalText));
        }

        /// <summary>Manual cleanup: delete every stored file payload. Returns rows removed.</summary>
        public int CleanAllFiles() => StorageService.CleanAllFiles();

        /// <summary>Deletes one type (one row) and refreshes the table.</summary>
        public int RemoveKind(StorageStat row)
        {
            var removed = StorageService.CleanKind(row.KindKey);
            RefreshStorage();
            return removed;
        }

        /// <summary>Applies the configured auto-clean rules (called on startup + periodic timer).</summary>
        public static void RunAutoClean()
        {
            var s = SettingsService.Current;
            StorageService.CleanExpired(s);
            StorageService.CleanThreshold(s);
        }

        public bool AutoCopyReceived
        {
            get => _settings.AutoCopyReceived;
            set
            {
                if (_settings.AutoCopyReceived == value) return;
                _settings.AutoCopyReceived = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public bool OpenFileLocationOnClick
        {
            get => _settings.OpenFileLocationOnClick;
            set
            {
                if (_settings.OpenFileLocationOnClick == value) return;
                _settings.OpenFileLocationOnClick = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public bool IgnoreTransferSizeLimit
        {
            get => _settings.IgnoreTransferSizeLimit;
            set
            {
                if (_settings.IgnoreTransferSizeLimit == value) return;
                _settings.IgnoreTransferSizeLimit = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        // ---- personalisation: size, colour, spacing, source label ----

        /// <summary>0.9 / 1.0 / 1.15. The history view listens for the change and re-applies.</summary>
        public double HistoryFontScale
        {
            get => _settings.HistoryFontScale;
            set
            {
                if (Math.Abs(_settings.HistoryFontScale - value) < 0.001) return;
                _settings.HistoryFontScale = value;
                SettingsService.Save();
                OnPropertyChanged();
                HistoryAppearance.Notify();
            }
        }

        public bool HistoryFontSmall
        {
            get => Math.Abs(HistoryFontScale - 0.9) < 0.001;
            set { if (value) HistoryFontScale = 0.9; }
        }

        public bool HistoryFontNormal
        {
            get => Math.Abs(HistoryFontScale - 1.0) < 0.001;
            set { if (value) HistoryFontScale = 1.0; }
        }

        public bool HistoryFontLarge
        {
            get => Math.Abs(HistoryFontScale - 1.15) < 0.001;
            set { if (value) HistoryFontScale = 1.15; }
        }

        /// <summary>"kind" or "custom" (the per-type colour map).</summary>
        public string HistoryAccentMode
        {
            get => _settings.HistoryAccentMode;
            set
            {
                if (_settings.HistoryAccentMode == value) return;
                _settings.HistoryAccentMode = value;
                SettingsService.Save();
                OnPropertyChanged();
                OnPropertyChanged(nameof(AccentKind));
                OnPropertyChanged(nameof(AccentCustom));
                HistoryAppearance.Notify();
            }
        }

        public bool AccentKind
        {
            get => HistoryAccentMode == "kind";
            set { if (value) HistoryAccentMode = "kind"; }
        }

        public bool AccentCustom
        {
            get => HistoryAccentMode == "custom";
            set { if (value) HistoryAccentMode = "custom"; }
        }

        /// <summary>Colour in effect for a type key, used by the colour editor swatches.</summary>
        public System.Windows.Media.Color AccentColorOf(string key) =>
            HistoryAppearance.EffectiveColor(_settings, key);

        /// <summary>Stores one type colour and repaints the history list immediately.</summary>
        public void SetAccentColor(string key, System.Windows.Media.Color colour)
        {
            _settings.HistoryAccentColors[key] = HistoryAppearance.ToHex(colour);
            SettingsService.Save();
            HistoryAppearance.Notify();
        }

        /// <summary>Drops every custom colour so the defaults apply again.</summary>
        public void ResetAccentColors()
        {
            if (_settings.HistoryAccentColors.Count == 0) return;
            _settings.HistoryAccentColors.Clear();
            SettingsService.Save();
            HistoryAppearance.Notify();
        }

        public string HistoryDensity
        {
            get => _settings.HistoryDensity;
            set
            {
                if (_settings.HistoryDensity == value) return;
                _settings.HistoryDensity = value;
                SettingsService.Save();
                OnPropertyChanged();
                OnPropertyChanged(nameof(DensityCompact));
                OnPropertyChanged(nameof(DensityNormal));
                OnPropertyChanged(nameof(DensityCozy));
                HistoryAppearance.Notify();
            }
        }

        public bool DensityCompact
        {
            get => HistoryDensity == "compact";
            set { if (value) HistoryDensity = "compact"; }
        }

        public bool DensityNormal
        {
            get => HistoryDensity == "normal";
            set { if (value) HistoryDensity = "normal"; }
        }

        public bool DensityCozy
        {
            get => HistoryDensity == "cozy";
            set { if (value) HistoryDensity = "cozy"; }
        }

        /// <summary>Draw a 4:3 preview on image entries.</summary>
        public bool HistoryThumbnails
        {
            get => _settings.HistoryThumbnails;
            set
            {
                if (_settings.HistoryThumbnails == value) return;
                _settings.HistoryThumbnails = value;
                SettingsService.Save();
                // Mirrored into the live view options; the row template reacts to that, so the
                // list updates without being rebuilt.
                HistoryViewOptions.Instance.Thumbnails = value;
                OnPropertyChanged();
            }
        }

        /// <summary>Whether pressing Reset in the filter panel also closes it.</summary>
        public bool CloseFilterOnReset
        {
            get => _settings.CloseFilterOnReset;
            set
            {
                if (_settings.CloseFilterOnReset == value) return;
                _settings.CloseFilterOnReset = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public bool ShowSourceDevice
        {
            get => _settings.ShowSourceDevice;
            set
            {
                if (_settings.ShowSourceDevice == value) return;
                _settings.ShowSourceDevice = value;
                SettingsService.Save();
                OnPropertyChanged();
                HistoryAppearance.Notify();
            }
        }

        public bool ShowNotifications
        {
            get => _settings.ShowNotifications;
            set
            {
                if (_settings.ShowNotifications == value) return;
                _settings.ShowNotifications = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public int NotificationSeconds
        {
            get => _settings.NotificationSeconds;
            set
            {
                var clamped = Math.Clamp(value, 1, 60);
                if (_settings.NotificationSeconds == clamped) return;
                _settings.NotificationSeconds = clamped;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public bool AutoHidePeripheralDevices
        {
            get => _settings.AutoHidePeripheralDevices;
            set
            {
                if (_settings.AutoHidePeripheralDevices == value) return;
                _settings.AutoHidePeripheralDevices = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public bool CloseToTray
        {
            get => _settings.CloseToTray;
            set
            {
                if (_settings.CloseToTray == value) return;
                _settings.CloseToTray = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public bool MinimizeToTray
        {
            get => _settings.MinimizeToTray;
            set
            {
                if (_settings.MinimizeToTray == value) return;
                _settings.MinimizeToTray = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public bool StartMinimized
        {
            get => _settings.StartMinimized;
            set
            {
                if (_settings.StartMinimized == value) return;
                _settings.StartMinimized = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public bool AutoStartWithWindows
        {
            get => _settings.AutoStartWithWindows;
            set
            {
                if (_settings.AutoStartWithWindows == value) return;
                _settings.AutoStartWithWindows = value;
                StartupHelper.SetEnabled(value);
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        /// <summary>Reopen at the size and spot the window was left at.</summary>
        public bool RememberWindowPlacement
        {
            get => _settings.RememberWindowPlacement;
            set
            {
                if (_settings.RememberWindowPlacement == value) return;
                _settings.RememberWindowPlacement = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public long MaxFileSizeBytes
        {
            get => _settings.MaxFileSizeBytes;
            set
            {
                if (_settings.MaxFileSizeBytes == value) return;
                _settings.MaxFileSizeBytes = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public bool AdaptiveFileSizeLimit
        {
            get => _settings.AdaptiveFileSizeLimit;
            set
            {
                if (_settings.AdaptiveFileSizeLimit == value) return;
                _settings.AdaptiveFileSizeLimit = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        /// <summary>Both ends must confirm a 6-digit code before data is accepted.</summary>
        public bool RequireConfirmCode
        {
            get => _settings.RequireConfirmCode;
            set
            {
                if (_settings.RequireConfirmCode == value) return;
                _settings.RequireConfirmCode = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        // ---------------------------------------------------------------- layer 2 security

        public sealed class SecurityModeOption : INotifyPropertyChanged
        {
            public string Value { get; init; } = "";

            private string _label = "";

            /// <summary>
            /// Mutable and notifying so a language switch relabels the existing list in place.
            /// Rebuilding the list instead kept losing the ComboBox selection — a TwoWay
            /// SelectedValue binding re-reads ItemsSource, the items become a new set of objects,
            /// and the field rendered blank even though the stored value never changed.
            /// </summary>
            public string Label
            {
                get => _label;
                set
                {
                    if (_label == value) return;
                    _label = value;
                    PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Label)));
                }
            }

            public event PropertyChangedEventHandler? PropertyChanged;

            public override string ToString() => Label;
        }

        public string SecurityMode
        {
            get
            {
                var mode = _settings.SecurityMode;
                return string.IsNullOrWhiteSpace(mode) ? "auto" : mode;
            }
            set
            {
                if (string.IsNullOrWhiteSpace(value) || _settings.SecurityMode == value) return;

                // "Require" with no key is a dead end: every link is refused (no-psk) and the app
                // looks broken. Refuse the combination instead of storing it.
                if (value == "require" && Security.PskVault.Unprotect(_settings.PskProtected).Length == 0)
                {
                    SecurityWarning = Loc.T("secMode.needKey");
                    OnPropertyChanged(nameof(SecurityWarning));
                    OnPropertyChanged();   // snap the ComboBox back to the stored value
                    return;
                }

                _settings.SecurityMode = value;
                SettingsService.Save();
                OnPropertyChanged();
                OnPropertyChanged(nameof(PskSummary));
                OnPropertyChanged(nameof(SecurityModeHint));
            }
        }

        /// <summary>Set when the user picked an impossible combination; the view shows it once.</summary>
        public string SecurityWarning
        {
            get => _warning;
            set { _warning = value; OnPropertyChanged(); }
        }
        private string _warning = "";

        /// <summary>Live one-liner so the current policy is readable without opening the log.</summary>
        public string SecurityModeHint
        {
            get
            {
                var key = Security.PskVault.Unprotect(_settings.PskProtected);
                return string.Format(Loc.T("secMode.state"),
                    Loc.T(SecurityMode == "require" ? "lbl.secModeRequire"
                        : SecurityMode == "off" ? "lbl.secModeOff" : "lbl.secModeAuto"),
                    key.Length == 0 ? Loc.T("psk.notSetShort") : Loc.T("psk.setShort"));
            }
        }

        /// <summary>Never shows the key itself — only whether one exists and a short fingerprint.</summary>
        public string PskSummary
        {
            get
            {
                var key = Security.PskVault.Unprotect(_settings.PskProtected);
                return key.Length == 0
                    ? Loc.T("psk.notSet")
                    : string.Format(Loc.T("psk.set"), Security.PskVault.Fingerprint(key));
            }
        }

        /// <summary>Shortest passphrase worth storing. Keep in sync with the Android copy.</summary>
        public const int MinPskLength = 12;

        /// <summary>
        /// Returns null on success, otherwise the localization key explaining the failure. The
        /// value is read back after storing so a vault failure cannot silently look like success.
        /// Input is normalised exactly like the Android side: spaces and dashes are transcription
        /// noise, so "ABCD-EFGH" and "abcdefgh"... compare equal only after the strip.
        /// </summary>
        public string? SavePsk(string value)
        {
            var normalised = Security.PskVault.Normalize(value);
            if (normalised.Length < MinPskLength) return "psk.tooShort";

            var stored = Security.PskVault.Protect(normalised);
            if (string.IsNullOrEmpty(stored) || Security.PskVault.Unprotect(stored) != normalised)
                return "psk.storeFailed";

            _settings.PskProtected = stored;
            SettingsService.Save();
            OnPropertyChanged(nameof(PskSummary));
            OnPropertyChanged(nameof(SecurityModeHint));
            return null;
        }

        public void ClearPsk()
        {
            _settings.PskProtected = "";
            // Clearing the key while in require mode would create the unconnectable state, so the
            // policy drops to auto in the same breath.
            var droppedRequire = _settings.SecurityMode == "require";
            if (droppedRequire) _settings.SecurityMode = "auto";
            SettingsService.Save();
            OnPropertyChanged(nameof(PskSummary));
            OnPropertyChanged(nameof(SecurityMode));
            OnPropertyChanged(nameof(SecurityModeHint));
            if (droppedRequire) SecurityWarning = Loc.T("psk.clearedModeReset");
        }

        public bool SecurityLogEnabled
        {
            get => _settings.SecurityLogEnabled;
            set
            {
                if (_settings.SecurityLogEnabled == value) return;
                _settings.SecurityLogEnabled = value;
                Security.CbbSecurityLog.Enabled = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public bool AutoFileSync
        {
            get => _settings.AutoFileSync;
            set
            {
                if (_settings.AutoFileSync == value) return;
                _settings.AutoFileSync = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public string DownloadFolder
        {
            get => _settings.ResolvedDownloadFolder;
            set
            {
                if (_settings.DownloadFolder == value) return;
                _settings.DownloadFolder = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public int HistoryLimit
        {
            get => _settings.HistoryLimit;
            set
            {
                var clamped = Math.Clamp(value, 20, 2000);
                if (_settings.HistoryLimit != clamped)
                {
                    _settings.HistoryLimit = clamped;
                    SettingsService.Save();
                }

                // Always notify: the text box has to snap back to the accepted value when
                // the user typed something out of range (or something that isn't a number).
                OnPropertyChanged();
            }
        }

        public bool HistoryCardMode
        {
            get => _settings.HistoryCardMode;
            set
            {
                if (_settings.HistoryCardMode == value) return;
                _settings.HistoryCardMode = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public int HistoryLines
        {
            get => _settings.HistoryLines;
            set
            {
                var clamped = Math.Clamp(value, 1, 8);
                if (_settings.HistoryLines == clamped) return;
                _settings.HistoryLines = clamped;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public bool AlwaysOnTop
        {
            get => _settings.AlwaysOnTop;
            set
            {
                if (_settings.AlwaysOnTop == value) return;
                _settings.AlwaysOnTop = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public bool HotkeyEnabled
        {
            get => _settings.HotkeyEnabled;
            set
            {
                if (_settings.HotkeyEnabled == value) return;
                _settings.HotkeyEnabled = value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public string HotkeyGesture
        {
            get => _settings.HotkeyGesture;
            set
            {
                if (_settings.HotkeyGesture == value) return;
                _settings.HotkeyGesture = value ?? "";
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public string OpenPageOnShow
        {
            get => _settings.OpenPageOnShow;
            set
            {
                if (_settings.OpenPageOnShow == value) return;
                _settings.OpenPageOnShow = string.IsNullOrWhiteSpace(value) ? "all" : value;
                SettingsService.Save();
                OnPropertyChanged();
            }
        }

        public void ResetSettings()
        {
            SettingsService.ResetToDefaults();
            ThemeManager.Instance.CurrentTheme = SettingsService.Current.Theme;
            I18n.Instance.Language = SettingsService.Current.Language;
            OnPropertyChanged(string.Empty);
        }

        /// <summary>Re-raises every binding after an external change (language switch, reset).</summary>
        public void RefreshAll() => OnPropertyChanged(string.Empty);

        public event PropertyChangedEventHandler? PropertyChanged;

        protected void OnPropertyChanged([CallerMemberName] string? name = null) =>
            PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}
