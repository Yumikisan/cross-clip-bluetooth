using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using ClipSyncWindows.Models;
using ClipSyncWindows.Security;
using ClipSyncWindows.Services;

namespace ClipSyncWindows.Views
{
    /// <summary>
    /// First-run security wizard. Explains why a second encryption layer exists on top of the
    /// Bluetooth pairing, then offers the three ways to get a pre-shared key:
    /// generate one, paste in one that already exists, or type your own.
    ///
    /// Everything stays local: the key is written through <see cref="PskVault"/> and never sent
    /// over the link. Deliberately dismissible — refusing to let the user into the app until a key
    /// exists would be worse than plaintext, because a key set on one end only refuses the peer
    /// (encrypting end refuses plaintext) and looks like a broken connection.
    /// </summary>
    public sealed class SecurityWizardWindow : Window
    {
        private readonly AppSettings _settings;

        private readonly StackPanel _intro = new();
        private readonly StackPanel _detail = new();
        private readonly TextBox _generatedBox = new();
        private readonly PasswordBox _manualBox = new();
        private readonly TextBlock _error = new();
        private readonly TextBlock _fingerprint = new();
        private readonly Button _finish = new();

        private bool _generated;
        private bool _saved;

        public SecurityWizardWindow(AppSettings settings)
        {
            _settings = settings;

            Title = Loc.T("wiz.title");
            Width = 520;
            SizeToContent = SizeToContent.Height;
            ResizeMode = ResizeMode.NoResize;
            WindowStartupLocation = WindowStartupLocation.CenterScreen;
            ShowInTaskbar = false;
            Background = ThemeManager.Instance.WindowBackground;

            var root = new StackPanel { Margin = new Thickness(24, 20, 24, 18) };
            root.Children.Add(Header());

            _intro.Children.Add(Paragraph(Loc.T("wiz.intro1")));
            _intro.Children.Add(Paragraph(Loc.T("wiz.intro2")));
            _intro.Children.Add(Paragraph(Loc.T("wiz.intro3")));
            _intro.Children.Add(OptionButton("wiz.optGenerate", "wiz.optGenerateHint", OnGenerate));
            _intro.Children.Add(OptionButton("wiz.optExisting", "wiz.optExistingHint", () => OnManual(false)));
            _intro.Children.Add(OptionButton("wiz.optCreate", "wiz.optCreateHint", () => OnManual(true)));
            root.Children.Add(_intro);

            _detail.Visibility = Visibility.Collapsed;
            root.Children.Add(_detail);

            root.Children.Add(_error);
            root.Children.Add(_fingerprint);

            var footer = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                HorizontalAlignment = HorizontalAlignment.Right,
                Margin = new Thickness(0, 18, 0, 0)
            };
            var later = new Button
            {
                Content = Loc.T("wiz.later"),
                Padding = new Thickness(16, 7, 16, 7),
                MinWidth = 110,
                Margin = new Thickness(0, 0, 8, 0)
            };
            later.Click += (_, _) => { MarkSeen(); Close(); };
            _finish.Content = Loc.T("wiz.finish");
            _finish.Padding = new Thickness(16, 7, 16, 7);
            _finish.MinWidth = 110;
            _finish.IsDefault = true;
            _finish.Click += (_, _) => Finish();
            footer.Children.Add(later);
            footer.Children.Add(_finish);
            root.Children.Add(footer);

            Content = root;
        }

        /// <summary>True when a key was actually stored by this wizard.</summary>
        public bool KeySaved => _saved;

        private UIElement Header()
        {
            var panel = new StackPanel();
            panel.Children.Add(new TextBlock
            {
                Text = Loc.T("wiz.headline"),
                FontSize = 17,
                FontWeight = FontWeights.SemiBold,
                TextWrapping = TextWrapping.Wrap,
                Foreground = ThemeManager.Instance.PrimaryText
            });
            panel.Children.Add(new TextBlock
            {
                Text = Loc.T("wiz.subhead"),
                FontSize = 12,
                Margin = new Thickness(0, 6, 0, 4),
                TextWrapping = TextWrapping.Wrap,
                Foreground = ThemeManager.Instance.SecondaryText,
                Opacity = 0.75
            });
            return panel;
        }

