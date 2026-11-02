using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using ClipSyncWindows.Services;
using ClipSyncWindows.Services.Transport;

namespace ClipSyncWindows.Security
{
    /// <summary>
    /// Self-contained verification of the layer-2 security stack: two live channels over an
    /// in-memory byte pipe, no Bluetooth required.
    ///
    /// Run it with <c>CBB-Windows.exe --selftest</c>. It is the check to repeat after touching
    /// anything in this folder, and it is also the reference for how the Android side must behave.
    /// </summary>
    public static class SecuritySelfTest
    {
        public sealed record TestResult(string Name, bool Passed, string Detail);

        public static IReadOnlyList<TestResult> Run()
        {
            // Keep the diagnostics of this run away from the live link log: this test fails
            // handshakes on purpose, and those lines are indistinguishable from a real incident.
            var shared = System.IO.Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ClipSync");
            try
            {
                Directory.CreateDirectory(shared);
                CbbSecurityLog.Redirect(System.IO.Path.Combine(shared, "selftest-security.log"));
            }
            catch { }

            var results = new List<TestResult>();
            results.Add(Capture("handshake+exchange", HandshakeAndExchange));
            results.Add(Capture("wrong-psk-rejected", WrongKey));
            results.Add(Capture("tampered-frame-dropped", TamperedFrame));
            results.Add(Capture("replayed-frame-dropped", ReplayedFrame));
            results.Add(Capture("plaintext-when-keyless", PlaintextMode));
            results.Add(Capture("require-without-key", RequireWithoutKey));
            results.Add(Capture("byte-fragmentation", Fragmentation));
            results.Add(Capture("legacy-peer-fallback", LegacyPeer));
            results.Add(Capture("replay-window", ReplayWindowLogic));
            results.Add(Capture("transport-closed-on-eof", TransportClosedOnEof));
            results.Add(Capture("key-schedule-vectors", KeyScheduleVectors));
            results.Add(Capture("aes-gcm-vector", AesGcmVector));
            // Real blocking stream (loopback TCP), not the in-memory pipe: proves the probe
            // exchange and handshake behave on a stream that can return partial reads, which is
            // what RFCOMM actually does. These two are the closest thing to a device test that
            // can run without a phone.
            results.Add(Capture("tcp-pair-plaintext", TcpPlaintextPair));
            results.Add(Capture("tcp-pair-encrypted", TcpEncryptedPair));
            results.Add(Capture("policy-require-heals", PolicyRequireHeals));
            results.Add(Capture("xlang-handshake-vector", XlangHandshakeVector));
            results.Add(Capture("wizard-generated-key", WizardGeneratedKey));
            // The transport seam: business code resolves a backend through the registry, so
            // "no connector, no link" and "reserved kind without a connector" must both hold.
            results.Add(Capture("transport-contract", TransportContractShape));
            // Binary chunk frames skip base64 in the hot path. A layout mistake there corrupts
            // files instead of merely slowing them, so the byte order is checked on every run.
            results.Add(Capture("raw-chunk-frame", RawChunkFrameShape));
            return results;
        }

        public static string RunAndWrite(string? extraPath)
        {
            var started = DateTime.Now;
            var results = Run();
            var sb = new StringBuilder();
            sb.AppendLine($"CBB security self-test — {started:yyyy-MM-dd HH:mm:ss}");
            sb.AppendLine();
            int passed = 0;
            foreach (var r in results)
            {
                if (r.Passed) passed++;
                sb.AppendLine($"{(r.Passed ? "PASS" : "FAIL")}  {r.Name}  {r.Detail}");
            }
            sb.AppendLine();
            sb.AppendLine($"{passed}/{results.Count} passed");

            var text = sb.ToString();
            try
            {
                var target = System.IO.Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "ClipSync");
                Directory.CreateDirectory(target);
                File.WriteAllText(System.IO.Path.Combine(target, "selftest.log"), text, Encoding.UTF8);
            }
            catch { }

            if (!string.IsNullOrEmpty(extraPath))
            {
                try { File.WriteAllText(extraPath, text, Encoding.UTF8); } catch { }
            }
            return text;
        }

        private static TestResult Capture(string name, Func<(bool ok, string detail)> body)
        {
            try
            {
                var (ok, detail) = body();
                return new TestResult(name, ok, detail);
            }
            catch (Exception ex)
            {
                return new TestResult(name, false, "exception: " + ex.GetType().Name + " " + ex.Message);
            }
        }

        // ------------------------------------------------------------------ tests

