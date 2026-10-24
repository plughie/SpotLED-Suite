import Combine
import CoreBluetooth
import Foundation
import UIKit

struct DiscoveredSpotLED: Identifiable, Equatable {
    let id: UUID
    let name: String
}

final class SpotLedBLEManager: NSObject, ObservableObject {
    @Published private(set) var devices: [DiscoveredSpotLED] = []
    @Published private(set) var selectedDeviceID: UUID?
    @Published private(set) var status = "Find a nearby SpotLED display to begin."
    @Published private(set) var isScanning = false
    @Published private(set) var isSending = false
    @Published private(set) var targetRevision = 0
    @Published private(set) var selectedDeviceName: String?

    private static let commandUUID = CBUUID(string: "0000FF21-0000-1000-8000-00805F9B34FB")
    private static let dataUUID = CBUUID(string: "0000FF22-0000-1000-8000-00805F9B34FB")
    private static let devicePrefix = "SpotLED_"
    private let maximumChunkSize = 182

    private var central: CBCentralManager!
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var quarantinedPeripheralIDs: Set<UUID> = []
    private var activePeripheral: CBPeripheral?
    private var commandCharacteristic: CBCharacteristic?
    private var dataCharacteristic: CBCharacteristic?
    private var notificationsRequested = false
    private var isReady = false
    private var nextSerial: UInt16 = 1
    private var capabilityResponse: [UInt8]?
    private var activeReceiveLimit = SpotLEDProtocol.maxReceiveBytes
    private var scanTimeout: DispatchWorkItem?
    private let transferSession = BLETransferSession()
    private var disconnectContinuation: CheckedContinuation<Void, Never>?
    private var disconnectToken: UUID?
    private var disconnectSessionID: UUID?
    private var finishingAcknowledgedTransfer = false

    private var activeTransferID: UUID? { transferSession.activeSessionID }

    override init() {
        super.init()
        if let id = UserDefaults.standard.string(forKey: "spotled.selectedPeripheralID") {
            selectedDeviceID = UUID(uuidString: id)
        }
        selectedDeviceName = UserDefaults.standard.string(forKey: "spotled.selectedPeripheralName")
        central = CBCentralManager(delegate: self, queue: .main)
    }

    var hasUsableSelection: Bool {
        guard let selectedDeviceID else { return false }
        return peripherals[selectedDeviceID] != nil
    }

    func scan() {
        guard !isSending else {
            status = "Wait for the current transfer before scanning again."
            return
        }
        guard central.state == .poweredOn else {
            status = "Bluetooth is not ready. Turn it on and try again."
            return
        }
        stopScan()
        devices = []
        peripherals = [:]
        isScanning = true
        status = selectedDeviceID == nil
            ? "Scanning for SpotLED_* displays…"
            : "Scanning for your saved SpotLED display…"
        central.scanForPeripherals(withServices: nil,
                                  options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        let timeout = DispatchWorkItem { [weak self] in self?.stopScan() }
        scanTimeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: timeout)
    }

    func cancelTransfer() {
        cancelTransfer(with: SpotLEDProtocolError.cancelled)
    }

    private func cancelTransfer(with error: Error) {
        stopScan()
        guard isSending else { return }
        guard !finishingAcknowledgedTransfer else {
            status = "Transfer acknowledged; disconnecting…"
            return
        }
        transferSession.cancelActive(with: error)
        status = "Cancelling the display transfer…"
        if let peripheral = activePeripheral,
           central.state == .poweredOn,
           peripheral.state == .connected || peripheral.state == .connecting {
            central.cancelPeripheralConnection(peripheral)
        }
    }

    func cancelForBackground() {
        cancelTransfer()
    }

    func select(_ device: DiscoveredSpotLED) {
        guard peripherals[device.id] != nil else { return }
        selectedDeviceID = device.id
        selectedDeviceName = device.name
        UserDefaults.standard.set(device.id.uuidString, forKey: "spotled.selectedPeripheralID")
        UserDefaults.standard.set(device.name, forKey: "spotled.selectedPeripheralName")
        targetRevision &+= 1
        status = "Selected \(device.name). The app connects only while sending."
    }

