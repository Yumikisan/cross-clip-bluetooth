using System;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// CRC32 (the IEEE 802.3 polynomial 0xEDB88320), which is exactly what
    /// <c>java.util.zip.CRC32</c> computes on the other end. Same algorithm, same numbers — the
    /// check on the phone only works if these two agree bit for bit.
    ///
    /// Fed as the file is streamed, so verifying a transfer costs no extra disk pass.
    /// </summary>
    public sealed class Crc32
    {
        private static readonly uint[] Table = BuildTable();

        private uint _state = 0xFFFFFFFFu;

        public void Update(byte[] buffer, int offset, int count)
        {
            uint crc = _state;
            for (int i = offset; i < offset + count; i++)
                crc = Table[(crc ^ buffer[i]) & 0xFF] ^ (crc >> 8);
            _state = crc;
        }

        public long Value => _state ^ 0xFFFFFFFFu;

        private static uint[] BuildTable()
        {
            var table = new uint[256];
            for (uint i = 0; i < 256; i++)
            {
                uint entry = i;
                for (int bit = 0; bit < 8; bit++)
                    entry = (entry & 1) != 0 ? 0xEDB88320u ^ (entry >> 1) : entry >> 1;
                table[i] = entry;
            }
            return table;
        }
    }
}
