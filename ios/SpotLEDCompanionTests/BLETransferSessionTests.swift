import XCTest
@testable import SpotLEDCompanion

final class BLETransferSessionTests: XCTestCase {
    @MainActor
    func testConnectionReadinessTimeoutResumesItsWaiter() async {
        let session = BLETransferSession()
        let id = session.begin()

        do {
            try await session.wait(for: .ready, sessionID: id, timeout: 0.01,
                                   timeoutError: SpotLEDProtocolError.timeout("BLE connection"))
            XCTFail("Expected the readiness wait to time out")
        } catch {
            XCTAssertEqual(error.localizedDescription,
                           SpotLEDProtocolError.timeout("BLE connection").localizedDescription)
        }
        XCTAssertNil(session.waitingPhase)
        session.finish(id)
    }

    @MainActor
    func testAcknowledgementTimeoutDoesNotLeaveAStaleWaiter() async {
        let session = BLETransferSession()
        let id = session.begin()
        let phase = BLETransferPhase.acknowledgement(type: 2, serial: 7)

        do {
            try await session.wait(for: phase, sessionID: id, timeout: 0.01,
                                   timeoutError: SpotLEDProtocolError.timeout("transfer"))
            XCTFail("Expected the acknowledgement wait to time out")
        } catch {
            XCTAssertEqual(error.localizedDescription,
                           SpotLEDProtocolError.timeout("transfer").localizedDescription)
        }
        XCTAssertNil(session.waitingPhase)
        session.finish(id)
    }

    @MainActor
    func testLateReadyCallbackFromOldSessionCannotReleaseNewWaiter() async throws {
        let session = BLETransferSession()
        let oldID = session.begin()
        let oldWait = Task {
            try await session.wait(for: .ready, sessionID: oldID, timeout: 1,
                                   timeoutError: SpotLEDProtocolError.timeout("BLE connection"))
        }
        await waitUntilWaiting(session, for: .ready)
        session.cancelActive()
        do { try await oldWait.value } catch { }
        session.finish(oldID)

        let currentID = session.begin()
        let currentWait = Task {
            try await session.wait(for: .ready, sessionID: currentID, timeout: 1,
                                   timeoutError: SpotLEDProtocolError.timeout("BLE connection"))
        }
        await waitUntilWaiting(session, for: .ready)

        XCTAssertFalse(session.resolve(.ready, sessionID: oldID, with: .success(())))
        XCTAssertEqual(session.waitingPhase, .ready)
        XCTAssertTrue(session.resolve(.ready, sessionID: currentID, with: .success(())))
        try await currentWait.value
        session.finish(currentID)
    }

    @MainActor
    func testDisconnectDuringChunkWritesClearsQueueAndFailsWaiter() async throws {
        let session = BLETransferSession()
        let id = session.begin()
        try session.enqueueChunks(Array(repeating: 0xA5, count: 12), size: 3, sessionID: id)
        XCTAssertEqual(session.queuedChunkCount, 4)
        let chunkWait = Task {
            try await session.wait(for: .chunkWrites, sessionID: id, timeout: 1,
                                   timeoutError: SpotLEDProtocolError.timeout("BLE data writes"))
        }
        await waitUntilWaiting(session, for: .chunkWrites)

        session.connectionLost(sessionID: id,
                               error: SpotLEDProtocolError.transport("Disconnected during BLE data writes."))
        XCTAssertEqual(session.queuedChunkCount, 0)
        do {
            try await chunkWait.value
            XCTFail("Expected disconnect to fail the chunk-write wait")
        } catch {
            XCTAssertEqual(error.localizedDescription, "Disconnected during BLE data writes.")
        }
        session.finish(id)
    }

    @MainActor
    func testExplicitAndBackgroundCancellationReleasePendingWaits() async throws {
        for _ in 0..<2 {
            let session = BLETransferSession()
            let id = session.begin()
            try session.enqueueChunks(Array(repeating: 0x5A, count: 8), size: 2, sessionID: id)
            let wait = Task {
                try await session.wait(for: .chunkWrites, sessionID: id, timeout: 1,
                                       timeoutError: SpotLEDProtocolError.timeout("BLE data writes"))
            }
            await waitUntilWaiting(session, for: .chunkWrites)

            // Explicit cancel and app-background cancellation use this same
            // session cleanup path in SpotLedBLEManager.
            session.cancelActive()
            XCTAssertEqual(session.queuedChunkCount, 0)
            do {
                try await wait.value
                XCTFail("Expected cancellation to release the pending write wait")
            } catch {
                XCTAssertEqual(error.localizedDescription,
                               SpotLEDProtocolError.cancelled.localizedDescription)
            }
            session.finish(id)
        }
    }

    @MainActor
    func testBluetoothPowerOffCancelsReadinessWait() async throws {
        let session = BLETransferSession()
        let id = session.begin()
        let wait = Task {
            try await session.wait(for: .ready, sessionID: id, timeout: 1,
                                   timeoutError: SpotLEDProtocolError.timeout("BLE connection"))
        }
        await waitUntilWaiting(session, for: .ready)

        session.cancelActive(with: SpotLEDProtocolError.bluetoothUnavailable)
        do {
            try await wait.value
            XCTFail("Expected Bluetooth power-off to cancel the readiness wait")
        } catch {
            XCTAssertEqual(error.localizedDescription,
                           SpotLEDProtocolError.bluetoothUnavailable.localizedDescription)
        }
        session.finish(id)
    }

    @MainActor
    func testNewTransferSucceedsAfterCancelledSession() async throws {
        let session = BLETransferSession()
        let failedID = session.begin()
        let interruptedWait = Task {
            try await session.wait(for: .ready, sessionID: failedID, timeout: 1,
                                   timeoutError: SpotLEDProtocolError.timeout("BLE connection"))
        }
        await waitUntilWaiting(session, for: .ready)
        session.cancelActive()
        do { try await interruptedWait.value } catch { }
        session.finish(failedID)

        let id = session.begin()
        try await session.wait(for: .ready, sessionID: id, timeout: 1,
                               timeoutError: SpotLEDProtocolError.timeout("BLE connection")) {
            XCTAssertTrue(session.resolve(.ready, sessionID: id, with: .success(())))
        }
        let ack = BLETransferPhase.acknowledgement(type: 2, serial: 8)
        try await session.wait(for: ack, sessionID: id, timeout: 1,
                               timeoutError: SpotLEDProtocolError.timeout("transfer")) {
            XCTAssertTrue(session.resolve(ack, sessionID: id, with: .success(())))
        }
        try session.enqueueChunks([1, 2, 3, 4], size: 2, sessionID: id)
        try await session.wait(for: .chunkWrites, sessionID: id, timeout: 1,
                               timeoutError: SpotLEDProtocolError.timeout("BLE data writes")) {
            XCTAssertNotNil(session.dequeueChunk(sessionID: id))
            XCTAssertNotNil(session.dequeueChunk(sessionID: id))
            XCTAssertTrue(session.resolve(.chunkWrites, sessionID: id, with: .success(())))
        }
        session.finish(id)
        XCTAssertNil(session.activeSessionID)
    }

    @MainActor
    private func waitUntilWaiting(_ session: BLETransferSession,
                                  for phase: BLETransferPhase,
                                  file: StaticString = #filePath,
                                  line: UInt = #line) async {
        for _ in 0..<100 {
            if session.waitingPhase == phase { return }
            await Task.yield()
        }
        XCTFail("The BLE session did not register its \(phase) wait", file: file, line: line)
    }
}
