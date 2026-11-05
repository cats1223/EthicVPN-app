import Foundation

/// Reads CHANGELOG.md (bundled with the app) for the "what's new" screen after an update.
enum Changelog {
    /// The lines under "## v<version>" up to the next version, or nil if there is no such section.
    static func section(_ text: String, version: String) -> String? {
        let lines = text.components(separatedBy: "\n")
        guard let start = lines.firstIndex(where: { $0.trimmingCharacters(in: .whitespaces) == "## v\(version)" }) else { return nil }
        let body = lines[(start + 1)...].prefix { !$0.hasPrefix("## ") }
        let text = body.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }
}
