import CablegramCore
import Foundation

/// Removing Telegram titles and "Save to Telegram" (`PhoneViewModel` sections "Removing Telegram titles" and "Save to Telegram").
extension TelegramController {
    private var token: String? { pairing.accountToken.flatMap { $0.isEmpty ? nil : $0 } }

    // MARK: TVs

    /// "Sign out of Telegram" for one TV: the phone ends the session on Telegram's side, even if the TV is off.
    func signOutTv(_ tv: PairedTv) {
        guard let token, let id = tv.deviceId else { return }
        Task {
            if await catalogClient().signOutTvTelegram(token: token, tvDeviceId: id) {
                setStatus(text("ui_signing_out_of_telegram_35bc7f", tv.name))
                work.start()
            } else {
                setStatus(L("ui_couldn_t_reach_cablegram_check_your_connection_and_try_de82d8"))
            }
        }
    }

    // MARK: Removing titles

    /// Hides the title everywhere (`permanently` false; it can be restored) or deletes the video, and every channel message with that file,
    /// from Telegram. The control plane removes it from all TVs.
    func removeTitle(_ item: LibraryItem, permanently: Bool) {
        guard let token else { return }
        Task {
            guard let orders = await catalogClient().removeTelegramTitle(token: token, itemId: item.id, mode: permanently ? "delete" : "hide") else {
                setStatus(text("ui_couldn_t_reach_cablegram_to_remove_check_your_connecti_8e61be", item.title))
                return
            }
            didRemove(item)
            store.removeFromLibrary(item.id)
            refresh()
            for order in orders {
                deletions.add(PendingTelegramDeletion(stableSourceKey: order.stableSourceKey, chatId: order.chatId, messageIds: order.messageIds, title: item.title, createdAt: Int64(Date().timeIntervalSince1970 * 1000)))
            }
            setStatus(text(permanently ? "ui_deleting_from_telegram_8a441e" : "ui_removed_restore_it_from_import_telegram_hidden_videos_7f17e6", item.title))
            processDeletions()
        }
    }

    /// Runs the queued channel deletions; whatever fails stays queued and is retried on the next start or removal.
    func processDeletions() {
        guard !deletions.all().isEmpty, let token, !deletionsRunning else { return }
        deletionsRunning = true
        Task {
            defer { deletionsRunning = false }
            guard PhoneTelegram.wasLinked else { return }
            let session = PhoneTelegram.session()
            guard await session.first(timeout: 30, where: { if case .ready = $0 { true } else { false } }) != nil else { return }
            let client = catalogClient()
            let jobs = deletions.all()
            // One walk of each channel for the whole batch, not one per queued deletion.
            var inChannel: [Int64: Result<[String: Set<Int64>], Error>] = [:]
            for (chatId, group) in Dictionary(grouping: jobs.filter { !$0.deletedInTelegram }, by: \.chatId) {
                do { inChannel[chatId] = .success(try await TelegramRemoval.channelMessageIds(session.api, chatId: chatId, keys: Set(group.map(\.stableSourceKey)))) }
                catch { inChannel[chatId] = .failure(error) }
            }
            for job in jobs {
                var failure: Error?
                if !job.deletedInTelegram {
                    do {
                        let found = try inChannel[job.chatId]!.get()[job.stableSourceKey] ?? []
                        let ids = found.union(job.messageIds).sorted()
                        if !ids.isEmpty { try await session.api.deleteMessages(chatId: job.chatId, messageIds: ids) }
                    } catch { failure = error }
                }
                if failure == nil {
                    deletions.markDeletedInTelegram(job.stableSourceKey)
                    // The entry goes only once the control plane knows; otherwise its tombstone would stay "deleting" forever. The retry
                    // only reports, it never deletes again.
                    if await client.reportTelegramDeletion(token: token, stableSourceKey: job.stableSourceKey) {
                        deletions.remove(job.stableSourceKey)
                        setStatus(text("ui_deleted_from_telegram_4e587f", job.title))
                    }
                } else {
                    let error = (failure as? TgError)?.name ?? L("ui_unknown_error_3e4443")
                    deletions.failed(job.stableSourceKey, error: error)
                    _ = await client.reportTelegramDeletion(token: token, stableSourceKey: job.stableSourceKey, error: error)
                    if Date().timeIntervalSince1970 * 1000 - Double(job.createdAt) > TelegramDeletionQueue.giveUpAfter * 1000 {
                        setStatus(text("ui_couldn_t_delete_from_telegram_open_telegram_and_delete_898bc2", job.title))
                    }
                }
            }
        }
    }

    /// Forgets the queued deletions (the account is gone).
    func clearDeletions() { for item in deletions.all() { deletions.remove(item.stableSourceKey) } }

    func openHiddenVideos() {
        showHidden = true
        loadHidden()
    }

    func loadHidden() {
        guard let token else { return }
        Task { hiddenTitles = await catalogClient().telegramTombstones(token: token) }
    }

    func restoreHidden(_ title: TelegramTombstone) {
        guard let token else { return }
        Task {
            if await catalogClient().restoreTelegramTitle(token: token, stableSourceKey: title.stableSourceKey) {
                setStatus(text("ui_restored_160ebf", title.title))
                loadHidden()
                await syncLibrary()
            } else {
                setStatus(text("ui_couldn_t_restore_check_your_connection_and_try_again_d17b3e", title.title))
            }
        }
    }

    // MARK: Save to Telegram

