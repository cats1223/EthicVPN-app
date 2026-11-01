import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Traffic and expiry the panel reports in the `subscription-userinfo` header.
struct SubscriptionInfo: Codable, Hashable {
    var title = ""
    var upload: Int64 = 0
    var download: Int64 = 0
    var total: Int64 = 0
    /// Unix seconds; 0 means no expiry.
    var expire: Int64 = 0
    var updatedAt: Int64 = 0
    /// A message from the panel (Remnawave's `announce` header), shown on the subscription card.
    /// Optional, so subscriptions saved by earlier versions still decode.
    var announce: String?
}

enum SubscriptionError: LocalizedError {
    case http(Int)
    case empty
    case deviceLimit(String)
    case noDeviceID
    case notSubscription

    var errorDescription: String? {
        switch self {
        case .http(let code): return "HTTP \(code)"
        case .empty: return NSLocalizedString("В подписке нет серверов VLESS или Trojan", comment: "")
        case .deviceLimit(let announce):
            let text = NSLocalizedString("В подписке достигнут лимит устройств. Попросите администратора освободить место для этого устройства", comment: "")
            return announce.isEmpty ? text : "\(text). \(announce)"
        case .noDeviceID: return NSLocalizedString("Панель не получила идентификатор устройства", comment: "")
        case .notSubscription:
            return NSLocalizedString("По ссылке открывается страница, а не подписка. Попросите ссылку подписки ещё раз", comment: "")
        }
    }
}

enum Subscription {
    static let userAgent = "TechTeamVPN/1.0"

    /// - Parameter headers: device headers (`x-hwid` and friends) for panels that bind keys to devices.
    static func fetch(_ url: URL, headers: [String: String] = [:]) async throws -> (profiles: [ServerProfile], info: SubscriptionInfo) {
        var request = URLRequest(url: url, timeoutInterval: 15)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        let (data, response) = try await URLSession.shared.data(for: request)
        let http = response as? HTTPURLResponse
        let profiles = try check(status: http?.statusCode ?? 0, header: { http?.value(forHTTPHeaderField: $0) },
                                 body: String(decoding: data, as: UTF8.self))
        var info = parseUserInfo(http?.value(forHTTPHeaderField: "subscription-userinfo"))
        info.title = decodeTitle(http?.value(forHTTPHeaderField: "profile-title"))
        info.updatedAt = Int64(Date().timeIntervalSince1970)
        let announce = decodeTitle(http?.value(forHTTPHeaderField: "announce"))
        info.announce = announce.isEmpty ? nil : announce
        return (profiles, info)
    }

    /// The servers in a panel's answer, or an error a person understands: Remnawave's device limit
    /// (`x-hwid-max-devices-reached`, with its `announce` message), a missing device id, an HTTP
    /// error, or a web page where the subscription should be.
    static func check(status: Int, header: (String) -> String?, body: String) throws -> [ServerProfile] {
        if header("x-hwid-max-devices-reached") == "true" { throw SubscriptionError.deviceLimit(decodeTitle(header("announce"))) }
        if header("x-hwid-not-supported") == "true" { throw SubscriptionError.noDeviceID }
        guard (200..<300).contains(status) else { throw SubscriptionError.http(status) }
        if body.drop(while: \.isWhitespace).hasPrefix("<") { throw SubscriptionError.notSubscription }
        let profiles = parseLinks(body)
        if profiles.isEmpty { throw SubscriptionError.empty }
        return profiles
    }

    /// Accepts plain text or base64 with one share link per line.
    static func parseLinks(_ body: String) -> [ServerProfile] {
        let text = body.trimmingCharacters(in: .whitespacesAndNewlines)
        let plain = text.contains("://") ? text : (decodeBase64(text) ?? text)
        return plain.split(whereSeparator: \.isNewline).compactMap { ServerProfile.parse(String($0)) }
    }

    static func parseUserInfo(_ header: String?) -> SubscriptionInfo {
        var info = SubscriptionInfo()
        for part in (header ?? "").split(separator: ";") {
            let kv = part.split(separator: "=", maxSplits: 1)
            guard kv.count == 2 else { continue }
            let value = Int64(Double(kv[1].trimmingCharacters(in: .whitespaces)) ?? 0)
            switch kv[0].trimmingCharacters(in: .whitespaces).lowercased() {
            case "upload": info.upload = value
            case "download": info.download = value
            case "total": info.total = value
            case "expire": info.expire = value
            default: break
            }
        }
        return info
    }

    static func decodeTitle(_ header: String?) -> String {
        guard let header, !header.isEmpty else { return "" }
        if header.hasPrefix("base64:") { return decodeBase64(String(header.dropFirst(7))) ?? "" }
        return header
    }

    private static func decodeBase64(_ s: String) -> String? {
        var clean = s.filter { !$0.isWhitespace }
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        clean += String(repeating: "=", count: (4 - clean.count % 4) % 4)
        guard let data = Data(base64Encoded: clean) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
