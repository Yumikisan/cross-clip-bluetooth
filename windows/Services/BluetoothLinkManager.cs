using System.Collections.Concurrent;
using System.Diagnostics;
using System.IO;
using System.Net.Sockets;
using System.Text;
using InTheHand.Net;
using InTheHand.Net.Sockets;
using ClipSyncWindows.Models;
using ClipSyncWindows.Security;
using ClipSyncWindows.Services.Transport;

namespace ClipSyncWindows.Services
{
    /// <summary>One live RFCOMM connection with a peer. Full duplex: we can always write.</summary>
    public sealed class PeerSession : IDisposable, IPeerLink
    {
        private readonly BluetoothClient _client;
        private readonly Stream _stream;
        private Thread? _readThread;
        private volatile bool _open = true;

        public PeerSession(BluetoothClient client, string address, string name, bool incoming,
            bool requireConfirm, SecurityPolicy policy)
        {
            _client = client;
            Address = address;
            Name = name;
            IsIncoming = incoming;
            _stream = client.GetStream();
            Auth = new SessionAuth(requireConfirm);
            CreatedAt = DateTime.UtcNow;

            // Layer 2 lives strictly between the socket and the business frames. Everything above
            // Channel still speaks the same newline-JSON it always did.
            Channel = new SecureChannel(
                new RfcommTransport(_stream, name),
                isInitiator: !incoming,
                policy,
                SettingsService.NodeId);
            Channel.Ready += () =>
            {
                Logged("security: " + Channel.StatusText);
                // Ask whether the peer can take binary chunks. It rides on the heartbeat that
                // already exists, so it costs no round trip and no handshake change; a peer that
                // never answers simply keeps receiving base64.
                _ = Task.Run(() => TrySend(new WireMessage { type = Wire.TypePing, raw = 1 }));
            };
        }

        /// <summary>Framing + encryption + replay window for this socket. Fresh per connection.</summary>
        public SecureChannel Channel { get; }

        /// <summary>True once the PSK handshake completed and frames are AES-GCM protected.</summary>
        public bool IsEncrypted => Channel.IsEncrypted;

        /// <summary>
        /// Non-empty when the link died because of the security layer rather than the radio.
        /// Surfaced to the UI so "it just won't connect" becomes an actionable message.
        /// </summary>
        public string SecurityFailure { get; private set; } = "";

        private Action<string>? _log;

        /// <summary>Per-link diagnostic sink (feeds the manager's transfer log).</summary>
        public Action<string>? LogSink
        {
            get => _log;
            set => _log = value;
        }

        private void Logged(string message) => _log?.Invoke(message);

        public string Address { get; }
        public string Name { get; set; }
        public bool IsIncoming { get; }
        public bool IsOpen => _open;
        public DateTime CreatedAt { get; }

        // ------------------------------------------------------------ IPeerLink contract
        // Business logic (clipboard, files, folders) only sees these members. Everything else
        // below is RFCOMM-specific and belongs to the transport manager.

        TransportPeer IPeerLink.Peer => new(Address, Name, TransportKind.Rfcomm, IsIncoming);
        TransportCapabilities IPeerLink.Capabilities => TransportCapabilities.Rfcomm();
        bool IPeerLink.IsAuthenticated => Auth.IsAuthenticatedSafe;
        bool IPeerLink.IsLegacyPeer => Channel.IsLegacy;
        bool IPeerLink.SendFrame(WireMessage message) => TrySend(message);

        /// <summary>
        /// Node id the peer announced on this socket. Empty until its auth_hello lands, and
        /// permanently empty for peers older than 2.1.
        /// </summary>
        public string PeerNode { get; set; } = "";

        /// <summary>Authentication state of this link. Fresh for every connection.</summary>
        public SessionAuth Auth { get; }

        /// <summary>Frames that could not be parsed: a broken peer is dropped eventually.</summary>
        internal int BadFrames;

        /// <summary>Round trip time of the last frame acknowledgement, in milliseconds.</summary>
        public long RttMs { get; internal set; } = -1;

        /// <summary>Bytes per second observed during the last transfer.</summary>
        public double Throughput { get; internal set; }

        public DateTime LastActivity { get; internal set; } = DateTime.UtcNow;

        /// <summary>
        /// When the last *inbound* frame arrived. Stays at <see cref="DateTime.MinValue"/> when
        /// the socket is up but the peer never speaks — the signature of a half-dead link
        /// (phone frozen in the background, or a socket the phone's stack accepted but nobody
        /// is reading). Such a link must be dropped, not kept as "connected".
        /// </summary>
        public DateTime LastInboundAt { get; private set; } = DateTime.MinValue;

        public event Action<PeerSession, WireMessage>? MessageReceived;
        public event Action<PeerSession>? Closed;

        public void StartReadLoop()
        {
            _readThread = new Thread(ReadLoop) { IsBackground = true, Name = $"rfcomm-{Address}" };
            _readThread.Start();
        }

        private void ReadLoop()
        {
            try
            {
                while (_open)
                {
                    var payload = Channel.Read();
                    if (payload == null) break;
                    if (payload.Length == 0) continue;

                    LastActivity = DateTime.UtcNow;
                    LastInboundAt = DateTime.UtcNow;

                    // Binary chunk frames carry no JSON at all — that is the whole point.
                    if (payload[0] == Wire.RawChunkMagic)
                    {
                        var rawMsg = Wire.TryParseRawChunk(payload);
                        if (rawMsg != null) MessageReceived?.Invoke(this, rawMsg);
                        else if (++BadFrames > 30) break;
                        continue;
                    }

                    var line = Encoding.UTF8.GetString(payload);
                    if (line.Length == 0) continue;

                    var msg = Wire.TryParse(line);
                    if (msg == null)
                    {
                        // A peer that keeps sending garbage is not worth keeping around.
                        if (++BadFrames > 30) break;
                        continue;
                    }

                    if (msg.type == Wire.TypePing)
                    {
                        TrySend(new WireMessage { type = Wire.TypePong, ts = msg.ts, raw = 1 });
                        continue;
                    }
                    if (msg.type == Wire.TypePong)
                    {
                        RttMs = Math.Max(1, DateTimeOffset.Now.ToUnixTimeMilliseconds() - msg.ts);
                        // The peer understood the magic byte path only if it echoes the flag.
                        if (msg.raw == 1) SupportsRawChunk = true;
                        continue;
                    }

                    MessageReceived?.Invoke(this, msg);
                }
            }
            catch (Exception)
            {
                // connection dropped
            }
            finally
            {
                // Capture the reason before Close() wipes the channel state, otherwise a policy
                // rejection looks identical to an ordinary hang-up.
                if (Channel.State == SecureState.Failed && !string.IsNullOrEmpty(Channel.FailureReason))
                    SecurityFailure = Channel.FailureReason;
                Close();
            }
        }

        public bool TrySend(WireMessage message)
        {
            if (!_open) return false;
            if (Channel.State == SecureState.Failed)
            {
                Logged("security: refusing to send, session failed: " + Channel.FailureReason);
                return false;
            }

            var payload = Encoding.UTF8.GetBytes(Wire.SerializeBody(message));
            if (!Channel.Write(payload)) return false;
            LastActivity = DateTime.UtcNow;
            return true;
        }

        /// <summary>
        /// True once the peer answered a raw-capability ping. Chunks then skip base64, which is
        /// what keeps a third of the link's bandwidth out of the encoding.
        /// </summary>
        public bool SupportsRawChunk { get; private set; }

        /// <summary>
        /// Sends one file chunk as raw bytes. Guarded by <see cref="SupportsRawChunk"/>: a peer
        /// that does not understand the magic byte would drop the frame and lose the file.
        /// </summary>
        public bool TrySendRawChunk(string id, int seq, byte[] data)
        {
            if (!_open || !SupportsRawChunk) return false;
            if (Channel.State == SecureState.Failed) return false;
            if (!Channel.Write(Wire.BuildRawChunk(id, seq, data))) return false;
            LastActivity = DateTime.UtcNow;
            return true;
        }

        public long Ping()
        {
            var sent = DateTimeOffset.Now.ToUnixTimeMilliseconds();
            if (!TrySend(new WireMessage { type = Wire.TypePing, ts = sent })) return -1;
            return sent;
        }

        public void Close()
        {
            if (!_open) return;
            _open = false;
            try { Channel.Close(); } catch { }
            try { _stream.Dispose(); } catch { }
            try { _client.Close(); } catch { }
            Closed?.Invoke(this);
        }

        public void Dispose() => Close();
    }

    /// <summary>
    /// Owns the RFCOMM listener, keeps persistent outbound links to the selected devices
    /// and moves clipboard/file frames in both directions.
    ///
    /// Why persistent outbound links? On Windows the RFCOMM *listener* is unreliable when
    /// the Android side initiates the connection (SDP record quirks), which is exactly why
    /// "copy on the phone never reached the PC" in the original build. A link opened by the
    /// PC works in both directions, because RFCOMM sockets are full duplex.
    /// </summary>
    public sealed class BluetoothLinkManager : IDisposable
    {
        public static readonly Guid ServiceUuid = new("8ce255c0-200a-11e0-ac64-0800200c9a66");

        /// <summary>
        /// Silence after which a link is considered dead. RFCOMM never reports that the peer's
        /// radio went away, so an unanswered ping is the only evidence available. The heartbeat
        /// runs every 15s, so this is roughly two missed pings plus margin.
        /// </summary>
        private const int HeartbeatTimeoutSeconds = 40;

        private BluetoothListener? _listener;
        private Thread? _acceptThread;
        private CancellationTokenSource? _cts;
        private readonly ConcurrentDictionary<string, PeerSession> _sessions = new();

        /// <summary>
        /// Links the peer opened towards us. Kept separately so a re-selection of targets
        /// never tears down a connection we did not start ourselves.
        /// </summary>
        private readonly ConcurrentDictionary<string, PeerSession> _inbounds = new();
        private readonly ConcurrentDictionary<string, byte> _targets = new();

        /// <summary>Devices allowed to reconnect after a drop. Unflagged ones are dialled once
        /// (at startup / on refresh) and then left alone.</summary>
        private readonly ConcurrentDictionary<string, byte> _autoRetry = new();

        /// <summary>How many dial attempts each target has had, for the once-only devices.</summary>
        private readonly ConcurrentDictionary<string, int> _dialAttempts = new();
        private readonly ConcurrentDictionary<string, string> _targetNames = new();
        private readonly ConcurrentDictionary<string, IncomingFile> _incomingFiles = new();

