using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Net.Sockets;
using System.Reflection;
using System.Text.Json;
using System.Threading.Tasks;
using EthicVPN.Core;

namespace EthicVPN;

/// <summary>
/// The app's logic behind the page: commands from app.js come in as JSON ({cmd, ...}), state,
/// pings, locations and traffic go back as messages ({type, ...}).
/// </summary>
public sealed class Bridge
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    public static readonly string Version =
        typeof(Bridge).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "1.0.0";

    private readonly Action<string> post;
    private readonly Func<string> readClipboard;
    private readonly StoredState state = Store.Load();
    private readonly XrayService xray = new();
    private readonly HttpClient direct = new(new HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromSeconds(15) };
    // Written from pings and lookups that finish on different threads.
    private readonly ConcurrentDictionary<string, int> pings = new();
    private readonly ConcurrentDictionary<string, GeoIP.Location> serverLocations = new();
    private GeoIP.Location? home, visible;
    private long blockedToday;
    private int reconnects;

    /// <summary>The tray menu follows the status.</summary>
    public event Action? StatusChanged;
    /// <summary>Tunnel mode without admin rights: the window restarts the app elevated.</summary>
    public event Action? NeedsElevation;

    public VpnStatus Status => xray.Status;
    public bool Connected => xray.Status == VpnStatus.Connected;

    public Bridge(Action<string> post, Func<string> readClipboard)
    {
        this.post = post;
        this.readClipboard = readClipboard;
        XrayService.KillLeftovers();
        // A crash may have left our proxy set with nothing behind it.
        SystemProxy.Disable(new XrayOptions().HttpPort, state);
        xray.StatusChanged += OnStatusChanged;
        xray.Traffic += (down, up, session, blocked) =>
        {
            blockedToday += blocked;
            Post(new { type = "traffic", down, up, session, blocked = blockedToday });
        };
    }

    public async void OnMessage(string raw)
    {
        try
        {
            using var doc = JsonDocument.Parse(raw);
            var m = doc.RootElement;
            string Str(string k) => m.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString()! : "";
            switch (Str("cmd"))
            {
                case "ready":
                    PushState();
                    PushPings();
                    _ = RefreshLocationsAsync();
                    _ = PingAllAsync();
                    _ = CheckUpdateAsync(false);
                    if (state.SubscriptionUrl.Length > 0) _ = RefreshSubscriptionAsync(silent: true);
                    break;
                case "connect": await ConnectAsync(); break;
                case "disconnect": Disconnect(); break;
                case "select": await SelectAsync(Str("id")); break;
                case "add": await AddAsync(Str("text")); break;
                case "remove":
                    state.Profiles.RemoveAll(p => p.Id == Str("id"));
                    Save();
                    break;
                case "refresh": await RefreshSubscriptionAsync(silent: false); break;
                case "removeSubscription":
                    state.Profiles.RemoveAll(p => state.SubscriptionIds.Contains(p.Id));
                    state.SubscriptionIds.Clear();
                    state.SubscriptionUrl = "";
                    state.SubscriptionInfo = null;
                    Save();
                    break;
                case "pingAll": await PingAllAsync(); break;
                case "setSetting": await SetSettingAsync(Str("key"), m.GetProperty("value")); break;
                case "checkUpdate": await CheckUpdateAsync(true); break;
                case "readClipboard": Post(new { type = "clipboard", text = readClipboard() }); break;
                case "openUrl":
                    var url = Str("url");
                    if (url.StartsWith("https://")) Process.Start(new ProcessStartInfo(url) { UseShellExecute = true });
                    break;
            }
        }
        catch (Exception e)
        {
            Toast(e.Message);
        }
    }

    // ---------- Connection ----------

    public async Task ConnectAsync()
    {
        var s = state.Settings;
        if (s.Mode == "tun" && !Elevation.IsAdmin)
        {
            NeedsElevation?.Invoke();
            return;
        }
        if (s.AutoFastest && state.Profiles.Count > 1)
        {
            await PingAllAsync();
            var best = state.Profiles.Where(p => pings.TryGetValue(p.Id, out var ms) && ms > 0).OrderBy(p => pings[p.Id]).FirstOrDefault();
            if (best is not null) state.SelectedId = best.Id;
        }
        var profile = state.Selected;
        if (profile is null)
        {
            Toast("Сначала добавьте сервер");
            return;
        }
        reconnects = 0;
        await StartAsync(profile);
    }

    private async Task StartAsync(ServerProfile profile)
    {
        var s = state.Settings;
        var options = new XrayOptions
        {
            Mode = s.Mode == "tun" ? TunnelMode.Tun : TunnelMode.Proxy,
            RussiaDirect = s.RussiaDirect,
            BlockAds = s.BlockAds,
            KillSwitch = s.KillSwitch,
            Dns = XrayConfig.UpstreamDns(s.Dns, s.CustomDns),
            DirectSites = s.DirectSites,
            ProxySites = s.ProxySites,
        };
        await xray.StartAsync(profile, options);
        if (xray.Status == VpnStatus.Connected && options.Mode == TunnelMode.Proxy)
        {
            SystemProxy.Enable(options.HttpPort, state);
            Save();
        }
    }

    public void Disconnect()
    {
        reconnects = int.MaxValue;
        var port = xray.HttpPort;
        xray.Stop();
        SystemProxy.Disable(port, state);
    }

    /// <summary>Called when the app exits: nothing may stay pointed at a stopped proxy.</summary>
    public void Shutdown() => Disconnect();

    private async void OnStatusChanged(bool crashed)
    {
        if (crashed)
        {
            SystemProxy.Disable(xray.HttpPort, state);
            // Up to five quick retries when Xray stops on its own.
            if (state.Settings.AutoReconnect && reconnects < 5 && state.Selected is { } p)
            {
                reconnects++;
                PushState();
                await Task.Delay(3000);
                if (reconnects <= 5) await StartAsync(p);
                return;
            }
        }
        PushState();
        StatusChanged?.Invoke();
        if (xray.Status is VpnStatus.Connected or VpnStatus.Disconnected) _ = RefreshLocationsAsync();
    }

    private async Task SelectAsync(string id)
    {
        if (state.Profiles.All(p => p.Id != id) || id == state.SelectedId) return;
        state.SelectedId = id;
        Save();
        _ = LocateServerAsync(state.Selected!);
        if (Connected) await StartAsync(state.Selected!);
    }

    // ---------- Servers ----------

    private async Task AddAsync(string text)
    {
        text = text.Trim();
        // A closed service: only EthicVPN subscription links, no single keys or other panels.
        if (!Subscription.IsAllowed(text))
        {
            Toast(Subscription.NotAllowed);
            return;
        }
        state.SubscriptionUrl = text;
        await RefreshSubscriptionAsync(silent: false);
    }

    private async Task RefreshSubscriptionAsync(bool silent)
    {
        if (state.SubscriptionUrl.Length == 0) return;
        try
        {
            var headers = new Dictionary<string, string>
            {
                ["x-hwid"] = state.Hwid,
                ["x-device-os"] = "Windows",
                ["x-ver-os"] = Environment.OSVersion.Version.ToString(),
                ["x-device-model"] = "Windows PC",
            };
            var result = await Subscription.FetchAsync(direct, state.SubscriptionUrl, headers);
            var selectedLink = state.Selected?.Link;
            state.Profiles.RemoveAll(p => state.SubscriptionIds.Contains(p.Id));
            state.Profiles.InsertRange(0, result.Profiles);
            state.SubscriptionIds = result.Profiles.Select(p => p.Id).ToList();
            state.SubscriptionInfo = result.Info;
            // Keep the same server selected across refreshes, matched by its link.
            state.SelectedId = state.Profiles.Find(p => p.Link == selectedLink)?.Id ?? state.SelectedId;
            if (state.Profiles.All(p => p.Id != state.SelectedId)) state.SelectedId = state.Profiles.FirstOrDefault()?.Id;
            Save();
            if (!silent) Toast("Подписка обновлена");
            _ = PingAllAsync();
            foreach (var p in result.Profiles) _ = LocateServerAsync(p);
        }
        catch (Exception e)
        {
            if (!silent) Toast("Ошибка подписки: " + e.Message);
        }
    }

    /// <summary>TCP connect time to each server, the same "ping" the phone apps show.</summary>
    private async Task PingAllAsync()
    {
        var tasks = state.Profiles.Select(async p =>
        {
            var sw = Stopwatch.StartNew();
            try
            {
                using var socket = new Socket(SocketType.Stream, ProtocolType.Tcp);
                using var cts = new System.Threading.CancellationTokenSource(3000);
                await socket.ConnectAsync(p.Address, p.Port, cts.Token);
                pings[p.Id] = Math.Max(1, (int)sw.ElapsedMilliseconds);
            }
            catch (Exception)
            {
                pings[p.Id] = -1;
            }
        });
        await Task.WhenAll(tasks);
        PushPings();
    }

    // ---------- Locations ----------

    private async Task RefreshLocationsAsync()
    {
        if (!Connected)
        {
            home = await GeoIP.LookupAsync(direct) ?? home;
            visible = null;
        }
        else
        {
            // Through the VPN: in proxy mode this client goes to Xray's HTTP inbound.
            using var viaVpn = new HttpClient(xray.Mode == TunnelMode.Proxy
                ? new HttpClientHandler { Proxy = new WebProxy($"http://127.0.0.1:{xray.HttpPort}"), UseProxy = true }
                : new HttpClientHandler { UseProxy = false }) { Timeout = TimeSpan.FromSeconds(10) };
            visible = await GeoIP.LookupAsync(viaVpn);
            if (visible is null && state.Selected is { } p && serverLocations.TryGetValue(p.Address, out var loc)) visible = loc;
        }
        foreach (var p in state.Profiles.Where(p => !serverLocations.ContainsKey(p.Address)).Take(10)) await LocateServerAsync(p);
        PushLocations();
    }

    private async Task LocateServerAsync(ServerProfile p)
    {
        if (serverLocations.ContainsKey(p.Address)) return;
        try
        {
            var ip = IPAddress.TryParse(p.Address, out var a) ? a : (await Dns.GetHostAddressesAsync(p.Address)).FirstOrDefault(x => x.AddressFamily == AddressFamily.InterNetwork);
            if (ip is null) return;
            if (await GeoIP.LookupAsync(direct, ip.ToString()) is { } loc)
            {
                serverLocations[p.Address] = loc;
                PushLocations();
            }
        }
        catch (Exception) { }
    }

    // ---------- Settings and updates ----------

    private async Task SetSettingAsync(string key, JsonElement value)
    {
        var s = state.Settings;
        var prop = typeof(Settings).GetProperties().FirstOrDefault(p => string.Equals(p.Name, key, StringComparison.OrdinalIgnoreCase));
        if (prop is null) return;
        prop.SetValue(s, value.Deserialize(prop.PropertyType, Json));
        Save();
        if (key == "autostart") Elevation.SetAutostart(s.Autostart);
        // Settings that change the config take effect at once by reconnecting.
        var reconnect = new[] { "mode", "killSwitch", "russiaDirect", "blockAds", "dns", "customDns", "directSites", "proxySites" };
        if (Connected && reconnect.Contains(key, StringComparer.OrdinalIgnoreCase))
        {
            if (key == "mode") Disconnect();
            await ConnectAsync();
        }
    }

    private async Task CheckUpdateAsync(bool manual)
    {
        try
        {
            using var request = new HttpRequestMessage(HttpMethod.Get, Updates.AllUrl);
            request.Headers.TryAddWithoutValidation("User-Agent", "EthicVPN");
            request.Headers.TryAddWithoutValidation("Accept", "application/vnd.github+json");
            using var response = await direct.SendAsync(request);
            var json = await response.Content.ReadAsStringAsync();
            var release = Updates.ParseNewest(json, state.Settings.BetaUpdates);
            if (release is not null && Updates.IsNewer(release.Version, Version))
                Post(new { type = "update", version = release.Version, url = release.DownloadUrl });
            else if (manual) Toast("У вас последняя версия");
        }
        catch (Exception e)
        {
            if (manual) Toast("Не удалось проверить обновления: " + e.Message);
        }
    }

    public bool ConnectOnStart => state.Settings.ConnectOnStart;

    // ---------- Messages to the page ----------

    private void Save()
    {
        Store.Save(state);
        PushState();
    }

    public void PushState() => Post(new
    {
        type = "state",
        state = new
        {
            version = Version,
            isAdmin = Elevation.IsAdmin,
            status = xray.Status.ToString().ToLowerInvariant(),
            error = xray.Error,
            // Since is UTC, and unset (DateTime.MinValue) until the first connection.
            since = xray.Since == default ? 0 : new DateTimeOffset(DateTime.SpecifyKind(xray.Since, DateTimeKind.Utc)).ToUnixTimeMilliseconds(),
            selectedId = state.Selected?.Id,
            profiles = state.Profiles.Select(p => new { p.Id, p.Name, p.Address, p.Port, p.Protocol, p.Network, p.Security }),
            subscription = state.SubscriptionUrl.Length > 0 ? new { url = state.SubscriptionUrl, info = state.SubscriptionInfo } : null,
            settings = state.Settings,
        },
    });

    private void PushPings() => Post(new { type = "pings", pings });

    private void PushLocations() => Post(new
    {
        type = "locations",
        home = Loc(home),
        visible = Loc(visible),
        servers = serverLocations.ToDictionary(kv => kv.Key, kv => Loc(kv.Value)),
    });

    private static object? Loc(GeoIP.Location? l) =>
        l is null ? null : new { flag = l.Flag, place = l.Place, latitude = l.Latitude, longitude = l.Longitude };

    private void Toast(string text) => Post(new { type = "toast", text });

    private void Post(object message) => post(JsonSerializer.Serialize(message, Json));
}
