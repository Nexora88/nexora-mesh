import CoreBluetooth
import Foundation
import Combine

/// Cross-platform BLE GATT transport for complete, bounded Nexora Mesh wire envelopes.
/// It moves bytes only; callers must authenticate peers and encrypt payloads before sending.
/// iOS background execution and real-device interoperability must be verified separately.
final class MeshBLETransport: NSObject, ObservableObject {
    static let serviceUUID = CBUUID(string: "7D2A0001-5B6C-4F10-9A7D-4E45584F5241")
    static let dataUUID = CBUUID(string: "7D2A0002-5B6C-4F10-9A7D-4E45584F5241")
    private static let maxEnvelopeBytes = 256 * 1024
    private static let headerBytes = 22 // transfer ID 16, index 2, count 2, payload length 2

    @Published private(set) var isScanning = false
    @Published private(set) var discoveredNames: [UUID: String] = [:]
    @Published private(set) var status = "Bluetooth başlatılıyor…"
    var onPacket: ((Data) -> Void)?

    private var central: CBCentralManager!
    private var peripheralManager: CBPeripheralManager!
    private var characteristic: CBMutableCharacteristic?
    private var connectedPeripheral: CBPeripheral?
    private var peripherals: [UUID: CBPeripheral] = [:]
    private var remoteCharacteristic: CBCharacteristic?
    private var subscribedCentrals: [CBCentral] = []
    private var assemblies: [String: Assembly] = [:]
    private var pendingWrites: [Data] = []
    private var pendingNotifications: [Data] = []
    private var notificationOffset = 0
    private var currentTransferID: Data?
    private var nextFragmentIndex = 0