        private static (bool, string) HandshakeAndExchange()
        {
            using var pair = new Pair("auto-key", "one-two-three-four", "one-two-three-four");
            if (!pair.WaitReady(4000)) return (false, $"not ready: A={pair.A.State}/{pair.A.FailureReason} B={pair.B.State}/{pair.B.FailureReason}");
            if (!pair.A.IsEncrypted || !pair.B.IsEncrypted) return (false, "session reported as plaintext");

            pair.SendA("hello-1");
            pair.SendA("hello-2");
            if (!pair.WaitReceived(2, 4000)) return (false, $"received {pair.ReceivedB.Count} of 2");
            if (pair.TextB(0) != "hello-1" || pair.TextB(1) != "hello-2")
                return (false, "payload mismatch: " + string.Join(",", pair.ReceivedB.ConvertAll(Encoding.UTF8.GetString)));

            // Reverse direction must work too (directional keys, not a shared one).
            pair.SendB("back");
            if (!pair.WaitReceivedA(1, 3000)) return (false, "reverse direction delivered nothing");
            return (true, "encrypted both ways");
        }

        private static (bool, string) WrongKey()
        {
            using var pair = new Pair("auto-key", "correct-key", "wrong-key");
            var ok = WaitFor(() => pair.A.State == SecureState.Failed && pair.B.State == SecureState.Failed, 6000);
            if (!ok) return (false, $"states A={pair.A.State} B={pair.B.State}");
            if (!pair.A.FailureReason.Contains("psk") && !pair.A.FailureReason.Contains("reject"))
                return (false, "unexpected reason: " + pair.A.FailureReason);
            return (true, "A=" + pair.A.FailureReason + " B=" + pair.B.FailureReason);
        }

        private static (bool, string) TamperedFrame()
        {
            using var pair = new Pair("auto-key", "tamper-key", "tamper-key");
            if (!pair.WaitReady(4000)) return (false, "not ready");

            pair.SendA("first");
            if (!pair.WaitReceived(1, 3000)) return (false, "baseline payload lost");

            var frame = pair.LastFrameFromA();
            if (frame == null) return (false, "no frame captured");
            var copy = (byte[])frame.Clone();
            // Flip a ciphertext byte: header stays valid, so this exercises the GCM tag path
            // instead of the frame parser.
            int index = FrameCodec.HeaderLen + CbbCrypto.NonceBytes + 1;
            if (index >= copy.Length) return (false, "frame too short to tamper");
            copy[index] ^= 0xFF;
            pair.InjectToB(copy);

            pair.SendA("second");
            if (!pair.WaitReceived(2, 3000)) return (false, "link died after tampering (expected: drop and continue)");

            var texts = pair.ReceivedB.ConvertAll(Encoding.UTF8.GetString);
            if (texts.Count != 2 || texts[0] != "first" || texts[1] != "second")
                return (false, "tampered frame leaked: " + string.Join("|", texts));
            return (true, "tampered dropped, next payload ok");
        }

        private static (bool, string) ReplayedFrame()
        {
            using var pair = new Pair("auto-key", "replay-key", "replay-key");
            if (!pair.WaitReady(4000)) return (false, "not ready");

            pair.SendA("once");
            if (!pair.WaitReceived(1, 3000)) return (false, "payload lost");

            var frame = pair.LastFrameFromA();
            if (frame == null) return (false, "no frame captured");
            pair.InjectToB(frame);            // replay the exact frame

            pair.SendA("twice");
            WaitFor(() => pair.ReceivedB.Count >= 2, 2500);
            var texts = pair.ReceivedB.ConvertAll(Encoding.UTF8.GetString);
            if (texts.Count != 2) return (false, "delivered " + texts.Count + ": " + string.Join(",", texts));
            if (texts[1] != "twice") return (false, "unexpected second payload: " + texts[1]);
            return (true, "duplicate suppressed");
        }

        private static (bool, string) PlaintextMode()
        {
            using var pair = new Pair("auto-nokey", "", "", expectedEncrypted: false);
            if (!pair.WaitReady(4000)) return (false, $"not ready: A={pair.A.State}/{pair.A.FailureReason}");
            if (pair.A.IsEncrypted || pair.B.IsEncrypted) return (false, "keyless pair claimed encryption");
            pair.SendA("plain");
            if (!pair.WaitReceived(1, 3000)) return (false, "plaintext payload lost");
            return (true, "plaintext by policy, both keyless");
        }

        private static (bool, string) RequireWithoutKey()
        {
            using var pair = new Pair("require-nokey", "", "", expectedEncrypted: false);
            var ok = WaitFor(() => pair.A.State == SecureState.Failed, 5000);
            if (!ok) return (false, "require-mode link did not fail: " + pair.A.State);
            return (true, "refused: " + pair.A.FailureReason);
        }

        private static (bool, string) Fragmentation()
        {
            using var pair = new Pair("auto-key", "frag-key", "frag-key", chunkSize: 3);
            if (!pair.WaitReady(6000)) return (false, "not ready");
            pair.SendA(new string('x', 500));
            if (!pair.WaitReceived(1, 4000)) return (false, "fragmented payload lost");
            if (pair.TextB(0).Length != 500) return (false, "length " + pair.TextB(0).Length);
            return (true, "3-byte fragments reassembled");
        }

