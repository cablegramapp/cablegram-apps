import Foundation
import Testing
@testable import CablegramCore

// Port of `LibraryBotsTest.kt`. Not ported: `new bot rights cannot delete edit invite or promote` (it reflects over TDLib's
// `ChatAdministratorRights`; the Swift `TdlibTelegramApi` has no equivalent function to assert on).

@MainActor @Suite struct LibraryBotsTests {
    let first = RecommendedLibraryBot(id: 71, username: "FirstBot", displayName: "First bot")
    let second = RecommendedLibraryBot(id: 72, username: "SecondBot", displayName: "Second bot")
    let library: Int64 = -10042

    func fake(_ bots: [RecommendedLibraryBot]) -> FakeTelegramApi {
        let api = FakeTelegramApi()
        api.recommendedBots = bots
        return api
    }

    @Test func parsesHandlesDeepLinksAndEmbeddedButtonURLsWithoutDuplicates() {
        #expect(BotHubLinks.usernames(
            "Try @FirstBot, https://t.me/FirstBot?start=hello and (t.me/SecondBot). tg://resolve?domain=ThirdBot",
            embeddedUrls: ["https://telegram.me/FourthBot?startchannel&admin=delete_messages", "https://t.me/FifthBot"]
        ) == ["firstbot", "secondbot", "thirdbot", "fourthbot", "fifthbot"])
    }

    @Test func rejectsOffsiteLinksInviteLinksPostLinksAndEmailAddresses() {
        #expect(BotHubLinks.usernames(
            "person@FirstBot.com https://t.me.evil.test/FirstBot https://evil.test/t.me/FirstBot "
                + "https://t.me/+invite https://t.me/SomeChannel/123 https://t.me/Name@evil.test/FirstBot "
                + "https://t.me:444/FirstBot https://t.me@evil.test/FirstBot tg://join?invite=FirstBot"
        ).isEmpty)
    }

    @Test func discoveryAndUnacceptedAddNeverGrantBotAccess() async {
        let api = fake([first, second])
        let bots = LibraryBotRecommendations(api: api, libraryChatId: library, onDecision: { _ in }, onCompleted: {})
        await bots.addCurrent()
        await bots.load()
        await bots.addCurrent()
        #expect(api.calls == ["recommendedLibraryBots"])
        #expect(!bots.state.disclaimerAccepted)
        #expect(bots.state.current == first)
    }

    @Test func eachBotRequiresFreshAcceptanceAndTargetsTheLibraryOnly() async {
        let api = fake([first, second])
        var decisions: [Int64] = []
        var completed = 0
        let bots = LibraryBotRecommendations(api: api, libraryChatId: library, onDecision: { decisions.append($0) }, onCompleted: { completed += 1 })
        await bots.load()
        bots.acceptDisclaimer(true)
        await bots.addCurrent()
        #expect(bots.state.current == second)
        #expect(!bots.state.disclaimerAccepted)
        await bots.addCurrent()
        #expect(api.calls == ["recommendedLibraryBots", "addRecommendedBot:\(library):71"])
        bots.acceptDisclaimer(true)
        await bots.addCurrent()
        #expect(decisions == [71, 72])
        #expect(completed == 1)
        #expect(bots.state.completed)
        #expect(bots.state.added == 2)
    }

    @Test func skipIndividualBotsOrAllWithoutAcceptingDisclaimer() async {
        let api = fake([first, second])
        var decisions: [Int64] = []
        var completed = false
        let bots = LibraryBotRecommendations(api: api, libraryChatId: library, onDecision: { decisions.append($0) }, onCompleted: { completed = true })
        await bots.load()
        bots.skipCurrent()
        #expect(bots.state.current == second)
        bots.finish()
        bots.finish()
        #expect(decisions == [71])
        #expect(completed)
        #expect(api.calls == ["recommendedLibraryBots"])
    }

    @Test func savedDecisionsAndRepeatedRecommendationsAreFilteredOnResume() async {
        let api = fake([first, second, first, second])
        let bots = LibraryBotRecommendations(api: api, libraryChatId: library, handledBotIds: [first.id], onDecision: { _ in }, onCompleted: {})
        await bots.load()
        await bots.load()
        #expect(bots.state.bots == [second])
        #expect(api.calls == ["recommendedLibraryBots"])
    }

    @Test func failedAdditionStaysOnCurrentBotAndCanBeRetriedOrSkipped() async {
        let api = fake([first, second])
        var decisions: [Int64] = []
        let bots = LibraryBotRecommendations(api: api, libraryChatId: library, onDecision: { decisions.append($0) }, onCompleted: {})
        await bots.load()
        bots.acceptDisclaimer(true)
        api.failNext = TgError(code: 400, name: "CHAT_ADMIN_REQUIRED")
        await bots.addCurrent()
        #expect(bots.state.current == first)
        #expect(bots.state.error != nil)
        #expect(decisions.isEmpty)
        await bots.addCurrent()
        #expect(bots.state.current == second)
        #expect(decisions == [71])
        #expect(bots.state.error == nil)
    }

    @Test func failedDiscoveryIsRetryableAndEmptyFeedIsOptional() async {
        let api = FakeTelegramApi()
        var completed = false
        let bots = LibraryBotRecommendations(api: api, libraryChatId: library, onDecision: { _ in }, onCompleted: { completed = true })
        api.failNext = TgError(code: 500, name: "INTERNAL")
        await bots.load()
        #expect(!bots.state.loaded)
        #expect(bots.state.error != nil)
        await bots.load()
        #expect(bots.state.loaded)
        #expect(bots.state.current == nil)
        #expect(!completed)
        bots.finish()
        #expect(completed)
    }

    @Test func concurrentAddCallsAndSkipCannotChangeBotDuringAddition() async {
        let api = fake([first, second])
        let gate = TgGate()
        let additions = TgLocked(0)
        api.addBotHook = { _, _ in additions.update { $0 += 1 }; await gate.wait() }
        let bots = LibraryBotRecommendations(api: api, libraryChatId: library, onDecision: { _ in }, onCompleted: {})
        await bots.load()
        bots.acceptDisclaimer(true)
        let job = Task { await bots.addCurrent() }
        #expect(await eventually { additions.value == 1 })
        bots.skipCurrent()
        bots.finish()
        bots.acceptDisclaimer(false)
        await bots.addCurrent()
        #expect(additions.value == 1)
        #expect(bots.state.current == first)
        await gate.open()
        await job.value
        #expect(bots.state.current == second)
        #expect(!bots.state.disclaimerAccepted)
    }

    @Test func cancellationStopsAddingAndDoesNotRecordADecision() async {
        let api = fake([first])
        let started = TgLocked(false)
        api.addBotHook = { _, _ in started.value = true; try await Task.sleep(for: .seconds(3600)) }
        let bots = LibraryBotRecommendations(api: api, libraryChatId: library, onDecision: { _ in Issue.record("Not added") }, onCompleted: { Issue.record("Not completed") })
        await bots.load()
        bots.acceptDisclaimer(true)
        let job = Task { await bots.addCurrent() }
        #expect(await eventually { started.value })
        job.cancel()
        await job.value
        #expect(!bots.state.busy)
        #expect(bots.state.current == first)
        #expect(!bots.state.completed)
    }

    @Test func additionTimeoutKeepsTheCurrentRecommendationRetryable() async {
        let api = fake([first])
        api.addBotHook = { _, _ in try await Task.sleep(for: .seconds(3600)) }
        let bots = LibraryBotRecommendations(api: api, libraryChatId: library, addTimeout: 0.1, onDecision: { _ in Issue.record("Not added") }, onCompleted: { Issue.record("Not completed") })
        await bots.load()
        bots.acceptDisclaimer(true)
        await bots.addCurrent()
        #expect(bots.state.error != nil)
        #expect(!bots.state.busy)
        #expect(bots.state.current == first)
    }

    @Test func manualReviewAfterCompletionFetchesFreshBotsAndRequiresNewAcceptance() async {
        let api = fake([first])
        var decisions = Set<Int64>()
        let original = LibraryBotRecommendations(api: api, libraryChatId: library, onDecision: { decisions.insert($0) }, onCompleted: {})
        await original.load()
        original.acceptDisclaimer(true)
        original.skipCurrent()
        #expect(original.state.completed)
        #expect(decisions == [first.id])

        // Manual review intentionally omits saved handled IDs so a skipped bot can be reconsidered.
        api.recommendedBots = [first, second]
        let reopened = LibraryBotRecommendations(api: api, libraryChatId: library, onDecision: { decisions.insert($0) }, onCompleted: {})
        await reopened.load()
        #expect(reopened.state.bots == [first, second])
        #expect(reopened.state.current == first)
        #expect(!reopened.state.disclaimerAccepted)
        await reopened.addCurrent()
        #expect(api.calls == ["recommendedLibraryBots", "recommendedLibraryBots"])
        reopened.acceptDisclaimer(true)
        await reopened.addCurrent()
        #expect(api.calls.last == "addRecommendedBot:\(library):71")
        #expect(reopened.state.current == second)
        #expect(!reopened.state.disclaimerAccepted)
    }
}
