using System.Runtime.InteropServices;
using Microsoft.UI.Xaml;
using PocketKeePass.Core;
using WinRT.Interop;

namespace PocketKeePass;

public sealed partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
        ExcludeFromCapture();
        // A first sign of life from the engine, until opening a database is built.
        EngineStatus.Text = $"Engine {PocketkeepassCoreMethods.EngineVersion()}";
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
