using System;
using System.Diagnostics;
using System.Security.Principal;
using Microsoft.Win32;

namespace EthicVPN;

public static class Elevation
{
    public static bool IsAdmin => new WindowsPrincipal(WindowsIdentity.GetCurrent()).IsInRole(WindowsBuiltInRole.Administrator);

    /// <summary>Starts the app again with admin rights (the tunnel needs them); false if the user said no.</summary>
    public static bool RelaunchAsAdmin(string args)
    {
        try
        {
            Process.Start(new ProcessStartInfo(Environment.ProcessPath!, "--elevated " + args) { UseShellExecute = true, Verb = "runas" });
            return true;
        }
        catch (System.ComponentModel.Win32Exception)
        {
            return false; // UAC prompt cancelled
        }
    }

    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";

    /// <summary>"Start with Windows": a Run entry that opens the app in the tray.</summary>
    public static void SetAutostart(bool enabled)
    {
        using var key = Registry.CurrentUser.CreateSubKey(RunKey);
        if (enabled) key.SetValue("EthicVPN", $"\"{Environment.ProcessPath}\" --minimized");
        else key.DeleteValue("EthicVPN", false);
    }
}
