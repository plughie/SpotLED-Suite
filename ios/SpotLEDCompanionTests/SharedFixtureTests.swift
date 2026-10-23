import CryptoKit
import XCTest
@testable import SpotLEDCompanion

final class SharedFixtureTests: XCTestCase {
    private func fixture() throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle(for: Self.self)
            .url(forResource: "spotled_protocol_vectors", withExtension: "json"))
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func glyphData() throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle(for: Self.self)
            .url(forResource: "spotled_glyphs", withExtension: "json"))
        let data = try Data(contentsOf: url)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func bytes(_ hex: String) -> [UInt8] {
        stride(from: 0, to: hex.count, by: 2).map { offset in
            let start = hex.index(hex.startIndex, offsetBy: offset)
            let end = hex.index(start, offsetBy: 2)
            return UInt8(hex[start..<end], radix: 16)!
        }
    }

    func testSharedRotationVectorsMatchFixture() throws {
        let vector = try XCTUnwrap(fixture()["rotations_rgb"] as? [String: Any])
        let width = try XCTUnwrap(vector["width"] as? Int)
        let height = try XCTUnwrap(vector["height"] as? Int)
        let input = bytes(try XCTUnwrap(vector["input_hex"] as? String))
        let expected = try XCTUnwrap(vector["expected"] as? [String: [String: Any]])
        for degrees in [0, 90, 180, 270] {
            let item = try XCTUnwrap(expected[String(degrees)])
            let rotated = RGBRasterizer.rotate(input, width: width, height: height,
                                               degrees: degrees)
            XCTAssertEqual(rotated.width, item["width"] as? Int)
            XCTAssertEqual(rotated.height, item["height"] as? Int)
            XCTAssertEqual(rotated.bytes, bytes(try XCTUnwrap(item["hex"] as? String)))
        }
    }

    func testSharedNativeGlyphVectorsMatchFixture() throws {
        let glyphs = try XCTUnwrap(glyphData()["captured_panel16_hex"] as? [String: String])
        XCTAssertEqual(GeneratedGlyphs.capturedPanel16Hex.count, glyphs.count)
        for (code, hex) in glyphs {
            let byte = try XCTUnwrap(UInt8(code))
            let expected = bytes(hex)
            XCTAssertEqual(GeneratedGlyphs.capturedPanel16(byte), expected)
            XCTAssertEqual(SpotLEDProtocol.nativeFontObject(byte), expected)
        }
    }

    func testNativeBMPFallbackUsesQuestionGlyphAndPreservesCodePoint() throws {
        let vector = try XCTUnwrap(fixture()["native_text_unicode"] as? [String: Any])
        let codePoint = UInt16(try XCTUnwrap(vector["bmp_fallback_codepoint"] as? Int))
        let character = String(UnicodeScalar(codePoint)!)
        let glyph = SpotLEDProtocol.nativeFontObject(codePoint)
        XCTAssertEqual(glyph, bytes(try XCTUnwrap(vector["glyph_object_hex"] as? String)))
        XCTAssertEqual(Array(glyph[14..<46]),
                       Array(SpotLEDProtocol.nativeFontObject(UInt8(ascii: "?"))[14..<46]))
        XCTAssertEqual(UInt16(glyph[11]) << 8 | UInt16(glyph[12]), codePoint)

        let payloads = try SpotLEDProtocol.buildNativeTextPayloads(
            character, color: .white, effectMode: 3)
        let glyphOffset = 15 + 9
        XCTAssertEqual(Array(payloads[0][glyphOffset..<(glyphOffset + glyph.count)]), glyph)
        let textObject = bytes(try XCTUnwrap(vector["character_text_object_hex"] as? String))
        XCTAssertNotNil(Data(payloads[1]).range(of: Data(textObject)))
    }

    func testSharedCompactGlyphTableMatchesCanonicalSource() throws {
        let compact = try XCTUnwrap(glyphData()["compact_3x5"] as? [String: [String]])
        let expected = Dictionary(uniqueKeysWithValues: compact.map { (Character($0.key), $0.value) })
        XCTAssertEqual(GeneratedGlyphs.compact3x5, expected)
        for (character, rows) in compact {
            XCTAssertEqual(CompactFont.rows(for: try XCTUnwrap(character.utf8.first)), rows)
        }
    }

    func testSharedBlackFramePacketMatchesFixtureDigest() throws {
        let vector = try XCTUnwrap(fixture()["black_frame_64x16_1000ms"] as? [String: Any])
        let width = try XCTUnwrap(vector["width"] as? Int)
        let height = try XCTUnwrap(vector["height"] as? Int)
        let interval = try XCTUnwrap(vector["interval_ms"] as? Int)
        let effect = try XCTUnwrap(vector["effect_mode"] as? Int)
        let payload = try SpotLEDProtocol.buildRGBFramePayload(
            [UInt8](repeating: 0, count: width * height * 3),
            intervalMs: UInt16(interval), effectMode: UInt8(effect))
        let digest = SHA256.hash(data: Data(payload)).map { String(format: "%02x", $0) }.joined()
        XCTAssertEqual(payload.count, vector["payload_length"] as? Int)
        XCTAssertEqual(digest, vector["payload_sha256"] as? String)
        XCTAssertEqual(Array(payload.prefix(bytes(try XCTUnwrap(vector["payload_prefix_hex"] as? String)).count)),
                       bytes(try XCTUnwrap(vector["payload_prefix_hex"] as? String)))
    }
}
