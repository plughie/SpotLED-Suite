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

    private static let commandUUID = CBUUID(string: "0000FF21-0000-1000-8000-00805F9B34FB")
    private static let dataUUID = CBUUID(string: "0000FF22-0000-1000-8000-00805F9B34FB")
    private static let devicePrefix = "SpotLED_"
    private let maximumChunkSize = 182

    private var central: CBCentralManager!
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var activePeripheral: CBPeripheral?
    private var commandCharacteristic: CBCharacteristic?
    private var dataCharacteristic: CBCharacteristic?
    private var notificationsRequested = false
    private var isReady = false
    private var nextSerial: UInt16 = 1
    private var scanTimeout: DispatchWorkItem?
    private var readyContinuation: CheckedContinuation<Void, Error>?
    private var disconnectContinuation: CheckedContinuation<Void, Never>?
    private var acknowledgementContinuation:
        (type: UInt8, serial: UInt16, continuation: CheckedContinuation<Void, Error>)?
    private var pendingChunks: [Data] = []
    private var chunkContinuation: CheckedContinuation<Void, Error>?
    private var chunkToken = UUID()

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
    }

    func scan() {
        guard central.state == .poweredOn else {
            status = "Bluetooth is not ready. Turn it on and try again."
            return
        }
        stopScan()
        devices = []
        peripherals = [:]
        isScanning = true
        status = "Scanning for SpotLED_* displays…"
        central.scanForPeripherals(withServices: nil,
                                  options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        let timeout = DispatchWorkItem { [weak self] in self?.stopScan() }
        scanTimeout = timeout
        DispatchQueue.main.asyncAfter(deadline: .now() + 15, execute: timeout)
    }

    func select(_ device: DiscoveredSpotLED) {
        guard peripherals[device.id] != nil else { return }
        selectedDeviceID = device.id
        status = "Selected \(device.name). The app connects only while sending."
    }

    func sendText(_ text: String, color: SpotLEDTextColor,
                  effectMode: UInt8, rotation: Int, fit: ImageFit,
                  rasterRequested: Bool) async {
        do {
            if !rasterRequested && rotation == 0 {
                let payloads = try SpotLEDProtocol.buildNativeTextPayloads(
                    text, color: color, effectMode: effectMode)
                try await sendUsingSelectedDisplay {
                    guard let peripheral = self.activePeripheral,
                          let command = self.commandCharacteristic else {
                        throw SpotLEDProtocolError.missingCharacteristic
                    }
                    for setup in SpotLEDProtocol.buildNativeTextSetupCommands() {
                        peripheral.writeValue(Data(setup), for: command, type: .withoutResponse)
                        try await Task.sleep(nanoseconds: 200_000_000)
                    }
                    for payload in payloads { try await self.transfer(payload) }
                }
            } else {
                let frame = RGBRasterizer.textFrame(text, color: color,
                                                    rotation: rotation, fit: fit)
                let payload = try SpotLEDProtocol.buildRGBFramePayload(
                    frame.bytes, intervalMs: 200, effectMode: effectMode)
                try await sendUsingSelectedDisplay { try await self.transfer(payload) }
            }
        } catch {
            status = error.localizedDescription
        }
    }

    func sendImage(_ image: UIImage, rotation: Int, fit: ImageFit) async {
        do {
            let frame = try RGBRasterizer.imageFrame(image, rotation: rotation, fit: fit)
            let payload = try SpotLEDProtocol.buildRGBFramePayload(frame.bytes)
            try await sendUsingSelectedDisplay { try await self.transfer(payload) }
        } catch {
            status = error.localizedDescription
        }
    }

    private func sendUsingSelectedDisplay(_ operation: () async throws -> Void) async throws {
        guard !isSending else { throw SpotLEDProtocolError.transport("A transfer is already running.") }
        guard let selectedDeviceID, let peripheral = peripherals[selectedDeviceID] else {
            throw SpotLEDProtocolError.deviceNotSelected
        }
        isSending = true
        status = "Connecting to the selected display…"
        do {
            try await connectAndWaitUntilReady(peripheral)
            try await operation()
            await disconnectActive()
            isSending = false
            status = "Transfer acknowledged. Check the display."
        } catch {
            await disconnectActive()
            isSending = false
            status = "Send failed: \(error.localizedDescription)"
            throw error
        }
    }

    private func connectAndWaitUntilReady(_ peripheral: CBPeripheral) async throws {
        guard central.state == .poweredOn else { throw SpotLEDProtocolError.bluetoothUnavailable }
        activePeripheral = peripheral
        commandCharacteristic = nil
        dataCharacteristic = nil
        notificationsRequested = false
        isReady = false
        peripheral.delegate = self
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            readyContinuation = continuation
            central.connect(peripheral, options: nil)
            DispatchQueue.main.asyncAfter(deadline: .now() + 20) { [weak self, weak peripheral] in
                guard let self, let peripheral, self.activePeripheral?.identifier == peripheral.identifier,
                      self.readyContinuation != nil else { return }
                self.finishReady(.failure(SpotLEDProtocolError.timeout("BLE connection")))
            }
        }
    }

    private func transfer(_ payload: [UInt8]) async throws {
        guard payload.count <= SpotLEDProtocol.maxReceiveBytes else {
            throw SpotLEDProtocolError.transferTooLarge
        }
        let serial = nextSerial
        nextSerial &+= 1
        let start = SpotLEDProtocol.buildStart(serial: serial, totalLength: payload.count)
        try await sendCommandAndWait(start, expectedType: 2, serial: serial, timeout: 8)
        try await writeChunks(payload)
        try await Task.sleep(nanoseconds: 80_000_000)
        let end = SpotLEDProtocol.buildStart(serial: serial, totalLength: payload.count, isEnd: true)
        try await sendCommandAndWait(end, expectedType: 4, serial: serial, timeout: 12)
    }

    private func sendCommandAndWait(_ bytes: [UInt8], expectedType: UInt8,
                                    serial: UInt16, timeout: TimeInterval) async throws {
        guard let peripheral = activePeripheral, let command = commandCharacteristic else {
            throw SpotLEDProtocolError.missingCharacteristic
        }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            acknowledgementContinuation = (expectedType, serial, continuation)
            peripheral.writeValue(Data(bytes), for: command, type: .withoutResponse)
            DispatchQueue.main.asyncAfter(deadline: .now() + timeout) { [weak self] in
                guard let self,
                      let waiter = self.acknowledgementContinuation,
                      waiter.type == expectedType, waiter.serial == serial else { return }
                self.acknowledgementContinuation = nil
                waiter.continuation.resume(throwing: SpotLEDProtocolError.timeout("transfer"))
            }
        }
    }

    private func writeChunks(_ payload: [UInt8]) async throws {
        guard let peripheral = activePeripheral, let data = dataCharacteristic else {
            throw SpotLEDProtocolError.missingCharacteristic
        }
        let chunkSize = min(maximumChunkSize,
                            peripheral.maximumWriteValueLength(for: .withoutResponse))
        guard chunkSize > 0 else { throw SpotLEDProtocolError.transport("Bluetooth reported a zero write size.") }
        pendingChunks = stride(from: 0, to: payload.count, by: chunkSize).map { offset in
            Data(payload[offset..<min(payload.count, offset + chunkSize)])
        }
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            chunkContinuation = continuation
            let token = UUID()
            chunkToken = token
            pumpChunks()
            DispatchQueue.main.asyncAfter(deadline: .now() + 15) { [weak self] in
                guard let self, self.chunkToken == token,
                      let continuation = self.chunkContinuation else { return }
                self.chunkContinuation = nil
                continuation.resume(throwing: SpotLEDProtocolError.timeout("BLE data writes"))
            }
        }
        _ = data // Retain the discovered data characteristic through the write loop.
    }

    private func pumpChunks() {
        guard let peripheral = activePeripheral, let data = dataCharacteristic else { return }
        while !pendingChunks.isEmpty && peripheral.canSendWriteWithoutResponse {
            let chunk = pendingChunks.removeFirst()
            peripheral.writeValue(chunk, for: data, type: .withoutResponse)
        }
        if pendingChunks.isEmpty, let continuation = chunkContinuation {
            chunkContinuation = nil
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.08) {
                continuation.resume()
            }
        }
    }

    private func disconnectActive() async {
        guard let peripheral = activePeripheral else { return }
        if peripheral.state == .connected {
            await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
                disconnectContinuation = continuation
                central.cancelPeripheralConnection(peripheral)
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
                    self?.finishDisconnect()
                }
            }
        } else {
            clearConnection()
        }
    }

    private func finishReady(_ result: Result<Void, Error>) {
        guard let continuation = readyContinuation else { return }
        readyContinuation = nil
        switch result {
        case .success: isReady = true; continuation.resume()
        case .failure(let error): continuation.resume(throwing: error)
        }
    }

    private func finishDisconnect() {
        guard let continuation = disconnectContinuation else { return }
        disconnectContinuation = nil
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
        switch central.state {
        case .poweredOn: status = "Bluetooth ready. Find a nearby SpotLED display."
        case .poweredOff: status = "Bluetooth is off. Turn it on to find a display."
        case .unauthorized: status = "Allow Bluetooth access for SpotLED Companion in Settings."
        case .unsupported: status = "This iPhone does not support Bluetooth LE."
        default: status = "Checking Bluetooth…"
        }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        let name = (advertisementData[CBAdvertisementDataLocalNameKey] as? String)
            ?? peripheral.name ?? ""
        guard name.hasPrefix(Self.devicePrefix) else { return }
        peripherals[peripheral.identifier] = peripheral
        let discovered = DiscoveredSpotLED(id: peripheral.identifier, name: name)
        if !devices.contains(where: { $0.id == discovered.id }) {
            devices.append(discovered)
            devices.sort { $0.name.localizedCaseInsensitiveCompare($1.name) == .orderedAscending }
        }
        if selectedDeviceID == nil { selectedDeviceID = discovered.id }
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
        finishReady(.failure(error ?? SpotLEDProtocolError.transport("Could not connect to the display.")))
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral,
                        error: Error?) {
        guard activePeripheral?.identifier == peripheral.identifier else { return }
        if readyContinuation != nil {
            finishReady(.failure(error ?? SpotLEDProtocolError.transport("Disconnected before the display was ready.")))
        }
        if let waiter = acknowledgementContinuation {
            acknowledgementContinuation = nil
            waiter.continuation.resume(throwing: error ?? SpotLEDProtocolError.transport("Disconnected during transfer."))
        }
        finishDisconnect()
        if !isSending, let error { status = "Bluetooth disconnected: \(error.localizedDescription)" }
    }
}

