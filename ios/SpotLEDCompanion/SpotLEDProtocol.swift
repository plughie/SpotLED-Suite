import Foundation

enum SpotLEDProtocolError: LocalizedError {
    case emptyText
    case textTooLong
    case unsupportedUnicode
    case invalidFrame
    case transferTooLarge
    case unsupportedDisplay(String)
    case rejected(UInt8)
    case timeout(String)
    case cancelled
    case bluetoothUnavailable
    case deviceNotSelected
    case missingCharacteristic
    case transport(String)

    var errorDescription: String? {
        switch self {
        case .emptyText: return "Enter text first."
        case .textTooLong: return "Native text exceeds its effective 154-character encoded-byte limit."
        case .unsupportedUnicode: return "Native text supports BMP characters only; emoji are not supported yet."
        case .invalidFrame: return "RGB data must be exactly 64×16 pixels."
        case .transferTooLarge: return "Transfer exceeds the display's receive limit."
        case .unsupportedDisplay(let reason): return "Display is not compatible: \(reason) No content was sent."
        case .rejected(let status): return "The display rejected the transfer (status \(status))."
        case .timeout(let phase): return "Timed out waiting for the display's \(phase) acknowledgement."
        case .cancelled: return "Transfer cancelled."
        case .bluetoothUnavailable: return "Turn on Bluetooth and allow SpotLED Companion to use it."
        case .deviceNotSelected: return "Find and select a SpotLED display first."
        case .missingCharacteristic: return "This display is missing the expected BLE characteristics."
        case .transport(let message): return message
        }
    }
}

struct NativeTextTransferSizes: Equatable {
    let characterCount: Int
    let fontPayloadBytes: Int
    let textPayloadBytes: Int
    let maximumPayloadBytes: Int
    let effectiveCharacterLimit: Int

    var fitsWithinLimits: Bool {
        characterCount <= effectiveCharacterLimit
            && fontPayloadBytes <= maximumPayloadBytes
            && textPayloadBytes <= maximumPayloadBytes
    }
}

struct SpotLEDDeviceCapabilities: Equatable {
    let width: Int
    let height: Int
    let colorType: UInt8
    let maximumFrames: Int
    let receiveBytes: Int
}

enum SpotLEDProtocol {
    static let width = 64
    static let height = 16
    static let maxReceiveBytes = 7_280
    static let maxNativeTextCharacters = 160
    static let nativeFontObjectBytes = 47
    static let rgbFramePayloadBytes = 15 + 9 + 7 + 5 + width * height * 3 + 10 + 8
    static let effectiveNativeTextCharacterLimit = min(
        maxNativeTextCharacters, (maxReceiveBytes - 24) / nativeFontObjectBytes)
    static let setDataObject: UInt16 = 0x8004

    static func buildQuery(id: UInt8, serial: UInt16) -> [UInt8] {
        [4, id, UInt8(serial >> 8), UInt8(serial & 0xff)]
    }

    static func parseCapabilities(screen: [UInt8], animation: [UInt8], capacity: [UInt8],
                                  screenSerial: UInt16, animationSerial: UInt16,
                                  capacitySerial: UInt16) throws -> SpotLEDDeviceCapabilities {
        guard validResponse(screen, id: 0x13, serial: screenSerial, minimumLength: 14),
              validResponse(animation, id: 0x1b, serial: animationSerial, minimumLength: 9),
              validResponse(capacity, id: 0x15, serial: capacitySerial, minimumLength: 9) else {
            throw SpotLEDProtocolError.transport("The display returned an invalid capability response.")
        }
        guard screen[4] == 0, animation[4] == 0, capacity[4] == 0 else {
            throw SpotLEDProtocolError.transport("The display rejected a capability query.")
        }
        return SpotLEDDeviceCapabilities(
            width: readU16(screen, 5), height: readU16(screen, 7), colorType: screen[9],
            maximumFrames: readU16(animation, 5), receiveBytes: Int(readU32(capacity, 5)))
    }

    static func validateRGB(_ capabilities: SpotLEDDeviceCapabilities, frameCount: Int,
                            payloadBytes: Int) throws {
        guard capabilities.width == width, capabilities.height == height else {
            throw SpotLEDProtocolError.unsupportedDisplay(
                "This app sends 64×16 frames; the display reports \(capabilities.width)×\(capabilities.height).")
        }
        guard capabilities.colorType == 0xFF else {
            throw SpotLEDProtocolError.unsupportedDisplay(
                String(format: "RGB requires color format 0xFF; the display reports 0x%02X.", capabilities.colorType))
        }
        guard frameCount <= capabilities.maximumFrames else {
            throw SpotLEDProtocolError.unsupportedDisplay("The animation exceeds the display's frame limit.")
        }
        guard capabilities.receiveBytes > 0, payloadBytes <= capabilities.receiveBytes else {
            throw SpotLEDProtocolError.unsupportedDisplay("The image exceeds the display's receive capacity.")
        }
    }

