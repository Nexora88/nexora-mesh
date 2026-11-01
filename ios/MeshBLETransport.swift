import CoreBluetooth
import Foundation
import Combine
import UIKit

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
    private var connectedPeripherals: [UUID: CBPeripheral] = [:]
    private var connectingPeers = Set<UUID>()
    private var remoteCharacteristic: CBCharacteristic?
    private var remoteCharacteristics: [UUID: CBCharacteristic] = [:]
    private var peerPendingWrites: [UUID: [Data]] = [:]
    private var peerNextFragmentIndex: [UUID: Int] = [:]
    private var peerAssemblies: [UUID: [String: Assembly]] = [:]
    private var subscribedCentrals: [CBCentral] = []
    private var subscribedCentralsByID: [UUID: CBCentral] = [:]
    private var centralPendingNotifications: [UUID: [Data]] = [:]
    private var centralNotificationOffsets: [UUID: Int] = [:]
    var onPacketFromPeer: ((UUID, Data) -> Void)?
    var onPeerReady: ((UUID) -> Void)?
    var onPeerDisconnected: ((UUID) -> Void)?
    var connectedPeerIDs: Set<UUID> { Set(connectedPeripherals.keys).union(subscribedCentralsByID.keys) }
    private var assemblies: [String: Assembly] = [:]
    private var discoveryRequested = false
    private var scanWindowWorkItem: DispatchWorkItem?
    private var scanRestartWorkItem: DispatchWorkItem?

    private struct Assembly {
        let count: Int
        var chunks: [Int: Data] = [:]
        var receivedBytes = 0
        let createdAt = Date()
    }

    override init() {
        super.init()
        central = CBCentralManager(
            delegate: self,
            queue: .main,
            options: [CBCentralManagerOptionRestoreIdentifierKey: "org.nexora.mesh.ble.central.v1"]
        )
        peripheralManager = CBPeripheralManager(
            delegate: self,
            queue: .main,
            options: [CBPeripheralManagerOptionRestoreIdentifierKey: "org.nexora.mesh.ble.peripheral.v1"]
        )
        NotificationCenter.default.addObserver(
            self, selector: #selector(applicationDidBecomeActive),
            name: UIApplication.didBecomeActiveNotification, object: nil
        )
    }

    func startDiscovery() {
        discoveryRequested = true
        beginForegroundScanWindow()
    }

    func stopDiscovery() {
        discoveryRequested = false
        scanWindowWorkItem?.cancel()
        scanRestartWorkItem?.cancel()
        scanWindowWorkItem = nil
        scanRestartWorkItem = nil
        central.stopScan()
        isScanning = false
    }

    @objc private func applicationDidBecomeActive() {
        guard discoveryRequested, central.state == .poweredOn else { return }
        if central.isScanning {
            central.stopScan()
            isScanning = false
        }
        beginForegroundScanWindow()
    }

    private func beginForegroundScanWindow() {
        guard discoveryRequested, central.state == .poweredOn else {
            status = "Bluetooth açık değil veya bu cihazda kullanılamıyor."
            return
        }
        scanRestartWorkItem?.cancel()
        central.scanForPeripherals(withServices: [Self.serviceUUID],
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])
        isScanning = true
        status = "Nexora Mesh BLE taraması etkin."
        guard UIApplication.shared.applicationState == .active else {
            status = "iOS arka plan BLE taraması OS tarafından zamanlanıyor; sürekli tarama garantisi yok."
            return
        }

        UIDevice.current.isBatteryMonitoringEnabled = true
        let batteryPercent = UIDevice.current.batteryLevel < 0
            ? 100 : UInt8((UIDevice.current.batteryLevel * 100).rounded()).clamped(to: 0...100)
        let charging = UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full
        let interval = MeshCoreBridge.recommendedScanIntervalSeconds(
            batteryPercent: batteryPercent,
            charging: charging,
            lowPowerMode: ProcessInfo.processInfo.isLowPowerModeEnabled,
            appForeground: true,
            recentlyConnected: connectedPeripheral != nil,
            urgentPending: !pendingWrites.isEmpty || !pendingNotifications.isEmpty
        )
        let stopWindow = DispatchWorkItem { [weak self] in
            guard let self, self.discoveryRequested else { return }
            // If the app moved to background, keep the CoreBluetooth scan active and let iOS
            // apply its documented background scheduling/coalescing behavior.
            guard UIApplication.shared.applicationState == .active else {
                self.status = "Arka plan BLE taraması iOS tarafından yönetiliyor."
                return
            }
            self.central.stopScan()
            self.isScanning = false
            let restart = DispatchWorkItem { [weak self] in self?.beginForegroundScanWindow() }
            self.scanRestartWorkItem = restart
            DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(Int(max(5, interval))), execute: restart)
        }
        scanWindowWorkItem = stopWindow
        DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(3), execute: stopWindow)
    }

    func connect(to id: UUID) {
        guard let peer = peripherals[id] ?? central.retrievePeripherals(withIdentifiers: [id]).first else {
            status = "Cihaz artık bulunamıyor; yeniden ara."
            return
        }
        if connectedPeripherals[id] != nil || connectingPeers.contains(id) {
            status = "Bu BLE eşinin bağlantısı zaten açık veya kuruluyor."
            return
        }
        connectingPeers.insert(id)
        peer.delegate = self
        central.connect(peer, options: nil)
        status = "BLE bağlantısı kuruluyor…"
    }

    func disconnect(to id: UUID) {
        if subscribedCentralsByID[id] != nil {
            subscribedCentralsByID.removeValue(forKey: id)
            subscribedCentrals.removeAll { $0.identifier == id }
            centralPendingNotifications.removeValue(forKey: id)
            centralNotificationOffsets.removeValue(forKey: id)
            onPeerDisconnected?(id)
            return
        }
        guard let peer = connectedPeripherals[id] ?? peripherals[id] else { return }
        central.cancelPeripheralConnection(peer)
        connectingPeers.remove(id)
        connectedPeripherals.removeValue(forKey: id)
        remoteCharacteristics.removeValue(forKey: id)
        peerPendingWrites.removeValue(forKey: id)
        peerNextFragmentIndex.removeValue(forKey: id)
        peerAssemblies.removeValue(forKey: id)
        if connectedPeripheral?.identifier == id { connectedPeripheral = connectedPeripherals.values.first }
        if let current = connectedPeripheral { remoteCharacteristic = remoteCharacteristics[current.identifier] }
        status = "BLE eş oturumu kapatıldı."
    }

    func disconnect() {
        for peer in connectedPeripherals.values { central.cancelPeripheralConnection(peer) }
        for id in connectingPeers {
            if let peer = peripherals[id] { central.cancelPeripheralConnection(peer) }
        }
        connectingPeers.removeAll()
        connectedPeripherals.removeAll()
        remoteCharacteristics.removeAll()
        peerPendingWrites.removeAll()
        peerNextFragmentIndex.removeAll()
        peerAssemblies.removeAll()
        subscribedCentrals.removeAll()
        subscribedCentralsByID.removeAll()
        centralPendingNotifications.removeAll()
        centralNotificationOffsets.removeAll()
        connectedPeripheral = nil
        remoteCharacteristic = nil
        assemblies.removeAll()
        status = "Tüm BLE eş oturumları kapatıldı."
    }

    /// Queue a frame for one explicit BLE peer; fragment progress is isolated per UUID.
    func send(envelope: Data, to peerID: UUID) {
        guard !envelope.isEmpty, envelope.count <= Self.maxEnvelopeBytes,
              let peer = connectedPeripherals[peerID],
              let remote = remoteCharacteristics[peerID] else {
            status = "Seçilen BLE eşinin GATT oturumu hazır değil."
            return
        }
        let maxPayload = peer.maximumWriteValueLength(for: .withResponse) - Self.headerBytes
        guard maxPayload > 0 else { status = "BLE MTU yetersiz."; return }
        let transferID = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
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
        let queuedCount = peerPendingWrites[peerID]?.count ?? 0
        guard queuedCount + fragments.count <= 8192 else {
            status = "Bu BLE eşinin gönderim kuyruğu dolu."
            return
        }
        let wasIdle = peerPendingWrites[peerID] == nil
        peerPendingWrites[peerID, default: []].append(contentsOf: fragments)
        if wasIdle {
            peerNextFragmentIndex[peerID] = 0
            writeNext(peer: peer, characteristic: remote, peerID: peerID)
        }
    }

    private func writeNext(peer: CBPeripheral, characteristic: CBCharacteristic, peerID: UUID) {
        let index = peerNextFragmentIndex[peerID] ?? 0
        let queue = peerPendingWrites[peerID] ?? []
        guard index < queue.count else {
            peerPendingWrites.removeValue(forKey: peerID)
            peerNextFragmentIndex[peerID] = 0
            return
        }
        peer.writeValue(queue[index], for: characteristic, type: .withResponse)
    }

    /// Route to the correct GATT role while preserving per-peer queues.
    func send(envelope: Data, toPeer peerID: UUID) {
        if connectedPeripherals[peerID] != nil {
            send(envelope: envelope, to: peerID)
        } else if subscribedCentralsByID[peerID] != nil {
            send(envelope: envelope, toCentral: peerID)
        } else {
            status = "Seçilen BLE eş oturumu bağlı değil."
        }
    }

    /// Send notifications to exactly one subscribed central, never broadcast to every peer.
    func send(envelope: Data, toCentral peerID: UUID) {
        guard !envelope.isEmpty, envelope.count <= Self.maxEnvelopeBytes,
              let central = subscribedCentralsByID[peerID], let characteristic else {
            status = "Seçilen BLE merkez eşinin aboneliği hazır değil."
            return
        }
        let maxPayload = central.maximumUpdateValueLength - Self.headerBytes
        guard maxPayload > 0 else { status = "BLE bildirim MTU değeri yetersiz."; return }
        let transferID = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
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
        let queued = centralPendingNotifications[peerID]?.count ?? 0
        guard queued + fragments.count <= 8192 else {
            status = "Bu BLE eşinin bildirim kuyruğu dolu."
            return
        }
        let wasIdle = centralPendingNotifications[peerID] == nil
        centralPendingNotifications[peerID, default: []].append(contentsOf: fragments)
        if wasIdle {
            centralNotificationOffsets[peerID] = 0
            flushNotifications(toCentral: peerID)
        }
    }

    private func flushNotifications(toCentral peerID: UUID) {
        guard let central = subscribedCentralsByID[peerID], let characteristic,
              let queue = centralPendingNotifications[peerID] else { return }
        var offset = centralNotificationOffsets[peerID] ?? 0
        while offset < queue.count {
            guard peripheralManager.updateValue(queue[offset], for: characteristic,
                                                onSubscribedCentrals: [central]) else {
                centralNotificationOffsets[peerID] = offset
                status = "BLE eş bildirim kuyruğu dolu; akış bekletildi."
                return
            }
            offset += 1
        }
        centralPendingNotifications.removeValue(forKey: peerID)
        centralNotificationOffsets.removeValue(forKey: peerID)
    }

    func send(envelope: Data) {
        guard !envelope.isEmpty, envelope.count <= Self.maxEnvelopeBytes else {
            status = "Paket boş veya izin verilen boyuttan büyük."
            return
        }
        if let peer = connectedPeripheral {
            send(envelope: envelope, to: peer.identifier)
        } else if let central = subscribedCentrals.first {
            send(envelope: envelope, toCentral: central.identifier)
        } else {
            status = "Önce çift yönlü BLE eş oturumu kur."
        }
    }

    private func acceptFragment(_ data: Data, from peerID: UUID? = nil) {
        let now = Date()
        var peerMap = peerID.map { peerAssemblies[$0] ?? [:] } ?? assemblies
        for key in Array(peerMap.keys) {
            if let value = peerMap[key], now.timeIntervalSince(value.createdAt) > 30 {
                peerMap.removeValue(forKey: key)
            }
        }
        guard data.count >= Self.headerBytes else { status = "Kısa BLE parçası reddedildi."; return }
        let id = data.prefix(16).map { String(format: "%02x", $0) }.joined()
        guard let index = data.uint16(at: 16), let count = data.uint16(at: 18),
              let payloadLength = data.uint16(at: 20),
              count > 0, index < count, Int(payloadLength) == data.count - Self.headerBytes else {
            status = "Geçersiz BLE parça başlığı reddedildi."
            return
        }
        if peerMap[id] == nil {
            guard peerMap.count < 4 else { status = "Eş başına parça belleği dolu."; return }
            peerMap[id] = Assembly(count: Int(count))
        }
        guard var assembly = peerMap[id], assembly.count == Int(count) else {
            peerMap.removeValue(forKey: id)
            status = "Çakışan aktarım başlığı reddedildi."
            return
        }
        let chunk = Data(data.dropFirst(Self.headerBytes))
        if let old = assembly.chunks[Int(index)] {
            guard old == chunk else { peerMap.removeValue(forKey: id); status = "Çakışan yinelenen parça reddedildi."; return }
            return
        }
        guard assembly.receivedBytes + chunk.count <= Self.maxEnvelopeBytes else {
            peerMap.removeValue(forKey: id)
            status = "Aktarım boyutu sınırı aşıldı."
            return
        }
        assembly.chunks[Int(index)] = chunk
        assembly.receivedBytes += chunk.count
        peerMap[id] = assembly
        if let peerID { peerAssemblies[peerID] = peerMap } else { assemblies = peerMap }
        guard assembly.chunks.count == assembly.count else { return }
        var packet = Data()
        for part in 0..<assembly.count {
            guard let bytes = assembly.chunks[part] else { return }
            packet.append(bytes)
        }
        peerMap.removeValue(forKey: id)
        if let peerID { peerAssemblies[peerID] = peerMap } else { assemblies = peerMap }
        guard !packet.isEmpty, packet.count <= Self.maxEnvelopeBytes else { return }
        if let peerID, let onPacketFromPeer {
            onPacketFromPeer(peerID, packet)
        } else {
            onPacket?(packet)
        }
        status = "Tam paket alındı; üst katman doğrulaması bekleniyor."
    }
}

