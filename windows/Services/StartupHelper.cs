using System;
using Microsoft.Win32;

namespace ClipSyncWindows.Services
{
    /// <summary>Adds/removes the per-user Run entry used for "start with Windows".</summary>
    public static class StartupHelper
    {
        private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
        private const string ValueName = "Cross Clip";

        /// <summary>
        /// Name used by the pre-rename builds. Left in place it would launch the app a second
        /// time from a stale path, so it is adopted on the first toggle and deleted afterwards.
        /// </summary>
        private const string LegacyValueName = "ClipSync";

        public static bool IsEnabled()
        {
            try
            {
                using var key = Registry.CurrentUser.OpenSubKey(RunKey);
                if (key == null) return false;
                if (key.GetValue(ValueName) is string s && s.Length > 0) return true;

                // An upgrading user already asked for "start with Windows"; report it as on so the
                // toggle reflects their intent and the entry gets rewritten to the new name.
                return key.GetValue(LegacyValueName) is string legacy && legacy.Length > 0;
            }
            catch
            {
                return false;
            }
        }

        public static void SetEnabled(bool enabled)
        {
            try
            {
                using var key = Registry.CurrentUser.CreateSubKey(RunKey, writable: true);
                if (key == null) return;

                if (enabled)
                {
                    var exe = Environment.ProcessPath;
                    if (!string.IsNullOrEmpty(exe))
                        key.SetValue(ValueName, $"\"{exe}\" --minimized");
                }
                else if (key.GetValue(ValueName) != null)
                {
                    key.DeleteValue(ValueName, throwOnMissingValue: false);
                }

                // Either way the legacy entry goes: on, because two copies must not start; off,
                // because the user just turned startup off.
                if (key.GetValue(LegacyValueName) != null)
                    key.DeleteValue(LegacyValueName, throwOnMissingValue: false);
            }
            catch
            {
                // registry access denied: silently ignore, the checkbox will snap back
            }
        }
    }
}
