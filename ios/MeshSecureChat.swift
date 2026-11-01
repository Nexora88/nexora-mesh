import CryptoKit
import Combine
import Foundation
import Security

/// Android-compatible identity, QR trust, AES-GCM framing and delivery queue primitives.
/// Wire compatibility is intentionally limited to the v1 Android JSON protocol.
final class MeshSecureChat: ObservableObject {
    struct Message: Identifiable, Codable {
        let id: String
        let text: String
        let outgoing: Bool
        let createdAt: Date
        var delivery: String
        let recipientFingerprint: String?
        var identifiableId: String { id }
    }

    struct Contact: Codable, Equatable {
        let version: Int
        let publicKeyBase64: String
        let fingerprint: String
        let label: String
    }

    @Published private(set) var messages: [Message] = []
    @Published private(set) var status = "QR kimliği doğrulanmalı."
    @Published private(set) var remoteFingerprint: String?
    @Published private(set) var isSecure = false
    @Published private(set) var trustedContacts: [Contact] = []
    @Published var selectedRecipientFingerprint: String?
    @Published private(set) var draftStatus = ""

    private let identityTag = "org.nexora.mesh.identity.v1"
    private let defaults = UserDefaults.standard
    private let pendingKey = "mesh_delivery_v1_pending_ios"
    private let receivedKey = "mesh_delivery_v1_received_ios"
    private let retryKey = "mesh_delivery_v1_retry_counts_ios"
    private let contactsKey = "mesh_trusted_contacts_v1_ios"
    private let queuedDraftPrefix = "mesh_queued_draft_v1_"
    private let controlAAD = Data("NexoraMesh/control/v1".utf8)
    private let messageLimit = 1000
    private var sendPacket: ((Data) -> Void)?
    private var candidateKey: SymmetricKey?
    private var sessionKey: SymmetricKey?
    private var localProofNonce: String?
    private var remoteProofReceived = false
    private var localProofAcked = false
    private var retryCounts: [String: Int] = [:]
    private var retryScheduled = Set<String>()

    init() {
        trustedContacts = (try? JSONDecoder().decode([Contact].self, from: defaults.data(forKey: contactsKey) ?? Data())) ?? []
        retryCounts = defaults.dictionary(forKey: retryKey) as? [String: Int] ?? [:]
        _ = try? localPublicKeyDER()
        selectedRecipientFingerprint = trustedContacts.first?.fingerprint
        restorePendingMessages()
    }

    func selectRecipient(_ fingerprint: String?) {
        selectedRecipientFingerprint = fingerprint
    }

    func loadDraft(for fingerprint: String?) -> String {
        guard let fingerprint else { return "" }
        do {
            let data = try keychainReadDraft(account: fingerprint)
            return data.flatMap { String(data: $0, encoding: .utf8) } ?? ""
        } catch {
            draftStatus = "Taslak güvenli depodan okunamadı."
            return ""
        }
    }

    func saveDraft(_ text: String, for fingerprint: String?) {
        guard let fingerprint else { draftStatus = "Taslak için önce güvenilir alıcı seç."; return }
        do {
            try keychainWriteDraft(Data(text.utf8), account: fingerprint)
            draftStatus = "Taslak bu alıcının Keychain alanına kaydedildi."
        } catch {
            draftStatus = "Taslak güvenli depoya kaydedilemedi."
        }
    }

    func attach(send: @escaping (Data) -> Void) {
        sendPacket = send
        isSecure = false
        sessionKey = nil
        candidateKey = nil
        remoteFingerprint = nil
        sendHello()
    }

    func localQRPayload(label: String = "Nexora Mesh") throws -> String {
        let key = try localPublicKeyDER()
        let fingerprint = Self.fingerprint(key)
        let object: [String: Any] = [
            "v": 1,
            "k": key.base64EncodedString(),
            "f": fingerprint,
            "n": String(label.prefix(48))
        ]
        let data = try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
        return "NEXORAMESH:" + data.base64EncodedString()
    }