        private static (bool, string) LegacyPeer()
        {
            // A 2.1.x peer answers the probe with its own JSON auth frame and knows nothing about
            // binary framing.
            using var pair = new Pair("auto-nokey", "", "", legacyPeer: true, readA: false);
            // Read() is what drives negotiation: the first call sends the probe and returns the
            // first application-level line the peer produced.
            var payload = pair.A.Read();
            if (payload == null) return (false, "no legacy line surfaced: " + pair.A.State + "/" + pair.A.FailureReason);
            if (pair.A.State != SecureState.Ready) return (false, "state " + pair.A.State);
            var line = Encoding.UTF8.GetString(payload);
            if (!line.Contains("auth_hello")) return (false, "unexpected line: " + line);
            return (true, "fell back to the line protocol");
        }

        /// <summary>
        /// A stored "Require, but no key" cannot connect to anything — every peer is refused with
        /// no-psk — so it must be healed on load. This is the state a 2.2.0 build shipped with the
        /// 12-character key rule and it looked exactly like a total connection failure.
        /// </summary>
        /// <summary>
        /// The wizard is the first thing a new user meets, and it writes the value every handshake
        /// derives from. Pin the shape (grouping, alphabet without look-alikes, 16 symbols = 80
        /// bits), the normalisation both ends apply, and the minimum length the two agree on.
        /// </summary>
        private static (bool, string) WizardGeneratedKey()
        {
            var seen = new HashSet<string>(StringComparer.Ordinal);
            for (int i = 0; i < 64; i++)
            {
                var key = PskGenerator.Generate();
                if (key.Length != 19) return (false, "unexpected length " + key.Length);
                if (key.Count(c => c == '-') != 3) return (false, "grouping wrong: " + key);
                var bare = PskVault.Normalize(key);
                if (bare.Length != PskGenerator.KeyLength) return (false, "normalised length " + bare.Length);
                foreach (var c in bare)
                    if (!"0123456789ABCDEFGHJKMNPQRSTVWXYZ".Contains(c))
                        return (false, "look-alike character in " + key);
                if (!seen.Add(bare)) return (false, "duplicate key " + bare);
            }

            // Normalisation has to be identical to the Android copy or two users with the same key
            // still get "key mismatch".
            if (PskVault.Normalize("  abcd-EFGH ijkl-mnop  ") != "abcdEFGHijklmnop")
                return (false, "normalisation drifted");
            if (PskVault.Normalize("-") != "") return (false, "dash-only input not empty");
            if (PskGenerator.KeyLength < SettingsViewModelMinPsk)
                return (false, "generated key shorter than the minimum passphrase");

            return (true, "64 keys well-formed and unique, normalisation stable");
        }

        /// <summary>
        /// Guards the transport contract itself: adding a LAN/HDT backend must be a registration,
        /// never a change to clipboard/file/folder code. These assertions are what a future
        /// backend has to keep true.
        /// </summary>
        private static (bool, string) TransportContractShape()
        {
            var registry = new TransportRegistry();
            var btPeer = new TransportPeer("001122334455", "desk", TransportKind.Rfcomm);

            if (registry.ConnectorFor(btPeer) != null)
                return (false, "resolved a connector before registering any");

            registry.Register(new RfcommConnector(
                available: () => true,
                connect: (p, t, c) => Task.FromResult<IPeerLink?>(null)));

            if (registry.ConnectorFor(btPeer)?.Kind != TransportKind.Rfcomm)
                return (false, "rfcomm connector not chosen for a bluetooth peer");

            // A reserved backend must stay unresolvable until someone writes its connector.
            var lanPeer = new TransportPeer("192.168.1.5:47820", "laptop", TransportKind.LanTcp);
            if (registry.ConnectorFor(lanPeer) != null)
                return (false, "reserved kind resolved with no connector");

            // An unavailable backend (radio off, no Wi-Fi) must never be handed out.
            registry.Register(new RfcommConnector(
                available: () => false,
                connect: (p, t, c) => Task.FromResult<IPeerLink?>(null)));
            if (registry.ConnectorFor(btPeer) != null)
                return (false, "unavailable connector offered to the caller");
            if (registry.AvailableKinds().Count != 0)
                return (false, "available kinds listed while nothing is available");

            if (!typeof(PeerSession).GetInterfaces().Contains(typeof(IPeerLink)))
                return (false, "PeerSession does not implement IPeerLink");

            // Reserved kinds must not be advertised as implemented, or a settings toggle would
            // exist and do nothing.
            var implemented = TransportContract.Implemented;
            if (implemented.Count != 1 || implemented[0] != TransportKind.Rfcomm)
                return (false, "implemented list drifted: " + string.Join(",", implemented));

            return (true, "registry gates by kind and availability, PeerSession fulfils IPeerLink");
        }

