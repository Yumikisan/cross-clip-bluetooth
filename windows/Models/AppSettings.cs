using System.IO;

namespace ClipSyncWindows.Models
{
    public class AppSettings
    {
        public AppTheme Theme { get; set; } = AppTheme.Light;

        /// <summary>UI language: "zh-Hans" or "en".</summary>
        public string Language { get; set; } = "zh-Hans";

        public bool AutoSyncEnabled { get; set; } = false;

        /// <summary>Automatically put content received from a peer on the clipboard.</summary>
        public bool AutoCopyReceived { get; set; } = true;

        /// <summary>
        /// Clicking a file history entry also reveals it in Explorer. Off by default so the
        /// click keeps doing exactly what it did before; the clipboard copy is unaffected.
        /// </summary>
        public bool OpenFileLocationOnClick { get; set; } = false;

        /// <summary>
        /// Lift the per-file size cap. Off by default: the cap exists to keep classic Bluetooth
        /// from stalling on multi-hundred-megabyte transfers.
        /// </summary>
        public bool IgnoreTransferSizeLimit { get; set; } = false;

        /// <summary>Show a toast when something is received.</summary>
        public bool ShowNotifications { get; set; } = true;

        /// <summary>How long a toast stays on screen, in seconds (1-60).</summary>
        public int NotificationSeconds { get; set; } = 3;

        /// <summary>
        /// Tuck audio devices, keyboards and mice (inferred from Class of Device + name) into
        /// the hidden group automatically; they never run this app and only add noise.
        /// </summary>
        public bool AutoHidePeripheralDevices { get; set; } = false;

        /// <summary>
        /// Stable identity of this install, generated once and never rotated. It only decides
        /// which of two redundant sockets between the same pair of devices survives, so it
        /// carries no user information and is safe to send on the wire.
        /// </summary>
        public string NodeId { get; set; } = "";

        /// <summary>Keep running in the notification area when the window is closed.</summary>
        public bool CloseToTray { get; set; } = true;

        // ---- layer 2 security (CBB 2.2+) ----

        /// <summary>
        /// Layer-2 encryption policy: "off" (plaintext, troubleshooting), "auto" (encrypt when a
        /// key is configured, which is the default) or "require" (no key → no link).
        /// </summary>
        public string SecurityMode { get; set; } = "auto";

        /// <summary>
        /// Local pre-shared passphrase, protected with DPAPI. It is never sent on the wire —
        /// only a proof derived from it. Empty means "not configured".
        /// </summary>
        public string PskProtected { get; set; } = "";

        /// <summary>Write handshake/frame diagnostics to %APPDATA%\ClipSync\security.log.</summary>
        public bool SecurityLogEnabled { get; set; } = true;

        /// <summary>
        /// Set once the first-run security wizard has been shown. It is a "shown", not a "configured"
        /// flag on purpose: a user who chooses "later" must not be asked again on every start, but
        /// can always come back from Settings → Run setup again.
        /// </summary>
        public bool SecurityWizardDone { get; set; } = false;

        /// <summary>
        /// False while the user has never closed the window: the first close shows the
        /// confirm dialog (checkbox pre-checked) instead of silently hiding to the tray.
        /// </summary>
        public bool CloseActionAsked { get; set; } = false;

        public bool MinimizeToTray { get; set; } = true;

        public bool StartMinimized { get; set; } = false;

        public bool AutoStartWithWindows { get; set; } = false;

        /// <summary>Reopen the window at the size and spot it was left at.</summary>
        public bool RememberWindowPlacement { get; set; } = true;

        // Nullable so an untouched install writes JSON nulls instead of NaN, which the
        // serializer would emit unquoted and then fail to read back cleanly.
        public double? WindowLeft { get; set; }
        public double? WindowTop { get; set; }
        public double? WindowWidth { get; set; }
        public double? WindowHeight { get; set; }
        public bool WindowMaximized { get; set; } = false;

        /// <summary>
        /// Ask both ends to confirm a six digit code before a link may carry clipboard data.
        /// On by default: a rogue or mixed-up device must not be able to push content just
        /// because it is paired.
        /// </summary>
        public bool RequireConfirmCode { get; set; } = true;

        /// <summary>Maximum size (bytes) of a file that may be transferred.</summary>
        public long MaxFileSizeBytes { get; set; } = 5L * 1024 * 1024;

        /// <summary>Shrink the limit automatically when the Bluetooth link is weak.</summary>
        public bool AdaptiveFileSizeLimit { get; set; } = true;

        /// <summary>
        /// Transfer files (and images) that are sitting in the clipboard, the way CrossPaste
        /// does: copy a file in Explorer or take a screenshot, and the phone receives the file.
        /// </summary>
        public bool AutoFileSync { get; set; } = true;

