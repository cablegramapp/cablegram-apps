import CommonCrypto
import CryptoKit
import Foundation

// Port of `PasswordSeal.kt`; byte-compatible with the Android and TV copies (contracts/telegram-link.md).

/// Seals the Telegram two-step password on the phone so only the TV that asked can read it. The control plane relays the TV's public
/// key and the sealed bytes and cannot open them.
///
/// ECDH on P-256; the AES-256-GCM key is SHA-256(shared secret || "cablegram-tg-password-v1:" || request id), and the request id is
/// also the associated data, so a sealed password only opens for the request it was made for.
/// `sealed = base64(phone SPKI || IV || ciphertext || tag)`.
public enum PasswordSeal {
    private static let spkiLength = 91 // DER SubjectPublicKeyInfo of a P-256 key
    private static let ivLength = 12
    private static let tagLength = 16

    /// The TV's one-time key pair for one request; `publicKey` is what the control plane gets.
    public struct TvKeys {
        let privateKey: P256.KeyAgreement.PrivateKey
        public var publicKey: String { privateKey.publicKey.derRepresentation.base64EncodedString() }
    }

    public static func newTvKeys() -> TvKeys { TvKeys(privateKey: P256.KeyAgreement.PrivateKey()) }

    public enum SealError: Error { case badKey }

    /// Phone: the sealed password for the TV whose `tvPublicKey` (base64 SPKI) was posted with `requestId`.
    public static func seal(tvPublicKey: String, requestId: String, password: String) throws -> String {
        guard let der = Data(base64Encoded: tvPublicKey), let tv = try? P256.KeyAgreement.PublicKey(derRepresentation: der) else { throw SealError.badKey }
        let ephemeral = P256.KeyAgreement.PrivateKey()
        let secret = try ephemeral.sharedSecretFromKeyAgreement(with: tv)
        let box = try AES.GCM.seal(Data(password.utf8), using: key(secret, requestId), nonce: AES.GCM.Nonce(), authenticating: Data(requestId.utf8))
        return (ephemeral.publicKey.derRepresentation + Data(box.nonce) + box.ciphertext + box.tag).base64EncodedString()
    }

    /// TV: the password, or nil when `sealed` is not for `requestId` and these keys (or was altered).
    public static func open(_ keys: TvKeys, requestId: String, sealed: String) -> String? {
        guard let raw = Data(base64Encoded: sealed), raw.count > spkiLength + ivLength + tagLength else { return nil }
        let bytes = [UInt8](raw)
        guard let phone = try? P256.KeyAgreement.PublicKey(derRepresentation: Data(bytes[0..<spkiLength])),
              let secret = try? keys.privateKey.sharedSecretFromKeyAgreement(with: phone),
              let nonce = try? AES.GCM.Nonce(data: Data(bytes[spkiLength..<(spkiLength + ivLength)])) else { return nil }
        let body = Data(bytes[(spkiLength + ivLength)...])
        guard let box = try? AES.GCM.SealedBox(nonce: nonce, ciphertext: body.dropLast(tagLength), tag: body.suffix(tagLength)),
              let plain = try? AES.GCM.open(box, using: key(secret, requestId), authenticating: Data(requestId.utf8)) else { return nil }
        return String(data: plain, encoding: .utf8)
    }

    /// The code both screens show for one TV key, so the owner can see that the phone seals to the key the TV made, not to one a
    /// compromised server put in its place. 8 digits behind a slow hash. Shown as "1234 5678". About a tenth of a second: compute it
    /// off the main thread.
    public static func checkCode(tvPublicKey: String) -> String? {
        guard let spki = Data(base64Encoded: tvPublicKey) else { return nil }
        let password = Array(spki.base64EncodedString().utf8)
        let salt = Array("cablegram-tg-key-check-v1".utf8)
        var derived = [UInt8](repeating: 0, count: 8)
        let status = CCKeyDerivationPBKDF(CCPBKDFAlgorithm(kCCPBKDF2), password.map { Int8(bitPattern: $0) }, password.count, salt, salt.count,
                                          CCPseudoRandomAlgorithm(kCCPRFHmacAlgSHA256), UInt32(checkIterations), &derived, derived.count)
        guard status == kCCSuccess else { return nil }
        let number = derived.reduce(UInt64(0)) { ($0 << 8) | UInt64($1) } % 100_000_000
        let text = String(format: "%08llu", number)
        return "\(text.prefix(4)) \(text.suffix(4))"
    }

    private static let checkIterations = 100_000

    private static func key(_ secret: SharedSecret, _ requestId: String) -> SymmetricKey {
        var digest = SHA256()
        secret.withUnsafeBytes { digest.update(bufferPointer: $0) }
        digest.update(data: Data("cablegram-tg-password-v1:\(requestId)".utf8))
        return SymmetricKey(data: Data(digest.finalize()))
    }
}
