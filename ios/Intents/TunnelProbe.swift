import Darwin

/// Whether the VPN is up, seen from any process: the tunnel's utun interface carries its address.
/// The widget uses this because without App Groups it cannot read the app's VPN state.
enum TunnelProbe {
    /// The address PacketTunnelProvider gives the utun interface.
    static let tunnelAddress = "172.19.0.1"

    static func isConnected() -> Bool {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return false }
        defer { freeifaddrs(list) }
        let tunnel = inet_addr(tunnelAddress)
        return sequence(first: first) { $0.pointee.ifa_next }.contains { ptr in
            guard let addr = ptr.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET),
                  ptr.pointee.ifa_flags & UInt32(IFF_UP) != 0 else { return false }
            return addr.withMemoryRebound(to: sockaddr_in.self, capacity: 1) { $0.pointee.sin_addr.s_addr == tunnel }
        }
    }
}
