#if canImport(Darwin)
import Darwin

/// Byte counters of the utun interface that carries the tunnel's address. The app and the
/// tunnel both see it, so neither needs anything from the other to measure traffic.
enum TunnelCounters {
    /// The address PacketTunnelProvider gives the utun interface.
    static let tunnelAddress = "172.19.0.1"

    /// Received (download) and sent (upload) bytes; 32-bit, so they wrap around at 4 GB.
    static func read() -> (rx: UInt32, tx: UInt32)? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        let all = Array(sequence(first: first) { $0.pointee.ifa_next })
        let tunnel = inet_addr(tunnelAddress)
        guard let name = all.first(where: { ptr in
            guard let addr = ptr.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) else { return false }
            return addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr == tunnel }
        }).map({ String(cString: $0.pointee.ifa_name) }) else { return nil }
        for ptr in all {
            guard let addr = ptr.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_LINK),
                  String(cString: ptr.pointee.ifa_name) == name,
                  let data = ptr.pointee.ifa_data?.assumingMemoryBound(to: if_data.self) else { continue }
            // ibytes come out of the tunnel to apps (download), obytes go from apps into it (upload).
            return (data.pointee.ifi_ibytes, data.pointee.ifi_obytes)
        }
        return nil
    }
}
#endif
