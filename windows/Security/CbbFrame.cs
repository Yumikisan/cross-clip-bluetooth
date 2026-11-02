using System;
using System.Buffers.Binary;

namespace ClipSyncWindows.Security
{
    /// <summary>
    /// Frame types of the CBB binary protocol (layer 2, above RFCOMM). See
    /// docs/02-帧协议规范.md — the layout is frozen so future versions can interoperate.
    /// </summary>
    public enum CbbFrameType : byte
    {
        HsHello = 1,
        HsChallenge = 2,
        HsConfirm = 3,
        HsAccept = 4,
        HsReject = 5,
        Data = 6
    }

    /// <summary>
    /// 16-byte fixed header + opaque body.
    ///
    ///   offset size  field
    ///   0      2     magic  'C','B' (0x43 0x42)
    ///   2      1     version (1)
    ///   3      1     type
    ///   4      1     flags  bit0 = body is AES-GCM protected
    ///   5      1     reserved (0)
    ///   6      2     reserved (0)
    ///   8      4     seq    u32 LE
    ///   12     4     bodyLen u32 LE
    ///
    /// The header is also the GCM additional authenticated data, so seq/type/flags cannot be
    /// tampered with without breaking the tag.
    /// </summary>
    public static class FrameCodec
    {
        public const byte Magic0 = 0x43;   // 'C'
        public const byte Magic1 = 0x42;   // 'B'
        public const byte Version = 1;
        public const int HeaderLen = 16;

        /// <summary>Hard ceiling for one body. Streams larger than this are split by the caller.</summary>
        public const int MaxBody = 12 * 1024 * 1024;

        public const byte FlagEncrypted = 0x01;

        public static byte[] EncodeHeader(CbbFrameType type, byte flags, uint seq, int bodyLen)
        {
            if (bodyLen < 0 || bodyLen > MaxBody) throw new ArgumentException("body too large", nameof(bodyLen));
            var header = new byte[HeaderLen];
            header[0] = Magic0;
            header[1] = Magic1;
            header[2] = Version;
            header[3] = (byte)type;
            header[4] = flags;
            header[5] = 0;
            // header[6..8] reserved
            BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(8, 4), seq);
            BinaryPrimitives.WriteUInt32LittleEndian(header.AsSpan(12, 4), (uint)bodyLen);
            return header;
        }

        public static byte[] Encode(CbbFrameType type, byte flags, uint seq, byte[] body)
        {
            var frame = new byte[HeaderLen + body.Length];
            Buffer.BlockCopy(EncodeHeader(type, flags, seq, body.Length), 0, frame, 0, HeaderLen);
            Buffer.BlockCopy(body, 0, frame, HeaderLen, body.Length);
            return frame;
        }

        public static bool TryReadHeader(ReadOnlySpan<byte> header, out CbbFrameType type,
            out byte flags, out uint seq, out int bodyLen)
        {
            type = default;
            flags = 0;
            seq = 0;
            bodyLen = 0;
            if (header.Length < HeaderLen) return false;
            if (header[0] != Magic0 || header[1] != Magic1) return false;
            if (header[2] != Version) return false;
            type = (CbbFrameType)header[3];
            flags = header[4];
            seq = BinaryPrimitives.ReadUInt32LittleEndian(header.Slice(8, 4));
            var len = BinaryPrimitives.ReadUInt32LittleEndian(header.Slice(12, 4));
            if (len > MaxBody) return false;
            bodyLen = (int)len;
            return true;
        }
    }
}
