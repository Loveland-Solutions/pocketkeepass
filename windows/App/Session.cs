using PocketKeePass.Core;

namespace PocketKeePass;

/// <summary>
/// The one open database, app-wide, as Android's and Apple's Session. Screens read it from here
/// and never keep their own. Used from the UI thread.
/// </summary>
internal sealed class Session
{
    public static Session Shared { get; } = new();

    private UnlockedDatabase? database;
    private bool closed;

    /// <summary>Raised on the UI thread when a database is unlocked or locked.</summary>
    public event Action? StateChanged;

    /// <summary>The open database, or null while locked.</summary>
    public UnlockedDatabase? Database => database;

    /// <summary>The open database's file.</summary>
    public string? Path { get; private set; }

    /// <summary>
    /// Holds a database that was just unlocked from <paramref name="path"/>. Returns false, and
    /// locks it at once, if the app is closing or another database is already open: an unlock that
    /// finishes after the window closed must not leave the database in memory.
    /// </summary>
    public bool Start(UnlockedDatabase unlocked, string path)
    {
        if (closed || database != null)
        {
            Wipe(unlocked, wait: true);
            return false;
        }
        database = unlocked;
        Path = path;
        StateChanged?.Invoke();
        return true;
    }

    /// <summary>
    /// Locks the open database: the screens let go of it at once, and the engine wipes it off the
    /// UI thread (a search still running holds the engine's lock until it ends).
    /// </summary>
    public void Lock()
    {
        var unlocked = database;
        if (unlocked == null) return;
        database = null;
        Path = null;
        Wipe(unlocked, wait: closed);
        StateChanged?.Invoke();
    }

    /// <summary>
    /// The app's window is closing: locks the database, waiting for the wipe, and refuses any
    /// unlock that finishes afterwards.
    /// </summary>
    public void Close()
    {
        closed = true;
        Lock();
    }

    private static void Wipe(UnlockedDatabase unlocked, bool wait)
    {
        // Lock, not Dispose: a read still running on another thread would then fail with
        // ObjectDisposedException instead of the engine's Locked. The finalizer frees the
        // (already wiped) object.
        var wipe = Task.Run(unlocked.Lock);
        if (wait) wipe.Wait();
    }
}
