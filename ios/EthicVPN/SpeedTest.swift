import Foundation

/// A speed test against Cloudflare's speed servers: ping, then download and upload for a few
/// seconds each over several connections. With the VPN on it measures the VPN.
@MainActor
final class SpeedTest: ObservableObject {
    enum Phase: Equatable { case idle, ping, download, upload, done, failed(String) }

    @Published private(set) var phase: Phase = .idle
    /// Megabits per second right now, for the gauge.
    @Published private(set) var current: Double = 0
    @Published private(set) var ping: Int?
    @Published private(set) var download: Double?
    @Published private(set) var upload: Double?

    nonisolated static let base = "https://speed.cloudflare.com"
    private static let seconds: TimeInterval = 8
    private static let streams = 4

    var running: Bool { [.ping, .download, .upload].contains(phase) }

    func run() async {
        ping = nil; download = nil; upload = nil; current = 0
        phase = .ping
        guard let ms = await Self.measurePing() else {
            phase = .failed(String(localized: "Сервер теста скорости не отвечает"))
            return
        }
        ping = ms
        phase = .download
        download = await measure(upload: false)
        current = 0
        phase = .upload
        upload = await measure(upload: true)
        current = 0
        phase = .done
    }

    /// Best of five small requests over one connection, so only the first pays for the handshake.
    private static func measurePing() async -> Int? {
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        guard let url = URL(string: base + "/__down?bytes=0") else { return nil }
        var best: Int?
        for _ in 0..<5 {
            let start = Date()
            guard (try? await session.data(from: url)) != nil else { continue }
            let ms = Int(Date().timeIntervalSince(start) * 1000)
            best = min(best ?? ms, ms)
        }
        return best
    }

    /// Runs the streams for `seconds`, showing the rate of each half second on the gauge, and
    /// returns the average in Mbit/s.
    private func measure(upload: Bool) async -> Double {
        let counter = ByteCounter()
        let session = URLSession(configuration: .ephemeral, delegate: counter, delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let payload = Data(count: 25_000_000)
        let start: @Sendable () -> Void = {
            if upload {
                var request = URLRequest(url: URL(string: SpeedTest.base + "/__up")!)
                request.httpMethod = "POST"
                session.uploadTask(with: request, from: payload).resume()
            } else {
                session.dataTask(with: URL(string: SpeedTest.base + "/__down?bytes=100000000")!).resume()
            }
        }
        // A finished stream is replaced, so the line stays busy until the time is up.
        counter.onFinish = start
        for _ in 0..<Self.streams { start() }

        let begin = Date()
        var lastBytes: Int64 = 0
        var lastTime = begin
        while Date().timeIntervalSince(begin) < Self.seconds {
            try? await Task.sleep(nanoseconds: 500_000_000)
            let now = Date(), bytes = counter.total
            current = Double(bytes - lastBytes) * 8 / now.timeIntervalSince(lastTime) / 1_000_000
            lastBytes = bytes
            lastTime = now
        }
        counter.onFinish = nil
        return Double(counter.total) * 8 / Date().timeIntervalSince(begin) / 1_000_000
    }
}

/// Counts bytes received and sent by a URLSession's tasks.
private final class ByteCounter: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var bytes: Int64 = 0
    private var finishHandler: (@Sendable () -> Void)?

    var total: Int64 { lock.withLock { bytes } }

    var onFinish: (@Sendable () -> Void)? {
        get { lock.withLock { finishHandler } }
        set { lock.withLock { finishHandler = newValue } }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.withLock { bytes += Int64(data.count) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64,
                    totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        lock.withLock { bytes += bytesSent }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        // Cancelled when the session is torn down; anything else ended on its own.
        if (error as? URLError)?.code == .cancelled { return }
        onFinish?()
    }
}