    func sendText(_ text: String, color: SpotLEDTextColor,
                  effectMode: UInt8, rotation: Int, fit: ImageFit,
                  rasterRequested: Bool, intervalMs: UInt16) async {
        do {
            if rotation != 0 || (!rasterRequested && rotation == 0) {
                let payloads = try SpotLEDProtocol.buildNativeTextPayloads(
                    text, color: color, effectMode: effectMode, intervalMs: intervalMs,
                    glyphRotation: rotation)
                try await sendUsingSelectedDisplay { sessionID in
                    let capabilities = try await self.queryCapabilities(sessionID: sessionID)
                    try SpotLEDProtocol.validateNativeText(payloads,
                                                           receiveBytes: capabilities.receiveBytes)
                    guard let peripheral = self.activePeripheral,
                          let command = self.commandCharacteristic else {
                        throw SpotLEDProtocolError.missingCharacteristic
                    }
                    for setup in SpotLEDProtocol.buildNativeTextSetupCommands() {
                        try self.checkCancellation(sessionID)
                        peripheral.writeValue(Data(setup), for: command, type: .withoutResponse)
                        try await Task.sleep(nanoseconds: 200_000_000)
                        try self.checkCancellation(sessionID)
                    }
                    for payload in payloads { try await self.transfer(payload, sessionID: sessionID) }
                }
            } else {
                let orientation = TextPanelOrientation(rotationDegrees: rotation)
                let frame = RGBRasterizer.textFrames(text, color: color,
                                                     orientation: orientation).wire
                let payload = try SpotLEDProtocol.buildRGBFramePayload(
                    frame.bytes, intervalMs: intervalMs, effectMode: effectMode)
                try await sendUsingSelectedDisplay { sessionID in
                    let capabilities = try await self.queryCapabilities(sessionID: sessionID)
                    try SpotLEDProtocol.validateRGB(capabilities, frameCount: 1,
                                                    payloadBytes: payload.count)
                    try await self.transfer(payload, sessionID: sessionID)
                }
            }
        } catch {
            status = error.localizedDescription
        }
    }

    func sendImage(_ image: UIImage, rotation: Int, fit: ImageFit) async {
        do {
            let frame = try RGBRasterizer.imageFrame(image, rotation: rotation, fit: fit)
            let payload = try SpotLEDProtocol.buildRGBFramePayload(frame.bytes)
            try await sendUsingSelectedDisplay { sessionID in
                let capabilities = try await self.queryCapabilities(sessionID: sessionID)
                try SpotLEDProtocol.validateRGB(capabilities, frameCount: 1,
                                                payloadBytes: payload.count)
                try await self.transfer(payload, sessionID: sessionID)
            }
        } catch {
            status = error.localizedDescription
        }
    }

    func sendImageFrames(_ frames: [[UInt8]], intervalMs: UInt16,
                         effectMode: UInt8 = 0) async {
        do {
            let payload = try SpotLEDProtocol.buildRGBAnimationPayload(
                frames, intervalMs: intervalMs, effectMode: effectMode)
            try await sendUsingSelectedDisplay { sessionID in
                let capabilities = try await self.queryCapabilities(sessionID: sessionID)
                try SpotLEDProtocol.validateRGB(capabilities, frameCount: frames.count,
                                                payloadBytes: payload.count)
                try await self.transfer(payload, sessionID: sessionID)
            }
        } catch {
            status = error.localizedDescription
        }
    }

    func sendVerticalScrollFrames(_ frames: [[UInt8]], intervalMs: UInt16 = 250) async {
        guard frames.count > 1 else {
            status = "The selected image is not taller than the display."
            return
        }
        do {
            let preflight = try SpotLEDProtocol.buildRGBFramePayload(
                frames[0], intervalMs: intervalMs, effectMode: 1)
            try await sendUsingSelectedDisplay { sessionID in
                let capabilities = try await self.queryCapabilities(sessionID: sessionID)
                try SpotLEDProtocol.validateRGB(capabilities, frameCount: 1,
                                                payloadBytes: preflight.count)
                self.status = "Vertical image scroll running; use Cancel transfer to stop."
                while true {
                    for frame in frames {
                        try self.checkCancellation(sessionID)
                        let payload = try SpotLEDProtocol.buildRGBFramePayload(
                            frame, intervalMs: intervalMs, effectMode: 1)
                        try await self.transfer(payload, sessionID: sessionID)
                        do {
                            try await Task.sleep(nanoseconds: UInt64(intervalMs) * 1_000_000)
                        } catch {
                            try self.checkCancellation(sessionID)
                            throw error
                        }
                    }
                }
            }
        } catch {
            status = error.localizedDescription
        }
    }

