import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Where an IP address is: the phone's own address before the VPN, the server's after. Asks
/// ipwho.is and falls back to ipapi.co, since a service may refuse requests from a VPN server.
enum GeoIP {
    struct Location: Equatable {
        var ip: String
        var country: String
        var countryCode: String
        var city: String
        var latitude: Double
        var longitude: Double

        var flag: String { GeoIP.flag(countryCode) }
        var place: String {
            var parts: [String] = []
            for part in [city, country] where !part.isEmpty && !parts.contains(part) { parts.append(part) }
            return parts.joined(separator: ", ")
        }
    }

    /// Lookup services in the order they are tried; `ip` nil asks about the caller's address.
    static func urls(_ ip: String?) -> [URL] {
        [
            URL(string: "https://ipwho.is/" + (ip ?? ""))!,
            URL(string: "https://ipapi.co/" + (ip.map { $0 + "/" } ?? "") + "json/")!,
        ]
    }

    /// Reads an ipwho.is or ipapi.co answer; nil for an error or a page that is not JSON.
    static func parse(_ data: Data) -> Location? {
        guard let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              o["success"] as? Bool != false, o["error"] as? Bool != true,
              let lat = (o["latitude"] as? NSNumber)?.doubleValue,
              let lon = (o["longitude"] as? NSNumber)?.doubleValue else { return nil }
        return Location(
            ip: o["ip"] as? String ?? "",
            country: o["country_name"] as? String ?? o["country"] as? String ?? "",
            countryCode: o["country_code"] as? String ?? "",
            city: o["city"] as? String ?? "",
            latitude: lat,
            longitude: lon
        )
    }

    /// "NL" -> 🇳🇱; anything else -> 🌐.
    static func flag(_ countryCode: String) -> String {
        let cc = countryCode.uppercased()
        guard cc.count == 2, cc.unicodeScalars.allSatisfy({ $0.value >= 65 && $0.value <= 90 }) else { return "🌐" }
        return String(String.UnicodeScalarView(cc.unicodeScalars.compactMap { Unicode.Scalar(0x1F1E6 + $0.value - 65) }))
    }

    /// A fresh session each time, so a lookup right after the VPN goes up does not reuse a connection made before it.
    static func lookup(_ ip: String? = nil) async -> Location? {
        let session = URLSession(configuration: .ephemeral)
        defer { session.finishTasksAndInvalidate() }
        for url in urls(ip) {
            var request = URLRequest(url: url, timeoutInterval: 8)
            request.setValue("EthicVPN", forHTTPHeaderField: "User-Agent")
            if let (data, _) = try? await session.data(for: request), let location = parse(data) { return location }
        }
        return nil
    }
}
