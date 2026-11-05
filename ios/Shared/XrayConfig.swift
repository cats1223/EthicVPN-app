import Foundation

/// Builds the Xray-core JSON that libXray runs inside the packet tunnel.
enum XrayConfig {
    static let tunMTU = 1500
    /// What the system resolver is pointed at; Xray hijacks these queries.
    static let dnsServers = ["1.1.1.1", "8.8.8.8"]
    /// Upstreams Xray resolves through the proxy. DoH rides a TCP stream, so DNS does not depend on UDP via the server.
    static let upstreamDNS = ["https://1.1.1.1/dns-query", "https://8.8.8.8/dns-query"]

    private static let privateCIDRs = [
        "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "127.0.0.0/8", "169.254.0.0/16",
        "100.64.0.0/10", "fc00::/7", "fe80::/10", "::1/128",
    ]

    /// Russian sites that go past the VPN when split tunneling is on: the category-ru list
    /// from geodata/geosite.dat and the .ru, .su and .рф zones.
    static let russianDomains = ["geosite:category-ru", "domain:ru", "domain:su", "domain:xn--p1ai"]
    /// Resolver for those sites, reached directly (its address is in geoip:ru), so they get
    /// the addresses a Russian network would.
    static let russianDNS = "77.88.8.8"
    /// Upstream DNS picked in settings. AdGuard is plain DNS over TCP to its IP, which needs no
    /// certificate for the address; it also filters ads.
    static func upstreamDNS(choice: String, custom: String = "") -> [String] {
        switch choice {
        case "cloudflare": return ["https://1.1.1.1/dns-query", "https://1.0.0.1/dns-query"]
        case "google": return ["https://8.8.8.8/dns-query", "https://8.8.4.4/dns-query"]
        case "adguard": return ["tcp://94.140.14.14:53", "tcp://94.140.15.15:53"]
        case "custom": return customDNS(custom).map { [$0] } ?? upstreamDNS
        default: return upstreamDNS
        }
    }

    /// What the user typed as their own DNS: an IPv4 address becomes DNS over TCP to it, an
    /// https:// URL is used as DoH. Anything else is nil.
    static func customDNS(_ entry: String) -> String? {
        let e = entry.trimmingCharacters(in: .whitespaces)
        if e.hasPrefix("https://") { return e.count > 8 && !e.contains(" ") ? e : nil }
        let parts = e.split(separator: ".", omittingEmptySubsequences: false)
        let isIP = parts.count == 4 && parts.allSatisfy { p in
            !p.isEmpty && p.count <= 3 && p.allSatisfy(\.isNumber) && (Int(p) ?? 256) <= 255
        }
        return isIP ? "tcp://\(e):53" : nil
    }

    /// Ad and tracker domains (geodata/geosite.dat) dropped when ad blocking is on.
    static let adDomains = ["geosite:category-ads-all"]

