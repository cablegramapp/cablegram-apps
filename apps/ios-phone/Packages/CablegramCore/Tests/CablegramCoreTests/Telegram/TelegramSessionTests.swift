import Foundation
import Testing
@testable import CablegramCore

// Port of `TelegramSessionTest.kt`.

@MainActor @Suite struct TelegramSessionTests {
    let params = TgParameters(databaseDirectory: "db", filesDirectory: "files", databaseKey: Data(count: 32), apiId: 1, apiHash: "hash", deviceModel: "Model", systemVersion: "iOS", applicationVersion: "test")
    let link = "tg://login?token=AQAB_c2VjcmV0LXRva2Vu"
    let ownApi = 100

    func session(_ api: FakeTelegramApi, _ role: TelegramRole) -> TelegramSession {
        let params = self.params
        let s = TelegramSession(api: api, role: role, identifyWait: .milliseconds(10)) { params }
        s.start()
        return s
    }

    @Test func unavailableConfigurationCanBeRetriedWithoutReplacingTheTelegramSession() async {
        let api = FakeTelegramApi()
        let requests = TgLocked(0)
        let params = self.params
        let s = TelegramSession(api: api, role: .phone, identifyWait: .milliseconds(10)) {
            if requests.update({ $0 += 1; return $0 }) == 1 { throw TelegramClientUnavailable() }
            return params
        }
        s.start()
        api.emitAuth(.waitParameters)
        #expect(await eventually { if case .failed = s.state { true } else { false } })
        #expect(api.calls.isEmpty)
        #expect(s.clientApiId == 0)
        s.retryStartup(); s.retryStartup()
        #expect(await eventually { api.calls == ["start"] })
        #expect(requests.value == 2)
        #expect(s.clientApiId == params.apiId)
        s.retryStartup()
        await settle()
        #expect(requests.value == 2)
    }

    @Test func phoneSignsInWithNumberCodeAndPassword() async {
        let api = FakeTelegramApi()
        let s = session(api, .phone)
        api.emitAuth(.waitParameters)
        #expect(await eventually { api.calls == ["start"] })
        api.emitAuth(.waitPhoneNumber)
        #expect(await eventually { s.state == .needsPhoneNumber })

        s.submitPhoneNumber(" +31 6 1234 5678 ")
        #expect(await eventually { api.calls.last == "setPhoneNumber:+31612345678" })
        #expect(await eventually { !s.busy })
        api.emitAuth(.waitCode)
        #expect(await eventually { s.state == .needsCode })
        s.submitCode("12 345")
        #expect(await eventually { api.calls.last == "checkCode:12345" })
        #expect(await eventually { !s.busy })
        api.emitAuth(.waitPassword(hint: "my hint"))
        #expect(await eventually { s.state == .needsPassword(hint: "my hint") })
        s.submitPassword("secret")
        #expect(await eventually { api.calls.last == "checkPassword" })
        api.emitAuth(.ready)
        #expect(await eventually { s.state == .ready(TgUser(id: 42, displayName: "Test User")) })
    }

    @Test func aTVNeverAsksForAPhoneNumberItWaitsForApproval() async {
        let api = FakeTelegramApi()
        let s = session(api, .tv)
        api.emitAuth(.waitPhoneNumber)
        #expect(await eventually { api.calls == ["requestQrLogin"] })
        api.emitAuth(.waitOtherDevice(link: link))
        #expect(await eventually { s.state == .waitingForApproval(link: link) })
    }

    @Test func onlyOneInputIsCheckedAtATime() async {
        let api = FakeTelegramApi()
        let s = session(api, .phone)
        api.emitAuth(.waitPhoneNumber)
        #expect(await eventually { s.state == .needsPhoneNumber })
        s.submitPhoneNumber("+31600000001")
        s.submitPhoneNumber("+31600000002") // second tap before the first finished
        #expect(await eventually { !s.busy })
        await settle()
        #expect(api.calls == ["setPhoneNumber:+31600000001"])
        #expect(s.busy == false)
    }

