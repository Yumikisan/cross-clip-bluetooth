using ClipSyncWindows.Models;
using System;
using System.IO;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Holds the single live <see cref="AppSettings"/> instance so that every part of the app
    /// (link manager, tray, views) always sees the same values after a settings change.
    /// </summary>
    public static class SettingsService
    {
        private static readonly string _settingsFilePath;
        private static AppSettings _current;

        static SettingsService()
        {
            var appDataPath = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
            var appFolder = Path.Combine(appDataPath, "ClipSync");
            Directory.CreateDirectory(appFolder);
            _settingsFilePath = Path.Combine(appFolder, "settings.json");
            _current = Load();
        }

        /// <summary>The live settings object. Mutate then call <see cref="Save"/>.</summary>
        public static AppSettings Current => _current;

        /// <summary>Per-device preference lookup over the live settings object.</summary>
        public static DevicePrefsStore Devices => new(_current.DevicePrefs);

        /// <summary>
        /// Stable per-install identity, created on first use. Used only to break ties when both
        /// ends dialled at once (see BluetoothLinkManager.Arbitrate).
        /// </summary>
        public static string NodeId
        {
            get
            {
                if (string.IsNullOrWhiteSpace(_current.NodeId))
                {
                    _current.NodeId = Guid.NewGuid().ToString("n").Substring(0, 16);
                    Save();
                }
                return _current.NodeId;
            }
        }

        public static event Action? Changed;

        public static AppSettings LoadSettings() => Load();

        public static AppSettings Load()
        {
            if (!File.Exists(_settingsFilePath)) return new AppSettings();
            AppSettings loaded;
            try
            {
                var json = File.ReadAllText(_settingsFilePath);
                loaded = Newtonsoft.Json.JsonConvert.DeserializeObject<AppSettings>(json) ?? new AppSettings();
            }
            catch
            {
                return new AppSettings();
            }

            // "Require" with no key refuses every peer (no-psk), which on screen is a dead app:
            // connect, drop, retry, forever. 2.2.0 could store that combination; heal it on load
            // and write the healed value back, so the settings page stops showing a mode that is
            // not what the policy is actually doing.
            if (string.Equals(loaded.SecurityMode, "require", StringComparison.OrdinalIgnoreCase)
                && Security.PskVault.Unprotect(loaded.PskProtected).Length == 0)
            {
                loaded.SecurityMode = "auto";
                try
                {
                    File.WriteAllText(_settingsFilePath,
                        Newtonsoft.Json.JsonConvert.SerializeObject(loaded));
                }
                catch { }
                Security.CbbSecurityLog.Write("settings",
                    "stored securityMode=require without a key is unconnectable; persisted as auto");
            }
            return loaded;
        }

        public static void Save()
        {
            SaveSettings(_current);
        }

        public static void SaveSettings(AppSettings settings)
        {
            try
            {
                var json = Newtonsoft.Json.JsonConvert.SerializeObject(settings);
                File.WriteAllText(_settingsFilePath, json);
            }
            catch
            {
                // ignore save failures: settings are not critical
            }
            if (!ReferenceEquals(settings, _current)) _current = settings;
            Changed?.Invoke();
        }

        /// <summary>Resets to defaults (used by the settings view).</summary>
        public static void ResetToDefaults()
        {
            _current = new AppSettings();
            Save();
        }
    }
}