    /// - Parameter tunFd: the utun descriptor; Xray reads it from the `xray.tun.fd` env key.
    ///   Pass nil to build a config for validation only.
    /// - Parameter logPath: file for Xray's error log at info level, shown in the app's connection log.
    /// - Parameter russiaDirect: send Russian sites and IP ranges past the VPN.
    /// - Parameter assetDir: directory with geoip.dat and geosite.dat, needed for `russiaDirect` and `blockAds`.
    /// - Parameter directSites: the user's sites that always go past the VPN.
    /// - Parameter proxySites: the user's sites that always go through the VPN, even Russian ones.
    /// - Parameter blockAds: drop connections to ad and tracker domains.
    /// - Parameter dns: upstream resolvers, reached through the proxy; see `upstreamDNS(choice:custom:)`.
    /// - Parameter accessLogPath: file for Xray's access log, one line per connection; blocked ones
    ///   end in "-> block]", which is how blocked ads are counted (`countBlocked`).
    static func build(
        _ profile: ServerProfile, tunFd: Int32?, logPath: String? = nil, russiaDirect: Bool = false, assetDir: String? = nil,
        directSites: [String] = [], proxySites: [String] = [], blockAds: Bool = false, dns: [String] = upstreamDNS,
        accessLogPath: String? = nil
    ) throws -> String {
        var dnsServers: [Any] = dns
        var rules: [[String: Any]] = [
            ["inboundTag": ["tun-in"], "port": "53", "outboundTag": "dns-out"],
            ["ip": privateCIDRs, "outboundTag": "direct"],
        ]
        // The user's own lists come first, so they win over ad blocking and the Russian rules.
        let proxy = siteRules(proxySites), direct = siteRules(directSites)
        if !proxy.isEmpty { rules.append(["domain": proxy, "outboundTag": "proxy"]) }
        if !direct.isEmpty { rules.append(["domain": direct, "outboundTag": "direct"]) }
        if blockAds { rules.append(["domain": adDomains, "outboundTag": "block"]) }
        if russiaDirect {
            dnsServers.insert(["address": russianDNS, "domains": russianDomains, "skipFallback": true], at: 0)
            rules.append(["domain": russianDomains, "outboundTag": "direct"])
            rules.append(["ip": ["geoip:ru"], "outboundTag": "direct"])
        }
        var log: [String: String] = logPath.map { ["loglevel": "info", "access": "none", "error": $0] } ?? ["loglevel": "warning"]
        if let accessLogPath { log["access"] = accessLogPath }
        var root: [String: Any] = [
            "log": log,
            "dns": ["servers": dnsServers, "queryStrategy": "UseIPv4"],
            "inbounds": [[
                "tag": "tun-in",
                "protocol": "tun",
                "settings": ["name": "xray0", "mtu": tunMTU],
                "sniffing": ["enabled": true, "destOverride": ["http", "tls", "quic"], "routeOnly": true],
            ]],
            "outbounds": [
                outbound(profile),
                ["tag": "direct", "protocol": "freedom"],
                ["tag": "block", "protocol": "blackhole"],
                ["tag": "dns-out", "protocol": "dns"],
            ],
            "routing": ["domainStrategy": "AsIs", "rules": rules],
        ]
        var env: [String: String] = [:]
        if let tunFd { env["xray.tun.fd"] = String(tunFd) }
        if russiaDirect || blockAds, let assetDir { env["xray.location.asset"] = assetDir }
        if !env.isEmpty { root["env"] = env }
        let data = try JSONSerialization.data(withJSONObject: root, options: [.sortedKeys])
        return String(decoding: data, as: UTF8.self)
    }

    /// The Xray config for a router (XKeen on Keenetic): its outbounds and routing, to drop into
    /// 04_outbounds.json and 05_routing.json. The router keeps its own inbounds and DNS.
    static func routerOutbounds(_ profile: ServerProfile) throws -> String {
        try pretty(["outbounds": [outbound(profile), ["tag": "direct", "protocol": "freedom"], ["tag": "block", "protocol": "blackhole"]]])
    }

    static func routerRouting(russiaDirect: Bool, blockAds: Bool = false, directSites: [String] = [], proxySites: [String] = []) throws -> String {
        var rules: [[String: Any]] = [["type": "field", "ip": privateCIDRs, "outboundTag": "direct"]]
        let proxy = siteRules(proxySites), direct = siteRules(directSites)
        if !proxy.isEmpty { rules.append(["type": "field", "domain": proxy, "outboundTag": "proxy"]) }
        if !direct.isEmpty { rules.append(["type": "field", "domain": direct, "outboundTag": "direct"]) }
        if blockAds { rules.append(["type": "field", "domain": adDomains, "outboundTag": "block"]) }
        if russiaDirect {
            rules.append(["type": "field", "domain": russianDomains, "outboundTag": "direct"])
            rules.append(["type": "field", "ip": ["geoip:ru"], "outboundTag": "direct"])
        }
        // Everything else through the VPN.
        rules.append(["type": "field", "network": "tcp,udp", "outboundTag": "proxy"])
        return try pretty(["routing": ["domainStrategy": "IPIfNonMatch", "rules": rules]])
    }

