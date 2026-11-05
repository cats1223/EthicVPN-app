import Foundation

enum AppGroup {
    /// Both come from build settings (APP_GROUP_ID, TUNNEL_BUNDLE_ID) via Info.plist, so CI can re-sign under other ids.
    static let id = Bundle.main.object(forInfoDictionaryKey: "AppGroupIdentifier") as? String ?? "group.com.ethic.vpn"
    static let tunnelBundleId = Bundle.main.object(forInfoDictionaryKey: "TunnelBundleIdentifier") as? String
        ?? (Bundle.main.bundleIdentifier ?? "com.ethic.vpn") + ".tunnel"

    static var defaults: UserDefaults { UserDefaults(suiteName: id) ?? .standard }

    /// Xray's log, written by the tunnel and read by the app.
    static var logURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id)?.appendingPathComponent("xray.log")
    }
}

struct StoredProfile: Codable, Hashable, Identifiable {
    var profile: ServerProfile
    var fromSubscription: Bool
    var id: String { profile.id }
}

struct ProfilesState: Codable, Hashable {
    var profiles: [StoredProfile] = []
    var selectedId: String?
    var subscriptionURL = ""
    var subscriptionInfo: SubscriptionInfo?

    var selected: ServerProfile? {
        (profiles.first { $0.id == selectedId } ?? profiles.first)?.profile
    }
}

extension ProfilesState {
    private enum CodingKeys: String, CodingKey { case profiles, selectedId, subscriptionURL, subscriptionInfo }

    /// A saved server whose link this version no longer reads (such as Hysteria2) is dropped
    /// instead of failing the whole list.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        profiles = try c.decode([Failable<StoredProfile>].self, forKey: .profiles).compactMap(\.value)
        selectedId = try c.decodeIfPresent(String.self, forKey: .selectedId)
        subscriptionURL = try c.decodeIfPresent(String.self, forKey: .subscriptionURL) ?? ""
        subscriptionInfo = try c.decodeIfPresent(SubscriptionInfo.self, forKey: .subscriptionInfo)
    }
}

private struct Failable<T: Decodable>: Decodable {
    let value: T?
    init(from decoder: Decoder) throws {
        value = try? T(from: decoder)
    }
}

/// Persists servers in the shared App Group so the packet tunnel can read the selection on its own
/// (for example when iOS restarts it through on-demand rules).
enum ProfileStore {
    private static let key = "profilesState"

    static func load() -> ProfilesState {
        guard let data = AppGroup.defaults.data(forKey: key),
              let state = try? JSONDecoder().decode(ProfilesState.self, from: data)
        else { return ProfilesState() }
        return state
    }

    static func save(_ state: ProfilesState) {
        if let data = try? JSONEncoder().encode(state) {
            AppGroup.defaults.set(data, forKey: key)
        }
    }
}
