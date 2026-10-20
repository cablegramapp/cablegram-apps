import CablegramCore
import Foundation
import Network
import Security

/// This phone's TLS identity for the LAN library server (CAB-48). An EC P-256 key made once in the Keychain signs a self-signed
/// certificate; TVs pin the certificate's SHA-256 (`fingerprint`), which the phone publishes with its address. The key never
/// leaves the Keychain. Deleting the app makes a new key, and so a new fingerprint, which the next announce publishes.
struct LanTlsIdentity: @unchecked Sendable {
    let identity: SecIdentity
    /// Lowercase hex SHA-256 of the DER certificate: what a TV pins.
    let fingerprint: String

    private static let label = "Cablegram LAN TLS"
    private static let keyTag = Data("app.cablegram.phone.lan-tls".utf8)

    enum Failure: Error { case keychain(OSStatus) }

    /// The identity in the Keychain, made on first use.
    static func load(service: String = label, tag: Data = keyTag) throws -> LanTlsIdentity {
        if let existing = try find(label: service) { return existing }
        // A half-made identity (key without certificate) is replaced.
        SecItemDelete([kSecClass: kSecClassKey, kSecAttrApplicationTag: tag] as CFDictionary)
        SecItemDelete([kSecClass: kSecClassCertificate, kSecAttrLabel: service] as CFDictionary)

        var error: Unmanaged<CFError>?
        let attributes: [CFString: Any] = [
            kSecAttrKeyType: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits: 256,
            kSecPrivateKeyAttrs: [
                kSecAttrIsPermanent: true,
                kSecAttrApplicationTag: tag,
                kSecAttrLabel: service,
                kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
            ] as [CFString: Any],
        ]
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else { throw error!.takeRetainedValue() }
        let der = try SelfSignedCertificate.make(commonName: "Cablegram phone", key: key)
        guard let certificate = SecCertificateCreateWithData(nil, der as CFData) else { throw Failure.keychain(errSecParam) }
        let status = SecItemAdd([kSecClass: kSecClassCertificate, kSecValueRef: certificate, kSecAttrLabel: service,
                                 kSecAttrAccessible: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly] as CFDictionary, nil)
        guard status == errSecSuccess else { throw Failure.keychain(status) }
        guard let made = try find(label: service) else { throw Failure.keychain(errSecItemNotFound) }
        return made
    }

    private static func find(label: String) throws -> LanTlsIdentity? {
        var item: CFTypeRef?
        let status = SecItemCopyMatching([kSecClass: kSecClassIdentity, kSecAttrLabel: label, kSecReturnRef: true] as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let item else { throw Failure.keychain(status) }
        let identity = item as! SecIdentity
        var certificate: SecCertificate?
        SecIdentityCopyCertificate(identity, &certificate)
        guard let certificate else { return nil }
        return LanTlsIdentity(identity: identity, fingerprint: SelfSignedCertificate.sha256Hex(SecCertificateCopyData(certificate) as Data))
    }

    /// TLS options for the listener: always this one certificate, TLS 1.2 or newer.
    func serverOptions() -> NWProtocolTLS.Options {
        let options = NWProtocolTLS.Options()
        if let local = sec_identity_create(identity) { sec_protocol_options_set_local_identity(options.securityProtocolOptions, local) }
        sec_protocol_options_set_min_tls_protocol_version(options.securityProtocolOptions, .TLSv12)
        return options
    }
}
