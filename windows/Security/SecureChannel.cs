using System;
using System.Buffers.Binary;
using System.Collections.Generic;
using System.Security.Cryptography;
using System.Text;

namespace ClipSyncWindows.Security
{
    public enum SecureState
    {
        /// <summary>Deciding whether the peer speaks the binary protocol at all.</summary>
        Negotiating,

        /// <summary>PSK challenge/response in flight. No application data is accepted.</summary>
        Handshaking,

        /// <summary>Session usable: encrypted, or explicitly plaintext by policy.</summary>
        Ready,

        /// <summary>Handshake or integrity failure. The link must be dropped.</summary>
        Failed,

        Closed
    }

    /// <summary>
    /// Layer 2 of CBB: framing, handshake, encryption, integrity and replay protection over an
    /// <see cref="ISecureTransport"/>. It sits between the socket and the existing clipboard
    /// business logic, which keeps speaking its line-oriented JSON and never learns that any of
    /// this exists.
    ///
    /// Read() is called from the transport's read loop and returns one application payload at a
    /// time. Write() may be called from any thread; frames produced before the handshake finishes
    /// are queued and flushed once the session is ready, which is why the caller can start talking
    /// to the peer immediately after accept/connect.
    /// </summary>
    public sealed class SecureChannel : IDisposable
    {
        // ------------------------------------------------------------------ protocol labels

        private const string LabelReply = "CBB-HS-REPLY-v1";
        private const string LabelConfirm = "CBB-HS-CONFIRM-v1";
        private const string LabelMaster = "CBB-MASTER-v1";
        private const string LabelKeyI2R = "CBB-KEY-I2R-v1";
        private const string LabelKeyR2I = "CBB-KEY-R2I-v1";

        /// <summary>
        /// First bytes on every link. A 2.2+ peer answers with the same line and both sides then
        /// switch to binary frames; an older peer answers with its JSON auth frames (or nothing),
        /// which tells us to stay on the legacy line protocol.
        /// </summary>
        public const string ProbeLine = "{\"type\":\"cbb_sec\",\"v\":3,\"cap\":1}";

        /// <summary>How long a handshake may take before the link is dropped.</summary>
        public const int HandshakeTimeoutSeconds = 12;

        /// <summary>Replay window size in frames (bitmap width).</summary>
        public const int ReplayWindow = 64;

        private const int MaxPendingWrites = 256;
        private const int MaxRecentNonces = 64;

        private static readonly byte[] Separator = { 0x00 };
        private static readonly byte[] Newline = { (byte)'\n' };

        private readonly ISecureTransport _transport;
        private readonly bool _isInitiator;
        private readonly SecurityPolicy _policy;
        private readonly string _nodeId;

        private readonly object _writeLock = new();
        private readonly object _pendingLock = new();
        private readonly List<byte[]> _pending = new();

        // ---- receive buffer (grows up to one maximum frame) ----
        private byte[] _buf = new byte[64 * 1024];
        private int _off;
        private int _cnt;

        // ---- negotiation / handshake state ----
        private int _probeStage;
        private bool _legacy;
        private byte[]? _stashedLine;
        private DateTime _handshakeStartedAt;
        private string _nodeInitiator = "";
        private string _nodeResponder = "";

        // ---- crypto state ----
        private byte[]? _nonceC;
        private byte[]? _nonceS;
        private byte[]? _pskKey;
        private byte[]? _keyTx;
        private byte[]? _keyRx;
        private int _kdfIterations = CbbCrypto.DefaultKdfIterations;
        private uint _txSeq;
        private uint _highestRx;
        private bool _rxStarted;
        private ulong _rxBitmap;
        private int _tagFailures;
        private bool _transportClosed;
        private readonly Queue<string> _recentNonces = new();
        private readonly HashSet<string> _recentNonceSet = new(StringComparer.Ordinal);

        public SecureChannel(ISecureTransport transport, bool isInitiator, SecurityPolicy policy, string nodeId)
        {
            _transport = transport;
            _isInitiator = isInitiator;
            _policy = policy;
            _nodeId = string.IsNullOrEmpty(nodeId) ? "unknown" : nodeId;
            _state = SecureState.Negotiating;
        }