    static func validateNativeText(_ payloads: [[UInt8]], receiveBytes: Int) throws {
        guard receiveBytes > 0, payloads.allSatisfy({ $0.count <= receiveBytes }) else {
            throw SpotLEDProtocolError.unsupportedDisplay("The text exceeds the display's receive capacity.")
        }
    }

    private static func validResponse(_ bytes: [UInt8], id: UInt8, serial: UInt16,
                                      minimumLength: Int) -> Bool {
        bytes.count >= minimumLength && Int(bytes[0]) == bytes.count && bytes[1] == id
            && readU16(bytes, 2) == Int(serial)
    }

    private static func readU16(_ bytes: [UInt8], _ offset: Int) -> Int {
        Int(bytes[offset]) << 8 | Int(bytes[offset + 1])
    }

    private static func readU32(_ bytes: [UInt8], _ offset: Int) -> UInt32 {
        UInt32(bytes[offset]) << 24 | UInt32(bytes[offset + 1]) << 16
            | UInt32(bytes[offset + 2]) << 8 | UInt32(bytes[offset + 3])
    }

    static func nativeTextTransferSizes(_ text: String) -> NativeTextTransferSizes? {
        guard text.unicodeScalars.allSatisfy({ $0.value <= 0xFFFF }) else { return nil }
        let count = text.unicodeScalars.count
        // SetData wrapper + WordStock header + one 47-byte FontObject per scalar.
        let fontBytes = 15 + 9 + count * nativeFontObjectBytes
        // SetData wrapper + CharacterString header + Color/Text objects + time/effect objects.
        let textBytes = 15 + 10 + count * (10 + 9) + 10 + 8
        return NativeTextTransferSizes(
            characterCount: count,
            fontPayloadBytes: fontBytes,
            textPayloadBytes: textBytes,
            maximumPayloadBytes: maxReceiveBytes,
            effectiveCharacterLimit: effectiveNativeTextCharacterLimit)
    }

    static func checksum(_ bytes: [UInt8]) -> UInt8 {
        let sum = bytes.reduce(0) { $0 + Int($1) }
        return UInt8(sum > 0xff ? (-sum) & 0xff : sum & 0xff)
    }

    static func buildRGBFramePayload(_ rgb: [UInt8], intervalMs: UInt16 = 1_000,
                                     effectMode: UInt8 = 0) throws -> [UInt8] {
        guard rgb.count == width * height * 3 else { throw SpotLEDProtocolError.invalidFrame }
        var frameContent: [UInt8] = []
        appendU16(width, to: &frameContent)
        appendU16(height, to: &frameContent)
        frameContent.append(0x18) // 24-bit RGB
        frameContent.append(contentsOf: rgb)
        let frame = object(id: 0x60, content: frameContent)
        let timing = object(id: 7, content: [0, UInt8(intervalMs >> 8), UInt8(intervalMs & 0xff)])
        let effect = object(id: 8, content: [effectMode])
        let animation = objectWithFollow(id: 11, content: [0, 1],
                                         follow: frame + timing + effect)
        let payload = wrapSetData(animation, serial: 1)
        guard payload.count <= maxReceiveBytes else { throw SpotLEDProtocolError.transferTooLarge }
        return payload
    }

    static func buildRGBAnimationPayload(_ frames: [[UInt8]], intervalMs: UInt16,
                                         effectMode: UInt8 = 0) throws -> [UInt8] {
        guard !frames.isEmpty, frames.count <= Int(UInt16.max),
              frames.allSatisfy({ $0.count == width * height * 3 }),
              effectMode <= 7 else {
            throw SpotLEDProtocolError.invalidFrame
        }
        var frameObjects: [UInt8] = []
        for pixels in frames {
            var content: [UInt8] = []
            appendU16(width, to: &content)
            appendU16(height, to: &content)
            content.append(0x18)
            content.append(contentsOf: pixels)
            frameObjects.append(contentsOf: object(id: 0x60, content: content))
        }
        let timing = object(id: 7, content: [0, UInt8(intervalMs >> 8), UInt8(intervalMs & 0xff)])
        let effect = object(id: 8, content: [effectMode])
        let count = [UInt8(frames.count >> 8), UInt8(frames.count & 0xff)]
        let animation = objectWithFollow(id: 11, content: count,
                                         follow: frameObjects + timing + effect)
        let payload = wrapSetData(animation, serial: 1)
        guard payload.count <= maxReceiveBytes else { throw SpotLEDProtocolError.transferTooLarge }
        return payload
    }

