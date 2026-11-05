using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace EthicVPN.Core;

/// <summary>How traffic reaches Xray on Windows.</summary>
public enum TunnelMode
{
    /// <summary>Local SOCKS and HTTP inbounds set as the Windows system proxy; no admin rights.</summary>
    Proxy,
    /// <summary>A Wintun adapter with system routes: everything goes through Xray. Needs admin rights.</summary>
    Tun,
}

public sealed record XrayOptions
{
    public TunnelMode Mode { get; init; } = TunnelMode.Proxy;
    public int SocksPort { get; init; } = 10808;
    public int HttpPort { get; init; } = 10809;
    /// <summary>Xray's metrics listener (expvar JSON with the traffic counters).</summary>
    public int MetricsPort { get; init; } = 10813;
    public bool RussiaDirect { get; init; } = true;
    public bool BlockAds { get; init; }
    /// <summary>Tun mode: block traffic that would leave outside the tunnel (DNS, IPv6).</summary>
    public bool KillSwitch { get; init; } = true;
    /// <summary>Directory with geoip.dat and geosite.dat.</summary>
    public string? AssetDir { get; init; }
    public string? LogPath { get; init; }
    public string? AccessLogPath { get; init; }
    public IReadOnlyList<string> DirectSites { get; init; } = Array.Empty<string>();
    public IReadOnlyList<string> ProxySites { get; init; } = Array.Empty<string>();
    public IReadOnlyList<string> Dns { get; init; } = XrayConfig.UpstreamDnsDefault;
}

/// <summary>Builds the Xray-core JSON for xray.exe; routing matches the phone apps.</summary>
public static class XrayConfig
{
    public const int TunMtu = 1500;
    public const string TunName = "EthicVPN";
    /// <summary>The TUN's own address and the resolver Windows is pointed at inside it.</summary>
    public const string TunGateway = "172.19.0.1/30";
    public const string TunDns = "172.19.0.2";

    /// <summary>Upstreams Xray resolves through the proxy. DoH rides a TCP stream.</summary>
    public static readonly IReadOnlyList<string> UpstreamDnsDefault = new[] { "https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query" };

    private static readonly string[] PrivateCidrs =
    {
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
        "100.64.0.0/10", "fc00::/7", "fe80::/10", "::1/128",
    };

    /// <summary>Russian sites that go past the VPN: the category-ru list and the .ru, .su and .рф zones.</summary>
    public static readonly string[] RussianDomains = { "geosite:category-ru", "domain:ru", "domain:su", "domain:xn--p1ai" };
    /// <summary>Resolver for those sites, reached directly, so they get the addresses a Russian network would.</summary>
    public const string RussianDns = "77.88.8.8";
    public static readonly string[] AdDomains = { "geosite:category-ads-all" };

    /// <summary>Upstream DNS picked in settings; AdGuard is plain DNS over TCP to its IP.</summary>
    public static IReadOnlyList<string> UpstreamDns(string choice, string custom = "") => choice switch
    {
        "cloudflare" => new[] { "https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query" },
        "google" => new[] { "https://8.8.8.8/dns-query", "https://8.8.4.4/dns-query" },
        "adguard" => new[] { "tcp://94.140.14.14:53", "tcp://94.140.15.15:53" },
        "custom" => CustomDns(custom) is { } c ? new[] { c } : UpstreamDnsDefault,
        _ => UpstreamDnsDefault,
    };

    /// <summary>An IPv4 address becomes DNS over TCP to it, an https:// URL is used as DoH; else null.</summary>
    public static string? CustomDns(string entry)
    {
        var e = entry.Trim();
        if (e.StartsWith("https://")) return e.Length > 8 && !e.Contains(' ') ? e : null;
        var parts = e.Split('.');
        var isIp = parts.Length == 4 && parts.All(p => p.Length is >= 1 and <= 3 && p.All(char.IsDigit) && int.Parse(p) <= 255);
        return isIp ? $"tcp://{e}:53" : null;
    }

    /// <summary>Connections Xray sent to the blackhole in a piece of its access log.</summary>
    public static int CountBlocked(string accessLog) => Regex.Matches(accessLog, @"-> block\]").Count;