        public event Action? Ready;

        public SecureState State => _state;
        private SecureState _state;

        public bool IsEncrypted { get; private set; }

        /// <summary>Empty unless <see cref="State"/> is Failed.</summary>
        public string FailureReason { get; private set; } = "";

        /// <summary>Node id the peer announced during the handshake (empty for legacy peers).</summary>
        public string PeerNode { get; private set; } = "";

        /// <summary>True when the peer predates 2.2 and there is no layer-2 security at all.</summary>
        public bool IsLegacy => _legacy;

        // ------------------------------------------------------------------ public API

        /// <summary>
        /// Returns the next application payload, or null when the link ended. Blocks.
        ///
        /// Handshake frames never reach the caller; a frame whose tag fails to verify is dropped
        /// silently (and counted) rather than surfaced, because forwarding unauthenticated bytes
        /// upward is exactly the hole this layer exists to close.
        /// </summary>
        public byte[]? Read()
        {
            try
            {
                while (true)
                {
                    if (_state == SecureState.Failed || _state == SecureState.Closed) return null;

                    if (_state == SecureState.Negotiating)
                    {
                        if (!Negotiate()) return null;
                        continue;
                    }

                    if (_legacy) return ReadLegacyLine();

                    if (!EnsureFrame(out var header, out var body)) return null;
                    if (header == null) return null;

                    var type = (CbbFrameType)header[3];
                    byte flags = header[4];
                    uint seq = BinaryPrimitives.ReadUInt32LittleEndian(header.AsSpan(8, 4));

                    switch (type)
                    {
                        case CbbFrameType.HsHello:
                            HandleHello(body!);
                            continue;
                        case CbbFrameType.HsChallenge:
                            HandleChallenge(body!);
                            continue;
                        case CbbFrameType.HsConfirm:
                            HandleConfirm(body!);
                            continue;
                        case CbbFrameType.HsAccept:
                            if (!_isInitiator) { Fail("unexpected-accept"); return null; }
                            BecomeReady(false);
                            continue;
                        case CbbFrameType.HsReject:
                            HandleReject(body!);
                            return null;
                        case CbbFrameType.Data:
                            var payload = OpenData(header, body!, flags, seq);
                            if (payload == null) continue;
                            return payload;
                        default:
                            CbbSecurityLog.Write(_transport.PeerLabel, $"frame: unknown type {(byte)type}, ignored");
                            continue;
                    }
                }
            }
            catch (Exception ex)
            {
                if (_state != SecureState.Failed)
                {
                    _state = SecureState.Closed;
                    CbbSecurityLog.Write(_transport.PeerLabel, "read ended: " + ex.GetType().Name);
                }
                return null;
            }
        }

        /// <summary>
        /// Sends one application payload. Before the handshake completes the payload is queued, so
        /// callers never have to synchronise with the security layer.
        /// </summary>
        public bool Write(byte[] payload)
        {
            if (_state == SecureState.Failed || _state == SecureState.Closed) return false;

            if (_state != SecureState.Ready)
            {
                lock (_pendingLock)
                {
                    if (_pending.Count >= MaxPendingWrites) return false;
                    _pending.Add(payload);
                }
                return true;
            }

            return SendPayload(payload);
        }

        /// <summary>
        /// Called from the maintenance loop: a handshake that never finishes must not linger as a
        /// half-open link that still shows up as "connected".
        /// </summary>
        public bool CheckTimeout()
        {
            if (_state != SecureState.Handshaking) return false;
            if (_handshakeStartedAt == default) return false;
            if ((DateTime.UtcNow - _handshakeStartedAt).TotalSeconds < HandshakeTimeoutSeconds) return false;
            Fail("handshake-timeout");
            return true;
        }

