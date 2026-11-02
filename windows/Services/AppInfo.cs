using System;
using System.Reflection;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Single source of truth for the version string. The window title, the tray menu and the
    /// settings page all read it from here, so a version bump only has to touch the csproj.
    /// </summary>
    public static class AppInfo
    {
        /// <summary>e.g. "2.0.17" (Major.Minor.Build of the release version).</summary>
        public static string Version { get; } = ResolveVersion();

        /// <summary>Full product name, used on the title bar, installer and window chrome.</summary>
        public const string ProductName = "Cross Clip Bluetooth";

        /// <summary>Internal short name: build ids, package prefixes, protocol labels.</summary>
        public const string ShortName = "CCB";

        /// <summary>What the main header shows next to the Bluetooth glyph.</summary>
        public const string Headline = "Cross Clip";

        /// <summary>e.g. "Cross Clip Bluetooth v2.2.3".</summary>
        public static string Display => $"{ProductName} v{Version}";

        /// <summary>
        /// Prefers <c>AssemblyFileVersion</c> over <c>AssemblyVersion</c>: the file version tracks
        /// the release number, while the assembly version is only bumped when the binding contract
        /// changes -- reading the latter is how the title sat on v2.0.9 for a dozen releases.
        /// </summary>
        private static string ResolveVersion()
        {
            var assembly = Assembly.GetExecutingAssembly();

            if (assembly.GetCustomAttribute<AssemblyFileVersionAttribute>()?.Version is { Length: > 0 } file)
                return Trim(file);

            if (assembly.GetName().Version is { } version)
                return $"{version.Major}.{version.Minor}.{version.Build}";

            return "0.0.0";
        }

        /// <summary>"2.0.17.0" -> "2.0.17"; keeps a fourth component only when it is meaningful.</summary>
        private static string Trim(string raw)
        {
            var parts = raw.Split('.');
            var keep = parts.Length >= 3 ? 3 : parts.Length;
            if (parts.Length == 4 && parts[3] != "0")
                keep = 4;

            return string.Join('.', parts[..keep]);
        }
    }
}