    private func sendUsingSelectedDisplay(_ operation: (UUID) async throws -> Void) async throws {
        guard !isSending else { throw SpotLEDProtocolError.transport("A transfer is already running.") }
        guard let selectedDeviceID, let peripheral = peripherals[selectedDeviceID] else {
            throw SpotLEDProtocolError.deviceNotSelected
        }
        if quarantinedPeripheralIDs.contains(peripheral.identifier) {
            guard peripheral.state == .disconnected else {
                throw SpotLEDProtocolError.transport("The previous Bluetooth session has not fully disconnected. Wait and scan again.")
            }
            quarantinedPeripheralIDs.remove(peripheral.identifier)
        }
        isSending = true
        let sessionID = transferSession.begin()
        finishingAcknowledgedTransfer = false
        defer {
            transferSession.finish(sessionID)
            finishingAcknowledgedTransfer = false
            isSending = false
        }
        stopScan()
        status = "Connecting to the selected display…"
        do {
            do {
                try checkCancellation(sessionID)
                try await connectAndWaitUntilReady(peripheral, sessionID: sessionID)
            } catch {
                let firstConnectionError = error
                await disconnectActive(sessionID: sessionID)
                try checkCancellation(sessionID)
                guard !quarantinedPeripheralIDs.contains(peripheral.identifier) else {
                    throw SpotLEDProtocolError.transport(
                        "The failed Bluetooth session has not disconnected; reconnect was stopped safely."
                    )
                }
                guard central.state == .poweredOn else { throw firstConnectionError }
                status = "Connection failed; retrying once…"
                try await connectAndWaitUntilReady(peripheral, sessionID: sessionID)
            }
            try checkCancellation(sessionID)
            try await operation(sessionID)
            try checkCancellation(sessionID)
            finishingAcknowledgedTransfer = true
            await disconnectActive(sessionID: sessionID)
            status = "Transfer acknowledged. Check the display."
        } catch {
            await disconnectActive(sessionID: sessionID)
            status = error.localizedDescription == SpotLEDProtocolError.cancelled.errorDescription
                ? "Transfer cancelled."
                : "Send failed: \(error.localizedDescription)"
            throw error
        }
    }

    private func checkCancellation(_ sessionID: UUID) throws {
        try transferSession.check(sessionID)
    }

    private func connectAndWaitUntilReady(_ peripheral: CBPeripheral, sessionID: UUID) async throws {
        try checkCancellation(sessionID)
        guard central.state == .poweredOn else { throw SpotLEDProtocolError.bluetoothUnavailable }
        activePeripheral = peripheral
        commandCharacteristic = nil
        dataCharacteristic = nil
        notificationsRequested = false
        isReady = false
        peripheral.delegate = self
        try await transferSession.wait(for: .ready, sessionID: sessionID,
                                       timeout: 20,
                                       timeoutError: SpotLEDProtocolError.timeout("BLE connection")) {
            central.connect(peripheral, options: nil)
        }
    }

    private func transfer(_ payload: [UInt8], sessionID: UUID) async throws {
        try checkCancellation(sessionID)
        guard payload.count <= activeReceiveLimit else {
            throw SpotLEDProtocolError.transferTooLarge
        }
        let serial = nextSerial
        nextSerial &+= 1
        let start = SpotLEDProtocol.buildStart(serial: serial, totalLength: payload.count)
        try await sendCommandAndWait(start, expectedType: 2, serial: serial, timeout: 8,
                                     sessionID: sessionID)
        try checkCancellation(sessionID)
        try await writeChunks(payload, sessionID: sessionID)
        try checkCancellation(sessionID)
        try await Task.sleep(nanoseconds: 80_000_000)
        try checkCancellation(sessionID)
        let end = SpotLEDProtocol.buildStart(serial: serial, totalLength: payload.count, isEnd: true)
        try await sendCommandAndWait(end, expectedType: 4, serial: serial, timeout: 12,
                                     sessionID: sessionID)
        try checkCancellation(sessionID)
    }