        /// <summary>
        /// Session reset entry point. Clears keys, sequence numbers and the replay window and goes
        /// back to negotiation. Kept for transports that can re-establish a stream on the same
        /// adapter object; with RFCOMM a new socket simply gets a new SecureChannel.
        /// </summary>
        public void ResetSession()
        {
            lock (_writeLock)
            {
                _nonceC = _nonceS = _pskKey = _keyTx = _keyRx = null;
                _txSeq = 0;
                _highestRx = 0;
                _rxStarted = false;
                _rxBitmap = 0;
                _recentNonces.Clear();
                _recentNonceSet.Clear();
                _tagFailures = 0;
                IsEncrypted = false;
                FailureReason = "";
                PeerNode = "";
                _legacy = false;
                _stashedLine = null;
                _probeStage = 0;
                _handshakeStartedAt = default;
                _transportClosed = false;
                _state = SecureState.Negotiating;
            }
            lock (_pendingLock) _pending.Clear();
        }

        public void Dispose() => Close();

        public void Close()
        {
            _state = SecureState.Closed;
            CloseTransport();
        }

        /// <summary>
        /// Releases the transport exactly once. It must run even when the state is already
        /// Closed (that is what an EOF in Read() leaves behind): if the read loop ended on
        /// its own, Close() is the only thing left that can release the socket.
        /// </summary>
        private void CloseTransport()
        {
            if (_transportClosed) return;
            _transportClosed = true;
            try { _transport.Close(); } catch { }
        }

        public string StatusText => _state switch
        {
            SecureState.Ready when IsEncrypted => "encrypted",
            SecureState.Ready when _legacy => "legacy-plaintext",
            SecureState.Ready => "plaintext",
            SecureState.Handshaking => "handshaking",
            SecureState.Failed => "failed:" + FailureReason,
            _ => _state.ToString().ToLowerInvariant()
        };

        // ------------------------------------------------------------------ negotiation

        private bool Negotiate()
        {
            if (_probeStage == 0)
            {
                _probeStage = 1;
                if (!WriteRaw(Encoding.UTF8.GetBytes(ProbeLine + "\n"))) return false;
                CbbSecurityLog.Write(_transport.PeerLabel, "negotiate: probe sent");
            }

            while (true)
            {
                if (Available >= 2 && _buf[_off] == FrameCodec.Magic0 && _buf[_off + 1] == FrameCodec.Magic1)
                {
                    EnterBinary();
                    return true;
                }

                var newline = IndexOfNewline();
                if (newline >= 0)
                {
                    var line = TakeLine(newline);
                    if (Encoding.UTF8.GetString(line).Trim() == ProbeLine)
                    {
                        EnterBinary();
                        return true;
                    }

                    // Anything else is an older peer: hand the line up unchanged.
                    _legacy = true;
                    _stashedLine = line;
                    IsEncrypted = false;
                    _state = SecureState.Ready;
                    CbbSecurityLog.Write(_transport.PeerLabel, "negotiate: peer is legacy (pre-2.2)");
                    if (_policy.RefusesPlaintext)
                    {
                        CbbSecurityLog.Write(_transport.PeerLabel,
                            "negotiate: legacy peer refused by policy " + _policy.Mode);
                        Fail("peer-legacy");
                        return false;
                    }
                    FlushPending();
                    return true;
                }

                if (Available > 64 * 1024)
                {
                    Fail("probe-garbage");
                    return false;
                }

                if (!Ensure(Available + 1))
                {
                    _state = SecureState.Closed;
                    return false;
                }
            }
        }

        private void EnterBinary()
        {
            _state = SecureState.Handshaking;
            _handshakeStartedAt = DateTime.UtcNow;

            if (_policy.Mode == SecurityMode.Require && !_policy.HasKey)
            {
                CbbSecurityLog.Write(_transport.PeerLabel, "handshake: require-mode without a configured key");
                Fail("no-psk");
                return;
            }

            CbbSecurityLog.Write(_transport.PeerLabel,
                $"negotiate: binary v{FrameCodec.Version}, role={(_isInitiator ? "initiator" : "responder")}, " +
                $"policy={_policy.Mode}, key={(_policy.HasKey ? "set" : "none")}, " +
                $"kp={PskVault.DebugTag(_policy.Passphrase)}, node={_nodeId}");

            if (_isInitiator) SendHello();
        }

