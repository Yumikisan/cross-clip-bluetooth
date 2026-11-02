using System;
using System.IO;
using System.Text;

namespace ClipSyncWindows.Security
{
    /// <summary>How badly the app insists on layer-2 encryption.</summary>
    public enum SecurityMode
    {
        /// <summary>Plaintext frames even when a key is configured (troubleshooting only).</summary>
        Off = 0,

        /// <summary>
        /// Encrypt whenever this device has a pre-shared key, and refuse to send plaintext to a
        /// peer that asks for encryption. Two keyless devices still talk in plaintext.
        /// </summary>
        Auto = 1,

        /// <summary>No key, no link. Legacy (2.1.x) peers are refused outright.</summary>
        Require = 2
    }

    /// <summary>
    /// Immutable snapshot of the local security policy, taken when a link is created. Reading it
    /// once avoids a settings change mid-handshake producing two different keys.
    /// </summary>
    public sealed class SecurityPolicy
    {
        public SecurityPolicy(SecurityMode mode, string passphrase, int kdfIterations)
        {
            Mode = mode;
            // Same normalisation as the wizard and the settings page: whitespace and dashes are
            // noise, so a key typed with or without them still matches the other device.
            Passphrase = PskVault.Normalize(passphrase);
            KdfIterations = kdfIterations <= 0 ? CbbCrypto.DefaultKdfIterations : kdfIterations;
        }

        public SecurityMode Mode { get; private set; }

        /// <summary>Empty means "not configured". Never sent over the wire.</summary>
        public string Passphrase { get; }

        public int KdfIterations { get; }

        public bool HasKey => Passphrase.Length > 0;

        /// <summary>
        /// True when a stored "Require, but no key" was healed into Auto on load. That state is
        /// unconnectable by design (every peer is refused with no-psk) and, left alone, it looks
        /// exactly like a broken app: connect, drop, retry, forever. The settings UI refuses to
        /// create it; this flag covers hand-edited files and configs written by 2.2.0-dev builds.
        /// </summary>
        public bool RequireDowngraded { get; private set; }

        /// <summary>True when this side will ask for an encrypted session.</summary>
        public bool WantsEncryption => Mode != SecurityMode.Off && HasKey;

        /// <summary>True when a plaintext peer must be refused.</summary>
        public bool RefusesPlaintext => Mode == SecurityMode.Require || (Mode == SecurityMode.Auto && HasKey);

        public static SecurityPolicy FromSettings(Models.AppSettings settings)
        {
            var requested = settings.SecurityMode?.Trim().ToLowerInvariant() switch
            {
                "off" => SecurityMode.Off,
                "require" => SecurityMode.Require,
                _ => SecurityMode.Auto
            };
            var key = PskVault.Unprotect(settings.PskProtected);

            var policy = new SecurityPolicy(requested, key, CbbCrypto.DefaultKdfIterations);
            if (requested == SecurityMode.Require && key.Length == 0)
            {
                policy.Mode = SecurityMode.Auto;
                policy.RequireDowngraded = true;
                CbbSecurityLog.Write("policy",
                    "stored mode=require without a key is unconnectable; treating this link as auto");
            }
            return policy;
        }
    }

    /// <summary>
    /// Appends to %APPDATA%\ClipSync\security.log. Diagnostic only — it records frame sizes,
    /// sequence numbers and handshake transitions, never payload bytes. Plaintext dumps exist but
    /// are compiled out of Release builds (see <see cref="DebugPlaintextEnabled"/>).
    /// </summary>
    public static class CbbSecurityLog
    {
        private static readonly object Gate = new();
        private static string? _path;

        /// <summary>Set from settings; Release builds additionally require #if DEBUG.</summary>
        public static bool Enabled { get; set; } = true;

        /// <summary>
        /// Sends subsequent lines somewhere other than the live log. The self-test uses this: its
        /// deliberately-wrong handshakes look identical to a real failure, and mixing them into
        /// %APPDATA%\ClipSync\security.log makes a routine self-test look like a field incident.
        /// </summary>
        public static void Redirect(string path)
        {
            lock (Gate)
            {
                _path = path;
                try { Directory.CreateDirectory(System.IO.Path.GetDirectoryName(path)!); } catch { }
            }
        }

        public static string Path
        {
            get
            {
                if (_path != null) return _path;
                var dir = System.IO.Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ClipSync");
                Directory.CreateDirectory(dir);
                _path = System.IO.Path.Combine(dir, "security.log");
                return _path;
            }
        }

        /// <summary>
        /// Payload logging is only ever available in debug builds. A released binary cannot be
        /// talked into dumping clipboard contents by flipping a setting, which is the whole point.
        /// </summary>
        public static bool DebugPlaintextEnabled
        {
            get
            {
#if DEBUG
                return true;
#else
                return false;
#endif
            }
        }

        public static void Write(string peer, string message)
        {
            if (!Enabled) return;
            try
            {
                var line = $"[{DateTime.Now:yyyy-MM-dd HH:mm:ss.fff}] [{peer}] {message}{Environment.NewLine}";
                lock (Gate)
                {
                    var info = new FileInfo(Path);
                    if (info.Exists && info.Length > 2 * 1024 * 1024)
                    {
                        // Keep the log bounded: rotate once, keep the previous generation.
                        var old = Path + ".1";
                        try { if (File.Exists(old)) File.Delete(old); } catch { }
                        try { File.Move(Path, old); } catch { }
                    }
                    File.AppendAllText(Path, line, Encoding.UTF8);
                }
            }
            catch
            {
                // Logging must never take the app down.
            }
        }

        /// <summary>Dumps a payload — debug builds only, and truncated.</summary>
        public static void WritePlaintext(string peer, string direction, byte[] payload)
        {
#if DEBUG
            if (!Enabled || !DebugPlaintextEnabled) return;
            var preview = payload.Length > 512
                ? Encoding.UTF8.GetString(payload, 0, 512)
                : Encoding.UTF8.GetString(payload);
            Write(peer, $"{direction} plaintext({payload.Length})={preview}");
#endif
        }
    }
}