    private func queryCapabilities(sessionID: UUID) async throws -> SpotLEDDeviceCapabilities {
        let screenSerial = nextQuerySerial()
        let screen = try await queryResponse(id: 0x12, responseID: 0x13,
                                             serial: screenSerial, sessionID: sessionID)
        let animationSerial = nextQuerySerial()
        let animation = try await queryResponse(id: 0x1a, responseID: 0x1b,
                                                serial: animationSerial, sessionID: sessionID)
        let capacitySerial = nextQuerySerial()
        let capacity = try await queryResponse(id: 0x14, responseID: 0x15,
                                               serial: capacitySerial, sessionID: sessionID)
        let capabilities = try SpotLEDProtocol.parseCapabilities(
            screen: screen, animation: animation, capacity: capacity,
            screenSerial: screenSerial, animationSerial: animationSerial,
            capacitySerial: capacitySerial)
        activeReceiveLimit = capabilities.receiveBytes
        return capabilities
    }

    private func nextQuerySerial() -> UInt16 {
        let value = nextSerial
        nextSerial &+= 1
        return value
    }

    private func queryResponse(id: UInt8, responseID: UInt8, serial: UInt16,
                               sessionID: UUID) async throws -> [UInt8] {
        guard activeTransferID == sessionID,
              let peripheral = activePeripheral, let command = commandCharacteristic else {
            throw SpotLEDProtocolError.missingCharacteristic
        }
        capabilityResponse = nil
        try await transferSession.wait(for: .response(type: responseID, serial: serial),
                                       sessionID: sessionID, timeout: 6,
                                       timeoutError: SpotLEDProtocolError.timeout("capability query")) {
            peripheral.writeValue(Data(SpotLEDProtocol.buildQuery(id: id, serial: serial)),
                                  for: command, type: .withoutResponse)
        }
        guard let response = capabilityResponse else {
            throw SpotLEDProtocolError.transport("The display capability response was lost.")
        }
        return response
    }

    private func sendCommandAndWait(_ bytes: [UInt8], expectedType: UInt8,
                                    serial: UInt16, timeout: TimeInterval,
                                    sessionID: UUID) async throws {
        try checkCancellation(sessionID)
        guard activeTransferID == sessionID,
              let peripheral = activePeripheral, let command = commandCharacteristic else {
            throw SpotLEDProtocolError.missingCharacteristic
        }
        try await transferSession.wait(
            for: .acknowledgement(type: expectedType, serial: serial),
            sessionID: sessionID, timeout: timeout,
            timeoutError: SpotLEDProtocolError.timeout("transfer")) {
            peripheral.writeValue(Data(bytes), for: command, type: .withoutResponse)
        }
    }

    private func writeChunks(_ payload: [UInt8], sessionID: UUID) async throws {
        try checkCancellation(sessionID)
        guard activeTransferID == sessionID,
              let peripheral = activePeripheral, let data = dataCharacteristic else {
            throw SpotLEDProtocolError.missingCharacteristic
        }
        let chunkSize = min(maximumChunkSize,
                            peripheral.maximumWriteValueLength(for: .withoutResponse))
        guard chunkSize > 0 else { throw SpotLEDProtocolError.transport("Bluetooth reported a zero write size.") }
        try transferSession.enqueueChunks(payload, size: chunkSize, sessionID: sessionID)
        try await transferSession.wait(for: .chunkWrites, sessionID: sessionID,
                                       timeout: 15,
                                       timeoutError: SpotLEDProtocolError.timeout("BLE data writes")) {
            self.pumpChunks(sessionID: sessionID)
        }
        _ = data // Retain the discovered data characteristic through the write loop.
    }