        private byte[]? ReadLegacyLine()
        {
            if (_stashedLine != null)
            {
                var stashed = _stashedLine;
                _stashedLine = null;
                return stashed;
            }
            while (true)
            {
                var newline = IndexOfNewline();
                if (newline >= 0) return TakeLine(newline);
                if (Available > 24 * 1024 * 1024) { Fail("legacy-frame-too-large"); return null; }
                if (!Ensure(Available + 1)) { _state = SecureState.Closed; return null; }
            }
        }

        // ------------------------------------------------------------------ handshake

        private void SendHello()
        {
            var suite = _policy.WantsEncryption ? (byte)1 : (byte)0;
            uint iterations = suite == 1 ? (uint)_policy.KdfIterations : 0u;
            var nonce = suite == 1 ? CbbCrypto.RandomBytes(CbbCrypto.HandshakeNonceBytes)
                : new byte[CbbCrypto.HandshakeNonceBytes];
            if (suite == 1)
            {
                _nonceC = nonce;
                // The initiator must keep the exact iteration count it announced: the responder
                // echoes it implicitly by deriving with the value from the HELLO, and a mismatch
                // here silently produces two different keys ("psk mismatch" with the right PSK).
                _kdfIterations = _policy.KdfIterations;
            }

            var node = CbbCrypto.Utf8(_nodeId);
            if (node.Length > 255) node = node[..255];

            var body = new byte[38 + node.Length];
            body[0] = suite;
            BinaryPrimitives.WriteUInt32LittleEndian(body.AsSpan(1, 4), iterations);
            Buffer.BlockCopy(nonce, 0, body, 5, CbbCrypto.HandshakeNonceBytes);
            body[37] = (byte)node.Length;
            Buffer.BlockCopy(node, 0, body, 38, node.Length);

            if (!WriteFrame(CbbFrameType.HsHello, 0, 0, body)) return;
            CbbSecurityLog.Write(_transport.PeerLabel,
                suite == 1 ? $"handshake: HELLO suite=1 kdf={iterations}" : "handshake: HELLO suite=0 (plaintext)");
        }

        private void HandleHello(byte[] body)
        {
            if (_isInitiator) { Fail("unexpected-hello"); return; }
            if (body.Length < 38) { Reject(7, "bad-hello"); return; }

            var suite = body[0];
            var iterations = BinaryPrimitives.ReadUInt32LittleEndian(body.AsSpan(1, 4));
            var nonceC = body.AsSpan(5, CbbCrypto.HandshakeNonceBytes).ToArray();
            int nodeLen = body[37];
            if (body.Length < 38 + nodeLen) { Reject(7, "bad-hello"); return; }

            PeerNode = Encoding.UTF8.GetString(body, 38, nodeLen);
            _nodeInitiator = PeerNode;
            _nodeResponder = _nodeId;

            if (suite == 0)
            {
                if (_policy.RefusesPlaintext)
                {
                    CbbSecurityLog.Write(_transport.PeerLabel, "handshake: peer offered plaintext, refused");
                    Reject(4, "plaintext-refused");
                    return;
                }
                if (!WriteFrame(CbbFrameType.HsAccept, 0, 0, Array.Empty<byte>())) return;
                BecomeReady(false);
                return;
            }

            if (suite != 1) { Reject(3, "unsupported-suite"); return; }
            if (!_policy.HasKey) { Reject(2, "no-psk"); return; }

            _nonceC = nonceC;
            _nonceS = CbbCrypto.RandomBytes(CbbCrypto.HandshakeNonceBytes);
            _kdfIterations = (int)Math.Clamp(
                iterations == 0 ? (uint)CbbCrypto.DefaultKdfIterations : iterations,
                (uint)CbbCrypto.MinKdfIterations, (uint)CbbCrypto.MaxKdfIterations);

            _pskKey = CbbCrypto.Pbkdf2(_policy.Passphrase, CbbCrypto.Concat(_nonceC, _nonceS), _kdfIterations);
            var mac = CbbCrypto.Hmac(_pskKey, HandshakeData(LabelReply));

            // The challenge carries the responder's node id so the initiator can bind the same
            // data into both MACs. Without it the two sides would sign different strings.
            var node = CbbCrypto.Utf8(_nodeId);
            if (node.Length > 255) node = node[..255];
            var challenge = new byte[32 + 1 + node.Length + 32];
            Buffer.BlockCopy(_nonceS, 0, challenge, 0, 32);
            challenge[32] = (byte)node.Length;
            Buffer.BlockCopy(node, 0, challenge, 33, node.Length);
            Buffer.BlockCopy(mac, 0, challenge, 33 + node.Length, 32);

            if (!WriteFrame(CbbFrameType.HsChallenge, 0, 0, challenge)) return;
            DeriveSessionKeys();
            CbbSecurityLog.Write(_transport.PeerLabel,
                $"handshake: CHALLENGE sent, kdf={_kdfIterations}, key={CbbCrypto.KeyFingerprint(_pskKey!)}, " +
                HandshakeTrace());
        }

