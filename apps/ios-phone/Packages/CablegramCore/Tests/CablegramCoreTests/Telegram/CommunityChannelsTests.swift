import Foundation
import Testing
@testable import CablegramCore

// Port of `CommunityChannelsTest.kt`. The join order of the Swift type follows a `Set`, so tests compare sets where Kotlin compared lists.

@MainActor @Suite struct CommunityChannelsTests {
    static let allUsernames = Set(CablegramChannel.allCases.map { "joinCommunityChannel:\($0.username)" })

    @Test func allSelectedInitiallyButNothingHappensBeforeSave() {
        let api = FakeTelegramApi()
        let channels = CommunityChannels(api: api) { Issue.record("Not saved") }
        #expect(channels.state.selected == Set(CablegramChannel.allCases))
        channels.select(.news, selected: false)
        #expect(api.calls.isEmpty)
    }

    @Test func saveJoinsOnlySelectedChannelsAndCompletesOnce() async {
        let api = FakeTelegramApi()
        var completed = 0
        let channels = CommunityChannels(api: api) { completed += 1 }
        channels.select(.community, selected: false)
        await channels.save()
        await channels.save()
        #expect(api.calls.count == 2)
        #expect(Set(api.calls) == ["joinCommunityChannel:CablegramNews", "joinCommunityChannel:CablegramBotHubs"])
        #expect(completed == 1)
        #expect(channels.state.completed)
    }

    @Test func emptySelectionCompletesWithoutJoiningAnything() async {
        let api = FakeTelegramApi()
        var completed = false
        let channels = CommunityChannels(api: api) { completed = true }
        for channel in CablegramChannel.allCases { channels.select(channel, selected: false) }
        await channels.save()
        #expect(completed)
        #expect(api.calls.isEmpty)
    }

    @Test func partialFailureAttemptsEverySelectionAndRetriesOnlyFailures() async throws {
        let api = FakeTelegramApi()
        var completed = false
        let channels = CommunityChannels(api: api) { completed = true }
        api.failNext = TgError(code: 429, name: "FLOOD_WAIT_30")
        await channels.save()
        #expect(api.calls.count == 3)
        #expect(channels.state.failed.count == 1)  // Kotlin: exactly News (enum order); Swift joins in Set order
        let failed = try #require(channels.state.failed.first)
        #expect(channels.state.joined == Set(CablegramChannel.allCases).subtracting([failed]))
        #expect(!completed)
        await channels.save()
        #expect(api.calls.count == 4)
        #expect(api.calls.last == "joinCommunityChannel:\(failed.username)")
        #expect(completed)
    }

    @Test func failedChannelCanBeUncheckedToFinish() async throws {
        let api = FakeTelegramApi()
        let channels = CommunityChannels(api: api) {}
        api.failNext = TgError(code: 400, name: "USERNAME_NOT_OCCUPIED")
        await channels.save()
        let failed = try #require(channels.state.failed.first)
        channels.select(failed, selected: false)
        await channels.save()
        #expect(api.calls.count == 3)
        #expect(channels.state.completed)
    }

    @Test func duplicateSaveAndSelectionChangesAreIgnoredWhileSaving() async {
        let api = FakeTelegramApi()
        let gate = TgGate()
        let calls = TgLocked(0)
        api.joinHook = { _ in calls.update { $0 += 1 }; await gate.wait() }
        let channels = CommunityChannels(api: api) {}
        let first = Task { await channels.save() }
        #expect(await eventually { calls.value == 1 })
        channels.select(.news, selected: false)
        await channels.save()
        #expect(calls.value == 1)
        #expect(channels.state.selected.count == 3)
        await gate.open()
        await first.value
        #expect(calls.value == 3)
    }

    @Test func timeoutLeavesFailedChannelAvailableToRetry() async {
        let api = FakeTelegramApi()
        api.joinHook = { _ in try await Task.sleep(for: .seconds(3600)) }
        let channels = CommunityChannels(api: api, joinTimeout: 0.1) {}
        await channels.save()
        #expect(channels.state.failed == Set(CablegramChannel.allCases))
        #expect(!channels.state.saving)
        #expect(!channels.state.completed)
    }

    @Test func cancellationStopsFurtherJoinsAndClearsBusyState() async {
        let api = FakeTelegramApi()
        let calls = TgLocked(0)
        api.joinHook = { _ in calls.update { $0 += 1 }; try await Task.sleep(for: .seconds(3600)) }
        let channels = CommunityChannels(api: api) {}
        let job = Task { await channels.save() }
        #expect(await eventually { calls.value == 1 })
        job.cancel()
        await job.value
        #expect(calls.value == 1)
        #expect(!channels.state.saving)
        #expect(!channels.state.completed)
    }
}
