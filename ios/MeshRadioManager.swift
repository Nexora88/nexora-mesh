import Foundation
import CoreBluetooth
import os.log

/// CoreBluetooth host for Nexora Mesh on Apple platforms.
///
/// IMPORTANT PLATFORM CONSTRAINTS (do not try to bypass):
/// - Background scanning / advertising is controlled by the OS.
/// - Only declare the background modes you actually need and that the user can understand.
/// - iOS may suspend, coalesce or delay BLE activity when the app is not in the foreground.
/// - There is no reliable way to keep continuous background mesh relay without user-visible
///   justification and careful state-restoration handling.
/// - This class is a correct skeleton; full A→B→C physical verification is still required.
@MainActor
final class MeshRadioManager: NSObject, ObservableObject {

    private let log = Logger(subsystem: "org.nexora.mesh", category: "radio")

    private var central: CBCentralManager?
    private var peripheral: CBPeripheralManager?

    @Published private(set) var bluetoothState: CBManagerState = .unknown
    @Published private(set) var isScanning = false
    @Published private(set) var isAdvertising = false
    @Published private(set) var lastError: String?

    static let meshServiceUUID = CBUUID(string: "6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

    func start() {
        central = CBCentralManager(delegate: self, queue: nil, options: [
            CBCentralManagerOptionShowPowerAlertKey: true
        ])
        peripheral = CBPeripheralManager(delegate: self, queue: nil, options: [
            CBPeripheralManagerOptionShowPowerAlertKey: true
        ])
    }

    func stop() {
        stopScan()
        stopAdvertising()
        central = nil
        peripheral = nil
    }

    func startScan(allowDuplicates: Bool = false) {
        guard let central, central.state == .poweredOn else {
            lastError = "Bluetooth not powered on"
            return
        }
        let options: [String: Any] = [
            CBCentralManagerScanOptionAllowDuplicatesKey: allowDuplicates
        ]
        central.scanForPeripherals(withServices: [Self.meshServiceUUID], options: options)
        isScanning = true
        log.info("Scan started (service-filtered)")
    }

    func stopScan() {
        central?.stopScan()
        isScanning = false
    }

    func startAdvertising(localName: String = "Nexora") {
        guard let peripheral, peripheral.state == .poweredOn else {
            lastError = "Peripheral manager not powered on"
            return
        }
        let data: [String: Any] = [
            CBAdvertisementDataLocalNameKey: localName,
            CBAdvertisementDataServiceUUIDsKey: [Self.meshServiceUUID]
        ]
        peripheral.startAdvertising(data)
    }

    func stopAdvertising() {
        peripheral?.stopAdvertising()
        isAdvertising = false
    }
}

extension MeshRadioManager: CBCentralManagerDelegate {
    nonisolated func centralManagerDidUpdateState(_ central: CBCentralManager) {
        Task { @MainActor in
            self.bluetoothState = central.state
            if central.state != .poweredOn {
                self.isScanning = false
            }
        }
    }

    nonisolated func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        // TODO: feed discovered peer into pairing / trust layer
        // Do not auto-add contacts. Discovery ≠ trust.
    }
}

extension MeshRadioManager: CBPeripheralManagerDelegate {
    nonisolated func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        Task { @MainActor in
            if peripheral.state != .poweredOn {
                self.isAdvertising = false
            }
        }
    }

    nonisolated func peripheralManagerDidStartAdvertising(
        _ peripheral: CBPeripheralManager,
        error: Error?
    ) {
        Task { @MainActor in
            if let error {
                self.lastError = error.localizedDescription
                self.isAdvertising = false
                self.log.error("Advertise failed: \(error.localizedDescription)")
            } else {
                self.isAdvertising = true
                self.log.info("Advertising started")
            }
        }
    }
}