        /// <summary>
        /// The inputs the MAC is computed over, minus the key. Nonces and node ids are already on
        /// the wire, so logging them leaks nothing — but it is what turns "key mismatch" into an
        /// answer: equal <c>kp</c> with unequal <c>mac</c> means the two sides built different
        /// handshake strings, not different keys.
        /// </summary>
        private string HandshakeTrace()
        {
            static string H(byte[]? b) =>
                b == null ? "-" : Convert.ToHexString(b, 0, Math.Min(8, b.Length)).ToLowerInvariant();

            var macInput = HandshakeMessage(
                LabelReply, _nodeInitiator, _nodeResponder, _nonceC!, _nonceS!);
            var macTag = Convert.ToHexString(SHA256.HashData(macInput), 0, 8).ToLowerInvariant();

            return $"kp={PskVault.DebugTag(_policy.Passphrase)} " +
                   $"nI={_nodeInitiator} nR={_nodeResponder} " +
                   $"nC={H(_nonceC)} nS={H(_nonceS)} kdf={_kdfIterations} mac={macTag}";
        }

        private void HandleChallenge(byte[] body)
        {
            if (!_isInitiator) { Fail("unexpected-challenge"); return; }
            if (body.Length < 32 + 1 + 32) { Fail("bad-challenge"); return; }
            if (_nonceC == null) { Fail("no-client-nonce"); return; }

            int nodeLen = body[32];
            if (body.Length != 32 + 1 + nodeLen + 32) { Fail("bad-challenge"); return; }

            _nonceS = body.AsSpan(0, 32).ToArray();
            var nodeS = Encoding.UTF8.GetString(body, 33, nodeLen);
            var peerMac = body.AsSpan(33 + nodeLen, 32).ToArray();

            if (!string.IsNullOrEmpty(nodeS)) PeerNode = nodeS;
            _nodeResponder = PeerNode;
            _nodeInitiator = _nodeId;

            _pskKey = CbbCrypto.Pbkdf2(_policy.Passphrase, CbbCrypto.Concat(_nonceC, _nonceS), _kdfIterations);
            var expected = CbbCrypto.Hmac(_pskKey, HandshakeData(LabelReply));

            if (!CbbCrypto.FixedTimeEquals(peerMac, expected))
            {
                CbbSecurityLog.Write(_transport.PeerLabel,
                    $"handshake: CHALLENGE MAC mismatch, key={CbbCrypto.KeyFingerprint(_pskKey)}, " +
                    HandshakeTrace());
                Reject(1, "psk-mismatch");
                return;
            }

            DeriveSessionKeys();
            if (!WriteFrame(CbbFrameType.HsConfirm, 0, 0, CbbCrypto.Hmac(_pskKey, HandshakeData(LabelConfirm)))) return;
            BecomeReady(true);
        }

        private void HandleConfirm(byte[] body)
        {
            if (_isInitiator) { Fail("unexpected-confirm"); return; }
            if (_pskKey == null) { Fail("no-session-key"); return; }
            if (body.Length != 32) { Fail("bad-confirm"); return; }

            var expected = CbbCrypto.Hmac(_pskKey, HandshakeData(LabelConfirm));
            if (!CbbCrypto.FixedTimeEquals(body, expected))
            {
                CbbSecurityLog.Write(_transport.PeerLabel, "handshake: CONFIRM MAC mismatch");
                Reject(1, "psk-mismatch");
                return;
            }

            BecomeReady(true);
        }

