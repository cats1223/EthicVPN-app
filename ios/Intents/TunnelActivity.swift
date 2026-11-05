#if canImport(ActivityKit)
import ActivityKit
import Foundation

/// The Live Activity shown while the VPN is up: Dynamic Island and the lock screen. The app
/// starts and ends it; the widget extension draws it. The timer counts by itself, so the
/// activity needs no updates while it runs.
@available(iOS 16.1, *)
struct TunnelActivityAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var since: Date
    }

    var serverName: String
}

#if !WIDGET
/// Starts the Live Activity when the VPN connects and ends it when it goes down. Changes run one
/// after another: the old activity is fully ended before a new one is requested, otherwise iOS
/// kept showing a stale one with the previous session's timer.
@MainActor
enum TunnelActivity {
    /// On by default; "Dynamic Island" in settings.
    static let enabledKey = "liveActivity"
    static var enabled: Bool { UserDefaults.standard.object(forKey: enabledKey) as? Bool ?? true }

    private static var queue: Task<Void, Never>?

    static func update(connected: Bool, since: Date?, serverName: String?) {
        guard #available(iOS 16.2, *) else { return }
        let previous = queue
        queue = Task { @MainActor in
            await previous?.value
            await apply(connected: connected, since: since, serverName: serverName)
        }
    }

    @available(iOS 16.2, *)
    private static func apply(connected: Bool, since: Date?, serverName: String?) async {
        let running = Activity<TunnelActivityAttributes>.activities
        let name = serverName ?? "EthicVPN"
        let wanted = connected && enabled && since != nil && ActivityAuthorizationInfo().areActivitiesEnabled
        // Exactly the one wanted is already showing: nothing to do.
        if wanted, running.count == 1, let current = running.first,
           current.attributes.serverName == name, current.content.state.since == since {
            return
        }
        for activity in running {
            await activity.end(ActivityContent(state: activity.content.state, staleDate: .now), dismissalPolicy: .immediate)
        }
        guard wanted, let since else { return }
        // Stale after 8 hours: iOS ends Live Activities by then anyway, and a stuck one shows as outdated.
        let content = ActivityContent(state: TunnelActivityAttributes.ContentState(since: since),
                                      staleDate: since.addingTimeInterval(8 * 3600))
        _ = try? Activity.request(attributes: TunnelActivityAttributes(serverName: name), content: content)
    }

    /// At launch: whatever an earlier run left behind goes, then the current state is shown.
    static func endAll() {
        guard #available(iOS 16.2, *) else { return }
        let previous = queue
        queue = Task { @MainActor in
            await previous?.value
            for activity in Activity<TunnelActivityAttributes>.activities {
                await activity.end(nil, dismissalPolicy: .immediate)
            }
        }
    }
}
#endif
#endif
