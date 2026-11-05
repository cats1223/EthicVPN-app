import XCTest

final class ServerProfileTests: XCTestCase {
    private let reality =
        "vless://0f6e1a3c-7c6e-4a59-9f2a-3d1a7c2b9e01@nl1.ethic.example:443" +
        "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.microsoft.com&fp=chrome" +
        "&pbk=SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc&sid=6ba85179e30d4fc2&spx=%2F&type=tcp" +
        "#🇳🇱 Нидерланды"

    func testParsesRealityLink() throws {
        let p = try XCTUnwrap(ServerProfile.parse(reality))
        XCTAssertEqual(p.uuid, "0f6e1a3c-7c6e-4a59-9f2a-3d1a7c2b9e01")
        XCTAssertEqual(p.address, "nl1.ethic.example")
        XCTAssertEqual(p.port, 443)
        XCTAssertEqual(p.flow, "xtls-rprx-vision")
        XCTAssertEqual(p.security, "reality")
        XCTAssertEqual(p.network, "tcp")
        XCTAssertEqual(p.sni, "www.microsoft.com")
        XCTAssertEqual(p.publicKey, "SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc")
        XCTAssertEqual(p.shortId, "6ba85179e30d4fc2")
        XCTAssertEqual(p.spiderX, "/")
        XCTAssertEqual(p.name, "🇳🇱 Нидерланды")
    }

    func testParsesIPv6AndDefaults() throws {
        let p = try XCTUnwrap(ServerProfile.parse("vless://abc@[2001:db8::1]:8443?type=raw"))
        XCTAssertEqual(p.address, "2001:db8::1")
        XCTAssertEqual(p.network, "tcp")
        XCTAssertEqual(p.security, "none")
        XCTAssertEqual(p.encryption, "none")
    }

    func testKeepsPlusInValues() throws {
        let p = try XCTUnwrap(ServerProfile.parse("vless://abc@h:1?security=reality&pbk=a+b/c&sid=1"))
        XCTAssertEqual(p.publicKey, "a+b/c")
    }

    func testRejectsInvalidLinks() {
        XCTAssertNil(ServerProfile.parse("vmess://abc"))
        XCTAssertNil(ServerProfile.parse("vless://@host:443"))
        XCTAssertNil(ServerProfile.parse("vless://id@host"))
        XCTAssertNil(ServerProfile.parse("not a link"))
    }

    func testBuildsRealityConfig() throws {
        let p = try XCTUnwrap(ServerProfile.parse(reality))
        let json = try XrayConfig.build(p, tunFd: 42)
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        XCTAssertEqual((root["env"] as? [String: String])?["xray.tun.fd"], "42")

        let out = try XCTUnwrap((root["outbounds"] as? [[String: Any]])?.first)
        XCTAssertEqual(out["protocol"] as? String, "vless")
        let settings = try XCTUnwrap(out["settings"] as? [String: Any])
        XCTAssertEqual(settings["address"] as? String, "nl1.ethic.example")
        XCTAssertEqual(settings["port"] as? Int, 443)
        XCTAssertEqual(settings["flow"] as? String, "xtls-rprx-vision")

        let stream = try XCTUnwrap(out["streamSettings"] as? [String: Any])
        let r = try XCTUnwrap(stream["realitySettings"] as? [String: Any])
        XCTAssertEqual(r["serverName"] as? String, "www.microsoft.com")
        XCTAssertEqual(r["password"] as? String, "SbVKOEMjK0sIlbwg4akyBg5mL5KZwwB-ed4eEE7YnRc")
        XCTAssertEqual(r["shortId"] as? String, "6ba85179e30d4fc2")
    }

