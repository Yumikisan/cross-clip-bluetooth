using System;
using System.Globalization;
using System.Windows.Data;

namespace ClipSyncWindows.Converters
{
    /// <summary>
    /// Turns a width into a height by a fixed ratio, so a preview can keep a 4:3 box whatever
    /// the column width happens to be (the list and the two-column card mode differ).
    ///
    /// Usage: Height="{Binding ActualWidth, RelativeSource={RelativeSource AncestorType=Border},
    ///                  Converter={StaticResource AspectHeight}, ConverterParameter=0.75}"
    /// </summary>
    public class AspectHeightConverter : IValueConverter
    {
        public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        {
            if (value is not double width || double.IsNaN(width) || width <= 0) return 0d;

            double ratio = 0.75;
            if (parameter is string text &&
                double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var parsed) &&
                parsed > 0)
            {
                ratio = parsed;
            }

            return Math.Round(width * ratio, 1);
        }

        public object ConvertBack(object value, Type targetType, object parameter, CultureInfo culture) =>
            throw new NotSupportedException();
    }
}