    static func buildNativeTextPayloads(_ text: String, color: SpotLEDTextColor,
                                        effectMode: UInt8, intervalMs: UInt16 = 200,
                                        glyphRotation: Int = 0) throws -> [[UInt8]] {
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw SpotLEDProtocolError.emptyText
        }
        guard text.unicodeScalars.allSatisfy({ $0.value <= 0xFFFF }) else {
            throw SpotLEDProtocolError.unsupportedUnicode
        }
        let codePoints = text.unicodeScalars.map { UInt16($0.value) }
        guard codePoints.count <= maxNativeTextCharacters,
              let sizes = nativeTextTransferSizes(text), sizes.fitsWithinLimits else {
            throw SpotLEDProtocolError.textTooLong
        }

        var glyphs: [UInt8] = []
        guard glyphRotation == 0 || glyphRotation == 90 || glyphRotation == 270 else {
            throw SpotLEDProtocolError.invalidFrame
        }
        for codePoint in codePoints {
            glyphs.append(contentsOf: rotatedNativeFontObject(codePoint, degrees: glyphRotation))
        }
        let wordStock = objectWithFollow(id: 5, content: u16Bytes(codePoints.count), follow: glyphs)

        var content = u16Bytes(codePoints.count)
        content.append(1)
        var follow: [UInt8] = []
        for (index, codePoint) in codePoints.enumerated() {
            let rgb = color.rgb(at: index, count: codePoints.count)
            follow.append(contentsOf: object(id: 2, content: rgb))
            follow.append(contentsOf: object(id: 3,
                                             content: [UInt8(codePoint >> 8), UInt8(codePoint & 0xff)]))
        }
        follow.append(contentsOf: object(id: 7,
                                         content: [0, UInt8(intervalMs >> 8), UInt8(intervalMs & 0xff)]))
        follow.append(contentsOf: object(id: 8, content: [effectMode]))
        let characterString = objectWithFollow(id: 4, content: content, follow: follow)
        let transfers = [wrapSetData(wordStock, serial: 1), wrapSetData(characterString, serial: 2)]
        guard transfers.allSatisfy({ $0.count <= maxReceiveBytes }) else {
            throw SpotLEDProtocolError.transferTooLarge
        }
        return transfers
    }

    static func buildStart(serial: UInt16, totalLength: Int, isEnd: Bool = false) -> [UInt8] {
        var bytes: [UInt8] = [10, isEnd ? 3 : 1]
        appendU16(Int(serial), to: &bytes)
        appendU16(Int(setDataObject), to: &bytes)
        appendU32(totalLength, to: &bytes)
        return bytes
    }

    static func buildNativeTextSetupCommands() -> [[UInt8]] {
        [commandData(id: 0x800c, content: [UInt8](repeating: 0, count: 9), serial: 1),
         commandData(id: 0x8000, content: [0, 0x10], serial: 3)]
    }

    static func acknowledgment(_ data: Data, expectedType: UInt8, serial: UInt16) -> UInt8? {
        let bytes = [UInt8](data)
        guard bytes.count >= 5, bytes[1] == expectedType,
              (UInt16(bytes[2]) << 8 | UInt16(bytes[3])) == serial else { return nil }
        return bytes[4]
    }

    static func nativeFontObject(_ character: UInt8) -> [UInt8] {
        nativeFontObject(UInt16(character))
    }

    static func nativeFontObject(_ codePoint: UInt16) -> [UInt8] {
        if codePoint <= UInt16(UInt8.max),
           let captured = CapturedPanelGlyphs.fontObject(UInt8(codePoint)) {
            return captured
        }
        let rows = CompactFont.rows(forCodePoint: codePoint)
        var content = [UInt8](repeating: 0, count: 40)
        content[0] = 1
        content[2] = 16
        content[4] = 16
        content[5] = UInt8(codePoint >> 8)
        content[6] = UInt8(codePoint & 0xff)
        content[7] = 0x20
        for y in 0..<5 {
            for x in 0..<3 where rows[y].utf8[rows[y].utf8.index(rows[y].utf8.startIndex, offsetBy: x)] == 49 {
                let px = 2 + x * 4
                let py = 3 + y * 2
                for yy in py..<(py + 2) {
                    let rowOffset = 8 + yy * 2
                    var bits = UInt16(content[rowOffset]) << 8 | UInt16(content[rowOffset + 1])
                    for xx in px..<(px + 4) { bits |= 0x8000 >> xx }
                    content[rowOffset] = UInt8(bits >> 8)
                    content[rowOffset + 1] = UInt8(bits & 0xff)
                }
            }
        }
        return object(id: 13, content: content)
    }

    static func rotatedNativeFontObject(_ codePoint: UInt16, degrees: Int) -> [UInt8] {
        let glyph = nativeFontObject(codePoint)
        guard degrees == 90 || degrees == 270 else { return glyph }
        var rotated = glyph
        for row in 0..<16 {
            rotated[14 + row * 2] = 0
            rotated[15 + row * 2] = 0
        }
        for y in 0..<16 {
            let bits = UInt16(glyph[14 + y * 2]) << 8 | UInt16(glyph[15 + y * 2])
            for x in 0..<16 where (bits & (0x8000 >> x)) != 0 {
                let outX = degrees == 90 ? 15 - y : y
                let outY = degrees == 90 ? x : 15 - x
                let offset = 14 + outY * 2
                var row = UInt16(rotated[offset]) << 8 | UInt16(rotated[offset + 1])
                row |= 0x8000 >> outX
                rotated[offset] = UInt8(row >> 8)
                rotated[offset + 1] = UInt8(row & 0xff)
            }
        }
        rotated[rotated.count - 1] = checksum(Array(rotated.dropLast()))
        return rotated
    }

    private static func commandData(id: UInt16, content: [UInt8], serial: UInt32) -> [UInt8] {
        var bytes: [UInt8] = []
        appendU32(content.count + 11, to: &bytes)
        appendU16(Int(id), to: &bytes)
        appendU32(Int(serial), to: &bytes)
        bytes.append(contentsOf: content)
        bytes.append(checksum(bytes))
        return bytes
    }

    private static func wrapSetData(_ objectData: [UInt8], serial: UInt32) -> [UInt8] {
        var header: [UInt8] = []
        appendU32(15, to: &header)
        appendU16(Int(setDataObject), to: &header)
        appendU32(Int(serial), to: &header)
        appendU32(objectData.count, to: &header)
        header.append(checksum(header))
        return header + objectData
    }

    private static func object(id: UInt16, content: [UInt8]) -> [UInt8] {
        objectWithFollow(id: id, content: content, follow: [])
    }

    private static func objectWithFollow(id: UInt16, content: [UInt8], follow: [UInt8]) -> [UInt8] {
        var header: [UInt8] = []
        appendU32(content.count + 7, to: &header)
        appendU16(Int(id), to: &header)
        header.append(contentsOf: content)
        header.append(checksum(header))
        return header + follow
    }

    private static func u16Bytes(_ value: Int) -> [UInt8] {
        [UInt8((value >> 8) & 0xff), UInt8(value & 0xff)]
    }

    private static func appendU16(_ value: Int, to bytes: inout [UInt8]) {
        bytes.append(contentsOf: u16Bytes(value))
    }

    private static func appendU32(_ value: Int, to bytes: inout [UInt8]) {
        bytes.append(UInt8((value >> 24) & 0xff))
        bytes.append(UInt8((value >> 16) & 0xff))
        bytes.append(UInt8((value >> 8) & 0xff))
        bytes.append(UInt8(value & 0xff))
    }
}

