# Nexora Mesh

**Android ve Apple platformları için internet olmadan yakındaki cihazlarla iletişim kurmayı hedefleyen, afet dayanıklılığı odaklı bir mesajlaşma projesi.** Hedefimiz; orman yangını, deprem, sel ve dağ arama-kurtarma gibi bağlantının kesilebildiği koşullarda da işe yarayabilecek güvenilir bir iletişim markası oluşturmaktır.

> **Dürüst durum notu:** Android tarafında Bluetooth Classic RFCOMM doğrudan sohbet prototipi, QR/kişi güven akışı ve ECDH/AES-GCM ile dosya parçalama yapı taşları bulunuyor; bunlar bağımsız güvenlik denetiminden ve gerçek cihaz doğrulamasından geçmiş üretim protokolü değildir. Rust tarafına beş atlamalı zarf biçimi ve C ABI doğrulama/iletme giriş noktaları eklendi. Ancak C ABI henüz Android JNI veya iOS XCFramework olarak paketlenmedi; Android BLE GATT ve iOS CoreBluetooth radyo taşıması uygulanmadı. Gerçek üç cihaz A→B→C ve Android–iPhone testleri yapılmadı. Sesli/görüntülü arama ve özel codec yoktur. Acil durumlarda kullanıma hazır değildir.

## Güvenilir kişiler ve QR tarama durumu

Android uygulamasına QR tarayıcı ve yerel güvenilir kişi kaydı eklendi. Kaydetme ekranı, parmak izini ayrı ve güvenli bir kanaldan karşılaştırma uyarısı verir. Kişi kaydı şu an Bluetooth bağlantısını veya mesaj gönderimini engelleyen zorunlu bir güven kapısı değildir; ECDH/AES-GCM el sıkışması da mevcut Bluetooth satır protokolüne bağlanmış değildir. Bu nedenle uygulama düzeyinde uçtan uca şifreleme (E2EE) tamamlandı olarak değerlendirilmemelidir. Kimlik anahtarının uygulama yeniden başlatmaları arasında kalıcı tutulması da henüz yapılmadı.

## Android çevrimdışı bağlantı prototipi

İlk radyo taşıması Bluetooth Classic RFCOMM ile iki Android cihaz arasında doğrudan bağlantı kurar. Kullanıcı cihaz aramasını başlatır ve Android sistem eşleştirmesini onaylar; bu eşleştirme tek başına uygulama kimliği değildir.

Güvenlik sekmesinde cihaz kimliği kalıcıdır: özel anahtar, Android Keystore tarafından korunan AES-GCM anahtarıyla şifrelenerek yerel depoda tutulur. Kişi QR kodu taranıp açıkça kaydedilir. Bağlantı, karşı tarafın HELLO mesajındaki açık anahtarın kaydedilmiş QR parmak iziyle eşleşmesi olmadan mesaj göndermez. Eşleşme sonrası doğrudan metin ve 1–64 MiB dosyalar ECDH/AES-GCM kanalı üzerinden gönderilir; dosyalar 16 KiB parçalara bölünür ve alıcıda SHA-256 ile doğrulanır.

Bu, bağımsız güvenlik denetimi yapılmış bir mesajlaşma sistemi değildir. CI derlemesi fiziksel cihazda radyo bağlantısını kanıtlamaz. Şifreli kanalın üretim güvenliği ve protokol bağlaması ayrıca doğrulanmalıdır. Test adımları: [Android Bluetooth, E2EE ve medya test rehberi](docs/ANDROID_BLUETOOTH_TEST.md). APK indirme: [Android APK rehberi](docs/ANDROID_INSTALL.md).

**Sınırlar:** Aynı anda tek doğrudan Bluetooth Classic bağlantısı vardır. A→B→C gerçek çok atlamalı aktarım henüz Android taşımasına bağlı değildir. Dosya aktarımında devam ettirme ve teslim onayı yoktur. iOS CoreBluetooth/Android BLE ortak taşıması ve iPhone–Android fiziksel testi tamamlanmamıştır. Sesli/görüntülü arama, kalıcı sohbet geçmişi ve bağımsız güvenlik incelemesi yoktur.

## Vizyon

- Günlük mesajlaşma: sohbet listesi, güvenilir kişiler ve ileride dosya/ses/görüntülü arama.
- Çevrimdışı iletişim: internet sunucusu olmadan yerel radyo bağlantısı.
- Afet dayanıklılığı: saha testleri ve açık sınırlamalarla altyapı kesintilerinde iletişimi desteklemek.
- Android + Apple: önce Android radyo katmanı; iOS arayüzü ve ortak protokol.
- Menzili aşamalı genişletme: doğrudan bağlantıdan sonra izinli ara cihazlar ve çok atlamalı mesh.

## Cihaz keşfi ve güven

