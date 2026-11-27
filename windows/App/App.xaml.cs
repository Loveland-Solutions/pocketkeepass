using Microsoft.UI.Xaml;

namespace PocketKeePass;

public partial class App : Application
{
    /// <summary>The app's one window, for the file pickers.</summary>
    public static Window? MainWindow { get; private set; }

    public App()
    {
        InitializeComponent();
#if DEBUG
        // Debug builds write crashes to LocalState\crash.log, for checking the app from the Mac.
        // Exceptions carry no secrets (the engine never puts one in a message).
        UnhandledException += (_, e) => DebugLog($"{e.Exception}");
        // Every thrown exception, not only unhandled ones: an error in XAML (a page that fails to
        // load) crashes inside the framework without reaching UnhandledException. The engine's
        // own errors (a wrong password) are expected and left out.
        AppDomain.CurrentDomain.FirstChanceException += (_, e) =>
        {
            if (e.Exception is Core.KeePassException) return;
            DebugLog($"first chance: {e.Exception.GetType()}: {e.Exception.Message}\n{Environment.StackTrace}");
        };
        DebugSettings.XamlResourceReferenceFailed += (_, e) => DebugLog(e.Message);
#endif
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
#if DEBUG
        TakeDebugChoice();
#endif
        MainWindow = new MainWindow();
        MainWindow.Activate();
    }

#if DEBUG
    private static void DebugLog(string text) => File.AppendAllText(
        Path.Combine(Windows.Storage.ApplicationData.Current.LocalFolder.Path, "crash.log"),
        $"{DateTime.Now:O} {text}\n\n");

    /// <summary>
    /// Debug builds only: a database (first line) and key file (second line, optional) chosen by
    /// writing LocalState\debug-open.txt before launching, because UI Automation can't drive the
    /// file picker in the test VM. The file is deleted once read. LocalState\debug-language.txt
    /// sets the app's language ("mn-MN", or "en-US" to go back), kept until changed.
    /// </summary>
    private static void TakeDebugChoice()
    {
        var language = Path.Combine(Windows.Storage.ApplicationData.Current.LocalFolder.Path, "debug-language.txt");
        if (File.Exists(language))
        {
            var tag = File.ReadAllText(language).Trim();
            File.Delete(language);
            // An empty tag is refused (it doesn't mean "Windows' own").
            if (tag.Length > 0) Microsoft.Windows.Globalization.ApplicationLanguages.PrimaryLanguageOverride = tag;
        }
        var file = Path.Combine(Windows.Storage.ApplicationData.Current.LocalFolder.Path, "debug-open.txt");
        if (!File.Exists(file)) return;
        var lines = File.ReadAllLines(file).Select(l => l.Trim()).ToArray();
        File.Delete(file);
        if (lines.Length == 0 || lines[0].Length == 0) return;
        Files.RecentDatabase.SetDatabase(lines[0]);
        Files.RecentDatabase.SetKeyFile(lines.Length > 1 && lines[1].Length > 0 ? lines[1] : null);
    }
#endif
}