        /// <summary>Pending file transfers waiting for the peer's ack/error frame.</summary>
        private readonly ConcurrentDictionary<string, TaskCompletionSource<bool>> _pendingAcks = new();

        /// <summary>
        /// Ids the receiver refused mid-transfer (it tapped cancel on its side).
        ///
        /// Without this the sender kept pushing bytes nobody would ever store: the peer answers
        /// an unsolicited cancel with an error frame, and finishing the upload takes minutes on
        /// a large file. Checked before every chunk; an id is always removed in the send path's
        /// finally, so the set cannot grow.
        /// </summary>
        private readonly ConcurrentDictionary<string, byte> _abortedSends = new();

        /// <summary>
        /// One dial at a time per device. The maintenance loop and a clipboard push can both
        /// decide to connect within the same millisecond; two concurrent RFCOMM connects to
        /// the same peer fail with WSAEADDRINUSE and leave the loser's socket behind.
        /// </summary>
        private readonly ConcurrentDictionary<string, SemaphoreSlim> _dialLocks = new();

        /// <summary>Consecutive dial failures per device, used for the retry backoff.</summary>
        private readonly ConcurrentDictionary<string, int> _dialFailures = new();
        /// <summary>Earliest moment the next dial attempt is allowed, per device.</summary>
        private readonly ConcurrentDictionary<string, DateTime> _nextDialAt = new();

        /// <summary>
        /// Devices that were just dropped by the security layer. Redialling a key mismatch every
        /// three seconds turns a fixable configuration problem into an endless reconnect storm
        /// (and floods the radio), so those waits are much longer than a plain dial failure.
        /// Cleared as soon as the security settings change.
        /// </summary>
        private readonly ConcurrentDictionary<string, DateTime> _securityCooldown = new();

        /// <summary>Device was dropped for a security reason: key missing, mismatch, too old.</summary>
        public event Action<string, string, string>? SecurityFailed;   // addressKey, name, reason

        /// <summary>
        /// Node ids learned from peers, per address. Their presence means the peer can take
        /// part in duplicate-link arbitration, so the old "one socket only" rule no longer has
        /// to be applied blindly at accept time.
        /// </summary>
        private readonly ConcurrentDictionary<string, string> _peerNodes = new();

        /// <summary>This install's identity, announced in auth_hello.</summary>
        private readonly string _nodeId = SettingsService.NodeId;

        /// <summary>
        /// How long a freshly opened link may stay completely silent before it is treated as
        /// half-dead. The peer answers our hello immediately, so 8s of nothing means nobody is
        /// reading the socket on the other side.
        /// </summary>
        private const int SilentLinkGraceSeconds = 8;

        /// <summary>
        /// How long one dial may block before it is abandoned. Windows RFCOMM often answers a
        /// dead peer by blocking for the full page timeout, so this has to be short enough that
        /// the retry loop stays responsive for the *other* devices.
        /// </summary>
        private const int ConnectAttemptSeconds = 12;

        private readonly AppSettings _settings;

        /// <summary>
        /// Every transport backend this build knows. Business logic asks the registry, never a
        /// concrete socket layer, so a LAN/HDT backend is a registration, not a refactor.
        /// </summary>
        public TransportRegistry Transports { get; } = new();

        public BluetoothLinkManager(AppSettings settings)
        {
            _settings = settings;
            _securitySnapshot = SecuritySnapshot();
            Transports.Register(new RfcommConnector(
                available: () => IsRunning,
                connect: OpenLinkAsync));
            // Editing the key or the mode is exactly how a user fixes a security rejection, so a
            // settings save releases every cooldown instead of making them wait it out -- and, if
            // the security settings themselves changed, retires the live links below.
            SettingsService.Changed += OnSettingsChanged;
        }

        /// <summary>The parts of the settings that a live session's negotiated policy depends on.</summary>
        private string SecuritySnapshot() =>
            (_settings.PskProtected ?? "") + "|" + (_settings.SecurityMode ?? "");

        private string _securitySnapshot = "";

        /// <summary>Lets every cooled-down device be dialled again on the next tick.</summary>
        public void ClearSecurityCooldown() => _securityCooldown.Clear();

        /// <summary>
        /// Any settings save releases the cooldowns. When the key or the mode changed, the live
        /// sessions are dropped as well: each one negotiated with a snapshot of the old policy, so
        /// without this a key change (or switching plaintext back to encrypted) does nothing until
        /// the link happens to die on its own — the setting silently appears not to work, which is
        /// how "only plaintext connects" turns into a permanent state.
        /// </summary>
        private void OnSettingsChanged()
        {
            _securityCooldown.Clear();

            var snapshot = SecuritySnapshot();
            if (snapshot == _securitySnapshot) return;
            _securitySnapshot = snapshot;

            var dropped = 0;
            foreach (var s in _sessions.Values.ToList())
            {
                try { s.Close(); dropped++; } catch { }
            }
            foreach (var s in _inbounds.Values.ToList())
            {
                try { s.Close(); dropped++; } catch { }
            }

            AppLog.Write("security",
                $"security policy changed, dropped {dropped} live link(s) so they renegotiate");
        }

        /// <summary>
        /// Records why a session ended. A security rejection means the link is fine but the
        /// policy is not: report it once and back off hard instead of redialling every few
        /// seconds into the same rejection.
        /// </summary>
        private void NoteSecurityOutcome(PeerSession session, string addressKey)
        {
            if (string.IsNullOrEmpty(session.SecurityFailure)) return;

            var reason = session.SecurityFailure;
            _securityCooldown[addressKey] = DateTime.UtcNow.AddSeconds(SecurityCooldownSeconds);
            _nextDialAt[addressKey] = DateTime.UtcNow.AddSeconds(SecurityCooldownSeconds);
            Log?.Invoke(session.Name, "security-blocked: " + reason);
            try { SecurityFailed?.Invoke(addressKey, session.Name, reason); } catch { }
        }

        /// <summary>Long enough that a rejected device does not monopolise the radio.</summary>
        private const int SecurityCooldownSeconds = 90;

        public bool IsRunning { get; private set; }

        /// <summary>Last measured round-trip time in ms (-1 when unknown).</summary>
        public long LastRtt { get; private set; } = -1;

        /// <summary>Last measured throughput in bytes/second.</summary>
        public double LastThroughput { get; private set; }

        private void RaiseQuality(string name, long rtt, double bps)
        {
            if (rtt > 0) LastRtt = rtt;
            if (bps > 0) LastThroughput = bps;
            QualityChanged?.Invoke(name, LastRtt, LastThroughput);
        }

        public event Action<string, string>? Log;
        public event Action<string, string>? TextReceived;
        public event Action<string, string, long>? FileReceived;

        /// <summary>from, folder name, absolute path, file count, total bytes.</summary>
        public event Action<string, string, string, int, long>? FolderStarted;

        /// <summary>from, folder name, absolute path, file count — fired once, at folder_end.</summary>
        public event Action<string, string, string, int>? FolderCompleted;

        /// <summary>
        /// (address key, peer name, connected). The address is the identity that matters: the
        /// name a session carries is whatever the peer announced in auth-hello (a hostname), which
        /// has nothing to do with the Windows pairing name the device list shows.
        /// </summary>
        public event Action<string, string, bool>? ConnectionChanged;
        public event Action<string, string, double>? TransferProgress;
        public event Action<string, long, double>? QualityChanged;

        /// <summary>
        /// A receive just opened a sink: the id is what a cancel has to name.
        ///
        /// The progress bar was already driven by [TransferProgress], but that only carries the
        /// file name — there was no way to say "stop this one", so the button only ever existed
        /// for uploads and a cancelled download left the bar frozen at its last percent.
        /// Name and size travel here so the bar can show a rate for a plain download; a folder
        /// member is skipped, because its percent belongs to the folder, not to this file.
        /// </summary>
        public event Action<string, string, long, bool>? IncomingStarted;

        /// <summary>
        /// That receive is over, one way or another (stored, aborted by either side, reaped).
        /// Fired exactly once per [IncomingStarted], so the UI can put the bar away.
        /// </summary>
        public event Action<string>? IncomingEnded;

        /// <summary>A peer asks the user to confirm a code: (peer name, code, session id).</summary>
        public event Action<string, string, string>? AuthRequested;

        /// <summary>Authentication finished: (peer name, accepted, reject reason).</summary>
        public event Action<string, bool, string?>? AuthCompleted;

        /// <summary>
        /// Live state for one device, looked up by address. The device list renders from this
        /// instead of trusting a chain of "connected"/"disconnected" events: the socket is the
        /// only truth, and a missed or reordered event then cannot leave a row lying.
        ///
        /// A socket that has not finished the handshake does *not* count. Accepting a connection
        /// is not the same as being linked: a phone whose app is not running still lets its
        /// stack accept the RFCOMM channel and then never says a word, which used to make the
        /// row flicker between "connected" and "not connected" every few seconds as the silent
        /// link was harvested and redialled.
        /// </summary>
        public bool HasLiveLink(string addressKey) =>
            (_sessions.TryGetValue(addressKey, out var out_) && out_.IsOpen && out_.Auth.IsAuthenticatedSafe)
            || (_inbounds.TryGetValue(addressKey, out var in_) && in_.IsOpen && in_.Auth.IsAuthenticatedSafe);

        /// <summary>Every session we own, outgoing and incoming.</summary>
        private IEnumerable<PeerSession> AllSessions =>
            _sessions.Values.Concat(_inbounds.Values);

        /// <summary>True while at least one device is genuinely linked (handshake done).</summary>
        /// <summary>True when the live link to this device negotiated layer-2 encryption.</summary>
        public bool IsLinkEncrypted(string addressKey) => FindLiveSession(addressKey)?.IsEncrypted == true;

        public bool HasAnyLiveLink => AllSessions.Any(s => s.IsOpen && s.Auth.IsAuthenticatedSafe);

        private sealed class IncomingFile
        {
            public string FileName = "";
            public string TempPath = "";
            public long Size;
            public long Received;
            public FileStream? Stream;
            public long StartedTicks;

            /// <summary>Fed chunk by chunk, so verifying costs no extra read pass.</summary>
            public Crc32 Crc = new Crc32();

            /// <summary>Part of a folder batch: not reported (or indexed) on its own.</summary>
            public bool InFolder = true;

            /// <summary>The folder this file belongs to, if any — it owns the progress bar.</summary>
            public ActiveFolder? Folder;

