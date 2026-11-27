using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using PocketKeePass.Core;

namespace PocketKeePass;

/// <summary>
/// The credits, as the Android settings' About section: written with Claude, KeePass and Dominik
/// Reichl for the format, independent of the KeePass project, and the license.
/// </summary>
internal static class AboutDialog
{
    public static async Task Show(XamlRoot root)
    {
        var text = new StackPanel { Spacing = 12, MaxWidth = 420 };
        foreach (var key in new[] { "AboutKeePass", "AboutClaude", "AboutLicense" })
        {
            text.Children.Add(new TextBlock { Text = Strings.Get(key), TextWrapping = TextWrapping.Wrap });
        }
        text.Children.Add(new TextBlock
        {
            Text = $"PocketKeePass {Package()} · {Strings.Format("EngineVersion", PocketkeepassCoreMethods.EngineVersion())}",
            Style = (Style)Application.Current.Resources["CaptionTextBlockStyle"],
            IsTextSelectionEnabled = true,
        });
        var dialog = new ContentDialog
        {
            XamlRoot = root,
            Title = Strings.Get("About"),
            Content = text,
            CloseButtonText = Strings.Get("Close"),
            DefaultButton = ContentDialogButton.Close,
        };
        await dialog.ShowAsync();
    }

    private static string Package()
    {
        var v = Windows.ApplicationModel.Package.Current.Id.Version;
        return $"{v.Major}.{v.Minor}.{v.Build}";
    }
}
