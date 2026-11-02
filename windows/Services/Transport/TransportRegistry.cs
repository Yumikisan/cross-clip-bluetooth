using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;

namespace ClipSyncWindows.Services.Transport
{
    /// <summary>
    /// Holds every backend the app knows about and answers "who can carry this peer?".
    ///
    /// Today exactly one connector is registered (RFCOMM). The rest of the app asks the registry
    /// instead of naming a backend, so a LAN or HDT connector is an addition, not a refactor.
    /// </summary>
    public sealed class TransportRegistry
    {
        private readonly List<ITransportConnector> _connectors = new();
        private readonly object _gate = new();

        public IReadOnlyList<ITransportConnector> Connectors
        {
            get { lock (_gate) return _connectors.ToList(); }
        }

        public void Register(ITransportConnector connector)
        {
            lock (_gate)
            {
                _connectors.RemoveAll(c => c.Kind == connector.Kind);
                _connectors.Add(connector);
            }
        }

        public ITransportConnector? ConnectorFor(TransportPeer peer) => Candidates(peer).FirstOrDefault();

        /// <summary>
        /// Backends that could serve <paramref name="peer"/>, best first. Unavailable ones are
        /// filtered out so a caller never tries a radio that is switched off.
        /// </summary>
        public IReadOnlyList<ITransportConnector> Candidates(TransportPeer peer)
        {
            return Connectors
                .Where(c => c.Kind == peer.Kind && c.CanHandle(peer) && c.IsAvailable())
                .OrderBy(c =>
                {
                    var index = TransportContract.Preference.ToList().IndexOf(c.Kind);
                    return index < 0 ? 99 : index;
                })
                .ToList();
        }

        /// <summary>Kinds this build can really carry traffic over.</summary>
        public IReadOnlyList<TransportKind> AvailableKinds() =>
            Connectors.Where(c => c.IsAvailable()).Select(c => c.Kind).ToList();

        /// <summary>
        /// Reserved-backend guard: a settings page must not offer a transport that has no
        /// connector, otherwise the toggle exists and does nothing.
        /// </summary>
        public IReadOnlyList<TransportKind> ImplementedKinds() =>
            Connectors.Where(c => TransportContract.Implemented.Contains(c.Kind))
                      .Select(c => c.Kind).ToList();
    }

    /// <summary>
    /// RFCOMM / SPP connector.
    ///
    /// Socket handling stays in <see cref="BluetoothLinkManager"/> (it owns pairing, arbitration
    /// and reconnect back-off); this adapter is only the contract face of it, so registering it
    /// changes no runtime behaviour.
    /// </summary>
    public sealed class RfcommConnector : ITransportConnector
    {
        private readonly Func<bool> _available;
        private readonly Func<TransportPeer, int, CancellationToken, Task<IPeerLink?>> _connect;

        public RfcommConnector(
            Func<bool> available,
            Func<TransportPeer, int, CancellationToken, Task<IPeerLink?>> connect)
        {
            _available = available;
            _connect = connect;
        }

        public TransportKind Kind => TransportKind.Rfcomm;

        public TransportCapabilities Capabilities => TransportCapabilities.Rfcomm();

        public string? LastError { get; private set; }

        public bool CanHandle(TransportPeer peer) =>
            peer.Kind == TransportKind.Rfcomm && !string.IsNullOrWhiteSpace(peer.Id);

        public bool IsAvailable()
        {
            try { return _available(); }
            catch { return false; }
        }

        public async Task<IPeerLink?> ConnectAsync(
            TransportPeer peer, int timeoutMs, CancellationToken token = default)
        {
            var link = await _connect(peer, timeoutMs, token).ConfigureAwait(false);
            LastError = string.IsNullOrWhiteSpace(link?.SecurityFailure) ? null : link!.SecurityFailure;
            return link;
        }
    }
}