            /// <summary>Last time a chunk landed; a transfer that stops moving is reaped.</summary>
            public DateTime LastChunkAt = DateTime.UtcNow;

            /// <summary>
            /// The session this file arrives on, so cancelling from this side can answer the
            /// sender with an error on the same link instead of hunting for it.
            /// </summary>
            public PeerSession? Owner;
        }

        // ---------------------------------------------------------------- lifecycle

        public void Start()
        {
            if (IsRunning) return;

            var cts = new CancellationTokenSource();
            var listener = new BluetoothListener(ServiceUuid) { ServiceName = "ClipSync" };

            try
            {
                // Throws SocketException(NetworkDown) when the local radio isn't usable.
                listener.Start();
            }
            catch
            {
                // Leave no half-initialised state behind so a retry can succeed.
                try { listener.Stop(); } catch { }
                cts.Dispose();
                throw;
            }

            _cts = cts;
            _listener = listener;
            IsRunning = true;

            _acceptThread = new Thread(AcceptLoop) { IsBackground = true, Name = "rfcomm-accept" };
            _acceptThread.Start();

            _ = Task.Run(() => MaintenanceLoop(cts.Token));
            Log?.Invoke("service", "listening");
        }

        public void Stop()
        {
            if (!IsRunning) return;
            IsRunning = false;
            _cts?.Cancel();
            try { _listener?.Stop(); } catch { }
            _listener = null;

            foreach (var s in _sessions.Values) s.Close();
            _sessions.Clear();
            foreach (var s in _inbounds.Values) s.Close();
            _inbounds.Clear();
        }

        /// <summary>Devices we should stay connected to (set from the UI selection).</summary>
        public void SetTargets(IEnumerable<(BluetoothAddress Address, string Name, bool AutoReconnect)> targets)
        {
            var keep = new HashSet<string>();
            foreach (var (address, name, autoReconnect) in targets)
            {
                var key = address.ToString();
                keep.Add(key);
                _targets[key] = 1;
                _targetNames[key] = name;
                if (autoReconnect) _autoRetry[key] = 1;
                else _autoRetry.TryRemove(key, out _);
            }

            // Forget attempts for devices that are no longer wanted, so re-selecting one dials it.
            foreach (var key in _dialAttempts.Keys)
            {
                if (!keep.Contains(key)) _dialAttempts.TryRemove(key, out _);
            }

            // The target set used to grow forever: a device that was deselected stayed a dial
            // target, so the PC kept hammering it (up to 12s per attempt) for the rest of the
            // session. That is radio time stolen from the devices still in use.
            foreach (var key in _targets.Keys)
            {
                if (keep.Contains(key)) continue;
                _targets.TryRemove(key, out _);
                _targetNames.TryRemove(key, out _);
                _autoRetry.TryRemove(key, out _);
                _nextDialAt.TryRemove(key, out _);
                _dialFailures.TryRemove(key, out _);
            }

            // Drop links to devices that are no longer selected.
            foreach (var key in _sessions.Keys)
            {
                if (!_targets.ContainsKey(key))
                {
                    if (_sessions.TryRemove(key, out var s)) s.Close();
                }
            }
        }

        /// <summary>Clears the "already tried" marks so the next maintenance pass dials everything
        /// again -- wired to the Refresh button and to a manual target change.</summary>
        public void ResetDialAttempts() => _dialAttempts.Clear();

        private async Task MaintenanceLoop(CancellationToken token)
        {
            var lastPing = DateTime.UtcNow;
            while (!token.IsCancellationRequested)
            {
                try
                {
                    // Every target is dialled on its own task. A peer that answers in 12s (or
                    // never) used to hold the whole loop hostage: the other devices stopped
                    // being checked, re-dialled late and their heartbeats were skipped, which is
                    // exactly what made a two-device setup look like a flapping connection.
                    foreach (var key in _targets.Keys)
                    {
                        if (token.IsCancellationRequested) return;
                        // Any live socket counts here, including one still waiting for the code
                        // to be confirmed: dialling again would create a second link to the same
                        // device and each side would end up reading only half of the pair.
                        if (FindLiveSession(key) != null) continue;
                        if (_nextDialAt.TryGetValue(key, out var notBefore) && DateTime.UtcNow < notBefore) continue;
                        if (_securityCooldown.TryGetValue(key, out var secNotBefore))
                        {
                            if (DateTime.UtcNow < secNotBefore) continue;
                            _securityCooldown.TryRemove(key, out _);
                        }

                        // Auto-reconnect off: one attempt per session, no silent retry loop.
                        var retriesAllowed = _autoRetry.ContainsKey(key);
                        var attempts = _dialAttempts.GetOrAdd(key, 0);
                        if (!retriesAllowed && attempts >= 1) continue;
                        _dialAttempts[key] = attempts + 1;

                        var addr = BluetoothAddress.Parse(key);
                        var name = _targetNames.TryGetValue(key, out var n) ? n : key;
                        // ConnectAsync serialises per device and never throws, so these fire-
                        // and-forget tasks cannot surface as unobserved exceptions.
                        _ = Task.Run(async () =>
                        {
                            try { await ConnectAsync(addr, name, token).ConfigureAwait(false); }
                            catch (Exception) { }
                        });
                    }

                    // A socket that is up but has never carried a frame is not a link: the peer
                    // is frozen, or its stack accepted the connection while nobody was reading.
                    // Drop it so the dial loop can retry instead of counting it as connected --
                    // and back off, otherwise a peer that is simply not running the app gets
                    // redialled every few seconds forever.
                    foreach (var s in AllSessions)
                    {
                        if (!s.IsOpen) continue;
                        if (s.LastInboundAt != DateTime.MinValue) continue;
                        if ((DateTime.UtcNow - s.CreatedAt).TotalSeconds < SilentLinkGraceSeconds) continue;
                        Log?.Invoke(s.Name, $"silent-link-dropped ({(s.IsIncoming ? "in" : "out")})");
                        s.Close();
                        var fails = _dialFailures.AddOrUpdate(s.Address, 1, (_, v) => v + 1);
                        var wait = Math.Min(45, 5 * fails);
                        _nextDialAt[s.Address] = DateTime.UtcNow.AddSeconds(wait);
                    }

                    // Aborted transfers (the peer vanished mid-file) must release their .part
                    // file, otherwise the retry hits "being used by another process" and the
                    // file can never be received.
                    foreach (var (id, file) in _incomingFiles)
                    {
                        if ((DateTime.UtcNow - file.LastChunkAt).TotalSeconds < 120) continue;
                        Log?.Invoke("", $"file-receive-timeout: {file.FileName}");
                        CleanupIncoming(id);
                    }

                    // A link that never finishes the confirmation handshake must not linger:
                    // it either times out or is dropped as an unsupported peer.
                    foreach (var s in AllSessions)
                    {
                        if (!s.IsOpen) continue;
                        // Layer-2 handshake first: an unfinished PSK exchange means the socket is
                        // open but nothing may cross it, and it must not sit here forever.
                        if (s.Channel.CheckTimeout()) continue;
                        var outcome = s.Auth.CheckTimeout(DateTime.UtcNow);
                        if (outcome != AuthOutcome.None) ApplyAuthOutcome(s, outcome);
                    }

                    // heartbeat + latency probe
                    if ((DateTime.UtcNow - lastPing).TotalSeconds >= 15)
                    {
                        lastPing = DateTime.UtcNow;
                        foreach (var s in AllSessions)
                        {
                            if (!s.IsOpen) continue;

                            // Only worth a line when the link is drifting towards the 40s cutoff:
                            // a healthy link writes nothing, and the line that does appear is the
                            // evidence for why it was dropped. Logging every heartbeat meant a
                            // few thousand trivial lines a day that buried the real events.
                            var idle = s.LastInboundAt == DateTime.MinValue
                                ? -1
                                : (int)(DateTime.UtcNow - s.LastInboundAt).TotalSeconds;
                            if (idle >= 25)
                            {
                                Log?.Invoke(s.Name,
                                    $"hb: auth={s.Auth.IsAuthenticatedSafe} idle={idle}s (drop at {HeartbeatTimeoutSeconds}s)");
                            }

                            // A link that stops answering pings is dead even though the socket
                            // still looks open: RFCOMM reports nothing when the peer's radio goes
                            // away (screen off, service killed, walk out of range), so this side
                            // kept "connected" and dropped every frame into a void until the
                            // watchdog eventually failed a transfer. Two missed pings is enough.
                            if (s.Auth.IsAuthenticatedSafe &&
                                s.LastInboundAt != DateTime.MinValue &&
                                (DateTime.UtcNow - s.LastInboundAt).TotalSeconds > HeartbeatTimeoutSeconds)
                            {
                                Log?.Invoke(s.Name,
                                    $"heartbeat lost ({(int)(DateTime.UtcNow - s.LastInboundAt).TotalSeconds}s), dropping link");
                                s.Close();
                                continue;
                            }

                            s.Ping();
                            RaiseQuality(s.Name, s.RttMs, s.Throughput);
                        }
                    }

                    await Task.Delay(3000, token).ConfigureAwait(false);
                }
                catch (OperationCanceledException) { return; }
                catch { }
            }
        }

        /// <summary>
        /// "Always trust" is per device: a trusted peer must not be asked for the code even
        /// though the global switch is on. Advertising need=true for a trusted device would
        /// pull the peer into the confirmation flow (its side derives needsCode from what we
        /// announce), which is exactly how the phone ended up prompting again and again.
        /// </summary>
        private bool IsDeviceTrusted(string addressKey) =>
            _settings.DevicePrefs?.Any(p =>
                string.Equals(p.Address, addressKey, StringComparison.OrdinalIgnoreCase)
                && p.AlwaysTrusted) == true;

        private bool RequireConfirmFor(string addressKey) =>
            _settings.RequireConfirmCode && !IsDeviceTrusted(addressKey);