    private struct Assembly {
        let count: Int
        var chunks: [Int: Data] = [:]
        var receivedBytes = 0
        let createdAt = Date()
    }

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: .main)
        peripheralManager = CBPeripheralManager(delegate: self, queue: .main)
    }

    func startDiscovery() {
        guard central.state == .poweredOn else {
            status = "Bluetooth açık değil veya bu cihazda kullanılamıyor."
            return
        }
        central.scanForPeripherals(withServices: [Self.serviceUUID],
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        isScanning = true
        status = "Nexora Mesh BLE cihazları aranıyor."
    }

    func stopDiscovery() {
        central.stopScan()
        isScanning = false
    }

    func connect(to id: UUID) {
        guard let peer = peripherals[id] ?? central.retrievePeripherals(withIdentifiers: [id]).first else {
            status = "Cihaz artık bulunamıyor; yeniden ara."
            return
        }
        connectedPeripheral = peer
        peer.delegate = self
        central.connect(peer, options: nil)
        status = "BLE bağlantısı kuruluyor…"
    }

    func disconnect() {
        if let peer = connectedPeripheral { central.cancelPeripheralConnection(peer) }
        connectedPeripheral = nil
        remoteCharacteristic = nil
        assemblies.removeAll()
        pendingWrites.removeAll()
        pendingNotifications.removeAll()
        notificationOffset = 0
        status = "Bağlantı kapatıldı."
    }

    func send(envelope: Data) {
        guard !envelope.isEmpty, envelope.count <= Self.maxEnvelopeBytes else {
            status = "Paket boş veya izin verilen boyuttan büyük."
            return
        }

        let transferID = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
        let maxPayload: Int
        let sendAsCentral: Bool
        if let peer = connectedPeripheral, remoteCharacteristic != nil {
            maxPayload = peer.maximumWriteValueLength(for: .withResponse) - Self.headerBytes
            sendAsCentral = true
        } else if !subscribedCentrals.isEmpty, characteristic != nil {
            maxPayload = peripheralManager.maximumUpdateValueLength - Self.headerBytes
            sendAsCentral = false
        } else {
            status = "Önce çift yönlü BLE bağlantısı kur."
            return
        }
        guard maxPayload > 0 else {
            status = "BLE MTU, güvenli parça başlığı için yetersiz."
            return
        }

        let count = Int(ceil(Double(envelope.count) / Double(maxPayload)))
        guard count <= Int(UInt16.max) else { status = "Paket çok fazla parçaya bölünüyor."; return }
        var fragments = [Data]()
        for index in 0..<count {
            let start = index * maxPayload
            let end = min(envelope.count, start + maxPayload)
            var fragment = Data()
            fragment.append(transferID)
            fragment.appendUInt16(UInt16(index))
            fragment.appendUInt16(UInt16(count))
            fragment.appendUInt16(UInt16(end - start))
            fragment.append(envelope[start..<end])
            fragments.append(fragment)
        }

        if sendAsCentral, let peer = connectedPeripheral, let remote = remoteCharacteristic {
            pendingWrites = fragments
            currentTransferID = transferID
            nextFragmentIndex = 0
            writeNext(peer: peer, characteristic: remote)
        } else {
            pendingNotifications = fragments
            notificationOffset = 0
            flushNotifications()
        }
    }

    private func flushNotifications() {
        guard let characteristic else { return }
        while notificationOffset < pendingNotifications.count {
            let next = pendingNotifications[notificationOffset]
            guard peripheralManager.updateValue(next, for: characteristic, onSubscribedCentrals: subscribedCentrals) else {
                status = "BLE bildirim kuyruğu dolu; gönderim akışı bekletildi."
                return
            }
            notificationOffset += 1
        }
        pendingNotifications.removeAll()
        notificationOffset = 0
        status = "Paket BLE bildirim kuyruğundan aktarıldı; uygulama teslim onayı ayrıca gerekir."
    }

    private func acceptFragment(_ data: Data) {
        let now = Date()
        assemblies = assemblies.filter { now.timeIntervalSince($0.value.createdAt) <= 30 }
        guard data.count >= Self.headerBytes else { status = "Kısa BLE parçası reddedildi."; return }
        let id = data.prefix(16).map { String(format: "%02x", $0) }.joined()
        guard let index = data.uint16(at: 16), let count = data.uint16(at: 18),
              let payloadLength = data.uint16(at: 20),
              count > 0, index < count, Int(payloadLength) == data.count - Self.headerBytes,
              count <= UInt16.max else {
            status = "Geçersiz BLE parça başlığı reddedildi."
            return
        }
        if assemblies[id] == nil {
            guard assemblies.count < 4 else { status = "Parça belleği dolu."; return }
            assemblies[id] = Assembly(count: Int(count))
        }
        guard var assembly = assemblies[id], assembly.count == Int(count) else {
            assemblies.removeValue(forKey: id)
            status = "Çakışan aktarım başlığı reddedildi."
            return
        }
        let chunk = Data(data.dropFirst(Self.headerBytes))
        if let old = assembly.chunks[Int(index)] {
            guard old == chunk else { assemblies.removeValue(forKey: id); status = "Çakışan yinelenen parça reddedildi."; return }
            return
        }
        guard assembly.receivedBytes + chunk.count <= Self.maxEnvelopeBytes else {
            assemblies.removeValue(forKey: id)
            status = "Aktarım boyutu sınırı aşıldı."
            return
        }
        assembly.chunks[Int(index)] = chunk
        assembly.receivedBytes += chunk.count
        assemblies[id] = assembly
        guard assembly.chunks.count == assembly.count else { return }
        var packet = Data()
        for part in 0..<assembly.count {
            guard let bytes = assembly.chunks[part] else { return }
            packet.append(bytes)
        }
        assemblies.removeValue(forKey: id)
        guard !packet.isEmpty, packet.count <= Self.maxEnvelopeBytes else { return }
        onPacket?(packet)
        status = "Tam paket alındı; üst katman doğrulaması bekleniyor."
    }
}

