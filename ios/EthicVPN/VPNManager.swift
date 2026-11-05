import Combine
import NetworkExtension

/// Owns the NETunnelProviderManager that points at the PacketTunnel extension.
@MainActor
final class VPNManager: ObservableObject {
    @Published private(set) var status: NEVPNStatus = .invalid
    @Published private(set) var connectedDate: Date?
    @Published var lastError: String?

    private var manager: NETunnelProviderManager?
    private var observer: NSObjectProtocol?

    /// One manager for the app and the Siri/widget intents, which run in the app process too.
    static let shared = VPNManager()

    private init() {
        TunnelActivity.endAll()
        Task { await load() }
    }

    var isActive: Bool { [.connected, .connecting, .reasserting].contains(status) }

    func load() async {
        let managers = (try? await NETunnelProviderManager.loadAllFromPreferences()) ?? []
        let manager = managers.first ?? NETunnelProviderManager()
        self.manager = manager
        observe(manager)
        refresh()
    }

    func connect(_ profile: ServerProfile, diagnose: Bool = false) async {
        lastError = nil
        do {
            // A diagnostic run stops itself on purpose, so it must not be restarted on demand.
            let manager = try await preparedManager(profile, reconnectOnFailure: !diagnose)
            var options: [String: NSObject] = ["link": profile.link as NSString]
            if diagnose { options["diagnose"] = "1" as NSString }
            // The detailed Xray log is only kept in developer mode.
            if UserDefaults.standard.bool(forKey: AppSettings.devModeKey) { options["verbose"] = "1" as NSString }
            try manager.connection.startVPNTunnel(options: options)
        } catch {
            lastError = error.localizedDescription
        }
    }

    /// Asks the running tunnel for its log; works without App Groups in the signing profile.
    func fetchTunnelLog() async -> String? {
        guard let session = manager?.connection as? NETunnelProviderSession, isActive else { return nil }
        return await withCheckedContinuation { continuation in
            do {
                try session.sendProviderMessage(Data("log".utf8)) { data in
                    continuation.resume(returning: data.map { String(decoding: $0, as: UTF8.self) })
                }
            } catch {
                continuation.resume(returning: nil)
            }
        }
    }

    /// Traffic per day from the running tunnel; the last answer is kept for when it is off.
    func fetchStats() async -> TrafficHistory {
        let key = "trafficHistoryCache"
        let cached = UserDefaults.standard.data(forKey: key).flatMap(TrafficHistory.init(data:)) ?? TrafficHistory()
        guard let session = manager?.connection as? NETunnelProviderSession, isActive else { return cached }
        let data: Data? = await withCheckedContinuation { continuation in
            do {
                try session.sendProviderMessage(Data("stats".utf8)) { continuation.resume(returning: $0) }
            } catch {
                continuation.resume(returning: nil)
            }
        }
        guard let data, let fresh = TrafficHistory(data: data) else { return cached }
        UserDefaults.standard.set(data, forKey: key)
        return fresh
    }

    /// Runs the tunnel for 20 s in diagnostic mode and returns the log it hands back as its
    /// disconnect error, or nil when the tunnel died without reporting.
    func recordLog(_ profile: ServerProfile) async -> String? {
        if isActive {
            await disconnect()
            for _ in 0..<50 where status != .disconnected { try? await Task.sleep(nanoseconds: 100_000_000) }
        }
        await connect(profile, diagnose: true)
        var wasActive = false
        for _ in 0..<600 {
            if isActive { wasActive = true } else if wasActive || lastError != nil { break }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        guard let connection = manager?.connection else { return lastError }
        return await withCheckedContinuation { continuation in
            connection.fetchLastDisconnectError { error in
                continuation.resume(returning: error.map { ($0 as NSError).localizedDescription })
            }
        }
    }

    /// Turns automatic reconnection off first, or iOS would bring the tunnel straight back up.
    func disconnect() async {
        if let manager, manager.isOnDemandEnabled {
            manager.isOnDemandEnabled = false
            try? await manager.saveToPreferences()
        }
        manager?.connection.stopVPNTunnel()
    }

    /// Restarts the tunnel when it is running, so a newly selected server takes effect.
    func reconnect(_ profile: ServerProfile) async {
        guard isActive else { return }
        await disconnect()
        for _ in 0..<50 where status != .disconnected {
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        await connect(profile)
    }

    private func preparedManager(_ profile: ServerProfile, reconnectOnFailure: Bool) async throws -> NETunnelProviderManager {
        let manager = manager ?? NETunnelProviderManager()
        let proto = (manager.protocolConfiguration as? NETunnelProviderProtocol) ?? NETunnelProviderProtocol()
        proto.providerBundleIdentifier = AppGroup.tunnelBundleId
        proto.serverAddress = profile.address
        proto.disconnectOnSleep = false
        // Saved with the configuration, so the tunnel has everything when iOS restarts it on its
        // own: without App Groups it cannot read the app's server list.
        proto.providerConfiguration = [
            "russiaDirect": AppSettings.russiaDirect,
            "blockAds": UserDefaults.standard.bool(forKey: AppSettings.blockAdsKey),
            "link": profile.link,
            "verbose": UserDefaults.standard.bool(forKey: AppSettings.devModeKey),
            "directSites": AppSettings.sites(AppSettings.directSitesKey),
            "proxySites": AppSettings.sites(AppSettings.proxySitesKey),
            "dns": UserDefaults.standard.string(forKey: AppSettings.dnsKey) ?? "",
            "customDNS": UserDefaults.standard.string(forKey: AppSettings.customDNSKey) ?? "",
        ]
        // Kill switch: everything must go through the tunnel, so nothing leaks while it is down;
        // the local network (printers, AirPlay) stays reachable.
        let killSwitch = UserDefaults.standard.bool(forKey: AppSettings.killSwitchKey)
        proto.includeAllNetworks = killSwitch
        proto.excludeLocalNetworks = killSwitch
        manager.protocolConfiguration = proto
        manager.localizedDescription = "EthicVPN"
        manager.isEnabled = true
        // If the tunnel drops with an error (or the extension is killed), iOS starts it again.
        manager.onDemandRules = [NEOnDemandRuleConnect()]
        manager.isOnDemandEnabled = reconnectOnFailure && AppSettings.autoReconnect
        // The first save shows the system "Add VPN configuration" prompt.
        try await manager.saveToPreferences()
        try await manager.loadFromPreferences()
        self.manager = manager
        observe(manager)
        return manager
    }

    /// Shows or ends the Live Activity to match the tunnel.
    func refreshActivity() {
        TunnelActivity.update(connected: status == .connected, since: connectedDate, serverName: ProfileStore.load().selected?.name)
    }

    private func observe(_ manager: NETunnelProviderManager) {
        if let observer { NotificationCenter.default.removeObserver(observer) }
        observer = NotificationCenter.default.addObserver(
            forName: .NEVPNStatusDidChange, object: manager.connection, queue: .main
        ) { [weak self] _ in
            Task { @MainActor in self?.refresh() }
        }
    }

    private func refresh() {
        guard let connection = manager?.connection else { return }
        if connection.status != status, [.connected, .disconnected].contains(connection.status) {
            VPNControl.reloadWidgets()
        }
        status = connection.status
        connectedDate = connection.status == .connected ? connection.connectedDate : nil
        if [.connected, .disconnected, .invalid].contains(connection.status) { refreshActivity() }
        if connection.status == .disconnected {
            connection.fetchLastDisconnectError { [weak self] error in
                guard let error else { return }
                Task { @MainActor in self?.lastError = error.localizedDescription }
            }
        }
    }
}
