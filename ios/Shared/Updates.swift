import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Checks GitHub for a newer release of the app.
enum Updates {
    static let latestURL = URL(string: "https://api.github.com/repos/cats1223/EthicVPN-app/releases/latest")!
    /// All releases, betas included; used when the user opted into betas. GitHub may sort them by tag name.
    static let allURL = URL(string: "https://api.github.com/repos/cats1223/EthicVPN-app/releases?per_page=100")!

    struct Release: Equatable {
        var version: String
        var pageURL: URL
    }

    static func parse(_ data: Data) -> Release? {
        (try? JSONSerialization.jsonObject(with: data) as? [String: Any]).flatMap(parse)
    }

    /// The newest published release in a list, beta or not, by version: GitHub may list tags as
    /// text, which puts beta.10 after beta.9.
    static func parseNewest(_ data: Data) -> Release? {
        let list = (try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]) ?? []
        return list.filter { $0["draft"] as? Bool != true }.compactMap(parse)
            .reduce(nil) { best, r in best.map { isNewer(r.version, than: $0.version) ? r : $0 } ?? r }
    }

    private static func parse(_ json: [String: Any]) -> Release? {
        guard let tag = json["tag_name"] as? String,
              let page = (json["html_url"] as? String).flatMap(URL.init(string:)) else { return nil }
        return Release(version: tag.hasPrefix("v") ? String(tag.dropFirst()) : tag, pageURL: page)
    }

    /// Compares dotted versions number by number: 1.10.0 is newer than 1.9.2. A beta
    /// (1.2.0-beta.1) is older than its release, so beta testers are offered 1.2.0, and betas of
    /// one version compare by their number: beta.6 is newer than beta.5.
    static func isNewer(_ latest: String, than current: String) -> Bool {
        let a = numbers(latest), b = numbers(current)
        for i in 0..<max(a.count, b.count) {
            let x = i < a.count ? a[i] : 0, y = i < b.count ? b[i] : 0
            if x != y { return x > y }
        }
        switch (isBeta(latest), isBeta(current)) {
        case (false, true): return true
        case (true, true): return betaNumber(latest) > betaNumber(current)
        default: return false
        }
    }

    /// The number at the end of a beta suffix: "1.2.0-beta.6" -> 6.
    private static func betaNumber(_ version: String) -> Int {
        guard let dash = version.firstIndex(of: "-") else { return 0 }
        let digits = version[version.index(after: dash)...].reversed().prefix { $0.isNumber }
        return Int(String(digits.reversed())) ?? 0
    }

    static func isBeta(_ version: String) -> Bool { version.contains("-") }

    private static func numbers(_ version: String) -> [Int] {
        let core = (version.hasPrefix("v") ? version.dropFirst() : Substring(version)).split(separator: "-").first ?? ""
        return core.split(separator: ".").map { Int($0) ?? 0 }
    }

    /// With `includeBeta` the newest beta counts too.
    static func fetchLatest(includeBeta: Bool = false) async throws -> Release {
        var request = URLRequest(url: includeBeta ? allURL : latestURL, timeoutInterval: 10)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard status == 200, let release = includeBeta ? parseNewest(data) : parse(data) else {
            throw URLError(.badServerResponse, userInfo: [NSLocalizedDescriptionKey: "GitHub: HTTP \(status)"])
        }
        return release
    }
}
