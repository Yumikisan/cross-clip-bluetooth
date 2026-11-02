using System;
using System.Text;

namespace ClipSyncWindows.Security
{
    /// <summary>
    /// Generates the pre-shared key offered by the first-run wizard. The alphabet is Crockford
    /// base32 (no I, L, O or U) so the key survives being read off one screen and typed into the
    /// other without "1 or l or I" arguments.
    ///
    /// 16 symbols = 80 bits. Grouped as XXXX-XXXX-XXXX-XXXX for transcription; the dashes are
    /// stripped before storage, so typing them or not makes no difference.
    /// </summary>
    public static class PskGenerator
    {
        private const string Alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        private const int Symbols = 16;
        private const int GroupSize = 4;

        public static string Generate()
        {
            var sb = new StringBuilder(Symbols + Symbols / GroupSize);
            for (int i = 0; i < Symbols; i++)
            {
                if (i > 0 && i % GroupSize == 0) sb.Append('-');
                sb.Append(Alphabet[CbbCrypto.RandomBytes(1)[0] % Alphabet.Length]);
            }
            return sb.ToString();
        }

        /// <summary>Length of the generated key once dashes are gone.</summary>
        public static int KeyLength => Symbols;
    }
}