        private TextBlock Paragraph(string text) => new()
        {
            Text = text,
            FontSize = 13,
            TextWrapping = TextWrapping.Wrap,
            Margin = new Thickness(0, 10, 0, 0),
            LineHeight = 20,
            Foreground = ThemeManager.Instance.PrimaryText
        };

        private Button OptionButton(string titleKey, string hintKey, Action onClick)
        {
            var content = new StackPanel();
            content.Children.Add(new TextBlock
            {
                Text = Loc.T(titleKey),
                FontSize = 13,
                FontWeight = FontWeights.SemiBold,
                Foreground = ThemeManager.Instance.PrimaryText
            });
            content.Children.Add(new TextBlock
            {
                Text = Loc.T(hintKey),
                FontSize = 11.5,
                Margin = new Thickness(0, 3, 0, 0),
                TextWrapping = TextWrapping.Wrap,
                Foreground = ThemeManager.Instance.SecondaryText,
                Opacity = 0.75
            });

            var button = new Button
            {
                Content = content,
                Margin = new Thickness(0, 10, 0, 0),
                Padding = new Thickness(14, 10, 14, 10),
                HorizontalContentAlignment = HorizontalAlignment.Left,
                Background = ThemeManager.Instance.CardBackground,
                BorderBrush = ThemeManager.Instance.BorderBrush,
                BorderThickness = new Thickness(1),
                Cursor = System.Windows.Input.Cursors.Hand
            };
            button.Click += (_, _) => onClick();
            return button;
        }

        private void OnGenerate()
        {
            var key = PskGenerator.Generate();
            _generated = true;

            _detail.Children.Clear();

            var label = new TextBlock
            {
                Text = Loc.T("wiz.generatedTitle"),
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                Margin = new Thickness(0, 4, 0, 0),
                Foreground = ThemeManager.Instance.PrimaryText
            };
            _detail.Children.Add(label);

            _generatedBox.Text = key;
            _generatedBox.FontFamily = new FontFamily("Consolas, Cascadia Mono, monospace");
            _generatedBox.FontSize = 19;
            _generatedBox.IsReadOnly = true;
            _generatedBox.Padding = new Thickness(10, 8, 10, 8);
            _generatedBox.Margin = new Thickness(0, 8, 0, 0);
            _generatedBox.Background = ThemeManager.Instance.CardBackground;
            _generatedBox.Foreground = ThemeManager.Instance.PrimaryText;
            _generatedBox.BorderBrush = ThemeManager.Instance.BorderBrush;
            _generatedBox.TextAlignment = TextAlignment.Center;
            _detail.Children.Add(_generatedBox);

            var hint = Paragraph(Loc.T("wiz.generatedHint"));
            hint.Foreground = ThemeManager.Instance.SecondaryText;
            hint.Opacity = 0.85;
            _detail.Children.Add(hint);

            var copy = new Button
            {
                Content = Loc.T("wiz.copy"),
                Padding = new Thickness(14, 6, 14, 6),
                Margin = new Thickness(0, 10, 0, 0),
                HorizontalAlignment = HorizontalAlignment.Left
            };
            copy.Click += (_, _) =>
            {
                try
                {
                    Clipboard.SetText(key);
                    // Same reason as on Android: a copied key must not be forwarded by the
                    // clipboard watcher to the device it is meant to authenticate.
                    (System.Windows.Application.Current.MainWindow as MainWindow)?.SuppressClipboardEcho(key);
                    _fingerprint.Text = Loc.T("wiz.copied");
                }
                catch { _fingerprint.Text = ""; }
                ShowFingerprint(key);
            };
            _detail.Children.Add(copy);

            _manualBox.Clear();
            _intro.Visibility = Visibility.Collapsed;
            _detail.Visibility = Visibility.Visible;
            ShowFingerprint(key);
        }

