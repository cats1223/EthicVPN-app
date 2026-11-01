import CryptoKit
import SwiftUI

enum AppSettings {
    static let themeKey = "theme"
    static let devModeKey = "devMode"
    /// Russian sites and IP ranges go past the VPN; on by default.
    static let russiaDirectKey = "russiaDirect"
    /// Ad and tracker domains are blocked; off by default, since a few sites break without them.
    static let blockAdsKey = "blockAds"
    /// Upstream DNS: "" (Cloudflare and Google), "cloudflare", "google", "adguard" or "custom".
    static let dnsKey = "dns"
    static let customDNSKey = "customDNS"
    /// Block all traffic while the VPN is down (iOS includeAllNetworks).
    static let killSwitchKey = "killSwitch"
    /// Offer betas in the update check too, not only full releases.
    static let betaUpdatesKey = "betaUpdates"

    /// iOS brings the tunnel back up after a drop (on-demand). On by default; it also means the
    /// system VPN switch cannot turn the VPN off, since iOS reconnects at once.
    static let autoReconnectKey = "autoReconnect"
    static var autoReconnect: Bool {
        UserDefaults.standard.object(forKey: autoReconnectKey) as? Bool ?? true
    }

    /// Before connecting, ping the servers and take the fastest.
    static let autoFastestKey = "autoFastest"
    /// The user's sites that always go past the VPN, and those that always go through it.
    static let directSitesKey = "directSites"
    static let proxySitesKey = "proxySites"

    static func sites(_ key: String) -> [String] {
        UserDefaults.standard.stringArray(forKey: key) ?? []
    }

    static var russiaDirect: Bool {
        UserDefaults.standard.object(forKey: russiaDirectKey) as? Bool ?? true
    }

    enum Theme: String, CaseIterable, Identifiable {
        case dark, light
        var id: String { rawValue }
        var title: String { self == .dark ? String(localized: "Чёрная") : String(localized: "Белая") }
    }

    /// SHA-256 of the developer password, so the password itself is not in the source.
    static let devPasswordHash = "ebf975deece2e0b3b0f84f343e7b235ec1bcc459910dfb6ebace4ec35a56df6d"

    static func checkDevPassword(_ password: String) -> Bool {
        SHA256.hash(data: Data(password.utf8)).map { String(format: "%02x", $0) }.joined() == devPasswordHash
    }

    /// The release version, with the beta suffix when there is one; the update check compares it.
    static var shortVersion: String {
        let info = Bundle.main.infoDictionary
        let display = info?["TTDisplayVersion"] as? String ?? ""
        return display.isEmpty ? info?["CFBundleShortVersionString"] as? String ?? "?" : display
    }

