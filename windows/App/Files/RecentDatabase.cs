using Windows.Foundation.Collections;
using Windows.Storage;

namespace PocketKeePass.Files;

/// <summary>
/// The database and key file the user last chose, remembered across launches in the package's
/// own settings (ApplicationData, removed with the app). Locations only, never anything secret,
/// as Android's RecentDatabase.kt.
///
/// A picked file is remembered by its path: the app runs with full trust, so the path is all it
/// needs to read and write the file again, with no grant to keep (unlike Android and Apple).
/// </summary>
internal static class RecentDatabase
{
    private const string DatabaseKey = "database_path";
    private const string KeyFileKey = "key_file_path";

    private static IPropertySet Values => ApplicationData.Current.LocalSettings.Values;

    public static string? DatabasePath => Values[DatabaseKey] as string;

    public static string? KeyFilePath => Values[KeyFileKey] as string;

    /// <summary>
    /// A newly chosen database. A key file chosen for the previous one isn't carried over.
    /// </summary>
    public static void SetDatabase(string path)
    {
        if (string.Equals(path, DatabasePath, StringComparison.OrdinalIgnoreCase)) return;
        Values[DatabaseKey] = path;
        Values.Remove(KeyFileKey);
    }

    public static void SetKeyFile(string? path)
    {
        if (path == null) Values.Remove(KeyFileKey);
        else Values[KeyFileKey] = path;
    }
}
