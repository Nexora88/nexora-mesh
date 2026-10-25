import SwiftUI

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
    let id = UUID()
    let title: String
    let detail: String
    let initials: String
}

struct MeshRootView: View {
    @StateObject private var bleTransport = MeshBLETransport()
    @State private var selectedTab: MeshTab = .chats
    @State private var selectedChat: ChatItem?

    private let chats = [
        ChatItem(title: "Yerel notlar", detail: "Nexora Mesh'e hoş geldin", initials: "NM"),
        ChatItem(title: "Güvenilir cihazlar", detail: "Eşleştirilmiş cihazların burada görünür", initials: "GC")
    ]

    var body: some View {
        ZStack {
            MeshPalette.background.ignoresSafeArea()
            VStack(spacing: 0) {
                header
                if let chat = selectedChat {
                    ChatPrototypeView(chat: chat) { selectedChat = nil }
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
        .preferredColorScheme(.dark)
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
            Text("Sohbetler")
                .font(.largeTitle.bold())
                .foregroundStyle(.white)
            Text("Buradaki örnek sohbetler henüz başka cihaza mesaj göndermez.")
                .font(.subheadline)
                .foregroundStyle(MeshPalette.muted)
            ForEach(chats) { chat in
                Button { selectedChat = chat } label: {
                    HStack(spacing: 12) {
                        Text(chat.initials)
                            .font(.headline.bold())
                            .foregroundStyle(MeshPalette.green)
                            .frame(width: 48, height: 48)
                            .background(Color(red: 28 / 255, green: 69 / 255, blue: 53 / 255), in: Circle())
                        VStack(alignment: .leading, spacing: 5) {
                            Text(chat.title).font(.headline).foregroundStyle(.white)
                            Text(chat.detail).font(.caption).foregroundStyle(MeshPalette.muted)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(MeshPalette.muted)
                    }
                    .padding(14)
                    .background(MeshPalette.surface, in: RoundedRectangle(cornerRadius: 18))
                }
                .buttonStyle(.plain)
            }
            Spacer()
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
            infoCard("Platformlar", "Android ilk geliştirme hedefi. Bu SwiftUI dosyası Apple platformları için başlangıç arayüzüdür; Xcode projesi, radyo katmanı ve iOS cihaz testleri ayrıca tamamlanmalıdır.", symbol: "iphone.and.arrow.forward")
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
    let onBack: () -> Void
    @State private var draft = ""
    @State private var localMessages: [String] = ["Bu ekran bir arayüz prototipidir; mesajlar başka cihaza iletilmez."]

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button("‹", action: onBack).font(.largeTitle).foregroundStyle(MeshPalette.green)
                VStack(alignment: .leading) {
                    Text(chat.title).font(.headline).foregroundStyle(.white)
                    Text("Yerel prototip · bağlantı yok").font(.caption).foregroundStyle(MeshPalette.muted)
                }
                Spacer()
            }
            .padding()
            .background(MeshPalette.surface)
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    ForEach(Array(localMessages.enumerated()), id: \.offset) { _, message in
                        Text(message)
                            .foregroundStyle(.white)
                            .padding(12)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .background(MeshPalette.surface, in: RoundedRectangle(cornerRadius: 14))
                    }
                }
                .padding()
            }
            Text("Gönder düğmesi yalnızca bu ekrana yerel metin ekler.")
                .font(.caption2)
                .foregroundStyle(MeshPalette.muted)
                .padding(.horizontal)
            HStack {
                TextField("Mesaj yaz…", text: $draft, axis: .vertical)
                    .textFieldStyle(.roundedBorder)
                Button {
                    guard !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                    localMessages.append(draft)
                    draft = ""
                } label: {
                    Image(systemName: "paperplane.fill")
                        .foregroundStyle(MeshPalette.background)
                        .padding(12)
                        .background(MeshPalette.green, in: Circle())
                }
            }
            .padding()
        }
    }
}