    public static string Build(ServerProfile profile, XrayOptions o)
    {
        var tun = o.Mode == TunnelMode.Tun;
        var log = o.LogPath is null
            ? new JsonObject { ["loglevel"] = "warning" }
            : new JsonObject { ["loglevel"] = "info", ["access"] = "none", ["error"] = o.LogPath };
        if (o.AccessLogPath is not null) log["access"] = o.AccessLogPath;

        var root = new JsonObject { ["log"] = log };
        if ((o.RussiaDirect || o.BlockAds) && o.AssetDir is not null)
            root["env"] = new JsonObject { ["xray.location.asset"] = o.AssetDir };

        var dnsServers = new JsonArray();
        if (o.RussiaDirect)
            dnsServers.Add(new JsonObject { ["address"] = RussianDns, ["domains"] = Arr(RussianDomains), ["skipFallback"] = true });
        foreach (var d in o.Dns) dnsServers.Add(d);
        root["dns"] = new JsonObject { ["servers"] = dnsServers, ["queryStrategy"] = "UseIPv4" };

        var sniffing = new JsonObject { ["enabled"] = true, ["destOverride"] = Arr("http", "tls", "quic"), ["routeOnly"] = true };
        var inbounds = new JsonArray();
        if (tun)
        {
            var settings = new JsonObject
            {
                ["name"] = TunName,
                ["mtu"] = TunMtu,
                ["gateway"] = Arr(TunGateway),
                ["dns"] = Arr(TunDns),
                // Xray adds the default route and keeps its own connections on the real interface.
                ["autoSystemRoutingTable"] = Arr("0.0.0.0/0"),
            };
            // DNS only through the tunnel, and IPv6 (not routed into it) blocked rather than leaking.
            if (o.KillSwitch) settings["autoSystemWfpBlockLeak"] = Arr("dns", "misconfigtun");
            inbounds.Add(new JsonObject { ["tag"] = "tun-in", ["protocol"] = "tun", ["settings"] = settings, ["sniffing"] = sniffing });
        }
        else
        {
            inbounds.Add(new JsonObject
            {
                ["tag"] = "socks-in", ["listen"] = "127.0.0.1", ["port"] = o.SocksPort, ["protocol"] = "socks",
                ["settings"] = new JsonObject { ["udp"] = true }, ["sniffing"] = sniffing.DeepClone(),
            });
            inbounds.Add(new JsonObject
            {
                ["tag"] = "http-in", ["listen"] = "127.0.0.1", ["port"] = o.HttpPort, ["protocol"] = "http",
                ["sniffing"] = sniffing.DeepClone(),
            });
        }
        root["inbounds"] = inbounds;
        root["outbounds"] = new JsonArray
        {
            Outbound(profile),
            new JsonObject { ["tag"] = "direct", ["protocol"] = "freedom" },
            new JsonObject { ["tag"] = "block", ["protocol"] = "blackhole" },
            new JsonObject { ["tag"] = "dns-out", ["protocol"] = "dns" },
        };

        var rules = new JsonArray();
        if (tun) rules.Add(new JsonObject { ["inboundTag"] = Arr("tun-in"), ["port"] = "53", ["outboundTag"] = "dns-out" });
        rules.Add(new JsonObject { ["ip"] = Arr(PrivateCidrs), ["outboundTag"] = "direct" });
        // The user's own lists come first, so they win over ad blocking and the Russian rules.
        if (SiteRules(o.ProxySites) is { Count: > 0 } proxy) rules.Add(new JsonObject { ["domain"] = Arr(proxy), ["outboundTag"] = "proxy" });
        if (SiteRules(o.DirectSites) is { Count: > 0 } direct) rules.Add(new JsonObject { ["domain"] = Arr(direct), ["outboundTag"] = "direct" });
        if (o.BlockAds) rules.Add(new JsonObject { ["domain"] = Arr(AdDomains), ["outboundTag"] = "block" });
        if (o.RussiaDirect)
        {
            rules.Add(new JsonObject { ["domain"] = Arr(RussianDomains), ["outboundTag"] = "direct" });
            rules.Add(new JsonObject { ["ip"] = Arr("geoip:ru"), ["outboundTag"] = "direct" });
        }
        root["routing"] = new JsonObject { ["domainStrategy"] = "AsIs", ["rules"] = rules };

        // Traffic counters for the speed line, read from the metrics listener.
        root["stats"] = new JsonObject();
        root["policy"] = new JsonObject
        {
            ["system"] = new JsonObject { ["statsOutboundUplink"] = true, ["statsOutboundDownlink"] = true },
        };
        root["metrics"] = new JsonObject { ["tag"] = "metrics", ["listen"] = $"127.0.0.1:{o.MetricsPort}" };
        return root.ToJsonString(new JsonSerializerOptions(JsonSerializerOptions.Default) { WriteIndented = true });
    }

    /// <summary>"https://www.Site.com/page", "*.site.com" and "site.com" all become "domain:site.com".</summary>
    public static List<string> SiteRules(IEnumerable<string> entries) =>
        entries.Select(SiteDomain).OfType<string>().Distinct().Select(d => "domain:" + d).ToList();

