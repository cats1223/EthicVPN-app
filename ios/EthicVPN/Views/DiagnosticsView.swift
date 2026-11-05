import SwiftUI

/// Connection checklist; starts on its own when the window opens.
struct DiagnosticsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(model.diagnostics) { step in
                        DiagnosticRow(step: step).listRowBackground(Brand.surface)
                    }
                }
                if !model.diagnosticsRunning, !model.diagnostics.isEmpty {
                    Section {
                        Text(summary)
                            .font(.footnote)
                            .foregroundStyle(Brand.muted)
                            .listRowBackground(Color.clear)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background(Brand.background.ignoresSafeArea())
            .navigationTitle("Проверка подключения")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if model.diagnosticsRunning {
                        ProgressView()
                    } else {
                        Button("Ещё раз") { Task { await model.runDiagnostics() } }
                    }
                }
            }
            .task { await model.runDiagnostics() }
        }
        .presentationDetents([.medium, .large])
    }

    private var summary: String {
        let states = model.diagnostics.map(\.state)
        if states.allSatisfy({ if case .ok = $0 { return true } else { return false } }) {
            return String(localized: "Всё в порядке: трафик идёт через ваш сервер.")
        }
        if let failed = model.diagnostics.first(where: { if case .failed = $0.state { return true } else { return false } }) {
            return String(localized: "Проблема на шаге «\(failed.kind.title)». Подробности — в «Журнале подключения» на главном экране.")
        }
        return String(localized: "Проверка завершена.")
    }
}

private struct DiagnosticRow: View {
    let step: DiagnosticStep

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            icon.frame(width: 22, height: 22)
            VStack(alignment: .leading, spacing: 2) {
                Text(step.kind.title)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(step.state == .pending || step.state == .skipped ? Brand.muted : .primary)
                if let detail {
                    Text(detail).font(.caption).foregroundStyle(Brand.muted)
                }
            }
        }
        .padding(.vertical, 2)
        .animation(.easeInOut(duration: 0.2), value: step.state)
    }

    @ViewBuilder private var icon: some View {
        switch step.state {
        case .pending: Image(systemName: "circle").foregroundStyle(Brand.surfaceHigh)
        case .running: ProgressView().controlSize(.small)
        case .skipped: Image(systemName: "minus.circle").foregroundStyle(Brand.muted)
        case .ok: Image(systemName: "checkmark.circle.fill").foregroundStyle(Brand.green)
        case .warning: Image(systemName: "exclamationmark.circle.fill").foregroundStyle(Brand.amber)
        case .failed: Image(systemName: "xmark.circle.fill").foregroundStyle(Brand.red)
        }
    }

    private var detail: String? {
        switch step.state {
        case .ok(let s), .warning(let s), .failed(let s): return s
        case .skipped: return String(localized: "пропущено")
        default: return nil
        }
    }
}
