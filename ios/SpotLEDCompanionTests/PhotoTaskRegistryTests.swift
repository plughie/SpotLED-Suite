import XCTest
@testable import SpotLEDCompanion

final class PhotoTaskRegistryTests: XCTestCase {
    @MainActor
    func testReplacingImageDecodeCancelsObsoleteTask() {
        let registry = PhotoTaskRegistry()
        let obsolete = Task<Void, Never> { try? await Task.sleep(for: .seconds(60)) }
        let current = Task<Void, Never> { try? await Task.sleep(for: .seconds(60)) }

        registry.replace(.decoding) { obsolete.cancel() }
        registry.replace(.decoding) { current.cancel() }

        XCTAssertTrue(obsolete.isCancelled)
        XCTAssertFalse(current.isCancelled)
        registry.cancelAll()
        XCTAssertTrue(current.isCancelled)
    }

    @MainActor
    func testCancellingPhotoLoadDoesNotCancelCurrentDecode() {
        let registry = PhotoTaskRegistry()
        let loading = Task<Void, Never> { try? await Task.sleep(for: .seconds(60)) }
        let decoding = Task<Void, Never> { try? await Task.sleep(for: .seconds(60)) }
        registry.replace(.loading) { loading.cancel() }
        registry.replace(.decoding) { decoding.cancel() }

        registry.cancel(.loading)

        XCTAssertTrue(loading.isCancelled)
        XCTAssertFalse(decoding.isCancelled)
        registry.cancelAll()
        XCTAssertTrue(decoding.isCancelled)
    }

    @MainActor
    func testRasterizerHonorsCancellationBeforeStartingImageDecode() async throws {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1)).image { context in
            UIColor.cyan.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 1, height: 1))
        }
        let data = try XCTUnwrap(image.pngData())
        let gate = DecodeGate()
        let decode = Task.detached {
            await gate.wait()
            _ = try RGBRasterizer.imageFrames(data, rotation: 0, fit: .contain)
        }

        while !(await gate.isWaiting) { await Task.yield() }
        decode.cancel()
        await gate.open()

        do {
            _ = try await decode.value
            XCTFail("Cancelled image decoding should exit before rasterizing pixels")
        } catch is CancellationError {
            // Expected: the task cancellation is observed at the rasterizer's entry checkpoint.
        }
    }
}

private actor DecodeGate {
    private var continuation: CheckedContinuation<Void, Never>?
    private(set) var isWaiting = false

    func wait() async {
        await withCheckedContinuation { continuation in
            self.continuation = continuation
            isWaiting = true
        }
    }

    func open() {
        continuation?.resume()
        continuation = nil
    }
}