        /// <summary>
        /// Binary chunk frame round-trip. Pins the byte layout both ends agreed on: magic, id
        /// length, id, big-endian seq, then the payload. Everything else about the transfer is
        /// unchanged, so a failure here means "chunks are corrupt", not "chunks are slow".
        /// </summary>
        private static (bool, string) RawChunkFrameShape()
        {
            var data = new byte[Wire.ChunkSize];
            for (int i = 0; i < data.Length; i++) data[i] = (byte)(i % 251);

            var frame = Wire.BuildRawChunk("abc123", 0x01020304, data);
            if (frame[0] != Wire.RawChunkMagic) return (false, "magic byte missing");
            if (frame[1] != 6) return (false, "id length not written");

            var back = Wire.TryParseRawChunk(frame);
            if (back == null) return (false, "own frame did not parse");
            if (back.id != "abc123") return (false, "id round-trip lost");
            if (back.seq != 0x01020304) return (false, "seq is not big-endian");
            if (back.rawData == null || back.rawData.Length != data.Length)
                return (false, "payload length changed");
            for (int i = 0; i < data.Length; i++)
                if (back.rawData[i] != data[i]) return (false, "payload byte " + i + " differs");

            // A JSON frame must never be mistaken for a chunk, and truncation must be refused
            // rather than yielding half a file.
            if (Wire.TryParseRawChunk(Encoding.UTF8.GetBytes("{\"type\":\"text\"}")) != null)
                return (false, "json payload parsed as a chunk");
            if (Wire.TryParseRawChunk(frame.Take(3).ToArray()) != null)
                return (false, "truncated frame parsed as a chunk");

            return (true, "magic/len/big-endian-seq/bytes round-trip, json and truncation refused");
        }

        private const int SettingsViewModelMinPsk = 12;

        private static (bool, string) PolicyRequireHeals()
        {
            var broken = SecurityPolicy.FromSettings(new Models.AppSettings
            {
                SecurityMode = "require",
                PskProtected = ""
            });
            if (broken.Mode != SecurityMode.Auto) return (false, "still " + broken.Mode);
            if (!broken.RequireDowngraded) return (false, "downgrade not flagged");
            if (broken.RefusesPlaintext) return (false, "healed policy still refuses plaintext");
            if (broken.WantsEncryption) return (false, "healed policy claims encryption");

            // A keyed require must be left exactly as configured.
            var keyed = SecurityPolicy.FromSettings(new Models.AppSettings
            {
                SecurityMode = "require",
                PskProtected = PskVault.Protect("self-test-passphrase")
            });
            if (keyed.Mode != SecurityMode.Require) return (false, "keyed require changed to " + keyed.Mode);
            if (keyed.RequireDowngraded) return (false, "keyed require flagged as downgraded");
            if (!keyed.WantsEncryption || !keyed.RefusesPlaintext) return (false, "keyed require lost its meaning");

            return (true, "require-without-key healed to auto, keyed require untouched");
        }

        /// <summary>
        /// Pins the handshake MAC input to a vector generated independently (Python hashlib/hmac).
        /// Both platforms must reproduce these exact bytes; if Android's copy drifts — field order,
        /// 0x00 separator, UTF-8 vs ASCII — two users with the same passphrase get "key mismatch".
        /// </summary>
        private static (bool, string) XlangHandshakeVector()
        {
            var nonceC = new byte[32];
            var nonceS = new byte[32];
            for (int i = 0; i < 32; i++) nonceC[i] = (byte)(i + 1);          // 0x01..0x20
            for (int i = 0; i < 32; i++) nonceS[i] = (byte)(0x80 + i);       // 0x80..0x9F

            const string nodeI = "pc-node-0000001";
            const string nodeR = "android-000002";

            var psk = CbbCrypto.Pbkdf2("cbb-xlang-test", CbbCrypto.Concat(nonceC, nonceS), 10_000);
            // Deliberately HandshakeMac (the production MAC), not a hand-rolled HMAC over
            // HandshakeMessage. The previous version re-derived the value its own way, so it kept
            // passing while the real handshake double-HMAC'd on this side.
            var reply = SecureChannel.HandshakeMac(psk, "CBB-HS-REPLY-v1", nodeI, nodeR, nonceC, nonceS);
            var confirm = SecureChannel.HandshakeMac(psk, "CBB-HS-CONFIRM-v1", nodeI, nodeR, nonceC, nonceS);

            const string expectedPsk = "032aac94d50a562175f476d1a15eb7b77804b510c239a0133ca19a3466385951";
            const string expectedReply = "14805e4b785b43e252709ef720df8e8ef7ff2e313b50ae4c6ceb6730e6e68249";
            const string expectedConfirm = "e33c2b73f9658450bbdf74c7c366601113c2976951fc82d50c40303ba2f2f89d";

            var pskHex = Convert.ToHexString(psk).ToLowerInvariant();
            var replyHex = Convert.ToHexString(reply).ToLowerInvariant();
            var confirmHex = Convert.ToHexString(confirm).ToLowerInvariant();

            if (pskHex != expectedPsk) return (false, "psk " + pskHex);
            if (replyHex != expectedReply) return (false, "reply " + replyHex);
            if (confirmHex != expectedConfirm) return (false, "confirm " + confirmHex);
            return (true, "reply/confirm MAC match the Python reference");
        }