extension MeshBLETransport: CBCentralManagerDelegate {
    func centralManager(_ central: CBCentralManager, willRestoreState dict: [String: Any]) {
        if let restored = dict[CBCentralManagerRestoredStatePeripheralsKey] as? [CBPeripheral] {
            for peer in restored {
                peripherals[peer.identifier] = peer
                peer.delegate = self
                if peer.state == .connected {
                    connectedPeripherals[peer.identifier] = peer
                    connectedPeripheral = peer
                    peer.discoverServices([Self.serviceUUID])
                }
                discoveredNames[peer.identifier] = peer.name ?? "Geri yüklenen Nexora cihazı"
            }
        }
        status = "iOS BLE durumu işletim sistemi tarafından geri yüklendi; arka plan zamanlaması iOS denetimindedir."
    }

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
        connectingPeers.remove(peripheral.identifier)
        connectedPeripherals[peripheral.identifier] = peripheral
        connectedPeripheral = peripheral
        peripheral.delegate = self
        peripheral.discoverServices([Self.serviceUUID])
        status = "Ayrı BLE eş oturumu bağlı; servis keşfediliyor."
    }

    func centralManager(_ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?) {
        connectingPeers.remove(peripheral.identifier)
        status = "BLE bağlantısı başarısız: \(error?.localizedDescription ?? "bilinmeyen hata")"
    }

    func centralManager(_ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        connectedPeripherals.removeValue(forKey: peripheral.identifier)
        onPeerDisconnected?(peripheral.identifier)
        remoteCharacteristics.removeValue(forKey: peripheral.identifier)
        peerPendingWrites.removeValue(forKey: peripheral.identifier)
        peerNextFragmentIndex.removeValue(forKey: peripheral.identifier)
        peerAssemblies.removeValue(forKey: peripheral.identifier)
        if connectedPeripheral?.identifier == peripheral.identifier { connectedPeripheral = connectedPeripherals.values.first }
        remoteCharacteristic = connectedPeripheral.flatMap { remoteCharacteristics[$0.identifier] }
        status = "BLE eş oturumu kesildi."
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
        remoteCharacteristics[peripheral.identifier] = item
        if connectedPeripheral?.identifier == peripheral.identifier { remoteCharacteristic = item }
        onPeerReady?(peripheral.identifier)
        if item.properties.contains(.notify) { peripheral.setNotifyValue(true, for: item) }
        status = "BLE veri kanalı hazır; uygulama kimliği ayrıca doğrulanmalıdır."
    }

    func peripheral(_ peripheral: CBPeripheral, didUpdateValueFor characteristic: CBCharacteristic, error: Error?) {
        guard error == nil, let value = characteristic.value else { return }
        acceptFragment(value, from: peripheral.identifier)
    }

    func peripheral(_ peripheral: CBPeripheral, didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        let peerID = peripheral.identifier
        guard error == nil, let remote = remoteCharacteristics[peerID] else {
            status = "BLE parçası yazılamadı."
            return
        }
        let index = peerNextFragmentIndex[peerID] ?? 0
        peerNextFragmentIndex[peerID] = index + 1
        writeNext(peer: peripheral, characteristic: remote, peerID: peerID)
    }

    func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) {
        if let remote = remoteCharacteristics[peripheral.identifier] {
            writeNext(peer: peripheral, characteristic: remote, peerID: peripheral.identifier)
        }
    }
}

