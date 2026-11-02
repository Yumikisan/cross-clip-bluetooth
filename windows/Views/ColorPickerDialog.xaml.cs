using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using ClipSyncWindows.Services;

namespace ClipSyncWindows.Views
{
    /// <summary>
    /// Small palette picker used by the per-type colour editor. WPF has no colour dialog, and
    /// pulling in System.Windows.Forms just for ColorDialog would drag a whole second UI stack
    /// into a single-file build, so this offers a fixed palette plus a hex field for anything else.
    /// </summary>
    public partial class ColorPickerDialog : Window
    {
        // 40 colours: a neutral ramp plus the hues the default type colours already use, so the
        // user can usually pick a sensible neighbour instead of typing a hex code.
        private static readonly string[] Palette =
        {
            "#3B82F6", "#2563EB", "#1D4ED8", "#6366F1", "#8B5CF6", "#A855F7", "#D946EF", "#EC4899",
            "#F43F5E", "#EF4444", "#DC2626", "#F97316", "#EA580C", "#F59E0B", "#D97706", "#EAB308",
            "#84CC16", "#65A30D", "#22C55E", "#16A34A", "#15803D", "#10B981", "#14B8A6", "#0D9488",
            "#06B6D4", "#0891B2", "#0E9AA7", "#0EA5E9", "#0284C7", "#475569", "#5B7C99", "#64748B",
            "#8D6E3A", "#A16207", "#C0269B", "#C0392B", "#3F51B5", "#94A3B8", "#334155", "#111827"
        };

        private Color _selected;
        private bool _updating;

        public ColorPickerDialog(Color initial, string title)
        {
            InitializeComponent();

            Title = title;
            Heading.Text = title;
            CancelButton.Content = I18n.Instance["btn.cancel"];
            OkButton.Content = I18n.Instance["btn.ok"];

            BuildSwatches();
            SetSelected(initial);
        }

        /// <summary>The colour the user confirmed.</summary>
        public Color SelectedColor => _selected;

        private void BuildSwatches()
        {
            foreach (var hex in Palette)
            {
                if (!HistoryAppearance.TryParseHex(hex, out var colour)) continue;

                var button = new Button
                {
                    Style = (Style)FindResource("SwatchButton"),
                    Background = new SolidColorBrush(colour),
                    Tag = colour
                };
                button.Click += Swatch_Click;
                Swatches.Children.Add(button);
            }
        }

        private void Swatch_Click(object sender, RoutedEventArgs e)
        {
            if (sender is Button { Tag: Color colour }) SetSelected(colour);
        }

        private void SetSelected(Color colour)
        {
            _selected = colour;

            _updating = true;
            HexBox.Text = HistoryAppearance.ToHex(colour).TrimStart('#');
            _updating = false;

            Preview.Background = new SolidColorBrush(colour);

            // Mark the matching swatch so the current choice is obvious.
            foreach (var child in Swatches.Children)
            {
                if (child is not Button button) continue;
                var isSelected = button.Tag is Color c && c == colour;
                button.BorderBrush = isSelected
                    ? ThemeManager.Instance.PrimaryText
                    : ThemeManager.Instance.BorderBrush;
                button.BorderThickness = new Thickness(isSelected ? 2 : 1);
            }
        }

        private void HexBox_TextChanged(object sender, TextChangedEventArgs e)
        {
            if (_updating) return;
            if (!HistoryAppearance.TryParseHex(HexBox.Text, out var colour)) return;

            _selected = colour;
            Preview.Background = new SolidColorBrush(colour);
        }

        private void Ok_Click(object sender, RoutedEventArgs e)
        {
            DialogResult = true;
        }

        private void Cancel_Click(object sender, RoutedEventArgs e)
        {
            DialogResult = false;
        }
    }
}
