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

private enum CompanionSheet: String, Identifiable {
    case settings
    case about
    var id: String { rawValue }
}

private enum PendingTransfer {
    case text(String, SpotLEDTextColor, SpotLEDEffect, Int, ImageFit, Bool, UInt16)
    case image([[UInt8]], UInt16)
}

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var ble = SpotLedBLEManager()
    @AppStorage("spotled.text") private var text = "HELLO"
    @AppStorage("spotled.textColor") private var colorName = SpotLEDTextColor.white.rawValue
    @AppStorage("spotled.textRendering") private var renderingName = SpotLEDTextRendering.normal.rawValue
    @AppStorage("spotled.textEffect") private var effectName = SpotLEDEffect.scrollLeft.rawValue
    @AppStorage("spotled.rotation") private var rotation = 0
    @AppStorage("spotled.fit") private var fitName = ImageFit.contain.rawValue
    @AppStorage("spotled.textSpeedMs") private var textSpeedMs = 200
    @AppStorage("spotled.appearance") private var appearanceName = "system"
    @State private var photoItem: PhotosPickerItem?
    @State private var selectedPhotoData: Data?
    @State private var photoProcessingID = UUID()
    @State private var imageFrames: [[UInt8]] = []
    @State private var imagePreviews: [UIImage] = []
    @State private var imageIntervalMs: UInt16 = 500
    @State private var imageLoadError: String?
    @State private var activeSheet: CompanionSheet?
    @State private var pendingTransfer: PendingTransfer?
    @State private var askToFindDisplay = false

    private var color: SpotLEDTextColor { SpotLEDTextColor(rawValue: colorName) ?? .white }
    private var textRendering: SpotLEDTextRendering {
        SpotLEDTextRendering(rawValue: renderingName) ?? .normal
    }
    private var effect: SpotLEDEffect { SpotLEDEffect(rawValue: effectName) ?? .scrollLeft }
    private var fit: ImageFit { ImageFit(rawValue: fitName) ?? .contain }
    private var preferredColorScheme: ColorScheme? {
        switch appearanceName {
        case "light": return .light
        case "dark": return .dark
        default: return nil
        }
    }

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
                    if let selectedDeviceName = ble.selectedDeviceName {
                        LabeledContent("Last selected", value: selectedDeviceName)
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
                    if ble.isSending {
                        Button("Cancel transfer", systemImage: "xmark.circle", role: .destructive) {
                            ble.cancelTransfer()
                        }
                    }
                }

                Section("Text") {
                    TextField("Enter a message", text: $text)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Picker("Color", selection: $colorName) {
                        ForEach(SpotLEDTextColor.allCases) { option in
                            Text(option.rawValue).tag(option.rawValue)
                        }
                    }
                    Picker("Text rendering", selection: $renderingName) {
                        ForEach(SpotLEDTextRendering.allCases) { mode in
                            Text(mode.rawValue).tag(mode.rawValue)
                        }
                    }
                    if textRendering == .normal && rotation != 0 {
                        Text("Rotated text is sent as raster pixels.")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    Picker("Effect", selection: $effectName) {
                        ForEach(SpotLEDEffect.allCases) { option in
                            Text(option.rawValue).tag(option.rawValue)
                        }
                    }
                    if let textPreview { previewSection(textPreview) }
                    Button("Preview and send text") {
                        requestTextSend()
                    }
                    .disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                              || ble.isSending)
                }

                Section("Image") {
                    PhotosPicker(selection: $photoItem, matching: .images) {
                        Label(imageFrames.isEmpty ? "Choose image or GIF" : "Choose another image or GIF",
                              systemImage: "photo")
                    }
                    Picker("Rotation", selection: $rotation) {
                        ForEach([0, 90, 180, 270], id: \.self) { angle in
                            Text("\(angle)°").tag(angle)
                        }
                    }
                    Picker("Fit", selection: $fitName) {
                        ForEach(ImageFit.allCases) { mode in Text(mode.rawValue).tag(mode.rawValue) }
                    }
                    if imagePreviews.count > 1 {
                        TimelineView(.periodic(from: .now, by: max(0.25, Double(imageIntervalMs) / 1000))) { timeline in
                            let seconds = max(0.25, Double(imageIntervalMs) / 1000)
                            let frameIndex = Int(timeline.date.timeIntervalSinceReferenceDate / seconds)
                                % imagePreviews.count
                            previewSection(imagePreviews[frameIndex])
                        }
                    } else if let imagePreview = imagePreviews.first {
                        previewSection(imagePreview)
                    }
                    if let imageLoadError {
                        Text(imageLoadError).font(.footnote).foregroundStyle(.red)
                    }
                    if imageFrames.count > 1 {
                        Text("GIF preview and transfer use two sampled frames at a safe interval (\(imageIntervalMs) ms/frame).")
                            .font(.footnote).foregroundStyle(.secondary)
                    }
                    Button("Preview and send image") {
                        guard !imageFrames.isEmpty else { return }
                        requestTransfer(.image(imageFrames, imageIntervalMs))
                    }
                    .disabled(imageFrames.isEmpty || ble.isSending)
                }
            }
            .navigationTitle("SPOTLED Companion")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Settings", systemImage: "gearshape") { activeSheet = .settings }
                        Button("About", systemImage: "info.circle") { activeSheet = .about }
                    } label: {
                        Image(systemName: "gearshape")
                            .accessibilityLabel("Settings and About")
                    }
                }
            }
            .preferredColorScheme(preferredColorScheme)
            .sheet(item: $activeSheet) { destination in
                switch destination {
                case .settings:
                    CompanionSettingsView(appearanceName: $appearanceName,
                                          textSpeedMs: $textSpeedMs)
                case .about:
                    CompanionAboutView()
                }
            }
            .alert("No display selected", isPresented: $askToFindDisplay) {
                Button("Find and select", systemImage: "dot.radiowaves.left.and.right") {
                    ble.scan()
                }
                Button("Cancel", role: .cancel) { pendingTransfer = nil }
            } message: {
                Text("Choose a nearby SpotLED display. Your content will send after you select it.")
            }
            .onChange(of: ble.targetRevision) { _, _ in sendPendingIfReady() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .background { ble.cancelForBackground() }
            }
            .onChange(of: ble.isScanning) { wasScanning, isScanning in
                if wasScanning && !isScanning && !ble.hasUsableSelection {
                    pendingTransfer = nil
                }
            }
            .onChange(of: rotation) { _, _ in reprocessSelectedPhoto() }
            .onChange(of: fitName) { _, _ in reprocessSelectedPhoto() }
            .onChange(of: photoItem) { _, item in loadPhoto(item) }
            .onAppear { reprocessSelectedPhoto() }
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
        guard let data = selectedPhotoData else { return }
        let generation = UUID()
        photoProcessingID = generation
        imageFrames = []
        imagePreviews = []
        let selectedRotation = rotation
        let selectedFit = fit
        Task {
            do {
                let result = try await Task.detached(priority: .userInitiated) {
                    try RGBRasterizer.imageFrames(data, rotation: selectedRotation, fit: selectedFit)
                }.value
                guard photoProcessingID == generation else { return }
                imageFrames = result.frames.map(\.bytes)
                imageIntervalMs = result.intervalMs
                imagePreviews = result.frames.compactMap(RGBRasterizer.previewImage)
                imageLoadError = imagePreviews.isEmpty
                    ? "Could not prepare the image preview. Choose a different file."
                    : nil
            } catch {
                guard photoProcessingID == generation else { return }
                imageFrames = []
                imagePreviews = []
                imageLoadError = "Could not decode selected image: \(error.localizedDescription)"
            }
        }
    }

    private func reprocessSelectedPhoto() {
        guard selectedPhotoData != nil else { return }
        refreshImagePreview()
    }

    private func requestTextSend() {
        requestTransfer(.text(text, color, effect, rotation, fit,
                              textRendering == .raster, UInt16(textSpeedMs)))
    }

    private func requestTransfer(_ request: PendingTransfer) {
        guard !ble.isSending else { return }
        if ble.hasUsableSelection {
            perform(request)
        } else {
            pendingTransfer = request
            askToFindDisplay = true
        }
    }

    private func sendPendingIfReady() {
        guard ble.hasUsableSelection, let pendingTransfer else { return }
        self.pendingTransfer = nil
        perform(pendingTransfer)
    }

    private func perform(_ request: PendingTransfer) {
        switch request {
        case let .text(message, selectedColor, selectedEffect, selectedRotation,
                       selectedFit, rasterRequested, intervalMs):
            Task {
                await ble.sendText(message, color: selectedColor,
                                   effectMode: selectedEffect.id, rotation: selectedRotation,
                                   fit: selectedFit, rasterRequested: rasterRequested,
                                   intervalMs: intervalMs)
            }
        case let .image(frames, intervalMs):
            Task { await ble.sendImageFrames(frames, intervalMs: intervalMs) }
        }
    }

    private func loadPhoto(_ item: PhotosPickerItem?) {
        guard let item else { return }
        imageLoadError = nil
        selectedPhotoData = nil
        imageFrames = []
        imagePreviews = []
        let generation = UUID()
        photoProcessingID = generation
        Task {
            do {
                guard let data = try await item.loadTransferable(type: Data.self) else {
                    guard photoProcessingID == generation else { return }
                    imageLoadError = "Could not read that image. Choose a different file."
                    return
                }
                guard photoProcessingID == generation else { return }
                selectedPhotoData = data
                refreshImagePreview()
            } catch {
                guard photoProcessingID == generation else { return }
                imageLoadError = error.localizedDescription
            }
        }
    }
}