    /// Imports the same QR payload emitted by Android. The fingerprint is recomputed locally.
    func trustQRPayload(_ raw: String) throws {
        guard raw.hasPrefix("NEXORAMESH:"), raw.count <= 2048,
              let data = Data(base64Encoded: String(raw.dropFirst("NEXORAMESH:".count))),
              data.count <= 1024,
              let object = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let version = object["v"] as? Int, version == 1,
              let keyText = object["k"] as? String,
              let key = Data(base64Encoded: keyText), key.count <= 512,
              let claimed = object["f"] as? String else {
            throw MeshSecureChatError.invalidContact
        }
        _ = try Self.publicSecKey(fromAndroidDER: key)
        let actual = Self.fingerprint(key)
        guard actual.caseInsensitiveCompare(claimed) == .orderedSame else {
            throw MeshSecureChatError.fingerprintMismatch
        }
        let contact = Contact(version: 1, publicKeyBase64: keyText, fingerprint: actual,
                              label: String((object["n"] as? String ?? "Nexora Mesh").prefix(48)))
        trustedContacts.removeAll { $0.fingerprint.caseInsensitiveCompare(actual) == .orderedSame }
        trustedContacts.append(contact)
        defaults.set(try JSONEncoder().encode(trustedContacts), forKey: contactsKey)
        status = "Güvenilir QR kimliği kaydedildi. Cihazı yeniden bağlayarak doğrula."
        if remoteFingerprint?.caseInsensitiveCompare(actual) == .orderedSame {
            activateIfTrusted(contact)
        }
    }

