using System;
using System.Collections.Generic;
using ClipSyncWindows.Services;

namespace ClipSyncWindows.Services.Transport
{
    /// <summary>
    /// Transport contract — the seam between "how the bytes travel" and everything above it.
    ///
    /// The clipboard, file-queue and folder (BFS) logic never touch a socket or a TCP stream:
    /// they only ever see an <see cref="IPeerLink"/>. Adding a backend (BR/EDR high speed, LAN
    /// TCP, BLE L2CAP) means writing one <see cref="ITransportConnector"/> and registering it;
    /// no business code changes.
    ///
    /// Layering, from the wire up:
    ///   socket / stream → ITransportLink (bytes) → SecureChannel (framing, PSK, AES-GCM, replay)
    ///   → Wire (newline JSON) → clipboard / files / folders
    ///
    /// The PSK layer sits above this contract on purpose: it must protect every backend the same
    /// way, including ones whose link layer is already encrypted.
    /// </summary>
    public enum TransportKind
    {
        /// <summary>Bluetooth Classic / SPP over RFCOMM. The only backend wired today.</summary>
        Rfcomm,

        /// <summary>Reserved: same LAN over TCP, no pairing required.</summary>
        LanTcp,

        /// <summary>Reserved: BLE L2CAP Connection-oriented Channel for LE-only devices.</summary>
        BleL2cap,

        /// <summary>Reserved: BR/EDR high-speed (802.11 AMP style) channel.</summary>
        Hdt,
    }

    /// <summary>Stable ids used in settings, logs and the diagnostics page.</summary>
    public static class TransportKindIds
    {
        public static string Id(this TransportKind kind) => kind switch
        {
            TransportKind.Rfcomm => "rfcomm",
            TransportKind.LanTcp => "lan-tcp",
            TransportKind.BleL2cap => "ble-l2cap",
            TransportKind.Hdt => "hdt",
            _ => "unknown",
        };

        public static TransportKind? FromId(string? id) => id switch
        {
            "rfcomm" => TransportKind.Rfcomm,
            "lan-tcp" => TransportKind.LanTcp,
            "ble-l2cap" => TransportKind.BleL2cap,
            "hdt" => TransportKind.Hdt,
            _ => null,
        };
    }

    /// <summary>
    /// What a backend can do. Business code may consult this to pick a path or to explain itself
    /// in the UI, but must not assume anything beyond these values.
    /// </summary>
    public sealed record TransportCapabilities(
        TransportKind Kind,
        int MaxWriteBytes = 4096,
        bool CanConnect = true,
        bool CanListen = true,
        bool LinkEncryption = false,
        long EstimatedBytesPerSecond = 0,
        bool SupportsDiscovery = true)
    {
        /// <summary>Values for Bluetooth Classic SPP; also the fallback for unknown backends.</summary>
        public static TransportCapabilities Rfcomm() => new(
            Kind: TransportKind.Rfcomm,
            MaxWriteBytes: 4096,
            LinkEncryption: true,
            EstimatedBytesPerSecond: 90_000,
            SupportsDiscovery: true);
    }

    /// <summary>
    /// Who is on the other end. <see cref="Id"/> is the only identity business logic may use:
    /// the BT address for RFCOMM, "host:port" for LAN, stable across reconnects.
    /// </summary>
    public sealed record TransportPeer(
        string Id,
        string DisplayName,
        TransportKind Kind,
        bool Incoming = false);

    /// <summary>
    /// The single surface business logic is allowed to use: send one protocol line, close, read
    /// link state. Sockets, SDP, reconnect policy and arbitration stay inside the connector and
    /// the manager that owns them.
    /// </summary>
    public interface IPeerLink
    {
        TransportPeer Peer { get; }
        TransportCapabilities Capabilities { get; }
        bool IsOpen { get; }
        bool IsAuthenticated { get; }
        bool IsEncrypted { get; }
        bool IsLegacyPeer { get; }
        long RttMs { get; }
        DateTime LastActivity { get; }
        string SecurityFailure { get; }
        /// <summary>
        /// Hands one protocol frame to the peer. False means the link is gone; callers treat it
        /// exactly like a failed socket write. The frame type is the transport-agnostic Wire
        /// message, not a raw byte buffer: framing stays one layer down.
        /// </summary>
        bool SendFrame(WireMessage message);
        void Close();
    }

    /// <summary>
    /// Opens links of one <see cref="TransportKind"/>. Registered in a
    /// <see cref="TransportRegistry"/> so callers stay ignorant of which backend serves a peer.
    /// </summary>
    public interface ITransportConnector
    {
        TransportKind Kind { get; }
        TransportCapabilities Capabilities { get; }
        bool CanHandle(TransportPeer peer);
        bool IsAvailable();
        System.Threading.Tasks.Task<IPeerLink?> ConnectAsync(TransportPeer peer, int timeoutMs, System.Threading.CancellationToken token = default);
        string? LastError { get; }
    }

    /// <summary>
    /// Protocol-level guard for future backends: a LAN peer must announce the same contract
    /// version, otherwise its handshake is refused instead of misparsed.
    /// </summary>
    public static class TransportContract
    {
        /// <summary>Bumped only when <see cref="IPeerLink"/> itself changes shape.</summary>
        public const int Version = 1;

        /// <summary>Backends that are actually implemented.</summary>
        public static readonly IReadOnlyList<TransportKind> Implemented = new[] { TransportKind.Rfcomm };

        /// <summary>
        /// Preference order when a peer is reachable over several backends: fastest first, RFCOMM
        /// last because it is the slowest and the only one that needs pairing.
        /// </summary>
        public static readonly IReadOnlyList<TransportKind> Preference = new[]
        {
            TransportKind.Hdt,
            TransportKind.LanTcp,
            TransportKind.BleL2cap,
            TransportKind.Rfcomm,
        };
    }
}
