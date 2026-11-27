using System.Runtime.InteropServices;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Media.Animation;
using PocketKeePass.Browse;
using PocketKeePass.Unlock;
using Windows.Graphics;
using WinRT.Interop;

namespace PocketKeePass;

public sealed partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        ExcludeFromCapture();
        AppWindow.Resize(new SizeInt32(1000, 720));
        AppWindow.SetIcon("Assets/Square44x44Logo.targetsize-32_altform-unplated.png");

        Session.Shared.StateChanged += ShowScreen;
        // Closing the window locks the database, and an unlock still running is locked as soon as
        // it finishes (Session.Start).
        Closed += (_, _) =>
        {
            Session.Shared.StateChanged -= ShowScreen;
            Session.Shared.Close();
        };
        ShowScreen();
    }

    /// <summary>
    /// The screen for the session's state. Each visit makes a new page, so nothing from an
    /// unlocked database (lists, a search) outlives it in a cached page.
    /// </summary>
    private void ShowScreen()
    {
        var screen = Session.Shared.Database == null ? typeof(UnlockPage) : typeof(BrowsePage);
        Screens.Navigate(screen, null, new SuppressNavigationTransitionInfo());
        Screens.BackStack.Clear();
    }

    /// <summary>
    /// Keeps the window out of screenshots, screen recordings, and screen sharing, like Android's
    /// FLAG_SECURE and the Mac app's sharingType = .none. Windows 10 2004 and later draw it as a
    /// black rectangle in captures.
    /// </summary>
    private void ExcludeFromCapture()
    {
        const uint WdaExcludeFromCapture = 0x11;
        SetWindowDisplayAffinity(WindowNative.GetWindowHandle(this), WdaExcludeFromCapture);
    }

    [DllImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowDisplayAffinity(IntPtr hwnd, uint affinity);
}
