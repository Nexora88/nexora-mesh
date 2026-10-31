import SwiftUI
import CoreImage.CIFilterBuiltins
import UIKit
import VisionKit

private enum MeshPalette {
    static let background = Color(red: 7 / 255, green: 17 / 255, blue: 15 / 255)
    static let surface = Color(red: 16 / 255, green: 32 / 255, blue: 28 / 255)
    static let green = Color(red: 101 / 255, green: 242 / 255, blue: 173 / 255)
    static let muted = Color(red: 145 / 255, green: 166 / 255, blue: 157 / 255)
}

@main
struct NexoraMeshApp: App {
    var body: some Scene {
        WindowGroup {
            MeshRootView()
                .preferredColorScheme(.dark)
        }
    }
}

private enum MeshTab: String, CaseIterable {
    case chats = "Sohbetler"
    case devices = "Cihazlar"
    case profile = "Profil"

    var symbol: String {
        switch self {
        case .chats: return "bubble.left.and.bubble.right"
        case .devices: return "dot.radiowaves.left.and.right"
        case .profile: return "person.crop.circle"
        }
    }
}

private struct ChatItem: Identifiable {
    let id: String
    let title: String
    let detail: String
    let initials: String
}

struct MeshRootView: View {
    @StateObject private var bleTransport = MeshBLETransport()
    @StateObject private var secureChat = MeshSecureChat()
    @State private var selectedTab: MeshTab = .chats
    @State private var selectedChat: ChatItem?
    @State private var qrPayloadInput = ""
    @State private var qrTrustStatus = ""
    @State private var showQRScanner = false
    @State private var relayStore = MeshRelayStore()
    @State private var relayOptIn = false


