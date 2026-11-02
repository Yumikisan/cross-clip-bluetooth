using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Threading;
using System.Windows.Media.Effects;

namespace ClipSyncWindows.Services
{

    public static class NotificationHelper
    {
        /// <summary>
        /// Small bottom-right toast. When <paramref name="onClick"/> is supplied the whole card
        /// is clickable (any point, not just the caption) and shows <paramref name="hint"/>
        /// underneath (used by "received a file → open its folder").
        /// </summary>
        public static void ShowSimpleNotification(string title, string message,
            Action? onClick = null, string? hint = null, int durationSeconds = 3)
        {
            try
            {
                var theme = ThemeManager.Instance;
                var interactive = onClick != null;

                // Create window shell
                var toast = new Window
                {
                    Width = 300,
                    Height = interactive ? 126 : 100,
                    WindowStyle = WindowStyle.None,
                    AllowsTransparency = true,
                    Background = Brushes.Transparent,
                    ShowInTaskbar = false,
                    Topmost = true,
                    ShowActivated = false,
                    Opacity = 0 // start invisible for fade-in
                };

                // Build content with rounded-corner card + shadow - theme aware
                var border = new Border
                {
                    CornerRadius = new CornerRadius(8),
                    Background = theme.CardBackground,
                    Padding = new Thickness(12),
                    Effect = new DropShadowEffect
                    {
                        Color = theme.IsDarkTheme ? Colors.Black : Colors.Gray,
                        BlurRadius = 10,
                        ShadowDepth = 0,
                        Opacity = theme.IsDarkTheme ? 0.4 : 0.2
                    }
                };

                var stack = new StackPanel();

                stack.Children.Add(new TextBlock
                {
                    Text = title,
                    FontWeight = FontWeights.SemiBold,
                    FontSize = 14,
                    Foreground = theme.PrimaryText,
                    Margin = new Thickness(0, 0, 0, 4)
                });

                stack.Children.Add(new TextBlock
                {
                    Text = message,
                    TextWrapping = TextWrapping.Wrap,
                    FontSize = 12,
                    Foreground = theme.SecondaryText
                });

                if (interactive && !string.IsNullOrEmpty(hint))
                {
                    stack.Children.Add(new TextBlock
                    {
                        Text = hint,
                        TextWrapping = TextWrapping.Wrap,
                        FontSize = 11,
                        Margin = new Thickness(0, 6, 0, 0),
                        FontWeight = FontWeights.SemiBold,
                        Foreground = theme.ShareButtonForegroundColor
                    });
                }

                if (interactive)
                {
                    border.Cursor = System.Windows.Input.Cursors.Hand;
                    // Preview + Down: fires no matter which child the pointer lands on (the old
                    // bubbled MouseLeftButtonUp missed clicks that started on a text run).
                    border.AddHandler(System.Windows.UIElement.MouseLeftButtonDownEvent,
                        new System.Windows.Input.MouseButtonEventHandler((_, _) =>
                        {
                            try { onClick!(); } catch { }
                            toast.Close();
                        }), true);
                }

                border.Child = stack;
                toast.Content = border;

                // Position at bottom-right of the primary screen's work area
                var wa = SystemParameters.WorkArea;
                toast.Left = wa.Right - toast.Width - 16;
                toast.Top = wa.Bottom - toast.Height - 16;

                // Fade+slide in
                var fadeIn = new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(300));
                toast.BeginAnimation(UIElement.OpacityProperty, fadeIn);

                var slideIn = new DoubleAnimation(wa.Bottom + toast.Height, toast.Top,
                                    TimeSpan.FromMilliseconds(300))
                {
                    EasingFunction = new QuadraticEase { EasingMode = EasingMode.EaseOut }
                };
                toast.BeginAnimation(Window.TopProperty, slideIn);

                toast.Show();

                // Auto-close after the configured delay with a fade-out
                var timer = new DispatcherTimer
                {
                    Interval = TimeSpan.FromSeconds(Math.Clamp(durationSeconds, 1, 60))
                };
                timer.Tick += (s, e) =>
                {
                    timer.Stop();

                    var fadeOut = new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(300));
                    fadeOut.Completed += (_, __) => toast.Close();
                    toast.BeginAnimation(UIElement.OpacityProperty, fadeOut);
                };
                timer.Start();
            }
            catch
            {
                // best-effort only
            }
        }
    }
}