    private func pumpChunks(sessionID: UUID) {
        guard activeTransferID == sessionID,
              transferSession.waitingPhase == .chunkWrites else { return }
        guard let peripheral = activePeripheral, let data = dataCharacteristic else { return }
        while peripheral.canSendWriteWithoutResponse,
              let chunk = transferSession.dequeueChunk(sessionID: sessionID) {
            peripheral.writeValue(chunk, for: data, type: .withoutResponse)
        }
        if transferSession.queuedChunkCount == 0,
           transferSession.waitingPhase == .chunkWrites {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) {
                self.transferSession.resolve(.chunkWrites, sessionID: sessionID, with: .success(()))
            }
        }
    }

    private func disconnectActive(sessionID: UUID) async {
        guard activeTransferID == sessionID else { return }
        guard let peripheral = activePeripheral else { return }
        guard central.state == .poweredOn else {
            clearConnection()
            return
        }
        if peripheral.state == .connected || peripheral.state == .connecting || peripheral.state == .disconnecting {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                let token = UUID()
                disconnectToken = token
                disconnectSessionID = sessionID
                disconnectContinuation = continuation
                if peripheral.state != .disconnecting {
                    central.cancelPeripheralConnection(peripheral)
                }
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
                    if peripheral.state != .disconnected {
                        self?.quarantinedPeripheralIDs.insert(peripheral.identifier)
                    }
                    self?.finishDisconnect(token: token, sessionID: sessionID)
                }
            }
        } else {
            clearConnection()
        }
    }

    private func finishReady(_ result: Result<Void, Error>, sessionID: UUID) {
        if case .success = result { isReady = true }
        transferSession.resolve(.ready, sessionID: sessionID, with: result)
    }

    private func finishDisconnect(token: UUID? = nil, sessionID: UUID? = nil) {
        guard let continuation = disconnectContinuation else { return }
        if let token, disconnectToken != token { return }
        if let sessionID, disconnectSessionID != sessionID { return }
        disconnectContinuation = nil
        disconnectToken = nil
        disconnectSessionID = nil
        clearConnection()
        continuation.resume()
    }

    private func clearConnection() {
        activePeripheral = nil
        commandCharacteristic = nil
        dataCharacteristic = nil
        notificationsRequested = false
        isReady = false
    }

    private func stopScan() {
        scanTimeout?.cancel()
        scanTimeout = nil
        if isScanning { central.stopScan() }
        isScanning = false
    }
}

extension SpotLedBLEManager: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        if central.state != .poweredOn {
            stopScan()
            if isSending {
                let error: Error = central.state == .poweredOff
                    ? SpotLEDProtocolError.bluetoothUnavailable
                    : SpotLEDProtocolError.cancelled
                cancelTransfer(with: error)
            }
        }
        switch central.state {
        case .poweredOn: status = "Bluetooth ready. Find a nearby SpotLED display."
        case .poweredOff:
            status = "Bluetooth is off. Turn it on to find a display."
        case .unauthorized: status = "Allow Bluetooth access for SpotLED Companion in Settings."
        case .unsupported: status = "This iPhone does not support Bluetooth LE."
        default: status = "Checking Bluetooth…"
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        guard isScanning, !isSending else { return }
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
            ?? peripheral.name ?? ""
        guard name.hasPrefix(Self.devicePrefix) else { return }
        peripherals[peripheral.identifier] = peripheral
        let discovered = DiscoveredSpotLED(id: peripheral.identifier, name: name)
        if !devices.contains(where: { $0.id == discovered.id }) {
            devices.append(discovered)
            devices.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        }
        if selectedDeviceID == discovered.id {
            selectedDeviceName = discovered.name
            UserDefaults.standard.set(discovered.name, forKey: "spotled.selectedPeripheralName")
            targetRevision &+= 1
        }
        status = "Found \(devices.count) SpotLED display(s)."
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        guard activePeripheral?.identifier == peripheral.identifier else {
            central.cancelPeripheralConnection(peripheral)
            return
        }
        peripheral.discoverServices(nil)
        status = "Connected; preparing the display transfer…"
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral,
                        error: Error?) {
        guard activePeripheral?.identifier == peripheral.identifier,
              peripheral.state != .connected,
              let sessionID = activeTransferID else { return }
        finishReady(.failure(error ?? SpotLEDProtocolError.transport("Could not connect to the display.")),
                    sessionID: sessionID)
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        guard peripheral.state == .disconnected else { return }
        guard activePeripheral?.identifier == peripheral.identifier,
              let sessionID = activeTransferID else {
            quarantinedPeripheralIDs.remove(peripheral.identifier)
            return
        }
        let disconnectError = error ?? SpotLEDProtocolError.transport("Disconnected during BLE transfer.")
        transferSession.connectionLost(sessionID: sessionID, error: disconnectError)
        if disconnectContinuation != nil {
            finishDisconnect(sessionID: sessionID)
        }
        else { clearConnection() }
        if !isSending, let error { status = "Bluetooth disconnected: \(error.localizedDescription)" }
    }
}

