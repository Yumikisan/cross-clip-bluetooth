using System;
using System.ComponentModel;
using InTheHand.Net;
using InTheHand.Net.Sockets;

namespace ClipSyncWindows.Models
{
    /// <summary>A paired Bluetooth device plus its live link state and per-device preferences.</summary>
    public class DeviceViewModel : INotifyPropertyChanged
    {
        private readonly DevicePrefs _prefs;
        private bool _isConnected;
        private bool _isSelected;

        public DeviceViewModel(BluetoothAddress address, string name, bool authenticated,
            DeviceKind kind, DevicePrefs prefs)
        {
            Address = address;
            Name = string.IsNullOrWhiteSpace(name) ? address.ToString() : name;
            IsAuthenticated = authenticated;
            _inferredKind = kind;
            _prefs = prefs;
        }

        public static DeviceViewModel FromInfo(BluetoothDeviceInfo info, DevicePrefs prefs) =>
            new(info.DeviceAddress, info.DeviceName ?? "", info.Authenticated,
                DeviceKinds.FromInfo(info), prefs);

        public BluetoothAddress Address { get; }

        /// <summary>Raw 12-hex form -- the key used everywhere (settings, link manager).</summary>
        public string Key => Address.ToString();

        public string AddressText => Address.ToString("C");

        public string Name { get; }

        public bool IsAuthenticated { get; }

        /// <summary>Inferred kind (Class of Device + name); <see cref="CustomKind"/> wins when set.</summary>
        private readonly DeviceKind _inferredKind;

        public DeviceKind Kind => CustomKind switch
        {
            "Phone" => DeviceKind.Phone,
            "Tablet" => DeviceKind.Tablet,
            "Computer" => DeviceKind.Computer,
            "Audio" => DeviceKind.Audio,
            "Other" => DeviceKind.Other,
            _ => _inferredKind
        };

        /// <summary>The user's type override; empty string = infer automatically.</summary>
        public string CustomKind
        {
            get => _prefs.CustomKind;
            set
            {
                if (_prefs.CustomKind == value) return;
                _prefs.CustomKind = value;
                Notify(nameof(CustomKind), nameof(Kind), nameof(KindText), nameof(GlyphPath),
                    nameof(IsUnknownKind), nameof(MayNotSupportSync));
                Services.SettingsService.Save();
            }
        }

        public string KindText => Kind switch
        {
            DeviceKind.Phone => Services.I18n.Instance.T("devkind.phone"),
            DeviceKind.Tablet => Services.I18n.Instance.T("devkind.tablet"),
            DeviceKind.Computer => Services.I18n.Instance.T("devkind.computer"),
            DeviceKind.Audio => Services.I18n.Instance.T("devkind.audio"),
            _ => Services.I18n.Instance.T("devkind.other")
        };

        /// <summary>16×16 outline geometry for the type badge.</summary>
        public string GlyphPath => DeviceKinds.GlyphPath(Kind);

        /// <summary>
        /// Set once at list load: the CoD / name says "audio device, keyboard or mouse". Used
        /// by the auto-hide-peripherals setting; a CustomKind override always wins.
        /// </summary>
        public bool InferredPeripheral { get; set; }

        /// <summary>Headsets and unknown peripherals almost never run this app: flag them.</summary>
        public bool MayNotSupportSync => Kind is DeviceKind.Audio or DeviceKind.Other;

        public bool IsUnknownKind => Kind == DeviceKind.Other;

        // ---------------------------------------------------------------- preferences

        public bool AlwaysTrusted
        {
            get => _prefs.AlwaysTrusted;
            set
            {
                if (_prefs.AlwaysTrusted == value) return;
                _prefs.AlwaysTrusted = value;
                Notify(nameof(AlwaysTrusted));
                Services.SettingsService.Save();
                GroupingChanged?.Invoke(this);
            }
        }

        public bool AutoReconnect
        {
            get => _prefs.AutoReconnect;
            set
            {
                if (_prefs.AutoReconnect == value) return;
                _prefs.AutoReconnect = value;
                Notify(nameof(AutoReconnect));
                Services.SettingsService.Save();
            }
        }

        public bool Hidden
        {
            get => _prefs.Hidden;
            set
            {
                if (_prefs.Hidden == value) return;
                _prefs.Hidden = value;
                Notify(nameof(Hidden));
                Services.SettingsService.Save();
                GroupingChanged?.Invoke(this);
            }
        }

        public bool FileSyncEnabled
        {
            get => _prefs.FileSyncEnabled;
            set
            {
                if (_prefs.FileSyncEnabled == value) return;
                _prefs.FileSyncEnabled = value;
                Notify(nameof(FileSyncEnabled));
                Services.SettingsService.Save();
            }
        }

        /// <summary>Raised when the row has to move between the visible / trusted / hidden groups.</summary>
        public event Action<DeviceViewModel>? GroupingChanged;

        // ---------------------------------------------------------------- live state

        public bool IsSelected
        {
            get => _isSelected;
            set
            {
                if (_isSelected == value) return;
                _isSelected = value;
                Notify(nameof(IsSelected));
                SelectionChanged?.Invoke(this);
            }
        }

        public event Action<DeviceViewModel>? SelectionChanged;

        public bool IsConnected
        {
            get => _isConnected;
            set
            {
                if (_isConnected == value) return;
                _isConnected = value;
                Notify(nameof(IsConnected), nameof(StateText));
            }
        }

        private bool _isEncrypted;

        /// <summary>Layer-2 encryption is active on the live link to this device.</summary>
        public bool IsEncrypted
        {
            get => _isEncrypted;
            set
            {
                if (_isEncrypted == value) return;
                _isEncrypted = value;
                Notify(nameof(IsEncrypted), nameof(StateText));
            }
        }

        /// <summary>
        /// Why the last attempt to reach this device was refused by the security layer, already
        /// localised. Empty when there is nothing to report. Shown instead of "not connected",
        /// because "not connected" is what made a key mismatch look like a dead radio.
        /// </summary>
        public string SecurityError
        {
            get => _securityError;
            set
            {
                if (_securityError == value) return;
                _securityError = value ?? "";
                Notify(nameof(SecurityError), nameof(HasSecurityError), nameof(StateText));
            }
        }
        private string _securityError = "";

        public bool HasSecurityError => _securityError.Length > 0;

        public string StateText
        {
            get
            {
                if (IsConnected)
                {
                    return IsEncrypted
                        ? Services.I18n.Instance.T("lbl.connectedEncrypted")
                        : Services.I18n.Instance.T("lbl.connected");
                }
                return HasSecurityError ? _securityError : Services.I18n.Instance.T("lbl.disconnected");
            }
        }

        public string Initial => string.IsNullOrEmpty(Name) ? "?" : Name.Substring(0, 1).ToUpperInvariant();

        /// <summary>Re-reads every localised string after a language switch.</summary>
        public void RefreshTexts() =>
            Notify(nameof(KindText), nameof(StateText));

        public event PropertyChangedEventHandler? PropertyChanged;

        private void Notify(params string[] names)
        {
            foreach (var name in names)
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
        }
    }
}