    func testRussiaDirectRoutesRussianSitesPastTheProxy() throws {
        let p = try XCTUnwrap(ServerProfile.parse(reality))
        let plain = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(XrayConfig.build(p, tunFd: 42).utf8)) as? [String: Any])
        XCTAssertNil((plain["env"] as? [String: String])?["xray.location.asset"])
        XCTAssertEqual((plain["routing"] as? [String: Any]).flatMap { $0["rules"] as? [Any] }?.count, 2)

        let json = try XrayConfig.build(p, tunFd: 42, russiaDirect: true, assetDir: "/geo")
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        XCTAssertEqual((root["env"] as? [String: String])?["xray.location.asset"], "/geo")
        let rules = try XCTUnwrap((root["routing"] as? [String: Any])?["rules"] as? [[String: Any]])
        XCTAssertTrue(rules.contains { $0["ip"] as? [String] == ["geoip:ru"] && $0["outboundTag"] as? String == "direct" })
        XCTAssertTrue(rules.contains { ($0["domain"] as? [String])?.contains("domain:ru") == true && $0["outboundTag"] as? String == "direct" })
        let dns = try XCTUnwrap((root["dns"] as? [String: Any])?["servers"] as? [Any])
        XCTAssertEqual((dns.first as? [String: Any])?["address"] as? String, XrayConfig.russianDNS)
    }

    func testBlockAdsDropsAdDomainsAfterTheUserLists() throws {
        let p = try XCTUnwrap(ServerProfile.parse(reality))
        let json = try XrayConfig.build(p, tunFd: 42, assetDir: "/geo", proxySites: ["ads.example.com"], blockAds: true)
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        XCTAssertEqual((root["env"] as? [String: String])?["xray.location.asset"], "/geo")
        let rules = try XCTUnwrap((root["routing"] as? [String: Any])?["rules"] as? [[String: Any]])
        let block = try XCTUnwrap(rules.firstIndex { $0["outboundTag"] as? String == "block" })
        XCTAssertEqual(rules[block]["domain"] as? [String], ["geosite:category-ads-all"])
        // A site the user listed still goes through, ad domain or not.
        let proxy = try XCTUnwrap(rules.firstIndex { $0["outboundTag"] as? String == "proxy" })
        XCTAssertLessThan(proxy, block)
        XCTAssertFalse(try XrayConfig.build(p, tunFd: 42).contains("category-ads"))
    }

    func testPicksUpstreamDNS() throws {
        XCTAssertEqual(XrayConfig.upstreamDNS(choice: ""), XrayConfig.upstreamDNS)
        XCTAssertEqual(XrayConfig.upstreamDNS(choice: "adguard").first, "tcp://94.140.14.14:53")
        XCTAssertEqual(XrayConfig.upstreamDNS(choice: "custom", custom: " 9.9.9.9 "), ["tcp://9.9.9.9:53"])
        XCTAssertEqual(XrayConfig.upstreamDNS(choice: "custom", custom: "dns.example"), XrayConfig.upstreamDNS)
        XCTAssertEqual(XrayConfig.customDNS("https://9.9.9.9/dns-query"), "https://9.9.9.9/dns-query")
        XCTAssertNil(XrayConfig.customDNS("300.1.1.1"))
        let p = try XCTUnwrap(ServerProfile.parse(reality))
        let json = try XrayConfig.build(p, tunFd: 42, dns: XrayConfig.upstreamDNS(choice: "google"))
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        XCTAssertEqual(((root["dns"] as? [String: Any])?["servers"] as? [Any])?.first as? String, "https://8.8.8.8/dns-query")
    }

    func testKeepsTrafficPerDay() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        let today = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 10, day: 25, hour: 12)))
        func ago(_ n: Int) -> Date { calendar.date(byAdding: .day, value: -n, to: today)! }
        var h = TrafficHistory()
        h.add(down: 100, up: 10, at: ago(61), calendar: calendar)
        h.add(down: 300, up: 30, at: ago(1), calendar: calendar)
        h.add(down: 500, up: 50, at: today, calendar: calendar)
        let back = try XCTUnwrap(TrafficHistory(data: h.data))
        XCTAssertEqual(back.total(1, until: today, calendar: calendar).down, 500)
        XCTAssertEqual(back.total(7, until: today, calendar: calendar).up, 80)
        XCTAssertEqual(back.daily(3, until: today, calendar: calendar).map(\.total), [0, 330, 550])
        XCTAssertNil(back.days["2026-08-25"])
        h.addBlocked(3, at: today, calendar: calendar)
        h.addBlocked(2, at: ago(1), calendar: calendar)
        let again = try XCTUnwrap(TrafficHistory(data: h.data))
        XCTAssertEqual(again.blocked(1, until: today, calendar: calendar), 3)
        XCTAssertEqual(again.blocked(7, until: today, calendar: calendar), 5)
        // Histories saved before the counter have no ads.
        let old = try XCTUnwrap(TrafficHistory(data: Data(#"{"days":{"2026-10-25":[1,2]}}"#.utf8)))
        XCTAssertEqual(old.blocked(1, until: today, calendar: calendar), 0)
        let log = "x accepted tcp:ads.example:443 [tun-in -> block]\ny [tun-in -> direct]\nz [tun-in -> block]\n"
        XCTAssertEqual(XrayConfig.countBlocked(log), 2)
        let json = try XrayConfig.build(try XCTUnwrap(ServerProfile.parse(reality)), tunFd: 42, accessLogPath: "/a.log")
        XCTAssertTrue(json.contains(#""access":"\/a.log""#) || json.contains(#""access":"/a.log""#))
    }

    func testExportsForRouters() throws {
        let p = try XCTUnwrap(ServerProfile.parse(reality))
        let out = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(XrayConfig.routerOutbounds(p).utf8)) as? [String: Any])
        XCTAssertEqual(((out["outbounds"] as? [[String: Any]])?.first)?["tag"] as? String, "proxy")
        let routing = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(XrayConfig.routerRouting(russiaDirect: true, blockAds: true).utf8)) as? [String: Any])
        let rules = try XCTUnwrap((routing["routing"] as? [String: Any])?["rules"] as? [[String: Any]])
        XCTAssertEqual(rules.last?["outboundTag"] as? String, "proxy")
        XCTAssertTrue(rules.contains { $0["ip"] as? [String] == ["geoip:ru"] })
    }

    func testExplainsRemnawaveAnswers() throws {
        XCTAssertEqual(try Subscription.check(status: 200, header: { _ in nil }, body: reality).count, 1)
        let announce = "base64:" + Data("Лимит: 3 устройства".utf8).base64EncodedString()
        let headers = ["x-hwid-max-devices-reached": "true", "announce": announce]
        XCTAssertThrowsError(try Subscription.check(status: 200, header: { headers[$0] }, body: "")) { error in
            XCTAssertTrue((error as? LocalizedError)?.errorDescription?.hasSuffix("Лимит: 3 устройства") == true)
        }
        XCTAssertThrowsError(try Subscription.check(status: 200, header: { _ in nil }, body: "<!doctype html>")) { error in
            guard case SubscriptionError.notSubscription = error else { return XCTFail("\(error)") }
        }
        XCTAssertThrowsError(try Subscription.check(status: 404, header: { _ in nil }, body: ""))
    }

    func testBuildsTransports() throws {
        let xhttp = try XCTUnwrap(ServerProfile.parse(
            "vless://id@h:443?type=xhttp&path=%2Fx&mode=auto&extra=%7B%22xPaddingBytes%22%3A%22100-1000%22%7D"
        ))
        let stream = try XCTUnwrap(XrayConfig.outbound(xhttp)["streamSettings"] as? [String: Any])
        let x = try XCTUnwrap(stream["xhttpSettings"] as? [String: Any])
        XCTAssertEqual(x["path"] as? String, "/x")
        XCTAssertEqual(x["xPaddingBytes"] as? String, "100-1000")

        let grpc = try XCTUnwrap(ServerProfile.parse("vless://id@h:443?type=grpc&serviceName=svc&mode=multi&security=tls"))
        let g = try XCTUnwrap((XrayConfig.outbound(grpc)["streamSettings"] as? [String: Any])?["grpcSettings"] as? [String: Any])
        XCTAssertEqual(g["serviceName"] as? String, "svc")
        XCTAssertEqual(g["multiMode"] as? Bool, true)
    }

    func testParsesSubscriptionBodies() {
        let links = "\(reality)\nvless://id2@h2:443?type=ws#Two\nvmess://ignored\n"
        XCTAssertEqual(Subscription.parseLinks(links).count, 2)
        let b64 = Data(links.utf8).base64EncodedString().trimmingCharacters(in: CharacterSet(charactersIn: "="))
        XCTAssertEqual(Subscription.parseLinks(b64).count, 2)
    }

    func testParsesUserInfo() {
        let info = Subscription.parseUserInfo("upload=1024; download=2048; total=10737418240; expire=1767225600")
        XCTAssertEqual(info.upload, 1024)
        XCTAssertEqual(info.download, 2048)
        XCTAssertEqual(info.total, 10_737_418_240)
        XCTAssertEqual(info.expire, 1_767_225_600)
    }

    func testUnwrapsDeepLinks() {
        let sub = "https://176.0.0.1:50070/abc/def"
        XCTAssertEqual(DeepLink.unwrap("ethicvpn://add?url=https%3A%2F%2F176.0.0.1%3A50070%2Fabc%2Fdef"), sub)
        XCTAssertEqual(DeepLink.unwrap("ethicvpn://add/\(sub)"), sub)
        XCTAssertEqual(DeepLink.unwrap("ethicvpn://add/https%3A%2F%2F176.0.0.1%3A50070%2Fabc%2Fdef"), sub)
        XCTAssertEqual(DeepLink.unwrap("vless://id@h:1"), "vless://id@h:1")
        XCTAssertEqual(DeepLink.unwrap("ethicvpn://add"), "")
        XCTAssertTrue(DeepLink.wantsClipboard("ethicvpn://add"))
        XCTAssertTrue(DeepLink.wantsClipboard("ethicvpn://add/"))
        XCTAssertFalse(DeepLink.wantsClipboard("ethicvpn://add?url=https%3A%2F%2Fa.b%2Fc"))
        XCTAssertFalse(DeepLink.wantsClipboard("vless://id@h:1"))
    }

    func testComparesVersions() {
        XCTAssertTrue(Updates.isNewer("1.1.0", than: "1.0.1"))
        XCTAssertTrue(Updates.isNewer("v1.10.0", than: "1.9.2"))
        XCTAssertTrue(Updates.isNewer("1.0.1", than: "1.0"))
        XCTAssertFalse(Updates.isNewer("1.0.1", than: "1.0.1"))
        XCTAssertFalse(Updates.isNewer("1.0.0", than: "1.1.0"))
        // The latest-release endpoint never returns betas; a beta tester is offered the release.
        XCTAssertTrue(Updates.isNewer("1.2.0", than: "1.2.0-beta.1"))
        XCTAssertFalse(Updates.isNewer("1.1.0", than: "1.2.0-beta.1"))
        XCTAssertTrue(Updates.isNewer("1.2.0-beta.6", than: "1.2.0-beta.5"))
        XCTAssertFalse(Updates.isNewer("1.2.0-beta.5", than: "1.2.0-beta.6"))
        XCTAssertFalse(Updates.isNewer("1.2.0-beta.6", than: "1.2.0"))
        let list = #"[{"draft":true,"tag_name":"v9","html_url":"x"},{"tag_name":"v1.2.0-beta.6","html_url":"https://x/b6","prerelease":true}]"#
        XCTAssertEqual(Updates.parseNewest(Data(list.utf8))?.version, "1.2.0-beta.6")
        // GitHub sorted the tags as text: beta.9, beta.3, beta.10.
        let textOrder = #"[{"tag_name":"v1.2.0-beta.9","html_url":"x"},{"tag_name":"v1.2.0-beta.3","html_url":"x"},{"tag_name":"v1.2.0-beta.10","html_url":"x"}]"#
        XCTAssertEqual(Updates.parseNewest(Data(textOrder.utf8))?.version, "1.2.0-beta.10")
    }

    func testParsesGeoIP() throws {
        let json = #"{"ip":"176.124.220.95","success":true,"country":"Netherlands","country_code":"NL","city":"Amsterdam","latitude":52.37,"longitude":4.89}"#
        let l = try XCTUnwrap(GeoIP.parse(Data(json.utf8)))
        XCTAssertEqual(l.place, "Amsterdam, Netherlands")
        XCTAssertEqual(l.latitude, 52.37, accuracy: 1e-9)
        XCTAssertEqual(l.flag, "🇳🇱")
        XCTAssertNil(GeoIP.parse(Data(#"{"success":false,"message":"Reserved range"}"#.utf8)))
        XCTAssertEqual(GeoIP.flag(""), "🌐")
        let ipapi = try XCTUnwrap(GeoIP.parse(Data(#"{"ip":"1.2.3.4","city":"Helsinki","country":"FI","country_name":"Finland","country_code":"FI","latitude":60.17,"longitude":24.94}"#.utf8)))
        XCTAssertEqual(ipapi.place, "Helsinki, Finland")
        XCTAssertNil(GeoIP.parse(Data(#"{"error":true,"reason":"RateLimited"}"#.utf8)))
        XCTAssertNil(GeoIP.parse(Data("<html>Just a moment...</html>".utf8)))
    }

    func testParsesLatestRelease() throws {
        let json = #"{"tag_name":"v1.1.0","html_url":"https://github.com/o/r/releases/tag/v1.1.0","assets":[]}"#
        let r = try XCTUnwrap(Updates.parse(Data(json.utf8)))
        XCTAssertEqual(r.version, "1.1.0")
        XCTAssertEqual(r.pageURL.absoluteString, "https://github.com/o/r/releases/tag/v1.1.0")
        XCTAssertNil(Updates.parse(Data("{}".utf8)))
    }

    func testReadsChangelogSection() {
        let text = "# Changes\n\n## v1.2.0\n\n- One\n- Two\n\n## v1.1.0\n\n- Old\n"
        XCTAssertEqual(Changelog.section(text, version: "1.2.0"), "- One\n- Two")
        XCTAssertNil(Changelog.section(text, version: "9.9"))
    }

    func testUserSiteRules() throws {
        XCTAssertEqual(XrayConfig.siteRules(["https://www.Site.com/page?x", "*.site.com", "вк.рф", "nonsense", " "]), ["domain:site.com", "domain:вк.рф"])
        let p = try XCTUnwrap(ServerProfile.parse(reality))
        let json = try XrayConfig.build(p, tunFd: 1, directSites: ["bank.com"], proxySites: ["yandex.ru"])
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])
        let rules = try XCTUnwrap((root["routing"] as? [String: Any])?["rules"] as? [[String: Any]])
        XCTAssertEqual(rules[2]["outboundTag"] as? String, "proxy")
        XCTAssertEqual(rules[2]["domain"] as? [String], ["domain:yandex.ru"])
        XCTAssertEqual(rules[3]["outboundTag"] as? String, "direct")
    }

    func testParsesTrojan() throws {
        let trojan = try XCTUnwrap(ServerProfile.parse("trojan://p%40ss@t.example:443?type=ws&path=%2Ft&sni=cdn.example#Trojan"))
        XCTAssertEqual(trojan.protocol, ServerProfile.trojan)
        XCTAssertEqual(trojan.password, "p@ss")
        XCTAssertEqual(trojan.security, "tls")
        XCTAssertEqual(trojan.network, "ws")
        let tOut = XrayConfig.outbound(trojan)
        XCTAssertEqual(tOut["protocol"] as? String, "trojan")
        XCTAssertEqual((tOut["settings"] as? [String: Any])?["password"] as? String, "p@ss")

        XCTAssertTrue(ServerProfile.isShareLink("TROJAN://a@b:1"))
        XCTAssertNil(ServerProfile.parse("hy2://auth@h.example:443#HY"))
        XCTAssertNil(ServerProfile.parse("vmess://abc"))
    }

    func testStoredProfilesAreRebuiltFromTheLink() throws {
        let old = #"{"id":"keep","name":"x","address":"h","port":1,"uuid":"u","link":"vless://u@h:1?type=ws#x"}"#
        let p = try JSONDecoder().decode(ServerProfile.self, from: Data(old.utf8))
        XCTAssertEqual(p.id, "keep")
        XCTAssertEqual(p.network, "ws")
        XCTAssertEqual(p.protocol, ServerProfile.vless)
        let again = try JSONDecoder().decode(ServerProfile.self, from: JSONEncoder().encode(p))
        XCTAssertEqual(again, p)
    }

    func testDropsSavedServersThisVersionCannotRead() throws {
        let json = #"{"profiles":[{"profile":{"id":"a","link":"hy2://x@h:1#H"},"fromSubscription":false},{"profile":{"id":"b","link":"vless://u@h:1#V"},"fromSubscription":true}],"selectedId":"b","subscriptionURL":"https://s"}"#
        let state = try JSONDecoder().decode(ProfilesState.self, from: Data(json.utf8))
        XCTAssertEqual(state.profiles.map(\.id), ["b"])
        XCTAssertEqual(state.subscriptionURL, "https://s")
    }
}