extension MeshBLETransport: CBPeripheralManagerDelegate {
    func peripheralManager(_ peripheral: CBPeripheralManager, willRestoreState dict: [String: Any]) {
        if let restored = dict[CBPeripheralManagerRestoredStateServicesKey] as? [CBMutableService],
           let service = restored.first(where: { $0.uuid == Self.serviceUUID }),
           let item = service.characteristics?.first(where: { $0.uuid == Self.dataUUID }) as? CBMutableCharacteristic {
            characteristic = item
            subscribedCentrals = item.subscribedCentrals ?? []
            subscribedCentralsByID = Dictionary(uniqueKeysWithValues: subscribedCentrals.map { ($0.identifier, $0) })
        }
        status = "iOS BLE yayın durumu geri yüklendi; sürekli arka plan yayını garanti edilmez."
    }

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
        for peerID in Array(centralPendingNotifications.keys) {
            flushNotifications(toCentral: peerID)
        }
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didSubscribeTo characteristic: CBCharacteristic) {
        subscribedCentralsByID[central.identifier] = central
        onPeerReady?(central.identifier)
        if !subscribedCentrals.contains(where: { $0.identifier == central.identifier }) {
            subscribedCentrals.append(central)
        }
        status = "Bir BLE istemcisi bildirimlere abone oldu."
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, central: CBCentral, didUnsubscribeFrom characteristic: CBCharacteristic) {
        subscribedCentrals.removeAll { $0.identifier == central.identifier }
        subscribedCentralsByID.removeValue(forKey: central.identifier)
        onPeerDisconnected?(central.identifier)
        centralPendingNotifications.removeValue(forKey: central.identifier)
        centralNotificationOffsets.removeValue(forKey: central.identifier)
    }

    func peripheralManager(_ peripheral: CBPeripheralManager, didReceiveWrite requests: [CBATTRequest]) {
        for request in requests {
            guard request.characteristic.uuid == Self.dataUUID, let value = request.value else {
                peripheral.respond(to: request, withResult: .attributeNotFound)
                continue
            }
            acceptFragment(value, from: request.central.identifier)
            peripheral.respond(to: request, withResult: .success)
        }
    }
}

private extension UInt8 {
    func clamped(to range: ClosedRange<UInt8>) -> UInt8 {
        Swift.min(Swift.max(self, range.lowerBound), range.upperBound)
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
