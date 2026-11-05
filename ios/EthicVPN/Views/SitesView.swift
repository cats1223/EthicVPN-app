import SwiftUI

/// One of the user's site lists: sites that always go past the VPN, or always through it.
struct SitesView: View {
    let title: String
    let footer: String
    let key: String
    var onChange: () -> Void

    @State private var sites: [String] = []
    @State private var entry = ""

    var body: some View {
        List {
            Section {
                HStack {
                    TextField("site.com", text: $entry)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .onSubmit(add)
                    Button("Добавить", action: add)
                        .disabled(XrayConfig.siteDomain(entry) == nil)
                }
                .listRowBackground(Brand.surface)
            } footer: {
                Text(footer)
            }

            if !sites.isEmpty {
                Section {
                    ForEach(sites, id: \.self) { Text($0) }
                        .onDelete { offsets in
                            sites.remove(atOffsets: offsets)
                            save()
                        }
                        .listRowBackground(Brand.surface)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(Brand.background.ignoresSafeArea())
        .navigationTitle(title)
        .onAppear { sites = AppSettings.sites(key) }
    }

    private func add() {
        guard let domain = XrayConfig.siteDomain(entry) else { return }
        if !sites.contains(domain) { sites.append(domain) }
        entry = ""
        save()
    }

    private func save() {
        UserDefaults.standard.set(sites, forKey: key)
        onChange()
    }
}