        // ------------------------------------------------------------------ real-stream pairs
        /// <summary>Both ends keyless and in Auto: must connect and carry plaintext payloads.</summary>
        private static (bool, string) TcpPlaintextPair()
        {
            using var pair = new TcpPair(SecurityMode.Auto, "", SecurityMode.Auto, "");
            if (!pair.WaitReady(5000))
                return (false, $"not ready: A={pair.A.State}/{pair.A.FailureReason} B={pair.B.State}/{pair.B.FailureReason}");
            if (pair.A.IsEncrypted || pair.B.IsEncrypted) return (false, "keyless pair claimed encryption");

            pair.SendA("tcp-plain-1");
            pair.SendB("tcp-plain-back");
            if (!pair.WaitCounts(1, 1, 4000))
                return (false, $"delivered A->B={pair.CountB} B->A={pair.CountA}");
            if (pair.TextB(0) != "tcp-plain-1" || pair.TextA(0) != "tcp-plain-back")
                return (false, "payload mismatch");
            return (true, "plaintext both ways over a real stream");
        }

        /// <summary>Same key on both ends in Auto: must handshake and carry encrypted payloads.</summary>
        private static (bool, string) TcpEncryptedPair()
        {
            using var pair = new TcpPair(SecurityMode.Auto, "tcp-loopback-key", SecurityMode.Auto, "tcp-loopback-key");
            if (!pair.WaitReady(6000))
                return (false, $"not ready: A={pair.A.State}/{pair.A.FailureReason} B={pair.B.State}/{pair.B.FailureReason}");
            if (!pair.A.IsEncrypted || !pair.B.IsEncrypted) return (false, "session not encrypted");

            pair.SendA("tcp-enc-1");
            pair.SendB("tcp-enc-back");
            if (!pair.WaitCounts(1, 1, 4000))
                return (false, $"delivered A->B={pair.CountB} B->A={pair.CountA}");
            if (pair.TextB(0) != "tcp-enc-1" || pair.TextA(0) != "tcp-enc-back")
                return (false, "payload mismatch");
            return (true, "AES-256-GCM both ways over a real stream");
        }

        private static (bool, string) ReplayWindowLogic()
        {            // Pure logic check of the window arithmetic, no transport involved.
            var probe = new ReplayWindowProbe();
            if (!probe.Accept(1)) return (false, "seq 1 rejected");
            if (!probe.Accept(2)) return (false, "seq 2 rejected");
            if (probe.Accept(2)) return (false, "seq 2 accepted twice");
            if (!probe.Accept(4)) return (false, "seq 4 (gap) rejected");
            if (!probe.Accept(3)) return (false, "late seq 3 inside window rejected");
            if (probe.Accept(3)) return (false, "seq 3 accepted twice");
            if (!probe.Accept(200)) return (false, "big jump rejected");
            if (probe.Accept(100)) return (false, "seq 100 outside window accepted");
            return (true, "window 64 behaves");
        }

        /// <summary>
        /// Known-answer test for the key schedule. The expected values were produced with an
        /// independent implementation (Python hashlib/hmac), so this pins the protocol — not just
        /// "the code agrees with itself". The Android build carries the same constants, which is
        /// what guarantees byte-for-byte parity between the platforms.
        /// </summary>
        private static (bool, string) KeyScheduleVectors()
        {
            const string passphrase = "cbb-test-vector-1";
            var nC = Enumerable.Range(0, 32).Select(i => (byte)i).ToArray();
            var nS = Enumerable.Range(32, 32).Select(i => (byte)i).ToArray();

            var psk = CbbCrypto.Pbkdf2(passphrase, CbbCrypto.Concat(nC, nS), 10_000);
            const string pskHex = "485fb70eb7ada08c4e7a11c8b1f9d1bd2739f26c2fc4e594001c6aa3b1270aa8";
            if (Convert.ToHexString(psk).ToLowerInvariant() != pskHex) return (false, "pbkdf2 " + Convert.ToHexString(psk));

            var replyData = CbbCrypto.Concat(
                CbbCrypto.Ascii("CBB-HS-REPLY-v1"),
                CbbCrypto.Utf8("nodeA"), new byte[] { 0 }, CbbCrypto.Utf8("nodeB"), nC, nS);
            var mac = CbbCrypto.Hmac(psk, replyData);
            const string macHex = "0ee0c3a30a4a92cf62f48b8f10a1a54e6b3307607db7ddb38d122c3480a8c0a6";
            if (Convert.ToHexString(mac).ToLowerInvariant() != macHex) return (false, "mac " + Convert.ToHexString(mac));

            var master = CbbCrypto.Hmac(psk, CbbCrypto.Concat(CbbCrypto.Ascii("CBB-MASTER-v1"), nC, nS));
            var i2r = CbbCrypto.Hmac(master, CbbCrypto.Concat(CbbCrypto.Ascii("CBB-KEY-I2R-v1"), new byte[] { 1 }));
            var r2i = CbbCrypto.Hmac(master, CbbCrypto.Concat(CbbCrypto.Ascii("CBB-KEY-R2I-v1"), new byte[] { 1 }));
            const string i2rHex = "a9735f22d2bddfca0a53e57cbc09cc9f72cf2e9c38aefea85b9d05ed5f4fd7ef";
            const string r2iHex = "3a8b8d4176d0e412239e3f6a3a8dc42d85be6728fb3d9cfe82bd93ef6b149c6d";
            if (Convert.ToHexString(i2r).ToLowerInvariant() != i2rHex) return (false, "i2r " + Convert.ToHexString(i2r));
            if (Convert.ToHexString(r2i).ToLowerInvariant() != r2iHex) return (false, "r2i " + Convert.ToHexString(r2i));

            return (true, "pbkdf2/hmac/hkdf match reference vectors");
        }

