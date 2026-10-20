import Foundation

public struct TimeoutError: Error, Equatable { public init() {} }

/// Runs `operation`, giving up with `TimeoutError` after `seconds` (Kotlin `withTimeout`). The operation is cancelled when time runs out,
/// and the caller does not wait for it to notice: a call stuck on the network no longer holds the timeout back.
public func withTimeout<T: Sendable>(seconds: Double, _ operation: @escaping @Sendable () async throws -> T) async throws -> T {
    let race = TimeoutRace<T>()
    return try await withTaskCancellationHandler {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<T, Error>) in
            race.install(continuation)
            let work = Task {
                do { race.finish(.success(try await operation())) } catch { race.finish(.failure(error)) }
            }
            let timer = Task {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                if race.finish(.failure(TimeoutError())) { work.cancel() }
            }
            race.onFinish { work.cancel(); timer.cancel() }
        }
    } onCancel: {
        race.cancel()
    }
}

/// Resolves a timeout race exactly once.
private final class TimeoutRace<T: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<T, Error>?
    private var cleanup: (@Sendable () -> Void)?
    private var done = false
    private var cancelled = false

    func install(_ continuation: CheckedContinuation<T, Error>) {
        let early = lock.withLock { () -> Bool in
            if cancelled { done = true; return true }
            self.continuation = continuation
            return false
        }
        if early { continuation.resume(throwing: CancellationError()) }
    }

    func onFinish(_ body: @escaping @Sendable () -> Void) {
        let already = lock.withLock { () -> Bool in if done { return true }; cleanup = body; return false }
        if already { body() }
    }

    @discardableResult
    func finish(_ result: Result<T, Error>) -> Bool {
        let (taken, cleanup) = lock.withLock { () -> (CheckedContinuation<T, Error>?, (@Sendable () -> Void)?) in
            guard !done else { return (nil, nil) }
            done = true
            defer { continuation = nil; self.cleanup = nil }
            return (continuation, self.cleanup)
        }
        guard let taken else { return false }
        cleanup?()
        taken.resume(with: result.mapError { $0 as Error })
        return true
    }

    func cancel() {
        lock.withLock { cancelled = true }
        finish(.failure(CancellationError()))
    }
}

/// A lock that is held across `await`s (an actor alone is reentrant: two callers would interleave at every suspension point).
public actor AsyncMutex {
    private var locked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    public init() {}

    public func lock() async {
        if !locked { locked = true; return }
        await withCheckedContinuation { waiters.append($0) }
    }

    public func unlock() {
        if waiters.isEmpty { locked = false } else { waiters.removeFirst().resume() }
    }

    public func withLock<T: Sendable>(_ body: @Sendable () async throws -> T) async rethrows -> T {
        await lock()
        do {
            let result = try await body()
            unlock()
            return result
        } catch {
            unlock()
            throw error
        }
    }
}
