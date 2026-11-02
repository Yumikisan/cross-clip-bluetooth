using System;
using System.Windows;
using System.Windows.Controls;

namespace ClipSyncWindows.Controls
{
    /// <summary>
    /// Lays children out in two independent columns, each stacking at its own pace.
    ///
    /// Why not a UniformGrid: it sizes every row to its tallest member, so a one-line entry sitting
    /// next to a six-line one leaves a band of dead space underneath the short card. The Android
    /// list had the same problem with LazyVerticalGrid and was moved to a staggered grid for the
    /// same reason — an entry should start right below the previous one in its own column.
    ///
    /// Children are distributed by index (even → left, odd → right), which keeps the reading order
    /// left-to-right as the history is chronological. Virtualisation is not lost, because
    /// UniformGrid was not a virtualising panel to begin with.
    /// </summary>
    public sealed class TwoColumnPanel : Panel
    {
        public static readonly DependencyProperty ColumnGapProperty =
            DependencyProperty.Register(
                nameof(ColumnGap), typeof(double), typeof(TwoColumnPanel),
                new FrameworkPropertyMetadata(
                    6.0,
                    FrameworkPropertyMetadataOptions.AffectsMeasure |
                    FrameworkPropertyMetadataOptions.AffectsArrange));

        public static readonly DependencyProperty RowGapProperty =
            DependencyProperty.Register(
                nameof(RowGap), typeof(double), typeof(TwoColumnPanel),
                new FrameworkPropertyMetadata(
                    0.0,
                    FrameworkPropertyMetadataOptions.AffectsMeasure |
                    FrameworkPropertyMetadataOptions.AffectsArrange));

        /// <summary>Horizontal space between the two columns.</summary>
        public double ColumnGap
        {
            get => (double)GetValue(ColumnGapProperty);
            set => SetValue(ColumnGapProperty, value);
        }

        /// <summary>Extra vertical space between two cards of the same column.</summary>
        public double RowGap
        {
            get => (double)GetValue(RowGapProperty);
            set => SetValue(RowGapProperty, value);
        }

        protected override Size MeasureOverride(Size availableSize)
        {
            var count = InternalChildren.Count;
            if (count == 0) return new Size(0, 0);

            var columnWidth = ColumnWidth(availableSize.Width);
            var heights = new double[2];
            var counts = new int[2];

            for (var i = 0; i < count; i++)
            {
                var child = InternalChildren[i];
                child.Measure(new Size(columnWidth, double.PositiveInfinity));

                var column = i % 2;
                heights[column] += child.DesiredSize.Height;
                counts[column]++;
            }

            // Gaps only between neighbours, never before the first card.
            var height = Math.Max(
                heights[0] + Math.Max(0, counts[0] - 1) * RowGap,
                heights[1] + Math.Max(0, counts[1] - 1) * RowGap);

            var width = double.IsInfinity(availableSize.Width) || double.IsNaN(availableSize.Width)
                ? columnWidth * 2 + ColumnGap
                : availableSize.Width;

            return new Size(width, height);
        }

        protected override Size ArrangeOverride(Size finalSize)
        {
            var columnWidth = ColumnWidth(finalSize.Width);
            var offsetY = new double[2];

            for (var i = 0; i < InternalChildren.Count; i++)
            {
                var child = InternalChildren[i];
                var column = i % 2;
                var x = column == 0 ? 0 : columnWidth + ColumnGap;

                child.Arrange(new Rect(x, offsetY[column], columnWidth, child.DesiredSize.Height));
                offsetY[column] += child.DesiredSize.Height + RowGap;
            }

            return finalSize;
        }

        private double ColumnWidth(double availableWidth)
        {
            if (double.IsInfinity(availableWidth) || double.IsNaN(availableWidth) || availableWidth <= 0)
                return 0;

            return Math.Max(0, (availableWidth - ColumnGap) / 2);
        }
    }
}
