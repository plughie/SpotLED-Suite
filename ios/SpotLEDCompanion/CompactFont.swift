import Foundation

/// Compatibility accessors for glyphs generated from the shared JSON source.
enum CompactFont {
    static var glyphs: [Character: [String]] { GeneratedGlyphs.compact3x5 }

    static func rows(for byte: UInt8) -> [String] {
        let normalized = (97...122).contains(byte) ? byte - 32 : byte
        let scalar = UnicodeScalar(normalized)
        return glyphs[Character(scalar)] ?? glyphs["?"]!
    }

    static func rows(forCodePoint codePoint: UInt16) -> [String] {
        rows(for: codePoint <= 0x7f ? UInt8(codePoint) : UInt8(ascii: "?"))
    }
}

enum CapturedPanelGlyphs {
    static func fontObject(_ character: UInt8) -> [UInt8]? {
        GeneratedGlyphs.capturedPanel16(character)
    }
}