    var body: some View {
        ZStack {
            MeshPalette.background.ignoresSafeArea()
            VStack(spacing: 0) {
                header
                if let chat = selectedChat {
                    ChatPrototypeView(chat: chat, secureChat: secureChat) { selectedChat = nil }
                } else {
                    Group {
                        switch selectedTab {
                        case .chats: chatsView
                        case .devices: devicesView
                        case .profile: profileView
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    bottomBar
                }
            }
        }
        .sheet(isPresented: $showQRScanner) {
            QRScannerView { payload in
                qrPayloadInput = payload
                showQRScanner = false
                do {
                    try secureChat.trustQRPayload(payload)
                    qrTrustStatus = secureChat.status
                } catch {
                    qrTrustStatus = "QR reddedildi: biçim veya parmak izi doğrulanamadı."
                }
            }
        }
        .preferredColorScheme(.dark)
        .onAppear {
            relayOptIn = relayStore.isOptedIn
            bleTransport.onPacket = { packet in
                DispatchQueue.main.async { secureChat.receive(packet) }
            }
        }
        .onChange(of: bleTransport.status) { _, newStatus in
            if newStatus.contains("BLE veri kanalı hazır") || newStatus.contains("Bir BLE istemcisi bildirimlere abone oldu") {
                secureChat.attach { packet in bleTransport.send(envelope: packet) }
            }
        }
    }

    private var header: some View {
        HStack(spacing: 12) {
            Image(systemName: "dot.radiowaves.left.and.right")
                .font(.title2.bold())
                .foregroundStyle(MeshPalette.background)
                .frame(width: 44, height: 44)
                .background(MeshPalette.green, in: RoundedRectangle(cornerRadius: 14))
            VStack(alignment: .leading, spacing: 3) {
                Text("NEXORA MESH")
                    .font(.headline.weight(.black))
                    .tracking(1.2)
                    .foregroundStyle(MeshPalette.green)
                Text("İnternetsiz iletişim vizyonu")
                    .font(.caption)
                    .foregroundStyle(MeshPalette.muted)
            }
            Spacer()
            Text("PROTOTİP")
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(MeshPalette.green)
                .padding(.horizontal, 10)
                .padding(.vertical, 7)
                .background(MeshPalette.surface, in: Capsule())
        }
        .padding(.horizontal, 20)
        .padding(.top, 16)
        .padding(.bottom, 18)
    }

    private var chatsView: some View {
        VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                Text("Sohbetler").font(.largeTitle.bold()).foregroundStyle(.white)
                Spacer()
                Text("\(secureChat.trustedContacts.count) güvenilir kişi")
                    .font(.caption).foregroundStyle(MeshPalette.muted)
            }
            if secureChat.trustedContacts.isEmpty {
                infoCard("Önce bir cihazı tanı", "Profil sekmesinden QR kodunu kamerayla tara. Parmak izi doğrulandıktan sonra gerçek sohbet burada açılabilir.", symbol: "qrcode.viewfinder")
                Spacer()
            } else {
                Text("Mesajlar yalnızca seçtiğin QR doğrulamalı kişiyle güvenli oturum kurulduğunda gönderilir.")
                    .font(.subheadline).foregroundStyle(MeshPalette.muted)
                ForEach(secureChat.trustedContacts, id: \.fingerprint) { contact in
                    let chat = ChatItem(
                        id: contact.fingerprint,
                        title: contact.label,
                        detail: "QR doğrulandı · \(String(contact.fingerprint.prefix(14)))…",
                        initials: String(contact.label.prefix(2)).uppercased()
                    )
                    Button {
                        secureChat.selectRecipient(contact.fingerprint)
                        selectedChat = chat
                    } label: {
                        HStack(spacing: 12) {
                            ZStack {
                                Circle().fill(Color(red: 28 / 255, green: 69 / 255, blue: 53 / 255))
                                Text(chat.initials).font(.headline.bold()).foregroundStyle(MeshPalette.green)
                            }
                            .frame(width: 48, height: 48)
                            VStack(alignment: .leading, spacing: 5) {
                                Text(contact.label).font(.headline).foregroundStyle(.white)
                                Text(chat.detail).font(.caption2.monospaced()).foregroundStyle(MeshPalette.muted)
                            }
                            Spacer()
                            Image(systemName: "lock.shield").foregroundStyle(MeshPalette.green)
                        }
                        .padding(14)
                        .background(MeshPalette.surface, in: RoundedRectangle(cornerRadius: 18))
                    }
                    .buttonStyle(.plain)
                }
                Spacer()
            }
        }
        .padding(.horizontal, 18)
        .padding(.top, 8)
    }

