using System;
using System.Text;
using Newtonsoft.Json;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Wire protocol version 2. Every frame is one UTF-8 JSON object terminated by '\n'.
    ///
    /// Legacy v1 peers simply send <c>{"clip":"...","timestamp":"..."}</c>; the parser
    /// still accepts that so old builds keep working.
    /// </summary>
    public static class Wire
    {
        public const int Version = 2;

        // frame types
        public const string TypeText = "text";
        public const string TypeFileBegin = "file_begin";
        public const string TypeFileChunk = "file_chunk";
        public const string TypeFileEnd = "file_end";
        public const string TypeAck = "ack";
        public const string TypeError = "error";
        public const string TypePing = "ping";
        public const string TypePong = "pong";
        public const string TypeHello = "hello";

        // ---- folder transfer (v4) ----
        // A folder is described by one "mkdir" frame per directory plus the ordinary
        // file_begin / file_chunk / file_end triple for every file, with "path" carrying the
        // relative location inside the folder. Old peers never see these frames: the sender
        // only emits them after the peer advertises folder capability in auth_hello.
        public const string TypeFolderBegin = "folder_begin";
        public const string TypeMkdir = "mkdir";
        public const string TypeFolderEnd = "folder_end";

        // Connection authentication (v3). Both ends exchange these right after the socket
        // opens; clipboard/file frames are dropped until the session is authenticated.
        public const string TypeAuthHello = "auth_hello";
        public const string TypeAuthNonce = "auth_nonce";
        public const string TypeAuthConfirm = "auth_confirm";
        public const string TypeAuthResult = "auth_result";

        /// <summary>Upper bound for a single clipboard text frame (1 MiB of text).</summary>
        public const int MaxTextBytes = 1024 * 1024;

        /// <summary>
        /// 8 KiB of raw bytes per chunk (~11 KB once base64 encoded). Frames of 32 KiB+ stall
        /// or get truncated on some RFCOMM stacks, which silently killed whole transfers.
        /// </summary>
        public const int ChunkSize = 8 * 1024;

        /// <summary>
        /// First byte of a binary chunk frame. Base64 costs a third of the link's bandwidth
        /// (8 KiB becomes 10.7 KiB), and on RFCOMM that is the whole difference between 90 and
        /// 120 KB/s, so chunks travel as raw bytes once both ends say they understand them.
        /// A JSON payload always starts with '{' (0x7B), so the magic never collides.
        /// </summary>
        public const byte RawChunkMagic = 0xB1;

        /// <summary>Longest transfer id a raw chunk frame can carry (it is a 1-byte length).</summary>
        public const int MaxRawIdBytes = 64;

        /// <summary>RFCOMM-friendly write slice size.</summary>
        public const int WriteSlice = 4 * 1024;

        // ---- folder transfer limits ----
        /// <summary>Deepest relative path a folder transfer may create (0 = the folder itself).</summary>
        public const int MaxFolderDepth = 6;

        public static string Serialize(WireMessage message)
        {
            return JsonConvert.SerializeObject(message, Formatting.None) + "\n";
        }

        /// <summary>
        /// Binary chunk frame: magic, id length, id, seq (big-endian), then the raw bytes.
        /// Reusing the encrypted data frame means the crypto, sequence numbers and replay window
        /// stay exactly as they were — only the payload shape differs.
        /// </summary>
        public static byte[] BuildRawChunk(string id, int seq, byte[] data)
        {
            var idBytes = Encoding.UTF8.GetBytes(id);
            if (idBytes.Length is 0 or > MaxRawIdBytes)
                throw new ArgumentException("raw chunk id out of range", nameof(id));

            var frame = new byte[2 + idBytes.Length + 4 + data.Length];
            frame[0] = RawChunkMagic;
            frame[1] = (byte)idBytes.Length;
            Buffer.BlockCopy(idBytes, 0, frame, 2, idBytes.Length);

            var at = 2 + idBytes.Length;
            frame[at] = (byte)(seq >> 24);
            frame[at + 1] = (byte)(seq >> 16);
            frame[at + 2] = (byte)(seq >> 8);
            frame[at + 3] = (byte)seq;

            Buffer.BlockCopy(data, 0, frame, at + 4, data.Length);
            return frame;
        }

        /// <summary>
        /// Recognises a binary chunk frame and rebuilds the equivalent file_chunk message.
        /// Returns null for anything malformed, so a stray byte can never be mistaken for data.
        /// </summary>
        public static WireMessage? TryParseRawChunk(byte[] payload)
        {
            if (payload.Length < 7 || payload[0] != RawChunkMagic) return null;

            int idLen = payload[1];
            if (idLen is 0 or > MaxRawIdBytes) return null;
            if (payload.Length < 2 + idLen + 4) return null;

            var id = Encoding.UTF8.GetString(payload, 2, idLen);
            var at = 2 + idLen;
            int seq = (payload[at] << 24) | (payload[at + 1] << 16) | (payload[at + 2] << 8) | payload[at + 3];

            var dataLen = payload.Length - (at + 4);
            if (dataLen <= 0) return null;
            var data = new byte[dataLen];
            Buffer.BlockCopy(payload, at + 4, data, 0, dataLen);

            return new WireMessage
            {
                type = TypeFileChunk,
                id = id,
                seq = seq,
                rawData = data
            };
        }

        /// <summary>
        /// Same JSON without the trailing newline. The secure channel adds its own framing, and
        /// in legacy mode re-adds the '\n' itself.
        /// </summary>
        public static string SerializeBody(WireMessage message)
        {
            return JsonConvert.SerializeObject(message, Formatting.None);
        }

        public static WireMessage? TryParse(string line)
        {
            if (string.IsNullOrWhiteSpace(line)) return null;
            line = line.Trim();
            try
            {
                if (line.StartsWith("{"))
                {
                    var msg = JsonConvert.DeserializeObject<WireMessage>(line);
                    if (msg != null && !string.IsNullOrEmpty(msg.type))
                        return msg;

                    // legacy v1: {"clip":"...","timestamp":"..."}
                    var legacy = JsonConvert.DeserializeObject<LegacyMessage>(line);
                    if (!string.IsNullOrEmpty(legacy?.clip))
                    {
                        return new WireMessage
                        {
                            v = 1,
                            type = TypeText,
                            text = legacy!.clip,
                            ts = long.TryParse(legacy.timestamp, out var t) ? t : 0
                        };
                    }
                    return null;
                }

                // Not JSON at all: treat the whole line as plain text (very old peers).
                return new WireMessage { v = 1, type = TypeText, text = line };
            }
            catch (JsonException)
            {
                return new WireMessage { v = 1, type = TypeText, text = line };
            }
        }

        private sealed class LegacyMessage
        {
            public string? clip { get; set; }
            public string? timestamp { get; set; }
        }
    }

    public class WireMessage
    {
        public int v { get; set; } = Wire.Version;

        public string type { get; set; } = Wire.TypeText;

        /// <summary>Transfer id, shared by all frames of one clipboard item.</summary>
        public string? id { get; set; }

        public string? text { get; set; }

        public string? name { get; set; }

        /// <summary>
        /// Path relative to the transfer root, using '/' as separator ("sub/dir/a.png").
        /// Sanitised by the receiver: both ends already reject "..", absolute paths and
        /// drive letters, because the sender may be a device we do not trust.
        /// </summary>
        public string? path { get; set; }

        /// <summary>CRC32 of the complete file, sent on file_end (folder transfers only).</summary>
        public long crc { get; set; }

        /// <summary>Number of files in a folder transfer (folder_end / diagnostics).</summary>
        public int files { get; set; }

        public string? mime { get; set; }

        /// <summary>Sender device name.</summary>
        public string? from { get; set; }

        /// <summary>
        /// Sender's install identity (auth_hello only). Both ends announce it so a duplicate
        /// pair of sockets can be arbitrated the same way from either side.
        /// </summary>
        public string? node { get; set; }

        public long size { get; set; }

        public int seq { get; set; }

        public string? data { get; set; }

        public bool? ok { get; set; }

        public string? error { get; set; }

        /// <summary>Human readable reason carried by an "error" frame.</summary>
        public string? reason { get; set; }

        // ---- authentication frames ----

        /// <summary>Session id of the authentication exchange that carries this frame.</summary>
        public string? sid { get; set; }

        /// <summary>32 lowercase hex characters: this side's random nonce.</summary>
        public string? nonce { get; set; }

        /// <summary>Six digit code shown to the user (diagnostics only, never trusted).</summary>
        public string? code { get; set; }

        /// <summary>Set by auth_hello: the sender asks for a confirmation code.</summary>
        public bool need { get; set; }

        public long ts { get; set; } = DateTimeOffset.Now.ToUnixTimeMilliseconds();

        /// <summary>
        /// Set on ping/pong when the sender understands binary chunk frames. Ping piggybacks on
        /// the heartbeat the link already sends, so capability costs no extra round trip and an
        /// older peer (which never sets it) simply keeps receiving base64 chunks.
        /// </summary>
        public int raw { get; set; }

        /// <summary>
        /// Chunk bytes carried by a binary frame. Never serialised: it exists so the receive
        /// path can hand raw bytes to the file sink without a base64 round trip.
        /// </summary>
        [JsonIgnore]
        public byte[]? rawData { get; set; }
    }
}