    func sendText(_ text: String) {
        let clean = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return }
        guard let recipient = selectedRecipientFingerprint else {
            status = "Mesaj gönderilmedi: önce güvenilir alıcı seç."
            return
        }
        guard let sessionKey, isSecure,
              remoteFingerprint?.caseInsensitiveCompare(recipient) == .orderedSame else {
            status = "Taslak korundu; güvenli oturum kurulunca gönderim kuyruğuna alınacak."
            saveDraft(clean, for: recipient)
            defaults.set(true, forKey: queuedDraftPrefix + recipient.uppercased())
            return
        }
        guard let sendPacket else {
            status = "Taslak korundu; bağlantı yok."
            saveDraft(clean, for: recipient)
            defaults.set(true, forKey: queuedDraftPrefix + recipient.uppercased())
            return
        }
        var pending = loadPending()
        guard pending.count < messageLimit else { status = "Teslim kuyruğu dolu."; return }
        do {
            let id = UUID().uuidString.lowercased()
            let sealed = try Self.seal(Data(clean.utf8), key: sessionKey,
                                       aad: Data("NexoraMesh/data/v1:\(id)".utf8))
            let frame: [String: Any] = [
                "t": "DATA", "id": id, "n": sealed.nonce.base64EncodedString(),
                "c": sealed.ciphertext.base64EncodedString()
            ]
            let raw = try JSONSerialization.data(withJSONObject: frame, options: [.sortedKeys])
            pending.append(Pending(id: id, frame: raw, recipientFingerprint: recipient))
            try savePending(pending)
            defaults.removeObject(forKey: queuedDraftPrefix + recipient.uppercased())
            try keychainWriteDraft(Data(), account: recipient)
            messages.append(Message(id: id, text: clean, outgoing: true, createdAt: Date(), delivery: "Teslim bekleniyor", recipientFingerprint: recipient))
            sendPacket(raw)
            scheduleRetry(id: id, attempt: 1)
        } catch {
            status = "Mesaj şifrelenemedi veya kalıcı kuyruğa kaydedilemedi."
        }
    }

    func receive(_ packet: Data) {
        guard packet.count <= 256 * 1024,
              let frame = try? JSONSerialization.jsonObject(with: packet) as? [String: Any],
              let type = frame["t"] as? String else {
            status = "Paket reddedildi: geçersiz JSON veya boyut sınırı aşıldı."
            return
        }
        do {
            switch type {
            case "HELLO":
                guard let keyText = frame["k"] as? String, let key = Data(base64Encoded: keyText),
                      let claimed = frame["f"] as? String else { throw MeshSecureChatError.invalidContact }
                _ = try Self.publicSecKey(fromAndroidDER: key)
                let fingerprint = Self.fingerprint(key)
                guard fingerprint.caseInsensitiveCompare(claimed) == .orderedSame else {
                    throw MeshSecureChatError.fingerprintMismatch
                }
                remoteFingerprint = fingerprint
                guard let contact = trustedContacts.first(where: {
                    $0.fingerprint.caseInsensitiveCompare(fingerprint) == .orderedSame &&
                    $0.publicKeyBase64 == keyText
                }) else {
                    isSecure = false
                    status = "Karşı taraf tanındı; QR kodunu doğrulayıp güvenilir kişilere ekle."
                    return
                }
                activateIfTrusted(contact)
            case "PROOF", "PROOF_ACK", "ACK":
                guard let key = candidateKey ?? sessionKey else { throw MeshSecureChatError.noSession }
                let opened = try Self.open(frame, key: key, aad: controlAAD)
                let control = (try JSONSerialization.jsonObject(with: opened)) as? [String: Any]
                if type == "PROOF" {
                    guard let control, control["type"] as? String == "PROOF",
                          let nonce = control["nonce"] as? String,
                          Data(base64Encoded: nonce)?.count == 32 else { throw MeshSecureChatError.invalidProof }
                    remoteProofReceived = true
                    sendControl(type: "PROOF_ACK", payload: ["type": "PROOF_ACK", "nonce": nonce], key: key)
                    maybeActivate()
                } else if type == "PROOF_ACK" {
                    guard let control, control["type"] as? String == "PROOF_ACK",
                          control["nonce"] as? String == localProofNonce else { throw MeshSecureChatError.invalidProof }
                    localProofAcked = true
                    maybeActivate()
                } else {
                    guard isSecure, let control, control["type"] as? String == "DELIVERY_ACK",
                          let id = control["id"] as? String, UUID(uuidString: id) != nil else {
                        throw MeshSecureChatError.invalidProof
                    }
                    var pending = loadPending()
                    pending.removeAll { $0.id == id }
                    try savePending(pending)
                    retryCounts.removeValue(forKey: id)
                    retryScheduled.remove(id)
                    persistRetryCounts()
                    if let index = messages.firstIndex(where: { $0.id == id }) { messages[index].delivery = "Teslim edildi" }
                }
            case "DATA":
                guard isSecure, let key = sessionKey, let id = frame["id"] as? String,
                      UUID(uuidString: id) != nil,
                      let nonceText = frame["n"] as? String,
                      let cipherText = frame["c"] as? String,
                      let nonce = Data(base64Encoded: nonceText),
                      let ciphertext = Data(base64Encoded: cipherText) else { throw MeshSecureChatError.invalidProof }
                let plaintext = try Self.open(nonce: nonce, ciphertext: ciphertext, key: key,
                                              aad: Data("NexoraMesh/data/v1:\(id)".utf8))
                var received = loadReceivedIDs()
                let isNew = Self.acceptMessageID(id, seen: received)
                if isNew {
                    received.append(id)
                    if received.count > 4096 { received.removeFirst(received.count - 4096) }
                    try saveReceivedIDs(received)
                    messages.append(Message(id: id, text: String(decoding: plaintext, as: UTF8.self),
                                            outgoing: false, createdAt: Date(), delivery: "Alındı",
                                            recipientFingerprint: remoteFingerprint))
                }
                sendControl(type: "ACK", payload: ["type": "DELIVERY_ACK", "id": id], key: key)
            default:
                throw MeshSecureChatError.invalidFrame
            }
        } catch {
            status = "Paket reddedildi: kimlik, biçim veya şifre doğrulaması başarısız."
        }
    }

    private struct Pending: Codable {
        let id: String
        let frame: Data
        // Optional so records from earlier experimental builds still decode.
        let recipientFingerprint: String?
    }

    private func localPrivateKey() throws -> SecKey {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey, kSecAttrApplicationTag as String: Data(identityTag.utf8),
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom, kSecReturnRef as String: true
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecSuccess, let item { return item as! SecKey }
        guard status == errSecItemNotFound else { throw MeshSecureChatError.keyStore }
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeySizeInBits as String: 256,
            kSecPrivateKeyAttrs as String: [
                kSecAttrIsPermanent as String: true,
                kSecAttrApplicationTag as String: Data(identityTag.utf8)
            ]
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            if let error { throw error.takeRetainedValue() }
            throw NSError(domain: "NexoraMesh.KeyStore", code: Int(status))
        }
        return key
    }

    private func localPublicKeyDER() throws -> Data {
        let privateKey = try localPrivateKey()
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else { throw MeshSecureChatError.keyStore }
        var error: Unmanaged<CFError>?
        guard let raw = SecKeyCopyExternalRepresentation(publicKey, &error) as Data? else {
            if let error { throw error.takeRetainedValue() }
            throw NSError(domain: "NexoraMesh.KeyStore", code: 2)
        }
        // SubjectPublicKeyInfo prefix for id-ecPublicKey / prime256v1, matching Java X.509 encoding.
        let prefix = Data([0x30,0x59,0x30,0x13,0x06,0x07,0x2A,0x86,0x48,0xCE,0x3D,0x02,0x01,
                           0x06,0x08,0x2A,0x86,0x48,0xCE,0x3D,0x03,0x01,0x07,0x03,0x42,0x00])
        guard raw.count == 65, raw.first == 0x04 else { throw MeshSecureChatError.keyStore }
        return prefix + raw
    }

    private static func publicSecKey(fromAndroidDER der: Data) throws -> SecKey {
        let prefixCount = 26
        guard der.count == prefixCount + 65,
              der.prefix(prefixCount) == Data([0x30,0x59,0x30,0x13,0x06,0x07,0x2A,0x86,0x48,0xCE,0x3D,0x02,0x01,
                                                0x06,0x08,0x2A,0x86,0x48,0xCE,0x3D,0x03,0x01,0x07,0x03,0x42,0x00]) else {
            throw MeshSecureChatError.invalidContact
        }
        let raw = Data(der.suffix(65)) as CFData
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeECSECPrimeRandom,
            kSecAttrKeyClass as String: kSecAttrKeyClassPublic,
            kSecAttrKeySizeInBits as String: 256
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateWithData(raw, attributes as CFDictionary, &error) else {
            if let error { throw error.takeRetainedValue() }
            throw NSError(domain: "NexoraMesh.Contact", code: 1)
        }
        return key
    }

    private func activateIfTrusted(_ contact: Contact) {
        do {
            guard let remoteDER = Data(base64Encoded: contact.publicKeyBase64) else { throw MeshSecureChatError.invalidContact }
            let remoteKey = try Self.publicSecKey(fromAndroidDER: remoteDER)
            let privateKey = try localPrivateKey()
            var error: Unmanaged<CFError>?
            guard let shared = SecKeyCopyKeyExchangeResult(privateKey, .ecdhKeyExchangeStandard,
                                                           remoteKey, [:] as CFDictionary, &error) as Data? else {
                if let error { throw error.takeRetainedValue() }
                throw NSError(domain: "NexoraMesh.KeyStore", code: 2)
            }
            let key = Self.deriveSessionKey(sharedSecret: shared)
            candidateKey = key
            sessionKey = nil
            isSecure = false
            remoteFingerprint = contact.fingerprint
            remoteProofReceived = false
            localProofAcked = false
            let nonce = Data((0..<32).map { _ in UInt8.random(in: 0...255) }).base64EncodedString()
            localProofNonce = nonce
            sendControl(type: "PROOF", payload: ["type": "PROOF", "nonce": nonce], key: key)
            status = "QR kimliği eşleşti; şifreli anahtar sahipliği doğrulanıyor."
        } catch {
            isSecure = false
            status = "Güvenli oturum kurulamadı."
        }
    }

    private func maybeActivate() {
        guard remoteProofReceived, localProofAcked, let candidateKey else { return }
        sessionKey = candidateKey
        isSecure = true
        status = "QR doğrulamalı AES-GCM oturumu etkin."
        flushPending()
        if let recipient = selectedRecipientFingerprint,
           recipient.caseInsensitiveCompare(remoteFingerprint ?? "") == .orderedSame,
           defaults.bool(forKey: queuedDraftPrefix + recipient.uppercased()) {
            let draft = loadDraft(for: recipient)
            if !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                sendText(draft)
            } else {
                defaults.removeObject(forKey: queuedDraftPrefix + recipient.uppercased())
            }
        }
    }

    private func sendHello() {
        guard let sendPacket, let key = try? localPublicKeyDER() else { return }
        let frame: [String: Any] = ["t": "HELLO", "k": key.base64EncodedString(), "f": Self.fingerprint(key)]
        if let raw = try? JSONSerialization.data(withJSONObject: frame, options: [.sortedKeys]) { sendPacket(raw) }
    }

    private func sendControl(type: String, payload: [String: Any], key: SymmetricKey) {
        guard let raw = try? JSONSerialization.data(withJSONObject: payload, options: [.sortedKeys]),
              let sealed = try? Self.seal(raw, key: key, aad: controlAAD),
              let sendPacket else { return }
        let frame: [String: Any] = ["t": type, "n": sealed.nonce.base64EncodedString(), "c": sealed.ciphertext.base64EncodedString()]
        if let bytes = try? JSONSerialization.data(withJSONObject: frame, options: [.sortedKeys]) { sendPacket(bytes) }
    }

    private func flushPending() {
        guard let sendPacket, let remoteFingerprint else { return }
        // Never flush a stored frame to a different trusted recipient.
        for item in loadPending() where item.recipientFingerprint?.caseInsensitiveCompare(remoteFingerprint) == .orderedSame {
            if (retryCounts[item.id] ?? 0) > 8 { retryCounts[item.id] = 0 }
            sendPacket(item.frame)
            scheduleRetry(id: item.id, attempt: max(1, retryCounts[item.id] ?? 1))
        }
        persistRetryCounts()
    }

    private func keychainReadDraft(account: String) throws -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "org.nexora.mesh.drafts.v1",
            kSecAttrAccount as String: account,
            kSecReturnData as String: true
        ]
        var item: CFTypeRef?
        let result = SecItemCopyMatching(query as CFDictionary, &item)
        if result == errSecItemNotFound { return nil }
        guard result == errSecSuccess else { throw MeshSecureChatError.keyStore }
        return item as? Data
    }

    private func keychainWriteDraft(_ data: Data, account: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "org.nexora.mesh.drafts.v1",
            kSecAttrAccount as String: account
        ]
        let update: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        let result = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if result == errSecItemNotFound {
            var insert = query
            update.forEach { insert[$0.key] = $0.value }
            let added = SecItemAdd(insert as CFDictionary, nil)
            guard added == errSecSuccess else { throw MeshSecureChatError.keyStore }
        } else if result != errSecSuccess {
            throw MeshSecureChatError.keyStore
        }
    }

    private func persistRetryCounts() {
        defaults.set(retryCounts, forKey: retryKey)
    }

    private func scheduleRetry(id: String, attempt: Int) {
        guard retryScheduled.insert(id).inserted else { return }
        let currentAttempt = max(attempt, retryCounts[id] ?? attempt)
        guard currentAttempt <= 8 else {
            retryScheduled.remove(id)
            status = "Teslim onayı yok; şifreli mesaj kalıcı kuyrukta tutuluyor."
            return
        }
        let delay = min(2.0 * pow(2.0, Double(currentAttempt - 1)), 300.0)
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            guard let self else { return }
            self.retryScheduled.remove(id)
            guard let item = self.loadPending().first(where: { $0.id == id }) else {
                self.retryCounts.removeValue(forKey: id)
                self.persistRetryCounts()
                return
            }
            guard let sendPacket = self.sendPacket else {
                self.scheduleRetry(id: id, attempt: currentAttempt)
                return
            }
            sendPacket(item.frame)
            self.retryCounts[id] = currentAttempt + 1
            self.persistRetryCounts()
            self.scheduleRetry(id: id, attempt: currentAttempt + 1)
        }
    }

    private func restorePendingMessages() {
        messages = loadPending().compactMap { item in
            guard let frame = try? JSONSerialization.jsonObject(with: item.frame) as? [String: Any],
                  let nonce = frame["n"] as? String, let ciphertext = frame["c"] as? String else { return nil }
            _ = nonce; _ = ciphertext
            return Message(id: item.id, text: "Bekleyen şifreli mesaj", outgoing: true,
                           createdAt: Date(), delivery: "Teslim bekleniyor",
                           recipientFingerprint: item.recipientFingerprint)
        }
    }

    /// Pending frames are end-to-end ciphertext; Keychain protects durable queue state at rest.
    private func loadPending() -> [Pending] {
        let data = (try? secureStoreRead(account: pendingKey)) ?? defaults.data(forKey: pendingKey)
        guard let data, let decoded = try? JSONDecoder().decode([Pending].self, from: data) else { return [] }
        if (try? secureStoreWrite(data, account: pendingKey)) != nil { defaults.removeObject(forKey: pendingKey) }
        return decoded
    }

    private func savePending(_ pending: [Pending]) throws {
        try secureStoreWrite(JSONEncoder().encode(pending), account: pendingKey)
        defaults.removeObject(forKey: pendingKey)
    }

    private func loadReceivedIDs() -> [String] {
        let data = (try? secureStoreRead(account: receivedKey)) ?? defaults.data(forKey: receivedKey)
        guard let data, let ids = try? JSONDecoder().decode([String].self, from: data) else {
            return defaults.stringArray(forKey: receivedKey) ?? []
        }
        if (try? secureStoreWrite(data, account: receivedKey)) != nil { defaults.removeObject(forKey: receivedKey) }
        return ids
    }

    private func saveReceivedIDs(_ ids: [String]) throws {
        try secureStoreWrite(JSONEncoder().encode(ids), account: receivedKey)
        defaults.removeObject(forKey: receivedKey)
    }

    private func secureStoreRead(account: String) throws -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "org.nexora.mesh.private-state.v1",
            kSecAttrAccount as String: account,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly,
            kSecReturnData as String: true
        ]
        var item: CFTypeRef?
        let result = SecItemCopyMatching(query as CFDictionary, &item)
        if result == errSecItemNotFound { return nil }
        guard result == errSecSuccess, let data = item as? Data else { throw MeshSecureChatError.keyStore }
        return data
    }

    private func secureStoreWrite(_ data: Data, account: String) throws {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: "org.nexora.mesh.private-state.v1",
            kSecAttrAccount as String: account
        ]
        let update: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        ]
        let result = SecItemUpdate(query as CFDictionary, update as CFDictionary)
        if result == errSecItemNotFound {
            var insert = query
            update.forEach { insert[$0.key] = $0.value }
            guard SecItemAdd(insert as CFDictionary, nil) == errSecSuccess else { throw MeshSecureChatError.keyStore }
        } else if result != errSecSuccess {
            throw MeshSecureChatError.keyStore
        }
    }


    static func acceptMessageID(_ id: String, seen: [String]) -> Bool {
        UUID(uuidString: id) != nil && !seen.contains(id)
    }

    /// Experimental v1 compatibility KDF. This is not a reviewed Noise/HKDF protocol.
    static func deriveSessionKey(sharedSecret: Data) -> SymmetricKey {
        let kdfKey = SymmetricKey(data: Data("NexoraMesh/session-key/v1".utf8))
        return SymmetricKey(data: Data(HMAC<SHA256>.authenticationCode(for: sharedSecret, using: kdfKey)))
    }

    static func fingerprint(_ key: Data) -> String {
        SHA256.hash(data: key).prefix(20).map { String(format: "%02X", $0) }.joined(separator: ":")
    }

    static func seal(_ plaintext: Data, key: SymmetricKey, aad: Data) throws -> (nonce: Data, ciphertext: Data) {
        let nonce = AES.GCM.Nonce()
        let sealed = try AES.GCM.seal(plaintext, using: key, nonce: nonce, authenticating: aad)
        return (Data(nonce), sealed.ciphertext + sealed.tag)
    }

    static func open(_ frame: [String: Any], key: SymmetricKey, aad: Data) throws -> Data {
        guard let n = frame["n"] as? String, let c = frame["c"] as? String,
              let nonce = Data(base64Encoded: n), let ciphertext = Data(base64Encoded: c) else {
            throw MeshSecureChatError.invalidFrame
        }
        return try open(nonce: nonce, ciphertext: ciphertext, key: key, aad: aad)
    }

    static func open(nonce: Data, ciphertext: Data, key: SymmetricKey, aad: Data) throws -> Data {
        guard nonce.count == 12, ciphertext.count >= 16 else { throw MeshSecureChatError.invalidFrame }
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce),
                                        ciphertext: ciphertext.dropLast(16), tag: ciphertext.suffix(16))
        return try AES.GCM.open(box, using: key, authenticating: aad)
    }
}

enum MeshSecureChatError: Error {
    case invalidContact, fingerprintMismatch, keyStore, invalidProof, noSession, invalidFrame
}
