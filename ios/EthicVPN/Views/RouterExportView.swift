import SwiftUI

/// The router export: XKeen's outbounds and routing files for a Keenetic, with the selected server
/// and the same rules as the app, so the whole home network goes through the VPN.
struct RouterExportView: View {
    @EnvironmentObject private var model: AppModel
    @AppStorage(AppSettings.russiaDirectKey) private var russiaDirect = true
    @AppStorage(AppSettings.blockAdsKey) private var blockAds = false

    var body: some View {
        List {
            Section {
                Text("1. На Keenetic нужен USB-накопитель и Entware (компонент «OPKG»), на нём — XKeen: github.com/Skrill0/XKeen.\n2. Замените файлы в /opt/etc/xray/configs/ содержимым ниже.\n3. Перезапустите: xkeen -restart")
                    .font(.footnote)
            }
            .listRowBackground(Brand.surface)

            if let profile = model.state.selected,
               let outbounds = try? XrayConfig.routerOutbounds(profile),
               let routing = try? XrayConfig.routerRouting(
                   russiaDirect: russiaDirect, blockAds: blockAds,
                   directSites: AppSettings.sites(AppSettings.directSitesKey), proxySites: AppSettings.sites(AppSettings.proxySitesKey)
               ) {
                file("04_outbounds.json", outbounds)
                file("05_routing.json", routing)
                Section {
                    ShareLink(item: "// 04_outbounds.json\n\(outbounds)\n\n// 05_routing.json\n\(routing)") {
                        Label("Отправить оба файла", systemImage: "square.and.arrow.up")
                    }
                } footer: {
                    Text("В файле ваш ключ — не публикуйте его. Роутер будет считаться этим же устройством в подписке.")
                }
                .listRowBackground(Brand.surface)
            } else {
                Text("Сначала добавьте сервер").foregroundStyle(Brand.muted).listRowBackground(Brand.surface)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Brand.background.ignoresSafeArea())
        .navigationTitle("Экспорт для роутера")
    }

    private func file(_ name: String, _ text: String) -> some View {
        Section {
            ScrollView([.horizontal, .vertical]) {
                Text(text).font(.caption.monospaced()).foregroundStyle(Brand.muted).textSelection(.enabled)
            }
            .frame(maxHeight: 220)
            Button {
                UIPasteboard.general.string = text
                model.message = String(localized: "\(name): скопировано")
            } label: {
                Label("Копировать", systemImage: "doc.on.doc")
            }
        } header: {
            Text(name)
        }
        .listRowBackground(Brand.surface)
    }
}
