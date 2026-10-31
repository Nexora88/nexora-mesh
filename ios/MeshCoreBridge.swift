import Foundation
import MeshCore

/// Thin Swift wrapper around the Rust C ABI. It accepts complete, bounded envelopes only.
/// This layer does not encrypt messages or authenticate the connected Bluetooth peer.
enum MeshCoreBridge {
    static let maxEnvelopeBytes = 256 * 1024

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
