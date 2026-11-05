using System.Text.Json;

namespace EthicVPN.Core;

/// <summary>
/// Checks GitHub for a newer Windows release. The Windows app ships under its own tags
/// (windows-v1.0.0), next to the phone apps' v1.2.0 releases, so only those tags count.
/// </summary>
public static class Updates
{
    public const string AllUrl = "https://api.github.com/repos/cats1223/EthicVPN-app/releases?per_page=100";
    public const string TagPrefix = "windows-v";

    /// <summary><see cref="DownloadUrl"/> is the setup file when the release has one, otherwise the release page.</summary>
    public sealed record Release(string Version, string PageUrl, string DownloadUrl);

    /// <summary>
    /// The newest published Windows release in a list, by version (GitHub may sort tags as text);
    /// betas only when <paramref name="includeBeta"/>.
    /// </summary>
    public static Release? ParseNewest(string json, bool includeBeta = false)
    {
        using var doc = JsonDocument.Parse(json);
        Release? best = null;
        foreach (var o in doc.RootElement.EnumerateArray())
        {
            if (o.TryGetProperty("draft", out var d) && d.ValueKind == JsonValueKind.True) continue;
            if (Parse(o) is not { } r || (!includeBeta && IsBeta(r.Version))) continue;
            if (best is null || IsNewer(r.Version, best.Version)) best = r;
        }
        return best;
    }

    private static Release? Parse(JsonElement o)
    {
        if (!o.TryGetProperty("tag_name", out var tag) || !o.TryGetProperty("html_url", out var page)) return null;
        var name = tag.GetString() ?? "";
        if (!name.StartsWith(TagPrefix)) return null;
        var setup = o.TryGetProperty("assets", out var assets)
            ? assets.EnumerateArray()
                .Where(a => a.GetProperty("name").GetString()?.EndsWith(".exe") == true)
                .Select(a => a.GetProperty("browser_download_url").GetString())
                .FirstOrDefault()
            : null;
        var pageUrl = page.GetString() ?? "";
        return new Release(name[TagPrefix.Length..], pageUrl, setup ?? pageUrl);
    }

    /// <summary>
    /// Compares dotted versions number by number; a beta (1.2.0-beta.1) is older than its release,
    /// and betas of one version compare by their number.
    /// </summary>
    public static bool IsNewer(string latest, string current)
    {
        var a = Numbers(latest);
        var b = Numbers(current);
        for (var i = 0; i < Math.Max(a.Length, b.Length); i++)
        {
            var x = i < a.Length ? a[i] : 0;
            var y = i < b.Length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return (IsBeta(latest), IsBeta(current)) switch
        {
            (false, true) => true,
            (true, true) => BetaNumber(latest) > BetaNumber(current),
            _ => false,
        };
    }

    public static bool IsBeta(string version) => version.Contains('-');

    private static int[] Numbers(string version) =>
        version.TrimStart('v').Split('-')[0].Split('.').Select(s => int.TryParse(s, out var n) ? n : 0).ToArray();

    private static int BetaNumber(string version)
    {
        var dash = version.IndexOf('-');
        if (dash < 0) return 0;
        var digits = new string(version[(dash + 1)..].Reverse().TakeWhile(char.IsDigit).Reverse().ToArray());
        return int.TryParse(digits, out var n) ? n : 0;
    }
}
