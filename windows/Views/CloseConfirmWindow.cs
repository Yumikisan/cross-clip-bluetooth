using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using ClipSyncWindows.Services;

namespace ClipSyncWindows.Views
{
    /// <summary>
    /// Shown the first time the user closes the main window: asks whether closing should
    /// minimize to the notification area or really exit. The checkbox is pre-checked
    /// ("keep ClipSync alive in the tray") and the choice is remembered, so this dialog
    /// appears exactly once unless the user resets it from Settings.
    /// </summary>
    public sealed class CloseConfirmWindow : Window
    {
        /// <summary>True when the user confirmed "minimize to tray on close".</summary>
        public bool ToTray { get; private set; } = true;

        /// <summary>False when the user cancelled this close attempt (window stays open).</summary>
        public bool Confirmed { get; private set; }

        public CloseConfirmWindow()
        {
            Title = Loc.T("close.title");
            Width = 420;
            SizeToContent = SizeToContent.Height;
            ResizeMode = ResizeMode.NoResize;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            ShowInTaskbar = false;
            Background = ThemeManager.Instance.WindowBackground;

            var root = new StackPanel { Margin = new Thickness(22, 20, 22, 16) };

            root.Children.Add(new TextBlock
            {
                Text = Loc.T("close.question"),
                FontSize = 13,
                TextWrapping = TextWrapping.Wrap,
                Foreground = ThemeManager.Instance.PrimaryText
            });

            var checkbox = new CheckBox
            {
                Content = Loc.T("close.toTray"),
                IsChecked = true,
                FontSize = 13,
                Margin = new Thickness(0, 14, 0, 0),
                Foreground = ThemeManager.Instance.PrimaryText
            };
            root.Children.Add(checkbox);

            var buttons = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                HorizontalAlignment = HorizontalAlignment.Right,
                Margin = new Thickness(0, 20, 0, 0)
            };

            var cancel = new Button
            {
                Content = Loc.T("btn.cancel"),
                Padding = new Thickness(16, 7, 16, 7),
                Margin = new Thickness(0, 0, 8, 0),
                MinWidth = 96
            };
            cancel.Click += (_, _) => { Confirmed = false; Close(); };

            var ok = new Button
            {
                Content = Loc.T("btn.ok"),
                Padding = new Thickness(16, 7, 16, 7),
                MinWidth = 96,
                IsDefault = true
            };
            ok.Click += (_, _) =>
            {
                ToTray = checkbox.IsChecked == true;
                Confirmed = true;
                Close();
            };

            buttons.Children.Add(cancel);
            buttons.Children.Add(ok);
            root.Children.Add(buttons);

            Content = root;
        }
    }
}
