using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using ClipSyncWindows.Models;
using ClipSyncWindows.Services;
using ClipSyncWindows.ViewModels;

namespace ClipSyncWindows.Views
{
    /// <summary>
    /// Per-type colour editor. Lists every text format and file kind the identifier bar knows
    /// about, so "custom" is not a single colour but thirteen independent choices.
    /// </summary>
    public partial class AccentColorsDialog : Window
    {
        private readonly SettingsViewModel _settings;
        private readonly ObservableCollection<AccentRow> _rows = new();

        public AccentColorsDialog(SettingsViewModel settings)
        {
            InitializeComponent();

            _settings = settings;
            Title = I18n.Instance["lbl.accentCustomTitle"];
            Heading.Text = I18n.Instance["lbl.accentCustomTitle"];
            Hint.Text = I18n.Instance["lbl.accentCustomHint"];
            ResetButton.Content = I18n.Instance["lbl.resetDefaults"];
            CloseButton.Content = I18n.Instance["btn.ok"];

            BuildRows();
            TypeList.ItemsSource = _rows;
        }

        private void BuildRows()
        {
            foreach (var key in EntryFormat.AllKeys())
            {
                _rows.Add(new AccentRow(
                    key,
                    I18n.Instance[EntryFormat.LabelKeyFor(key)],
                    _settings.AccentColorOf(key)));
            }
        }

        private void Swatch_Click(object sender, RoutedEventArgs e)
        {
            if (sender is not Button { Tag: AccentRow row }) return;

            var picker = new ColorPickerDialog(row.Color, I18n.Instance["lbl.pickColor"])
            {
                Owner = this
            };

            if (picker.ShowDialog() != true) return;

            row.SetColor(picker.SelectedColor);
            _settings.SetAccentColor(row.Key, picker.SelectedColor);
        }

        private void Reset_Click(object sender, RoutedEventArgs e)
        {
            _settings.ResetAccentColors();
            foreach (var row in _rows)
                row.SetColor(_settings.AccentColorOf(row.Key));
        }

        private void Close_Click(object sender, RoutedEventArgs e) => Close();

        /// <summary>One editable row: type name, current colour and its hex.</summary>
        private sealed class AccentRow : INotifyPropertyChanged
        {
            public AccentRow(string key, string label, Color colour)
            {
                Key = key;
                Label = label;
                SetColor(colour, raise: false);
            }

            public string Key { get; }

            public string Label { get; }

            public Color Color { get; private set; }

            public Brush Swatch { get; private set; } = Brushes.Gray;

            public string Hex { get; private set; } = "";

            public void SetColor(Color colour, bool raise = true)
            {
                Color = colour;
                var brush = new SolidColorBrush(colour);
                brush.Freeze();
                Swatch = brush;
                Hex = HistoryAppearance.ToHex(colour);

                if (!raise) return;
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Swatch)));
                PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(Hex)));
            }

            public event PropertyChangedEventHandler? PropertyChanged;
        }
    }
}
