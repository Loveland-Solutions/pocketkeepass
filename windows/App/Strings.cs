using Microsoft.Windows.ApplicationModel.Resources;
using PocketKeePass.Core;

namespace PocketKeePass;

/// <summary>
/// The app's strings in the user's language, from Strings/&lt;language&gt;/Resources.resw. XAML
/// elements get theirs through x:Uid; code reads them here.
/// </summary>
internal static class Strings
{
    private static readonly ResourceLoader Loader = new();

    public static string Get(string key) => Loader.GetString(key);

    public static string Format(string key, params object[] args) => string.Format(Get(key), args);

    /// <summary>
    /// An engine error in the app's language, as Android's EngineMessages.kt does. Errors with
    /// fixed wording are translated; those that carry details from the engine (a damaged file's
    /// reason, an unsupported version) keep the engine's English sentence.
    /// </summary>
    public static string UserMessage(this KeePassException error) => error switch
    {
        KeePassException.WrongKey => Get("ErrorWrongKey"),
        KeePassException.InvalidKeyFile => Get("ErrorInvalidKeyFile"),
        KeePassException.NotKeePass => Get("ErrorNotKeePass"),
        KeePassException.NeedsUpgrade => Get("ErrorNeedsUpgrade"),
        KeePassException.Random => Get("ErrorRandom"),
        KeePassException.SaveCheckFailed => Get("ErrorSaveCheckFailed"),
        KeePassException.Locked => Get("ErrorLocked"),
        KeePassException.SettingsChanged => Get("SyncSettingsChanged"),
        KeePassException.NotFound => Get("ErrorNotFound"),
        KeePassException.PasskeyExists => Get("ErrorPasskeyExists"),
        _ => error.Message,
    };
}