        /// <summary>AES-256-GCM with the frame header as AAD; expected value from Node's OpenSSL.</summary>
        private static (bool, string) AesGcmVector()
        {
            var key = Convert.FromHexString("a9735f22d2bddfca0a53e57cbc09cc9f72cf2e9c38aefea85b9d05ed5f4fd7ef");
            var nonce = Enumerable.Range(0x40, 12).Select(i => (byte)i).ToArray();
            var header = Convert.FromHexString("43420106010000000100000025000000");
            var plaintext = Encoding.UTF8.GetBytes("hello cbb");

            var cipher = CbbCrypto.AesGcmEncrypt(key, nonce, plaintext, header, out var tag);
            if (Convert.ToHexString(cipher).ToLowerInvariant() != "a2c15ae21f5befc045")
                return (false, "ct " + Convert.ToHexString(cipher));
            if (Convert.ToHexString(tag).ToLowerInvariant() != "0eddfe60aa76623dc14206207520ec66")
                return (false, "tag " + Convert.ToHexString(tag));

            var back = CbbCrypto.AesGcmDecrypt(key, nonce, cipher, tag, header);
            if (back == null || Encoding.UTF8.GetString(back) != "hello cbb") return (false, "roundtrip failed");

            var badTag = (byte[])tag.Clone();
            badTag[0] ^= 0xFF;
            if (CbbCrypto.AesGcmDecrypt(key, nonce, cipher, badTag, header) != null)
                return (false, "tampered tag accepted");

            return (true, "gcm vector + tamper rejection");
        }

        /// <summary>
        /// Regression guard: when Read() ends because the peer closed (EOF), the state is already
        /// Closed, and Close() used to return early without ever releasing the socket. On Android
        /// that leaked the RFCOMM channel; here it is checked explicitly.
        /// </summary>
        private static (bool, string) TransportClosedOnEof()
        {
            var transport = new EofTransport();
            var channel = new SecureChannel(transport, true,
                new SecurityPolicy(SecurityMode.Off, "", 10_000), "nodeEof");

            var payload = channel.Read();
            if (payload != null) return (false, "read returned a payload on an empty stream");

            channel.Close();
            channel.Close();   // must stay idempotent

            if (transport.CloseCount != 1)
                return (false, $"closeCount={transport.CloseCount}, expected 1");
            return (true, "read=null on eof, transport closed exactly once");
        }

        /// <summary>A transport whose stream is already at EOF: write succeeds, read returns -1.</summary>
        private sealed class EofTransport : ISecureTransport
        {
            public int CloseCount { get; private set; }
            public bool IsOpen => CloseCount == 0;
            public string PeerLabel => "eof";

            public int Read(byte[] buffer, int offset, int count) => -1;

            public void Write(byte[] data, int offset, int count)
            {
                // Swallow the negotiation probe; there is nobody on the other side.
            }

            public void Close() => CloseCount++;
        }

        /// <summary>Mirror of SecureChannel's window arithmetic, exposed for the test only.</summary>
        private sealed class ReplayWindowProbe
        {
            private uint _highest;
            private bool _started;
            private ulong _bitmap;

            public bool Accept(uint seq)
            {
                if (seq == 0) return false;
                if (!_started) { _started = true; _highest = seq; _bitmap = 1UL; return true; }
                if ((int)(seq - _highest) > 0)
                {
                    var shift = seq - _highest;
                    _bitmap = shift >= 64 ? 1UL : (_bitmap << (int)shift) | 1UL;
                    _highest = seq;
                    return true;
                }
                var back = _highest - seq;
                if (back >= 64) return false;
                var bit = 1UL << (int)back;
                if ((_bitmap & bit) != 0) return false;
                _bitmap |= bit;
                return true;
            }
        }

