using System;
using System.IO;

namespace ClipSyncWindows.Security
{
    /// <summary>
    /// The only thing the secure layer knows about the outside world: a reliable, ordered byte
    /// stream. Everything above this interface (framing, handshake, crypto, replay window) is
    /// transport agnostic on purpose — swapping RFCOMM for a future BR/EDR high-speed channel or
    /// a BLE L2CAP/CoC link means writing one new adapter, not touching the security code.
    /// </summary>
    public interface ISecureTransport
    {
        /// <summary>Blocking read; returns bytes read, 0 or -1 when the peer closed.</summary>
        int Read(byte[] buffer, int offset, int count);

        /// <summary>Writes every byte or throws.</summary>
        void Write(byte[] data, int offset, int count);

        void Close();

        bool IsOpen { get; }

        /// <summary>For logs only. Never used as an identity.</summary>
        string PeerLabel { get; }
    }

    /// <summary>Adapter for Bluetooth Classic RFCOMM / SPP (the current transport).</summary>
    public sealed class RfcommTransport : ISecureTransport
    {
        private readonly Stream _stream;
        private readonly object _gate = new();
        private volatile bool _open = true;

        public RfcommTransport(Stream stream, string peerLabel)
        {
            _stream = stream;
            PeerLabel = peerLabel;
        }

        public string PeerLabel { get; }

        public bool IsOpen => _open;

        public int Read(byte[] buffer, int offset, int count)
        {
            try
            {
                var read = _stream.Read(buffer, offset, count);
                return read;
            }
            catch (Exception)
            {
                _open = false;
                return -1;
            }
        }

        public void Write(byte[] data, int offset, int count)
        {
            // Several RFCOMM stacks stall or silently truncate multi-kilobyte single writes;
            // 4 KiB slices are what the original line protocol used and they never failed.
            lock (_gate)
            {
                int written = 0;
                while (written < count)
                {
                    int slice = Math.Min(4096, count - written);
                    _stream.Write(data, offset + written, slice);
                    written += slice;
                }
                _stream.Flush();
            }
        }

        public void Close()
        {
            if (!_open) return;
            _open = false;
            try { _stream.Dispose(); } catch { }
        }
    }
}
