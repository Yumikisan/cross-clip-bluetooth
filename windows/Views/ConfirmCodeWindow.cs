using System;
using System.Media;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Threading;
using ClipSyncWindows.Services;

namespace ClipSyncWindows.Views
{
    /// <summary>
    /// Modal-looking dialog that shows the six digit connection code. The user has to compare
    /// it with the other device and accept; closing the window counts as a rejection, and so
    /// does letting the countdown run out.
    /// </summary>
    public sealed class ConfirmCodeWindow : Window
    {
        private readonly DispatcherTimer _timer;
        private readonly TextBlock _countdown;
        private readonly string _sid;
        private int _secondsLeft = SessionAuth.ConfirmTimeoutSeconds;
        private bool _answered;

        public event Action<ConfirmCodeWindow, bool>? Answered;

        public ConfirmCodeWindow(string peerName, string code, string sid)
        {
            _sid = sid;

            Title = Loc.T("dlg.authTitle");
            Width = 400;
            SizeToContent = SizeToContent.Height;
            ResizeMode = ResizeMode.NoResize;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            Topmost = true;
            ShowInTaskbar = true;
            Background = ThemeManager.Instance.WindowBackground;

            var root = new StackPanel { Margin = new Thickness(24, 22, 24, 18) };

            root.Children.Add(new TextBlock
            {
                Text = Loc.T("dlg.authPeer", peerName),
                FontSize = 15,
                FontWeight = FontWeights.SemiBold,
                TextWrapping = TextWrapping.Wrap,
                Foreground = ThemeManager.Instance.PrimaryText
            });

            root.Children.Add(new TextBlock
            {
                Text = Loc.T("dlg.authInstruction"),
                FontSize = 12,
                Margin = new Thickness(0, 6, 0, 0),
                TextWrapping = TextWrapping.Wrap,
                Foreground = ThemeManager.Instance.SecondaryText,
                Opacity = 0.75
            });

            // "123 456" reads far better than a bare six digit run.
            var pretty = code.Length == 6 ? code.Substring(0, 3) + " " + code.Substring(3) : code;

            root.Children.Add(new TextBlock
            {
                Text = pretty,
                FontFamily = new FontFamily("Consolas, Segoe UI"),
                FontSize = 42,
                FontWeight = FontWeights.Bold,
                Margin = new Thickness(0, 18, 0, 12),
                HorizontalAlignment = HorizontalAlignment.Center,
                Foreground = ThemeManager.Instance.StatusBarBackground
            });

            _countdown = new TextBlock
            {
                Text = Loc.T("dlg.authCountdown", _secondsLeft),
                FontSize = 11,
                HorizontalAlignment = HorizontalAlignment.Center,
                Opacity = 0.6,
                Foreground = ThemeManager.Instance.SecondaryText
            };
            root.Children.Add(_countdown);

            var buttons = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                HorizontalAlignment = HorizontalAlignment.Right,
                Margin = new Thickness(0, 20, 0, 0)
            };

            var reject = new Button
            {
                Content = Loc.T("dlg.authReject"),
                Padding = new Thickness(16, 7, 16, 7),
                Margin = new Thickness(0, 0, 8, 0),
                MinWidth = 96
            };
            reject.Click += (_, _) => Answer(false);

            var accept = new Button
            {
                Content = Loc.T("dlg.authAccept"),
                Padding = new Thickness(16, 7, 16, 7),
                MinWidth = 96,
                IsDefault = true
            };
            accept.Click += (_, _) => Answer(true);

            buttons.Children.Add(reject);
            buttons.Children.Add(accept);
            root.Children.Add(buttons);

            Content = root;

            _timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
            _timer.Tick += (_, _) =>
            {
                _secondsLeft--;
                if (_secondsLeft <= 0)
                {
                    Answer(false);
                    return;
                }
                _countdown.Text = Loc.T("dlg.authCountdown", _secondsLeft);
            };
            _timer.Start();

            Loaded += (_, _) =>
            {
                Activate();
                try { SystemSounds.Exclamation.Play(); } catch { }
            };

            Closed += (_, _) =>
            {
                _timer.Stop();
                if (_answered) return;
                // Window closed with the X or Alt+F4: treat it as "no".
                _answered = true;
                Answered?.Invoke(this, false);
            };
        }

        public string Sid => _sid;

        private void Answer(bool accepted)
        {
            if (_answered) return;
            _answered = true;
            _timer.Stop();
            Answered?.Invoke(this, accepted);
            Close();
        }
    }
}