Gizlilik varsayılan olmalı. Keşif kullanıcı tarafından başlatılır; yakındaki cihazı görmek onu otomatik olarak güvenilir kişi yapmaz. Android prototipinde QR tarama ve kişi kaydı var; uygulama güven kapısının tüm protokol yollarında zorunlu olduğu ve kimliğin iki platform arasında doğrulandığı henüz kanıtlanmadı. Bluetooth adı veya adresi tek başına kriptografik kimlik değildir. Ortak BLE GATT / JNI / Swift köprüsü için sözleşme: [Cross-platform transport](docs/CROSS_PLATFORM_TRANSPORT.md).

## Mevcut durum

- [x] Android Jetpack Compose arayüzü ve doğrudan Bluetooth Classic RFCOMM taşıması
- [x] Kalıcı cihaz kimliği; özel anahtar Android Keystore AES-GCM anahtarıyla korunur
- [x] QR tarama, açıkça onaylanan güvenilir kişi kaydı ve parmak izi eşleşmesi olmadan mesaj gönderimini engelleme
- [x] QR ile güvenilen kimlik eşleşmesinden sonra ECDH/AES-GCM şifreli metin protokolü
- [x] 1–64 MiB dosyalar için şifreli parça aktarımı ve alıcıda SHA-256 bütünlük doğrulaması
- [x] Rust tarafında sınırlı, test edilebilir relay queue / hop-limit çekirdeği
- [x] CI debug APK artefaktı ve etiket tabanlı GitHub Release iş akışı
- [x] CI'da Rust biçim/test/Clippy, Python codec testi ve Android debug derlemesi (mevcut commit için sonuç bekleniyor)
- [ ] İki fiziksel Android cihazda güncel QR-gated E2EE ve dosya aktarımı testi
- [ ] Birden fazla Bluetooth bağlantısı ve gerçek A→B→C store-and-forward
- [ ] Üç fiziksel cihazda relay testleri (tekrar, süre aşımı, hop sınırı ve kuyruk doluluğu dahil)
- [ ] Android BLE GATT taşıması ve iOS CoreBluetooth taşıması
- [ ] Gerçek iPhone–Android bağlantı testi
- [ ] Dosya aktarımında devam ettirme, teslim alındı onayı ve güvenli dosya paylaşımı
- [ ] Ses/video çağrı protokolü, gerçek zamanlı codec ve cihaz testleri
- [ ] Bağımsız güvenlik incelemesi

Kaynak kodda bulunması, özelliğin fiziksel cihazlarda doğrulanmış çalışır durumda olduğu anlamına gelmez.

## Yapı

- android/ — Android uygulaması ve Bluetooth aktarımı
- ios/ — SwiftUI arayüz başlangıcı ve Apple platformu notları
- crates/mesh-core/ — Rust çerçeveleme ve ilk relay queue çekirdeği
- docs/PRODUCT_VISION.md — ürün vizyonu ve gizlilik kuralları
- docs/PROTOCOL.md — protokol notları
- docs/RELAY_ROUTING.md — çok atlamalı aktarım modeli ve sınırlar
- docs/THREAT_MODEL.md — güvenlik sınırları
- docs/ROADMAP.md — geliştirme aşamaları
- docs/ANDROID_BLUETOOTH_TEST.md — iki fiziksel cihaz test adımları
- docs/ANDROID_INSTALL.md — APK indirme ve kurma
- tools/ — Python yardımcı araçları ve testler

## Menzil hakkında

Bluetooth menzili sınırsız değildir; donanım, arazi, engeller, parazit ve pil durumu etkiler. Ara cihazlarla çok atlamalı aktarım erişimi artırabilir fakat teslimatı veya belirli bir mesafeyi garanti etmez. Rust'taki ilk relay çekirdeği henüz Android aktarım katmanına bağlanmadığından şu an A→B→C mesaj iletimi yoktur.

## Medya hakkında

Herhangi bir videoyu 10 MB'tan 50 KB'a dönüştürüp aynı kaliteyle yeniden kurmayı garanti eden genel bir yapay zekâ sıkıştırıcısı yoktur. Gerçekçi geliştirme yolu standart ses/video codec'leri, ayarlanabilir kalite, parçalı aktarım ve devam ettirmedir. Medya transferi, metin aktarımı, kimlik ve E2EE güvenilir hâle geldikten sonra ele alınacaktır.

## Güvenlik ilkeleri

1. Kriptografi kendimiz icat edilmeyecek.
2. Anonimlik veya izlenemezlik garantisi verilmeyecek.
3. Özel mesajlaşma olarak sunulmadan önce incelenmiş kütüphanelerle uçtan uca şifreleme uygulanacak.
4. Keşif otomatik olarak güven oluşturmayacak.
5. Teslim alınmayan mesaj teslim edildi gösterilmeyecek.
6. Acil durumlara hazır olduğu gerçek saha testleri olmadan iddia edilmeyecek.
7. Belgelenmiş gereklilik olmadıkça Android manifestine internet izni eklenmeyecek.

Ayrıntılar: docs/PRODUCT_VISION.md, docs/PROTOCOL.md, docs/RELAY_ROUTING.md, docs/THREAT_MODEL.md ve SECURITY.md.