        private void AcceptLoop()
        {
            while (IsRunning)
            {
                try
                {
                    var client = _listener?.AcceptBluetoothClient();
                    if (client == null) break;

                    // Key by the peer's address so a second socket from the same device replaces
                    // the previous one instead of living next to it (two live links = each side
                    // ends up talking into a socket the other one has already dropped).
                    var address = SafeRemoteAddress(client) ?? ("in:" + Guid.NewGuid().ToString("n").Substring(0, 6));
                    var name = SafeRemoteName(client);

                    var session = new PeerSession(client, address, name, incoming: true,
                        RequireConfirmFor(address), SecurityPolicy.FromSettings(_settings));
                    session.LogSink = msg => Log?.Invoke(session.Name, msg);
                    session.MessageReceived += OnSessionMessage;
                    session.Closed += s =>
                    {
                        if (_inbounds.TryGetValue(s.Address, out var current) && ReferenceEquals(current, s))
                        {
                            _inbounds.TryRemove(s.Address, out _);
                            // Only the session that was actually current may report "gone": a
                            // replaced socket closes while its successor is already linked.
                            ConnectionChanged?.Invoke(s.Address, s.Name, false);
                        }
                        Log?.Invoke(s.Name, "incoming-closed");
                        NoteSecurityOutcome(s, s.Address);
                    };

                    // Already have a working outgoing link to this device: keep it and drop the
                    // redundant socket, otherwise both ends may settle on different ones.
                    // A peer that announces a node id is exempt: it can arbitrate, and doing it
                    // there picks the *same* surface on both ends instead of each side keeping
                    // its own socket (the bug behind "connected but nothing arrives").
                    if (_sessions.TryGetValue(address, out var outbound) && outbound.IsOpen
                        && outbound.Auth.IsAuthenticatedSafe
                        && !_peerNodes.ContainsKey(address))
                    {
                        Log?.Invoke(name, "incoming-dropped: already linked");
                        try { client.Close(); } catch { }
                        continue;
                    }

                    // Same for a healthy inbound -- again only for peers that cannot arbitrate.
                    if (_inbounds.TryGetValue(address, out var existing) && existing.IsOpen
                        && existing.Auth.IsAuthenticatedSafe
                        && (DateTime.UtcNow - existing.LastInboundAt).TotalSeconds < 30
                        && !_peerNodes.ContainsKey(address))
                    {
                        Log?.Invoke(name, "incoming-dropped: already linked (in)");
                        try { client.Close(); } catch { }
                        continue;
                    }

                    // Replace an older inbound from the same device (peer re-dialled).
                    if (_inbounds.TryRemove(address, out var stale) && !ReferenceEquals(stale, session))
                    {
                        try { stale.Close(); } catch { }
                    }

                    _inbounds[address] = session;
                    session.StartReadLoop();
                    BeginAuth(session);
                    Log?.Invoke(name, "connected (in)");
                }
                catch (Exception)
                {
                    if (!IsRunning) break;
                    Thread.Sleep(400);
                }
            }
        }

        private static string? SafeRemoteAddress(BluetoothClient client)
        {
            try
            {
                return (client.Client.RemoteEndPoint as BluetoothEndPoint)?.Address.ToString();
            }
            catch
            {
                return null;
            }
        }

        private static string SafeRemoteName(BluetoothClient client)
        {
            try { return client.RemoteMachineName ?? "device"; }
            catch { return "device"; }
        }

        // ---------------------------------------------------------------- connections

        /// <summary>
        /// Contract entry point used by the transport registry: opens (or reuses) a link to a
        /// peer and hands it back as the transport-agnostic <see cref="IPeerLink"/>.
        /// </summary>
        public async Task<IPeerLink?> OpenLinkAsync(
            TransportPeer peer, int timeoutMs, CancellationToken token = default)
        {
            if (!BluetoothAddress.TryParse(peer.Id, out var address)) return null;
            using var cts = CancellationTokenSource.CreateLinkedTokenSource(token);
            if (timeoutMs > 0) cts.CancelAfter(timeoutMs);
            if (!await ConnectAsync(address, peer.DisplayName, cts.Token).ConfigureAwait(false))
                return null;
            return await GetSessionAsync(address.ToString(), peer.DisplayName, cts.Token)
                .ConfigureAwait(false);
        }

        public async Task<bool> ConnectAsync(BluetoothAddress address, string name, CancellationToken token = default)
        {
            var key = address.ToString();
            if (HasUsableLink(key)) return true;

            var gate = _dialLocks.GetOrAdd(key, _ => new SemaphoreSlim(1, 1));
            await gate.WaitAsync(token).ConfigureAwait(false);

            // The socket is tracked outside the Task because the blocking Connect cannot be
            // cancelled: if the attempt times out, this is the only handle that can break it.
            BluetoothClient? pending = null;
            Task<BluetoothClient>? dial = null;
            try
            {
                // Another caller may have finished the dial while we were queued.
                if (HasUsableLink(key)) return true;

                pending = new BluetoothClient();
                var owned = pending;
                dial = Task.Run(() =>
                {
                    owned.Connect(new BluetoothEndPoint(address, ServiceUuid));
                    return owned;
                }, token);

                var client = await dial.WaitAsync(TimeSpan.FromSeconds(ConnectAttemptSeconds), token)
                    .ConfigureAwait(false);

                // Ownership moves to the session from here on.
                pending = null;
                dial = null;

                PeerSession session;
                try
                {
                    session = new PeerSession(client, key, name, incoming: false,
                        RequireConfirmFor(key), SecurityPolicy.FromSettings(_settings));
                }
                catch
                {
                    try { client.Close(); } catch { }
                    throw;
                }

                session.LogSink = msg => Log?.Invoke(session.Name, msg);
                session.MessageReceived += OnSessionMessage;
                session.Closed += s =>
                {
                    var wasCurrent = _sessions.TryRemove(key, out var current) && ReferenceEquals(current, s);
                    if (wasCurrent) ConnectionChanged?.Invoke(key, s.Name, false);
                    Log?.Invoke(s.Name, "disconnected");
                    NoteSecurityOutcome(s, key);
                };

                _sessions[key] = session;
                session.StartReadLoop();
                session.TrySend(new WireMessage { type = Wire.TypeHello, from = Environment.MachineName });
                BeginAuth(session);
                _dialFailures.TryRemove(key, out _);
                _nextDialAt.TryRemove(key, out _);
                Log?.Invoke(name, "connected (out)");
                return true;
            }
            catch (Exception ex)
            {
                // A dial that is still running owns the socket. Close it so the blocked connect
                // fails fast, and observe the task: an abandoned Task.Run whose Connect throws
                // later rethrows on the finalizer thread and takes the whole process down with
                // "A Task's exception(s) were not observed" (SocketException 10060 inside).
                if (pending != null)
                {
                    try { pending.Close(); } catch { }
                }
                if (dial != null) ObserveQuietly(dial);

                var fails = _dialFailures.AddOrUpdate(key, 1, (_, v) => v + 1);
                var wait = Math.Min(20, 2 * (int)Math.Pow(2, Math.Min(fails - 1, 3)));
                _nextDialAt[key] = DateTime.UtcNow.AddSeconds(wait);

                var reason = ex is TimeoutException
                    ? $"no answer within {ConnectAttemptSeconds}s"
                    : ex.Message;
                Log?.Invoke(name, $"connect-failed ({fails}, retry in {wait}s): {reason}");
                return false;
            }
            finally
            {
                gate.Release();
            }
        }

        /// <summary>
        /// Swallows a task's exception without waiting for it. Used for dials we gave up on:
        /// the fault must be marked observed so it never reaches
        /// <see cref="TaskScheduler.UnobservedTaskException"/>.
        /// </summary>
        private static void ObserveQuietly(Task task) =>
            _ = task.ContinueWith(
                t => { _ = t.Exception; },
                CancellationToken.None,
                TaskContinuationOptions.ExecuteSynchronously,
                TaskScheduler.Default);

        /// <summary>
        /// True when we already have a link to this device worth keeping: a live outbound
        /// socket, or an inbound one that finished the handshake. A silent inbound link does
        /// not count — otherwise a frozen phone would block every retry.
        /// </summary>
        private bool HasUsableLink(string key)
        {
            if (_sessions.TryGetValue(key, out var outbound) && outbound.IsOpen) return true;
            if (_inbounds.TryGetValue(key, out var inbound) && inbound.IsOpen && inbound.Auth.IsAuthenticatedSafe)
                return true;
            return false;
        }

        /// <summary>Any live link to the peer, whichever side opened it.</summary>
        private PeerSession? FindLiveSession(string addressKey)
        {
            if (_sessions.TryGetValue(addressKey, out var outbound) && outbound.IsOpen) return outbound;
            if (_inbounds.TryGetValue(addressKey, out var inbound) && inbound.IsOpen) return inbound;
            return null;
        }

        private async Task<PeerSession?> GetSessionAsync(string addressKey, string name, CancellationToken token)
        {
            var existing = FindLiveSession(addressKey);
            if (existing != null) return existing;

            var address = BluetoothAddress.Parse(addressKey);
            if (await ConnectAsync(address, name, token).ConfigureAwait(false))
            {
                return FindLiveSession(addressKey);
            }
            return null;
        }

        // ---------------------------------------------------------------- sending

        public async Task<bool> SendTextAsync(BluetoothAddress address, string name, string text,
            CancellationToken token = default, bool allowDial = true)
        {
            // The data path must not dial. A target with no live socket is skipped instead of
            // blocking this send for up to 12 seconds -- with two devices selected, one quiet
            // phone was enough to delay the clipboard for the phone that was working.
            var key = address.ToString();
            var session = allowDial
                ? await GetSessionAsync(key, name, token).ConfigureAwait(false)
                : FindLiveSession(key);
            if (session == null) return false;

            // Do not hand data to a link the user has not confirmed yet.
            if (!session.Auth.IsAuthenticatedSafe)
            {
                Log?.Invoke(name, "send-blocked: awaiting confirmation");
                return false;
            }

            var ok = session.TrySend(new WireMessage
            {
                type = Wire.TypeText,
                id = Guid.NewGuid().ToString("n"),
                text = text,
                from = Environment.MachineName
            });

            // Even without an explicit ack (older peer) the write succeeding means it was handed off.
            return ok;
        }

        /// <summary>Human readable reason for the last failed transfer (null when it worked).</summary>
        public string? LastTransferError { get; private set; }

        /// <summary>
        /// Members the last folder send left behind because they were over the size limit.
        /// The transfer itself can still succeed, so this needs its own channel to the UI.
        /// </summary>
        public int LastFolderSkipped { get; private set; }

        /// <summary>Seconds of no progress after which a send gives up and drops the link.</summary>
        private const int SendStallSeconds = 30;

