import SwiftUI

/// Shows the tail of the tunnel's Xray log so connection problems can be copied and sent.
struct LogView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var vpn: VPNManager
    @EnvironmentObject private var model: AppModel
    @State private var text = ""
    @State private var recording = false

    var body: some View {
        NavigationStack {
            ScrollView {
                recordButton.padding(.horizontal, 12).padding(.top, 12)
                Text(text.isEmpty ? String(localized: "VPN включён, но модуль VPN не вернул журнал.") : text)
                    .font(.system(.caption2, design: .monospaced))
                    .foregroundStyle(text.isEmpty ? Brand.muted : .primary)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(12)
            }
            .background(Brand.background.ignoresSafeArea())
            .navigationTitle("Журнал")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } }
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button { Task { await load() } } label: { Image(systemName: "arrow.clockwise") }
                    Button { UIPasteboard.general.string = text } label: { Image(systemName: "doc.on.doc") }
                        .disabled(text.isEmpty)
                    ShareLink(item: text) { Image(systemName: "square.and.arrow.up") }
                        .disabled(text.isEmpty)
                }
            }
            .task { await load() }
        }
    }

    private var recordButton: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button {
                Task { await record() }
            } label: {
                HStack {
                    if recording { ProgressView().controlSize(.small) } else { Image(systemName: "record.circle") }
                    Text(recording ? "Записываю…" : "Записать журнал (20 с)")
                }
                .font(.subheadline.weight(.semibold))
                .frame(maxWidth: .infinity, minHeight: 44)
                .background(Brand.surface, in: RoundedRectangle(cornerRadius: 12))
            }
            .disabled(recording || model.state.selected == nil)
            Text(recording
                 ? "Сейчас откройте любой сайт в Safari и вернитесь сюда. VPN сам отключится через 20 секунд."
                 : "VPN включится на 20 секунд, затем сам отключится и покажет журнал.")
                .font(.caption)
                .foregroundStyle(Brand.muted)
        }
    }

    private func record() async {
        guard let profile = model.state.selected else { return }
        recording = true
        defer { recording = false }
        let report = await vpn.recordLog(profile)
        text = report ?? String(localized: "Модуль VPN не вернул отчёт: скорее всего, iOS его выгрузила (например, по памяти).")
    }

    private func load() async {
        // The running tunnel answers directly; the shared file only exists when App Groups is signed in.
        var raw = await vpn.fetchTunnelLog()
        if raw?.isEmpty ?? true, let url = AppGroup.logURL, let data = try? Data(contentsOf: url) {
            raw = String(decoding: data, as: UTF8.self)
        }
        guard let raw, !raw.isEmpty else {
            text = vpn.isActive ? "" : String(localized: "Журнал доступен, пока VPN включён.")
            return
        }
        // Info level is chatty; the last part is what matters.
        let lines = raw.split(separator: "\n", omittingEmptySubsequences: false)
        let tail = lines.count > 400 ? [lines[0], "…"] + lines.suffix(400) : Array(lines)
        text = tail.joined(separator: "\n")
    }
}