        private void HandleReject(byte[] body)
        {
            int code = body.Length > 0 ? body[0] : 0;
            string reason = "";
            if (body.Length > 3)
            {
                int len = BinaryPrimitives.ReadUInt16LittleEndian(body.AsSpan(1, 2));
                if (len > 0 && body.Length >= 3 + len)
                    reason = Encoding.UTF8.GetString(body, 3, len);
            }
            Fail($"peer-reject:{code}:{reason}");
        }

        /// <summary>
        /// One MAC over <see cref="HandshakeMessage"/>. This used to HMAC the message *and* be
        /// HMAC'd again by every caller, so Windows signed HMAC(psk, HMAC(psk, msg)) while Android
        /// signed HMAC(psk, msg). Two devices with the same passphrase could never agree, and the
        /// self-test still passed because it called HandshakeMessage directly instead of this.
        /// Every caller does exactly one HMAC now, and the self-test goes through HandshakeMac.
        /// </summary>
        private byte[] HandshakeData(string label) =>
            HandshakeMac(_pskKey!, label, _nodeInitiator, _nodeResponder, _nonceC!, _nonceS!);

        /// <summary>
        /// The one and only handshake MAC. Public so the self-test can pin the production path
        /// against the Python reference rather than re-deriving the same value a second way.
        /// </summary>
        public static byte[] HandshakeMac(byte[] psk, string label, string nodeInitiator,
            string nodeResponder, byte[] nonceC, byte[] nonceS) =>
            CbbCrypto.Hmac(psk, HandshakeMessage(label, nodeInitiator, nodeResponder, nonceC, nonceS));

        /// <summary>
        /// The exact byte string both handshake MACs are computed over. Frozen by docs/02: the two
        /// platforms build it independently, so any divergence (field order, separator, encoding)
        /// shows up as "psk mismatch" even when both users typed the same passphrase. The
        /// cross-language vector in the self-test pins it.
        /// </summary>
        public static byte[] HandshakeMessage(string label, string nodeInitiator, string nodeResponder,
            byte[] nonceC, byte[] nonceS) =>
            CbbCrypto.Concat(
                CbbCrypto.Ascii(label),
                CbbCrypto.Utf8(nodeInitiator),
                Separator,
                CbbCrypto.Utf8(nodeResponder),
                nonceC,
                nonceS);

        private void DeriveSessionKeys()
        {
            var master = CbbCrypto.Hmac(_pskKey!, CbbCrypto.Concat(
                CbbCrypto.Ascii(LabelMaster), _nonceC!, _nonceS!));
            var i2r = CbbCrypto.Hmac(master, CbbCrypto.Concat(CbbCrypto.Ascii(LabelKeyI2R), new byte[] { 0x01 }));
            var r2i = CbbCrypto.Hmac(master, CbbCrypto.Concat(CbbCrypto.Ascii(LabelKeyR2I), new byte[] { 0x01 }));

            if (_isInitiator) { _keyTx = i2r; _keyRx = r2i; }
            else { _keyTx = r2i; _keyRx = i2r; }
        }

        private void BecomeReady(bool encrypted)
        {
            IsEncrypted = encrypted;
            _state = SecureState.Ready;
            CbbSecurityLog.Write(_transport.PeerLabel,
                encrypted ? "session READY, encrypted (AES-256-GCM)" : "session READY, PLAINTEXT by policy");
            try { Ready?.Invoke(); } catch { }
            FlushPending();
        }

        private void FlushPending()
        {
            List<byte[]> queued;
            lock (_pendingLock)
            {
                if (_pending.Count == 0) return;
                queued = new List<byte[]>(_pending);
                _pending.Clear();
            }
            foreach (var payload in queued) SendPayload(payload);
        }

        // ------------------------------------------------------------------ data path

