import PhotosUI
import SwiftUI
import UIKit

enum SpotLEDEffect: String, CaseIterable, Identifiable {
    case slide = "Slide"
    case scrollUp = "Scroll up"
    case scrollDown = "Scroll down"
    case scrollLeft = "Scroll left"
    case scrollRight = "Scroll right"
    case snow = "Snow"
    case scroll = "Scroll"
    case laser = "Laser"

    var id: UInt8 {
        switch self {
        case .slide: return 0
        case .scrollUp: return 1
        case .scrollDown: return 2
        case .scrollLeft: return 3
        case .scrollRight: return 4
        case .snow: return 5
        case .scroll: return 6
        case .laser: return 7
        }
    }
}

enum SpotLEDTextRendering: String, CaseIterable, Identifiable {
    case normal = "Normal"
    case raster = "Raster"
    var id: String { rawValue }
}

struct ContentView: View {
    @StateObject private var ble = SpotLedBLEManager()
    @State private var text = "HELLO"
    @State private var color = SpotLEDTextColor.white
    @State private var textRendering = SpotLEDTextRendering.normal
    @State private var effect = SpotLEDEffect.scrollLeft
    @State private var rotation = 0
    @State private var fit = ImageFit.contain
    @State private var photoItem: PhotosPickerItem?
    @State private var selectedImage: UIImage?
    @State private var imagePreview: UIImage?
    @State private var imageLoadError: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("Display") {
                    Button(ble.isScanning ? "Scanning…" : "Find SpotLED displays") {
                        ble.scan()
                    }
                    .disabled(ble.isScanning)

                    if ble.devices.isEmpty {
                        Text("Displays named SpotLED_* appear here.")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                    ForEach(ble.devices) { device in
                        Button {
                            ble.select(device)
                        } label: {
                            HStack {
                                Text(device.name)
                                Spacer()
                                if ble.selectedDeviceID == device.id {
                                    Image(systemName: "checkmark.circle.fill")
                                        .foregroundStyle(.tint)
                                }
                            }
                        }
                        .buttonStyle(.plain)
                    }
                    Text(ble.status)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section("Text") {
                    TextField("Enter a message", text: $text)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()
                    Picker("Color", selection: $color) {
                        ForEach(SpotLEDTextColor.allCases) { option in
                            Text(option.rawValue).tag(option)
                        }
                    }
                    Picker("Text rendering", selection: $textRendering) {
                        ForEach(SpotLEDTextRendering.allCases) { mode in
                            Text(mode.rawValue).tag(mode)
                        }
                    }
                    if textRendering == .normal && rotation != 0 {
                        Text("Rotated text is sent as raster pixels.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    Picker("Effect", selection: $effect) {
                        ForEach(SpotLEDEffect.allCases) { option in
                            Text(option.rawValue).tag(option)
                        }
                    }
                    if let textPreview { previewSection(textPreview) }
                    Button("Preview and send text") {
                        let message = text
                        let selectedColor = color
                        let useRaster = textRendering == .raster
                        let selectedEffect = effect.id
                        let selectedRotation = rotation
                        let selectedFit = fit
                        Task {
                            await ble.sendText(message, color: selectedColor,
                                               effectMode: selectedEffect,
                                               rotation: selectedRotation, fit: selectedFit,
                                               rasterRequested: useRaster)
                        }
                    }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                              || ble.selectedDeviceID == nil || ble.isSending)
                }

                Section("Image") {
                    PhotosPicker(selection: $photoItem, matching: .images) {
                        Label(selectedImage == nil ? "Choose image" : "Choose another image",
                              systemImage: "photo")
                    }
                    Picker("Rotation", selection: $rotation) {
                        ForEach([0, 90, 180, 270], id: \.self) { angle in
                            Text("\(angle)°").tag(angle)
                        }
                    }
                    Picker("Fit", selection: $fit) {
                        ForEach(ImageFit.allCases) { mode in Text(mode.rawValue).tag(mode) }
                    }
                    if let imagePreview { previewSection(imagePreview) }
                    if let imageLoadError {
                        Text(imageLoadError).font(.footnote).foregroundStyle(.red)
                    }
                    Button("Preview and send image") {
                        guard let image = selectedImage else { return }
                        let selectedRotation = rotation
                        let selectedFit = fit
                        Task { await ble.sendImage(image, rotation: selectedRotation, fit: selectedFit) }
                    }
                    .disabled(selectedImage == nil || ble.selectedDeviceID == nil || ble.isSending)
                }
            }
            .navigationTitle("SPOTLED Companion")
            .onChange(of: rotation) { _, _ in refreshImagePreview() }
            .onChange(of: fit) { _, _ in refreshImagePreview() }
            .onChange(of: photoItem) { _, item in loadPhoto(item) }
            .onAppear { refreshImagePreview() }
        }
    }

    private var textPreview: UIImage? {
        let frame: RGBFrame
        if textRendering == .normal && rotation == 0 {
            frame = RGBRasterizer.nativeTextFrame(text, color: color)
        } else {
            frame = RGBRasterizer.textFrame(text, color: color,
                                            rotation: rotation, fit: fit)
        }
        return RGBRasterizer.previewImage(frame)
    }

    private func previewSection(_ preview: UIImage) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("64×16 preview").font(.caption).foregroundStyle(.secondary)
            Image(uiImage: preview)
                .resizable()
                .interpolation(.none)
                .aspectRatio(4, contentMode: .fit)
                .background(.black)
                .clipShape(RoundedRectangle(cornerRadius: 6))
        }
        .padding(.vertical, 4)
    }

    private func refreshImagePreview() {
        guard let selectedImage,
              let frame = try? RGBRasterizer.imageFrame(selectedImage, rotation: rotation, fit: fit) else {
            imagePreview = nil
            return
        }
        imagePreview = RGBRasterizer.previewImage(frame)
    }

    private func loadPhoto(_ item: PhotosPickerItem?) {
        guard let item else { return }
        imageLoadError = nil
        Task {
            do {
                guard let data = try await item.loadTransferable(type: Data.self),
                      let image = UIImage(data: data) else {
                    imageLoadError = "Could not read that image. Choose a different file."
                    return
                }
                selectedImage = image
                refreshImagePreview()
            } catch {
                imageLoadError = error.localizedDescription
            }
        }
    }
}
