using System;
using System.Windows;
using System.Windows.Media;
using ClipSyncWindows.Models;

namespace ClipSyncWindows.Services
{
    /// <summary>
    /// Turns the personalisation settings into the concrete numbers the history view binds to.
    ///
    /// The view uses DynamicResource for these values, so applying a change is a matter of
    /// re-publishing five window resources instead of walking the visual tree. One place computes
    /// them, which is also why the two history layouts (rows and cards) cannot drift apart.
    /// </summary>
    public static class HistoryAppearance
    {
        /// <summary>Raised after a setting changed, so the window can republish its resources.</summary>
        public static event Action? Changed;

        public static void Notify() => Changed?.Invoke();

        public static double BodyFontSize(AppSettings s) => Math.Round(13 * Scale(s), 1);

        public static double MetaFontSize(AppSettings s) => Math.Round(10 * Scale(s), 1);

        /// <summary>Text size multiplier clamped to the three values the UI offers.</summary>
        public static double Scale(AppSettings s) => s.HistoryFontScale switch
        {
            <= 0.95 => 0.9,
            >= 1.1 => 1.15,
            _ => 1.0
        };

        /// <summary>Vertical margin of a history entry container for the current density.</summary>
        public static Thickness ItemMargin(AppSettings s) => s.HistoryDensity switch
        {
            "compact" => new Thickness(0, 1, 0, 1),
            "cozy" => new Thickness(0, 7, 0, 7),
            _ => new Thickness(0, 3, 0, 3)
        };

        /// <summary>Inner padding of a history entry container for the current density.</summary>
        public static Thickness ItemPadding(AppSettings s) => s.HistoryDensity switch
        {
            "compact" => new Thickness(7, 4, 7, 4),
            "cozy" => new Thickness(9, 9, 9, 9),
            _ => new Thickness(8, 6, 8, 6)
        };

        /// <summary>Gap between the two card columns; also grows with density.</summary>
        public static double ColumnGap(AppSettings s) => s.HistoryDensity switch
        {
            "compact" => 5.0,
            "cozy" => 11.0,
            _ => 8.0
        };

        /// <summary>
        /// Resolves the identifier-bar brush for one entry type.
        ///
        /// "custom" reads the per-type map and falls back to the default colour when the user has
        /// not picked one; "theme" is only kept for settings written by 2.3.8 and earlier.
        /// </summary>
        public static Brush AccentFor(AppSettings s, string key, Brush defaultBrush)
        {
            if (s.HistoryAccentMode == "theme")
                return ThemeManager.Instance.ShareButtonColor;

            if (s.HistoryAccentMode == "custom"
                && s.HistoryAccentColors != null
                && s.HistoryAccentColors.TryGetValue(key, out var hex)
                && TryParseHex(hex, out var colour))
            {
                var brush = new SolidColorBrush(colour);
                brush.Freeze();
                return brush;
            }

            return defaultBrush;
        }

        /// <summary>Parses "#RRGGBB" (a leading "#" is optional).</summary>
        public static bool TryParseHex(string? hex, out Color colour)
        {
            colour = Colors.Gray;
            if (string.IsNullOrWhiteSpace(hex)) return false;

            var text = hex.Trim().TrimStart('#');
            if (text.Length != 6) return false;

            try
            {
                var r = Convert.ToByte(text.Substring(0, 2), 16);
                var g = Convert.ToByte(text.Substring(2, 2), 16);
                var b = Convert.ToByte(text.Substring(4, 2), 16);
                colour = Color.FromRgb(r, g, b);
                return true;
            }
            catch
            {
                return false;
            }
        }

        public static string ToHex(Color colour) => $"#{colour.R:X2}{colour.G:X2}{colour.B:X2}";

        /// <summary>Colour currently in effect for a type, for the editor's swatches.</summary>
        public static Color EffectiveColor(AppSettings s, string key)
        {
            var fallback = EntryFormat.DefaultHexFor(key);
            if (s.HistoryAccentMode == "custom"
                && s.HistoryAccentColors != null
                && s.HistoryAccentColors.TryGetValue(key, out var hex)
                && TryParseHex(hex, out var colour))
            {
                return colour;
            }

            return TryParseHex(fallback, out var def) ? def : Colors.Gray;
        }
    }
}
