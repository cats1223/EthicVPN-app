import SwiftUI

/// Picks the resolver the VPN asks for site addresses; queries go through the server.
struct DNSView: View {
    var onChange: () -> Void
    @AppStorage(AppSettings.dnsKey) private var dns = ""
    @AppStorage(AppSettings.customDNSKey) private var custom = ""
    @State private var draft = ""

    struct Choice: Identifiable {
        let id: String
        let title: LocalizedStringKey
        let detail: LocalizedStringKey
    }

    static let choices = [
        Choice(id: "", title: "Автоматически", detail: "Cloudflare и Google"),
        Choice(id: "cloudflare", title: "Cloudflare", detail: "1.1.1.1 — быстрый"),
        Choice(id: "google", title: "Google", detail: "8.8.8.8"),
        Choice(id: "adguard", title: "AdGuard", detail: "Ещё и блокирует рекламу"),
        Choice(id: "custom", title: "Свой", detail: "Адрес IPv4 или https://…/dns-query"),
    ]

    static func title(_ id: String) -> String {
        switch id {
        case "cloudflare": return "Cloudflare"
        case "google": return "Google"
        case "adguard": return "AdGuard"
        case "custom": return String(localized: "Свой")
        default: return String(localized: "Автоматически")
        }
    }

    var body: some View {
        List {
            Section {
                ForEach(Self.choices) { choice in
                    Button {
                        guard dns != choice.id else { return }
                        dns = choice.id
                        if choice.id != "custom" || XrayConfig.customDNS(custom) != nil { onChange() }
                    } label: {
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(choice.title).foregroundStyle(.primary)
                                Text(choice.detail).font(.caption).foregroundStyle(Brand.muted)
                            }
                            Spacer()
                            if dns == choice.id { Image(systemName: "checkmark").foregroundStyle(Brand.cyan) }
                        }
                    }
                }
            } footer: {
                Text("Запросы к DNS идут через сервер VPN, провайдер их не видит.")
            }
            .listRowBackground(Brand.surface)

            if dns == "custom" {
                Section {
                    TextField("9.9.9.9", text: $draft)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .onSubmit(save)
                    Button("Сохранить", action: save).disabled(XrayConfig.customDNS(draft) == nil)
                } header: {
                    Text("Свой DNS")
                } footer: {
                    Text("Адрес IPv4 (например 9.9.9.9) или ссылка DNS-over-HTTPS с IP-адресом: https://9.9.9.9/dns-query.")
                }
                .listRowBackground(Brand.surface)
            }
        }
        .scrollContentBackground(.hidden)
        .background(Brand.background.ignoresSafeArea())
        .navigationTitle("DNS")
        .onAppear { draft = custom }
    }

    private func save() {
        guard XrayConfig.customDNS(draft) != nil else { return }
        custom = draft.trimmingCharacters(in: .whitespaces)
        onChange()
    }
}
