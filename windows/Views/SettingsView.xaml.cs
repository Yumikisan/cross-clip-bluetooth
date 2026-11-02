using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using ClipSyncWindows.ViewModels;

namespace ClipSyncWindows.Views
{
    public partial class SettingsView : UserControl
    {
        public SettingsView()
        {
            InitializeComponent();
            Loaded += (_, _) => UpdatePskPlaceholder();
        }

        private SettingsViewModel? ViewModel => DataContext as SettingsViewModel;

        // ---------------------------------------------------------------- help & feedback

        /// <summary>
        /// Category label shown in the pasted report. Kept as the localised name the user saw,
        /// not an enum, so a developer reading the issue knows which tab they were on.
        /// </summary>
        private string FeedbackKind()
        {
            if (FeedbackKindBox?.SelectedItem is ComboBoxItem item) return item.Content?.ToString() ?? "";
            return "";
        }

        /// <summary>Environment summary + what the user typed. Nothing is sent anywhere.</summary>
        private string BuildReport()
        {
            var vm = ViewModel;
            var detail = FeedbackDetailBox?.Text?.Trim() ?? "";
            var head = Services.SupportReport.EnvironmentInfo(
                SettingsViewModel.AppVersion,
                vm?.SecurityMode ?? "auto",
                FeedbackKind());
            // The last few lines of the transfer log give the report a starting point without
            // making the user hunt for a file.
            var tail = Services.SupportReport.Tail("transfer.log", 12);
            var report = head;
            if (!string.IsNullOrWhiteSpace(detail)) report += Environment.NewLine + "问题描述:" + Environment.NewLine + detail + Environment.NewLine;
            if (!string.IsNullOrWhiteSpace(tail)) report += Environment.NewLine + "最近日志:" + Environment.NewLine + tail;
            return report;
        }

        private void CopyReport_Click(object sender, RoutedEventArgs e)
        {
            Services.SupportReport.Copy(BuildReport());
            Toast(Services.I18n.Instance.T("msg.reportCopied"));
        }

        /// <summary>
        /// Packs the log files next to the app's data folder. Deliberately a manual step: an
        /// automatic upload would need a server, and this app has none.
        /// </summary>
        private void ExportLogs_Click(object sender, RoutedEventArgs e)
        {
            var version = SettingsViewModel.AppVersion;
            var zip = Services.SupportReport.ExportLogs(version);
            if (zip != null)
                Toast(Services.I18n.Instance.T("msg.logsExported", zip));
            else if (!Services.SupportReport.HasLogs())
                Toast(Services.I18n.Instance.T("msg.logsEmpty"));
            else
                Toast(Services.I18n.Instance.T("msg.logsFailed"));
        }

        private void OpenIssues_Click(object sender, RoutedEventArgs e) =>
            Services.SupportReport.OpenUrl(Services.SupportReport.IssuesUrl);

        private void OpenDevHome_Click(object sender, RoutedEventArgs e) =>
            Services.SupportReport.OpenUrl(Services.SupportReport.UpstreamUrl);

        private void OpenForkHome_Click(object sender, RoutedEventArgs e) =>
            Services.SupportReport.OpenUrl(Services.SupportReport.ForkUrl);

        private void OpenUpstreamSponsor_Click(object sender, RoutedEventArgs e) =>
            Services.SupportReport.OpenUrl(Services.SupportReport.UpstreamSponsorUrl);

        private void OpenForkSponsor_Click(object sender, RoutedEventArgs e) =>
            Services.SupportReport.OpenUrl(Services.SupportReport.ForkSponsorUrl);

        private void OpenHelpDoc_Click(object sender, RoutedEventArgs e) =>
            Services.SupportReport.OpenUrl(Services.SupportReport.HelpUrl);

        /// <summary>Inline confirmation: a modal box for "copied" would be heavier than the action.</summary>
        private void Toast(string message)
        {
            if (Window.GetWindow(this) is MainWindow main) main.ShowStatus(message);
        }

        // ---------------------------------------------------------------- hotkey capture

        private void HotkeyBox_GotKeyboardFocus(object sender, KeyboardFocusChangedEventArgs e)
        {
            // Visual cue only: the box is read-only, keys are captured in PreviewKeyDown.
            if (sender is TextBox box) box.SelectAll();
        }

