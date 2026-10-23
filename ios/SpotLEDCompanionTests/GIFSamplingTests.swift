import Foundation
import XCTest
@testable import SpotLEDCompanion

final class GIFSamplingTests: XCTestCase {
    func testGIFSamplingAndTimingMatchSharedPythonAndroidVectors() throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "gif_processing_vectors",
                                                          withExtension: "json"))
        let root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        let cases = try XCTUnwrap(root["cases"] as? [[String: Any]])
        for item in cases {
            let sourceFrames = try XCTUnwrap(item["source_frames"] as? Int)
            let limit = try XCTUnwrap(item["limit"] as? Int)
            let duration = try XCTUnwrap(item["duration_ms"] as? Double ?? (item["duration_ms"] as? Int).map(Double.init))
            let expected = try XCTUnwrap(item["indices"] as? [Int])
            let expectedInterval = try XCTUnwrap(item["interval_ms"] as? Int)
            XCTAssertEqual(RGBRasterizer.gifSampleIndices(frameCount: sourceFrames, limit: limit), expected)
            XCTAssertEqual(Int(RGBRasterizer.gifIntervalMs(totalDurationMs: duration,
                                                           sentFrameCount: expected.count)), expectedInterval)
        }
    }
}
