import Foundation
import MeshCore

/// Thin Swift wrapper around the Rust C ABI. It accepts complete, bounded envelopes only.
/// This layer does not encrypt messages or authenticate the connected Bluetooth peer.
enum MeshCoreBridge {
    static let maxEnvelopeBytes = 256 * 1024

    static func deriveHopKey(sharedSecret: Data, peerA: Data, peerB: Data, epoch: UInt64) -> Data? {
        guard sharedSecret.count >= 32, peerA.count == 16, peerB.count == 16 else { return nil }
        var output = Data(count: 32)
        let result = sharedSecret.withUnsafeBytes { secret in
            peerA.withUnsafeBytes { a in
                peerB.withUnsafeBytes { b in
                    output.withUnsafeMutableBytes { out in
                        nm_derive_hop_key(secret.bindMemory(to: UInt8.self).baseAddress, secret.count,
                            a.bindMemory(to: UInt8.self).baseAddress, b.bindMemory(to: UInt8.self).baseAddress,
                            epoch, out.bindMemory(to: UInt8.self).baseAddress, out.count)
                    }
                }
            }
        }
        return result == 32 ? output : nil
    }

    static func validate(_ envelope: Data) throws {
        guard !envelope.isEmpty, envelope.count <= maxEnvelopeBytes else {
            throw MeshCoreError.invalidLength
        }
        let result = envelope.withUnsafeBytes { rawBuffer in
            nm_wire_validate(rawBuffer.bindMemory(to: UInt8.self).baseAddress, rawBuffer.count)
        }
        guard result == 0 else { throw MeshCoreError.invalidEnvelope(Int32(result)) }
    }

    static func recommendedScanIntervalSeconds(
        batteryPercent: UInt8, charging: Bool, lowPowerMode: Bool,
        appForeground: Bool, recentlyConnected: Bool, urgentPending: Bool
    ) -> UInt64 {
        nm_relay_scan_interval_seconds(
            batteryPercent, charging ? 1 : 0, lowPowerMode ? 1 : 0,
            appForeground ? 1 : 0, recentlyConnected ? 1 : 0, urgentPending ? 1 : 0
        )
    }

    static func mayRelay(
        optedIn: Bool, batteryPercent: UInt8, charging: Bool,
        lowPowerMode: Bool, appForeground: Bool
    ) -> Bool {
        nm_relay_may_forward(
            optedIn ? 1 : 0, batteryPercent, charging ? 1 : 0,
            lowPowerMode ? 1 : 0, appForeground ? 1 : 0
        ) == 1
    }



    /// Creates a pairwise-key authenticated next-hop STORE_ACK using Rust.
    static func createStoreAck(messageID: Data, fromNode: Data, toNode: Data,
                               storedAtUnixSeconds: UInt64, nonce: Data,
                               hopKey: Data) -> Data? {
        guard messageID.count == 16, fromNode.count == 16, toNode.count == 16,
              nonce.count == 16, hopKey.count >= 32 else { return nil }
        var output = Data(count: 105)
        let written = messageID.withUnsafeBytes { idBytes in
            fromNode.withUnsafeBytes { fromBytes in
                toNode.withUnsafeBytes { toBytes in
                    nonce.withUnsafeBytes { nonceBytes in
                        hopKey.withUnsafeBytes { keyBytes in
                            output.withUnsafeMutableBytes { outputBytes in
                                nm_store_ack_create(
                                    idBytes.bindMemory(to: UInt8.self).baseAddress,
                                    fromBytes.bindMemory(to: UInt8.self).baseAddress,
                                    toBytes.bindMemory(to: UInt8.self).baseAddress,
                                    storedAtUnixSeconds,
                                    nonceBytes.bindMemory(to: UInt8.self).baseAddress,
                                    keyBytes.bindMemory(to: UInt8.self).baseAddress,
                                    keyBytes.count,
                                    outputBytes.bindMemory(to: UInt8.self).baseAddress,
                                    outputBytes.count
                                )
                            }
                        }
                    }
                }
            }
        }
        guard written == 105 else { return nil }
        return output
    }

    /// Verifies the Rust HMAC-SHA256 next-hop STORE_ACK against the expected peer IDs.
    /// The supplied hop key must be a pairwise secret of at least 32 bytes.
    static func verifyStoreAck(_ ack: Data, hopKey: Data, expectedFrom: Data,
                               expectedTo: Data, nowUnixSeconds: UInt64,
                               maxAgeSeconds: UInt64) -> Bool {
        guard ack.count == 105, hopKey.count >= 32,
              expectedFrom.count == 16, expectedTo.count == 16 else { return false }
        let result = ack.withUnsafeBytes { ackBytes in
            hopKey.withUnsafeBytes { keyBytes in
                expectedFrom.withUnsafeBytes { fromBytes in
                    expectedTo.withUnsafeBytes { toBytes in
                        nm_store_ack_verify(
                            ackBytes.bindMemory(to: UInt8.self).baseAddress,
                            ackBytes.count,
                            keyBytes.bindMemory(to: UInt8.self).baseAddress,
                            keyBytes.count,
                            fromBytes.bindMemory(to: UInt8.self).baseAddress,
                            toBytes.bindMemory(to: UInt8.self).baseAddress,
                            nowUnixSeconds,
                            maxAgeSeconds
                        )
                    }
                }
            }
        }
        return result == 0
    }

    static func forward(_ envelope: Data, nowUnixSeconds: UInt64) throws -> Data {
        try validate(envelope)
        var output = Data(count: envelope.count)
        let written: Int = envelope.withUnsafeBytes { inputBuffer in
            output.withUnsafeMutableBytes { outputBuffer in
                nm_wire_forward(
                    inputBuffer.bindMemory(to: UInt8.self).baseAddress,
                    inputBuffer.count,
                    outputBuffer.bindMemory(to: UInt8.self).baseAddress,
                    outputBuffer.count,
                    nowUnixSeconds
                )
            }
        }
        guard written > 0, written <= output.count else {
            throw MeshCoreError.forwardRejected(Int32(clamping: written))
        }
        return Data(output.prefix(written))
    }
}

enum MeshCoreError: Error {
    case invalidLength
    case invalidEnvelope(Int32)
    case forwardRejected(Int32)
}
