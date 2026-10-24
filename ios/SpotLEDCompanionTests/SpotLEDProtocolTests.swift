import XCTest
@testable import SpotLEDCompanion

final class SpotLEDProtocolTests: XCTestCase {
    func testLiveCapabilitiesAndValidation() throws {
        XCTAssertEqual(SpotLEDProtocol.buildQuery(id: 0x12, serial: 1), [4, 0x12, 0, 1])
        let screen: [UInt8] = [14, 0x13, 0, 1, 0, 0, 64, 0, 16, 0xff, 8, 80, 4, 1]
        let animation: [UInt8] = [9, 0x1b, 0, 2, 0, 0, 8, 0, 160]
        let capacity: [UInt8] = [9, 0x15, 0, 3, 0, 0, 0, 0x1c, 0x70]
        let caps = try SpotLEDProtocol.parseCapabilities(screen: screen, animation: animation,
                                                        capacity: capacity, screenSerial: 1,
                                                        animationSerial: 2, capacitySerial: 3)
        XCTAssertEqual(caps, SpotLEDDeviceCapabilities(width: 64, height: 16, colorType: 0xff,
                                                       maximumFrames: 8, receiveBytes: 7280))
        XCTAssertNoThrow(try SpotLEDProtocol.validateRGB(caps, frameCount: 2, payloadBytes: 7000))
        XCTAssertThrowsError(try SpotLEDProtocol.validateRGB(caps, frameCount: 9, payloadBytes: 7000))
        XCTAssertThrowsError(try SpotLEDProtocol.validateRGB(caps, frameCount: 1, payloadBytes: 7281))
        XCTAssertThrowsError(try SpotLEDProtocol.validateNativeText([[UInt8](repeating: 0, count: 7281)],
                                                                    receiveBytes: caps.receiveBytes))
        XCTAssertThrowsError(try SpotLEDProtocol.parseCapabilities(screen: screen, animation: animation,
                                                                   capacity: capacity, screenSerial: 99,
                                                                   animationSerial: 2, capacitySerial: 3))
        var unsupportedColor = screen
        unsupportedColor[9] = 0x18
        let rgbCaps = try SpotLEDProtocol.parseCapabilities(screen: unsupportedColor, animation: animation,
                                                            capacity: capacity, screenSerial: 1,
                                                            animationSerial: 2, capacitySerial: 3)
        XCTAssertThrowsError(try SpotLEDProtocol.validateRGB(rgbCaps, frameCount: 1, payloadBytes: 100))
    }

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
        XCTAssertNotNil(Data(payloads[1]).range(of: Data([0, 7, 0, 0, 150])))
    }

    func testNativeTextByteBudgetsExposeEffectiveCharacterBoundary() throws {
        let single = try XCTUnwrap(SpotLEDProtocol.nativeTextTransferSizes("A"))
        XCTAssertEqual(single.characterCount, 1)
        XCTAssertEqual(single.fontPayloadBytes, 71)
        XCTAssertEqual(single.textPayloadBytes, 62)
        XCTAssertEqual(single.effectiveCharacterLimit, 154)
        XCTAssertTrue(single.fitsWithinLimits)

        let maximum = try XCTUnwrap(SpotLEDProtocol.nativeTextTransferSizes(String(repeating: "A", count: 154)))
        XCTAssertEqual(maximum.fontPayloadBytes, 7_262)
        XCTAssertEqual(maximum.textPayloadBytes, 2_969)
        XCTAssertTrue(maximum.fitsWithinLimits)
        XCTAssertEqual(try SpotLEDProtocol.buildNativeTextPayloads(
            String(repeating: "A", count: 154), color: .white, effectMode: 3)[0].count, 7_262)

        let oneTooMany = try XCTUnwrap(SpotLEDProtocol.nativeTextTransferSizes(String(repeating: "A", count: 155)))
        XCTAssertEqual(oneTooMany.fontPayloadBytes, 7_309)
        XCTAssertFalse(oneTooMany.fitsWithinLimits)
        XCTAssertThrowsError(try SpotLEDProtocol.buildNativeTextPayloads(
            String(repeating: "A", count: 155), color: .white, effectMode: 3)) { error in
                guard case SpotLEDProtocolError.textTooLong = error else {
                    return XCTFail("Expected the effective encoded-byte limit error")
                }
            }
        XCTAssertNil(SpotLEDProtocol.nativeTextTransferSizes("😀"))
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

    func testPortraitTextRotatesNativeGlyphsAndKeepsScrollingString() throws {
        let clockwise = RGBRasterizer.textFrames("HELLO", color: .white,
                                                 orientation: .portrait)
        XCTAssertEqual(clockwise.preview.width, 64)
        XCTAssertEqual(clockwise.preview.height, 16)
        XCTAssertEqual(clockwise.wire.width, 64)
        XCTAssertEqual(clockwise.wire.height, 16)
        XCTAssertNotEqual(clockwise.preview.bytes,
                          RGBRasterizer.textFrames("HELLO", color: .white,
                                                   orientation: .landscape).preview.bytes)

        let clockwiseForA = RGBRasterizer.textFrames("ABCD", color: .white,
                                                     orientation: .portrait)
        let landscapeGlyph = SpotLEDProtocol.nativeFontObject(UInt8(ascii: "A"))
        var glyphPixels = [UInt8](repeating: 0, count: 16 * 16 * 3)
        for y in 0..<16 {
            let bits = UInt16(landscapeGlyph[14 + y * 2]) << 8
                | UInt16(landscapeGlyph[15 + y * 2])
            for x in 0..<16 where (bits & (0x8000 >> x)) != 0 {
                let offset = (y * 16 + x) * 3
                glyphPixels[offset] = 255
                glyphPixels[offset + 1] = 255
                glyphPixels[offset + 2] = 255
            }
        }
        let expectedGlyph = RGBRasterizer.rotate(glyphPixels, width: 16, height: 16,
                                                 degrees: 270).bytes
        let actualGlyph = stride(from: 0, to: 16 * 16 * 3, by: 3).flatMap { offset in
            let row = offset / (16 * 3)
            let column = (offset / 3) % 16
            let panelOffset = (row * 64 + column) * 3
            return Array(clockwiseForA.preview.bytes[panelOffset..<(panelOffset + 3)])
        }
        XCTAssertEqual(actualGlyph, expectedGlyph)
        let scrollPayloads = try SpotLEDProtocol.buildNativeTextPayloads(
            "HELLO", color: .white, effectMode: 3, glyphRotation: 270)
        XCTAssertEqual(scrollPayloads[0].count, 15 + 9 + 5 * 47)
        let expectedO = SpotLEDProtocol.rotatedNativeFontObject(79, degrees: 270)
        XCTAssertEqual(Array(scrollPayloads[0][15 + 9 + 4 * 47..<15 + 9 + 5 * 47]), expectedO)
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

    func testLowercaseDescendersReachLastCellRowAndPreserveCodepoints() {
        for character: UInt8 in [103, 106, 112, 113, 121] {
            let glyph = SpotLEDProtocol.nativeFontObject(character)
            XCTAssertTrue(glyph[44] != 0 || glyph[45] != 0)
            XCTAssertTrue(glyph[42] != 0 || glyph[43] != 0)
            XCTAssertEqual(UInt16(glyph[11]) << 8 | UInt16(glyph[12]), UInt16(character))
            let firstInkRow = (0..<16).first { glyph[14 + $0 * 2] != 0 || glyph[15 + $0 * 2] != 0 }
            XCTAssertEqual(firstInkRow, Optional(character == 106 ? 1 : character == 103 ? 3 : 4))
            if character == 103 {
                XCTAssertEqual(UInt16(glyph[40]) << 8 | UInt16(glyph[41]), 0x0300)
                XCTAssertEqual(UInt16(glyph[42]) << 8 | UInt16(glyph[43]), 0x0600)
                XCTAssertEqual(UInt16(glyph[44]) << 8 | UInt16(glyph[45]), 0x0c00)
            }
        }
        let preview = RGBRasterizer.nativeTextFrame("gpjy", color: .white)
        let bottomRow = (15 * 64 * 3)..<(16 * 64 * 3)
        let bottomBytes = Array(preview.bytes[bottomRow])
        for characterIndex in 0..<4 {
            let range = (characterIndex * 16 * 3)..<((characterIndex + 1) * 16 * 3)
            XCTAssertTrue(bottomBytes[range].contains { $0 != 0 })
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
