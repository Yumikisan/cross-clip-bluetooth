using System;
using System.Net.Sockets;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Turns raw Winsock / 32feet failures into something a human can act on.
    ///
    /// The one that bites most often is WSAENETDOWN (10050), which .NET renders as
    /// "套接字操作遇到了一个已死的网络" / "A socket operation encountered a dead network".
    /// With Bluetooth that almost always means the local radio is off, the adapter has
    /// not come back from sleep yet, or the Bluetooth Support Service isn't up — not
    /// that the phone is unreachable.
    /// </summary>
    public static class BluetoothError
    {
        private const int WsaNetDown = 10050;
        private const int WsaNetUnreachable = 10051;
        private const int WsaHostDown = 10064;
        private const int WsaHostUnreachable = 10065;
        private const int WsaAddrNotAvailable = 10049;
        private const int WsaTimedOut = 10060;

        private static int SocketCode(Exception ex) =>
            ex is SocketException se ? (int)se.SocketErrorCode : 0;

        /// <summary>Local Bluetooth stack is not usable right now (radio off / service not ready).</summary>
        public static bool IsRadioUnavailable(Exception ex)
        {
            var code = SocketCode(ex);
            return code == WsaNetDown || code == WsaNetUnreachable || code == WsaHostDown;
        }

        /// <summary>Local stack is fine but the peer could not be reached.</summary>
        public static bool IsPeerUnreachable(Exception ex)
        {
            var code = SocketCode(ex);
            return code == WsaAddrNotAvailable || code == WsaHostUnreachable || code == WsaTimedOut;
        }

        /// <summary>Localized, actionable message for the given failure.</summary>
        public static string Explain(Exception ex)
        {
            if (IsRadioUnavailable(ex))
                return I18n.Instance.T("status.errBtOff");

            if (ex is SocketException se)
            {
                return (int)se.SocketErrorCode switch
                {
                    WsaAddrNotAvailable => I18n.Instance.T("status.errNotInRange"),
                    WsaTimedOut => I18n.Instance.T("status.errTimeout"),
                    WsaHostUnreachable => I18n.Instance.T("status.errNotInRange"),
                    _ => se.Message
                };
            }

            return ex.Message;
        }
    }
}
