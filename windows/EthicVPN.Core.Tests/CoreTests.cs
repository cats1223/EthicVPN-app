using System.Text;
using System.Text.Json.Nodes;
using EthicVPN.Core;
using Xunit;

namespace EthicVPN.Core.Tests;

/// <summary>The same cases as android/.../ServerProfileTest.kt, for the Windows port.</summary>
public class CoreTests
{
    private const string Reality =
        "vless://0f6e1a3c-7c6e-4a59-9f2a-3d1a7c2b9e01@nl1.ethic.example:443" +
        "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome" +
        "&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=6ba85179e30d4fc2&spx=%2F&type=tcp" +
        "#🇳🇱 Нидерланды";

    [Fact]
    public void ParsesRealityLink()
    {
        var p = ServerProfile.Parse(Reality)!;
        Assert.Equal("0f6e1a3c-7c6e-4a59-9f2a-3d1a7c2b9e01", p.Uuid);
        Assert.Equal("nl1.ethic.example", p.Address);
        Assert.Equal(443, p.Port);
        Assert.Equal("xtls-rprx-vision", p.Flow);
        Assert.Equal("reality", p.Security);
        Assert.Equal("tcp", p.Network);
        Assert.Equal("www.microsoft.com", p.Sni);
        Assert.Equal("SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc", p.PublicKey);
        Assert.Equal("6ba85179e30d4fc2", p.ShortId);
        Assert.Equal("/", p.SpiderX);
        Assert.Equal("🇳🇱 Нидерланды", p.Name);
    }

    [Fact]
    public void ParsesIpv6AndDefaults()
    {
        var p = ServerProfile.Parse("vless://abc@[2001:db8::1]:8443?type=raw")!;
        Assert.Equal("2001:db8::1", p.Address);
        Assert.Equal("tcp", p.Network);
        Assert.Equal("none", p.Security);
        Assert.Equal("none", p.Encryption);
        Assert.Equal("2001:db8::1:8443", p.Name);
    }

    [Fact]
    public void KeepsPlusInValues() =>
        Assert.Equal("a+b/c", ServerProfile.Parse("vless://abc@h:1?security=reality&pbk=a+b/c&sid=1")!.PublicKey);

    [Fact]
    public void RejectsInvalidLinks()
    {
        Assert.Null(ServerProfile.Parse("vmess://abc"));
        Assert.Null(ServerProfile.Parse("vless://@host:443"));
        Assert.Null(ServerProfile.Parse("vless://id@host"));
        Assert.Null(ServerProfile.Parse("not a link"));
    }

    [Fact]
    public void ParsesTrojanWithTlsByDefault()
    {
        var p = ServerProfile.Parse("trojan://pass%40word@t.example:2087?sni=cdn.example&type=ws&path=%2Fws#T")!;
        Assert.Equal(ServerProfile.Trojan, p.Protocol);
        Assert.Equal("pass@word", p.Password);
        Assert.Equal("tls", p.Security);
        Assert.Equal("ws", p.Network);
        Assert.Equal("/ws", p.Path);
        var outbound = XrayConfig.Outbound(p);
        Assert.Equal("trojan", (string?)outbound["protocol"]);
        Assert.Equal("cdn.example", (string?)outbound["streamSettings"]!["tlsSettings"]!["serverName"]);
    }

    [Fact]
    public void ParsesXhttpExtra()
    {
        var p = ServerProfile.Parse("vless://id@h:443?type=xhttp&path=%2Fx&mode=stream-one&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%7D")!;
        var x = XrayConfig.Outbound(p)["streamSettings"]!["xhttpSettings"]!;
        Assert.Equal("/x", (string?)x["path"]);
        Assert.Equal("stream-one", (string?)x["mode"]);
        Assert.Equal("100-1000", (string?)x["xPaddingBytes"]);
    }

    [Fact]
    public void BuildsProxyModeConfig()
    {
        var config = JsonNode.Parse(XrayConfig.Build(ServerProfile.Parse(Reality)!, new XrayOptions { AssetDir = "C:/geo" }))!;
        var inbounds = config["inbounds"]!.AsArray();
        Assert.Equal("socks", (string?)inbounds[0]!["protocol"]);
        Assert.Equal(10808, (int)inbounds[0]!["port"]!);
        Assert.Equal("http", (string?)inbounds[1]!["protocol"]);
        Assert.Equal("127.0.0.1", (string?)inbounds[1]!["listen"]);
        Assert.Equal("C:/geo", (string?)config["env"]!["xray.location.asset"]);
        var rules = config["routing"]!["rules"]!.AsArray();
        // No TUN, so no DNS hijack rule; Russian sites go direct.
        Assert.DoesNotContain(rules, r => (string?)r!["outboundTag"] == "dns-out");
        Assert.Contains(rules, r => r!["ip"]?.ToJsonString() == "[\"geoip:ru\"]");
        Assert.Equal("127.0.0.1:10813", (string?)config["metrics"]!["listen"]);
        Assert.Equal(XrayConfig.RussianDns, (string?)config["dns"]!["servers"]![0]!["address"]);
    }

