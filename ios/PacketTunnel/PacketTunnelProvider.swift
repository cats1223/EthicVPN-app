import LibXray
import NetworkExtension
import os

final class PacketTunnelProvider: NEPacketTunnelProvider {
    private let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "tunnel", category: "tunnel")
    /// Shared with the app when the App Group is really writable, otherwise private to the
    /// extension; the app can always fetch it through `handleAppMessage`.
    private let logURL = PacketTunnelProvider.writableLogURL()
    /// Our own lines, kept in memory so the app gets something even if no file can be written.
    private var notes: [String] = []
    private var diagnoseTask: Task<Void, Never>?
    private var memoryTask: Task<Void, Never>?
    private var trafficTask: Task<Void, Never>?
    private var lastCounters: (rx: UInt32, tx: UInt32)?
    /// Xray's access log while ad blocking is on; read and emptied with each traffic save.
    private var accessLogURL: URL?
    private static let historyKey = "trafficHistory"

    private static func writableLogURL() -> URL {
        let candidates = [AppGroup.logURL, FileManager.default.temporaryDirectory.appendingPathComponent("xray.log")]
        for case let url? in candidates {
            // Keep the previous run's log: if the tunnel crashed, its last lines say why.
            let previous = previousLogURL(for: url)
            try? FileManager.default.removeItem(at: previous)
            try? FileManager.default.moveItem(at: url, to: previous)
            if FileManager.default.createFile(atPath: url.path, contents: Data()) { return url }
        }
        return FileManager.default.temporaryDirectory.appendingPathComponent("xray.log")
    }

    private static func previousLogURL(for url: URL) -> URL {
        url.deletingLastPathComponent().appendingPathComponent("xray.prev.log")
    }

    /// Go writes a panic or "out of memory" to stderr just before the process dies; send it to the log.
    private func captureCrashOutput() {
        let fd = open(logURL.path, O_WRONLY | O_APPEND)
        guard fd >= 0 else { return }
        dup2(fd, STDERR_FILENO)
        close(fd)
    }

    override func startTunnel(options: [String: NSObject]?) async throws {
        // The app passes the link explicitly; when iOS restarts the tunnel on its own (after a
        // crash, on demand) it comes from the saved configuration.
        let providerConfig = (protocolConfiguration as? NETunnelProviderProtocol)?.providerConfiguration
        let link = options?["link"] as? String ?? providerConfig?["link"] as? String
        guard let profile = link.flatMap(ServerProfile.parse) ?? ProfileStore.load().selected else {
            throw TunnelError.noServer
        }

        captureCrashOutput()
        appendLog("EthicVPN: \(profile.summary)\(options == nil ? " (перезапуск системой)" : "")")
        appendLog("EthicVPN: log \(logURL.path), App Group \(AppGroup.logURL == nil ? "нет в профиле подписи" : "есть")")
        do {
            try await setTunnelNetworkSettings(networkSettings(for: profile))
            guard let fd = tunnelFileDescriptor else { throw TunnelError.noTunDescriptor }
            appendLog("EthicVPN: utun fd \(fd), starting Xray")
            let verbose = options?["verbose"] as? String == "1" || options?["diagnose"] as? String == "1"
                || (options == nil && providerConfig?["verbose"] as? Bool == true)
            let russiaDirect = providerConfig?["russiaDirect"] as? Bool ?? true
            let blockAds = providerConfig?["blockAds"] as? Bool ?? false
            let access = blockAds ? FileManager.default.temporaryDirectory.appendingPathComponent("access.log") : nil
            if let access { FileManager.default.createFile(atPath: access.path, contents: Data()) }
            accessLogURL = access
            let dns = XrayConfig.upstreamDNS(
                choice: providerConfig?["dns"] as? String ?? "", custom: providerConfig?["customDNS"] as? String ?? ""
            )
            // geoip.dat and geosite.dat are bundled with the extension (geodata/ in project.yml).
            let assetDir = Bundle.main.resourceURL?.appendingPathComponent("geodata").path
            appendLog("EthicVPN: russian sites direct \(russiaDirect ? "on" : "off"), ads \(blockAds ? "blocked" : "allowed")")
            let config = try XrayConfig.build(
                profile, tunFd: fd, logPath: verbose ? logURL.path : nil, russiaDirect: russiaDirect, assetDir: assetDir,
                directSites: providerConfig?["directSites"] as? [String] ?? [],
                proxySites: providerConfig?["proxySites"] as? [String] ?? [],
                blockAds: blockAds, dns: dns, accessLogPath: access?.path
            )
            try XrayCore.run(config)
            appendLog("EthicVPN: Xray started, memory \(Self.memoryMB()) MB")
            log.info("Xray started for \(profile.name, privacy: .public)")
            if options?["diagnose"] as? String == "1" { scheduleDiagnosticStop() } else { watchMemory() }
            countTraffic()
        } catch {
            appendLog("EthicVPN: start failed: \(error.localizedDescription)")
            throw error
        }
    }

    /// The app sends "log" and gets the last 64 KB of the log back, or "stats" and gets the
    /// traffic history (TrafficHistory as JSON).
    override func handleAppMessage(_ messageData: Data) async -> Data? {
        switch String(decoding: messageData, as: UTF8.self) {
        case "log": return Data(report(limit: 65_536).utf8)
        case "stats":
            saveTraffic()
            return UserDefaults.standard.data(forKey: Self.historyKey) ?? TrafficHistory().data
        default: return nil
        }
    }

    /// Adds the tunnel's traffic to the per-day history every 10 s.
    private func countTraffic() {
        lastCounters = TunnelCounters.read()
        trafficTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 10_000_000_000)
                self?.saveTraffic()
            }
        }
    }

    private func saveTraffic() {
        let defaults = UserDefaults.standard
        var history = defaults.data(forKey: Self.historyKey).flatMap(TrafficHistory.init(data:)) ?? TrafficHistory()
        if let now = TunnelCounters.read() {
            // The counters are 32-bit and wrap around at 4 GB; unsigned subtraction handles that.
            if let last = lastCounters { history.add(down: Int64(now.rx &- last.rx), up: Int64(now.tx &- last.tx)) }
            lastCounters = now
        }
        // Xray appends to the file, so emptying it after reading loses nothing that matters.
        if let url = accessLogURL, let handle = try? FileHandle(forUpdating: url) {
            let data = handle.readDataToEndOfFile()
            try? handle.truncate(atOffset: 0)
            try? handle.close()
            history.addBlocked(XrayConfig.countBlocked(String(decoding: data, as: UTF8.self)))
        }
        defaults.set(history.data, forKey: Self.historyKey)
    }

    /// A memory line every 30 s: iOS kills a packet tunnel near 50 MB without a word, so the trend
    /// before a drop is the only trace of it.
    private func watchMemory() {
        memoryTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 30_000_000_000)
                guard let self else { return }
                self.appendLog("EthicVPN: memory \(Self.memoryMB()) MB")
                if self.notes.count > 200 { self.notes.removeFirst(self.notes.count - 200) }
            }
        }
    }

    /// Diagnostic run: after 20 s the tunnel stops itself and hands the log to the app as the
    /// disconnect error, which needs neither App Groups nor app-to-provider messages.
    private func scheduleDiagnosticStop() {
        diagnoseTask = Task { [weak self] in
            for second in stride(from: 5, through: 20, by: 5) {
                try? await Task.sleep(nanoseconds: 5_000_000_000)
                self?.appendLog("EthicVPN: \(second) s, memory \(Self.memoryMB()) MB")
            }
            guard let self else { return }
            let error = NSError(domain: "EthicVPN", code: 1, userInfo: [NSLocalizedDescriptionKey: self.report(limit: 8000)])
            self.cancelTunnelWithError(error)
        }
    }

    /// Our notes, then Xray's log with the DNS chatter cut down: the lines about the
    /// connection to the server are what explain a failure, and DNS lines would crowd them out.
    private func report(limit: Int) -> String {
        var text = ""
        if let previous = try? Data(contentsOf: Self.previousLogURL(for: logURL)), !previous.isEmpty {
            // Lines other than DNS chatter from the run before this one, ending where it stopped.
            let lines = String(decoding: previous, as: UTF8.self).split(separator: "\n")
                .filter { !$0.contains("app/dns") && !$0.contains("proxy/dns") && !$0.contains("[dns-out]") }
            text += "— прошлый запуск, конец —\n" + String(lines.joined(separator: "\n").suffix(limit / 4)) + "\n\n"
        }
        text += notes.joined(separator: "\n") + "\n— Xray —\n"
        guard let data = try? Data(contentsOf: logURL) else { return text + "файл журнала не читается" }
        let lines = String(decoding: data, as: UTF8.self).split(separator: "\n")
        // DNS handling and its per-packet routing lines are the bulk; what is left is the
        // connection to the server and errors.
        let isDNS: (Substring) -> Bool = {
            $0.contains("app/dns") || $0.contains("proxy/dns") || $0.contains("[dns-out]")
                || ($0.contains("proxy/tun: processing") && $0.contains(":53"))
        }
        let other = lines.filter { !isDNS($0) }
        let dns = lines.filter(isDNS)
        text += "строк в журнале: \(lines.count), из них DNS: \(dns.count)\n"
        text += String(other.joined(separator: "\n").suffix(limit * 3 / 4))
        text += "\n— последние DNS —\n" + dns.suffix(8).joined(separator: "\n")
        return text
    }

    /// Physical memory of this process; iOS stops packet tunnels at about 50 MB.
    private static func memoryMB() -> Int {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        return result == KERN_SUCCESS ? Int(info.phys_footprint / 1_048_576) : -1
    }

    private func appendLog(_ line: String) {
        notes.append(line)
        guard let handle = try? FileHandle(forWritingTo: logURL) else { return }
        handle.seekToEndOfFile()
        handle.write(Data((line + "\n").utf8))
        try? handle.close()
    }

    override func stopTunnel(with reason: NEProviderStopReason) async {
        diagnoseTask?.cancel()
        memoryTask?.cancel()
        trafficTask?.cancel()
        saveTraffic()
        log.info("stopping tunnel, reason \(reason.rawValue)")
        XrayCore.stop()
    }

    private func networkSettings(for profile: ServerProfile) -> NEPacketTunnelNetworkSettings {
        let settings = NEPacketTunnelNetworkSettings(tunnelRemoteAddress: Self.remoteIP(for: profile.address))
        settings.mtu = NSNumber(value: XrayConfig.tunMTU)

        let ipv4 = NEIPv4Settings(addresses: ["172.19.0.1"], subnetMasks: ["255.255.255.252"])
        ipv4.includedRoutes = [NEIPv4Route.default()]
        // The home network stays outside the tunnel: a PC, printer or console on Wi-Fi is reachable.
        ipv4.excludedRoutes = [("10.0.0.0", "255.0.0.0"), ("169.254.0.0", "255.255.0.0"), ("192.168.0.0", "255.255.0.0")]
            .map { NEIPv4Route(destinationAddress: $0.0, subnetMask: $0.1) }
        settings.ipv4Settings = ipv4

        let ipv6 = NEIPv6Settings(addresses: ["fdfe:dcba:9876::1"], networkPrefixLengths: [126])
        ipv6.includedRoutes = [NEIPv6Route.default()]
        settings.ipv6Settings = ipv6

        let dns = NEDNSSettings(servers: XrayConfig.dnsServers)
        dns.matchDomains = [""]
        settings.dnsSettings = dns
        return settings
    }

    /// iOS rejects the tunnel settings when tunnelRemoteAddress is a host name ("Invalid
    /// NETunnelNetworkSettings tunnelRemoteAddress"), and panels now put a domain in the link.
    /// The field is informational (the extension's own sockets bypass the tunnel), so an IP
    /// literal is passed through, a name is resolved, and a placeholder is used if that fails.
    static func remoteIP(for host: String) -> String {
        let bare = host.trimmingCharacters(in: CharacterSet(charactersIn: "[]"))
        var v4 = in_addr(), v6 = in6_addr()
        if inet_pton(AF_INET, bare, &v4) == 1 || inet_pton(AF_INET6, bare, &v6) == 1 { return bare }
        var hints = addrinfo(ai_flags: 0, ai_family: AF_UNSPEC, ai_socktype: SOCK_STREAM,
                             ai_protocol: 0, ai_addrlen: 0, ai_canonname: nil, ai_addr: nil, ai_next: nil)
        var result: UnsafeMutablePointer<addrinfo>?
        guard getaddrinfo(bare, nil, &hints, &result) == 0, let first = result else { return "127.0.0.1" }
        defer { freeaddrinfo(result) }
        var fallback: String?
        var node: UnsafeMutablePointer<addrinfo>? = first
        while let ai = node {
            var buf = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            if getnameinfo(ai.pointee.ai_addr, ai.pointee.ai_addrlen, &buf, socklen_t(buf.count), nil, 0, NI_NUMERICHOST) == 0 {
                let ip = String(cString: buf)
                if ai.pointee.ai_family == AF_INET { return ip }
                fallback = fallback ?? ip
            }
            node = ai.pointee.ai_next
        }
        return fallback ?? "127.0.0.1"
    }

    /// NetworkExtension does not expose the utun descriptor, so find the open socket whose
    /// control name is utunN (SYSPROTO_CONTROL = 2, UTUN_OPT_IFNAME = 2).
    private var tunnelFileDescriptor: Int32? {
        var buf = [CChar](repeating: 0, count: Int(IFNAMSIZ))
        let prefix = Array("utun".utf8CString.dropLast())
        for fd: Int32 in 0...1024 {
            var len = socklen_t(buf.count)
            if getsockopt(fd, 2, 2, &buf, &len) == 0, buf.starts(with: prefix) {
                return fd
            }
        }
        return nil
    }
}

