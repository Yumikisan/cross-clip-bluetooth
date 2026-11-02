using System;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace ClipSyncWindows.Security
{
    /// <summary>
    /// Stores the pre-shared passphrase at rest, protected with DPAPI (the Windows per-user key
    /// store), so a copied settings.json on another account or machine is useless.
    ///
    /// The wire format is a short prefix plus base64:
    ///   "dpapi:..."  DPAPI protected (normal case)
    ///   "b64:..."    fallback when DPAPI is unavailable — still obfuscated, not encrypted
    ///   ""           no key configured
    /// </summary>
    public static class PskVault
    {
        private const string DpapiPrefix = "dpapi:";
        private const string FallbackPrefix = "b64:";
        private const int CryptProtectUiForbidden = 0x1;

        [StructLayout(LayoutKind.Sequential)]
        private struct DataBlob
        {
            public int cbData;
            public IntPtr pbData;
        }

        [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool CryptProtectData(ref DataBlob dataIn, string? description,
            IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, ref DataBlob dataOut);

        [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool CryptUnprotectData(ref DataBlob dataIn, IntPtr description,
            IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, ref DataBlob dataOut);

        [DllImport("kernel32.dll")]
        private static extern IntPtr LocalFree(IntPtr handle);

        public static string Protect(string plaintext)
        {
            if (string.IsNullOrEmpty(plaintext)) return "";
            var bytes = Encoding.UTF8.GetBytes(plaintext);

            var input = new DataBlob
            {
                cbData = bytes.Length,
                pbData = Marshal.AllocHGlobal(bytes.Length)
            };
            try
            {
                Marshal.Copy(bytes, 0, input.pbData, bytes.Length);
                var output = new DataBlob();
                if (CryptProtectData(ref input, "CBB-PSK", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero,
                        CryptProtectUiForbidden, ref output))
                {
                    try
                    {
                        var protectedBytes = new byte[output.cbData];
                        Marshal.Copy(output.pbData, protectedBytes, 0, output.cbData);
                        return DpapiPrefix + Convert.ToBase64String(protectedBytes);
                    }
                    finally
                    {
                        LocalFree(output.pbData);
                    }
                }
            }
            catch
            {
                // fall through to the obfuscated form
            }
            finally
            {
                Marshal.FreeHGlobal(input.pbData);
            }

            CbbSecurityLog.Write("settings", "dpapi-unavailable, PSK stored base64 only");
            return FallbackPrefix + Convert.ToBase64String(bytes);
        }

        public static string Unprotect(string? stored)
        {
            if (string.IsNullOrEmpty(stored)) return "";
            try
            {
                if (stored.StartsWith(FallbackPrefix, StringComparison.Ordinal))
                    return Encoding.UTF8.GetString(Convert.FromBase64String(stored[FallbackPrefix.Length..]));

                if (!stored.StartsWith(DpapiPrefix, StringComparison.Ordinal)) return "";

                var protectedBytes = Convert.FromBase64String(stored[DpapiPrefix.Length..]);
                var input = new DataBlob
                {
                    cbData = protectedBytes.Length,
                    pbData = Marshal.AllocHGlobal(protectedBytes.Length)
                };
                try
                {
                    Marshal.Copy(protectedBytes, 0, input.pbData, protectedBytes.Length);
                    var output = new DataBlob();
                    if (!CryptUnprotectData(ref input, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero,
                            0, ref output))
                        return "";

                    try
                    {
                        var plain = new byte[output.cbData];
                        Marshal.Copy(output.pbData, plain, 0, output.cbData);
                        return Encoding.UTF8.GetString(plain);
                    }
                    finally
                    {
                        LocalFree(output.pbData);
                    }
                }
                finally
                {
                    Marshal.FreeHGlobal(input.pbData);
                }
            }
            catch
            {
                return "";
            }
        }

        /// <summary>
        /// Canonical form of user input: whitespace and dashes are transcription noise, not key
        /// material. Both ends strip them identically, so a key typed as "ABCD-EFGH..." and one
        /// typed as "ABCDEFGH..." still meet.
        /// </summary>
        public static string Normalize(string? raw)
        {
            if (string.IsNullOrEmpty(raw)) return "";
            var sb = new StringBuilder(raw.Length);
            foreach (var c in raw)
                if (!char.IsWhiteSpace(c) && c != '-') sb.Append(c);
            return sb.ToString();
        }

        /// <summary>
        /// A short fingerprint of the configured key so both ends can be compared on screen
        /// without the key itself ever being displayed. Not reversible, and useless to an attacker
        /// who cannot guess the passphrase.
        /// </summary>
        public static string Fingerprint(string plaintext)
        {
            if (string.IsNullOrEmpty(plaintext)) return "";
            var digest = SHA256.HashData(Encoding.UTF8.GetBytes(plaintext));
            return Convert.ToHexString(digest, 0, 3).ToLowerInvariant();
        }

        /// <summary>
        /// 16 hex characters (the first 8 bytes of SHA-256) for the diagnostic log only. The
        /// six-character fingerprint shown in the UI is too short to prove two devices hold the
        /// same passphrase when a handshake fails: it is a 24-bit value, and it also says nothing
        /// about which string the link actually used. Two logs carrying the same tag prove the
        /// keys agree; different tags localise the fault to the key itself, not the protocol.
        /// </summary>
        public static string DebugTag(string? plaintext)
        {
            if (string.IsNullOrEmpty(plaintext)) return "none";
            var digest = SHA256.HashData(Encoding.UTF8.GetBytes(plaintext));
            return Convert.ToHexString(digest, 0, 8).ToLowerInvariant();
        }
    }
}