        public async Task<bool> SendFileAsync(
            BluetoothAddress address,
            string name,
            string filePath,
            IProgress<double>? progress = null,
            CancellationToken token = default,
            string? relativePath = null)
        {
            LastTransferError = null;

            // Declared out here so the catch and finally can still reach them: the watchdog is
            // what turns a permanently blocked write into a normal, reportable failure.
            Timer? watchdog = null;
            var stalled = new bool[1];

            var session = await GetSessionAsync(address.ToString(), name, token).ConfigureAwait(false);
            if (session == null)
            {
                LastTransferError = I18n.Instance.T("status.connFailed", name);
                return false;
            }

            if (!session.Auth.IsAuthenticatedSafe)
            {
                LastTransferError = I18n.Instance.T("status.authPending");
                return false;
            }

            var info = new FileInfo(filePath);
            var id = Guid.NewGuid().ToString("n");
            Log?.Invoke(name, $"file-send begin: {info.Name} ({info.Length} bytes)");

            // The receiver answers with ack/error once file_end lands, so "sent" really means
            // "the other side stored it" instead of "we managed to write bytes".
            var ack = new TaskCompletionSource<bool>(TaskCreationOptions.RunContinuationsAsynchronously);
            _pendingAcks[id] = ack;

            try
            {
                if (!session.TrySend(new WireMessage
                {
                    type = Wire.TypeFileBegin,
                    id = id,
                    name = info.Name,
                    size = info.Length,
                    mime = GuessMime(info.Name),
                    from = Environment.MachineName,
                    // Empty for a plain transfer, the folder-relative location inside a folder.
                    path = relativePath ?? ""
                }))
                {
                    LastTransferError = I18n.Instance.T("status.connFailed", name);
                    return false;
                }

                var stopwatch = Stopwatch.StartNew();
                long sentBytes = 0;
                // Computed on the fly: the receiver compares it before it publishes the file, so
                // a transfer corrupted in the middle never shows up under its real name.
                var crc = new Crc32();
                var buffer = new byte[Wire.ChunkSize];

                // Last time a chunk actually went out, and whether the watchdog below gave up.
                // An RFCOMM stream has no write timeout: a peer that stops reading (screen off,
                // app frozen, link half-dead) blocks stream.Write forever, and the transfer plus
                // everything queued behind it hangs with no error at all. Dropping the link is
                // the only way to unblock that write, and it lets the reconnect logic start.
                var lastProgress = new long[] { DateTime.UtcNow.Ticks, 0 };
                watchdog = new Timer(_ =>
                {
                    var idle = DateTime.UtcNow.Ticks - Interlocked.Read(ref lastProgress[0]);
                    if (idle < TimeSpan.FromSeconds(SendStallSeconds).Ticks) return;
                    stalled[0] = true;
                    Log?.Invoke(name,
                        $"send stalled {SendStallSeconds}s at {Interlocked.Read(ref lastProgress[1])}/{info.Length}, closing link");
                    try { session.Close(); } catch { }
                }, null, TimeSpan.FromSeconds(5), TimeSpan.FromSeconds(5));

                using (var fs = new FileStream(filePath, FileMode.Open, FileAccess.Read, FileShare.ReadWrite))
                {
                    int read;
                    int seq = 0;
                    while ((read = await fs.ReadAsync(buffer.AsMemory(0, buffer.Length), token).ConfigureAwait(false)) > 0)
                    {
                        token.ThrowIfCancellationRequested();

                        // The phone tapped cancel on its receive row: it will never store these
                        // bytes, so stop instead of pushing the rest of the file over a slow
                        // RFCOMM link. Checked here so the stop lands within one chunk.
                        if (_abortedSends.ContainsKey(id))
                        {
                            LastTransferError ??= I18n.Instance.T("status.transferCancelled");
                            Log?.Invoke(name, $"file-send aborted by peer: {info.Name} at {sentBytes}/{info.Length}");
                            return false;
                        }

                        var chunk = new byte[read];
                        Buffer.BlockCopy(buffer, 0, chunk, 0, read);

                        // Raw bytes when the peer said it understands them; base64 otherwise.
                        // Same encrypted frame, same sequence numbers — only the payload differs.
                        var chunkSent = session.SupportsRawChunk
                            ? session.TrySendRawChunk(id, seq, chunk)
                            : session.TrySend(new WireMessage
                            {
                                type = Wire.TypeFileChunk,
                                id = id,
                                seq = seq,
                                data = Convert.ToBase64String(chunk)
                            });
                        seq++;

                        if (!chunkSent)
                        {
                            LastTransferError = I18n.Instance.T("status.connFailed", name);
                            return false;
                        }

                        crc.Update(chunk, 0, read);
                        sentBytes += read;
                        Interlocked.Exchange(ref lastProgress[0], DateTime.UtcNow.Ticks);
                        Interlocked.Exchange(ref lastProgress[1], sentBytes);
                        progress?.Report(info.Length == 0 ? 1 : (double)sentBytes / info.Length);
                    }
                }

                // Past this point the file is out; a slow ack is a different failure and has its
                // own 20s wait, so the stall watchdog must not fire during it.
                watchdog.Dispose();
                watchdog = null;

                if (!session.TrySend(new WireMessage
                {
                    type = Wire.TypeFileEnd,
                    id = id,
                    size = sentBytes,
                    crc = crc.Value
                }))
                {
                    LastTransferError = I18n.Instance.T("status.connFailed", name);
                    return false;
                }

                stopwatch.Stop();
                if (stopwatch.Elapsed.TotalSeconds > 0.05 && sentBytes > 0)
                {
                    session.Throughput = sentBytes / stopwatch.Elapsed.TotalSeconds;
                    RaiseQuality(session.Name, session.RttMs, session.Throughput);
                }

                // Wait for the receiver to confirm (or explain why it could not store the file).
                var timeout = TimeSpan.FromSeconds(20);
                var finished = await Task.WhenAny(ack.Task, Task.Delay(timeout, token)).ConfigureAwait(false);
                if (finished != ack.Task)
                {
                    LastTransferError = I18n.Instance.T("status.fileNoAck");
                    return false;
                }

                var ok = await ack.Task.ConfigureAwait(false);
                if (!ok && LastTransferError == null)
                    LastTransferError = I18n.Instance.T("status.fileRejected");
                return ok;
            }
            catch (OperationCanceledException) when (token.IsCancellationRequested)
            {
                // The user pressed cancel, so the receiver is left holding a half file. Tell it
                // rather than letting it wait for a file_end that will never come.
                session.TrySend(new WireMessage
                {
                    type = Wire.TypeError,
                    id = id,
                    reason = I18n.Instance.T("status.transferCancelled")
                });
                LastTransferError = I18n.Instance.T("status.transferCancelled");
                return false;
            }
            catch (OperationCanceledException)
            {
                LastTransferError = I18n.Instance.T("status.fileNoAck");
                return false;
            }
            catch (Exception ex)
            {
                // A write that finally unblocked because the watchdog closed the link lands
                // here, as does any other transport fault. Either way the transfer failed and
                // the user deserves a reason instead of a silent hang.
                LastTransferError = stalled[0]
                    ? I18n.Instance.T("status.peerStalled")
                    : I18n.Instance.T("status.connFailed", name);
                Log?.Invoke(name, $"file-send error: {info.Name}: {ex.GetType().Name} {ex.Message}");
                return false;
            }
            finally
            {
                watchdog?.Dispose();
                _pendingAcks.TryRemove(id, out _);
                _abortedSends.TryRemove(id, out _);
            }
        }

        /// <summary>
        /// Sends a whole folder: one folder_begin, one mkdir per directory, then every file as an
        /// ordinary transfer carrying its relative path, and a folder_end when it is done.
        ///
        /// Walk is breadth-first and depth-limited, and skips symlinked directories: following
        /// them could loop forever or drag in half the disk through one junction point.
        /// </summary>
        public async Task<bool> SendFolderAsync(
            BluetoothAddress address,
            string name,
            string folderPath,
            IProgress<(int Done, int Total, string File)>? progress = null,
            CancellationToken token = default)
        {
            LastTransferError = null;
            LastFolderSkipped = 0;

            var root = new DirectoryInfo(folderPath);
            if (!root.Exists)
            {
                LastTransferError = I18n.Instance.T("status.fileMissing");
                return false;
            }

            var session = await GetSessionAsync(address.ToString(), name, token).ConfigureAwait(false);
            if (session == null)
            {
                LastTransferError = I18n.Instance.T("status.connFailed", name);
                return false;
            }
            if (!session.Auth.IsAuthenticatedSafe)
            {
                LastTransferError = I18n.Instance.T("status.authPending");
                return false;
            }

            var (directories, allFiles) = ScanFolder(root);

            // The same cap a single file obeys. A folder used to walk straight past it, so the
            // receiver rejected the oversized member and the whole transfer died half way with
            // no hint about which file was at fault.
            var limit = EffectiveFileLimit();
            var files = allFiles.Where(f => f.Length <= limit).ToList();
            LastFolderSkipped = allFiles.Count - files.Count;

            if (files.Count == 0 && allFiles.Count > 0)
            {
                LastTransferError = I18n.Instance.T("status.folderAllTooLarge",
                    allFiles.Count, HumanSize(limit));
                return false;
            }

            var totalBytes = files.Sum(f => f.Length);
            var folderId = Guid.NewGuid().ToString("n");

            Log?.Invoke(name, $"folder-send begin: {root.Name} ({files.Count} files, {totalBytes} bytes)");

            if (!session.TrySend(new WireMessage
            {
                type = Wire.TypeFolderBegin,
                id = folderId,
                name = root.Name,
                files = files.Count,
                size = totalBytes,
                from = Environment.MachineName
            }))
            {
                LastTransferError = I18n.Instance.T("status.connFailed", name);
                return false;
            }

            var rootName = root.Name;
            var rootFull = root.FullName;
            foreach (var dir in directories)
            {
                if (!session.TrySend(new WireMessage
                {
                    type = Wire.TypeMkdir,
                    id = folderId,
                    path = Relative(rootFull, rootName, dir),
                    from = Environment.MachineName
                }))
                {
                    LastTransferError = I18n.Instance.T("status.connFailed", name);
                    await CloseFolderOnWireAsync(address, name, folderId, rootName, 0, token)
                        .ConfigureAwait(false);
                    return false;
                }
            }

            var done = 0;
            foreach (var file in files)
            {
                token.ThrowIfCancellationRequested();

                // The size limit is enforced per file by the receiver; a folder is not one item.
                var ok = await SendFileAsync(
                    address, name, file.FullName,
                    token: token,
                    relativePath: Relative(rootFull, rootName, file.FullName)).ConfigureAwait(false);

                if (!ok)
                {
                    // Close the transfer on the wire even when it broke half way: the receiver
                    // waits for folder_end to drop its progress row, create the empty
                    // directories and point the history entry at the folder. Without it the row
                    // stayed on the phone forever and the entry claimed to be missing.
                    await CloseFolderOnWireAsync(address, name, folderId, rootName, done, token)
                        .ConfigureAwait(false);
                    Log?.Invoke(name, $"folder-send aborted: {rootName} after {done}/{files.Count} files");
                    return false;
                }

                done++;
                progress?.Report((done, files.Count, file.Name));
            }

            session = await GetSessionAsync(address.ToString(), name, token).ConfigureAwait(false);
            session?.TrySend(new WireMessage
            {
                type = Wire.TypeFolderEnd,
                id = folderId,
                name = rootName,
                files = files.Count
            });

            Log?.Invoke(name, $"folder-send done: {rootName} ({files.Count} files)");
            return true;
        }

