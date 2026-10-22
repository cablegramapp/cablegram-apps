import CryptoKit
import Foundation
import Testing
@testable import CablegramCore

/// Interop with the Kotlin implementation (PasswordSeal.kt on the TV and the Android phone): the vector below was made by running the same
/// operations in Java, so a password sealed by one platform opens on the other.
@Suite struct PasswordSealTests {
    // A TV key pair, request id, and a password sealed by the Kotlin code for it.
    static let tvPrivatePKCS8 = "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCAGdNejFxm1QYTtXbfMm8vi99IJqkRHQ2a5CuPmwJ6nFA=="
    static let tvPublic = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEofLO6GiBG106e2WXItsQ+SU1pqD9/QC8PMQA2R5eHEpXLRLeZlcu5h38/SLMLgBOuumlMX9ZSNNdupUZeO7QeA=="
    static let requestId = "req-7f3a9c"
    static let sealedByKotlin = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEgHjNx1yDCPYuKUjTuVSUSBKSZsLNFHlUfm2aIy61gag57Qnr3REtkP+/nfuECeQs3WRdnlAwmg9LQD7K+T3KxCmhjDLxjaVyjwQdIpNIyyWRte866ktZFDe5bk/lHdiWbsqI0TgaBWQMC3a4cjN+GCJx1uc="
    static let kotlinCheckCode = "4302 9143"
    static let password = "hunter2 ñ ✓ pässword"

    static func tvKeys() throws -> PasswordSeal.TvKeys {
        PasswordSeal.TvKeys(privateKey: try P256.KeyAgreement.PrivateKey(derRepresentation: Data(base64Encoded: tvPrivatePKCS8)!))
    }

    @Test func aPasswordSealedByKotlinOpensHere() throws {
        #expect(PasswordSeal.open(try Self.tvKeys(), requestId: Self.requestId, sealed: Self.sealedByKotlin) == Self.password)
    }

    @Test func theKeyCheckCodeEqualsKotlins() {
        #expect(PasswordSeal.checkCode(tvPublicKey: Self.tvPublic) == Self.kotlinCheckCode)
    }

    @Test func theTvPublicKeyIsDerSpki() throws {
        #expect(try Self.tvKeys().publicKey == Self.tvPublic)
        #expect(Data(base64Encoded: Self.tvPublic)!.count == 91)
    }

    @Test func sealAndOpenRoundTrip() throws {
        let keys = PasswordSeal.newTvKeys()
        let sealed = try PasswordSeal.seal(tvPublicKey: keys.publicKey, requestId: "r1", password: "pw")
        #expect(PasswordSeal.open(keys, requestId: "r1", sealed: sealed) == "pw")
        #expect(PasswordSeal.open(keys, requestId: "r2", sealed: sealed) == nil, "bound to the request it was made for")
        #expect(PasswordSeal.open(PasswordSeal.newTvKeys(), requestId: "r1", sealed: sealed) == nil, "opens only with the TV's own key")
        var altered = Data(base64Encoded: sealed)!
        altered[altered.count - 1] ^= 1
        #expect(PasswordSeal.open(keys, requestId: "r1", sealed: altered.base64EncodedString()) == nil)
        #expect(PasswordSeal.open(keys, requestId: "r1", sealed: "not base64") == nil)
    }

    @Test func twoSealsOfTheSamePasswordDiffer() throws {
        let keys = PasswordSeal.newTvKeys()
        #expect(try PasswordSeal.seal(tvPublicKey: keys.publicKey, requestId: "r", password: "p") != PasswordSeal.seal(tvPublicKey: keys.publicKey, requestId: "r", password: "p"))
    }

    /// Prints what Swift sealed for the Kotlin TV key; `java Seal.java <priv> <id> <sealed>` must open it (run by hand, see the review notes).
    @Test(.enabled(if: ProcessInfo.processInfo.environment["PRINT_SEALED"] != nil)) func printSealedForKotlin() throws {
        print("SWIFTSEALED=" + (try PasswordSeal.seal(tvPublicKey: Self.tvPublic, requestId: Self.requestId, password: Self.password)))
    }
}
