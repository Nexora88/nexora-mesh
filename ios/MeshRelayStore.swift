import Foundation
import Security

/// Opt-in, device-local store for opaque Rust wire envelopes.
/// It never decrypts or displays relayed message content.
final class MeshRelayStore {
    private struct Record: Codable {
        let id: String
        let envelope: Data
    }

    private let service = "org.nexora.mesh.relay.v1"
    private let queueAccount = "opaque-envelope-queue"
    private let seenAccount = "seen-message-ids"
    private let optInAccount = "relay-opt-in"
    private let lock = NSLock()
    private let maximumItems = 128
    private let maximumSeen = 512

    var isOptedIn: Bool {
        get { (try? read(optInAccount)) == Data([1]) }
        set {
            lock.lock()
            defer { lock.unlock() }
            try? write(Data([newValue ? 1 : 0]), account: optInAccount)
            if !newValue { try? write(try JSONEncoder().encode([Record]()), account: queueAccount) }
        }
    }

    func pendingCount() -> Int {
        lock.lock()
        defer { lock.unlock() }
        return (try? loadQueue().count) ?? 0
    }

    @discardableResult
    func enqueue(_ envelope: Data) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard isOptedIn, envelope.count >= 49, envelope.count <= MeshCoreBridge.maxEnvelopeBytes else { return false }
        do { try MeshCoreBridge.validate(envelope) } catch { return false }
        let id = envelope[3..<19].map { String(format: "%02x", $0) }.joined()
        guard var queue = try? loadQueue(), !queue.contains(where: { $0.id == id }),
              queue.count < maximumItems else { return false }
        guard var seen = try? loadSeen(), !seen.contains(id) else { return false }
        queue.append(Record(id: id, envelope: envelope))
        seen.append(id)
        if seen.count > maximumSeen { seen.removeFirst(seen.count - maximumSeen) }
        do {
            try write(try JSONEncoder().encode(queue), account: queueAccount)
            try write(try JSONEncoder().encode(seen), account: seenAccount)
            return true
        } catch {
            return false
        }
    }

    /// Returns a hop-decremented copy while retaining the stored original until next-hop ACK.
    func peekForNextHop(nowUnixSeconds: UInt64) -> (id: String, envelope: Data)? {
        lock.lock()
        defer { lock.unlock() }
        guard isOptedIn, let items = try? loadQueue(), let item = items.first,
              let forwarded = try? MeshCoreBridge.forward(item.envelope, nowUnixSeconds: nowUnixSeconds)
        else { return nil }
        return (item.id, forwarded)
    }

    /// Remove only after the next device confirms durable storage of this envelope.
    @discardableResult
    private func acknowledgeStoredByNextHop(messageID: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        guard var queue = try? loadQueue(),
              let index = queue.firstIndex(where: { $0.id == messageID.lowercased() }) else { return false }
        queue.remove(at: index)
        return (try? write(try JSONEncoder().encode(queue), account: queueAccount)) != nil
    }

    /// Create a STORE_ACK only after enqueue() has committed the opaque envelope.
    func createStoredAck(envelope: Data, fromNode: Data, toNode: Data,
                         hopKey: Data, nowUnixSeconds: UInt64, nonce: Data) -> Data? {
        lock.lock()
        defer { lock.unlock() }
        guard isOptedIn, envelope.count >= 49 else { return nil }
        do { try MeshCoreBridge.validate(envelope) } catch { return nil }
        let messageID = envelope.subdata(in: 3..<19)
        let id = messageID.map { String(format: "%02x", $0) }.joined()
        guard (try? loadQueue().contains(where: { $0.id == id && $0.envelope == envelope })) == true else {
            return nil
        }
        return MeshCoreBridge.createStoreAck(messageID: messageID, fromNode: fromNode,
            toNode: toNode, storedAtUnixSeconds: nowUnixSeconds, nonce: nonce, hopKey: hopKey)
    }

    /// Remove a stored item only after Rust verifies the pairwise MAC and peer identities.
    @discardableResult
    func acknowledgeStoredByNextHop(ack: Data, hopKey: Data, expectedFrom: Data,
                                    expectedTo: Data, nowUnixSeconds: UInt64,
                                    maxAgeSeconds: UInt64) -> Bool {
        guard MeshCoreBridge.verifyStoreAck(ack, hopKey: hopKey, expectedFrom: expectedFrom,
            expectedTo: expectedTo, nowUnixSeconds: nowUnixSeconds,
            maxAgeSeconds: maxAgeSeconds), ack.count == 105 else { return false }
        let id = ack.subdata(in: 1..<17).map { String(format: "%02x", $0) }.joined()
        return acknowledgeStoredByNextHop(messageID: id)
    }

    private func loadQueue() throws -> [Record] {
        guard let data = try? read(queueAccount) else { return [] }
        return try JSONDecoder().decode([Record].self, from: data)
    }

    private func loadSeen() throws -> [String] {
        guard let data = try? read(seenAccount) else { return [] }
        return try JSONDecoder().decode([String].self, from: data)
    }

    private func read(_ account: String) throws -> Data {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
        return data
    }

    private func write(_ data: Data, account: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var insert = query
            attributes.forEach { insert[$0.key] = $0.value }
            let added = SecItemAdd(insert as CFDictionary, nil)
            guard added == errSecSuccess else {
                throw NSError(domain: NSOSStatusErrorDomain, code: Int(added))
            }
        } else if status != errSecSuccess {
            throw NSError(domain: NSOSStatusErrorDomain, code: Int(status))
        }
    }
}