    @Test func aRejectedInputKeepsTheStepAndSaysWhy() async {
        let api = FakeTelegramApi()
        let s = session(api, .phone)
        api.emitAuth(.waitCode)
        #expect(await eventually { s.state == .needsCode })
        api.failNext = TgError(code: 400, name: "PHONE_CODE_INVALID")
        s.submitCode("00000")
        #expect(await eventually { s.lastError == "That code isn't right. Check the latest code in Telegram." })
        #expect(s.state == .needsCode)
        api.emitAuth(.waitPassword(hint: ""))
        #expect(await eventually { s.state == .needsPassword(hint: "") })
        #expect(s.lastError == nil, "cleared when Telegram moves on")
    }

    @Test func waitsAndFreshSessionRefusalsAreExplained() {
        #expect(TelegramSession.describe(TgError(code: 429, name: "Too Many Requests: retry after 300")) == "Telegram asks to wait 5 minutes before trying again.")
        #expect(TelegramSession.describe(TgError(code: 420, name: "FLOOD_WAIT_7200")) == "Telegram asks to wait 2 hours before trying again.")
        #expect(TelegramSession.describe(TgError(code: 406, name: "FRESH_RESET_AUTHORISATION_FORBIDDEN")).contains("24 hours"))
        #expect(TelegramSession.describe(TgError(code: 500, name: "Wrong database encryption key")).contains("damaged"))
        #expect(TelegramSession.describe(TgError(code: 500, name: "database disk image is malformed")).contains("connect it again"))
    }

    @Test func unsupportedStepsFailWithAUsefulMessage() async {
        let api = FakeTelegramApi()
        let s = session(api, .phone)
        api.emitAuth(.unsupported(name: "WaitRegistration"))
        #expect(await eventually { s.state == .failed("This phone number has no Telegram account. Create one in the Telegram app first.") })
    }

    @Test func phoneApprovesOnlyRealLoginLinks() async {
        let api = FakeTelegramApi()
        let s = session(api, .phone)
        let ownApi = self.ownApi
        let bad = await s.approveTvLogin(link: "https://example.com/?token=abc", ownApiId: ownApi)
        #expect({ if case .failure = bad { true } else { false } }())
        let chromecast = TgSessionInfo(id: 7, isCurrent: false, apiId: ownApi, applicationName: "Cablegram", deviceModel: "Chromecast", logInDate: 50)
        api.onConfirm = { [unowned api] in api.sessions.append(chromecast) }
        let approved = await s.approveTvLogin(link: link, ownApiId: ownApi)
        #expect((try? approved.get()) == ApprovedLogin(session: chromecast))
        #expect(api.calls == ["confirmQrLogin"])
        api.onConfirm = nil
        api.failNext = TgError(code: 400, name: "AUTH_TOKEN_EXPIRED")
        let expired = await s.approveTvLogin(link: link, ownApiId: ownApi)
        #expect(expired.failureDescription == "400 AUTH_TOKEN_EXPIRED")
    }

    @Test func aLinkMadeByAnotherTelegramAppIsEndedAtOnceAndTheApprovalFails() async {
        let api = FakeTelegramApi()
        let s = session(api, .phone)
        api.onConfirm = { [unowned api] in api.sessions.append(TgSessionInfo(id: 9, isCurrent: false, apiId: 2040, applicationName: "Telegram Desktop", deviceModel: "MacBook", logInDate: 60)) }
        let outcome = await s.approveTvLogin(link: link, ownApiId: ownApi)
        #expect(outcome.failureDescription == "403 UNEXPECTED_CLIENT")
        #expect(api.calls == ["confirmQrLogin", "terminateSession:9"])
    }

    @Test func nothingIsApprovedWhenTheSessionsCantBeListedFirst() async {
        let api = FakeTelegramApi()
        let s = session(api, .tv)
        api.failSessions = true
        let outcome = await s.approvePhoneLogin(link: link, ownApiId: ownApi)
        #expect(outcome.failureDescription == "500 SESSIONS_UNAVAILABLE")
        #expect(api.calls == [], "never confirmed blind")
    }