extension SpotLedBLEManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard activePeripheral?.identifier == peripheral.identifier,
              peripheral.state == .connected,
              let sessionID = activeTransferID else { return }
        guard error == nil, let services = peripheral.services, !services.isEmpty else {
            finishReady(.failure(error ?? SpotLEDProtocolError.transport("No BLE services were found.")),
                        sessionID: sessionID)
            return
        }
        for service in services { peripheral.discoverCharacteristics(nil, for: service) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        guard activePeripheral?.identifier == peripheral.identifier,
              peripheral.state == .connected,
              let sessionID = activeTransferID else { return }
        guard error == nil else {
            finishReady(.failure(error ?? SpotLEDProtocolError.transport("Could not inspect BLE characteristics.")),
                        sessionID: sessionID)
            return
        }
        for characteristic in service.characteristics ?? [] {
            if characteristic.uuid == Self.commandUUID { commandCharacteristic = characteristic }
            if characteristic.uuid == Self.dataUUID { dataCharacteristic = characteristic }
        }
        guard commandCharacteristic != nil, dataCharacteristic != nil,
              !notificationsRequested, let commandCharacteristic else { return }
        notificationsRequested = true
        peripheral.setNotifyValue(true, for: commandCharacteristic)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateNotificationStateFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard activePeripheral?.identifier == peripheral.identifier,
              characteristic.uuid == Self.commandUUID,
              let sessionID = activeTransferID else { return }
        if let error { finishReady(.failure(error), sessionID: sessionID); return }
        guard characteristic.isNotifying else {
            finishReady(.failure(SpotLEDProtocolError.transport("Could not enable BLE acknowledgements.")),
                        sessionID: sessionID)
            return
        }
        finishReady(.success(()), sessionID: sessionID)
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard activePeripheral?.identifier == peripheral.identifier,
              characteristic.uuid == Self.commandUUID,
              let sessionID = activeTransferID,
              let phase = transferSession.waitingPhase else { return }
        if let error {
            transferSession.resolve(phase, sessionID: sessionID, with: .failure(error))
            return
        }
        guard let value = characteristic.value else { return }
        switch phase {
        case let .acknowledgement(type, serial):
            guard let status = SpotLEDProtocol.acknowledgment(value,
                                                             expectedType: type,
                                                             serial: serial) else { return }
            let result: Result<Void, Error> = status == 0
                ? .success(())
                : .failure(SpotLEDProtocolError.rejected(status))
            transferSession.resolve(phase, sessionID: sessionID, with: result)
        case let .response(type, serial):
            let bytes = [UInt8](value)
            guard bytes.count >= 5, Int(bytes[0]) == bytes.count,
                  bytes[1] == type, (UInt16(bytes[2]) << 8 | UInt16(bytes[3])) == serial else { return }
            capabilityResponse = bytes
            transferSession.resolve(phase, sessionID: sessionID, with: .success(()))
        case .ready, .chunkWrites:
            return
        }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        guard activePeripheral?.identifier == peripheral.identifier,
              let sessionID = activeTransferID,
              transferSession.waitingPhase == .chunkWrites else { return }
        pumpChunks(sessionID: sessionID)
    }
}