    /// Uploads this title's video into the household's Telegram channel, verifies it by size and file id, and adds it as a second source of
    /// the same title. Free up space is only offered after that. A failed or interrupted upload ends as "Save failed"; saving again retries.
    func saveToTelegram(_ item: LibraryItem) {
        guard canSaveToTelegram(item), saves.insert(item.id).inserted else { return }
        guard let token, let chatId = link?.chatId, link?.linked == true, PhoneTelegram.wasLinked else {
            saves.remove(item.id)
            setStatus(L("ui_connect_telegram_on_this_phone_first_import_telegram_41f79b"))
            return
        }
        Task {
            var prepared: PreparedUpload?
            defer { prepared?.cleanUp(); saves.remove(item.id); refresh() }
            do {
                let size = store.get(item.id).flatMap { videoLength($0) } ?? videoLength(item)
                store.updateItem(item.id) { var i = $0; i.transferStatus = transferSaving; i.uploadBytes = 0; i.uploadTotal = size; return i }
                refresh()
                let session = PhoneTelegram.session()
                guard await session.first(timeout: 30, where: { if case .ready = $0 { true } else { false } }) != nil else {
                    throw SaveFailure("Telegram isn't ready on this phone. Try again in a moment.")
                }
                // Refused before any upload: the account's file size limit (2 GB, 4 GB with Premium).
                if let refusal = telegramUploadRefusal(sizeBytes: size, limitBytes: try await session.api.uploadLimitBytes()) { throw SaveFailure(refusal) }
                // An earlier attempt may have reached the channel and failed afterwards: reuse that upload.
                let earlier = try await TelegramRemoval.findUpload(session.api, chatId: chatId, sizeBytes: size, uploadNames: store.telegramUploadNames(item))
                let sent: TgVideo
                if let earlier { sent = earlier } else {
                    let file = try await prepareUpload(item)
                    prepared = file
                    setStatus(text("ui_saving_to_telegram_8c9d51", item.title))
                    let progress = SaveProgress()
                    let store = self.store, id = item.id
                    sent = try await session.api.uploadVideo(chatId: chatId, path: file.url.path, caption: item.title) { done, total in
                        if progress.shouldReport() { store.updateItem(id) { var i = $0; i.uploadBytes = done; i.uploadTotal = total; return i } }
                    }
                }
                // Verify what Telegram stored before anything else relies on it.
                let stored = await session.api.message(chatId: chatId, messageId: sent.messageId)?.video
                guard let stored, stored.file.uniqueId == sent.file.uniqueId, stored.file.size == size else {
                    throw SaveFailure(L("ui_telegram_didn_t_confirm_the_upload_try_saving_again_c7e964"))
                }
                guard await catalogClient().attachTelegramCopy(token: token, localItemId: item.id, video: sent) else {
                    throw SaveFailure(L("ui_couldn_t_add_the_telegram_copy_to_your_library_try_sav_5406b8"))
                }
                store.updateItem(item.id) { var i = $0; i.telegramCopy = true; i.transferStatus = transferIdle; i.webTransferError = nil; i.uploadBytes = nil; i.uploadTotal = nil; return i }
                setStatus(text("ui_saved_to_telegram_you_can_now_free_up_phone_space_2c0ba2", item.title))
            } catch {
                // Our own messages (size, verification, registration) say which step failed; no secrets in them.
                store.updateItem(item.id) { var i = $0; i.transferStatus = transferFailed; i.webTransferError = telegramSaveFailed; return i }
                if error is CancellationError { return }
                setStatus((error as? TgError).map(TelegramSession.describe) ?? (error as? SaveFailure)?.message ?? text("ui_couldn_t_save_to_telegram_234f31", item.title))
            }
        }
    }

    private func videoLength(_ item: LibraryItem) -> Int64 {
        let local = store.videoFile(item)
        if let size = (try? FileManager.default.attributesOfItem(atPath: local.path))?[.size] as? NSNumber { return size.int64Value }
        return item.fileSizeBytes ?? 0
    }

    private struct SaveFailure: Error { var message: String; init(_ message: String) { self.message = message } }

    /// How often upload progress is written to the library (the index is rewritten each time).
    private final class SaveProgress: @unchecked Sendable {
        private let lock = NSLock()
        private var last = Date.distantPast
        func shouldReport() -> Bool { lock.withLock { let now = Date(); guard now.timeIntervalSince(last) >= 1 else { return false }; last = now; return true } }
    }

    /// The file TDLib uploads: it needs a real path named like the video (Telegram shows that name). A copied video is linked under that
    /// name; one indexed from other storage (Files, Photos) is copied into the cache first, which needs room for a second copy.
    private func prepareUpload(_ item: LibraryItem) async throws -> PreparedUpload {
        let directory = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("telegram-upload", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let target = directory.appendingPathComponent(store.telegramUploadName(item))
        try? FileManager.default.removeItem(at: target)
        let local = store.videoFile(item)
        if FileManager.default.fileExists(atPath: local.path) {
            if (try? FileManager.default.createSymbolicLink(at: target, withDestinationURL: local)) != nil { return PreparedUpload(url: target, temporary: true) }
            return PreparedUpload(url: local, temporary: false)
        }
        guard let opened = await media.openVideo(item) else { throw SaveFailure("Cablegram can't open this video any more.") }
        defer { opened.release() }
        let free = (try? directory.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?.volumeAvailableCapacityForImportantUsage ?? 0
        guard opened.length <= 0 || free > opened.length + 64 * 1024 * 1024 else { throw SaveFailure("Not enough free space on the phone to prepare the upload.") }
        try FileManager.default.copyItem(at: opened.url, to: target)
        return PreparedUpload(url: target, temporary: true)
    }

    struct PreparedUpload {
        var url: URL
        var temporary: Bool
        func cleanUp() { if temporary { try? FileManager.default.removeItem(at: url) } }
    }
}