        private void HotkeyBox_PreviewKeyDown(object sender, KeyEventArgs e)
        {
            e.Handled = true;

            if (e.Key == Key.Escape)
            {
                Keyboard.ClearFocus();
                return;
            }

            var gesture = Services.HotkeyService.FromKeyEvent(e);
            if (gesture == null) return;   // still typing the chord: bare modifiers

            if (ViewModel is { } vm) vm.HotkeyGesture = gesture;
        }

        private void HotkeyClear_Click(object sender, System.Windows.RoutedEventArgs e)
        {
            if (ViewModel is { } vm) vm.HotkeyGesture = "";
        }

        // ---------------------------------------------------------------- layer 2 security

        private void SavePsk_Click(object sender, RoutedEventArgs e) => SubmitPsk();

        /// <summary>Enter in the key field saves too — most people never look for the button.</summary>
        private void PskBox_KeyDown(object sender, System.Windows.Input.KeyEventArgs e)
        {
            if (e.Key != System.Windows.Input.Key.Enter) return;
            e.Handled = true;
            SubmitPsk();
        }

        /// <summary>The key currently on screen, whichever of the two fields is showing it.</summary>
        private string CurrentPsk() =>
            PskBox.Visibility == Visibility.Visible ? PskBox.Password : PskBoxPlain.Text;

        /// <summary>
        /// True while the reveal box is showing the *stored* key rather than something the user
        /// typed. It lets hiding the field drop that value again instead of silently saving it.
        /// </summary>
        private bool _pskAutoFilled;

        private void ClearPskFields()
        {
            PskBox.Clear();
            PskBoxPlain.Clear();
            _pskAutoFilled = false;
            UpdatePskPlaceholder();
        }

        /// <summary>
        /// The hint is shown when the visible field has no text. Done in code because
        /// PasswordBox.Password is not a dependency property — a binding to it never fires, which
        /// is why the hint used to sit on top of the masked dots.
        /// </summary>
        private void UpdatePskPlaceholder()
        {
            if (PskPlaceholder == null) return;
            bool empty = PskBox.Visibility == Visibility.Visible
                ? PskBox.Password.Length == 0
                : PskBoxPlain.Text.Length == 0;
            PskPlaceholder.Visibility = empty ? Visibility.Visible : Visibility.Collapsed;
        }

        private void PskBox_PasswordChanged(object sender, RoutedEventArgs e) => UpdatePskPlaceholder();

        private void PskBoxPlain_TextChanged(object sender, TextChangedEventArgs e)
        {
            // Any manual edit means the box no longer mirrors the saved key verbatim.
            _pskAutoFilled = false;
            UpdatePskPlaceholder();
        }

        /// <summary>
        /// Eye toggle. Masked by default; revealing swaps in a plain TextBox. When the field is
        /// empty it also loads the key that is already saved on this device, so "show" means "show
        /// me my key", not "show me whatever I happen to be typing" — that was the whole point of
        /// the request, since a key typed wrong is indistinguishable from a key mismatch.
        /// </summary>
        private void TogglePskVisibility_Click(object sender, RoutedEventArgs e)
        {
            bool revealing = PskBox.Visibility == Visibility.Visible;

            if (revealing)
            {
                if (PskBox.Password.Length == 0)
                {
                    var stored = Security.PskVault.Unprotect(Services.SettingsService.Current.PskProtected);
                    if (stored.Length > 0)
                    {
                        PskBoxPlain.Text = stored;
                        _pskAutoFilled = true;   // set after TextChanged resets it
                    }
                }
                else
                {
                    PskBoxPlain.Text = PskBox.Password;
                }

                PskBox.Visibility = Visibility.Collapsed;
                PskBoxPlain.Visibility = Visibility.Visible;
                PskBoxPlain.Focus();
                PskBoxPlain.CaretIndex = PskBoxPlain.Text.Length;
            }
            else
            {
                if (_pskAutoFilled)
                {
                    // Nothing was edited: drop the revealed copy again rather than leaving a
                    // readable key sitting in the field.
                    PskBoxPlain.Clear();
                    PskBox.Clear();
                    _pskAutoFilled = false;
                }
                else
                {
                    PskBox.Password = PskBoxPlain.Text;
                }

                PskBoxPlain.Visibility = Visibility.Collapsed;
                PskBox.Visibility = Visibility.Visible;
                PskBox.Focus();
            }

            PskEyeIcon.Text = revealing ? "\uED1A" : "\uE7B3";
            PskEyeButton.ToolTip = Services.Loc.T(revealing ? "psk.hide" : "psk.show");
            UpdatePskPlaceholder();
        }

