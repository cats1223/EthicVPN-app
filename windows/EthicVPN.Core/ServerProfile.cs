using System.Text.Json.Serialization;

namespace EthicVPN.Core;

/// <summary>
/// A server parsed from a share link. Supported:
/// vless://&lt;uuid&gt;@&lt;host&gt;:&lt;port&gt;?type=tcp&amp;security=reality&amp;pbk=...#Name and
/// trojan://&lt;password&gt;@&lt;host&gt;:&lt;port&gt;?security=tls&amp;sni=...#Name (TLS unless told otherwise).
/// </summary>
public sealed record ServerProfile
{
    public const string Vless = "vless";
    public const string Trojan = "trojan";

    public string Id { get; init; } = Guid.NewGuid().ToString();
    public string Name { get; init; } = "";
    public string Address { get; init; } = "";
    public int Port { get; init; }
    /// <summary>"vless" or "trojan".</summary>
    public string Protocol { get; init; } = Vless;
    public string Uuid { get; init; } = "";
    /// <summary>Trojan password.</summary>
    public string Password { get; init; } = "";
    public string Encryption { get; init; } = "none";
    public string Flow { get; init; } = "";
    public string Network { get; init; } = "tcp";
    public string Security { get; init; } = "none";
    public string Sni { get; init; } = "";
    public string Fingerprint { get; init; } = "";
    public string PublicKey { get; init; } = "";
    public string ShortId { get; init; } = "";
    public string SpiderX { get; init; } = "";
    public string Mldsa65Verify { get; init; } = "";
    public List<string> Alpn { get; init; } = new();
    public string PinnedPeerCertSha256 { get; init; } = "";
    public string VerifyPeerCertByName { get; init; } = "";
    public string EchConfigList { get; init; } = "";
    public string Host { get; init; } = "";
    public string Path { get; init; } = "";
    public string ServiceName { get; init; } = "";
    public string Authority { get; init; } = "";
    public string Mode { get; init; } = "";
    public string HeaderType { get; init; } = "";
    public string Extra { get; init; } = "";
    /// <summary>The original link, kept so the profile can be shared or re-parsed.</summary>
    public string Link { get; init; } = "";

    /// <summary>Connection parameters without the uuid, passwords and keys, safe to show in the log.</summary>
    [JsonIgnore]
    public string Summary
    {
        get
        {
            // VLESS encryption strings carry a key after the first dot; only the scheme is shown.
            var enc = Protocol == Vless ? Encryption.Split('.')[0] : "";
            var parts = new List<string> { Protocol, $"{Address}:{Port}", $"type={Network}", $"security={Security}" };
            foreach (var (k, v) in new[]
            {
                ("enc", enc), ("sni", Sni), ("fp", Fingerprint), ("flow", Flow), ("mode", Mode), ("spx", SpiderX),
                ("path", Path), ("host", Host), ("sid", ShortId.Length == 0 ? "" : "set"),
                ("pbk", PublicKey.Length == 0 ? "" : "set"), ("extra", Extra.Length == 0 ? "" : "set"),
            })
            {
                if (v.Length > 0) parts.Add($"{k}={v}");
            }
            return string.Join(" ", parts);
        }
    }

    private static readonly Dictionary<string, string> Schemes = new() { ["vless"] = Vless, ["trojan"] = Trojan };

    /// <summary>Whether <paramref name="text"/> starts like a server link this app can read.</summary>
    public static bool IsShareLink(string text) =>
        Schemes.Keys.Any(s => text.TrimStart().StartsWith(s + "://", StringComparison.OrdinalIgnoreCase));