        // ------------------------------------------------------------------ harness

        /// <summary>
        /// Two live channels over a loopback TCP connection. Unlike <see cref="Pair"/> this uses a
        /// real blocking stream, so a probe/handshake bug that only shows up when Read() returns a
        /// partial buffer will surface here.
        /// </summary>
        private sealed class TcpPair : IDisposable
        {
            private readonly TcpListener _listener;
            private readonly TcpClient _client;
            private readonly TcpClient _accepted;
            private readonly List<byte[]> _rxA = new();
            private readonly List<byte[]> _rxB = new();

            public TcpPair(SecurityMode modeA, string keyA, SecurityMode modeB, string keyB)
            {
                _listener = new TcpListener(IPAddress.Loopback, 0);
                _listener.Start();
                var port = ((IPEndPoint)_listener.LocalEndpoint).Port;

                _client = new TcpClient();
                _client.NoDelay = true;
                _client.Connect(IPAddress.Loopback, port);
                _accepted = _listener.AcceptTcpClient();
                _accepted.NoDelay = true;

                A = new SecureChannel(new RfcommTransport(_client.GetStream(), "tcp-A"), true,
                    new SecurityPolicy(modeA, keyA, 10_000), "nodeA");
                B = new SecureChannel(new RfcommTransport(_accepted.GetStream(), "tcp-B"), false,
                    new SecurityPolicy(modeB, keyB, 10_000), "nodeB");

                _ = Task.Run(() => Pump(A, _rxA));
                _ = Task.Run(() => Pump(B, _rxB));
            }

            public SecureChannel A { get; }
            public SecureChannel B { get; }

            private static void Pump(SecureChannel channel, List<byte[]> sink)
            {
                while (true)
                {
                    var payload = channel.Read();
                    if (payload == null) return;
                    if (payload.Length == 0) continue;
                    lock (sink) sink.Add(payload);
                }
            }

            public int CountA { get { lock (_rxA) return _rxA.Count; } }
            public int CountB { get { lock (_rxB) return _rxB.Count; } }

            public string TextA(int i) { lock (_rxA) return Encoding.UTF8.GetString(_rxA[i]); }
            public string TextB(int i) { lock (_rxB) return Encoding.UTF8.GetString(_rxB[i]); }

            public bool WaitReady(int ms) =>
                WaitFor(() => A.State == SecureState.Ready && B.State == SecureState.Ready, ms);

            public bool WaitCounts(int a, int b, int ms) =>
                WaitFor(() => CountA >= a && CountB >= b, ms);

            public void SendA(string text) => A.Write(Encoding.UTF8.GetBytes(text));
            public void SendB(string text) => B.Write(Encoding.UTF8.GetBytes(text));

            public void Dispose()
            {
                try { A.Close(); } catch { }
                try { B.Close(); } catch { }
                try { _client.Dispose(); } catch { }
                try { _accepted.Dispose(); } catch { }
                try { _listener.Stop(); } catch { }
            }
        }

        private sealed class Pair : IDisposable
        {
            private readonly Inbox _toA = new();
            private readonly Inbox _toB = new();
            private readonly Pipe _pipeA;
            private readonly Pipe _pipeB;
            private readonly Task _readerA;
            private readonly Task _readerB;

            public Pair(string mode, string keyA, string keyB, bool expectedEncrypted = true,
                int chunkSize = 0, bool legacyPeer = false, bool readA = true)
            {
                var modeA = SecurityMode.Auto;
                var modeB = SecurityMode.Auto;
                if (mode.StartsWith("require")) modeA = SecurityMode.Require;

                _pipeA = new Pipe(_toA, _toB, "A", chunkSize);
                _pipeB = new Pipe(_toB, _toA, "B", chunkSize);

                A = new SecureChannel(_pipeA, true, new SecurityPolicy(modeA, keyA, 10_000), "nodeA");
                if (!legacyPeer)
                    B = new SecureChannel(_pipeB, false, new SecurityPolicy(modeB, keyB, 10_000), "nodeB");

                _readerA = readA
                    ? Task.Run(() =>
                    {
                        while (true)
                        {
                            var payload = A.Read();
                            if (payload == null) break;
                            if (payload.Length == 0) continue;
                            lock (_receivedA) _receivedA.Add(payload);
                        }
                    })
                    : Task.CompletedTask;

                if (B != null)
                {
                    _readerB = Task.Run(() =>
                    {
                        while (true)
                        {
                            var payload = B.Read();
                            if (payload == null) break;
                            if (payload.Length == 0) continue;
                            lock (_receivedB) _receivedB.Add(payload);
                        }
                    });
                }
                else
                {
                    // Legacy peer: answer the probe with a 2.1-style frame, then stay quiet.
                    _toA.Push(Encoding.UTF8.GetBytes("{\"v\":2,\"type\":\"auth_hello\",\"sid\":\"x\",\"from\":\"PC\",\"need\":false}\n"));
                    _readerB = Task.CompletedTask;
                }

                _ = expectedEncrypted;
            }

