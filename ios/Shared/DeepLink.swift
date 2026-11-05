import Foundation

/// ethicvpn:// links that a Telegram bot opens (through the redirect page in server/link-page):
///   ethicvpn://add?url=<url-encoded subscription or vless:// key>
///   ethicvpn://add/<subscription or key>
///   ethicvpn://add  — no link: take it from the clipboard (the bot's "copy, then open" flow)
enum DeepLink {
    static let scheme = "ethicvpn"

    /// True for ethicvpn://add without a link: the subscription is waiting in the clipboard.
    static func wantsClipboard(_ link: String) -> Bool {
        link.trimmingCharacters(in: .whitespacesAndNewlines).lowercased().hasPrefix("\(scheme)://add") && unwrap(link).isEmpty
    }

    /// What to import from `link`, or `link` itself when it is not one of our links.
    static func unwrap(_ link: String) -> String {
        let text = link.trimmingCharacters(in: .whitespacesAndNewlines)
        let prefix = "\(scheme)://add"
        guard text.lowercased().hasPrefix(prefix) else { return text }
        let rest = String(text.dropFirst(prefix.count))
        if rest.hasPrefix("?") {
            let value = rest.dropFirst().split(separator: "&").first { $0.hasPrefix("url=") }?.dropFirst(4)
            return value.map { String($0).replacingOccurrences(of: "+", with: " ").removingPercentEncoding ?? String($0) }?
                .trimmingCharacters(in: .whitespaces) ?? ""
        }
        if rest.hasPrefix("/") {
            // A raw link keeps its "://"; an encoded one has it as %3A%2F%2F.
            let value = String(rest.dropFirst())
            return value.contains("://") ? value : (value.removingPercentEncoding ?? value)
        }
        return ""
    }
}
