import XCTest
import CryptoKit
@testable import NexoraMesh

final class MeshSecureChatCryptoTests: XCTestCase {
    func testSharedV1KDFVectorMatchesAndroidContract() {
        let secret = Data((0..<32).map { UInt8($0) })
        let expected = "f686564b5419341dd9eaa8bcfa472c607e046069d08e66243c97a0fa180d0162"
        let key = MeshSecureChat.deriveSessionKey(sharedSecret: secret)
        let actual = key.withUnsafeBytes { Data($0).map { String(format: "%02x", $0) }.joined() }
        XCTAssertEqual(actual, expected)
    }

    func testFingerprintVectorMatchesAndroidContract() {
        XCTAssertEqual(
            MeshSecureChat.fingerprint(Data([0x30, 0x00])),
            "E4:F6:0D:0A:A6:D7:F3:D3:B6:A6:49:4B:1C:86:1B:99:F6:49:C6:F9"
        )
    }

    func testTamperedCiphertextIsRejected() throws {
        let key = SymmetricKey(data: Data(repeating: 7, count: 32))
        let aad = Data("NexoraMesh/data/v1:test".utf8)
        let sealed = try MeshSecureChat.seal(Data("hello".utf8), key: key, aad: aad)
        var tampered = sealed.ciphertext
        tampered[0] ^= 0x01
        XCTAssertThrowsError(try MeshSecureChat.open(
            nonce: sealed.nonce, ciphertext: tampered, key: key, aad: aad
        ))
    }

    func testWrongAssociatedDataIsRejected() throws {
        let key = SymmetricKey(data: Data(repeating: 9, count: 32))
        let sealed = try MeshSecureChat.seal(
            Data("hello".utf8), key: key, aad: Data("recipient-A".utf8)
        )
        XCTAssertThrowsError(try MeshSecureChat.open(
            nonce: sealed.nonce, ciphertext: sealed.ciphertext, key: key,
            aad: Data("recipient-B".utf8)
        ))
    }

    func testMalformedNonceIsRejected() {
        let key = SymmetricKey(data: Data(repeating: 1, count: 32))
        XCTAssertThrowsError(try MeshSecureChat.open(
            nonce: Data([1, 2]), ciphertext: Data(repeating: 0, count: 16),
            key: key, aad: Data()
        ))
    }

    func testReplayAndMalformedMessageIDsAreRejected() {
        let id = "123e4567-e89b-12d3-a456-426614174000"
        XCTAssertTrue(MeshSecureChat.acceptMessageID(id, seen: []))
        XCTAssertFalse(MeshSecureChat.acceptMessageID(id, seen: [id]))
        XCTAssertFalse(MeshSecureChat.acceptMessageID("not-a-uuid", seen: []))
    }

    func testRustPowerPolicyUsesDeepSleepForLowBatteryBackgroundState() {
        let interval = MeshCoreBridge.recommendedScanIntervalSeconds(
            batteryPercent: 5, charging: false, lowPowerMode: true,
            appForeground: false, recentlyConnected: false, urgentPending: false
        )
        XCTAssertEqual(interval, 300)
        XCTAssertFalse(MeshCoreBridge.mayRelay(
            optedIn: true, batteryPercent: 5, charging: false,
            lowPowerMode: true, appForeground: false
        ))
    }

    func testRustPowerPolicyRequiresExplicitRelayOptIn() {
        XCTAssertFalse(MeshCoreBridge.mayRelay(
            optedIn: false, batteryPercent: 90, charging: false,
            lowPowerMode: false, appForeground: true
        ))
        XCTAssertTrue(MeshCoreBridge.mayRelay(
            optedIn: true, batteryPercent: 90, charging: false,
            lowPowerMode: false, appForeground: true
        ))
    }


    func testRustStoreAckBridgeAuthenticatesHopAndRejectsTampering() {
        let messageID = Data(repeating: 0x07, count: 16)
        let fromNode = Data(repeating: 0x08, count: 16)
        let toNode = Data(repeating: 0x09, count: 16)
        let nonce = Data(repeating: 0x0A, count: 16)
        let hopKey = Data(repeating: 0x0B, count: 32)
        let ack = MeshCoreBridge.createStoreAck(
            messageID: messageID, fromNode: fromNode, toNode: toNode,
            storedAtUnixSeconds: 100, nonce: nonce, hopKey: hopKey
        )
        XCTAssertNotNil(ack)
        guard let ack else { return }
        XCTAssertTrue(MeshCoreBridge.verifyStoreAck(
            ack, hopKey: hopKey, expectedFrom: fromNode, expectedTo: toNode,
            nowUnixSeconds: 101, maxAgeSeconds: 30
        ))
        XCTAssertFalse(MeshCoreBridge.verifyStoreAck(
            ack, hopKey: hopKey, expectedFrom: toNode, expectedTo: fromNode,
            nowUnixSeconds: 101, maxAgeSeconds: 30
        ))
        var tampered = ack
        tampered[tampered.count - 1] ^= 0x01
        XCTAssertFalse(MeshCoreBridge.verifyStoreAck(
            tampered, hopKey: hopKey, expectedFrom: fromNode, expectedTo: toNode,
            nowUnixSeconds: 101, maxAgeSeconds: 30
        ))
        XCTAssertNil(MeshCoreBridge.createStoreAck(
            messageID: messageID, fromNode: fromNode, toNode: toNode,
            storedAtUnixSeconds: 100, nonce: nonce, hopKey: Data(repeating: 1, count: 16)
        ))
    }

    func testMalformedWirePacketDoesNotCreateMessage() {
        let chat = MeshSecureChat()
        chat.receive(Data("not-json".utf8))
        XCTAssertTrue(chat.messages.isEmpty)
        XCTAssertTrue(chat.status.contains("Paket reddedildi"))
    }
}
