import XCTest
@testable import SpotLEDCompanion

final class SpotLEDProtocolTests: XCTestCase {
    func testRGBFramePayloadMatchesAndroidLayoutAndSize() throws {
        let pixels = [UInt8](repeating: 0, count: 64 * 16 * 3)
        let payload = try SpotLEDProtocol.buildRGBFramePayload(pixels)
        XCTAssertEqual(payload.count, 3_126)
        XCTAssertEqual(Array(payload.prefix(6)), [0, 0, 0, 15, 0x80, 0x04])
        XCTAssertEqual(Array(payload[19...20]), [0, 11])
    }

    func testTwoFrameAnimationUsesAndroidFrameAndTimingObjects() throws {
        let pixels = [UInt8](repeating: 0, count: 64 * 16 * 3)
        let payload = try SpotLEDProtocol.buildRGBAnimationPayload([pixels, pixels], intervalMs: 500)
        XCTAssertEqual(payload.count, 6_210)
        XCTAssertEqual(Array(payload[21...22]), [0, 2])
        XCTAssertLessThanOrEqual(payload.count, SpotLEDProtocol.maxReceiveBytes)
    }

    func testNativeTextCreatesSeparateFontAndCharacterTransfers() throws {
        let payloads = try SpotLEDProtocol.buildNativeTextPayloads("A", color: .white, effectMode: 3)
        XCTAssertEqual(payloads.count, 2)
        XCTAssertEqual(payloads[0].count, 71)
        XCTAssertEqual(payloads[1].count, 62)
        XCTAssertEqual(Array(payloads[0][4...5]), [0x80, 0x04])
        XCTAssertEqual(Array(payloads[1][4...5]), [0x80, 0x04])
    }

    func testNativeTextPreservesLowercaseAndRequestedSpeed() throws {
        let payloads = try SpotLEDProtocol.buildNativeTextPayloads(
            "a", color: .white, effectMode: 3, intervalMs: 150)
        let glyphOffset = 15 + 9
        XCTAssertEqual(payloads[0][glyphOffset + 12], UInt8(ascii: "a"))
        XCTAssertNotNil(Data(payloads[1]).range(of: Data([0, 7, 0, 0, 0, 150])))
    }

    func testNonBMPNativeTextIsRejectedExplicitly() {
        XCTAssertThrowsError(try SpotLEDProtocol.buildNativeTextPayloads(
            "A 🦆", color: .rainbow, effectMode: 6)) { error in
                guard case SpotLEDProtocolError.unsupportedUnicode = error else {
                    return XCTFail("Expected an explicit non-BMP text error")
                }
            }
    }

    func testTextPreviewIsA64By16RGBFrame() {
        let frame = RGBRasterizer.textFrame("B", color: .white)
        XCTAssertEqual(frame.width, 64)
        XCTAssertEqual(frame.height, 16)
        XCTAssertEqual(frame.bytes.count, 64 * 16 * 3)
        XCTAssertTrue(frame.bytes.contains(where: { $0 != 0 }))
    }

    func testNativeTextPreviewUsesPanelFontAndScreenDimensions() {
        let frame = RGBRasterizer.nativeTextFrame("A", color: .white)
        XCTAssertEqual(frame.width, 64)
        XCTAssertEqual(frame.height, 16)
        XCTAssertEqual(frame.bytes.count, 64 * 16 * 3)
        XCTAssertTrue(frame.bytes.contains(where: { $0 != 0 }))
        for row in 0..<16 {
            let rightOfFirstCell = row * 64 * 3 + 16 * 3
            XCTAssertTrue(frame.bytes[rightOfFirstCell..<(row + 1) * 64 * 3].allSatisfy { $0 == 0 })
        }
    }

    func testCapitalINativeGlyphHasSerifs() {
        let glyph = SpotLEDProtocol.nativeFontObject(UInt8(ascii: "I"))
        func row(_ y: Int) -> UInt16 {
            UInt16(glyph[14 + y * 2]) << 8 | UInt16(glyph[15 + y * 2])
        }
        XCTAssertEqual(glyph.count, 47)
        XCTAssertEqual(row(0), 0)
        XCTAssertEqual(row(1), 0)
        XCTAssertEqual(row(2), 0x1ff0)
        XCTAssertEqual(row(3), 0x0100)
        XCTAssertEqual(row(12), 0x0100)
        XCTAssertEqual(row(13), 0x1ff0)
        XCTAssertEqual(row(14), 0)
        XCTAssertEqual(row(15), 0)
    }
}