    static var version: String {
        "\(shortVersion) (\(Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"))"
    }
}

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @EnvironmentObject private var vpn: VPNManager
    @AppStorage(AppSettings.themeKey) private var theme = AppSettings.Theme.dark.rawValue
    @AppStorage(AppSettings.devModeKey) private var devMode = false
    @AppStorage(AppSettings.russiaDirectKey) private var russiaDirect = true
    @AppStorage(AppSettings.autoFastestKey) private var autoFastest = false
    @AppStorage(AppSettings.autoReconnectKey) private var autoReconnect = true
    @AppStorage(TunnelActivity.enabledKey) private var liveActivity = true
    @State private var versionTaps = 0
    @State private var lastTap = Date.distantPast
    @State private var askPassword = false
    @State private var password = ""
    @State private var showLog = false
    @State private var showDiagnostics = false

    /// The everyday settings; everything else is under "Дополнительно".
    var body: some View {
        NavigationStack {
            List {
                Section("Оформление") {
                    Picker("Тема", selection: $theme) {
                        ForEach(AppSettings.Theme.allCases) { Text($0.title).tag($0.rawValue) }
                    }
                    .pickerStyle(.segmented)
                    .listRowBackground(Brand.surface)
                }

                Section {
                    Toggle("Российские сайты напрямую", isOn: $russiaDirect)
                        .tint(Brand.cyan)
                        .onChange(of: russiaDirect) { _ in
                            Task { await model.applyTunnelSettings() }
                        }
                        .listRowBackground(Brand.surface)
                } header: {
                    Text("Раздельное туннелирование")
                } footer: {
                    Text("Сайты в зонах .ru, .su, .рф и российские адреса открываются без VPN, с вашего обычного адреса. Российские приложения ходят на свои серверы тоже напрямую. Выбрать отдельные приложения на iPhone нельзя: Apple разрешает это только через MDM.")
                }

                Section {
                    Toggle("Самый быстрый сервер", isOn: $autoFastest)
                        .tint(Brand.cyan)
                        .listRowBackground(Brand.surface)
                    Toggle("Dynamic Island", isOn: $liveActivity)
                        .tint(Brand.cyan)
                        .onChange(of: liveActivity) { _ in vpn.refreshActivity() }
                        .listRowBackground(Brand.surface)
                    Toggle("Автопереподключение", isOn: $autoReconnect)
                        .tint(Brand.cyan)
                        .onChange(of: autoReconnect) { _ in
                            Task { await model.applyTunnelSettings() }
                        }
                        .listRowBackground(Brand.surface)
                } header: {
                    Text("Подключение")
                } footer: {
                    Text("Самый быстрый: перед подключением приложение проверит пинг всех серверов. Dynamic Island: таймер и сервер в «островке» и на экране блокировки, пока VPN включён. Автопереподключение: если VPN оборвался, iOS поднимет его сама — но тогда системная кнопка VPN в настройках и шторке не сможет его выключить; выключайте в приложении, виджетом или Siri.")
                }

                Section {
                    NavigationLink { AdvancedSettingsView() } label: { Label("Дополнительно", systemImage: "slider.horizontal.3") }
                        .listRowBackground(Brand.surface)
                }

                Section("О приложении") {
                    HStack {
                        Text("Версия")
                        Spacer()
                        Text(AppSettings.version).foregroundStyle(Brand.muted)
                    }
                    .contentShape(Rectangle())
                    .onTapGesture(perform: tapVersion)
                    .listRowBackground(Brand.surface)
                    Button("Проверить обновления") { Task { await model.checkForUpdate(manual: true) } }
                        .listRowBackground(Brand.surface)
                }

                if devMode { developerSection }
            }
            .scrollContentBackground(.hidden)
            .background(Brand.background.ignoresSafeArea())
            .navigationTitle("Настройки")
            .alert("Режим разработчика", isPresented: $askPassword) {
                SecureField("Пароль", text: $password)
                Button("Отмена", role: .cancel) { password = "" }
                Button("Включить") { unlock() }
            } message: {
                Text("Введите пароль разработчика")
            }
            .sheet(isPresented: $showLog) { LogView() }
            .sheet(isPresented: $showDiagnostics) { DiagnosticsView() }
        }
    }

    private var developerSection: some View {
        Section {
            Button { showLog = true } label: { Label("Журнал подключения", systemImage: "doc.text.magnifyingglass") }
            Button { showDiagnostics = true } label: { Label("Проверка подключения", systemImage: "checklist") }
            copyRow(String(localized: "ID устройства"), DeviceInfo.hwid)
            if let profile = model.state.selected {
                copyRow(String(localized: "Параметры ключа"), profile.summary)
            }
            Button(role: .destructive) { devMode = false } label: {
                Label("Выключить режим разработчика", systemImage: "hammer")
            }
        } header: {
            Text("Разработчик")
        } footer: {
            Text("В режиме разработчика VPN ведёт подробный журнал Xray. Переподключитесь, чтобы он начал записываться.")
        }
        .listRowBackground(Brand.surface)
    }

    private func copyRow(_ title: String, _ value: String) -> some View {
        Button {
            UIPasteboard.general.string = value
            model.message = String(localized: "\(title): скопировано")
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).foregroundStyle(.primary)
                Text(value).font(.caption.monospaced()).foregroundStyle(Brand.muted).lineLimit(3)
            }
        }
    }

    /// Five taps on the version within a couple of seconds ask for the developer password.
    private func tapVersion() {
        let now = Date()
        versionTaps = now.timeIntervalSince(lastTap) < 1.5 ? versionTaps + 1 : 1
        lastTap = now
        guard versionTaps >= 5 else { return }
        versionTaps = 0
        if devMode {
            model.message = String(localized: "Режим разработчика уже включён")
        } else {
            askPassword = true
        }
    }

    private func unlock() {
        if AppSettings.checkDevPassword(password) {
            devMode = true
            model.message = String(localized: "Режим разработчика включён")
        } else {
            model.message = String(localized: "Неверный пароль")
        }
        password = ""
    }
}

