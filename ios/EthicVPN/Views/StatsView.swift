import SwiftUI

/// Traffic through the VPN: totals for today, a week and a month, and a bar per day for the
/// last week. The tunnel counts it; while it is off the last numbers it gave are shown.
struct StatsView: View {
    @EnvironmentObject private var vpn: VPNManager
    @State private var history = TrafficHistory()

    var body: some View {
        List {
            Section {
                HStack(spacing: 10) {
                    tile("Сегодня", history.total(1))
                    tile("7 дней", history.total(7))
                    tile("30 дней", history.total(30))
                }
                .listRowBackground(Color.clear)
                .listRowInsets(EdgeInsets())
            }

            Section {
                HStack {
                    Image(systemName: "hand.raised.fill").font(.title2).foregroundStyle(Brand.green)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(String(history.blocked(1)))").font(.title.bold()).monospacedDigit()
                        Text("реклам и трекеров заблокировано сегодня").font(.caption).foregroundStyle(Brand.muted)
                    }
                    Spacer()
                    VStack(alignment: .trailing, spacing: 2) {
                        Text("7 дней: \(String(history.blocked(7)))").font(.caption)
                        Text("30 дней: \(String(history.blocked(30)))").font(.caption)
                    }
                    .foregroundStyle(Brand.muted)
                }
                .padding(.vertical, 6)
            } header: {
                Text("Блокировка рекламы")
            } footer: {
                Text("Считается, пока включена «Блокировать рекламу».")
            }
            .listRowBackground(Brand.surface)

            Section {
                chart.padding(.vertical, 8)
            } header: {
                Text("Последние 7 дней")
            } footer: {
                Text("Считается весь трафик через VPN на этом телефоне: ↓ загружено, ↑ отправлено.")
            }
            .listRowBackground(Brand.surface)
        }
        .scrollContentBackground(.hidden)
        .background(Brand.background.ignoresSafeArea())
        .navigationTitle("Статистика")
        .task {
            // Refresh while the screen is open, so today's numbers grow.
            while !Task.isCancelled {
                history = await vpn.fetchStats()
                try? await Task.sleep(nanoseconds: 10_000_000_000)
            }
        }
    }

    private func tile(_ title: LocalizedStringKey, _ total: (down: Int64, up: Int64)) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.caption).foregroundStyle(Brand.muted)
            Text(formatBytes(total.down + total.up)).font(.headline).lineLimit(1).minimumScaleFactor(0.7)
            Text("↓ \(formatBytes(total.down))").font(.caption2).foregroundStyle(Brand.muted).lineLimit(1)
            Text("↑ \(formatBytes(total.up))").font(.caption2).foregroundStyle(Brand.muted).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(Brand.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
    }

    private var chart: some View {
        let days = history.daily(7)
        let peak = max(days.map(\.total).max() ?? 0, 1)
        return HStack(alignment: .bottom, spacing: 8) {
            ForEach(days, id: \.day) { day in
                VStack(spacing: 6) {
                    Text(day.total > 0 ? formatBytes(day.total) : "")
                        .font(.system(size: 9)).foregroundStyle(Brand.muted).lineLimit(1).minimumScaleFactor(0.5)
                    RoundedRectangle(cornerRadius: 5, style: .continuous)
                        .fill(Calendar.current.isDateInToday(day.day) ? Brand.cyan : Brand.cyan.opacity(0.45))
                        .frame(height: max(4, 120 * CGFloat(day.total) / CGFloat(peak)))
                    Text(day.day.formatted(.dateTime.weekday(.abbreviated)))
                        .font(.caption2).foregroundStyle(Brand.muted)
                }
                .frame(maxWidth: .infinity)
            }
        }
        .frame(height: 170, alignment: .bottom)
    }
}