        private bool SendPayload(byte[] payload)
        {
            if (_state != SecureState.Ready) return false;

            if (_legacy)
            {
                var line = CbbCrypto.Concat(payload, Newline);
                CbbSecurityLog.WritePlaintext(_transport.PeerLabel, "tx", payload);
                return WriteRaw(line);
            }

            lock (_writeLock)
            {
                if (_txSeq == uint.MaxValue) { Fail("tx-seq-exhausted"); return false; }
                var seq = ++_txSeq;

                if (IsEncrypted && _keyTx != null)
                {
                    int bodyLen = CbbCrypto.NonceBytes + payload.Length + CbbCrypto.TagBytes;
                    var header = FrameCodec.EncodeHeader(CbbFrameType.Data, FrameCodec.FlagEncrypted, seq, bodyLen);
                    var nonce = CbbCrypto.RandomBytes(CbbCrypto.NonceBytes);
                    var cipher = CbbCrypto.AesGcmEncrypt(_keyTx, nonce, payload, header, out var tag);
                    var frame = CbbCrypto.Concat(header, nonce, cipher, tag);
                    CbbSecurityLog.WritePlaintext(_transport.PeerLabel, "tx", payload);
                    return WriteRaw(frame);
                }

                var plainHeader = FrameCodec.EncodeHeader(CbbFrameType.Data, 0, seq, payload.Length);
                CbbSecurityLog.WritePlaintext(_transport.PeerLabel, "tx", payload);
                return WriteRaw(CbbCrypto.Concat(plainHeader, payload));
            }
        }

        private byte[]? OpenData(byte[] header, byte[] body, byte flags, uint seq)
        {
            bool encrypted = (flags & FrameCodec.FlagEncrypted) != 0;

            if (!AcceptSequence(seq))
            {
                CbbSecurityLog.Write(_transport.PeerLabel, $"drop: replay/out-of-window seq={seq}");
                return null;
            }

            if (!encrypted)
            {
                if (IsEncrypted)
                {
                    // An attacker (or a broken peer) stripping the encryption bit must not be
                    // able to inject a plaintext frame into an encrypted session.
                    CbbSecurityLog.Write(_transport.PeerLabel, "drop: plaintext frame inside encrypted session");
                    Fail("plaintext-downgrade");
                    return null;
                }
                CbbSecurityLog.WritePlaintext(_transport.PeerLabel, "rx", body);
                return body;
            }

            if (_keyRx == null) { Fail("unexpected-encrypted"); return null; }
            if (body.Length < CbbCrypto.NonceBytes + CbbCrypto.TagBytes) { Fail("short-data-frame"); return null; }

            var nonce = body.AsSpan(0, CbbCrypto.NonceBytes).ToArray();
            var cipher = body.AsSpan(CbbCrypto.NonceBytes, body.Length - CbbCrypto.NonceBytes - CbbCrypto.TagBytes).ToArray();
            var tag = body.AsSpan(body.Length - CbbCrypto.TagBytes, CbbCrypto.TagBytes).ToArray();

            if (!NoteNonce(nonce))
            {
                CbbSecurityLog.Write(_transport.PeerLabel, "drop: nonce reuse detected");
                Fail("nonce-reuse");
                return null;
            }

            var plain = CbbCrypto.AesGcmDecrypt(_keyRx, nonce, cipher, tag, header);
            if (plain == null)
            {
                _tagFailures++;
                CbbSecurityLog.Write(_transport.PeerLabel, $"drop: tag mismatch (seq={seq}, count={_tagFailures})");
                if (_tagFailures >= 8) Fail("tag-failures");
                return null;
            }

            CbbSecurityLog.WritePlaintext(_transport.PeerLabel, "rx", plain);
            return plain;
        }

        private bool AcceptSequence(uint seq)
        {
            if (seq == 0) return false;

            if (!_rxStarted)
            {
                _rxStarted = true;
                _highestRx = seq;
                _rxBitmap = 1UL;
                return true;
            }

            if ((int)(seq - _highestRx) > 0)
            {
                var shift = seq - _highestRx;
                _rxBitmap = shift >= (uint)ReplayWindow ? 1UL : (_rxBitmap << (int)shift) | 1UL;
                _highestRx = seq;
                return true;
            }

            var back = _highestRx - seq;
            if (back >= (uint)ReplayWindow) return false;
            var bit = 1UL << (int)back;
            if ((_rxBitmap & bit) != 0) return false;
            _rxBitmap |= bit;
            return true;
        }

