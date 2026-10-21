import Foundation

/// The credentials the running LAN server accepts, kept in step with the paired TVs while it serves (CAB-37). `sync` drops
/// whatever is no longer paired; `revokeDevice` works from the device ids this server has served, so a TV already gone from
/// the store can still be cut off when the control plane reports it revoked.
public final class LanCredentials: @unchecked Sendable {
    private let verifier: LanCapabilityVerifier?
    private let lock = NSLock()
    private var capabilities = Set<String>()
    /// Every TV served since the server started, so a removed one's device id still maps to its capability.
    private var deviceIds: [String: String] = [:]
    private var revoked = Set<String>()
    private var served: [PairedTv] = []

    /// `verifier` confirms capabilities of TVs paired by another household phone.
    public init(verifier: LanCapabilityVerifier? = nil) { self.verifier = verifier }

    /// Accept exactly the TVs paired now. Only capabilities are accepted, never a pairing PIN (CAB-43).
    public func sync(_ tvs: [PairedTv]) {
        var forgotten: [PairedTv] = []
        lock.withLock {
            // A TV paired since the last sync is a new pairing: an earlier revocation of the same device id is over.
            let servedPins = Set(served.map(\.pin))
            for tv in tvs where !servedPins.contains(tv.pin) { if let id = tv.deviceId { revoked.remove(id) } }
            let pins = Set(tvs.map(\.pin))
            forgotten = served.filter { !pins.contains($0.pin) }
            served = tvs
            let current = tvs.filter { $0.deviceId == nil || !revoked.contains($0.deviceId!) }
            for tv in current { if let capability = tv.capability, let id = tv.deviceId { deviceIds[capability] = id } }
            capabilities = Set(current.compactMap(\.capability))
        }
        for tv in forgotten {
            if let capability = tv.capability { verifier?.forget(capability) }
            if let id = tv.deviceId { verifier?.forgetDevice(id) }
        }
    }

    /// The control plane reports this TV revoked: it is refused from now on, whether or not it is still in the store.
    /// Only pairing it again (a new entry in `sync`) lets it back in.
    public func revokeDevice(_ deviceId: String) {
        let gone: [String] = lock.withLock {
            revoked.insert(deviceId)
            var list = deviceIds.filter { $0.value == deviceId }.map(\.key)
            list += served.filter { $0.deviceId == deviceId }.compactMap(\.capability)
            for capability in list { capabilities.remove(capability) }
            return list
        }
        for capability in gone { verifier?.forget(capability) }
        verifier?.forgetDevice(deviceId)
    }

    /// This phone's TVs' capabilities first, then the control plane for other phones' TVs.
    public func authorized(_ supplied: [String]) async -> Bool {
        if supplied.isEmpty { return false }
        if lock.withLock({ supplied.contains(where: capabilities.contains) }) { return true }
        for capability in supplied where await verifiedDeviceId(capability) != nil { return true }
        return false
    }

    /// The TV device id behind `capability`, or nil when this phone does not accept it.
    public func tvDeviceId(_ capability: String) async -> String? {
        let own = lock.withLock { capabilities.contains(capability) ? deviceIds[capability] : nil }
        if let own { return own }
        return await verifiedDeviceId(capability)
    }

    private func verifiedDeviceId(_ capability: String) async -> String? {
        guard let id = await verifier?.tvDeviceId(capability) else { return nil }
        return lock.withLock { revoked.contains(id) } ? nil : id
    }
}