        /// <summary>
        /// Best-effort folder_end for an interrupted folder send: the frame carries how many
        /// files really arrived, so the receiver can close the transfer instead of waiting.
        /// </summary>
        private async Task CloseFolderOnWireAsync(
            BluetoothAddress address,
            string name,
            string folderId,
            string rootName,
            int files,
            CancellationToken token)
        {
            try
            {
                var session = await GetSessionAsync(address.ToString(), name, token).ConfigureAwait(false);
                session?.TrySend(new WireMessage
                {
                    type = Wire.TypeFolderEnd,
                    id = folderId,
                    name = rootName,
                    files = files
                });
            }
            catch (OperationCanceledException)
            {
                // Cancelled because the link is already gone; there is nothing left to tell.
            }
        }

        /// <summary>
        /// Every directory and file under <paramref name="root"/>, depth-limited and without
        /// following directory symlinks. Returns the directories first so the receiver can create
        /// them (including empty ones) before any file lands.
        /// </summary>
        private static (List<string> Directories, List<FileInfo> Files) ScanFolder(DirectoryInfo root)
        {
            var directories = new List<string>();
            var files = new List<FileInfo>();
            var queue = new Queue<(DirectoryInfo Dir, int Depth)>();
            queue.Enqueue((root, 0));

            while (queue.Count > 0)
            {
                var (dir, depth) = queue.Dequeue();
                directories.Add(dir.FullName);

                IEnumerable<FileInfo> children;
                IEnumerable<DirectoryInfo> subdirs;
                try
                {
                    children = dir.GetFiles();
                    subdirs = dir.GetDirectories();
                }
                catch (Exception)
                {
                    // Unreadable folder (permissions, OneDrive placeholder): skip it rather than
                    // failing the whole transfer.
                    continue;
                }

                foreach (var file in children)
                {
                    if ((file.Attributes & FileAttributes.ReparsePoint) != 0) continue;
                    files.Add(file);
                }

                if (depth + 1 >= Wire.MaxFolderDepth) continue;

                foreach (var sub in subdirs)
                {
                    if ((sub.Attributes & FileAttributes.ReparsePoint) != 0) continue;
                    queue.Enqueue((sub, depth + 1));
                }
            }

            return (directories, files);
        }

        /// <summary>
        /// Destination path of <paramref name="full"/>, including the folder name itself as the
        /// first segment ("MyFolder/sub/a.txt").
        ///
        /// The folder name is part of the path on purpose: without it every transfer would be
        /// unpacked straight into the receiver's download folder and two folders with the same
        /// file names would collide.
        ///
        /// The root is matched as a prefix rather than searched for: a path may contain the
        /// folder name again further down ("D:\Photos\Photos\a.jpg"), and the old substring
        /// search cut at the first hit, dropping whole levels.
        /// </summary>
        private static string Relative(string rootFull, string rootName, string full)
        {
            var root = rootFull.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
            var path = full.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);

