using System.Security.Cryptography;
using System.Text;

namespace PocketKeePass.Security;

/// <summary>
/// Secrets as bytes, for the engine. They cross the bridge as byte arrays, never strings, so
/// they can be wiped; the engine wipes its own copies.
/// </summary>
internal static class Secrets
{
    /// <summary>
    /// The password as UTF-8 bytes, the encoding KeePass uses. The string itself can't be wiped
    /// (WinUI's PasswordBox only gives one), so the caller clears the box as soon as it can.
    /// </summary>
    public static byte[] Utf8(string password) => Encoding.UTF8.GetBytes(password);

    public static void Wipe(byte[]? secret)
    {
        if (secret != null) CryptographicOperations.ZeroMemory(secret);
    }
}
