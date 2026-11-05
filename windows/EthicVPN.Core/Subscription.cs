using System.Net.Http.Headers;
using System.Text;

namespace EthicVPN.Core;

/// <summary>Traffic and expiry the panel reports in the <c>subscription-userinfo</c> header.</summary>
public sealed record SubscriptionInfo
{
    public string Title { get; init; } = "";
    public long Upload { get; init; }
    public long Download { get; init; }
    public long Total { get; init; }
    /// <summary>Unix seconds; 0 means no expiry.</summary>
    public long Expire { get; init; }
    public long UpdatedAt { get; init; }
    /// <summary>A message from the panel (Remnawave's <c>announce</c> header), shown on the subscription card.</summary>
    public string Announce { get; init; } = "";
}

public sealed record SubscriptionResult(List<ServerProfile> Profiles, SubscriptionInfo Info);

public static class Subscription
{
    public const string UserAgent = "EthicVPN/1.0";

    /// <summary>The closed service: subscriptions are taken from this host only (any port and path, HTTPS).</summary>
    public const string AllowedHost = "ethicvpn.duckdns.org";
    public const string NotAllowed = "Принимаются только ссылки подписки EthicVPN — https://ethicvpn.duckdns.org/…";

    /// <summary>Whether <paramref name="url"/> is an EthicVPN subscription: https://ethicvpn.duckdns.org with any port and path.</summary>
    public static bool IsAllowed(string url) =>
        Uri.TryCreate(url.Trim(), UriKind.Absolute, out var u)
        && u.Scheme == Uri.UriSchemeHttps
        && string.Equals(u.Host, AllowedHost, StringComparison.OrdinalIgnoreCase);

    /// <param name="headers">Device headers (<c>x-hwid</c> and friends) for panels that bind keys to devices.</param>
    public static async Task<SubscriptionResult> FetchAsync(HttpClient http, string url, IReadOnlyDictionary<string, string> headers)
    {
        if (!IsAllowed(url)) throw new InvalidOperationException(NotAllowed);
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.TryAddWithoutValidation("User-Agent", UserAgent);
        request.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("*/*"));
        foreach (var (name, value) in headers) request.Headers.TryAddWithoutValidation(name, value);
        using var response = await http.SendAsync(request);
        var body = await response.Content.ReadAsStringAsync();
        var profiles = Check((int)response.StatusCode, name => Header(response, name), body);
        var info = ParseUserInfo(Header(response, "subscription-userinfo")) with
        {
            Title = DecodeTitle(Header(response, "profile-title")),
            UpdatedAt = DateTimeOffset.UtcNow.ToUnixTimeSeconds(),
            Announce = DecodeTitle(Header(response, "announce")),
        };
        return new SubscriptionResult(profiles, info);
    }

    /// <summary>
    /// The servers in a panel's answer, or an error a person understands: Remnawave's device limit
    /// (<c>x-hwid-max-devices-reached</c>, with its <c>announce</c> message), a missing device id,
    /// an HTTP error, or a web page where the subscription should be.
    /// </summary>
    public static List<ServerProfile> Check(int status, Func<string, string?> header, string body)
    {
        if (header("x-hwid-max-devices-reached") == "true")
        {
            var announce = DecodeTitle(header("announce"));
            throw new InvalidOperationException("В подписке достигнут лимит устройств. Попросите администратора освободить место для этого устройства"
                + (announce.Length > 0 ? ". " + announce : ""));
        }
        if (header("x-hwid-not-supported") == "true") throw new InvalidOperationException("Панель не получила идентификатор устройства");
        if (status is < 200 or > 299) throw new InvalidOperationException($"HTTP {status}");
        if (body.TrimStart().StartsWith('<'))
            throw new InvalidOperationException("По ссылке открывается страница, а не подписка. Попросите ссылку подписки ещё раз");
        var profiles = ParseLinks(body);
        if (profiles.Count == 0) throw new InvalidOperationException("В подписке нет серверов VLESS или Trojan");
        return profiles;
    }

    private static string? Header(HttpResponseMessage r, string name) =>
        r.Headers.TryGetValues(name, out var v) ? v.FirstOrDefault()
        : r.Content.Headers.TryGetValues(name, out var c) ? c.FirstOrDefault() : null;

    /// <summary>Accepts plain text or base64 with one share link per line.</summary>
    public static List<ServerProfile> ParseLinks(string body)
    {
        var text = body.Trim();
        var plain = text.Contains("://") ? text : DecodeBase64(text) ?? text;
        return plain.Split('\n').Select(ServerProfile.Parse).OfType<ServerProfile>().ToList();
    }

    public static SubscriptionInfo ParseUserInfo(string? header)
    {
        if (string.IsNullOrWhiteSpace(header)) return new SubscriptionInfo();
        var values = new Dictionary<string, long>();
        foreach (var part in header.Split(';'))
        {
            var kv = part.Split('=', 2);
            if (kv.Length == 2)
                values[kv[0].Trim().ToLowerInvariant()] = double.TryParse(kv[1].Trim(), System.Globalization.NumberStyles.Float,
                    System.Globalization.CultureInfo.InvariantCulture, out var d) ? (long)d : 0;
        }
        long V(string k) => values.TryGetValue(k, out var v) ? v : 0;
        return new SubscriptionInfo { Upload = V("upload"), Download = V("download"), Total = V("total"), Expire = V("expire") };
    }

    public static string DecodeTitle(string? header)
    {
        if (string.IsNullOrWhiteSpace(header)) return "";
        return header.StartsWith("base64:") ? DecodeBase64(header["base64:".Length..]) ?? "" : header;
    }

    public static string? DecodeBase64(string s)
    {
        var clean = new string(s.Where(c => !char.IsWhiteSpace(c)).ToArray()).Replace('-', '+').Replace('_', '/');
        clean += new string('=', (4 - clean.Length % 4) % 4);
        try { return Encoding.UTF8.GetString(Convert.FromBase64String(clean)); } catch { return null; }
    }
}