        private void SubmitPsk()
        {
            if (ViewModel is not { } vm) return;
            var typed = CurrentPsk();

            var error = vm.SavePsk(typed);
            if (error != null)
            {
                MessageBox.Show(string.Format(Services.Loc.T(error), SettingsViewModel.MinPskLength),
                    Services.Loc.T("lbl.psk"), MessageBoxButton.OK, MessageBoxImage.Warning);
                return;
            }

            ClearPskFields();
            // Feedback lives in the summary line right above the field, so the user can see the
            // fingerprint without the key ever being echoed back.
            MessageBox.Show(Services.Loc.T("psk.saved"), Services.Loc.T("lbl.psk"),
                MessageBoxButton.OK, MessageBoxImage.Information);

            if (!string.IsNullOrEmpty(vm.SecurityWarning))
            {
                MessageBox.Show(vm.SecurityWarning, Services.Loc.T("lbl.secMode"),
                    MessageBoxButton.OK, MessageBoxImage.Information);
                vm.SecurityWarning = "";
            }
        }

        private void ClearPsk_Click(object sender, RoutedEventArgs e)
        {
            if (ViewModel is not { } vm) return;

            // Clearing the key drops encrypted sync back to whatever the mode allows, on this
            // device only, and there is no undo — so ask first instead of acting on one click.
            var confirm = MessageBox.Show(
                Services.Loc.T("psk.confirmClear"),
                Services.Loc.T("psk.confirmClearTitle"),
                MessageBoxButton.YesNo, MessageBoxImage.Warning, MessageBoxResult.No);
            if (confirm != MessageBoxResult.Yes) return;

            vm.ClearPsk();
            ClearPskFields();

            if (!string.IsNullOrEmpty(vm.SecurityWarning))
            {
                MessageBox.Show(vm.SecurityWarning, Services.Loc.T("lbl.secMode"),
                    MessageBoxButton.OK, MessageBoxImage.Information);
                vm.SecurityWarning = "";
            }
        }

        /// <summary>
        /// Re-opens the first-run wizard. The main window owns the link manager, so it also owns
        /// the "a new key means every live session must be renegotiated" part.
        /// </summary>
        private void OpenWizard_Click(object sender, RoutedEventArgs e)
        {
            (Window.GetWindow(this) as MainWindow)?.OpenSecurityWizard();
            ViewModel?.RefreshAll();
        }

        /// <summary>Called every time the panel is opened so it reflects external changes.</summary>
        public void Reload()        {
            ViewModel?.RefreshAll();
            ViewModel?.RefreshStorage();
            SyncHistoryPills();
        }

        // ---------------------------------------------------------------- history display

        private void HistoryLines_Click(object sender, System.Windows.RoutedEventArgs e)
        {
            if (ViewModel == null) return;
            if ((sender as FrameworkElement)?.Tag is not string tag) return;
            if (!int.TryParse(tag, out var lines)) return;
            ViewModel.HistoryLines = lines;
            SyncHistoryPills();
        }

        /// <summary>Pushes the view-model values back into the segmented pills.</summary>
        private void SyncHistoryPills()
        {
            if (ViewModel is not { } vm) return;

            foreach (var child in HistoryLinesPanel.Children)
            {
                if (child is not RadioButton pill) continue;
                pill.IsChecked = pill.Tag is string t && int.TryParse(t, out var n) && n == vm.HistoryLines;
            }
        }

        // ---------------------------------------------------------------- storage

        private void CleanNow_Click(object sender, System.Windows.RoutedEventArgs e)
        {
            var result = System.Windows.MessageBox.Show(
                Services.Loc.T("lbl.cleanConfirm"),
                Services.Loc.T("btn.cleanNow"),
                System.Windows.MessageBoxButton.OKCancel,
                System.Windows.MessageBoxImage.Warning);
            if (result != System.Windows.MessageBoxResult.OK) return;

            ViewModel?.CleanAllFiles();
            ViewModel?.RefreshStorage();
        }