/// Settings for those who want them: protection, DNS, own sites, statistics, the speed test,
/// the router export and beta versions.
struct AdvancedSettingsView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage(AppSettings.blockAdsKey) private var blockAds = false
    @AppStorage(AppSettings.killSwitchKey) private var killSwitch = false
    @AppStorage(AppSettings.dnsKey) private var dns = ""
    @AppStorage(AppSettings.betaUpdatesKey) private var betaUpdates = false
    @State private var showSpeedTest = false

    var body: some View {
        List {
            Section {
                Toggle("Блокировать рекламу", isOn: $blockAds)
                    .tint(Brand.cyan)
                    .onChange(of: blockAds) { _ in
                        Task { await model.applyTunnelSettings() }
                    }
                    .listRowBackground(Brand.surface)
                Toggle("Блокировать интернет без VPN", isOn: $killSwitch)
                    .tint(Brand.cyan)
                    .onChange(of: killSwitch) { _ in
                        Task { await model.applyTunnelSettings() }
                    }
                    .listRowBackground(Brand.surface)
                NavigationLink {
                    DNSView { Task { await model.applyTunnelSettings() } }
                } label: {
                    HStack {
                        Text("DNS")
                        Spacer()
                        Text(DNSView.title(dns)).foregroundStyle(Brand.muted)
                    }
                }
                .listRowBackground(Brand.surface)
            } header: {
                Text("Защита")
            } footer: {
                Text("Реклама: рекламные и следящие домены не загружаются — в браузере и в приложениях; если сайт сломался, добавьте его в «Свои сайты». Без VPN: пока VPN переподключается, интернет не работает, чтобы ничего не ушло мимо него.")
            }

            Section {
                NavigationLink {
                    SitesView(
                        title: String(localized: "Всегда напрямую"),
                        footer: String(localized: "Эти сайты открываются без VPN, с вашего обычного адреса. Поддомены тоже."),
                        key: AppSettings.directSitesKey
                    ) { Task { await model.applyTunnelSettings() } }
                } label: {
                    siteListLabel("Всегда напрямую", key: AppSettings.directSitesKey)
                }
                .listRowBackground(Brand.surface)
                NavigationLink {
                    SitesView(
                        title: String(localized: "Всегда через VPN"),
                        footer: String(localized: "Эти сайты идут через VPN, даже если они российские. Поддомены тоже."),
                        key: AppSettings.proxySitesKey
                    ) { Task { await model.applyTunnelSettings() } }
                } label: {
                    siteListLabel("Всегда через VPN", key: AppSettings.proxySitesKey)
                }
                .listRowBackground(Brand.surface)
            } header: {
                Text("Свои сайты")
            }

            Section {
                NavigationLink { StatsView() } label: { Label("Статистика трафика", systemImage: "chart.bar") }
                    .listRowBackground(Brand.surface)
                Button { showSpeedTest = true } label: { Label("Тест скорости", systemImage: "speedometer") }
                    .listRowBackground(Brand.surface)
                NavigationLink { RouterExportView() } label: { Label("Экспорт для роутера Keenetic", systemImage: "wifi.router") }
                    .listRowBackground(Brand.surface)
            }

            Section {
                Toggle("Получать бета-версии", isOn: $betaUpdates)
                    .tint(Brand.cyan)
                    .listRowBackground(Brand.surface)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Brand.background.ignoresSafeArea())
        .navigationTitle("Дополнительно")
        .sheet(isPresented: $showSpeedTest) { SpeedTestView() }
    }

    private func siteListLabel(_ title: LocalizedStringKey, key: String) -> some View {
        HStack {
            Text(title)
            Spacer()
            let count = AppSettings.sites(key).count
            Text(count == 0 ? String(localized: "Нет") : "\(count)").foregroundStyle(Brand.muted)
        }
    }
}