            public SecureChannel A { get; }
            public SecureChannel? B { get; }

            private readonly List<byte[]> _receivedA = new();
            private readonly List<byte[]> _receivedB = new();

            public List<byte[]> ReceivedB
            {
                get { lock (_receivedB) return new List<byte[]>(_receivedB); }
            }

            public string TextB(int index) => Encoding.UTF8.GetString(ReceivedB[index]);

            public bool WaitReady(int ms) =>
                WaitFor(() => A.State == SecureState.Ready && (B == null || B.State == SecureState.Ready), ms);

            public bool WaitReceived(int count, int ms) => WaitFor(() => ReceivedB.Count >= count, ms);

            public bool WaitReceivedA(int count, int ms) =>
                WaitFor(() => { lock (_receivedA) return _receivedA.Count >= count; }, ms);

            public void SendA(string text) => A.Write(Encoding.UTF8.GetBytes(text));

            public void SendB(string text) => B?.Write(Encoding.UTF8.GetBytes(text));

            public byte[]? LastFrameFromA()
            {
                var sent = _pipeA.Sent;
                if (sent.Count == 0) return null;
                // Skip the probe line: it is ASCII, not a frame.
                for (int i = sent.Count - 1; i >= 0; i--)
                {
                    var candidate = sent[i];
                    if (candidate.Length >= FrameCodec.HeaderLen
                        && candidate[0] == FrameCodec.Magic0 && candidate[1] == FrameCodec.Magic1)
                        return candidate;
                }
                return null;
            }

            public void InjectToB(byte[] raw) => _toB.Push(raw);

            public void Dispose()
            {
                A.Dispose();
                B?.Dispose();
                _toA.Close();
                _toB.Close();
                try { Task.WaitAll(new[] { _readerA, _readerB }, 1500); } catch { }
            }
        }

        private sealed class Inbox
        {
            private readonly Queue<byte> _queue = new();
            private readonly object _gate = new();
            private bool _closed;

            public void Push(byte[] data)
            {
                lock (_gate)
                {
                    foreach (var b in data) _queue.Enqueue(b);
                    Monitor.PulseAll(_gate);
                }
            }

            public void Close()
            {
                lock (_gate)
                {
                    _closed = true;
                    Monitor.PulseAll(_gate);
                }
            }

            public int Read(byte[] buffer, int offset, int count)
            {
                lock (_gate)
                {
                    while (_queue.Count == 0 && !_closed) Monitor.Wait(_gate);
                    if (_queue.Count == 0) return -1;
                    var n = Math.Min(count, _queue.Count);
                    for (var i = 0; i < n; i++) buffer[offset + i] = _queue.Dequeue();
                    return n;
                }
            }
        }

        private sealed class Pipe : ISecureTransport
        {
            private readonly Inbox _inbound;
            private readonly Inbox _outbound;
            private readonly int _chunkSize;
            private readonly List<byte[]> _sent = new();
            private readonly object _gate = new();
            private volatile bool _open = true;

            public Pipe(Inbox inbound, Inbox outbound, string label, int chunkSize)
            {
                _inbound = inbound;
                _outbound = outbound;
                PeerLabel = label;
                _chunkSize = chunkSize;
            }

            public string PeerLabel { get; }

            public bool IsOpen => _open;

            public List<byte[]> Sent
            {
                get { lock (_gate) return new List<byte[]>(_sent); }
            }

            public int Read(byte[] buffer, int offset, int count) => _open ? _inbound.Read(buffer, offset, count) : -1;

            public void Write(byte[] data, int offset, int count)
            {
                var copy = new byte[count];
                Buffer.BlockCopy(data, offset, copy, 0, count);
                lock (_gate) _sent.Add(copy);

                if (_chunkSize <= 0)
                {
                    _outbound.Push(copy);
                    return;
                }
                for (var i = 0; i < copy.Length; i += _chunkSize)
                {
                    var len = Math.Min(_chunkSize, copy.Length - i);
                    var slice = new byte[len];
                    Buffer.BlockCopy(copy, i, slice, 0, len);
                    _outbound.Push(slice);
                }
            }

            public void Close()
            {
                _open = false;
                _inbound.Close();
            }
        }

        private static bool WaitFor(Func<bool> condition, int timeoutMs)
        {
            var deadline = Environment.TickCount64 + timeoutMs;
            while (Environment.TickCount64 < deadline)
            {
                if (condition()) return true;
                Thread.Sleep(15);
            }
            return condition();
        }
    }
}