    public static string? SiteDomain(string entry)
    {
        var host = entry.Trim().ToLowerInvariant();
        var scheme = host.IndexOf("://", StringComparison.Ordinal);
        if (scheme >= 0) host = host[(scheme + 3)..];
        foreach (var cut in new[] { '/', '?', ':' })
        {
            var i = host.IndexOf(cut);
            if (i >= 0) host = host[..i];
        }
        if (host.StartsWith("*.")) host = host[2..];
        if (host.StartsWith("www.")) host = host[4..];
        host = host.Trim('.');
        var ok = Regex.IsMatch(host, @"^[a-z0-9-]+(\.[a-z0-9-]+)+$") || Regex.IsMatch(host, @"^[\p{L}0-9-]+(\.[\p{L}0-9-]+)+$");
        return ok ? host : null;
    }

    public static JsonObject Outbound(ServerProfile p)
    {
        if (p.Protocol == ServerProfile.Trojan)
        {
            return new JsonObject
            {
                ["tag"] = "proxy", ["protocol"] = "trojan",
                ["settings"] = new JsonObject { ["address"] = p.Address, ["port"] = p.Port, ["password"] = p.Password },
                ["streamSettings"] = StreamSettings(p),
            };
        }
        var settings = new JsonObject { ["address"] = p.Address, ["port"] = p.Port, ["id"] = p.Uuid, ["encryption"] = p.Encryption };
        if (p.Flow.Length > 0) settings["flow"] = p.Flow;
        return new JsonObject { ["tag"] = "proxy", ["protocol"] = "vless", ["settings"] = settings, ["streamSettings"] = StreamSettings(p) };
    }

    private static JsonObject StreamSettings(ServerProfile p)
    {
        var s = new JsonObject { ["network"] = p.Network, ["security"] = p.Security };
        var serverName = p.Sni.Length > 0 ? p.Sni : p.Host;
        switch (p.Security)
        {
            case "reality":
                var r = new JsonObject
                {
                    ["serverName"] = serverName,
                    ["fingerprint"] = p.Fingerprint.Length > 0 ? p.Fingerprint : "chrome",
                    ["password"] = p.PublicKey,
                    ["shortId"] = p.ShortId,
                };
                PutIfNotEmpty(r, "spiderX", p.SpiderX);
                PutIfNotEmpty(r, "mldsa65Verify", p.Mldsa65Verify);
                s["realitySettings"] = r;
                break;
            case "tls":
                var t = new JsonObject();
                PutIfNotEmpty(t, "serverName", serverName);
                PutIfNotEmpty(t, "fingerprint", p.Fingerprint);
                if (p.Alpn.Count > 0) t["alpn"] = Arr(p.Alpn);
                PutIfNotEmpty(t, "pinnedPeerCertSha256", p.PinnedPeerCertSha256);
                PutIfNotEmpty(t, "verifyPeerCertByName", p.VerifyPeerCertByName);
                PutIfNotEmpty(t, "echConfigList", p.EchConfigList);
                s["tlsSettings"] = t;
                break;
        }
        var path = p.Path.Length > 0 ? p.Path : "/";
        switch (p.Network)
        {
            case "ws":
                var ws = new JsonObject { ["path"] = path };
                PutIfNotEmpty(ws, "host", p.Host);
                s["wsSettings"] = ws;
                break;
            case "httpupgrade":
                var hu = new JsonObject { ["path"] = path };
                PutIfNotEmpty(hu, "host", p.Host);
                s["httpupgradeSettings"] = hu;
                break;
            case "grpc":
                var g = new JsonObject { ["serviceName"] = p.ServiceName };
                PutIfNotEmpty(g, "authority", p.Authority);
                g["multiMode"] = p.Mode == "multi";
                s["grpcSettings"] = g;
                break;
            case "xhttp":
                var x = new JsonObject { ["path"] = path };
                PutIfNotEmpty(x, "host", p.Host);
                PutIfNotEmpty(x, "mode", p.Mode);
                if (p.Extra.Length > 0)
                {
                    try
                    {
                        if (JsonNode.Parse(p.Extra) is JsonObject extra)
                            foreach (var (k, v) in extra) x[k] = v?.DeepClone();
                    }
                    catch (JsonException) { }
                }
                s["xhttpSettings"] = x;
                break;
            case "tcp" when p.HeaderType == "http":
                var request = new JsonObject { ["path"] = Arr(path) };
                if (p.Host.Length > 0)
                    request["headers"] = new JsonObject { ["Host"] = Arr(p.Host.Split(',').Select(h => h.Trim())) };
                s["tcpSettings"] = new JsonObject { ["header"] = new JsonObject { ["type"] = "http", ["request"] = request } };
                break;
        }
        return s;
    }

    private static void PutIfNotEmpty(JsonObject o, string key, string value)
    {
        if (value.Length > 0) o[key] = value;
    }

    private static JsonArray Arr(params string[] items) => new(items.Select(i => (JsonNode)JsonValue.Create(i)!).ToArray());
    private static JsonArray Arr(IEnumerable<string> items) => Arr(items.ToArray());
}
