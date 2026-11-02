using System;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Input;
using System.Windows.Interop;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// System-wide hotkey (RegisterHotKey) that brings the window to the front even when the
    /// app sits in the tray. Only one hotkey is registered at a time: re-registering replaces
    /// the previous one, so changing it in the settings page always leaves exactly one alive.
    /// </summary>
    public sealed class HotkeyService : IDisposable
    {
        private const int WmHotkey = 0x0312;
        private const int HotkeyId = 0x4C53; // "CS"
        private const uint ModNoRepeat = 0x4000;

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool RegisterHotKey(IntPtr hWnd, int id, uint fsModifiers, uint vk);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool UnregisterHotKey(IntPtr hWnd, int id);

        private IntPtr _hwnd;
        private HwndSource? _source;

        /// <summary>Raised on the UI thread when the hotkey fires.</summary>
        public event Action? Pressed;

        public bool IsRegistered { get; private set; }

        /// <summary>Human readable form of what is currently registered ("Ctrl+Alt+C").</summary>
        public string CurrentGesture { get; private set; } = "";

        public void Attach(Window window)
        {
            var helper = new WindowInteropHelper(window);
            _hwnd = helper.EnsureHandle();
            _source = HwndSource.FromHwnd(_hwnd);
            _source?.AddHook(WndProc);
        }

        /// <summary>
        /// Parses and registers "<c>Ctrl+Alt+C</c>" style gestures. Returns false when the
        /// gesture is unparsable or already owned by another application.
        /// </summary>
        public bool Register(string gesture)
        {
            Unregister();

            if (!TryParse(gesture, out var modifiers, out var key)) return false;
            if (_hwnd == IntPtr.Zero) return false;

            var ok = RegisterHotKey(_hwnd, HotkeyId, modifiers | ModNoRepeat,
                (uint)KeyInterop.VirtualKeyFromKey(key));
            if (ok)
            {
                IsRegistered = true;
                CurrentGesture = Format(modifiers, key);
            }
            return ok;
        }

        public void Unregister()
        {
            if (!IsRegistered) return;
            try { UnregisterHotKey(_hwnd, HotkeyId); } catch { }
            IsRegistered = false;
        }

        private IntPtr WndProc(IntPtr hwnd, int msg, IntPtr wParam, IntPtr lParam, ref bool handled)
        {
            if (msg == WmHotkey && wParam.ToInt32() == HotkeyId)
            {
                Pressed?.Invoke();
                handled = true;
            }
            return IntPtr.Zero;
        }

        // ---------------------------------------------------------------- gesture parsing

        public static bool TryParse(string? gesture, out uint modifiers, out Key key)
        {
            modifiers = 0;
            key = Key.None;
            if (string.IsNullOrWhiteSpace(gesture)) return false;

            var parts = gesture.Split('+', StringSplitOptions.RemoveEmptyEntries);
            if (parts.Length == 0) return false;

            foreach (var raw in parts)
            {
                var part = raw.Trim();
                switch (part.ToLowerInvariant())
                {
                    case "ctrl":
                    case "control":
                        modifiers |= 0x0002;
                        break;
                    case "alt":
                        modifiers |= 0x0001;
                        break;
                    case "shift":
                        modifiers |= 0x0004;
                        break;
                    case "win":
                        modifiers |= 0x0008;
                        break;
                    default:
                        if (!Enum.TryParse<Key>(part, true, out var parsed)) return false;
                        key = parsed;
                        break;
                }
            }

            // A modifier-only chord would swallow the whole keyboard.
            return key != Key.None && modifiers != 0;
        }

        public static string Format(uint modifiers, Key key)
        {
            var text = "";
            if ((modifiers & 0x0002) != 0) text += "Ctrl+";
            if ((modifiers & 0x0001) != 0) text += "Alt+";
            if ((modifiers & 0x0004) != 0) text += "Shift+";
            if ((modifiers & 0x0008) != 0) text += "Win+";
            return text + KeyName(key);
        }

        /// <summary>Formats a captured key event as a gesture string.</summary>
        public static string? FromKeyEvent(KeyEventArgs e)
        {
            var key = e.Key == Key.System ? e.SystemKey : e.Key;

            // Ignore bare modifier presses: the user is still building the chord.
            if (key is Key.LeftCtrl or Key.RightCtrl or Key.LeftAlt or Key.RightAlt
                or Key.LeftShift or Key.RightShift or Key.LWin or Key.RWin)
                return null;

            var modifiers = Keyboard.Modifiers;
            if (modifiers == ModifierKeys.None) return null;

            var text = "";
            if (modifiers.HasFlag(ModifierKeys.Control)) text += "Ctrl+";
            if (modifiers.HasFlag(ModifierKeys.Alt)) text += "Alt+";
            if (modifiers.HasFlag(ModifierKeys.Shift)) text += "Shift+";
            if (modifiers.HasFlag(ModifierKeys.Windows)) text += "Win+";
            return text + KeyName(key);
        }

        private static string KeyName(Key key) => key switch
        {
            Key.Oem3 => "`",
            Key.OemMinus => "-",
            Key.OemPlus => "=",
            Key.OemOpenBrackets => "[",
            Key.Oem6 => "]",
            Key.Oem5 => "\\",
            Key.Oem1 => ";",
            Key.OemQuotes => "'",
            Key.OemComma => ",",
            Key.OemPeriod => ".",
            Key.OemQuestion => "/",
            _ => key.ToString()
        };

        public void Dispose()
        {
            Unregister();
            _source?.RemoveHook(WndProc);
            _source = null;
        }
    }
}
