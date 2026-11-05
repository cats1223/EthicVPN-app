import AppIntents
#if !WIDGET
import WidgetKit
#endif

/// Turns the VPN on or off; used by the home screen widget.
/// As a LiveActivityIntent it always runs in the app (iOS starts it in the background), which
/// owns the VPN configuration; the widget extension only builds the button.
@available(iOS 17.0, *)
struct SetVPNIntent: SetValueIntent, LiveActivityIntent {
    static var title: LocalizedStringResource = "Включить или выключить EthicVPN"
    static var description = IntentDescription("Подключает или отключает VPN.")

    @Parameter(title: "Включён")
    var value: Bool

    init() {}

    init(value: Bool) {
        self.value = value
    }

    func perform() async throws -> some IntentResult {
        #if !WIDGET
        try await VPNControl.set(on: value)
        #endif
        return .result()
    }
}

#if !WIDGET
/// "Включи EthicVPN" for Siri and Shortcuts.
struct ConnectVPNIntent: AppIntent {
    static var title: LocalizedStringResource = "Включить EthicVPN"
    static var description = IntentDescription("Подключает VPN к выбранному серверу.")

    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await VPNControl.set(on: true)
        return .result(dialog: "EthicVPN подключается")
    }
}

/// "Выключи EthicVPN" for Siri and Shortcuts.
struct DisconnectVPNIntent: AppIntent {
    static var title: LocalizedStringResource = "Выключить EthicVPN"
    static var description = IntentDescription("Отключает VPN.")

    func perform() async throws -> some IntentResult & ProvidesDialog {
        try await VPNControl.set(on: false)
        return .result(dialog: "EthicVPN отключён")
    }
}

/// Phrases Siri knows without any setup; they also appear in the Shortcuts app.
struct EthicShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: ConnectVPNIntent(),
            phrases: ["Включи \(.applicationName)", "Подключи \(.applicationName)", "Turn on \(.applicationName)"],
            shortTitle: "Включить VPN",
            systemImageName: "checkmark.shield"
        )
        AppShortcut(
            intent: DisconnectVPNIntent(),
            phrases: ["Выключи \(.applicationName)", "Отключи \(.applicationName)", "Turn off \(.applicationName)"],
            shortTitle: "Выключить VPN",
            systemImageName: "shield.slash"
        )
    }
}

/// Starts or stops the tunnel from an intent, through the app's own VPN manager.
@MainActor
enum VPNControl {
    struct NoServer: LocalizedError {
        var errorDescription: String? { String(localized: "Сначала добавьте сервер в EthicVPN") }
    }

    static func set(on: Bool) async throws {
        let vpn = VPNManager.shared
        await vpn.load()
        if on {
            guard let profile = ProfileStore.load().selected else { throw NoServer() }
            await vpn.connect(profile)
        } else {
            await vpn.disconnect()
        }
        reloadWidgets()
    }

    /// The widget shows the new state right away.
    static func reloadWidgets() {
        WidgetCenter.shared.reloadAllTimelines()
    }
}
#endif
