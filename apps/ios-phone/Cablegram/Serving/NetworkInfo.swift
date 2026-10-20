import Foundation
import Network

/// What the serving code needs to know about the phone's network: its LAN address, and whether it is on Wi-Fi or mobile data.
final class NetworkInfo: @unchecked Sendable {
    private let monitor = NWPathMonitor()
    private let lock = NSLock()
    private var current = "other"
    private var onChange: (@Sendable (String) -> Void)?

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            let type = Self.type(of: path)
            let changed = lock.withLock { () -> Bool in defer { current = type }; return current != type }
            if changed { onChange?(type) }
        }
        monitor.start(queue: DispatchQueue(label: "app.cablegram.network"))
        lock.withLock { current = Self.type(of: monitor.currentPath) }
    }

    deinit { monitor.cancel() }

    static func type(of path: NWPath) -> String {
        if path.usesInterfaceType(.wifi) || path.usesInterfaceType(.wiredEthernet) { return "wifi" }
        if path.usesInterfaceType(.cellular) { return "cellular" }
        return "other"
    }

    /// `wifi`, `cellular` or `other`, as the relay protocol names them.
    var networkType: String { lock.withLock { current } }

    func observe(_ handler: @escaping @Sendable (String) -> Void) { lock.withLock { onChange = handler } }

    /// The phone's IPv4 address on the local network (Wi-Fi first), or nil without one. Port of `localIpv4`.
    static func localIPv4() -> String? {
        var list: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&list) == 0, let first = list else { return nil }
        defer { freeifaddrs(list) }
        var found: [(name: String, address: String)] = []
        for pointer in sequence(first: first, next: { $0.pointee.ifa_next }) {
            let interface = pointer.pointee
            guard let sockaddr = interface.ifa_addr, sockaddr.pointee.sa_family == UInt8(AF_INET),
                  interface.ifa_flags & UInt32(IFF_UP) != 0, interface.ifa_flags & UInt32(IFF_LOOPBACK) == 0 else { continue }
            var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
            guard getnameinfo(sockaddr, socklen_t(sockaddr.pointee.sa_len), &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 else { continue }
            let address = String(cString: host)
            if address.hasPrefix("169.254.") { continue }
            found.append((String(cString: interface.ifa_name), address))
        }
        return (found.first { $0.name == "en0" } ?? found.first { $0.name.hasPrefix("en") } ?? found.first)?.address
    }
}
