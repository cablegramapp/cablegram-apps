import CryptoKit
import Foundation
import Security

/// Builds the phone's self-signed X.509 certificate (CAB-48). TVs do not trust it through a CA: the phone publishes the
/// SHA-256 of the DER bytes with its LAN address, and a TV accepts only a server whose certificate has exactly that hash.
/// Security.framework cannot create certificates, so the DER is written here and signed with the Keychain key.
public enum SelfSignedCertificate {
    public enum BuildError: Error { case publicKey, signature }

    /// DER of a certificate for `key`, subject `CN=<commonName>`, valid from a day ago for `years` years.
    public static func make(commonName: String, key: SecKey, years: Int = 20, now: Date = Date(), serial: UInt64? = nil) throws -> Data {
        guard let publicKey = SecKeyCopyPublicKey(key), let point = SecKeyCopyExternalRepresentation(publicKey, nil) as Data? else { throw BuildError.publicKey }
        // ecdsa-with-SHA256: 1.2.840.10045.4.3.2
        let algorithm = DER.sequence([DER.oid([1, 2, 840, 10045, 4, 3, 2])])
        let name = DER.sequence([DER.set([DER.sequence([DER.oid([2, 5, 4, 3]), DER.utf8(commonName)])])])
        let validity = DER.sequence([DER.time(now.addingTimeInterval(-86_400)), DER.time(now.addingTimeInterval(Double(years) * 365 * 86_400))])
        // id-ecPublicKey with prime256v1
        let spki = DER.sequence([
            DER.sequence([DER.oid([1, 2, 840, 10045, 2, 1]), DER.oid([1, 2, 840, 10045, 3, 1, 7])]),
            DER.bitString(point),
        ])
        let serialNumber = serial ?? UInt64(now.timeIntervalSince1970 * 1000)
        let tbs = DER.sequence([
            DER.explicit(0, DER.integer(2)),
            DER.integer(serialNumber),
            algorithm,
            name,
            validity,
            name,
            spki,
        ])
        var error: Unmanaged<CFError>?
        guard let signature = SecKeyCreateSignature(key, .ecdsaSignatureMessageX962SHA256, tbs as CFData, &error) as Data? else { throw BuildError.signature }
        return DER.sequence([tbs, algorithm, DER.bitString(signature)])
    }

    public static func sha256Hex(_ der: Data) -> String { SHA256.hash(data: der).map { String(format: "%02x", $0) }.joined() }
}

/// Just enough DER for the certificate above.
enum DER {
    static func length(_ n: Int) -> Data {
        if n < 128 { return Data([UInt8(n)]) }
        var bytes: [UInt8] = []
        var rest = n
        while rest > 0 { bytes.insert(UInt8(rest & 0xff), at: 0); rest >>= 8 }
        return Data([0x80 | UInt8(bytes.count)] + bytes)
    }

    static func tlv(_ tag: UInt8, _ content: Data) -> Data { Data([tag]) + length(content.count) + content }
    static func sequence(_ items: [Data]) -> Data { tlv(0x30, items.reduce(Data(), +)) }
    static func set(_ items: [Data]) -> Data { tlv(0x31, items.reduce(Data(), +)) }
    static func explicit(_ tag: UInt8, _ content: Data) -> Data { tlv(0xA0 | tag, content) }
    static func utf8(_ text: String) -> Data { tlv(0x0C, Data(text.utf8)) }

    static func integer(_ value: UInt64) -> Data {
        var bytes: [UInt8] = []
        var rest = value
        repeat { bytes.insert(UInt8(rest & 0xff), at: 0); rest >>= 8 } while rest > 0
        if bytes[0] & 0x80 != 0 { bytes.insert(0, at: 0) }
        return tlv(0x02, Data(bytes))
    }

    static func oid(_ arcs: [UInt]) -> Data {
        var bytes: [UInt8] = [UInt8(arcs[0] * 40 + arcs[1])]
        for arc in arcs.dropFirst(2) {
            var chunk: [UInt8] = [UInt8(arc & 0x7f)]
            var rest = arc >> 7
            while rest > 0 { chunk.insert(UInt8(rest & 0x7f) | 0x80, at: 0); rest >>= 7 }
            bytes += chunk
        }
        return tlv(0x06, Data(bytes))
    }

    static func bitString(_ content: Data) -> Data { tlv(0x03, Data([0]) + content) }

    /// UTCTime until 2049, GeneralizedTime after (RFC 5280).
    static func time(_ date: Date) -> Data {
        let calendar = Calendar(identifier: .gregorian)
        let c = calendar.dateComponents(in: TimeZone(identifier: "UTC")!, from: date)
        let year = c.year!
        func two(_ v: Int?) -> String { String(format: "%02d", v ?? 0) }
        let rest = two(c.month) + two(c.day) + two(c.hour) + two(c.minute) + two(c.second) + "Z"
        if year < 2050 { return tlv(0x17, Data((two(year % 100) + rest).utf8)) }
        return tlv(0x18, Data((String(format: "%04d", year) + rest).utf8))
    }
}
