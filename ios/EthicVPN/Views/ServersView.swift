import SwiftUI

struct ServersView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showAdd = false
    @State private var showDiagnostics = false
    @AppStorage(AppSettings.devModeKey) private var devMode = false

    var body: some View {
        NavigationStack {
            Group {
                if model.state.profiles.isEmpty {
                    Text("Серверов пока нет. Добавьте ключ или подписку.")
                        .foregroundStyle(Brand.muted)
                        .multilineTextAlignment(.center)
                        .padding(32)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        ForEach(model.state.profiles) { item in
                            row(item)
                                .listRowBackground(Brand.surface)
                                .swipeActions {
                                    // Subscription servers come back on the next refresh, so only manual keys can be deleted.
                                    if !item.fromSubscription {
                                        Button("Удалить", role: .destructive) { model.remove(item.id) }
                                    }
                                }
                        }
                    }
                    .scrollContentBackground(.hidden)
                    .refreshable { await model.refreshSubscription() }
                }
            }
            .background(Brand.background.ignoresSafeArea())
            .navigationTitle("Серверы")
            .toolbar {
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    if model.busy { ProgressView() }
                    Button { Task { await model.pingAll() } } label: { Image(systemName: "speedometer") }
                        .accessibilityLabel("Проверить пинг")
                    if devMode {
                        Button { showDiagnostics = true } label: { Image(systemName: "checklist") }
                            .accessibilityLabel("Проверка подключения")
                    }
                    if !model.state.subscriptionURL.isEmpty {
                        Menu {
                            Button {
                                Task { await model.refreshSubscription() }
                            } label: {
                                Label("Обновить подписку", systemImage: "arrow.clockwise")
                            }
                            Button(role: .destructive) {
                                model.removeSubscription()
                            } label: {
                                Label("Удалить подписку", systemImage: "trash")
                            }
                        } label: { Image(systemName: "ellipsis.circle") }
                    }
                    Button { showAdd = true } label: { Image(systemName: "plus") }
                        .accessibilityLabel("Добавить ключ")
                }
            }
            .sheet(isPresented: $showAdd) { AddView() }
            .sheet(isPresented: $showDiagnostics) { DiagnosticsView() }
        }
    }

    private func row(_ item: StoredProfile) -> some View {
        let p = item.profile
        let selected = p.id == model.state.selected?.id
        return Button {
            Task { await model.select(p.id) }
        } label: {
            HStack(spacing: 14) {
                Circle()
                    .fill(selected ? Brand.cyan : Brand.surfaceHigh)
                    .frame(width: 10, height: 10)
                VStack(alignment: .leading, spacing: 2) {
                    Text(p.name).font(.body.weight(.medium)).lineLimit(1)
                    Text("\(p.network.uppercased()) · \(p.security.uppercased())")
                        .font(.caption).foregroundStyle(Brand.muted)
                }
                Spacer()
                if let ms = model.pings[p.id] {
                    Text(ms < 0 ? "—" : "\(ms) мс")
                        .font(.footnote.monospacedDigit())
                        .foregroundStyle(ms < 0 ? Brand.red : ms < 150 ? Brand.green : ms < 400 ? Brand.amber : Brand.red)
                }
                if selected {
                    Image(systemName: "checkmark").foregroundStyle(Brand.cyan)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