    /// <summary>Returns null for anything that is not a well-formed link of a supported protocol.</summary>
    public static ServerProfile? Parse(string raw)
    {
        var text = raw.Trim();
        var sep = text.IndexOf("://", StringComparison.Ordinal);
        if (sep <= 0 || !Schemes.TryGetValue(text[..sep].ToLowerInvariant(), out var protocol)) return null;
        // Names often hold raw spaces and emoji; cut the fragment off before parsing the rest.
        var hash = text.IndexOf('#');
        var body = hash >= 0 ? text[..hash] : text;
        var fragment = hash >= 0 ? text[(hash + 1)..] : null;

        var rest = body[(sep + 3)..];
        var at = rest.LastIndexOf('@');
        if (at <= 0) return null;
        var user = Decode(rest[..at]);
        if (string.IsNullOrWhiteSpace(user)) return null;
        var hostPart = rest[(at + 1)..];
        var query = "";
        var cut = hostPart.IndexOfAny(new[] { '?', '/' });
        if (cut >= 0)
        {
            var q = hostPart.IndexOf('?');
            query = q >= 0 ? hostPart[(q + 1)..] : "";
            hostPart = hostPart[..cut];
        }
        string host;
        string portText;
        if (hostPart.StartsWith('['))
        {
            var close = hostPart.IndexOf(']');
            if (close < 0 || close + 1 >= hostPart.Length || hostPart[close + 1] != ':') return null;
            host = hostPart[1..close];
            portText = hostPart[(close + 2)..];
        }
        else
        {
            var colon = hostPart.LastIndexOf(':');
            if (colon <= 0) return null;
            host = hostPart[..colon];
            portText = hostPart[(colon + 1)..];
        }
        if (string.IsNullOrWhiteSpace(host) || !int.TryParse(portText, out var port) || port is < 1 or > 65535) return null;

        var qs = ParseQuery(query);
        var name = fragment is null ? "" : SafeDecode(fragment).Trim();
        if (name.Length == 0) name = $"{host}:{port}";
        var baseProfile = new ServerProfile { Name = name, Address = host, Port = port, Protocol = protocol, Link = text };
        return protocol == Trojan
            ? WithStream(baseProfile with { Password = user }, qs, "tls")
            : WithStream(baseProfile with { Uuid = user, Encryption = Get(qs, "encryption") is { Length: > 0 } e ? e : "none" }, qs, "none");
    }

    /// <summary>The transport and security parameters VLESS and Trojan links share.</summary>
    private static ServerProfile WithStream(ServerProfile p, Dictionary<string, string> q, string defaultSecurity)
    {
        var network = Get(q, "type").ToLowerInvariant() switch
        {
            "" or "raw" => "tcp",
            "splithttp" => "xhttp",
            var t => t,
        };
        var security = Get(q, "security").ToLowerInvariant();
        return p with
        {
            Flow = Get(q, "flow"),
            Network = network,
            Security = security.Length > 0 ? security : defaultSecurity,
            Sni = q.TryGetValue("sni", out var sni) ? sni : Get(q, "peer"),
            Fingerprint = Get(q, "fp"),
            PublicKey = Get(q, "pbk"),
            ShortId = Get(q, "sid"),
            SpiderX = Get(q, "spx"),
            Mldsa65Verify = Get(q, "pqv"),
            Alpn = Get(q, "alpn").Split(',').Select(s => s.Trim()).Where(s => s.Length > 0).ToList(),
            PinnedPeerCertSha256 = Get(q, "pcs"),
            VerifyPeerCertByName = Get(q, "vcn"),
            EchConfigList = Get(q, "ech"),
            Host = Get(q, "host"),
            Path = Get(q, "path"),
            ServiceName = Get(q, "serviceName"),
            Authority = Get(q, "authority"),
            Mode = Get(q, "mode"),
            HeaderType = Get(q, "headerType"),
            Extra = Get(q, "extra"),
        };
    }

    private static string Get(Dictionary<string, string> q, string key) => q.TryGetValue(key, out var v) ? v : "";

    private static Dictionary<string, string> ParseQuery(string raw)
    {
        var result = new Dictionary<string, string>();
        foreach (var pair in raw.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var i = pair.IndexOf('=');
            var key = Decode(i < 0 ? pair : pair[..i]);
            result.TryAdd(key, i < 0 ? "" : Decode(pair[(i + 1)..]));
        }
        return result;
    }

    // '+' stays literal: it is part of base64 values such as pbk, not a space.
    private static string Decode(string s) => Uri.UnescapeDataString(s);

    private static string SafeDecode(string s)
    {
        try { return Uri.UnescapeDataString(s); } catch { return s; }
    }
}
