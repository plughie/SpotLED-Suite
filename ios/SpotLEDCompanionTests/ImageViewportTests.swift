import XCTest
@testable import SpotLEDCompanion

final class ImageViewportTests: XCTestCase {
    func testZoomAndPanSelectOppositeImageEdges() {
        let red: [UInt8] = [255, 0, 0]
        let blue: [UInt8] = [0, 0, 255]
        let source = red + blue
        let left = RGBRasterizer.fitPixels(source, width: 2, height: 1,
                                           rotation: 0, fit: .stretch,
                                           targetWidth: 2, targetHeight: 1,
                                           zoom: 2, panX: -1, panY: 0)
        let right = RGBRasterizer.fitPixels(source, width: 2, height: 1,
                                            rotation: 0, fit: .stretch,
                                            targetWidth: 2, targetHeight: 1,
                                            zoom: 2, panX: 1, panY: 0)
        XCTAssertEqual(left.bytes, red + red)
        XCTAssertEqual(right.bytes, blue + blue)
    }

    func testVerticalScrollReturnsAdjacentFramesAndScrollEffect() throws {
        var source = [UInt8](repeating: 0, count: 16 * 32 * 3)
        for y in 0..<32 {
            for x in 0..<16 {
                let offset = (y * 16 + x) * 3
                if y < 16 { source[offset] = 255 }
                else { source[offset + 2] = 255 }
            }
        }
        let frames = RGBRasterizer.verticalScrollFrames(source, width: 16, height: 32)
        XCTAssertEqual(frames.count, 8)
        XCTAssertEqual(frames[0].bytes.count, 64 * 16 * 3)
        XCTAssertEqual(Array(frames[0].bytes[0..<3]), [255, 0, 0])
        XCTAssertEqual(Array(frames[frames.count - 1].bytes[0..<3]), [0, 0, 255])

        let payload = try SpotLEDProtocol.buildRGBAnimationPayload(
            Array(frames.prefix(2)).map(\.bytes), intervalMs: 250, effectMode: 1)
        XCTAssertNotNil(Data(payload).range(of: Data([0, 0, 0, 8, 0, 8, 1, 17])))
    }
}
