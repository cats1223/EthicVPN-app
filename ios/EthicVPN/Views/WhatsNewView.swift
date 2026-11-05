import SwiftUI

/// "What's new" after an update: the version's section of CHANGELOG.md.
struct WhatsNewView: View {
    let notes: String
    var onClose: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    ForEach(Array(paragraphs.enumerated()), id: \.offset) { _, line in
                        if line.hasPrefix("- ") {
                            HStack(alignment: .firstTextBaseline, spacing: 10) {
                                Circle().fill(Brand.cyan).frame(width: 6, height: 6)
                                Text(markdown(String(line.dropFirst(2))))
                            }
                        } else {
                            Text(markdown(line)).font(line.hasPrefix("**") ? .headline : .body)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            }
            .background(Brand.background.ignoresSafeArea())
            .navigationTitle("Что нового в \(AppSettings.shortVersion)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Отлично", action: onClose) }
            }
        }
    }

    /// Bullets and paragraphs; a bullet's continuation lines are joined back to it.
    private var paragraphs: [String] {
        var out: [String] = []
        for raw in notes.components(separatedBy: "\n") {
            let line = raw.trimmingCharacters(in: .whitespaces)
            if line.isEmpty { continue }
            if raw.hasPrefix("  "), let last = out.popLast() { out.append(last + " " + line) } else { out.append(line) }
        }
        return out
    }

    private func markdown(_ s: String) -> AttributedString {
        (try? AttributedString(markdown: s)) ?? AttributedString(s)
    }
}
