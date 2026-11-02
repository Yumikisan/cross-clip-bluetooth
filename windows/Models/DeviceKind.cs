using InTheHand.Net.Bluetooth;
using InTheHand.Net.Sockets;
using System.Linq;

namespace ClipSyncWindows.Models
{
    /// <summary>What kind of thing sits on the other end of the link.</summary>
    public enum DeviceKind
    {
        Phone,
        Tablet,
        Computer,
        Audio,
        Other
    }

    /// <summary>
    /// Maps the Bluetooth Class of Device to a kind, and each kind to a hand-drawn outline glyph.
    /// The glyphs are plain geometry rather than a MDL2 lookup: icon font characters for things
    /// like "headset" are easy to get subtly wrong and fail as an empty box.
    /// </summary>
    public static class DeviceKinds
    {
        public static DeviceKind FromInfo(BluetoothDeviceInfo info)
        {
            try
            {
                // ClassOfDevice.MajorDevice hands back the refined DeviceClass enum (Smartphone,
                // LaptopComputer, TabletComputer, ...) rather than a bare major class, which is
                // exactly the granularity wanted here.
                return info.ClassOfDevice.MajorDevice switch
                {
                    DeviceClass.Smartphone or DeviceClass.CellPhone or DeviceClass.CordlessPhone
                        => DeviceKind.Phone,
                    DeviceClass.TabletComputer or DeviceClass.HandheldComputer or DeviceClass.PalmComputer
                        or DeviceClass.WearableComputer
                        => DeviceKind.Tablet,
                    DeviceClass.DesktopComputer or DeviceClass.LaptopComputer or DeviceClass.ServerComputer
                        or DeviceClass.Computer
                        => DeviceKind.Computer,
                    // Refined audio/video classes: several stacks report a bare "audio video"
                    // major for headsets, and Windows phones/tablets also often end up there.
                    DeviceClass.AudioVideoHeadset or DeviceClass.AudioVideoHeadphones
                        or DeviceClass.AudioVideoHandsFree or DeviceClass.AudioVideoLoudSpeaker
                        or DeviceClass.AudioVideoMicrophone or DeviceClass.AudioVideoCar
                        or DeviceClass.AudioVideoHiFi or DeviceClass.AudioVideoGaming
                        or DeviceClass.AudioVideoSetTopBox or DeviceClass.AudioVideoVcr
                        or DeviceClass.AudioVideoVideoCamera or DeviceClass.AudioVideoCamcorder
                        => DeviceKind.Audio,
                    DeviceClass.AudioVideoUnclassified or DeviceClass.Wearable or DeviceClass.Medical
                        => NameLooksLikePhone(info.DeviceName) ? DeviceKind.Phone : DeviceKind.Audio,
                    _ => DeviceKind.Other
                };
            }
            catch
            {
                return DeviceKind.Other;
            }
        }

        /// <summary>
        /// Phones whose CoD is missing or lumped into "audio video" (common with some Android
        /// stacks): their names almost always give them away.
        /// </summary>
        private static readonly string[] PhoneHints =
            { "phone", "iphone", "xiaomi", "redmi", "honor", "huawei", "galaxy", "pixel",
              "oppo", "vivo", "oneplus", "honor", "pad", "pro max" };

        private static readonly string[] AudioHints =
            { "airpods", "buds", "headset", "headphone", "earphone", "earbuds", "speaker",
              "soundbar", "wh-", "wf-", "soundcore", "jbl", "bose", "marshall", "freebuds" };

        private static readonly string[] InputHints =
            { "keyboard", "keychron", "mouse", "mx keys", "mx master", "k380", "k480",
              "signature", "trackpad" };

        private static string LowerName(string? name) => (name ?? "").ToLowerInvariant();

        private static bool NameLooksLikePhone(string? name) =>
            PhoneHints.Any(h => LowerName(name).Contains(h));

        /// <summary>Audio devices, keyboards and mice never run this app: candidates for the
        /// "auto hide peripherals" setting. Inferred from the CoD first, name as fallback.</summary>
        public static bool LooksLikePeripheral(BluetoothDeviceInfo info)
        {
            try
            {
                var byClass = info.ClassOfDevice.MajorDevice switch
                {
                    DeviceClass.AudioVideoHeadset or DeviceClass.AudioVideoHeadphones
                        or DeviceClass.AudioVideoHandsFree or DeviceClass.AudioVideoLoudSpeaker
                        or DeviceClass.AudioVideoMicrophone or DeviceClass.AudioVideoCar
                        or DeviceClass.AudioVideoHiFi
                        or DeviceClass.PeripheralKeyboard or DeviceClass.PeripheralPointingDevice
                        or DeviceClass.PeripheralCombinedKeyboardPointingDevice
                        or DeviceClass.PeripheralGamepad or DeviceClass.PeripheralJoystick
                        or DeviceClass.PeripheralRemoteControl
                        => true,
                    _ => false
                };
                if (byClass) return true;

                var name = LowerName(info.DeviceName);
                return AudioHints.Any(h => name.Contains(h)) || InputHints.Any(h => name.Contains(h));
            }
            catch
            {
                return false;
            }
        }

        /// <summary>16×16 outline geometry; drawn with a stroke, not filled.</summary>
        public static string GlyphPath(DeviceKind kind) => kind switch
        {
            // handset with a home dot
            DeviceKind.Phone =>
                "M5.6,1.5 H10.4 A0.9,0.9 0 0 1 11.3,2.4 V13.6 A0.9,0.9 0 0 1 10.4,14.5 " +
                "H5.6 A0.9,0.9 0 0 1 4.7,13.6 V2.4 A0.9,0.9 0 0 1 5.6,1.5 Z " +
                "M7.2,12.2 A0.8,0.8 0 1 0 8.8,12.2 A0.8,0.8 0 1 0 7.2,12.2",

            // wider handset: same idea, clearly a tablet at a glance
            DeviceKind.Tablet =>
                "M3.6,1.5 H12.4 A0.9,0.9 0 0 1 13.3,2.4 V13.6 A0.9,0.9 0 0 1 12.4,14.5 " +
                "H3.6 A0.9,0.9 0 0 1 2.7,13.6 V2.4 A0.9,0.9 0 0 1 3.6,1.5 Z " +
                "M6.9,12.3 A0.7,0.7 0 1 0 8.3,12.3 A0.7,0.7 0 1 0 6.9,12.3",

            // monitor on a stand
            DeviceKind.Computer =>
                "M1.5,2.5 H14.5 V10.5 H1.5 Z M8,10.5 V13.3 M4.5,13.3 H11.5",

            // headband with two earcups (stands in for any audio device)
            DeviceKind.Audio =>
                "M2.5,9.5 V8 A5.5,5.5 0 0 1 13.5,8 V9.5 " +
                "M2.5,9.5 H4.6 V13 H3.4 A0.9,0.9 0 0 1 2.5,12.1 Z " +
                "M13.5,9.5 H11.4 V13 H12.6 A0.9,0.9 0 0 0 13.5,12.1 Z",

            // plain circle: the row draws a "?" on top of it
            _ => "M8,1.6 A6.4,6.4 0 1 1 7.99,1.6 Z"
        };
    }
}