enum SpotLEDTextColor: String, CaseIterable, Identifiable {
    case white = "White"
    case red = "Red"
    case orange = "Orange"
    case yellow = "Yellow"
    case green = "Green"
    case cyan = "Cyan"
    case blue = "Blue"
    case magenta = "Magenta"
    case rainbow = "Rainbow"

    var id: String { rawValue }

    func rgb(at index: Int, count: Int) -> [UInt8] {
        switch self {
        case .white: return [255, 255, 255]
        case .red: return [255, 0, 0]
        case .orange: return [255, 128, 0]
        case .yellow: return [255, 255, 0]
        case .green: return [0, 255, 0]
        case .cyan: return [0, 255, 255]
        case .blue: return [0, 0, 255]
        case .magenta: return [255, 0, 255]
        case .rainbow:
            let hue = count <= 1 ? 0 : 360.0 * Double(index) / Double(count)
            return Self.hsv(hue)
        }
    }

    private static func hsv(_ degrees: Double) -> [UInt8] {
        let h = (degrees.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) / 60
        let x = 1 - abs(h.truncatingRemainder(dividingBy: 2) - 1)
        let (r, g, b): (Double, Double, Double)
        switch h {
        case 0..<1: (r, g, b) = (1, x, 0)
        case 1..<2: (r, g, b) = (x, 1, 0)
        case 2..<3: (r, g, b) = (0, 1, x)
        case 3..<4: (r, g, b) = (0, x, 1)
        case 4..<5: (r, g, b) = (x, 0, 1)
        default: (r, g, b) = (1, 0, x)
        }
        return [UInt8((r * 255).rounded()), UInt8((g * 255).rounded()), UInt8((b * 255).rounded())]
    }
}