    private var devicesView: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Cihazlar").font(.largeTitle.bold()).foregroundStyle(.white)
            infoCard("BLE GATT taşıması", "Bu ekran ortak BLE GATT servisinde cihaz keşfi ve bağlantı prototipini kullanır. Bağlantı tek başına kişi kimliği veya uçtan uca şifreleme doğrulaması değildir.", symbol: "antenna.radiowaves.left.and.right")
            Button("Yakındaki BLE cihazlarını tara") { bleTransport.startDiscovery() }
                .buttonStyle(.borderedProminent)
                .tint(MeshPalette.green)
                .foregroundStyle(MeshPalette.background)
            Text(bleTransport.status)
                .font(.caption)
                .foregroundStyle(MeshPalette.muted)
            ForEach(Array(bleTransport.discoveredNames.keys), id: \.self) { id in
                Button {
                    bleTransport.connect(to: id)
                } label: {
                    HStack {
                        Image(systemName: "dot.radiowaves.left.and.right")
                            .foregroundStyle(MeshPalette.green)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(bleTransport.discoveredNames[id] ?? "Nexora Mesh cihazı")
                                .foregroundStyle(.white)
                            Text(id.uuidString)
                                .font(.caption2)
                                .foregroundStyle(MeshPalette.muted)
                        }
                        Spacer()
                        Text("BAĞLAN")
                            .font(.caption2.bold())
                            .foregroundStyle(MeshPalette.green)
                    }
                    .padding(12)
                    .background(MeshPalette.surface, in: RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
            }
            infoCard("Mesajlaşma güvenliği", "Bu GATT katmanı taşıma prototipidir. Bu ekran henüz sohbet mesajlarını Rust zarfına bağlamaz; QR doğrulaması ve uygulama katmanı şifreleme zorunlu olmadan özel mesaj gönderimi yapılmamalıdır.", symbol: "lock.shield")
            Spacer()
        }
        .padding(.horizontal, 18)
        .padding(.top, 8)
    }

    private var profileView: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("Güvenlik ve afet iletişimi").font(.largeTitle.bold()).foregroundStyle(.white)
            infoCard("Afetlerde dayanıklılık", "Orman yangını, deprem, sel ve dağ arama-kurtarma ekipleri için internet kesilse de yerel iletişim hedefleniyor. Bu uygulama şu an acil durum haberleşme aracı değildir.", symbol: "cross.case")
            infoCard("Menzil", "Bluetooth tek başına uzak mesafeyi garanti etmez. Daha geniş kapsama için Wi-Fi Direct ve izinli ara cihazlar üzerinden çok atlamalı aktarım araştırılacak.", symbol: "point.3.connected.trianglepath.dotted")
            infoCard("Platformlar", "iOS arka plan BLE davranışı iOS tarafından zamanlanır. Uygulama sürekli tarama veya kesintisiz yayın garantisi veremez.", symbol: "iphone.and.arrow.forward")
            VStack(alignment: .leading, spacing: 10) {
                Toggle(isOn: $relayOptIn) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Bu cihazın şifreli paketleri saklamasına izin ver").foregroundStyle(.white)
                        Text("Yalnızca doğrulanmış Rust zarfları Keychain'de saklanır. Canlı A→B→C aktarımı henüz tamamlanmadı. Kuyruk: \\(relayStore.pendingCount())")
                            .font(.caption).foregroundStyle(MeshPalette.muted)
                    }
                }
                .tint(MeshPalette.green)
                .onChange(of: relayOptIn) { _, enabled in relayStore.isOptedIn = enabled }
            }
            .padding(16)
            .background(MeshPalette.surface, in: RoundedRectangle(cornerRadius: 18))
            VStack(alignment: .leading, spacing: 10) {
                Text("Kimlik ve QR güveni").font(.headline).foregroundStyle(.white)
                Text("Bu cihazın QR içeriğini karşı tarafa aktar. Diğer kişinin QR içeriğini buraya yapıştırıp parmak izini doğrulayarak güvenilir kişi olarak ekle.")
                    .font(.caption).foregroundStyle(MeshPalette.muted)
                if let payload = try? secureChat.localQRPayload() {
                    QRCodeView(payload: payload).frame(width: 160, height: 160).frame(maxWidth: .infinity)
                    Button("Kendi QR içeriğini kopyala") {
                        UIPasteboard.general.string = payload
                    }.buttonStyle(.borderedProminent).tint(MeshPalette.green).foregroundStyle(MeshPalette.background)
                }
                Button {
                    guard DataScannerViewController.isSupported, DataScannerViewController.isAvailable else {
                        qrTrustStatus = "Bu cihazda canlı QR tarama kullanılamıyor; QR içeriğini yapıştırabilirsin."
                        return
                    }
                    showQRScanner = true
                } label: {
                    Label("QR kodunu kamerayla tara", systemImage: "qrcode.viewfinder")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(MeshPalette.green)
                .foregroundStyle(MeshPalette.background)
                TextField("NEXORAMESH: QR içeriğini yapıştır", text: $qrPayloadInput, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                Button("QR kimliğini doğrula ve kaydet") {
                    do {
                        try secureChat.trustQRPayload(qrPayloadInput.trimmingCharacters(in: .whitespacesAndNewlines))
                        qrTrustStatus = "Kimlik doğrulandı ve güvenilir kişi listesine eklendi."
                        qrPayloadInput = ""
                    } catch {
                        qrTrustStatus = "QR reddedildi: içerik veya parmak izi geçersiz."
                    }
                }
                .buttonStyle(.borderedProminent).tint(MeshPalette.green).foregroundStyle(MeshPalette.background)
                Text(qrTrustStatus.isEmpty ? secureChat.status : qrTrustStatus)
                    .font(.caption).foregroundStyle(MeshPalette.muted)
                ForEach(secureChat.trustedContacts, id: \.fingerprint) { contact in
                    VStack(alignment: .leading, spacing: 3) {
                        Text(contact.label).foregroundStyle(.white)
                        Text(contact.fingerprint).font(.system(.caption2, design: .monospaced)).foregroundStyle(MeshPalette.green)
                    }.padding(10).frame(maxWidth: .infinity, alignment: .leading)
                     .background(MeshPalette.background, in: RoundedRectangle(cornerRadius: 10))
                }
            }
            .padding(16)
            .background(MeshPalette.surface, in: RoundedRectangle(cornerRadius: 18))
            Spacer()
        }
        .padding(.horizontal, 18)
        .padding(.top, 8)
    }

    private func infoCard(_ title: String, _ detail: String, symbol: String) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: symbol)
                .font(.title3)
                .foregroundStyle(MeshPalette.green)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 6) {
                Text(title).font(.headline).foregroundStyle(.white)
                Text(detail).font(.subheadline).foregroundStyle(MeshPalette.muted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(MeshPalette.surface, in: RoundedRectangle(cornerRadius: 18))
    }

    private var bottomBar: some View {
        HStack(spacing: 0) {
            ForEach(MeshTab.allCases, id: \.self) { tab in
                Button { selectedTab = tab } label: {
                    VStack(spacing: 5) {
                        Image(systemName: tab.symbol).font(.system(size: 19, weight: .semibold))
                        Text(tab.rawValue).font(.caption2.weight(.medium))
                    }
                    .foregroundStyle(selectedTab == tab ? MeshPalette.green : MeshPalette.muted)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                }
                .buttonStyle(.plain)
            }
        }
        .background(MeshPalette.surface)
    }
}

