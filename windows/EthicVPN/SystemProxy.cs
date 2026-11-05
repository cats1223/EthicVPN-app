using System;
using System.Runtime.InteropServices;
using Microsoft.Win32;

namespace EthicVPN;

/// <summary>The Windows (WinINET) proxy setting that browsers and most programs follow.</summary>
public static class SystemProxy
{
    private const string Key = @"Software\Microsoft\Windows\CurrentVersion\Internet Settings";
    /// <summary>Local and private addresses never go to the proxy.</summary>
    private const string Bypass = "localhost;127.*;10.*;172.16.*;172.17.*;172.18.*;172.19.*;172.20.*;172.21.*;172.22.*;172.23.*;" +
                                  "172.24.*;172.25.*;172.26.*;172.27.*;172.28.*;172.29.*;172.30.*;172.31.*;192.168.*;<local>";

    public static void Enable(int httpPort, StoredState state)
    {
        using var key = Registry.CurrentUser.CreateSubKey(Key);
        var current = key.GetValue("ProxyServer") as string;
        // Remember the user's own proxy once, not ours left over from a crash.
        if (current != Server(httpPort))
        {
            state.SavedProxyServer = current;
            state.SavedProxyOverride = key.GetValue("ProxyOverride") as string;
            state.SavedProxyEnable = key.GetValue("ProxyEnable") is int e ? e : 0;
        }
        key.SetValue("ProxyServer", Server(httpPort));
        key.SetValue("ProxyOverride", Bypass);
        key.SetValue("ProxyEnable", 1, RegistryValueKind.DWord);
        Refresh();
    }

    /// <summary>Puts back what was there before, if the proxy is still ours.</summary>
    public static void Disable(int httpPort, StoredState state)
    {
        using var key = Registry.CurrentUser.CreateSubKey(Key);
        if (key.GetValue("ProxyServer") as string != Server(httpPort)) return;
        if (string.IsNullOrEmpty(state.SavedProxyServer)) key.DeleteValue("ProxyServer", false);
        else key.SetValue("ProxyServer", state.SavedProxyServer);
        if (string.IsNullOrEmpty(state.SavedProxyOverride)) key.DeleteValue("ProxyOverride", false);
        else key.SetValue("ProxyOverride", state.SavedProxyOverride);
        key.SetValue("ProxyEnable", state.SavedProxyEnable, RegistryValueKind.DWord);
        Refresh();
    }

    private static string Server(int port) => $"127.0.0.1:{port}";

    [DllImport("wininet.dll", SetLastError = true)]
    private static extern bool InternetSetOption(IntPtr hInternet, int option, IntPtr buffer, int length);

    /// <summary>Running programs pick up the change without a restart.</summary>
    private static void Refresh()
    {
        InternetSetOption(IntPtr.Zero, 39, IntPtr.Zero, 0); // INTERNET_OPTION_SETTINGS_CHANGED
        InternetSetOption(IntPtr.Zero, 37, IntPtr.Zero, 0); // INTERNET_OPTION_REFRESH
    }
}