private struct CompanionSettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @Binding var appearanceName: String
    @Binding var textSpeedMs: Int
    private let speeds = [300, 250, 200, 150, 100]

    var body: some View {
        NavigationStack {
            Form {
                Picker("Appearance", selection: $appearanceName) {
                    Text("System").tag("system")
                    Text("Light").tag("light")
                    Text("Dark").tag("dark")
                }
                Picker("Text speed", selection: $textSpeedMs) {
                    ForEach(speeds, id: \.self) { speed in
                        Text(speedLabel(speed)).tag(speed)
                    }
                }
            }
            .navigationTitle("Settings")
            .toolbar { ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
            } }
        }
    }

    private func speedLabel(_ milliseconds: Int) -> String {
        switch milliseconds {
        case 300: return "Slow (300 ms)"
        case 250: return "250 ms"
        case 200: return "Normal (200 ms)"
        case 150: return "150 ms"
        default: return "Fast (100 ms)"
        }
    }
}

private struct CompanionAboutView: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("SpotLED Companion") {
                    Text("SpotLED Suite is a Bug-Mag.net project by DuvTheDove, with contributions from clavo and OpenAI Codex.")
                }
                Section("Artwork") {
                    Text("Weather icons by Hank the Tank (Wetter.ax), used with permission.")
                }
                Section("iOS scope") {
                    Text("This app sends content while in the foreground. Clock/weather/MQTT and background notification forwarding are Android-only.")
                }
            }
            .navigationTitle("About")
            .toolbar { ToolbarItem(placement: .confirmationAction) {
                Button("Done") { dismiss() }
            } }
        }
    }
}
