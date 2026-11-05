using System;
using System.Collections.Generic;
using System.IO;
using System.Text.Json;
using EthicVPN.Core;

namespace EthicVPN;

/// <summary>Settings, as the page sees them (the same names as in app.js).</summary>
public sealed class Settings
{
    public string Theme { get; set; } = "dark";
    /// <summary>"proxy" or "tun".</summary>
    public string Mode { get; set; } = "proxy";
    public bool KillSwitch { get; set; } = true;
    public bool RussiaDirect { get; set; } = true;
    public bool BlockAds { get; set; }
    public string Dns { get; set; } = "";
    public string CustomDns { get; set; } = "";
    public bool AutoFastest { get; set; }
    public bool AutoReconnect { get; set; } = true;
    public bool Autostart { get; set; }
    public bool ConnectOnStart { get; set; }
    public bool BetaUpdates { get; set; }
    public List<string> DirectSites { get; set; } = new();
    public List<string> ProxySites { get; set; } = new();
}

/// <summary>Everything the app keeps, in %APPDATA%\EthicVPN\state.json.</summary>
public sealed class StoredState
{
    public List<ServerProfile> Profiles { get; set; } = new();
    /// <summary>Which profiles came from the subscription, so a refresh replaces only those.</summary>
    public List<string> SubscriptionIds { get; set; } = new();
    public string? SelectedId { get; set; }
    public string SubscriptionUrl { get; set; } = "";
    public SubscriptionInfo? SubscriptionInfo { get; set; }
    public Settings Settings { get; set; } = new();
    /// <summary>Stable per install, so a panel with per-device keys keeps seeing the same device.</summary>
    public string Hwid { get; set; } = Guid.NewGuid().ToString();
    /// <summary>The system proxy before we set ours, restored on disconnect.</summary>
    public string? SavedProxyServer { get; set; }
    public string? SavedProxyOverride { get; set; }
    public int SavedProxyEnable { get; set; }

    public ServerProfile? Selected => Profiles.Find(p => p.Id == SelectedId) ?? (Profiles.Count > 0 ? Profiles[0] : null);
}

public static class Store
{
    public static readonly string RoamingDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "EthicVPN");
    public static readonly string LocalDir = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "EthicVPN");
    private static string FilePath => Path.Combine(RoamingDir, "state.json");
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { WriteIndented = true };
    private static readonly object Lock = new();

    public static StoredState Load()
    {
        try
        {
            if (File.Exists(FilePath)) return JsonSerializer.Deserialize<StoredState>(File.ReadAllText(FilePath), Json) ?? new();
        }
        catch (Exception) { }
        return new StoredState();
    }

    public static void Save(StoredState state)
    {
        lock (Lock)
        {
            Directory.CreateDirectory(RoamingDir);
            var tmp = FilePath + ".tmp";
            File.WriteAllText(tmp, JsonSerializer.Serialize(state, Json));
            File.Move(tmp, FilePath, overwrite: true);
        }
    }
}
