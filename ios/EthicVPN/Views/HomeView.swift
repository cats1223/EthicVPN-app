import NetworkExtension
import SwiftUI

/// The globe fills the screen; a glass panel at the bottom holds the status, the connect button
/// and the servers, and pulls up for the subscription, all servers and settings.
struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var vpn: VPNManager
    @State private var expanded = false
    @State private var showAdd = false
    @State private var showServers = false
    @State private var showSettings = false
    @State private var previousStatus: NEVPNStatus = .invalid
    @State private var burst = false

    private var connected: Bool { vpn.status == .connected }
    private var busy: Bool { [.connecting, .reasserting, .disconnecting].contains(vpn.status) }

    var body: some View {
        ZStack {
            GlobeMap(
                home: model.homeLocation,
                server: model.selectedServerLocation,
                serverName: model.state.selected?.name ?? String(localized: "Сервер"),
                connected: connected
            )
            .ignoresSafeArea()

            VStack(spacing: 0) {
                StatusPill(status: vpn.status, error: vpn.lastError)
                    .padding(.top, 8)
                Spacer()
                panel
            }
        }
        .background(Color.black.ignoresSafeArea())
        .messageAlert(active: !showAdd && !showServers && !showSettings)
        .sheet(isPresented: $showAdd) { AddView().messageAlert() }
        .sheet(isPresented: $showServers) { ServersView().messageAlert().presentationDragIndicator(.visible) }
        .sheet(isPresented: $showSettings) { SettingsView().messageAlert().presentationDragIndicator(.visible) }
        .task {
            if connected { model.traffic.start() }
            await model.refreshLocation(connected: connected)
            await model.locateSelectedServer()
        }
        .onChange(of: vpn.status) { status in
            if status == .connected {
                // The internet card starts its live ping itself once it shows up.
                model.traffic.start()
            } else {
                model.resetInternetCheck()
                model.traffic.stop()
            }
            if status == .connected || status == .disconnected {
                Task { await model.refreshLocation(connected: status == .connected) }
            }
        }
        .onChange(of: model.state.selected?.address) { _ in
            Task { await model.locateSelectedServer() }
        }
        // Ping the servers for the chips; again whenever the list changes.
        .task(id: model.state.profiles.map(\.id)) { await model.pingAll() }
    }

    // MARK: - Panel

    private var panel: some View {
        VStack(spacing: 16) {
            Capsule()
                .fill(.secondary)
                .frame(width: 40, height: 5)
                .padding(.top, 8)
                .frame(maxWidth: .infinity)
                .contentShape(Rectangle())
                .onTapGesture { withAnimation(.spring()) { expanded.toggle() } }

            statusCard

            if !model.state.profiles.isEmpty {
                serverChips
            }

            if expanded {
                Group {
                    if connected { internetCheck }
                    if let info = model.state.subscriptionInfo { SubscriptionCard(info: info) }
                    menu
                }
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.spring(response: 0.45, dampingFraction: 0.85), value: expanded)
        .padding(.horizontal, 16)
        .padding(.bottom, 12)
        .liquidGlass(in: RoundedRectangle(cornerRadius: 32, style: .continuous))
        .padding(.horizontal, 10)
        .gesture(
            DragGesture(minimumDistance: 20).onEnded { value in
                withAnimation(.spring()) {
                    if value.translation.height < -40 { expanded = true }
                    if value.translation.height > 40 { expanded = false }
                }
            }
        )
    }

    private var statusCard: some View {
        VStack(spacing: 14) {
            HStack(spacing: 14) {
                ZStack {
                    // A ring that bursts out once when the connection comes up.
                    Circle()
                        .stroke(Brand.green, lineWidth: 3)
                        .frame(width: 44, height: 44)
                        .scaleEffect(burst ? 2.2 : 0.6)
                        .opacity(burst ? 0 : (connected ? 0.9 : 0))
                    Image(systemName: statusIcon)
                        .font(.system(size: 30))
                        .foregroundStyle(connected ? Brand.green : busy ? Brand.amber : Color.secondary)
                        .id(statusIcon)
                        .transition(.scale(scale: 0.4).combined(with: .opacity))
                        .symbolPulse(busy)
                }
                .frame(width: 44)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.title3.weight(.semibold))
                        .id(title)
                        .transition(.asymmetric(insertion: .move(edge: .bottom).combined(with: .opacity), removal: .opacity))
                    subtitle.font(.footnote).foregroundStyle(.secondary).lineLimit(2)
                }
                Spacer(minLength: 0)
            }

            if connected {
                TrafficLine(meter: model.traffic)
                    .transition(.move(edge: .top).combined(with: .opacity))
            }

            if model.state.selected == nil {
                bigButton(String(localized: "Добавить ключ"), color: Brand.cyan) { showAdd = true }
            } else {
                bigButton(buttonTitle, color: connected || busy ? Brand.red : Brand.cyan) {
                    Feedback.tap()
                    Task { await model.toggle() }
                }
            }
        }
        .padding(16)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .stroke(connected ? Brand.green.opacity(0.6) : .white.opacity(0.12), lineWidth: connected ? 1.5 : 1)
        )
        .animation(.spring(response: 0.5, dampingFraction: 0.7), value: vpn.status)
        .onChange(of: vpn.status) { status in
            // Only real transitions: opening the app also moves the status from "unknown".
            let previous = previousStatus
            previousStatus = status
            switch status {
            case .connected where [.connecting, .reasserting].contains(previous):
                Feedback.connected()
            case .disconnected where [.connected, .disconnecting, .connecting, .reasserting].contains(previous):
                if previous == .connecting, vpn.lastError != nil { Feedback.failed() } else { Feedback.disconnected() }
            default: break
            }
            guard status == .connected else { return }
            burst = false
            withAnimation(.easeOut(duration: 0.9)) { burst = true }
        }
    }

    private var statusIcon: String {
        connected ? "checkmark.shield.fill" : busy ? "shield.lefthalf.filled" : "exclamationmark.triangle"
    }

    private var title: String {
        switch vpn.status {
        case .connected: return String(localized: "Вы защищены")
        case .connecting, .reasserting: return String(localized: "Подключение…")
        case .disconnecting: return String(localized: "Отключение…")
        default: return String(localized: "Вы не защищены")
        }
    }

    @ViewBuilder private var subtitle: some View {
        if vpn.status == .disconnected, let error = vpn.lastError {
            Text(error)
        } else if connected, let since = vpn.connectedDate {
            TimelineView(.periodic(from: since, by: 1)) { context in
                Text(locationLine(model.visibleLocation, prefix: String(localized: "Сайты видят")) + " · " + duration(context.date.timeIntervalSince(since)))
                    .monospacedDigit()
            }
        } else if let home = model.homeLocation {
            Text(locationLine(home, prefix: String(localized: "Ваш адрес")))
        } else {
            Text("Подключитесь к VPN для защиты")
        }
    }

    private var buttonTitle: String {
        switch vpn.status {
        case .connected: return String(localized: "Отключиться")
        case .connecting, .reasserting: return String(localized: "Отменить")
        case .disconnecting: return String(localized: "Отключение…")
        default: return String(localized: "Подключиться")
        }
    }

    private func bigButton(_ title: String, color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.headline)
                .frame(maxWidth: .infinity, minHeight: 54)
                .foregroundStyle(.white)
        }
        .glassProminentButton(color)
        .disabled(vpn.status == .disconnecting)
    }

    private var serverChips: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Серверы").font(.subheadline).foregroundStyle(.secondary).padding(.leading, 4)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 10) {
                    ForEach(model.state.profiles) { item in
                        let selected = item.id == model.state.selected?.id
                        Button {
                            Task { await model.select(item.id) }
                        } label: {
                            HStack(spacing: 8) {
                                Text(flag(for: item.profile)).font(.title3)
                                Text(item.profile.name.strippingLeadingFlag).lineLimit(1)
                                if let ms = model.pings[item.id] {
                                    Text(ms > 0 ? "\(ms) мс" : "—")
                                        .font(.caption.monospacedDigit())
                                        .foregroundStyle(ms <= 0 ? Brand.red : ms < 150 ? Brand.green : ms < 400 ? Brand.amber : Brand.red)
                                }
                            }
                            .padding(.horizontal, 16)
                            .frame(height: 46)
                            .liquidGlass(in: Capsule(), tint: selected ? Brand.cyan.opacity(0.35) : nil, interactive: true)
                            .foregroundStyle(selected ? Brand.cyan : .primary)
                            .scaleEffect(selected ? 1 : 0.95)
                            .animation(.spring(response: 0.35, dampingFraction: 0.6), value: selected)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }

    private var internetCheck: some View {
        Button {
            model.startLivePing()
        } label: {
            HStack(spacing: 8) {
                switch model.internetCheck {
                case .idle:
                    Image(systemName: "network")
                    Text("Проверить интернет")
                case .running:
                    ProgressView().controlSize(.small)
                    Text("Проверяю интернет…")
                case .ok(let ms):
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(Brand.green)
                    // A String, not an Int, so SwiftUI doesn't add a thousands separator ("1,051").
                    Text("Интернет работает · \(String(ms)) мс").monospacedDigit()
                case .failed(let reason):
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Brand.amber)
                    Text("Интернета нет: \(reason)").multilineTextAlignment(.leading)
                }
                Spacer(minLength: 0)
            }
            .font(.footnote)
            .padding(14)
            .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(model.internetCheck == .running)
        .onAppear { model.startLivePing() }
        .onDisappear { model.stopLivePing() }
    }

    private var menu: some View {
        VStack(spacing: 0) {
            menuRow(String(localized: "Все серверы"), icon: "server.rack") { showServers = true }
            Divider().opacity(0.4)

            menuRow(String(localized: "Настройки"), icon: "gearshape") { showSettings = true }
        }
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).stroke(.white.opacity(0.12)))
    }

    private func menuRow(_ title: String, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: icon).frame(width: 24)
                Text(title)
                Spacer()
                Image(systemName: "chevron.right").font(.footnote).foregroundStyle(.secondary)
            }
            .padding(.horizontal, 18)
            .frame(height: 54)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Helpers

    private func flag(for profile: ServerProfile) -> String {
        profile.name.leadingFlag ?? model.serverLocations[profile.address]?.flag ?? "🌐"
    }

    private func locationLine(_ location: GeoIP.Location?, prefix: String) -> String {
        guard let location else { return "\(prefix): " + String(localized: "определяю…") }
        return "\(prefix): \(location.flag) \(location.place)"
    }

    private func duration(_ t: TimeInterval) -> String {
        let s = Int(max(0, t))
        return String(format: "%02d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    }
}

/// Download and upload speed right now, and what the session has used.
private struct TrafficLine: View {
    @ObservedObject var meter: TrafficMeter

    var body: some View {
        HStack(spacing: 14) {
            Label(formatRate(meter.down), systemImage: "arrow.down")
            Label(formatRate(meter.up), systemImage: "arrow.up")
            Spacer(minLength: 0)
            Text(formatBytes(Int64(meter.sessionDown + meter.sessionUp)))
                .foregroundStyle(.secondary)
        }
        .font(.footnote.monospacedDigit())
        .animation(.default, value: meter.down)
        .padding(.horizontal, 4)
    }
}

private struct StatusPill: View {
    let status: NEVPNStatus
    let error: String?
    @State private var pulse = false

    private var busy: Bool { [.connecting, .reasserting, .disconnecting].contains(status) }

    var body: some View {
        HStack(spacing: 10) {
            ZStack {
                // While connecting the dot sends out rings; once connected it glows.
                Circle().fill(color.opacity(0.5)).frame(width: 12, height: 12)
                    .scaleEffect(busy && pulse ? 2.4 : 1)
                    .opacity(busy && pulse ? 0 : (busy ? 0.8 : 0))
                Circle().fill(color).frame(width: 12, height: 12)
                    .shadow(color: status == .connected ? color : .clear, radius: 6)
            }
            Text(text).font(.headline).foregroundStyle(.white)
                .id(text)
                .transition(.opacity.combined(with: .scale(scale: 0.9)))
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .liquidGlass(in: Capsule(), tint: .black.opacity(0.35))
        .animation(.spring(response: 0.4, dampingFraction: 0.8), value: status)
        .onAppear {
            withAnimation(.easeOut(duration: 1.1).repeatForever(autoreverses: false)) { pulse = true }
        }
    }

    private var text: String {
        switch status {
        case .connected: return String(localized: "Подключено")
        case .connecting, .reasserting: return String(localized: "Подключение…")
        case .disconnecting: return String(localized: "Отключение…")
        default: return error == nil ? String(localized: "Не подключено") : String(localized: "Ошибка подключения")
        }
    }

    private var color: Color {
        switch status {
        case .connected: return Brand.green
        case .connecting, .reasserting, .disconnecting: return Brand.amber
        default: return Brand.red
        }
    }
}

private struct SubscriptionCard: View {
    let info: SubscriptionInfo

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(info.title.isEmpty ? String(localized: "Подписка") : info.title).font(.body.weight(.semibold))
            let used = info.upload + info.download
            if info.total > 0 {
                ProgressView(value: min(1, Double(used) / Double(info.total))).tint(Brand.cyan)
                Text("Трафик: \(formatBytes(used)) из \(formatBytes(info.total))")
                    .font(.footnote).foregroundStyle(.secondary)
            } else {
                Text("Использовано: \(formatBytes(used))").font(.footnote).foregroundStyle(.secondary)
            }
            if info.expire > 0 {
                let days = max(0, Int((Double(info.expire) - Date().timeIntervalSince1970) / 86_400))
                Text("Осталось дней: \(days)")
                    .font(.footnote)
                    .foregroundStyle(days <= 3 ? Brand.amber : .secondary)
            }
            // A message the panel sends to everyone (Remnawave "announce").
            if let announce = info.announce, !announce.isEmpty {
                Label(announce, systemImage: "megaphone").font(.footnote).foregroundStyle(Brand.cyan)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.white.opacity(0.06), in: RoundedRectangle(cornerRadius: 24, style: .continuous))
    }
}

private extension String {
    /// A flag emoji at the start of a server name, like "🇳🇱 Нидерланды".
    var leadingFlag: String? {
        let scalars = Array(unicodeScalars.prefix(2))
        guard scalars.count == 2, scalars.allSatisfy({ (0x1F1E6...0x1F1FF).contains($0.value) }) else { return nil }
        return String(String.UnicodeScalarView(scalars))
    }

    var strippingLeadingFlag: String {
        guard leadingFlag != nil else { return self }
        return String(String.UnicodeScalarView(unicodeScalars.dropFirst(2))).trimmingCharacters(in: .whitespaces)
    }
}
