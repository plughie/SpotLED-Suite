import CoreGraphics
import Foundation
import ImageIO
import UIKit

struct RGBFrame: Sendable {
    let width: Int
    let height: Int
    let bytes: [UInt8]

    static let screenWidth = 64
    static let screenHeight = 16
}

enum ImageFit: String, CaseIterable, Identifiable, Sendable {
    case contain = "Contain"
    case cover = "Cover"
    case stretch = "Stretch"
    var id: String { rawValue }
}

enum RGBRasterizer {
    static func imageFrames(_ data: Data, rotation: Int, fit: ImageFit)
        throws -> (frames: [RGBFrame], intervalMs: UInt16) {
        guard data.count <= 50_000_000 else { throw SpotLEDProtocolError.transferTooLarge }
        guard let source = CGImageSourceCreateWithData(data as CFData, nil) else {
            throw SpotLEDProtocolError.invalidFrame
        }
        let frameCount = CGImageSourceGetCount(source)
        guard frameCount > 0 else { throw SpotLEDProtocolError.invalidFrame }

        var delays: [Double] = []
        for index in 0..<frameCount {
            let props = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any]
            let gif = props?[kCGImagePropertyGIFDictionary] as? [CFString: Any]
            let delay = (gif?[kCGImagePropertyGIFUnclampedDelayTime] as? NSNumber)?.doubleValue
                ?? (gif?[kCGImagePropertyGIFDelayTime] as? NSNumber)?.doubleValue
                ?? 0.1
            delays.append(max(0.02, delay))
        }
        let totalDuration = delays.reduce(0, +)
        let sampleIndices: [Int]
        if frameCount > 1 {
            let midpoint = totalDuration / 2
            var elapsed = 0.0
            var secondIndex = frameCount / 2
            for (index, delay) in delays.enumerated() {
                elapsed += delay
                if elapsed >= midpoint { secondIndex = index; break }
            }
            if secondIndex == 0 { secondIndex = min(1, frameCount - 1) }
            sampleIndices = [0, secondIndex]
        } else {
            sampleIndices = [0]
        }

