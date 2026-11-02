using System;
using System.ComponentModel;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Live view options for the history list. The list template binds to this singleton so
    /// changing the layout or the line count updates every row without rebuilding the items.
    /// Persisted values live in <see cref="Models.AppSettings"/>; this is the runtime mirror.
    /// </summary>
    public sealed class HistoryViewOptions : INotifyPropertyChanged
    {
        public static HistoryViewOptions Instance { get; } = new HistoryViewOptions();

        public const double LineHeight = 18.0;

        private bool _cardMode;
        private int _lines = 2;
        private double _fontScale = 1.0;
        private bool _showSourceDevice = true;
        private bool _thumbnails = true;

        /// <summary>False = one entry per row, true = two columns of fixed-width cards.</summary>
        public bool CardMode
        {
            get => _cardMode;
            set
            {
                if (_cardMode == value) return;
                _cardMode = value;
                Raise(nameof(CardMode));
            }
        }

        /// <summary>Visible preview lines per entry (1-8).</summary>
        public int Lines
        {
            get => _lines;
            set
            {
                var v = Math.Clamp(value, 1, 8);
                if (_lines == v) return;
                _lines = v;
                Raise(nameof(Lines));
                Raise(nameof(PreviewMaxHeight));
                Raise(nameof(PreviewLineHeight));
            }
        }

        /// <summary>
        /// Text size multiplier. Line height has to follow it, otherwise a larger font would be
        /// clipped by a max height computed for 13px text.
        /// </summary>
        public double FontScale
        {
            get => _fontScale;
            set
            {
                var v = value switch { <= 0.95 => 0.9, >= 1.1 => 1.15, _ => 1.0 };
                if (Math.Abs(_fontScale - v) < 0.001) return;
                _fontScale = v;
                Raise(nameof(FontScale));
                Raise(nameof(PreviewLineHeight));
                Raise(nameof(PreviewMaxHeight));
            }
        }

        /// <summary>Whether the source device name is shown under each entry.</summary>
        public bool ShowSourceDevice
        {
            get => _showSourceDevice;
            set
            {
                if (_showSourceDevice == value) return;
                _showSourceDevice = value;
                Raise(nameof(ShowSourceDevice));
            }
        }

        /// <summary>
        /// Live copy of the "image thumbnails" switch. Item templates bind their visibility to it
        /// so flipping the option does not need the list to be rebuilt.
        /// </summary>
        public bool Thumbnails
        {
            get => _thumbnails;
            set
            {
                if (_thumbnails == value) return;
                _thumbnails = value;
                Raise(nameof(Thumbnails));
            }
        }

        public double PreviewLineHeight => Math.Round(LineHeight * _fontScale, 1);

        /// <summary>Caps the wrapped preview to exactly <see cref="Lines"/> visible rows.</summary>
        public double PreviewMaxHeight => Math.Round(Lines * PreviewLineHeight, 1);

        /// <summary>Reloads from settings (called once at startup and after the settings page).</summary>
        public void LoadFrom(Models.AppSettings settings)
        {
            CardMode = settings.HistoryCardMode;
            Lines = settings.HistoryLines;
            FontScale = settings.HistoryFontScale;
            ShowSourceDevice = settings.ShowSourceDevice;
            Thumbnails = settings.HistoryThumbnails;
        }

        public void SaveTo(Models.AppSettings settings)
        {
            settings.HistoryCardMode = CardMode;
            settings.HistoryLines = Lines;
        }

        public event PropertyChangedEventHandler? PropertyChanged;

        private void Raise(string name) => PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}
