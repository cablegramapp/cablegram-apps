import Foundation
import Security
import Testing
@testable import CablegramCore

@Suite struct ByteRangeTests {
    @Test func absentHeaderIsFull() { #expect(ByteRange.parse(nil, length: 100) == .full); #expect(ByteRange.parse("", length: 100) == .full) }
    @Test func closedRange() { #expect(ByteRange.parse("bytes=10-19", length: 100) == .partial(start: 10, end: 19)) }
    @Test func openEnded() { #expect(ByteRange.parse("bytes=90-", length: 100) == .partial(start: 90, end: 99)) }
    @Test func endClampedToLength() { #expect(ByteRange.parse("bytes=90-500", length: 100) == .partial(start: 90, end: 99)) }
    @Test func suffix() { #expect(ByteRange.parse("bytes=-10", length: 100) == .partial(start: 90, end: 99)); #expect(ByteRange.parse("bytes=-500", length: 100) == .partial(start: 0, end: 99)) }
    @Test func startAtEndIsUnsatisfiable() { #expect(ByteRange.parse("bytes=100-", length: 100) == .unsatisfiable) }
    @Test func garbageIsUnsatisfiable() {
        for header in ["bytes=-", "bytes=a-b", "items=0-5", "bytes=20-10", "bytes=5", "bytes=-0"] { #expect(ByteRange.parse(header, length: 100) == .unsatisfiable, "\(header)") }
    }
    @Test func firstOfSeveralRangesWins() { #expect(ByteRange.parse("bytes=0-4, 10-14", length: 100) == .partial(start: 0, end: 4)) }
    @Test func contentRangeText() { #expect(ByteRange.partial(start: 5, end: 9).contentRange(of: 100) == "bytes 5-9/100"); #expect(ByteRange.partial(start: 5, end: 9).length == 5) }
}

@Suite struct StreamLinkTests {
    final class Clock: @unchecked Sendable { var t: TimeInterval = 1000 }

    @Test func linkWorksForItsPathOnly() async {
        let clock = Clock()
        let links = LanStreamLinks(now: { clock.t })
        let id = links.issue(path: "/media/a", capability: "cap", tvDeviceId: "tv1")
        #expect(await links.redeem(id, path: "/media/b", stillAccepted: { _ in true }) == nil)
        #expect(await links.redeem(id, path: "/media/a", stillAccepted: { _ in true }) == .init(tvDeviceId: "tv1"))
    }

    @Test func linkEndsWhenCapabilityIsNoLongerAccepted() async {
        let links = LanStreamLinks()
        let id = links.issue(path: "/media/a", capability: "cap", tvDeviceId: nil)
        #expect(await links.redeem(id, path: "/media/a", stillAccepted: { _ in false }) == nil)
        #expect(await links.redeem(id, path: "/media/a", stillAccepted: { _ in true }) == nil)
    }

    @Test func idleLinkExpiresButUseKeepsItAlive() async {
        let clock = Clock()
        let links = LanStreamLinks(now: { clock.t }, idle: 100, maximum: 1000)
        let id = links.issue(path: "/media/a", capability: "c", tvDeviceId: nil)
        clock.t += 90
        #expect(await links.redeem(id, path: "/media/a", stillAccepted: { _ in true }) != nil)
        clock.t += 90
        #expect(await links.redeem(id, path: "/media/a", stillAccepted: { _ in true }) != nil)
        clock.t += 101
        #expect(await links.redeem(id, path: "/media/a", stillAccepted: { _ in true }) == nil)
    }

    @Test func linkNeverOutlivesMaximum() async {
        let clock = Clock()
        let links = LanStreamLinks(now: { clock.t }, idle: 100, maximum: 150)
        let id = links.issue(path: "/media/a", capability: "c", tvDeviceId: nil)
        clock.t += 90; #expect(await links.redeem(id, path: "/media/a", stillAccepted: { _ in true }) != nil)
        clock.t += 90; #expect(await links.redeem(id, path: "/media/a", stillAccepted: { _ in true }) == nil)
    }

    @Test func idsAreDistinctAndUrlSafe() {
        let links = LanStreamLinks()
        let ids = (0..<20).map { _ in links.issue(path: "/media/a", capability: "c", tvDeviceId: nil) }
        #expect(Set(ids).count == 20)
        #expect(ids.allSatisfy { $0.range(of: "^[A-Za-z0-9_-]{43}$", options: .regularExpression) != nil })
    }
}

@Suite struct CredentialsTests {
    func tv(_ pin: String, cap: String?, device: String?) -> PairedTv { PairedTv(pin: pin, name: "TV", capability: cap, deviceId: device) }

    @Test func acceptsOnlyPairedCapabilities() async {
        let creds = LanCredentials()
        creds.sync([tv("1", cap: "capA", device: "A")])
        #expect(await creds.authorized(["capA"]))
        #expect(await creds.authorized(["1"]) == false, "a pairing PIN is never a credential")
        #expect(await creds.authorized([]) == false)
        #expect(await creds.tvDeviceId("capA") == "A")
    }

    @Test func removedTvLosesAccess() async {
        let creds = LanCredentials()
        creds.sync([tv("1", cap: "capA", device: "A"), tv("2", cap: "capB", device: "B")])
        creds.sync([tv("2", cap: "capB", device: "B")])
        #expect(await creds.authorized(["capA"]) == false)
        #expect(await creds.authorized(["capB"]))
    }

    @Test func revokedDeviceIsRefusedEvenAfterRemoval() async {
        let creds = LanCredentials()
        creds.sync([tv("1", cap: "capA", device: "A")])
        creds.revokeDevice("A")
        #expect(await creds.authorized(["capA"]) == false)
        creds.sync([tv("1", cap: "capA", device: "A")])
        #expect(await creds.authorized(["capA"]) == false, "same pairing stays revoked")
        creds.sync([tv("9", cap: "capA", device: "A")])
        #expect(await creds.authorized(["capA"]), "a new pairing lets the device back in")
    }

    @Test func otherPhonesTvIsVerifiedWithControlPlane() async {
        let calls = Counter()
        let verifier = LanCapabilityVerifier(verify: { cap in calls.bump(); return cap == "foreign" ? "F" : nil })
        let creds = LanCredentials(verifier: verifier)
        #expect(await creds.authorized(["foreign"]))
        #expect(await creds.authorized(["foreign"]))
        #expect(calls.value == 1, "accepted answers are cached")
        creds.revokeDevice("F")
        #expect(await creds.authorized(["foreign"]) == false)
    }

    @Test func rejectedAnswerIsCachedBriefly() async {
        let calls = Counter()
        let verifier = LanCapabilityVerifier(verify: { _ in calls.bump(); return nil })
        _ = await verifier.tvDeviceId("x"); _ = await verifier.tvDeviceId("x")
        #expect(calls.value == 1)
    }
}

final class Counter: @unchecked Sendable {
    private let lock = NSLock(); private var n = 0
    func bump() { lock.withLock { n += 1 } }
    var value: Int { lock.withLock { n } }
}

@Suite struct PrivatePassTests {
    @Test func passMustNameTheTitleAndTheTv() async {
        let verifier = PrivatePassVerifier(verify: { pass in pass == "good" ? VerifiedPass(originIdentities: ["m1"], tvDeviceId: "tv1", expiresAt: Date().addingTimeInterval(60)) : nil })
        #expect(await verifier.allows(pass: "good", itemId: "m1", tvDeviceId: "tv1"))
        #expect(await verifier.allows(pass: "good", itemId: "m2", tvDeviceId: "tv1") == false)
        #expect(await verifier.allows(pass: "good", itemId: "m1", tvDeviceId: "tv2") == false)
        #expect(await verifier.allows(pass: "good", itemId: "m1", tvDeviceId: nil))
        #expect(await verifier.allows(pass: "bad", itemId: "m1", tvDeviceId: "tv1") == false)
        #expect(await verifier.allows(pass: nil, itemId: "m1", tvDeviceId: "tv1") == false)
    }

    @Test func expiredPassIsRefused() async {
        let verifier = PrivatePassVerifier(verify: { _ in VerifiedPass(originIdentities: ["m1"], tvDeviceId: nil, expiresAt: Date().addingTimeInterval(-1)) })
        #expect(await verifier.allows(pass: "p", itemId: "m1", tvDeviceId: nil) == false)
    }
}

@Suite struct LifetimeTests {
    final class Clock: @unchecked Sendable { var t: TimeInterval = 0 }

    @Test func stopsOnlyAfterBackgroundIdle() {
        let clock = Clock()
        let life = LanLibraryLifetime(idleTimeout: 100, clock: { clock.t })
        life.appForeground(true)
        clock.t = 500; #expect(life.shouldStop() == false)
        life.appForeground(false)
        clock.t = 550; #expect(life.shouldStop() == false)
        clock.t = 600; #expect(life.shouldStop())
    }

    @Test func activeStreamOrTvRequestKeepsItRunning() {
        let clock = Clock()
        let life = LanLibraryLifetime(idleTimeout: 100, clock: { clock.t })
        life.streamStarted()
        clock.t = 1000; #expect(life.shouldStop() == false)
        life.streamFinished()
        clock.t = 1050; #expect(life.shouldStop() == false)
        clock.t = 1100; #expect(life.shouldStop())
        life.tvRequest(); #expect(life.shouldStop() == false)
        #expect(life.shouldStop(castActive: true) == false)
    }
}

@Suite struct CertificateTests {
    @Test func buildsAParsableSelfSignedCertificate() throws {
        let attributes: [String: Any] = [kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom, kSecAttrKeySizeInBits as String: 256]
        let key = try #require(SecKeyCreateRandomKey(attributes as CFDictionary, nil))
        let der = try SelfSignedCertificate.make(commonName: "Cablegram phone", key: key)
        let certificate = try #require(SecCertificateCreateWithData(nil, der as CFData))
        #expect(SecCertificateCopySubjectSummary(certificate) as String? == "Cablegram phone")
        #expect(SelfSignedCertificate.sha256Hex(der).count == 64)
        let trusted = SecKeyCopyPublicKey(key)
        let inCert = SecCertificateCopyKey(certificate)
        #expect((SecKeyCopyExternalRepresentation(try #require(trusted), nil) as Data?) == (SecKeyCopyExternalRepresentation(try #require(inCert), nil) as Data?))
    }
}

@Suite struct RelayConsentTests {
    @Test func wifiIsAlwaysServed() { #expect(RelayConsent.admit(network: "wifi", policy: .never, allowedUntil: 0, ask: {}) == nil) }
    @Test func neverRefusesOnMobileData() { #expect(RelayConsent.admit(network: "cellular", policy: .never, allowedUntil: 0, ask: {}) == .mobileDataNotAllowed) }
    @Test func alwaysServesOnMobileData() { #expect(RelayConsent.admit(network: "cellular", policy: .always, allowedUntil: 0, ask: {}) == nil) }
    @Test func askRefusesAndAsksUntilAllowed() {
        let asked = Counter()
        #expect(RelayConsent.admit(network: "cellular", policy: .ask, allowedUntil: 0, now: 100, ask: { asked.bump() }) == .mobileDataPending)
        #expect(asked.value == 1)
        #expect(RelayConsent.admit(network: "cellular", policy: .ask, allowedUntil: 200, now: 100, ask: { asked.bump() }) == nil)
        #expect(asked.value == 1)
        #expect(RelayConsent.admit(network: "cellular", policy: .ask, allowedUntil: 200, now: 300, ask: { asked.bump() }) == .mobileDataPending)
    }
    @Test func allowanceResetsOffMobileData() {
        #expect(RelayConsent.shouldResetAllowance(network: "wifi", allowedUntil: 5))
        #expect(!RelayConsent.shouldResetAllowance(network: "cellular", allowedUntil: 5))
        #expect(!RelayConsent.shouldResetAllowance(network: "wifi", allowedUntil: 0))
    }
}