    [Fact]
    public void BuildsTunModeConfig()
    {
        var config = JsonNode.Parse(XrayConfig.Build(ServerProfile.Parse(Reality)!,
            new XrayOptions { Mode = TunnelMode.Tun, RussiaDirect = false, KillSwitch = true }))!;
        var tun = config["inbounds"]![0]!;
        Assert.Equal("tun", (string?)tun["protocol"]);
        Assert.Equal("[\"0.0.0.0/0\"]", tun["settings"]!["autoSystemRoutingTable"]!.ToJsonString());
        Assert.Equal("[\"172.19.0.2\"]", tun["settings"]!["dns"]!.ToJsonString());
        Assert.Equal("[\"dns\",\"misconfigtun\"]", tun["settings"]!["autoSystemWfpBlockLeak"]!.ToJsonString());
        var rules = config["routing"]!["rules"]!.AsArray();
        Assert.Equal("dns-out", (string?)rules[0]!["outboundTag"]);
        Assert.Null(config["env"]);
        var noKill = JsonNode.Parse(XrayConfig.Build(ServerProfile.Parse(Reality)!, new XrayOptions { Mode = TunnelMode.Tun, KillSwitch = false }))!;
        Assert.Null(noKill["inbounds"]![0]!["settings"]!["autoSystemWfpBlockLeak"]);
    }

    [Fact]
    public void UserListsComeBeforeAdBlocking()
    {
        var config = JsonNode.Parse(XrayConfig.Build(ServerProfile.Parse(Reality)!, new XrayOptions
        {
            BlockAds = true, ProxySites = new[] { "https://www.Ads.example.com/page" }, DirectSites = new[] { "*.bank.ru", "junk" },
        }))!;
        var rules = config["routing"]!["rules"]!.AsArray().Select(r => r!).ToList();
        var proxy = rules.FindIndex(r => (string?)r["outboundTag"] == "proxy");
        var block = rules.FindIndex(r => (string?)r["outboundTag"] == "block");
        Assert.True(proxy >= 0 && proxy < block);
        Assert.Equal("[\"domain:ads.example.com\"]", rules[proxy]["domain"]!.ToJsonString());
        Assert.Contains(rules, r => r["domain"]?.ToJsonString() == "[\"domain:bank.ru\"]");
    }

    [Fact]
    public void PicksUpstreamDns()
    {
        Assert.Equal(XrayConfig.UpstreamDnsDefault, XrayConfig.UpstreamDns(""));
        Assert.Equal("tcp://94.140.14.14:53", XrayConfig.UpstreamDns("adguard")[0]);
        Assert.Equal(new[] { "tcp://9.9.9.9:53" }, XrayConfig.UpstreamDns("custom", " 9.9.9.9 "));
        Assert.Null(XrayConfig.CustomDns("300.1.1.1"));
    }

    [Fact]
    public void ParsesSubscriptions()
    {
        var body = Convert.ToBase64String(Encoding.UTF8.GetBytes(Reality + "\ntrojan://p@h:443#T\nvmess://x\n"));
        Assert.Equal(2, Subscription.ParseLinks(body).Count);
        var info = Subscription.ParseUserInfo("upload=1; download=2; total=10737418240; expire=1767225600");
        Assert.Equal(10737418240, info.Total);
        Assert.Equal(1767225600, info.Expire);
    }

    [Fact]
    public void ExplainsRemnawaveAnswers()
    {
        Assert.Single(Subscription.Check(200, _ => null, Reality));
        var announce = "base64:" + Convert.ToBase64String(Encoding.UTF8.GetBytes("Лимит: 3 устройства"));
        var headers = new Dictionary<string, string> { ["x-hwid-max-devices-reached"] = "true", ["announce"] = announce };
        var e = Assert.Throws<InvalidOperationException>(() => Subscription.Check(200, n => headers.GetValueOrDefault(n), ""));
        Assert.EndsWith("Лимит: 3 устройства", e.Message);
        Assert.Contains("страница", Assert.Throws<InvalidOperationException>(() => Subscription.Check(200, _ => null, "<!doctype html>")).Message);
        Assert.Equal("HTTP 404", Assert.Throws<InvalidOperationException>(() => Subscription.Check(404, _ => null, "")).Message);
    }

    [Fact]
    public void ComparesVersions()
    {
        Assert.True(Updates.IsNewer("1.2.0", "1.2.0-beta.1"));
        Assert.True(Updates.IsNewer("1.2.0-beta.10", "1.2.0-beta.9"));
        Assert.False(Updates.IsNewer("1.2.0", "1.2.0"));
        // Phone releases (v1.2.2) are not Windows updates; betas only when asked for.
        var list = """[{"tag_name":"v1.2.2","html_url":"x"},{"tag_name":"windows-v1.0.0","html_url":"p","assets":[{"name":"EthicVPN-Setup-1.0.0.exe","browser_download_url":"z"}]},{"tag_name":"windows-v1.1.0-beta.1","html_url":"b"}]""";
        var newest = Updates.ParseNewest(list)!;
        Assert.Equal("1.0.0", newest.Version);
        Assert.Equal("z", newest.DownloadUrl);
        Assert.Equal("1.1.0-beta.1", Updates.ParseNewest(list, includeBeta: true)!.Version);
        Assert.Null(Updates.ParseNewest("""[{"tag_name":"v1.2.2","html_url":"x"}]"""));
    }

    [Fact]
    public void ReadsGeoIp()
    {
        var l = GeoIP.Parse("""{"ip":"1.2.3.4","success":true,"country":"Netherlands","country_code":"NL","city":"Amsterdam","latitude":52.37,"longitude":4.89}""")!;
        Assert.Equal("🇳🇱", l.Flag);
        Assert.Equal("Amsterdam, Netherlands", l.Place);
        Assert.Null(GeoIP.Parse("""{"success":false}"""));
    }

    [Fact]
    public void CountsBlockedAds() =>
        Assert.Equal(2, XrayConfig.CountBlocked("a [tun-in -> block]\nb [socks-in -> direct]\nc [http-in -> block]\n"));
}