private struct ChatPrototypeView: View {
    let chat: ChatItem
    @ObservedObject var secureChat: MeshSecureChat
    let onBack: () -> Void
    @State private var draft = ""

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button("‹", action: onBack).font(.largeTitle).foregroundStyle(MeshPalette.green)
                VStack(alignment: .leading) {
                    Text(chat.title).font(.headline).foregroundStyle(.white)
                    Text(secureChat.isSecure ? "QR doğrulamalı şifreli oturum" : "Güvenli oturum bekleniyor")
                        .font(.caption).foregroundStyle(MeshPalette.muted)
                }
                Spacer()
                Image(systemName: secureChat.isSecure ? "lock.fill" : "lock.open")
                    .foregroundStyle(secureChat.isSecure ? MeshPalette.green : MeshPalette.muted)
            }
            .padding()
            .background(MeshPalette.surface)
            VStack(alignment: .leading, spacing: 6) {
                Text(secureChat.status)
                    .font(.caption).foregroundStyle(MeshPalette.muted)
                Picker("Güvenilir alıcı", selection: Binding(
                    get: { secureChat.selectedRecipientFingerprint ?? "" },
                    set: { fingerprint in
                        secureChat.selectRecipient(fingerprint.isEmpty ? nil : fingerprint)
                        draft = secureChat.loadDraft(for: fingerprint.isEmpty ? nil : fingerprint)
                    }
                )) {
                    Text("Alıcı seç").tag("")
                    ForEach(secureChat.trustedContacts, id: \.fingerprint) { contact in
                        Text("\(contact.label) · \(String(contact.fingerprint.prefix(8)))").tag(contact.fingerprint)
                    }
                }
                .pickerStyle(.menu)
                Text(secureChat.draftStatus)
                    .font(.caption2).foregroundStyle(MeshPalette.muted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal).padding(.top, 8)
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(secureChat.messages.filter { message in
                        guard let selected = secureChat.selectedRecipientFingerprint,
                              let recipient = message.recipientFingerprint else { return false }
                        return recipient.caseInsensitiveCompare(selected) == .orderedSame
                    }) { message in
                        VStack(alignment: message.outgoing ? .trailing : .leading, spacing: 4) {
                            Text(message.text)
                                .foregroundStyle(.white)
                                .padding(12)
                                .frame(maxWidth: .infinity, alignment: message.outgoing ? .trailing : .leading)
                                .background(message.outgoing ? Color(red: 25 / 255, green: 72 / 255, blue: 52 / 255) : MeshPalette.surface,
                                            in: RoundedRectangle(cornerRadius: 14))
                            Text(message.delivery)
                                .font(.caption2).foregroundStyle(MeshPalette.muted)
                        }
                    }
                }
                .padding()
            }
            HStack {
                TextField("Çevrimdışı taslak / şifreli mesaj…", text: $draft, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                    .onChange(of: draft) { _, value in
                        secureChat.saveDraft(value, for: secureChat.selectedRecipientFingerprint)
                    }
                Button {
                    guard secureChat.selectedRecipientFingerprint != nil else { return }
                    secureChat.sendText(draft)
                    if secureChat.isSecure { draft = ""; secureChat.saveDraft("", for: secureChat.selectedRecipientFingerprint) }
                } label: {
                    Image(systemName: "paperplane.fill")
                        .foregroundStyle(MeshPalette.background)
                        .padding(12)
                        .background(secureChat.isSecure ? MeshPalette.green : MeshPalette.muted, in: Circle())
                }
                .disabled(secureChat.selectedRecipientFingerprint == nil || draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding()
        }
        .onAppear {
            draft = secureChat.loadDraft(for: secureChat.selectedRecipientFingerprint)
        }
    }
}