extension SpotLedBLEManager: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let services = peripheral.services, !services.isEmpty else {
            finishReady(.failure(error ?? SpotLEDProtocolError.transport("No BLE services were found.")))
            return
        }
        for service in services { peripheral.discoverCharacteristics(nil, for: service) }
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService,
                    error: Error?) {
        guard error == nil else {
            finishReady(.failure(error ?? SpotLEDProtocolError.transport("Could not inspect BLE characteristics.")))
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
        guard characteristic.uuid == Self.commandUUID else { return }
        if let error { finishReady(.failure(error)); return }
        guard characteristic.isNotifying else {
            finishReady(.failure(SpotLEDProtocolError.transport("Could not enable BLE acknowledgements.")))
            return
        }
        finishReady(.success(()))
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic,
                    error: Error?) {
        guard characteristic.uuid == Self.commandUUID,
              let waiter = acknowledgementContinuation else { return }
        if let error {
            acknowledgementContinuation = nil
            waiter.continuation.resume(throwing: error)
            return
        }
        guard let value = characteristic.value,
              let status = SpotLEDProtocol.acknowledgment(value,
                                                         expectedType: waiter.type,
                                                         serial: waiter.serial) else { return }
        acknowledgementContinuation = nil
        if status == 0 { waiter.continuation.resume() }
        else { waiter.continuation.resume(throwing: SpotLEDProtocolError.rejected(status)) }
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        pumpChunks()
    }
}