        public int HistoryLimit { get; set; } = 300;

        /// <summary>
        /// Close the filter panel when Reset is pressed. Off keeps it open with empty fields, for
        /// people who reset first and then pick a few other conditions.
        /// </summary>
        public bool CloseFilterOnReset { get; set; } = true;

        /// <summary>Draw a 4:3 preview on image entries in the history list.</summary>
        public bool HistoryThumbnails { get; set; } = true;

        /// <summary>History layout: false = one entry per row, true = two-column cards.</summary>
        public bool HistoryCardMode { get; set; } = false;

        /// <summary>Visible lines of the preview inside a history entry (1-8).</summary>
        public int HistoryLines { get; set; } = 2;

        /// <summary>
        /// Text size multiplier for history entries: 0.9 (small), 1.0 (standard) or 1.15 (large).
        /// Stored as a number rather than an index so the two clients can share the same JSON.
        /// </summary>
        public double HistoryFontScale { get; set; } = 1.0;

        /// <summary>
        /// Colour of the identifier bar: "kind" (per text format / file kind), "custom" (the
        /// per-type colours in <see cref="HistoryAccentColors"/>), or "theme" (legacy: accent).
        /// </summary>
        public string HistoryAccentMode { get; set; } = "kind";

        /// <summary>
        /// User-chosen colours per type, keyed by <see cref="Models.EntryFormat.KeyFor"/> values
        /// ("text.code", "file.image", …) and stored as #RRGGBB. Missing keys use the defaults.
        /// </summary>
        public Dictionary<string, string> HistoryAccentColors { get; set; } = new();

        /// <summary>Vertical spacing of history entries: "compact", "normal" or "cozy".</summary>
        public string HistoryDensity { get; set; } = "normal";

        /// <summary>
        /// Show the source device name under each entry. Off by default for people who only ever
        /// sync with one device and find the repeated name noise.
        /// </summary>
        public bool ShowSourceDevice { get; set; } = true;

        /// <summary>Keep the window above other windows (also settable from the right-click menu).</summary>
        public bool AlwaysOnTop { get; set; } = false;

        /// <summary>Global hotkey that brings the window back from the tray.</summary>
        public bool HotkeyEnabled { get; set; } = true;

        /// <summary>Gesture string, e.g. "Ctrl+Alt+C".</summary>
        public string HotkeyGesture { get; set; } = "Ctrl+Alt+C";

        /// <summary>
        /// Page shown when the hotkey brings the window up: "all", "text" or "file".
        /// </summary>
        public string OpenPageOnShow { get; set; } = "all";

        /// <summary>Folder used to store received files. Empty means the default folder.</summary>
        public string DownloadFolder { get; set; } = "";

        /// <summary>Delete received files whose history entry is older than the retention period.</summary>
        public bool AutoCleanExpired { get; set; } = false;

        /// <summary>Retention (days) for image files; 0 = keep forever.</summary>
        public int ImageRetentionDays { get; set; } = 7;

        /// <summary>Retention (days) for non-image files; 0 = keep forever.</summary>
        public int FileRetentionDays { get; set; } = 30;

        /// <summary>Cleanup by total stored size instead of age.</summary>
        public bool ThresholdCleanupEnabled { get; set; } = false;

        /// <summary>Max stored size (MB) before threshold cleanup kicks in.</summary>
        public int MaxStorageMb { get; set; } = 1024;

        /// <summary>When over the threshold, delete oldest files until this % below the limit.</summary>
        public int CleanupPercent { get; set; } = 30;

        /// <summary>
        /// Addresses of the paired devices chosen for syncing, so a restart reconnects on its
        /// own instead of silently syncing with nobody until the user re-picks the device.
        /// Format matches BluetoothAddress.ToString("C").
        /// </summary>
        public List<string> SelectedDeviceAddresses { get; set; } = new();

        /// <summary>
        /// Per-device preferences (always trusted, auto reconnect, hidden, file sync). Keyed by
        /// the same address form as <see cref="SelectedDeviceAddresses"/>.
        /// </summary>
        public List<DevicePrefs> DevicePrefs { get; set; } = new();

        public string ResolvedDownloadFolder
        {
            get
            {
                if (!string.IsNullOrWhiteSpace(DownloadFolder))
                    return DownloadFolder!;

                // Same shape as the phone: <Documents>/CrossClip. The old default was
                // <Downloads>/ClipSync — a folder people rarely open, under the retired product
                // name. A folder the user picked themselves always wins (checked above).
                var documents = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments);
                if (string.IsNullOrEmpty(documents))
                    documents = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
                return Path.Combine(documents, "CrossClip");
            }
        }
    }

    public enum AppTheme
    {
        Light,
        Dark
    }
}
