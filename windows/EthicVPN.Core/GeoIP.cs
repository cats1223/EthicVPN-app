using System.Text.Json;

namespace EthicVPN.Core;

/// <summary>
/// Where an IP address is: the computer's own address before the VPN, the server's after. Asks
/// ipwho.is and falls back to ipapi.co, since a service may refuse requests from a VPN server.
/// </summary>
public static class GeoIP
{
    public sealed record Location(string Ip, string Country, string CountryCode, string City, double Latitude, double Longitude)
    {
        public string Flag => GeoIP.Flag(CountryCode);
        public string Place => string.Join(", ", new[] { City, Country }.Where(s => s.Length > 0).Distinct());
    }

    public static IReadOnlyList<string> Urls(string? ip) => new[]
    {
        "https://ipwho.is/" + (ip ?? ""),
        "https://ipapi.co/" + (ip is null ? "" : ip + "/") + "json/",
    };

    /// <summary>Reads an ipwho.is or ipapi.co answer; null for an error or a page that is not JSON.</summary>
    public static Location? Parse(string json)
    {
        try
        {
            using var doc = JsonDocument.Parse(json);
            var o = doc.RootElement;
            if (o.TryGetProperty("success", out var ok) && ok.ValueKind == JsonValueKind.False) return null;
            if (o.TryGetProperty("error", out var err) && err.ValueKind == JsonValueKind.True) return null;
            string S(string k) => o.TryGetProperty(k, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString()! : "";
            if (!o.TryGetProperty("latitude", out var lat) || lat.ValueKind != JsonValueKind.Number) return null;
            if (!o.TryGetProperty("longitude", out var lon) || lon.ValueKind != JsonValueKind.Number) return null;
            var country = S("country_name");
            return new Location(S("ip"), country.Length > 0 ? country : S("country"), S("country_code"), S("city"), lat.GetDouble(), lon.GetDouble());
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>"NL" -> 🇳🇱; anything else -> 🌐.</summary>
    public static string Flag(string countryCode)
    {
        var cc = countryCode.ToUpperInvariant();
        if (cc.Length != 2 || !cc.All(c => c is >= 'A' and <= 'Z')) return "🌐";
        return string.Concat(cc.Select(c => char.ConvertFromUtf32(0x1F1E6 + (c - 'A'))));
    }

    public static async Task<Location?> LookupAsync(HttpClient http, string? ip = null)
    {
        foreach (var url in Urls(ip))
        {
            try
            {
                using var request = new HttpRequestMessage(HttpMethod.Get, url);
                request.Headers.TryAddWithoutValidation("User-Agent", "EthicVPN");
                using var response = await http.SendAsync(request);
                if (Parse(await response.Content.ReadAsStringAsync()) is { } location) return location;
            }
            catch (Exception e) when (e is HttpRequestException or TaskCanceledException) { }
        }
        return null;
    }
}