        private void OnManual(bool creating)
        {
            _generated = false;
            _detail.Children.Clear();

            _detail.Children.Add(new TextBlock
            {
                Text = Loc.T(creating ? "wiz.createTitle" : "wiz.existingTitle"),
                FontSize = 12,
                FontWeight = FontWeights.SemiBold,
                Margin = new Thickness(0, 4, 0, 0),
                Foreground = ThemeManager.Instance.PrimaryText
            });

            _manualBox.Clear();
            _manualBox.Width = double.NaN;
            _manualBox.Padding = new Thickness(10, 6, 10, 6);
            _manualBox.Margin = new Thickness(0, 8, 0, 0);
            _manualBox.Background = ThemeManager.Instance.CardBackground;
            _manualBox.Foreground = ThemeManager.Instance.PrimaryText;
            _manualBox.BorderBrush = ThemeManager.Instance.BorderBrush;
            if (!_detail.Children.Contains(_manualBox)) _detail.Children.Add(_manualBox);

            var hint = Paragraph(creating
                ? string.Format(Loc.T("wiz.createHint"), SettingsViewModelPskLength)
                : Loc.T("wiz.existingHint"));
            hint.Foreground = ThemeManager.Instance.SecondaryText;
            hint.Opacity = 0.85;
            _detail.Children.Add(hint);

            // Suggestions only make sense when the user is inventing one.
            if (creating)
            {
                var suggest = new Button
                {
                    Content = Loc.T("wiz.suggest"),
                    Padding = new Thickness(14, 6, 14, 6),
                    Margin = new Thickness(0, 10, 0, 0),
                    HorizontalAlignment = HorizontalAlignment.Left
                };
                // Offered into the box so the user can still tweak it; nothing is stored twice.
                suggest.Click += (_, _) =>
                {
                    var key = PskGenerator.Generate();
                    _manualBox.Password = key;
                    ShowFingerprint(key);
                };
                _detail.Children.Add(suggest);
            }

            _intro.Visibility = Visibility.Collapsed;
            _detail.Visibility = Visibility.Visible;
            _manualBox.Focus();
        }

        private void ShowFingerprint(string key)
        {
            var normalized = PskVault.Normalize(key);
            if (normalized.Length == 0) { _fingerprint.Text = ""; return; }
            _fingerprint.Text = string.Format(Loc.T("wiz.fingerprint"), PskVault.Fingerprint(normalized));
            _fingerprint.FontSize = 12;
            _fingerprint.Margin = new Thickness(0, 10, 0, 0);
            _fingerprint.TextWrapping = TextWrapping.Wrap;
            _fingerprint.Foreground = ThemeManager.Instance.SecondaryText;
            _fingerprint.Opacity = 0.9;
        }

        private void Finish()
        {
            var raw = _generated ? _generatedBox.Text : _manualBox.Password;
            var key = PskVault.Normalize(raw);

            if (key.Length == 0)
            {
                // Nothing chosen yet: treat "finish" as "remind me later" instead of failing.
                Intro();
                return;
            }

            if (key.Length < SettingsViewModelPskLength)
            {
                _error.Text = string.Format(Loc.T("psk.tooShort"), SettingsViewModelPskLength);
                _error.FontSize = 12;
                _error.Margin = new Thickness(0, 10, 0, 0);
                _error.TextWrapping = TextWrapping.Wrap;
                _error.Foreground = Brushes.IndianRed;
                return;
            }

            var stored = PskVault.Protect(key);
            if (string.IsNullOrEmpty(stored) || PskVault.Unprotect(stored) != key)
            {
                _error.Text = Loc.T("wiz.storeFailed");
                _error.FontSize = 12;
                _error.Margin = new Thickness(0, 10, 0, 0);
                _error.TextWrapping = TextWrapping.Wrap;
                _error.Foreground = Brushes.IndianRed;
                return;
            }

            _settings.PskProtected = stored;
            // A key with mode=off would never be used; auto is what the user just asked for.
            if (string.IsNullOrWhiteSpace(_settings.SecurityMode) || _settings.SecurityMode == "off")
                _settings.SecurityMode = "auto";
            SettingsService.Save();
            _saved = true;
            MarkSeen();
            DialogResult = true;
        }

        private void Intro()
        {
            _generated = false;
            _manualBox.Clear();
            _detail.Children.Clear();
            _detail.Visibility = Visibility.Collapsed;
            _intro.Visibility = Visibility.Visible;
            _error.Text = "";
            _fingerprint.Text = "";
        }

        private void MarkSeen()
        {
            if (_settings.SecurityWizardDone) return;
            _settings.SecurityWizardDone = true;
            SettingsService.Save();
        }

        /// <summary>Kept in sync with SettingsViewModel.MinPskLength.</summary>
        private const int SettingsViewModelPskLength = 12;
    }
}