enum TunnelError: LocalizedError {
    case noServer
    case noTunDescriptor
    case xray(String)

    var errorDescription: String? {
        switch self {
        case .noServer: return String(localized: "Сервер не выбран")
        case .noTunDescriptor: return String(localized: "Не удалось получить дескриптор туннеля")
        case .xray(let message): return message
        }
    }
}

/// Thin wrapper over libXray's JSON `invoke` API (apiVersion 3).
enum XrayCore {
    static func run(_ config: String) throws {
        try call("runXray", payload: ["xrayJson": config])
    }

    static func stop() {
        _ = try? call("stopXray", payload: nil)
    }

    @discardableResult
    private static func call(_ method: String, payload: [String: Any]?) throws -> [String: Any]? {
        var request: [String: Any] = ["apiVersion": 3, "method": method]
        if let payload { request["payload"] = payload }
        let data = try JSONSerialization.data(withJSONObject: request)
        let response = LibXrayInvoke(String(decoding: data, as: UTF8.self))
        guard let json = try JSONSerialization.jsonObject(with: Data(response.utf8)) as? [String: Any] else {
            throw TunnelError.xray("Xray: invalid response")
        }
        guard json["success"] as? Bool == true else {
            throw TunnelError.xray((json["error"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "Xray: \(method) failed")
        }
        return json["data"] as? [String: Any]
    }
}
