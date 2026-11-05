import SwiftUI

/// The speed test screen: a gauge whose needle follows the current speed, and the results.
struct SpeedTestView: View {
    @StateObject private var test = SpeedTest()
    @EnvironmentObject private var vpn: VPNManager
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            VStack(spacing: 28) {
                SpeedGauge(value: test.current, active: test.running)
                    .frame(width: 260, height: 260)
                    .padding(.top, 12)

                Text(phaseText).font(.headline).foregroundStyle(Brand.muted)

                HStack(spacing: 12) {
                    result("Пинг", value: test.ping.map { "\($0)" }, unit: "мс", icon: "dot.radiowaves.left.and.right")
                    result("Загрузка", value: test.download.map(Self.format), unit: "Мбит/с", icon: "arrow.down.circle")
                    result("Отдача", value: test.upload.map(Self.format), unit: "Мбит/с", icon: "arrow.up.circle")
                }

                Spacer()

                Button {
                    Task { await test.run() }
                } label: {
                    Text(test.phase == .idle ? "Начать" : "Ещё раз")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 16)
                        .background(Brand.cyan, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                        .foregroundStyle(.black)
                }
                .disabled(test.running)
                .opacity(test.running ? 0.5 : 1)

                Text(vpn.status == .connected ? "Замер идёт через VPN." : "VPN выключен — замер идёт напрямую.")
                    .font(.footnote).foregroundStyle(Brand.muted)
            }
            .padding(24)
            .background(Brand.background.ignoresSafeArea())
            .navigationTitle("Тест скорости")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Закрыть") { dismiss() } }
            }
        }
    }

    private var phaseText: LocalizedStringKey {
        switch test.phase {
        case .idle: return "Нажмите «Начать»"
        case .ping: return "Измеряю пинг…"
        case .download: return "Загрузка…"
        case .upload: return "Отдача…"
        case .done: return "Готово"
        case .failed(let reason): return "\(reason)"
        }
    }

    static func format(_ mbps: Double) -> String {
        mbps >= 100 ? String(format: "%.0f", mbps) : String(format: "%.1f", mbps)
    }

    private func result(_ title: LocalizedStringKey, value: String?, unit: LocalizedStringKey, icon: String) -> some View {
        VStack(spacing: 6) {
            Label(title, systemImage: icon).font(.caption).foregroundStyle(Brand.muted)
            Text(value ?? "—").font(.title2.weight(.semibold)).monospacedDigit()
            Text(unit).font(.caption2).foregroundStyle(Brand.muted)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
        .background(Brand.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }
}

/// A 270° speedometer. The scale is stretched at the low end (0, 5, 10, 25, 50, 100, 250, 500),
/// where most connections are, and the needle moves on a spring.
private struct SpeedGauge: View {
    var value: Double
    var active: Bool

    private static let marks: [Double] = [0, 5, 10, 25, 50, 100, 250, 500]
    private static let start = 135.0, sweep = 270.0

    /// 0…1 along the arc for a speed, linear between neighbouring marks.
    static func position(_ v: Double) -> Double {
        let m = marks
        guard v > 0 else { return 0 }
        guard v < m.last! else { return 1 }
        let i = m.lastIndex { $0 <= v }!
        let part = (v - m[i]) / (m[i + 1] - m[i])
        return (Double(i) + part) / Double(m.count - 1)
    }

    var body: some View {
        let p = Self.position(value)
        GeometryReader { geo in
            let size = min(geo.size.width, geo.size.height)
            ZStack {
                arc(to: 1).stroke(Brand.surface, style: StrokeStyle(lineWidth: 18, lineCap: .round))
                arc(to: p)
                    .stroke(
                        AngularGradient(colors: [Brand.cyan.opacity(0.6), Brand.cyan], center: .center,
                                        startAngle: .degrees(Self.start), endAngle: .degrees(Self.start + Self.sweep)),
                        style: StrokeStyle(lineWidth: 18, lineCap: .round)
                    )
                    .shadow(color: Brand.cyan.opacity(active ? 0.6 : 0), radius: 10)
                ForEach(Array(Self.marks.enumerated()), id: \.offset) { i, mark in
                    let angle = Angle.degrees(Self.start + Self.sweep * Double(i) / Double(Self.marks.count - 1))
                    Text(mark == 0 ? "0" : String(Int(mark)))
                        .font(.caption2).foregroundStyle(Brand.muted)
                        .position(
                            x: size / 2 + cos(angle.radians) * (size / 2 - 40),
                            y: size / 2 + sin(angle.radians) * (size / 2 - 40)
                        )
                }
                Capsule()
                    .fill(.white)
                    .frame(width: size / 2 - 50, height: 4)
                    .offset(x: (size / 2 - 50) / 2)
                    .rotationEffect(.degrees(Self.start + Self.sweep * p))
                Circle().fill(.white).frame(width: 14, height: 14)
                VStack(spacing: 0) {
                    Text(SpeedTestView.format(value)).font(.system(size: 40, weight: .bold)).monospacedDigit()
                    Text("Мбит/с").font(.caption).foregroundStyle(Brand.muted)
                }
                .offset(y: size * 0.28)
            }
            .frame(width: size, height: size)
            .animation(.spring(response: 0.5, dampingFraction: 0.7), value: p)
        }
    }

    private func arc(to fraction: Double) -> some Shape {
        ArcShape(start: Self.start, sweep: Self.sweep * fraction).inset(by: 9)
    }
}

private struct ArcShape: InsettableShape {
    var start: Double
    var sweep: Double
    var inset: CGFloat = 0

    var animatableData: Double {
        get { sweep }
        set { sweep = newValue }
    }

    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.addArc(
            center: CGPoint(x: rect.midX, y: rect.midY), radius: min(rect.width, rect.height) / 2 - inset,
            startAngle: .degrees(start), endAngle: .degrees(start + sweep), clockwise: false
        )
        return path
    }

    func inset(by amount: CGFloat) -> ArcShape {
        var copy = self
        copy.inset += amount
        return copy
    }
}
