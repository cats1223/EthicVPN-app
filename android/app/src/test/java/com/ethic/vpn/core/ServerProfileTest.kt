package com.ethic.vpn.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ServerProfileTest {
    private val reality =
        "vless://0f6e1a3c-7c6e-4a59-9f2a-3d1a7c2b9e01@nl1.ethic.example:443" +
            "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome" +
            "&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=6ba85179e30d4fc2&spx=%2F&type=tcp" +
            "#🇳🇱 Нидерланды"

    @Test
    fun parsesRealityLink() {
        val p = ServerProfile.parse(reality)!!
        assertEquals("0f6e1a3c-7c6e-4a59-9f2a-3d1a7c2b9e01", p.uuid)
        assertEquals("nl1.ethic.example", p.address)
        assertEquals(443, p.port)
        assertEquals("xtls-rprx-vision", p.flow)
        assertEquals("reality", p.security)
        assertEquals("tcp", p.network)
        assertEquals("www.microsoft.com", p.sni)
        assertEquals("SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc", p.publicKey)
        assertEquals("6ba85179e30d4fc2", p.shortId)
        assertEquals("/", p.spiderX)
        assertEquals("🇳🇱 Нидерланды", p.name)
    }

    @Test
    fun parsesIpv6AndDefaults() {
        val p = ServerProfile.parse("vless://abc@[2001:db8::1]:8443?type=raw")!!
        assertEquals("2001:db8::1", p.address)
        assertEquals("tcp", p.network)
        assertEquals("none", p.security)
        assertEquals("none", p.encryption)
        assertEquals("2001:db8::1:8443", p.name)
    }

    @Test
    fun keepsPlusInValues() {
        val p = ServerProfile.parse("vless://abc@h:1?security=reality&pbk=a+b/c&sid=1")!!
        assertEquals("a+b/c", p.publicKey)
    }

    @Test
    fun rejectsInvalidLinks() {
        assertNull(ServerProfile.parse("vmess://abc"))
        assertNull(ServerProfile.parse("vless://@host:443"))
        assertNull(ServerProfile.parse("vless://id@host"))
        assertNull(ServerProfile.parse("not a link"))
    }

    @Test
    fun buildsRealityOutbound() {
        val p = ServerProfile.parse(reality)!!
        val config = JSONObject(XrayConfig.build(p, 42))
        assertEquals("42", config.getJSONObject("env").getString("xray.tun.fd"))
        assertEquals("tun", config.getJSONArray("inbounds").getJSONObject(0).getString("protocol"))

        val out = config.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", out.getString("protocol"))
        val settings = out.getJSONObject("settings")
        assertEquals("nl1.ethic.example", settings.getString("address"))
        assertEquals(443, settings.getInt("port"))
        assertEquals("xtls-rprx-vision", settings.getString("flow"))

        val stream = out.getJSONObject("streamSettings")
        assertEquals("reality", stream.getString("security"))
        val r = stream.getJSONObject("realitySettings")
        assertEquals("www.microsoft.com", r.getString("serverName"))
        assertEquals("SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc", r.getString("password"))
        assertEquals("6ba85179e30d4fc2", r.getString("shortId"))
        assertEquals("chrome", r.getString("fingerprint"))
    }

    @Test
    fun russiaDirectRoutesRussianSitesPastTheProxy() {
        val p = ServerProfile.parse(reality)!!
        val plain = JSONObject(XrayConfig.build(p, 42))
        assertFalse(plain.getJSONObject("env").has("xray.location.asset"))
        assertEquals(2, plain.getJSONObject("routing").getJSONArray("rules").length())

        val config = JSONObject(XrayConfig.build(p, 42, russiaDirect = true, assetDir = "/geo"))
        assertEquals("/geo", config.getJSONObject("env").getString("xray.location.asset"))
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        val direct = (0 until rules.length()).map { rules.getJSONObject(it) }.filter { it.getString("outboundTag") == "direct" }
        assertTrue(direct.any { it.optJSONArray("ip")?.toString() == "[\"geoip:ru\"]" })
        assertTrue(direct.any { it.optJSONArray("domain")?.toString()?.contains("domain:ru") == true })
        val dns = config.getJSONObject("dns").getJSONArray("servers")
        assertEquals(XrayConfig.RUSSIAN_DNS, dns.getJSONObject(0).getString("address"))
    }

    @Test
    fun blockAdsDropsAdDomainsAfterTheUserLists() {
        val p = ServerProfile.parse(reality)!!
        val config = JSONObject(XrayConfig.build(p, 42, assetDir = "/geo", proxySites = listOf("ads.example.com"), blockAds = true))
        assertEquals("/geo", config.getJSONObject("env").getString("xray.location.asset"))
        val rules = config.getJSONObject("routing").getJSONArray("rules").let { r -> (0 until r.length()).map { r.getJSONObject(it) } }
        val block = rules.indexOfFirst { it.getString("outboundTag") == "block" }
        assertEquals("[\"geosite:category-ads-all\"]", rules[block].getJSONArray("domain").toString())
        // A site the user listed still goes through, ad domain or not.
        assertTrue(rules.indexOfFirst { it.getString("outboundTag") == "proxy" } in 0 until block)
        assertTrue(JSONObject(XrayConfig.build(p, 42)).getJSONObject("routing").toString().indexOf("block") < 0)
    }

    @Test
    fun picksUpstreamDns() {
        assertEquals(XrayConfig.UPSTREAM_DNS, XrayConfig.upstreamDns(""))
        assertEquals("tcp://94.140.14.14:53", XrayConfig.upstreamDns("adguard").first())
        assertEquals(listOf("tcp://9.9.9.9:53"), XrayConfig.upstreamDns("custom", " 9.9.9.9 "))
        assertEquals(XrayConfig.UPSTREAM_DNS, XrayConfig.upstreamDns("custom", "dns.example"))
        assertEquals("https://9.9.9.9/dns-query", XrayConfig.customDns("https://9.9.9.9/dns-query"))
        assertEquals(null, XrayConfig.customDns("300.1.1.1"))
        val config = JSONObject(XrayConfig.build(ServerProfile.parse(reality)!!, 42, dns = XrayConfig.upstreamDns("google")))
        assertEquals("https://8.8.8.8/dns-query", config.getJSONObject("dns").getJSONArray("servers").getString(0))
    }

    @Test
    fun keepsTrafficPerDay() {
        val today = java.time.LocalDate.of(2026, 10, 25)
        val h = TrafficHistory()
        h.add(100, 10, today.minusDays(61))
        h.add(300, 30, today.minusDays(1))
        h.add(500, 50, today)
        h.add(-5, 0, today)
        val back = TrafficHistory.decode(h.encode())
        assertEquals(500L to 50L, back.total(1, today))
        assertEquals(800L to 80L, back.total(7, today))
        assertEquals(listOf(0L, 330L, 550L), back.daily(3, today).map { it.total })
        assertEquals(false, h.encode().contains("2026-08-25"))
        h.addBlocked(3, today)
        h.addBlocked(2, today.minusDays(1))
        val again = TrafficHistory.decode(h.encode())
        assertEquals(3L, again.blocked(1, today))
        assertEquals(5L, again.blocked(7, today))
        assertEquals(550L, again.daily(1, today).single().total)
        // Histories saved before the counter have three fields.
        assertEquals(0L, TrafficHistory.decode("2026-10-25:1:2").blocked(1, today))
        val log = "x accepted tcp:ads.example:443 [tun-in -> block]\ny accepted tcp:a.ru:443 [tun-in -> direct]\nz [tun-in -> block]\n"
        assertEquals(2, XrayConfig.countBlocked(log))
        val config = JSONObject(XrayConfig.build(ServerProfile.parse(reality)!!, 42, accessLogPath = "/a.log"))
        assertEquals("/a.log", config.getJSONObject("log").getString("access"))
    }

    @Test
    fun exportsForRouters() {
        val p = ServerProfile.parse(reality)!!
        val outbounds = JSONObject(XrayConfig.routerOutbounds(p)).getJSONArray("outbounds")
        assertEquals("proxy", outbounds.getJSONObject(0).getString("tag"))
        val rules = JSONObject(XrayConfig.routerRouting(russiaDirect = true, blockAds = true)).getJSONObject("routing").getJSONArray("rules")
        assertEquals("proxy", rules.getJSONObject(rules.length() - 1).getString("outboundTag"))
        assertTrue((0 until rules.length()).any { rules.getJSONObject(it).optJSONArray("ip")?.toString() == "[\"geoip:ru\"]" })
    }

    @Test
    fun explainsRemnawaveAnswers() {
        val ok = Subscription.check(200, { null }, reality)
        assertEquals(1, ok.size)
        val announce = "base64:" + Base64.getEncoder().encodeToString("Лимит: 3 устройства".toByteArray())
        val limit = runCatching {
            Subscription.check(200, { mapOf("x-hwid-max-devices-reached" to "true", "announce" to announce)[it] }, "")
        }.exceptionOrNull()!!.message!!
        assertTrue(limit.startsWith("В подписке достигнут лимит устройств"))
        assertTrue(limit.endsWith("Лимит: 3 устройства"))
        assertTrue(runCatching { Subscription.check(200, { null }, "<!doctype html><html>") }.exceptionOrNull()!!.message!!.contains("страница"))
        assertEquals("HTTP 404", runCatching { Subscription.check(404, { null }, "") }.exceptionOrNull()!!.message)
    }

    @Test
    fun buildsTransports() {
        val ws = XrayConfig.outbound(ServerProfile.parse("vless://id@h:443?type=ws&security=tls&path=%2Fws&host=cdn.example&alpn=h2,http/1.1")!!)
            .getJSONObject("streamSettings")
        assertEquals("/ws", ws.getJSONObject("wsSettings").getString("path"))
        assertEquals("cdn.example", ws.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals(2, ws.getJSONObject("tlsSettings").getJSONArray("alpn").length())

        val grpc = XrayConfig.outbound(ServerProfile.parse("vless://id@h:443?type=grpc&serviceName=svc&mode=multi&security=tls")!!)
            .getJSONObject("streamSettings").getJSONObject("grpcSettings")
        assertEquals("svc", grpc.getString("serviceName"))
        assertTrue(grpc.getBoolean("multiMode"))

        val xhttp = XrayConfig.outbound(
            ServerProfile.parse("vless://id@h:443?type=xhttp&path=%2Fx&mode=auto&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%7D")!!,
        ).getJSONObject("streamSettings").getJSONObject("xhttpSettings")
        assertEquals("/x", xhttp.getString("path"))
        assertEquals("100-1000", xhttp.getString("xPaddingBytes"))
    }

    @Test
    fun validationConfigHasNoTunFd() {
        val config = JSONObject(XrayConfig.build(ServerProfile.parse(reality)!!, null))
        assertFalse(config.has("env"))
    }

    @Test
    fun parsesSubscriptionBodies() {
        val links = "$reality\nvless://id2@h2:443?type=ws#Two\nvmess://ignored\n"
        assertEquals(2, Subscription.parseLinks(links).size)
        val b64 = Base64.getEncoder().encodeToString(links.toByteArray()).trimEnd('=')
        assertEquals(2, Subscription.parseLinks(b64).size)
    }

    @Test
    fun parsesUserInfo() {
        val info = Subscription.parseUserInfo("upload=1024; download=2048; total=10737418240; expire=1767225600")
        assertEquals(1024L, info.upload)
        assertEquals(2048L, info.download)
        assertEquals(10737418240L, info.total)
        assertEquals(1767225600L, info.expire)
        assertNotNull(Subscription.parseUserInfo(null))
    }

    @Test
    fun unwrapsDeepLinks() {
        val sub = "https://176.0.0.1:50070/abc/def"
        assertEquals(sub, DeepLink.unwrap("ethicvpn://add?url=https%3A%2F%2F176.0.0.1%3A50070%2Fabc%2Fdef"))
        assertEquals(sub, DeepLink.unwrap("ethicvpn://add/$sub"))
        assertEquals(sub, DeepLink.unwrap("ethicvpn://add/https%3A%2F%2F176.0.0.1%3A50070%2Fabc%2Fdef"))
        assertEquals("vless://id@h:1", DeepLink.unwrap("vless://id@h:1"))
        assertEquals("", DeepLink.unwrap("ethicvpn://add"))
        assertTrue(DeepLink.wantsClipboard("ethicvpn://add"))
        assertTrue(DeepLink.wantsClipboard("ethicvpn://add/"))
        assertFalse(DeepLink.wantsClipboard("ethicvpn://add?url=https%3A%2F%2Fa.b%2Fc"))
        assertFalse(DeepLink.wantsClipboard("vless://id@h:1"))
    }

    @Test
    fun comparesVersions() {
        assertTrue(Updates.isNewer("1.1.0", "1.0.1"))
        assertTrue(Updates.isNewer("v1.10.0", "1.9.2"))
        assertTrue(Updates.isNewer("1.0.1", "1.0"))
        assertFalse(Updates.isNewer("1.0.1", "1.0.1"))
        assertFalse(Updates.isNewer("1.0.0", "1.1.0"))
        // The latest-release endpoint never returns betas; a beta tester is offered the release.
        assertTrue(Updates.isNewer("1.2.0", "1.2.0-beta.1"))
        assertFalse(Updates.isNewer("1.1.0", "1.2.0-beta.1"))
        assertTrue(Updates.isNewer("1.2.0-beta.6", "1.2.0-beta.5"))
        assertFalse(Updates.isNewer("1.2.0-beta.5", "1.2.0-beta.6"))
        assertFalse(Updates.isNewer("1.2.0-beta.6", "1.2.0"))
        val list = """[{"draft":true,"tag_name":"v9","html_url":"x"},{"tag_name":"v1.2.0-beta.6","html_url":"https://x/b6","prerelease":true}]"""
        assertEquals("1.2.0-beta.6", Updates.parseNewest(list)!!.version)
        // GitHub sorted the tags as text: beta.9, beta.3, beta.10.
        val textOrder = """[{"tag_name":"v1.2.0-beta.9","html_url":"x"},{"tag_name":"v1.2.0-beta.3","html_url":"x"},{"tag_name":"v1.2.0-beta.10","html_url":"x"}]"""
        assertEquals("1.2.0-beta.10", Updates.parseNewest(textOrder)!!.version)
    }

    @Test
    fun parsesGeoIp() {
        val l = GeoIP.parse("""{"ip":"176.124.220.95","success":true,"country":"Netherlands","country_code":"NL","city":"Amsterdam","latitude":52.37,"longitude":4.89}""")!!
        assertEquals("Amsterdam, Netherlands", l.place)
        assertEquals(52.37, l.latitude, 1e-9)
        assertEquals("\uD83C\uDDF3\uD83C\uDDF1", l.flag)
        assertNull(GeoIP.parse("""{"success":false,"message":"Reserved range"}"""))
        assertEquals("\uD83C\uDF10", GeoIP.flag(""))
        val ipapi = GeoIP.parse("""{"ip":"1.2.3.4","city":"Helsinki","country":"FI","country_name":"Finland","country_code":"FI","latitude":60.17,"longitude":24.94}""")!!
        assertEquals("Helsinki, Finland", ipapi.place)
        assertNull(GeoIP.parse("""{"error":true,"reason":"RateLimited"}"""))
        assertNull(GeoIP.parse("<html>Just a moment...</html>"))
    }

    @Test
    fun parsesLatestRelease() {
        val json = """{"tag_name":"v1.1.0","html_url":"https://github.com/o/r/releases/tag/v1.1.0","assets":[
            {"name":"EthicVPN-v1.1.0-unsigned.ipa","browser_download_url":"https://x/a.ipa"},
            {"name":"EthicVPN-v1.1.0.apk","browser_download_url":"https://x/a.apk"}]}"""
        val r = Updates.parse(json)
        assertEquals("1.1.0", r.version)
        assertEquals("https://x/a.apk", r.downloadUrl)
        assertEquals("https://github.com/o/r/releases/tag/v1.1.0", Updates.parse("""{"tag_name":"v1.1.0","html_url":"https://github.com/o/r/releases/tag/v1.1.0"}""").downloadUrl)
    }

    @Test
    fun recognizesRussianApps() {
        assertTrue(RussianApps.isRussian("ru.sberbankmobile"))
        assertTrue(RussianApps.isRussian("com.idamob.tinkoff.android"))
        assertFalse(RussianApps.isRussian("org.telegram.messenger"))
    }

    @Test
    fun readsChangelogSection() {
        val text = "# Changes\n\n## v1.2.0\n\n- One\n- Two\n\n## v1.1.0\n\n- Old\n"
        assertEquals("- One\n- Two", Changelog.section(text, "1.2.0"))
        assertNull(Changelog.section(text, "9.9"))
    }

    @Test
    fun userSiteRules() {
        assertEquals(listOf("domain:site.com", "domain:вк.рф"), XrayConfig.siteRules(listOf("https://www.Site.com/page?x", "*.site.com", "вк.рф", "nonsense", " ")))
        val config = JSONObject(XrayConfig.build(ServerProfile.parse(reality)!!, 1, directSites = listOf("bank.com"), proxySites = listOf("yandex.ru")))
        val rules = config.getJSONObject("routing").getJSONArray("rules")
        assertEquals("proxy", rules.getJSONObject(2).getString("outboundTag"))
        assertEquals("domain:yandex.ru", rules.getJSONObject(2).getJSONArray("domain").getString(0))
        assertEquals("direct", rules.getJSONObject(3).getString("outboundTag"))
    }

    @Test
    fun parsesTrojan() {
        val trojan = ServerProfile.parse("trojan://p%40ss@t.example:443?type=ws&path=%2Ft&sni=cdn.example#Trojan")!!
        assertEquals(ServerProfile.TROJAN, trojan.protocol)
        assertEquals("p@ss", trojan.password)
        assertEquals("tls", trojan.security)
        assertEquals("ws", trojan.network)
        val tOut = XrayConfig.outbound(trojan)
        assertEquals("trojan", tOut.getString("protocol"))
        assertEquals("p@ss", tOut.getJSONObject("settings").getString("password"))

        assertTrue(ServerProfile.isShareLink("TROJAN://a@b:1"))
        assertNull(ServerProfile.parse("hy2://auth@h.example:443#HY"))
        assertNull(ServerProfile.parse("vmess://abc"))
    }
}
