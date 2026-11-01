import SwiftUI
import VisionKit
import AVFoundation

/// Native camera QR scanner for Nexora Mesh identity payloads.
/// The caller must still verify the fingerprint before trusting the contact.
struct QRScannerSheet: UIViewControllerRepresentable {
    let onPayload: (String) -> Void
    let onError: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onPayload: onPayload, onError: onError) }

    func makeUIViewController(context: Context) -> UIViewController {
        guard DataScannerViewController.isSupported,
              DataScannerViewController.isAvailable else {
            let controller = UIViewController()
            controller.view.backgroundColor = .black
            let label = UILabel()
            label.text = "Bu cihazda canlı QR tarama kullanılamıyor."
            label.textColor = .white
            label.numberOfLines = 0
            label.textAlignment = .center
            label.translatesAutoresizingMaskIntoConstraints = false
            controller.view.addSubview(label)
            NSLayoutConstraint.activate([
                label.leadingAnchor.constraint(equalTo: controller.view.leadingAnchor, constant: 24),
                label.trailingAnchor.constraint(equalTo: controller.view.trailingAnchor, constant: -24),
                label.centerYAnchor.constraint(equalTo: controller.view.centerYAnchor)
            ])
            DispatchQueue.main.async { onError("Canlı QR tarama bu cihazda kullanılamıyor.") }
            return controller
        }

        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .accurate,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        do {
            try scanner.startScanning()
        } catch {
            DispatchQueue.main.async { onError("Kamera taraması başlatılamadı. Kamera iznini kontrol et.") }
        }
        return scanner
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        private let onPayload: (String) -> Void
        private let onError: (String) -> Void
        private var didRead = false

        init(onPayload: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
            self.onPayload = onPayload
            self.onError = onError
        }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !didRead else { return }
            for item in addedItems {
                guard case .barcode(let barcode) = item,
                      let payload = barcode.payloadStringValue,
                      payload.hasPrefix("NEXORAMESH:") else { continue }
                didRead = true
                dataScanner.stopScanning()
                DispatchQueue.main.async { self.onPayload(payload) }
                return
            }
        }
    }
}