        var frames: [RGBFrame] = []
        for index in sampleIndices {
            let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any]
            let width = (properties?[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue ?? 0
            let height = (properties?[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue ?? 0
            guard width > 0, height > 0,
                  Double(width) * Double(height) <= 1_000_000_000 else {
                throw SpotLEDProtocolError.invalidFrame
            }
            let pixelCount = Double(width) * Double(height)
            let maxDimension = pixelCount > 4_000_000
                ? max(1, Int(Double(max(width, height)) * sqrt(4_000_000.0 / pixelCount)))
                : max(width, height)
            let options: [CFString: Any] = [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: maxDimension,
                kCGImageSourceShouldCacheImmediately: true
            ]
            guard let oriented = CGImageSourceCreateThumbnailAtIndex(source, index, options as CFDictionary) else {
                throw SpotLEDProtocolError.invalidFrame
            }
            let normalized = UIImage(cgImage: oriented, scale: 1, orientation: .up)
            frames.append(try imageFrame(normalized, rotation: rotation, fit: fit))
        }
        let interval = frameCount > 1
            ? max(250, min(1000, Int((totalDuration * 500).rounded())))
            : 500
        return (frames, UInt16(interval))
    }

    static func nativeTextFrame(_ text: String, color: SpotLEDTextColor) -> RGBFrame {
        let chars = normalizedASCII(text, uppercased: false)
        var pixels = [UInt8](repeating: 0,
                             count: RGBFrame.screenWidth * RGBFrame.screenHeight * 3)
        for (characterIndex, byte) in chars.enumerated() {
            let glyph = SpotLEDProtocol.nativeFontObject(byte)
            let rgb = color.rgb(at: characterIndex, count: chars.count)
            let xStart = characterIndex * 16
            guard xStart < RGBFrame.screenWidth, glyph.count >= 46 else { continue }
            for y in 0..<RGBFrame.screenHeight {
                let rowOffset = 14 + y * 2
                let bits = UInt16(glyph[rowOffset]) << 8 | UInt16(glyph[rowOffset + 1])
                for x in 0..<16 where (bits & (0x8000 >> x)) != 0 {
                    let targetX = xStart + x
                    guard targetX < RGBFrame.screenWidth else { continue }
                    let offset = (y * RGBFrame.screenWidth + targetX) * 3
                    pixels[offset] = rgb[0]
                    pixels[offset + 1] = rgb[1]
                    pixels[offset + 2] = rgb[2]
                }
            }
        }
        return RGBFrame(width: RGBFrame.screenWidth,
                        height: RGBFrame.screenHeight, bytes: pixels)
    }

    static func textFrame(_ text: String, color: SpotLEDTextColor,
                          rotation: Int = 0, fit: ImageFit = .contain) -> RGBFrame {
        let chars = normalizedASCII(text)
        let sourceWidth = max(1, chars.count * 4 - 1)
        var pixels = [UInt8](repeating: 0, count: sourceWidth * 5 * 3)
        for (characterIndex, byte) in chars.enumerated() {
            let rows = CompactFont.rows(for: byte)
            let rgb = color.rgb(at: characterIndex, count: chars.count)
            for y in 0..<5 {
                let columns = Array(rows[y].utf8)
                for x in 0..<3 where columns[x] == 49 {
                    let offset = (y * sourceWidth + characterIndex * 4 + x) * 3
                    pixels[offset] = rgb[0]
                    pixels[offset + 1] = rgb[1]
                    pixels[offset + 2] = rgb[2]
                }
            }
        }
        return fitPixels(pixels, width: sourceWidth, height: 5, rotation: rotation, fit: fit)
    }

    static func imageFrame(_ image: UIImage, rotation: Int, fit: ImageFit) throws -> RGBFrame {
        guard let sourceCG = image.cgImage else { throw SpotLEDProtocolError.invalidFrame }
        let sourceSize = CGSize(width: sourceCG.width, height: sourceCG.height)
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let normalized = UIGraphicsImageRenderer(size: sourceSize, format: format).image { context in
            UIColor.black.setFill()
            context.fill(CGRect(origin: .zero, size: sourceSize))
            image.draw(in: CGRect(origin: .zero, size: sourceSize))
        }
        guard let cg = normalized.cgImage else { throw SpotLEDProtocolError.invalidFrame }
        let sourceBytes = rgbBytes(from: cg)
        return fitPixels(sourceBytes, width: cg.width, height: cg.height,
                         rotation: rotation, fit: fit)
    }

    static func previewImage(_ frame: RGBFrame) -> UIImage? {
        guard frame.bytes.count == frame.width * frame.height * 3 else { return nil }
        var rgba = [UInt8](repeating: 255, count: frame.width * frame.height * 4)
        for pixel in 0..<(frame.width * frame.height) {
            rgba[pixel * 4] = frame.bytes[pixel * 3]
            rgba[pixel * 4 + 1] = frame.bytes[pixel * 3 + 1]
            rgba[pixel * 4 + 2] = frame.bytes[pixel * 3 + 2]
        }
        guard let provider = CGDataProvider(data: Data(rgba) as CFData),
              let cg = CGImage(width: frame.width, height: frame.height,
                               bitsPerComponent: 8, bitsPerPixel: 32,
                               bytesPerRow: frame.width * 4,
                               space: CGColorSpaceCreateDeviceRGB(),
                               bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.last.rawValue),
                               provider: provider, decode: nil, shouldInterpolate: false,
                               intent: .defaultIntent) else { return nil }
        return UIImage(cgImage: cg)
    }

    private static func normalizedASCII(_ text: String, uppercased: Bool = true) -> [UInt8] {
        let normalized = uppercased ? text.uppercased() : text
        return Array(normalized.unicodeScalars.map { scalar in
            (32...126).contains(scalar.value) ? UInt8(scalar.value) : UInt8(ascii: "?")
        })
    }

    private static func rgbBytes(from image: CGImage) -> [UInt8] {
        let width = image.width
        let height = image.height
        var rgba = [UInt8](repeating: 0, count: width * height * 4)
        rgba.withUnsafeMutableBytes { buffer in
            guard let context = CGContext(data: buffer.baseAddress,
                                          width: width, height: height,
                                          bitsPerComponent: 8, bytesPerRow: width * 4,
                                          space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue
                                            | CGBitmapInfo.byteOrder32Big.rawValue) else { return }
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
        }
        var rgb = [UInt8](repeating: 0, count: width * height * 3)
        for pixel in 0..<(width * height) {
            rgb[pixel * 3] = rgba[pixel * 4]
            rgb[pixel * 3 + 1] = rgba[pixel * 4 + 1]
            rgb[pixel * 3 + 2] = rgba[pixel * 4 + 2]
        }
        return rgb
    }

    private static func fitPixels(_ source: [UInt8], width: Int, height: Int,
                                  rotation: Int, fit: ImageFit) -> RGBFrame {
        let rotated = rotate(source, width: width, height: height, degrees: rotation)
        let targetWidth = RGBFrame.screenWidth
        let targetHeight = RGBFrame.screenHeight
        let scale = fit == .stretch ? 0 : (fit == .contain
            ? min(Double(targetWidth) / Double(rotated.width), Double(targetHeight) / Double(rotated.height))
            : max(Double(targetWidth) / Double(rotated.width), Double(targetHeight) / Double(rotated.height)))
        var scaledWidth = fit == .stretch ? targetWidth : max(1, Int((Double(rotated.width) * scale).rounded()))
        var scaledHeight = fit == .stretch ? targetHeight : max(1, Int((Double(rotated.height) * scale).rounded()))
        if fit == .contain {
            scaledWidth = min(targetWidth, scaledWidth)
            scaledHeight = min(targetHeight, scaledHeight)
        } else if fit == .cover {
            scaledWidth = max(targetWidth, scaledWidth)
            scaledHeight = max(targetHeight, scaledHeight)
        }
        let left = (targetWidth - scaledWidth) / 2
        let top = (targetHeight - scaledHeight) / 2
        var output = [UInt8](repeating: 0, count: targetWidth * targetHeight * 3)
        for y in 0..<targetHeight {
            let scaledY = y - top
            guard scaledY >= 0, scaledY < scaledHeight else { continue }
            let sourceY = min(rotated.height - 1,
                              Int((Double(scaledY) + 0.5) * Double(rotated.height) / Double(scaledHeight)))
            for x in 0..<targetWidth {
                let scaledX = x - left
                guard scaledX >= 0, scaledX < scaledWidth else { continue }
                let sourceX = min(rotated.width - 1,
                                  Int((Double(scaledX) + 0.5) * Double(rotated.width) / Double(scaledWidth)))
                let from = (sourceY * rotated.width + sourceX) * 3
                let to = (y * targetWidth + x) * 3
                output[to] = rotated.bytes[from]
                output[to + 1] = rotated.bytes[from + 1]
                output[to + 2] = rotated.bytes[from + 2]
            }
        }
        return RGBFrame(width: targetWidth, height: targetHeight, bytes: output)
    }

    private static func rotate(_ pixels: [UInt8], width: Int, height: Int,
                               degrees: Int) -> (width: Int, height: Int, bytes: [UInt8]) {
        let normalized = ((degrees % 360) + 360) % 360
        guard normalized != 0 else { return (width, height, pixels) }
        let outWidth = normalized == 180 ? width : height
        let outHeight = normalized == 180 ? height : width
        var output = [UInt8](repeating: 0, count: outWidth * outHeight * 3)
        for y in 0..<height {
            for x in 0..<width {
                let dx: Int
                let dy: Int
                switch normalized {
                case 90: (dx, dy) = (height - 1 - y, x)
                case 180: (dx, dy) = (width - 1 - x, height - 1 - y)
                case 270: (dx, dy) = (y, width - 1 - x)
                default: (dx, dy) = (x, y)
                }
                let sourceOffset = (y * width + x) * 3
                let targetOffset = (dy * outWidth + dx) * 3
                output[targetOffset..<(targetOffset + 3)] = pixels[sourceOffset..<(sourceOffset + 3)]
            }
        }
        return (outWidth, outHeight, output)
    }
}
