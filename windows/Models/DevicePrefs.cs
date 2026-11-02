using System.Collections.Generic;
using System.Linq;

namespace ClipSyncWindows.Models
{
    /// <summary>
    /// Per-device preferences, keyed by the device address in the same 12-hex-digit form the
    /// link manager uses. Nothing here is derived from the live link -- it survives restarts,
    /// re-pairing and the device being out of range for weeks.
    /// </summary>
    public class DevicePrefs
    {
        public string Address { get; set; } = "";

        public string Name { get; set; } = "";

        /// <summary>Skip the per-connection confirmation prompt (still authenticated via SAS).</summary>
        public bool AlwaysTrusted { get; set; }

        /// <summary>Reconnect automatically when the app starts, without the user re-picking it.</summary>
        public bool AutoReconnect { get; set; } = true;

        /// <summary>Hide from the main device list; the device keeps working if it is selected.</summary>
        public bool Hidden { get; set; }

        /// <summary>Accept/send file payloads with this device (mirrors the global switch).</summary>
        public bool FileSyncEnabled { get; set; } = true;

        /// <summary>Last time this device was seen (ISO 8601), for sorting.</summary>
        public string LastSeen { get; set; } = "";

        /// <summary>
        /// User-selected type ("Phone", "Tablet", "Computer", "Audio", "Other"); empty means
        /// "infer from the Class of Device and the name". Beats the OS misreporting a phone
        /// as "other device" — the override wins everywhere the kind is shown.
        /// </summary>
        public string CustomKind { get; set; } = "";
    }

    /// <summary>Lookup helpers over the persisted list; keeps every caller on the same key format.</summary>
    public class DevicePrefsStore
    {
        private readonly List<DevicePrefs> _items;

        public DevicePrefsStore(List<DevicePrefs> items) => _items = items;

        public IReadOnlyList<DevicePrefs> All => _items;

        public DevicePrefs Get(string address)
        {
            var found = _items.FirstOrDefault(p => p.Address == address);
            if (found != null) return found;

            found = new DevicePrefs { Address = address };
            _items.Add(found);
            return found;
        }

        public DevicePrefs? Find(string address) => _items.FirstOrDefault(p => p.Address == address);

        public void Remove(string address) =>
            _items.RemoveAll(p => p.Address == address);
    }
}