extension MeshBLETransport: CBCentralManagerDelegate {
    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        status = central.state == .poweredOn ? "Bluetooth hazır." : "Bluetooth kullanılamıyor."
        if central.state == .poweredOn { startPeripheralServiceIfReady() }
    }

    func centralManager(_ central: CBCentralManager, didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any], rssi RSSI: NSNumber) {
        peripherals[peripheral.identifier] = peripheral
        discoveredNames[peripheral.identifier] = peripheral.name ?? "Yakındaki Nexora cihazı"
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        peripheral.delegate = self
        peripheral.discoverServices([Self.serviceUUID])
        status = "BLE bağlı; servis keşfediliyor."
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        status = "BLE bağlantısı başarısız: \(error?.localizedDescription ?? "bilinmeyen hata")"
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectedPeripheral = nil
        remoteCharacteristic = nil
        status = "BLE bağlantısı kesildi."
    }
}

extension MeshBLETransport: CBPeripheralDelegate {
    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        guard error == nil, let service = peripheral.services?.first(where: { $0.uuid == Self.serviceUUID }) else {
            status = "Nexora BLE servisi bulunamadı."
            return
        }
        peripheral.discoverCharacteristics([Self.dataUUID], for: service)
    }

    func peripheral(_ peripheral: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard error == nil, let item = service.characteristics?.first(where: { $0.uuid == Self.dataUUID }) else {
            status = "BLE veri özelliği bulunamadı."
            return
        }
        remoteCharacteristic = item
        if item.properties.contains(.notify) { peripheral.setNotifyValue(true, for: item) }
        status = "BLE veri kanalı hazır; uygulama kimliği ayrıca doğrulanmalıdır."
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let value = characteristic.value else { return }
        acceptFragment(value)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let remote = remoteCharacteristic else {
            status = "BLE parçası yazılamadı."
            return
        }
        nextFragmentIndex += 1
        writeNext(peer: peripheral, characteristic: remote)
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        if let remote = remoteCharacteristic { writeNext(peer: peripheral, characteristic: remote) }
    }
}

extension MeshBLETransport: CBPeripheralManagerDelegate {
    private func startPeripheralServiceIfReady() {
        guard peripheralManager.state == .poweredOn, characteristic == nil else { return }
        let value = CBMutableCharacteristic(type: Self.dataUUID,
                                            properties: [.write, .writeWithoutResponse, .notify],
                                            value: nil,
                                            permissions: [.writeable])
        let service = CBMutableService(type: Self.serviceUUID, primary: true)
        service.characteristics = [value]
        characteristic = value
        peripheralManager.add(service)
    }

    func peripheralManagerDidUpdateState(_ peripheral: CBPeripheralManager) {
        if peripheral.state == .poweredOn {
            startPeripheralServiceIfReady()
        } else {
            status = "BLE yayıncısı kullanılamıyor."
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didAdd service: CBService, error: Error?) {
        guard error == nil else { status = "BLE GATT servisi kurulamadı."; return }
        peripheralManager.startAdvertising([CBAdvertisementDataServiceUUIDsKey: [Self.serviceUUID],
                                            CBAdvertisementDataLocalNameKey: "Nexora Mesh"])
    }

    func peripheralManagerIsReady(toUpdateSubscribers peripheral: CBPeripheralManager) {
        flushNotifications()
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        if !subscribedCentrals.contains(where: { $0.identifier == central.identifier }) {
            subscribedCentrals.append(central)
        }
        status = "Bir BLE istemcisi bildirimlere abone oldu."
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribedCentrals.removeAll { $0.identifier == central.identifier }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            guard request.characteristic.uuid == Self.dataUUID, let value = request.value else {
                peripheral.respond(to: request, withResult: .attributeNotFound)
                continue
            }
            acceptFragment(value)
            peripheral.respond(to: request, withResult: .success)
        }
    }
}

private extension Data {
    mutating func appendUInt16(_ value: UInt16) {
        var bigEndian = value.bigEndian
        Swift.withUnsafeBytes(of: &bigEndian) { append(contentsOf: $0) }
    }

    func uint16(at offset: Int) -> UInt16? {
        guard offset >= 0, count >= offset + 2 else { return nil }
        return (UInt16(self[offset]) << 8) | UInt16(self[offset + 1])
    }
}