    private static func pretty(_ object: [String: Any]) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys, .prettyPrinted, .withoutEscapingSlashes])
        return String(decoding: data, as: UTF8.self)
    }

    /// Connections Xray sent to the blackhole in a piece of its access log.
    static func countBlocked(_ accessLog: String) -> Int {
        accessLog.components(separatedBy: "-> block]").count - 1
    }

    /// Xray domain rules for what a user typed: "https://www.Site.com/page", "*.site.com" and
    /// "site.com" all become "domain:site.com", which also covers subdomains. Junk is dropped.
    static func siteRules(_ entries: [String]) -> [String] {
        var seen = Set<String>()
        return entries.compactMap(siteDomain).filter { seen.insert($0).inserted }.map { "domain:" + $0 }
    }

    static func siteDomain(_ entry: String) -> String? {
        var host = entry.trimmingCharacters(in: .whitespaces).lowercased()
        if let r = host.range(of: "://") { host = String(host[r.upperBound...]) }
        for cut in ["/", "?", ":"] { if let r = host.range(of: cut) { host = String(host[..<r.lowerBound]) } }
        for prefix in ["*.", "www."] where host.hasPrefix(prefix) { host.removeFirst(prefix.count) }
        host = host.trimmingCharacters(in: CharacterSet(charactersIn: "."))
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        let valid = parts.count >= 2 && parts.allSatisfy { p in
            !p.isEmpty && p.unicodeScalars.allSatisfy { CharacterSet.alphanumerics.contains($0) || $0 == "-" }
        }
        return valid ? host : nil
    }

    static func outbound(_ p: ServerProfile) -> [String: Any] {
        switch p.protocol {
        case ServerProfile.trojan:
            return [
                "tag": "proxy",
                "protocol": "trojan",
                "settings": ["address": p.address, "port": p.port, "password": p.password],
                "streamSettings": streamSettings(p),
            ]
        default:
            var settings: [String: Any] = [
                "address": p.address,
                "port": p.port,
                "id": p.uuid,
                "encryption": p.encryption,
            ]
            if !p.flow.isEmpty { settings["flow"] = p.flow }
            return [
                "tag": "proxy",
                "protocol": "vless",
                "settings": settings,
                "streamSettings": streamSettings(p),
            ]
        }
    }

    private static func streamSettings(_ p: ServerProfile) -> [String: Any] {
        var s: [String: Any] = ["network": p.network, "security": p.security]
        let serverName = p.sni.isEmpty ? p.host : p.sni

        switch p.security {
        case "reality":
            var r: [String: Any] = [
                "serverName": serverName,
                "fingerprint": p.fingerprint.isEmpty ? "chrome" : p.fingerprint,
                "password": p.publicKey,
                "shortId": p.shortId,
            ]
            r.putIfNotEmpty("spiderX", p.spiderX)
            r.putIfNotEmpty("mldsa65Verify", p.mldsa65Verify)
            s["realitySettings"] = r
        case "tls":
            var t: [String: Any] = [:]
            t.putIfNotEmpty("serverName", serverName)
            t.putIfNotEmpty("fingerprint", p.fingerprint)
            if !p.alpn.isEmpty { t["alpn"] = p.alpn }
            t.putIfNotEmpty("pinnedPeerCertSha256", p.pinnedPeerCertSha256)
            t.putIfNotEmpty("verifyPeerCertByName", p.verifyPeerCertByName)
            t.putIfNotEmpty("echConfigList", p.echConfigList)
            s["tlsSettings"] = t
        default:
            break
        }

        let path = p.path.isEmpty ? "/" : p.path
        switch p.network {
        case "ws", "httpupgrade":
            var w: [String: Any] = ["path": path]
            w.putIfNotEmpty("host", p.host)
            s[p.network == "ws" ? "wsSettings" : "httpupgradeSettings"] = w
        case "grpc":
            var g: [String: Any] = ["serviceName": p.serviceName, "multiMode": p.mode == "multi"]
            g.putIfNotEmpty("authority", p.authority)
            s["grpcSettings"] = g
        case "xhttp":
            var x: [String: Any] = ["path": path]
            x.putIfNotEmpty("host", p.host)
            x.putIfNotEmpty("mode", p.mode)
            if let data = p.extra.data(using: .utf8),
               let extra = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                x.merge(extra) { _, new in new }
            }
            s["xhttpSettings"] = x
        case "tcp" where p.headerType == "http":
            var request: [String: Any] = ["path": [path]]
            if !p.host.isEmpty {
                request["headers"] = ["Host": p.host.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }]
            }
            s["tcpSettings"] = ["header": ["type": "http", "request": request]]
        default:
            break
        }
        return s
    }
}

extension ServerProfile {
    /// Connection parameters without the uuid, passwords and keys, safe to show in the log.
    var summary: String {
        var parts = [self.protocol, "\(address):\(port)", "type=\(network)", "security=\(security)"]
        // VLESS encryption strings carry a key after the first dot; only the scheme is shown.
        let enc = self.protocol == ServerProfile.vless ? encryption.split(separator: ".").first.map(String.init) ?? encryption : ""
        for (key, value) in [("enc", enc), ("sni", sni), ("fp", fingerprint), ("flow", flow), ("mode", mode), ("spx", spiderX),
                             ("path", path), ("host", host), ("sid", shortId.isEmpty ? "" : "set"),
                             ("pbk", publicKey.isEmpty ? "" : "set"), ("extra", extra.isEmpty ? "" : "set")]
        where !value.isEmpty {
            parts.append("\(key)=\(value)")
        }
        return parts.joined(separator: " ")
    }
}

private extension Dictionary where Key == String, Value == Any {
    mutating func putIfNotEmpty(_ key: String, _ value: String) {
        if !value.isEmpty { self[key] = value }
    }
}