            string relative;
            if (path.Equals(root, StringComparison.OrdinalIgnoreCase))
                relative = "";     // the root itself
            else if (path.StartsWith(root + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                relative = path.Substring(root.Length + 1);
            else
                relative = Path.GetFileName(path);

            relative = relative.Replace(Path.DirectorySeparatorChar, '/');
            return relative.Length == 0 ? rootName : rootName + "/" + relative;
        }

        // ---------------------------------------------------------------- authentication

        /// <summary>
        /// Starts the confirmation handshake: announce whether *we* want a code, then hand over
        /// our nonce. Both ends do this, so whoever connects second simply joins the same
        /// derivation.
        /// </summary>
        /// <summary>
        /// Resolves two live sockets to the same peer. Both ends hold the same pair, so both
        /// must reach the same verdict: the socket opened by the device with the smaller node
        /// id survives. Peers older than 2.1 announce nothing; for those this PC keeps its own
        /// outbound socket, which is the arrangement that has always worked with them.
        /// </summary>
        private void Arbitrate(string address)
        {
            var both = new List<PeerSession>(2);
            if (_sessions.TryGetValue(address, out var outbound) && outbound.IsOpen) both.Add(outbound);
            if (_inbounds.TryGetValue(address, out var inbound) && inbound.IsOpen) both.Add(inbound);
            if (both.Count < 2) return;

            var peer = both.Select(s => s.PeerNode).FirstOrDefault(n => !string.IsNullOrEmpty(n));
            PeerSession winner;
            if (!string.IsNullOrEmpty(peer) && !string.Equals(peer, _nodeId, StringComparison.Ordinal))
            {
                winner = both
                    .OrderBy(s => s.IsIncoming ? peer : _nodeId, StringComparer.Ordinal)
                    .First();
            }
            else
            {
                winner = both.First(s => !s.IsIncoming);
            }

            foreach (var loser in both.Where(s => !ReferenceEquals(s, winner)))
            {
                Log?.Invoke(loser.Name, $"arbitrated-out ({(loser.IsIncoming ? "in" : "out")})");
                loser.Close();
            }
            Log?.Invoke(winner.Name, $"arbitrated-in ({(winner.IsIncoming ? "in" : "out")})");
        }

        private void BeginAuth(PeerSession session)
        {
            session.TrySend(new WireMessage
            {
                type = Wire.TypeAuthHello,
                sid = session.Auth.Sid,
                from = Environment.MachineName,
                need = session.Auth.RequireConfirm,
                node = _nodeId
            });

            session.TrySend(new WireMessage
            {
                type = Wire.TypeAuthNonce,
                sid = session.Auth.Sid,
                nonce = session.Auth.LocalNonce
            });
        }

        /// <summary>The local user answered the dialog. Returns false when the request is gone.</summary>
        public bool RespondToAuth(string sid, bool accept)
        {
            var session = AllSessions.FirstOrDefault(s => s.Auth.Sid == sid);
            if (session == null || !session.IsOpen) return false;

            var outcome = session.Auth.MarkMine(accept);

            // Tell the peer either way; a rejection comes with a result frame right after.
            session.TrySend(new WireMessage
            {
                type = Wire.TypeAuthConfirm,
                sid = sid,
                ok = accept
            });

            ApplyAuthOutcome(session, outcome);
            return true;
        }

        private void ApplyAuthOutcome(PeerSession session, AuthOutcome outcome)
        {
            switch (outcome)
            {
                case AuthOutcome.NeedUser:
                    Log?.Invoke(session.Name, "auth-awaiting-user");
                    AuthRequested?.Invoke(session.Name, session.Auth.Code ?? "------", session.Auth.Sid);
                    break;

                case AuthOutcome.Authorized:
                    session.TrySend(new WireMessage
                    {
                        type = Wire.TypeAuthResult,
                        sid = session.Auth.Sid,
                        ok = true
                    });
                    Log?.Invoke(session.Name, "auth-ok");
                    ConnectionChanged?.Invoke(session.Address, session.Name, true);
                    AuthCompleted?.Invoke(session.Name, true, null);
                    break;

                case AuthOutcome.Rejected:
                    session.TrySend(new WireMessage
                    {
                        type = Wire.TypeAuthResult,
                        sid = session.Auth.Sid,
                        ok = false,
                        reason = session.Auth.RejectReason
                    });
                    Log?.Invoke(session.Name, "auth-rejected: " + session.Auth.RejectReason);
                    AuthCompleted?.Invoke(session.Name, false, session.Auth.RejectReason);
                    session.Close();
                    break;
            }
        }

        private void OnAuthFrame(PeerSession session, WireMessage msg)
        {
            // The sid tags who originated an exchange; each end generates its own, so it can
            // never be used to filter incoming frames (both sides would drop each other's
            // hello and the handshake would dead-lock into "peer-unsupported"). A session's
            // auth state is created fresh with its socket, so there is nothing stale to skip.

            switch (msg.type)
            {
                case Wire.TypeAuthHello:
                    if (!string.IsNullOrWhiteSpace(msg.from)) session.Name = msg.from!;
                    if (!string.IsNullOrWhiteSpace(msg.node))
                    {
                        session.PeerNode = msg.node!;
                        _peerNodes[session.Address] = msg.node!;
                        // Two sockets to the same peer (both ends dialled at once) must be
                        // resolved now: keeping one on each side is what goes deaf.
                        Arbitrate(session.Address);
                    }
                    ApplyAuthOutcome(session, session.Auth.NotePeerHello(msg.from, msg.need));
                    break;

                case Wire.TypeAuthNonce:
                    ApplyAuthOutcome(session, session.Auth.NotePeerNonce(msg.nonce ?? ""));
                    break;

                case Wire.TypeAuthConfirm:
                    ApplyAuthOutcome(session, session.Auth.MarkPeer(msg.ok ?? false));
                    break;

                case Wire.TypeAuthResult:
                    if (msg.ok == false)
                    {
                        Log?.Invoke(session.Name, "peer-auth-result: rejected");
                        session.Close();
                    }
                    break;
            }
        }

        private static bool IsAuthFrame(string type) =>
            type is Wire.TypeAuthHello or Wire.TypeAuthNonce or Wire.TypeAuthConfirm or Wire.TypeAuthResult;

        // ---------------------------------------------------------------- receiving

        private void OnSessionMessage(PeerSession session, WireMessage msg)
        {
            try
            {
                // Authentication frames are always allowed; everything else needs a confirmed
                // link, otherwise a paired-but-wrong device could inject clipboard content.
                if (IsAuthFrame(msg.type))
                {
                    OnAuthFrame(session, msg);
                    return;
                }

                if (!session.Auth.IsAuthenticatedSafe)
                {
                    Log?.Invoke(session.Name, "dropped-unauthenticated: " + msg.type);
                    return;
                }

                switch (msg.type)
                {
                    case Wire.TypeText:
                        if (msg.text != null && Encoding.UTF8.GetByteCount(msg.text) > Wire.MaxTextBytes)
                        {
                            Log?.Invoke(session.Name, "dropped-text: too large");
                            break;
                        }
                        if (!string.IsNullOrEmpty(msg.text))
                            TextReceived?.Invoke(string.IsNullOrEmpty(msg.from) ? session.Name : msg.from!, msg.text!);
                        break;

                    case Wire.TypeFileBegin:
                        StartIncomingFile(session, msg);
                        break;

                    case Wire.TypeFileChunk:
                        AppendIncomingChunk(msg);
                        break;

                    case Wire.TypeFileEnd:
                        FinishIncomingFile(session, msg);
                        break;

                    case Wire.TypeFolderBegin:
                        StartIncomingFolder(session, msg);
                        break;

                    case Wire.TypeMkdir:
                        EnsureIncomingDirectory(session, msg);
                        break;

                    case Wire.TypeFolderEnd:
                        FinishIncomingFolder(session, msg);
                        break;

                    case Wire.TypeAck:
                        if (msg.id != null && _pendingAcks.TryGetValue(msg.id, out var ackTcs))
                            ackTcs.TrySetResult(msg.ok ?? true);
                        break;

                    case Wire.TypeError:
                        LastTransferError = string.IsNullOrWhiteSpace(msg.reason)
                            ? I18n.Instance.T("status.fileRejected")
                            : msg.reason;
                        Log?.Invoke(session.Name, "peer-error: " + LastTransferError);
                        if (msg.id != null)
                        {
                            if (_pendingAcks.TryGetValue(msg.id, out var errTcs))
                                errTcs.TrySetResult(false);
                            // The sender gave up (cancel): drop the half file instead of leaving
                            // a pending row that nothing will ever finish.
                            CleanupIncoming(msg.id);
                            // The receiver gave up: stop uploading the rest of this file.
                            _abortedSends[msg.id] = 0;
                        }
                        break;

                    case Wire.TypeHello:
                        session.Name = string.IsNullOrWhiteSpace(msg.from) ? session.Name : msg.from!;
                        break;
                }
            }
            catch (Exception ex)
            {
                Log?.Invoke(session.Name, "receive-error: " + ex.Message);
            }
        }

        // ---------------------------------------------------------------- folder transfers

        private sealed class ActiveFolder
        {
            /// <summary>Folder name as the sender knows it; paths are prefixed with it.</summary>
            public string Name = "";
            public string RootPath = "";
            public int Expected;
            public int Completed;

            /// <summary>Total and already-landed bytes, for one folder-wide progress bar.</summary>
            public long TotalBytes;
            public long BytesDone;
        }

        private readonly ConcurrentDictionary<string, ActiveFolder> _incomingFolders =
            new ConcurrentDictionary<string, ActiveFolder>();

        /// <summary>
        /// Opens a folder transfer and creates its root. Everything else — the directory list,
        /// then the files — arrives as separate frames, so an interrupted transfer leaves only
        /// what actually landed.
        /// </summary>
        private void StartIncomingFolder(PeerSession session, WireMessage msg)
        {
            var id = msg.id ?? Guid.NewGuid().ToString("n");
            try
            {
                var download = _settings.ResolvedDownloadFolder;
                Directory.CreateDirectory(download);

                var name = SanitizeFileName(msg.name ?? "folder");
                var root = UniqueDirectory(download, name);

                _incomingFolders[id] = new ActiveFolder
                {
                    Name = name,
                    RootPath = root,
                    Expected = msg.files,
                    TotalBytes = msg.size
                };

                FolderStarted?.Invoke(session.Name, Path.GetFileName(root), root, msg.files, msg.size);
                Log?.Invoke(session.Name, $"folder-begin: {root} ({msg.files} files, {msg.size} bytes)");
            }
            catch (Exception ex)
            {
                Log?.Invoke(session.Name, "folder-begin failed: " + ex.Message);
            }
        }

        /// <summary>Creates one directory of the transfer, including the empty ones.</summary>
        private void EnsureIncomingDirectory(PeerSession session, WireMessage msg)
        {
            if (string.IsNullOrWhiteSpace(msg.path)) return;
            var id = msg.id ?? "";
            if (!_incomingFolders.TryGetValue(id, out var folder))
            {
                // Older Android builds send mkdir without the folder id. Dropping those frames
                // silently meant a folder whose sub-directories were all empty arrived as one
                // empty root: every mkdir was thrown away before this. With exactly one transfer
                // in flight there is no ambiguity; with more than one, guessing would be worse
                // than skipping.
                if (_incomingFolders.Count != 1) return;
                folder = _incomingFolders.Values.First();
            }

            var target = MapUnder(folder, msg.path!);
            if (target == null)
            {
                Log?.Invoke(session.Name, "mkdir-rejected: " + msg.path);
                return;
            }

            try
            {
                Directory.CreateDirectory(target);
            }
            catch (Exception ex)
            {
                Log?.Invoke(session.Name, "mkdir failed: " + ex.Message);
            }
        }

        /// <summary>Closes the transfer; the folder is reported once, not once per file.</summary>
        private void FinishIncomingFolder(PeerSession session, WireMessage msg)
        {
            var id = msg.id ?? "";
            if (!_incomingFolders.TryRemove(id, out var folder))
            {
                Log?.Invoke(session.Name, "folder-end without begin: " + id);
                return;
            }

            var name = Path.GetFileName(folder.RootPath);
            FolderCompleted?.Invoke(session.Name, name, folder.RootPath, folder.Completed);
            Log?.Invoke(session.Name, $"folder-end: {name} ({folder.Completed} files)");
        }

        /// <summary>The active folder a file path belongs to, or null for a plain transfer.</summary>
        private ActiveFolder? FindFolderFor(string? path)
        {
            if (string.IsNullOrWhiteSpace(path)) return null;
            var normalised = path!.Replace('\\', '/');
            foreach (var folder in _incomingFolders.Values)
            {
                if (normalised.Equals(folder.Name, StringComparison.OrdinalIgnoreCase)) return folder;
                if (normalised.StartsWith(folder.Name + "/", StringComparison.OrdinalIgnoreCase))
                    return folder;
            }
            return null;
        }

        /// <summary>
        /// Absolute destination of a wire path inside <paramref name="folder"/>.
        ///
        /// The path is untrusted: it comes from another device. Every segment is sanitised,
        /// ".." and drive letters are dropped, and the result must still live under the folder
        /// root — a peer must not be able to write anywhere else through "../".
        /// </summary>
        private static string? MapUnder(ActiveFolder folder, string relative)
        {
            var segments = relative.Replace('\\', '/')
                .Split('/', StringSplitOptions.RemoveEmptyEntries)
                .Select(part => part.Trim())
                .Where(part => part.Length > 0 && part != "." && part != ".." && !part.Contains(':'))
                .Select(SanitizeFileName)
                .ToList();

            if (segments.Count == 0) return null;

            // The first segment is the sender's folder name; ours may carry a " (2)" suffix.
            segments[0] = Path.GetFileName(folder.RootPath);

            var baseDir = Path.GetDirectoryName(folder.RootPath) ?? folder.RootPath;
            var full = Path.GetFullPath(Path.Combine(baseDir, string.Join(Path.DirectorySeparatorChar.ToString(), segments)));
            var baseFull = Path.GetFullPath(baseDir);

            return full.StartsWith(baseFull + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase)
                ? full
                : null;
        }

        private static string UniqueDirectory(string parent, string name)
        {
            var path = Path.Combine(parent, name);
            if (!Directory.Exists(path) && !File.Exists(path))
            {
                Directory.CreateDirectory(path);
                return path;
            }

            for (int i = 1; i < 1000; i++)
            {
                var candidate = Path.Combine(parent, $"{name} ({i})");
                if (Directory.Exists(candidate) || File.Exists(candidate)) continue;
                Directory.CreateDirectory(candidate);
                return candidate;
            }

            var fallback = Path.Combine(parent, $"{name}-{DateTime.Now:HHmmss}");
            Directory.CreateDirectory(fallback);
            return fallback;
        }

        private void StartIncomingFile(PeerSession session, WireMessage msg)
        {
            var id = msg.id ?? Guid.NewGuid().ToString("n");

            // The receiver enforces its own limit: a peer must not be able to fill the disk,
            // no matter what it claims about the file or about its own settings.
            var receiverLimit = EffectiveFileLimit();
            if (msg.size < 0 || msg.size > receiverLimit)
            {
                session.TrySend(new WireMessage
                {
                    type = Wire.TypeError,
                    id = id,
                    reason = I18n.Instance.T("status.fileTooLarge", HumanSize(receiverLimit))
                });
                Log?.Invoke(session.Name, $"file-rejected: {msg.size} bytes > limit {receiverLimit}");
                return;
            }

            if (_incomingFiles.ContainsKey(id))
            {
                CleanupIncoming(id);
            }

            try
            {
                var download = _settings.ResolvedDownloadFolder;
                Directory.CreateDirectory(download);

                var safeName = SanitizeFileName(msg.name ?? "file.bin");
                var owner = FindFolderFor(msg.path);

                string target;
                if (owner != null)
                {
                    // A folder file carries its destination ("Root/sub/a.txt"). The first
                    // segment is the sender's folder name and is replaced by the one created
                    // here, which may carry a " (2)" suffix when the name was taken.
                    var mapped = MapUnder(owner, msg.path!);
                    if (mapped == null)
                    {
                        session.TrySend(new WireMessage
                        {
                            type = Wire.TypeError,
                            id = id,
                            reason = I18n.Instance.T("status.fileCannotSave", msg.path)
                        });
                        Log?.Invoke(session.Name, "file-rejected: unsafe path " + msg.path);
                        return;
                    }

                    var dir = Path.GetDirectoryName(mapped);
                    if (!string.IsNullOrEmpty(dir)) Directory.CreateDirectory(dir);
                    target = UniquePath(dir ?? owner.RootPath, Path.GetFileName(mapped));
                    owner.Completed++;
                }
                else
                {
                    target = UniquePath(download, safeName);
                }

                var temp = target + ".part";

                var file = new IncomingFile
                {
                    FileName = Path.GetFileName(target),
                    TempPath = temp,
                    Size = msg.size,
                    Stream = new FileStream(temp, FileMode.Create, FileAccess.Write, FileShare.None),
                    StartedTicks = Stopwatch.GetTimestamp(),
                    // A path on the wire only ever means "this file travels inside a folder", so
                    // it decides membership even when folder_begin was missed — otherwise the
                    // stray member became a history row of its own.
                    InFolder = owner != null || !string.IsNullOrWhiteSpace(msg.path),
                    Folder = owner,
                    Owner = session
                };

                _incomingFiles[id] = file;
                TransferProgress?.Invoke(file.FileName, "0", 0);
                IncomingStarted?.Invoke(id, file.FileName, file.Size, file.InFolder);
                Log?.Invoke(session.Name, "file-begin: " + target + " (" + msg.size + " bytes)");
            }
            catch (Exception ex)
            {
                // Tell the sender instead of letting it think the transfer worked.
                session.TrySend(new WireMessage
                {
                    type = Wire.TypeError,
                    id = id,
                    reason = I18n.Instance.T("status.fileCannotSave", ex.Message)
                });
                Log?.Invoke(session.Name, "file-begin failed: " + ex.Message);
            }
        }

        private void AppendIncomingChunk(WireMessage msg)
        {
            var id = msg.id ?? "";
            if (!_incomingFiles.TryGetValue(id, out var file) || file.Stream == null) return;
            if (msg.rawData == null && msg.data == null) return;

            // Binary frames already carry the bytes; base64 is only the fallback for peers that
            // never answered the raw-capability ping.
            var bytes = msg.rawData ?? Convert.FromBase64String(msg.data!);
            if (bytes.Length > Wire.ChunkSize * 2)
            {
                Log?.Invoke("", $"chunk-rejected: {bytes.Length} bytes");
                return;
            }

            // Never write more than the size announced by file_begin.
            if (file.Size > 0 && file.Received + bytes.Length > file.Size)
            {
                Log?.Invoke("", "chunk-rejected: exceeds declared size");
                CleanupIncoming(id);
                return;
            }

            file.Stream.Write(bytes, 0, bytes.Length);
            file.Crc.Update(bytes, 0, bytes.Length);
            file.Received += bytes.Length;
            file.LastChunkAt = DateTime.UtcNow;

            var percent = file.Size > 0 ? (double)file.Received / file.Size : 0;

            if (file.Folder != null)
            {
                // One row for the folder: report its overall share and how many files landed,
                // so the transfer list reads "代办 3/12" instead of a dozen restarting bars.
                var folder = file.Folder;
                var total = folder.TotalBytes > 0 ? folder.TotalBytes : file.Size;
                var done = folder.BytesDone + file.Received;
                TransferProgress?.Invoke(
                    $"{Path.GetFileName(folder.RootPath)}  {folder.Completed}/{folder.Expected}",
                    "recv",
                    total > 0 ? Math.Clamp((double)done / total, 0, 1) : percent);
            }
            else
            {
                TransferProgress?.Invoke(file.FileName, "recv", percent);
            }
        }

        private void FinishIncomingFile(PeerSession session, WireMessage msg)
        {
            var id = msg.id ?? "";
            if (!_incomingFiles.TryGetValue(id, out var file))
            {
                session.TrySend(new WireMessage
                {
                    type = Wire.TypeError,
                    id = id,
                    reason = I18n.Instance.T("status.fileMissing")
                });
                return;
            }

            var elapsed = (Stopwatch.GetTimestamp() - file.StartedTicks) / (double)Stopwatch.Frequency;
            file.Stream?.Flush();
            file.Stream?.Dispose();
            file.Stream = null;

            // Whatever happened to this file, the bytes it did deliver are behind us: the folder
            // bar must move forward instead of restarting at the next file.
            if (file.Folder != null) file.Folder.BytesDone += file.Received;

            try
            {
                // Verify before publishing: a file that arrived damaged must never appear
                // under its real name, not even briefly.
                if (msg.crc != 0 && file.Crc.Value != msg.crc)
                {
                    Log?.Invoke(session.Name,
                        $"crc-mismatch: {file.FileName} expected={msg.crc} actual={file.Crc.Value}");
                    CleanupIncoming(id);
                    session.TrySend(new WireMessage
                    {
                        type = Wire.TypeError,
                        id = id,
                        reason = I18n.Instance.T("status.fileCorrupt")
                    });
                    return;
                }

                var finalPath = file.TempPath.EndsWith(".part")
                    ? file.TempPath[..^5]
                    : file.TempPath;
                if (File.Exists(finalPath)) File.Delete(finalPath);
                File.Move(file.TempPath, finalPath);

                if (elapsed > 0.05 && file.Received > 0)
                {
                    var bps = file.Received / elapsed;
                    RaiseQuality("", -1, bps);
                }

                TransferProgress?.Invoke(file.FileName, "done", 1);

                // Files inside a folder are not rows of their own: the folder reports once and
                // owns the history entry. Fifty separate rows would bury the list.
                if (!file.InFolder) FileReceived?.Invoke("", finalPath, file.Received);
                session.TrySend(new WireMessage { type = Wire.TypeAck, id = id, ok = true });
                Log?.Invoke(session.Name, "file-done: " + finalPath);
            }
            catch (Exception ex)
            {
                session.TrySend(new WireMessage
                {
                    type = Wire.TypeError,
                    id = id,
                    reason = I18n.Instance.T("status.fileCannotSave", ex.Message)
                });
                Log?.Invoke("", "save-error: " + ex.Message);
            }
            finally
            {
                // Still in the map means this was the success path; CleanupIncoming already
                // removed it (and announced itself) on every abort route.
                if (_incomingFiles.TryRemove(id, out _)) IncomingEnded?.Invoke(id);
            }
        }

        /// <summary>
        /// Stops a receive this side no longer wants: the sender is told, the half file is
        /// deleted, and the progress bar is retired through [IncomingEnded].
        /// </summary>
        public void CancelIncoming(string id)
        {
            if (!_incomingFiles.TryGetValue(id, out var file)) return;

            file.Owner?.TrySend(new WireMessage
            {
                type = Wire.TypeError,
                id = id,
                reason = I18n.Instance.T("status.transferCancelled")
            });
            Log?.Invoke(file.Owner?.Name ?? "", "receive cancelled: " + file.FileName);
            LastTransferError = I18n.Instance.T("status.transferCancelled");
            CleanupIncoming(id);
        }

        private void CleanupIncoming(string id)
        {
            if (!_incomingFiles.TryRemove(id, out var file)) return;
            try { file.Stream?.Dispose(); } catch { }
            try { if (File.Exists(file.TempPath)) File.Delete(file.TempPath); } catch { }
            // Announced from here rather than at each call site: every abort route lands here,
            // and a route that forgot meant a progress bar stuck at its last percentage.
            IncomingEnded?.Invoke(id);
        }

        // ---------------------------------------------------------------- helpers

        /// <summary>
        /// Size limit for the next transfer. When adaptation is enabled a weak link
        /// (high latency / low measured throughput) shrinks the allowed size so the
        /// clipboard keeps feeling instant.
        /// </summary>
        public long EffectiveFileLimit()
        {
            // The user asked for the guard to be gone. Every caller compares a size against this
            // value, so lifting it here is enough for the picker, the queue and the receiver.
            if (_settings.IgnoreTransferSizeLimit) return long.MaxValue;

            var configured = Math.Max(1, _settings.MaxFileSizeBytes);
            if (!_settings.AdaptiveFileSizeLimit) return configured;

            var sessions = AllSessions.Where(s => s.IsOpen).ToList();
            if (sessions.Count == 0) return configured;

            double worstFactor = 1.0;
            foreach (var s in sessions)
            {
                double factor = 1.0;
                if (s.RttMs > 1200) factor = 0.2;
                else if (s.RttMs > 600) factor = 0.4;
                else if (s.RttMs > 300) factor = 0.7;

                if (s.Throughput > 0)
                {
                    if (s.Throughput < 60 * 1024) factor = Math.Min(factor, 0.3);
                    else if (s.Throughput < 200 * 1024) factor = Math.Min(factor, 0.6);
                }
                worstFactor = Math.Min(worstFactor, factor);
            }

            // never below 256 KiB so small screenshots still work
            return Math.Max(256L * 1024, (long)(configured * worstFactor));
        }

        public static string HumanSize(long bytes)
        {
            double value = Math.Max(0, bytes);
            string[] units = { "B", "KB", "MB", "GB" };
            int i = 0;
            while (value >= 1024 && i < units.Length - 1) { value /= 1024; i++; }
            return $"{value:0.#} {units[i]}";
        }

        public static string GuessMime(string fileName)        {
            var ext = Path.GetExtension(fileName).ToLowerInvariant();
            return ext switch
            {
                ".png" => "image/png",
                ".jpg" or ".jpeg" => "image/jpeg",
                ".gif" => "image/gif",
                ".webp" => "image/webp",
                ".pdf" => "application/pdf",
                ".txt" or ".md" => "text/plain",
                ".zip" => "application/zip",
                ".apk" => "application/vnd.android.package-archive",
                _ => "application/octet-stream"
            };
        }

        public static string SanitizeFileName(string name)
        {
            foreach (var c in Path.GetInvalidFileNameChars())
                name = name.Replace(c, '_');
            name = name.Trim();
            if (string.IsNullOrWhiteSpace(name)) name = "file.bin";
            if (name.Length > 120) name = name[..120];
            return name;
        }

        public static string UniquePath(string folder, string fileName)
        {
            var path = System.IO.Path.Combine(folder, fileName);
            if (IsFree(path)) return path;

            var stem = System.IO.Path.GetFileNameWithoutExtension(fileName);
            var ext = System.IO.Path.GetExtension(fileName);
            for (int i = 1; i < 1000; i++)
            {
                path = System.IO.Path.Combine(folder, $"{stem} ({i}){ext}");
                if (IsFree(path)) return path;
            }
            return System.IO.Path.Combine(folder, $"{stem}-{DateTime.Now:HHmmss}{ext}");
        }

        /// <summary>
        /// A name is only free when neither the final file nor its ".part" exists: a leftover
        /// part file is still held open by an aborted transfer, and reusing it made the next
        /// send of the same name fail with "being used by another process".
        /// </summary>
        private static bool IsFree(string path) =>
            !File.Exists(path) && !File.Exists(path + ".part");

        public void Dispose()
        {
            Stop();
            _cts?.Dispose();
        }
    }
}