    @Test func anUnrelatedSessionThatAlreadyExistedIsNeverTakenForTheApprovedOne() async {
        let api = FakeTelegramApi()
        let s = session(api, .phone)
        // The owner's own desktop, signed in earlier and more recently active than anything else.
        api.sessions.append(TgSessionInfo(id: 3, isCurrent: false, apiId: 2040, applicationName: "Telegram Desktop", deviceModel: "MacBook", logInDate: 99))
        // Telegram is slow to list the new TV session: the approval succeeds without naming a session.
        let outcome = await s.approveTvLogin(link: link, ownApiId: ownApi)
        #expect((try? outcome.get()) == ApprovedLogin(session: nil))
        #expect(api.calls == ["confirmQrLogin"], "the desktop is left alone")
    }

    @Test func libraryChannelIsOpenedByIdFoundByTitleOrCreatedByThePhone() async throws {
        let api = FakeTelegramApi()
        let s = session(api, .phone)
        api.chats[-100] = TelegramSession.libraryTitle
        #expect(try await s.openLibrary(knownChatId: -100, allowCreate: false) == TgLibrary(chatId: -100, created: false))
        #expect(try await s.openLibrary(knownChatId: -5, allowCreate: false)?.chatId == -100, "found by title when the known id is gone")

        api.chats.removeAll()
        #expect(try await s.openLibrary(knownChatId: nil, allowCreate: false) == nil, "a TV never creates the channel")
        #expect(try await s.openLibrary(knownChatId: nil, allowCreate: true) == TgLibrary(chatId: -1001, created: true))
        #expect(api.calls.last == "createChannel:Cablegram library")
    }

    @Test func theLogoGoesOnANewChannelOrOneWithoutAPhotoNeverOverTheOwnersPhoto() async throws {
        let api = FakeTelegramApi()
        let s = session(api, .phone)
        _ = try await s.openLibrary(knownChatId: nil, allowCreate: true, logoPath: "/logo.jpg")
        #expect(api.calls.last == "setChannelPhoto:-1001")

        api.calls.removeAll(); api.photos.removeAll()
        _ = try await s.openLibrary(knownChatId: -1001, allowCreate: false, logoPath: "/logo.jpg")
        #expect(api.calls == ["setChannelPhoto:-1001"], "existing channel without a photo gets the logo")

        api.calls.removeAll(); api.photos[-1001] = "/owner-chose-this.jpg"
        _ = try await s.openLibrary(knownChatId: -1001, allowCreate: false, logoPath: "/logo.jpg")
        #expect(api.calls == [], "the owner's photo is kept")

        api.photos.removeAll(); api.failNext = TgError(code: 400, name: "PHOTO_INVALID_DIMENSIONS")
        #expect(try await s.openLibrary(knownChatId: -1001, allowCreate: false, logoPath: "/logo.jpg")?.chatId == -1001, "a failed upload doesn't fail linking")
    }

    @Test func aChannelTheOwnerLeftIsReportedAndReplacedByANewOne() async throws {
        let api = FakeTelegramApi()
        let s = session(api, .phone)
        api.chats[-100] = TelegramSession.libraryTitle
        #expect(await s.isLibraryAvailable(-100))

        api.left.insert(-100)
        #expect(await s.isLibraryAvailable(-100) == false)
        #expect(await s.isLibraryAvailable(-999) == false)
        #expect(try await s.openLibrary(knownChatId: -100, allowCreate: false) == nil, "a left channel is not reused")
        #expect(try await s.openLibrary(knownChatId: -100, allowCreate: true) == TgLibrary(chatId: -1001, created: true))
    }

    @Test func phoneNumbersAreNormalisedToInternationalFormat() {
        #expect(TelegramSession.normalizePhone("+31 6 12 34 56 78") == "+31612345678")
        #expect(TelegramSession.normalizePhone("0031 612345678") == "+31612345678")
        #expect(TelegramSession.normalizePhone("06-12345678") == "0612345678")
    }
}

extension Result {
    /// `description` of the failure, nil on success.
    var failureDescription: String? {
        if case let .failure(error) = self { return (error as? TgError)?.description ?? "\(error)" }
        return nil
    }
}
