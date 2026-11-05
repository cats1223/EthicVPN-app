import Darwin
import Foundation

/// Live speed and session traffic of the VPN, read from the tunnel interface's byte counters.
/// The app sees the utun interface like any other (`TunnelCounters`), so this needs nothing from the extension.
@MainActor
final class TrafficMeter: ObservableObject {
    /// Bytes per second.
    @Published private(set) var down: Double = 0
    @Published private(set) var up: Double = 0
    /// Bytes since the meter started.
    @Published private(set) var sessionDown: UInt64 = 0
    @Published private(set) var sessionUp: UInt64 = 0

    private var timer: Timer?
    private var last: (rx: UInt32, tx: UInt32)?

    func start() {
        stop()
        sessionDown = 0
        sessionUp = 0
        last = Self.counters()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.tick() }
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
        down = 0
        up = 0
    }

    private func tick() {
        guard let now = Self.counters() else { return }
        if let last {
            // The counters are 32-bit and wrap around at 4 GB; unsigned subtraction handles that.
            let rx = UInt64(now.rx &- last.rx), tx = UInt64(now.tx &- last.tx)
            down = Double(rx)
            up = Double(tx)
            sessionDown += rx
            sessionUp += tx
        }
        last = now
    }

    private static func counters() -> (rx: UInt32, tx: UInt32)? { TunnelCounters.read() }
}

/// "1.2 МБ/с" for a byte rate.
func formatRate(_ bytesPerSecond: Double) -> String {
    String(localized: "\(formatBytes(Int64(bytesPerSecond)))/с")
}
