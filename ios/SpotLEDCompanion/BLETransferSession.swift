import Foundation

enum BLETransferPhase: Equatable {
    case ready
    case acknowledgement(type: UInt8, serial: UInt16)
    case response(type: UInt8, serial: UInt16)
    case chunkWrites
}

/// Session-scoped asynchronous state shared by the CoreBluetooth manager and
/// simulator tests. Every callback and timeout must match both session and phase.
final class BLETransferSession {
    private struct PendingWait {
        let sessionID: UUID
        let phase: BLETransferPhase
        let token: UUID
        let continuation: CheckedContinuation<Void, Error>
        var timeout: DispatchWorkItem?
    }

    private(set) var activeSessionID: UUID?
    private(set) var cancellationRequested = false
    private var pendingWait: PendingWait?
    private var pendingChunks: [Data] = []

    var waitingPhase: BLETransferPhase? { pendingWait?.phase }
    var queuedChunkCount: Int { pendingChunks.count }

    func begin() -> UUID {
        precondition(activeSessionID == nil && pendingWait == nil,
                     "Cannot begin a BLE transfer while another session is active")
        let id = UUID()
        activeSessionID = id
        cancellationRequested = false
        pendingChunks.removeAll()
        return id
    }

    func finish(_ sessionID: UUID) {
        guard activeSessionID == sessionID else { return }
        if let wait = pendingWait, wait.sessionID == sessionID {
            complete(wait, with: .failure(SpotLEDProtocolError.cancelled))
        }
        pendingChunks.removeAll()
        activeSessionID = nil
        cancellationRequested = false
    }

    func check(_ sessionID: UUID) throws {
        guard activeSessionID == sessionID, !cancellationRequested, !Task.isCancelled else {
            throw SpotLEDProtocolError.cancelled
        }
    }

    func wait(for phase: BLETransferPhase, sessionID: UUID,
              timeout: TimeInterval, timeoutError: Error,
              start: () -> Void = {}) async throws {
        try check(sessionID)
        guard pendingWait == nil else {
            throw SpotLEDProtocolError.transport("A BLE session already has an outstanding wait.")
        }

        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            let token = UUID()
            var wait = PendingWait(sessionID: sessionID, phase: phase,
                                   token: token, continuation: continuation)
            let timeoutWork = DispatchWorkItem { [weak self] in
                guard let self,
                      let current = self.pendingWait,
                      current.sessionID == sessionID,
                      current.phase == phase,
                      current.token == token else { return }
                if phase == .chunkWrites { self.pendingChunks.removeAll() }
                self.complete(current, with: .failure(timeoutError))
            }
            wait.timeout = timeoutWork
            pendingWait = wait
            DispatchQueue.main.asyncAfter(deadline: .now() + max(0, timeout),
                                          execute: timeoutWork)
            start()
        }
    }

    @discardableResult
    func resolve(_ phase: BLETransferPhase, sessionID: UUID,
                 with result: Result<Void, Error>) -> Bool {
        guard activeSessionID == sessionID,
              let wait = pendingWait,
              wait.sessionID == sessionID,
              wait.phase == phase else { return false }
        complete(wait, with: result)
        return true
    }

    func cancelActive(with error: Error = SpotLEDProtocolError.cancelled) {
        guard let sessionID = activeSessionID else { return }
        cancellationRequested = true
        pendingChunks.removeAll()
        if let wait = pendingWait, wait.sessionID == sessionID {
            complete(wait, with: .failure(error))
        }
    }

    func connectionLost(sessionID: UUID, error: Error) {
        pendingChunks.removeAll()
        guard let wait = pendingWait, wait.sessionID == sessionID else { return }
        complete(wait, with: .failure(error))
    }

    func enqueueChunks(_ payload: [UInt8], size: Int, sessionID: UUID) throws {
        try check(sessionID)
        guard size > 0 else {
            throw SpotLEDProtocolError.transport("Bluetooth reported a zero write size.")
        }
        pendingChunks = stride(from: 0, to: payload.count, by: size).map { offset in
            Data(payload[offset..<min(payload.count, offset + size)])
        }
    }

    func dequeueChunk(sessionID: UUID) -> Data? {
        guard activeSessionID == sessionID, !pendingChunks.isEmpty else { return nil }
        return pendingChunks.removeFirst()
    }

    private func complete(_ wait: PendingWait, with result: Result<Void, Error>) {
        guard pendingWait?.token == wait.token else { return }
        pendingWait?.timeout?.cancel()
        pendingWait = nil
        switch result {
        case .success: wait.continuation.resume()
        case .failure(let error): wait.continuation.resume(throwing: error)
        }
    }
}
