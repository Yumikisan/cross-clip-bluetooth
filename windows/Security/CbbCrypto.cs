using System;
using System.Security.Cryptography;
using System.Text;

namespace ClipSyncWindows.Security
{
    /// <summary>
    /// Every primitive the secure layer needs, in one place, so a future suite can be swapped in
    /// without touching the framing or the state machine.
    ///
    /// MUST stay byte-for-byte identical to CbbCrypto.kt on Android:
    ///   PBKDF2-HMAC-SHA256(passphraseUtf8, salt, iterations) -> 32 bytes
    ///   HMAC-SHA256(key, data)
    ///   AES-256-GCM, 96-bit random nonce, 128-bit tag, AAD = the 16 byte frame header
    /// </summary>
    public static class CbbCrypto
    {
        public const int KeyBytes = 32;
        public const int NonceBytes = 12;
        public const int TagBytes = 16;

        /// <summary>Nonce used by the handshake (32 bytes, hex-free, raw).</summary>
        public const int HandshakeNonceBytes = 32;

        /// <summary>
        /// Iteration count for turning the human passphrase into a key. High enough to make an
        /// offline dictionary attack on a captured handshake expensive, low enough that a phone
        /// finishes the handshake in well under a second.
        /// </summary>
        public const int DefaultKdfIterations = 60_000;

        public const int MinKdfIterations = 10_000;
        public const int MaxKdfIterations = 500_000;

        public static byte[] RandomBytes(int count) => RandomNumberGenerator.GetBytes(count);

        public static byte[] Pbkdf2(string passphrase, byte[] salt, int iterations)
        {
            var clamped = Math.Clamp(iterations, MinKdfIterations, MaxKdfIterations);
            return Rfc2898DeriveBytes.Pbkdf2(
                Encoding.UTF8.GetBytes(passphrase), salt, clamped, HashAlgorithmName.SHA256, KeyBytes);
        }

        public static byte[] Hmac(byte[] key, byte[] data) => HMACSHA256.HashData(key, data);

        public static byte[] Hmac(byte[] key, params byte[][] parts) => Hmac(key, Concat(parts));

        public static byte[] Concat(params byte[][] parts)
        {
            int total = 0;
            foreach (var p in parts) total += p?.Length ?? 0;
            var outBytes = new byte[total];
            int offset = 0;
            foreach (var p in parts)
            {
                if (p == null || p.Length == 0) continue;
                Buffer.BlockCopy(p, 0, outBytes, offset, p.Length);
                offset += p.Length;
            }
            return outBytes;
        }

        public static byte[] Ascii(string text) => Encoding.ASCII.GetBytes(text);

        public static byte[] Utf8(string text) => Encoding.UTF8.GetBytes(text);

        /// <summary>Constant time compare: a byte-by-byte early exit leaks how much matched.</summary>
        public static bool FixedTimeEquals(byte[] a, byte[] b)
        {
            if (a.Length != b.Length) return false;
            return CryptographicOperations.FixedTimeEquals(a, b);
        }

        /// <summary>Encrypts in place of a stream cipher: returns ciphertext, writes the tag out.</summary>
        public static byte[] AesGcmEncrypt(byte[] key, byte[] nonce, byte[] plaintext, byte[] aad, out byte[] tag)
        {
            var cipher = new byte[plaintext.Length];
            tag = new byte[TagBytes];
            using var gcm = new AesGcm(key, TagBytes);
            gcm.Encrypt(nonce, plaintext, cipher, tag, aad);
            return cipher;
        }

        /// <summary>Returns null when the tag does not verify: the frame is discarded, not decrypted.</summary>
        public static byte[]? AesGcmDecrypt(byte[] key, byte[] nonce, byte[] ciphertext, byte[] tag, byte[] aad)
        {
            try
            {
                var plain = new byte[ciphertext.Length];
                using var gcm = new AesGcm(key, TagBytes);
                gcm.Decrypt(nonce, ciphertext, tag, plain, aad);
                return plain;
            }
            catch (CryptographicException)
            {
                return null;
            }
        }

        /// <summary>Human readable fingerprint of a key, for diagnostics only (never the key).</summary>
        public static string KeyFingerprint(byte[] key)
        {
            if (key.Length == 0) return "none";
            var digest = SHA256.HashData(key);
            return Convert.ToHexString(digest, 0, 4).ToLowerInvariant();
        }
    }
}