        /// <summary>
        /// Deletes one storage row (a single type). Always asks first: this removes the records
        /// and the payload file, and there is no undo.
        /// </summary>
        private void StorageDeleteKind_Click(object sender, System.Windows.RoutedEventArgs e)
        {
            if ((sender as System.Windows.FrameworkElement)?.Tag is not Services.StorageStat row) return;

            var result = System.Windows.MessageBox.Show(
                Services.Loc.T("storage.clearKindConfirm", row.Label),
                Services.Loc.T("storage.clearKind", row.Label),
                System.Windows.MessageBoxButton.OKCancel,
                System.Windows.MessageBoxImage.Warning);
            if (result != System.Windows.MessageBoxResult.OK) return;

            // The table refreshes itself, which is the feedback: the row is gone.
            ViewModel?.RemoveKind(row);
        }

        private void MaxStorageMinus_Click(object sender, System.Windows.RoutedEventArgs e)
        {
            if (ViewModel != null) ViewModel.MaxStorageMb -= ViewModel.MaxStorageMb <= 64 ? 16 : 64;
        }

        private void MaxStoragePlus_Click(object sender, System.Windows.RoutedEventArgs e)
        {
            if (ViewModel != null) ViewModel.MaxStorageMb += ViewModel.MaxStorageMb < 64 ? 16 : 64;
        }

        private void CleanupPercentMinus_Click(object sender, System.Windows.RoutedEventArgs e)
        {
            if (ViewModel != null) ViewModel.CleanupPercent -= 5;
        }

        private void CleanupPercentPlus_Click(object sender, System.Windows.RoutedEventArgs e)
        {
            if (ViewModel != null) ViewModel.CleanupPercent += 5;
        }

        // ---------------------------------------------------------------- history limit box

        /// <summary>Digits only — no sign, no decimal point, no letters.</summary>
        private void HistoryLimitBox_PreviewTextInput(object sender, TextCompositionEventArgs e)
        {
            foreach (var c in e.Text)
            {
                if (!char.IsDigit(c))
                {
                    e.Handled = true;
                    return;
                }
            }
        }

        /// <summary>Digits only; shared by every numeric settings field (toast seconds, ...).</summary>
        private void NumberBox_PreviewTextInput(object sender, TextCompositionEventArgs e) =>
            HistoryLimitBox_PreviewTextInput(sender, e);

        /// <summary>Up/Down step the value by 10 so the box still feels adjustable.</summary>
        private void HistoryLimitBox_PreviewKeyDown(object sender, KeyEventArgs e)
        {
            if (ViewModel == null) return;

            switch (e.Key)
            {
                case Key.Up:
                    ViewModel.HistoryLimit += 10;
                    e.Handled = true;
                    MoveCaretToEnd(sender);
                    break;
                case Key.Down:
                    ViewModel.HistoryLimit -= 10;
                    e.Handled = true;
                    MoveCaretToEnd(sender);
                    break;
                case Key.Enter:
                    // Push the typed value through the binding immediately.
                    HistoryLimitBox.GetBindingExpression(TextBox.TextProperty)?.UpdateSource();
                    e.Handled = true;
                    break;
            }
        }

        private void MoveCaretToEnd(object sender)
        {
            if (sender is TextBox box)
            {
                box.CaretIndex = box.Text.Length;
            }
        }

        /// <summary>Opens the per-type colour editor ("custom" accent mode).</summary>
        private void EditAccentColors_Click(object sender, System.Windows.RoutedEventArgs e)
        {
            if (ViewModel is not { } vm) return;

            var dialog = new AccentColorsDialog(vm) { Owner = Window.GetWindow(this) };
            dialog.ShowDialog();
        }

        private void BrowseFolder_Click(object sender, System.Windows.RoutedEventArgs e)
        {
            using var dialog = new System.Windows.Forms.FolderBrowserDialog
            {
                Description = Services.Loc.T("lbl.downloadFolder"),
                UseDescriptionForTitle = true,
                SelectedPath = Services.SettingsService.Current.ResolvedDownloadFolder
            };

            if (dialog.ShowDialog() == System.Windows.Forms.DialogResult.OK)
            {
                Services.SettingsService.Current.DownloadFolder = dialog.SelectedPath;
                Services.SettingsService.Save();
                ViewModel?.RefreshAll();
            }
        }
    }
}
