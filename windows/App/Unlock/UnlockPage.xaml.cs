using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Navigation;
using Microsoft.Windows.Storage.Pickers;
using PocketKeePass.Core;
using PocketKeePass.Files;
using PocketKeePass.Security;
using Windows.System;

namespace PocketKeePass.Unlock;

/// <summary>
/// The welcome screen (no database chosen yet) and the unlock screen, as Android's UnlockScreen:
/// the remembered database, its master password and/or key file, and Unlock.
/// </summary>
public sealed partial class UnlockPage : Page
{
    private bool unlocking;

    public UnlockPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        Refresh();
    }

    /// <summary>Shows the welcome or the unlock panel for the remembered choices.</summary>
    private void Refresh()
    {
        var database = RecentDatabase.DatabasePath;
        WelcomePanel.Visibility = database == null ? Visibility.Visible : Visibility.Collapsed;
        UnlockPanel.Visibility = database == null ? Visibility.Collapsed : Visibility.Visible;
        if (database == null) return;

        DatabaseName.Text = Path.GetFileName(database);
        DatabaseFolder.Text = Path.GetDirectoryName(database) ?? "";
        var keyFile = RecentDatabase.KeyFilePath;
        KeyFileRow.Visibility = keyFile == null ? Visibility.Collapsed : Visibility.Visible;
        AddKeyFileButton.Visibility = keyFile == null ? Visibility.Visible : Visibility.Collapsed;
        KeyFileName.Text = keyFile == null ? "" : Strings.Format("KeyFile", Path.GetFileName(keyFile));
        // Once the panel is laid out, so the focus lands.
        DispatcherQueue.TryEnqueue(() => PasswordBox.Focus(FocusState.Programmatic));
    }

    private async void OpenDatabase_Click(object sender, RoutedEventArgs e)
    {
        var path = await Pick(".kdbx");
        if (path == null) return;
        RecentDatabase.SetDatabase(path);
        ClearPassword();
        ShowError(null);
        Refresh();
    }

    private async void AddKeyFile_Click(object sender, RoutedEventArgs e)
    {
        // A key file can be any file at all.
        var path = await Pick(null);
        if (path == null) return;
        RecentDatabase.SetKeyFile(path);
        ShowError(null);
        Refresh();
    }

    private void RemoveKeyFile_Click(object sender, RoutedEventArgs e)
    {
        RecentDatabase.SetKeyFile(null);
        ShowError(null);
        Refresh();
    }

    /// <summary>The system's file picker; the chosen file's path, or null if cancelled.</summary>
    private static async Task<string?> Pick(string? extension)
    {
        var picker = new FileOpenPicker(App.MainWindow!.AppWindow.Id)
        {
            SuggestedStartLocation = PickerLocationId.DocumentsLibrary,
        };
        if (extension != null) picker.FileTypeFilter.Add(extension);
        // KeePass files don't always end in .kdbx.
        picker.FileTypeFilter.Add("*");
        var result = await picker.PickSingleFileAsync();
        return result?.Path;
    }

    // async void, so an unexpected exception reaches App.UnhandledException rather than vanishing
    // with a discarded task.
    private async void PasswordBox_KeyDown(object sender, KeyRoutedEventArgs e)
    {
        if (e.Key != VirtualKey.Enter) return;
        e.Handled = true;
        await Unlock();
    }

    private async void Unlock_Click(object sender, RoutedEventArgs e) => await Unlock();

    /// <summary>
    /// Reads the file and key file and unlocks off the UI thread. The password goes to the engine
    /// as UTF-8 bytes, wiped afterwards, and the box is cleared after unlocking or a wrong key.
    /// An empty password with a key file means the key file alone; an empty password without one
    /// is a real (empty) password.
    /// </summary>
    private async Task Unlock()
    {
        var databasePath = RecentDatabase.DatabasePath;
        if (unlocking || databasePath == null) return;
        var keyFilePath = RecentDatabase.KeyFilePath;
        SetUnlocking(true);
        ShowError(null);

        var typed = PasswordBox.Password;
        byte[]? password = typed.Length == 0 && keyFilePath != null ? null : Secrets.Utf8(typed);
        byte[]? keyFile = null;
        try
        {
            var database = await Task.Run(() =>
            {
                var file = Read(databasePath, "CantReadDatabase");
                if (keyFilePath != null) keyFile = Read(keyFilePath, "CantReadKeyFile");
                return UnlockedDatabase.Open(file, password, keyFile);
            });
            ClearPassword();
            // Session locks it at once if the window closed meanwhile.
            Session.Shared.Start(database, databasePath);
        }
        catch (KeePassException.WrongKey error)
        {
            ClearPassword();
            ShowError(error.UserMessage());
        }
        catch (KeePassException error)
        {
            ShowError(error.UserMessage());
        }
        catch (CantReadException error)
        {
            ShowError(Strings.Get(error.MessageKey));
        }
        finally
        {
            Secrets.Wipe(password);
            Secrets.Wipe(keyFile);
            SetUnlocking(false);
        }
    }

    /// <summary>A whole file, or <see cref="CantReadException"/> with the message to show.</summary>
    private static byte[] Read(string path, string messageKey)
    {
        try
        {
            return File.ReadAllBytes(path);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or System.Security.SecurityException)
        {
            throw new CantReadException(messageKey);
        }
    }

    private sealed class CantReadException(string messageKey) : Exception
    {
        public string MessageKey { get; } = messageKey;
    }

    private void SetUnlocking(bool value)
    {
        unlocking = value;
        UnlockButton.Visibility = value ? Visibility.Collapsed : Visibility.Visible;
        UnlockingPanel.Visibility = value ? Visibility.Visible : Visibility.Collapsed;
        PasswordBox.IsEnabled = !value;
        ChangeDatabaseButton.IsEnabled = !value;
        AddKeyFileButton.IsEnabled = !value;
        RemoveKeyFileButton.IsEnabled = !value;
        if (!value && UnlockPanel.Visibility == Visibility.Visible)
        {
            PasswordBox.Focus(FocusState.Programmatic);
        }
    }

    private void ClearPassword() => PasswordBox.Password = "";

    private void ShowError(string? message)
    {
        ErrorBar.Message = message ?? "";
        ErrorBar.IsOpen = message != null;
    }

    private async void About_Click(object sender, RoutedEventArgs e) => await AboutDialog.Show(XamlRoot);
}