        private bool NoteNonce(byte[] nonce)
        {
            var key = Convert.ToHexString(nonce);
            if (_recentNonceSet.Contains(key)) return false;
            _recentNonceSet.Add(key);
            _recentNonces.Enqueue(key);
            while (_recentNonces.Count > MaxRecentNonces)
                _recentNonceSet.Remove(_recentNonces.Dequeue());
            return true;
        }

        // ------------------------------------------------------------------ plumbing

        private void Reject(byte code, string reason)
        {
            var text = CbbCrypto.Utf8(reason);
            var body = new byte[3 + text.Length];
            body[0] = code;
            BinaryPrimitives.WriteUInt16LittleEndian(body.AsSpan(1, 2), (ushort)text.Length);
            Buffer.BlockCopy(text, 0, body, 3, text.Length);
            WriteFrame(CbbFrameType.HsReject, 0, 0, body);
            Fail($"local-reject:{code}:{reason}");
        }

        private void Fail(string reason)
        {
            if (_state == SecureState.Failed || _state == SecureState.Closed) return;
            FailureReason = reason;
            _state = SecureState.Failed;
            lock (_pendingLock) _pending.Clear();
            CbbSecurityLog.Write(_transport.PeerLabel, "SECURITY-FAILED: " + reason);
            CloseTransport();
        }

        private bool WriteFrame(CbbFrameType type, byte flags, uint seq, byte[] body)
        {
            lock (_writeLock)
            {
                var frame = FrameCodec.Encode(type, flags, seq, body);
                return WriteRaw(frame);
            }
        }

        private bool WriteRaw(byte[] data)
        {
            try
            {
                _transport.Write(data, 0, data.Length);
                return true;
            }
            catch (Exception)
            {
                _state = SecureState.Closed;
                return false;
            }
        }

        private bool EnsureFrame(out byte[]? header, out byte[]? body)
        {
            header = null;
            body = null;
            if (!Ensure(FrameCodec.HeaderLen)) return false;

            var candidate = new byte[FrameCodec.HeaderLen];
            Buffer.BlockCopy(_buf, _off, candidate, 0, FrameCodec.HeaderLen);
            if (!FrameCodec.TryReadHeader(candidate, out _, out _, out _, out var bodyLen))
            {
                Fail("bad-header");
                return false;
            }
            if (!Ensure(FrameCodec.HeaderLen + bodyLen)) return false;

            body = new byte[bodyLen];
            Buffer.BlockCopy(_buf, _off + FrameCodec.HeaderLen, body, 0, bodyLen);
            _off += FrameCodec.HeaderLen + bodyLen;
            header = candidate;
            return true;
        }

        private int Available => _cnt - _off;

        private bool Ensure(int needed)
        {
            while (Available < needed)
            {
                if (_cnt == _buf.Length)
                {
                    Compact();
                    if (_cnt == _buf.Length)
                    {
                        int limit = FrameCodec.HeaderLen + FrameCodec.MaxBody + 16;
                        int target = Math.Min(_buf.Length * 2, limit);
                        if (target <= _buf.Length) return false;
                        Array.Resize(ref _buf, target);
                    }
                }

                int read = _transport.Read(_buf, _cnt, _buf.Length - _cnt);
                if (read <= 0) return false;
                _cnt += read;
            }
            return true;
        }

        private void Compact()
        {
            if (_off == 0) return;
            int len = Available;
            if (len > 0) Buffer.BlockCopy(_buf, _off, _buf, 0, len);
            _off = 0;
            _cnt = len;
        }

        private int IndexOfNewline()
        {
            for (int i = _off; i < _cnt; i++)
                if (_buf[i] == (byte)'\n') return i;
            return -1;
        }

        private byte[] TakeLine(int newlineIndex)
        {
            int len = newlineIndex - _off;
            if (len > 0 && _buf[newlineIndex - 1] == (byte)'\r') len--;
            var line = new byte[Math.Max(0, len)];
            if (len > 0) Buffer.BlockCopy(_buf, _off, line, 0, len);
            _off = newlineIndex + 1;
            return line;
        }
    }
}