private struct QRCodeView: View {
    let payload: String
    private let context = CIContext()
    private let filter = CIFilter.qrCodeGenerator()

    var body: some View {
        Group {
            if let image = makeImage() {
                Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
                    .padding(8).background(.white, in: RoundedRectangle(cornerRadius: 12))
            } else {
                Image(systemName: "qrcode").resizable().scaledToFit().foregroundStyle(MeshPalette.muted)
            }
        }
    }

    private func makeImage() -> UIImage? {
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let transform = CGAffineTransform(scaleX: 4, y: 4)
        guard let cgImage = context.createCGImage(output.transformed(by: transform), from: output.extent.applying(transform)) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}


private struct QRScannerView: UIViewControllerRepresentable {
    let onRecognized: (String) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onRecognized: onRecognized) }

    func makeUIViewController(context: Context) -> DataScannerViewController {
        let scanner = DataScannerViewController(
            recognizedDataTypes: [.barcode(symbologies: [.qr])],
            qualityLevel: .balanced,
            recognizesMultipleItems: false,
            isHighFrameRateTrackingEnabled: false,
            isPinchToZoomEnabled: true,
            isGuidanceEnabled: true,
            isHighlightingEnabled: true
        )
        scanner.delegate = context.coordinator
        do { try scanner.startScanning() } catch { }
        return scanner
    }

    func updateUIViewController(_ uiViewController: DataScannerViewController, context: Context) {}

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        private let onRecognized: (String) -> Void
        private var didReturn = false

        init(onRecognized: @escaping (String) -> Void) { self.onRecognized = onRecognized }

        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            guard !didReturn else { return }
            for item in addedItems {
                if case .barcode(let barcode) = item, let value = barcode.payloadStringValue,
                   value.hasPrefix("NEXORAMESH:") {
                    didReturn = true
                    onRecognized(value)
                    return
                }
            }
        }
    }
}
