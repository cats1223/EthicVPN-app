import Foundation

/// A server parsed from a share link. Supported:
/// - vless://<uuid>@<host>:<port>?type=tcp&security=reality&pbk=...&sid=...&sni=...&fp=chrome&flow=xtls-rprx-vision#Name
/// - trojan://<password>@<host>:<port>?security=tls&sni=...&type=ws&path=...#Name (TLS unless told otherwise)
struct ServerProfile: Codable, Identifiable, Hashable {
    static let vless = "vless"
    static let trojan = "trojan"

    var id = UUID().uuidString
    var name: String
    var address: String
    var port: Int
    /// "vless" or "trojan".
    var `protocol` = ServerProfile.vless
    var uuid = ""
    /// Trojan password.
    var password = ""
    var encryption = "none"
    var flow = ""
    var network = "tcp"
    var security = "none"
    var sni = ""
    var fingerprint = ""
    var publicKey = ""
    var shortId = ""
    var spiderX = ""
    var mldsa65Verify = ""
    var alpn: [String] = []
    var pinnedPeerCertSha256 = ""
    var verifyPeerCertByName = ""
    var echConfigList = ""
    var host = ""
    var path = ""
    var serviceName = ""
    var authority = ""
    var mode = ""
    var headerType = ""
    var extra = ""
    /// The original link, kept so the profile can be shared or re-parsed.
    var link = ""

    private static let schemes = ["vless": vless, "trojan": trojan]

    /// Whether `text` starts like a server link this app can read.
    static func isShareLink(_ text: String) -> Bool {
        let lower = text.lowercased()
        return schemes.keys.contains { lower.hasPrefix($0 + "://") }
    }

    /// Returns nil for anything that is not a well-formed link of a supported protocol.
    static func parse(_ raw: String) -> ServerProfile? {
        let text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let schemeEnd = text.range(of: "://"),
              let proto = schemes[text[..<schemeEnd.lowerBound].lowercased()] else { return nil }

        // Names often hold raw spaces and emoji, so cut the fragment off before URL parsing.
        var body = text
        var fragment: String?
        if let hash = text.firstIndex(of: "#") {
            body = String(text[..<hash])
            fragment = String(text[text.index(after: hash)...])
        }
        body = body.replacingOccurrences(of: " ", with: "%20")
        // URLComponents splits "user:pass" userinfo; a Trojan password may hold a colon, so rejoin it.
        guard let comps = URLComponents(string: body),
              let user = comps.percentEncodedUser.map({ decode($0) + (comps.percentEncodedPassword.map { ":" + decode($0) } ?? "") }),
              !user.isEmpty,
              var host = comps.host, !host.isEmpty,
              let port = comps.port, (1...65535).contains(port)
        else { return nil }
        host = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))

        var q: [String: String] = [:]
        for pair in (comps.percentEncodedQuery ?? "").split(separator: "&") {
            let parts = pair.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            q[decode(String(parts[0]))] = parts.count > 1 ? decode(String(parts[1])) : ""
        }

        let decodedName = fragment.map { $0.removingPercentEncoding ?? $0 }?
            .trimmingCharacters(in: .whitespaces) ?? ""
        var p = ServerProfile(name: decodedName.isEmpty ? "\(host):\(port)" : decodedName, address: host, port: port)
        p.protocol = proto
        p.link = text

        switch proto {
        case trojan:
            p.password = user
            p.applyStream(q, defaultSecurity: "tls")
        default:
            p.uuid = user
            p.encryption = q["encryption"].flatMap { $0.isEmpty ? nil : $0 } ?? "none"
            p.applyStream(q, defaultSecurity: "none")
        }
        return p
    }

    /// The transport and security parameters VLESS and Trojan links share.
    private mutating func applyStream(_ q: [String: String], defaultSecurity: String) {
        var network = (q["type"] ?? "").lowercased()
        if network.isEmpty || network == "raw" { network = "tcp" }
        if network == "splithttp" { network = "xhttp" }
        self.network = network
        flow = q["flow"] ?? ""
        security = q["security"].flatMap { $0.isEmpty ? nil : $0.lowercased() } ?? defaultSecurity
        sni = q["sni"] ?? q["peer"] ?? ""
        fingerprint = q["fp"] ?? ""
        publicKey = q["pbk"] ?? ""
        shortId = q["sid"] ?? ""
        spiderX = q["spx"] ?? ""
        mldsa65Verify = q["pqv"] ?? ""
        alpn = Self.list(q["alpn"])
        pinnedPeerCertSha256 = q["pcs"] ?? ""
        verifyPeerCertByName = q["vcn"] ?? ""
        echConfigList = q["ech"] ?? ""
        host = q["host"] ?? ""
        path = q["path"] ?? ""
        serviceName = q["serviceName"] ?? ""
        authority = q["authority"] ?? ""
        mode = q["mode"] ?? ""
        headerType = q["headerType"] ?? ""
        extra = q["extra"] ?? ""
    }

    private static func list(_ value: String?) -> [String] {
        (value ?? "").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
    }

    // Unlike form decoding, '+' stays literal: base64 values such as pbk contain it.
    private static func decode(_ s: String) -> String {
        s.removingPercentEncoding ?? s
    }
}

extension ServerProfile {
    private enum StoredKeys: String, CodingKey { case id, link }

    /// Saved profiles are rebuilt from their link, so fields added in later versions get their
    /// values instead of failing to decode.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: StoredKeys.self)
        let link = try c.decode(String.self, forKey: .link)
        guard var p = ServerProfile.parse(link) else {
            throw DecodingError.dataCorruptedError(forKey: .link, in: c, debugDescription: "unsupported link")
        }
        p.id = try c.decode(String.self, forKey: .id)
        self = p
    }
}
