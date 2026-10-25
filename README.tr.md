# Nexora Mesh

**Android ve Apple platformları için internet olmadan yakındaki cihazlarla iletişim kurmayı hedefleyen, afet dayanıklılığı odaklı bir mesajlaşma projesi.** Hedefimiz; orman yangını, deprem, sel ve dağ arama-kurtarma gibi bağlantının kesilebildiği koşullarda da işe yarayabilecek güvenilir bir iletişim markası oluşturmaktır.

> **Dürüst durum notu:** Bu dal, Android Bluetooth Classic keşfini, Android sistem eşleştirmesini, doğrudan RFCOMM metin aktarımını ve bu aktarıma bağlı sohbet arayüzünü ekler. CI ve iki gerçek telefonda test henüz doğrulanmadı. Uygulama seviyesinde uçtan uca şifreleme, mesh aktarma ve iOS radyo bağlantısı uygulanmadı. Acil durumlarda kullanıma hazır değildir.

## Android çevrimdışı bağlantı prototipi

İlk aktarım katmanı Bluetooth Classic RFCOMM kullanır. Kullanıcı cihaz aramasını kendisi başlatır; yakındaki cihaz otomatik olarak güvenilir sayılmaz. Kullanıcı cihazı seçmeli ve her iki telefonda da Android sistem eşleştirme isteğini onaylamalıdır. Eşleştirmeden sonra bir telefon gelen bağlantıyı bekler, diğeri bağlanır. İnternet kapalıyken doğrudan metin iletimi hedeflenmiştir.

İki fiziksel cihazla deneme adımları: [Android Bluetooth test rehberi](docs/ANDROID_BLUETOOTH_TEST.md).

**Sınırlar:** Aynı anda tek doğrudan bağlantı vardır. Android sistem eşleştirmesi ilk güven kapısıdır; uygulama seviyesinde kimlik doğrulama protokolü değildir. QR/kodla kimlik doğrulama, incelenmiş uçtan uca şifreleme, kalıcı mesaj geçmişi, teslim raporları, Apple uyumluluğu ve mesh aktarma sonraki işlerdir. CI derlemesi gerçek telefonlarda Bluetooth'un çalıştığını kanıtlamaz.

## Vizyon

- Günlük mesajlaşma: sohbet listesi, güvenilir kişiler ve ileride dosya/ses/görüntülü arama.
- Çevrimdışı iletişim: internet sunucusu olmadan yerel radyo bağlantısı.
- Afet dayanıklılığı: saha testleri ve açık sınırlamalarla altyapı kesintilerinde iletişimi desteklemek.
- Android + Apple: önce Android radyo katmanı; iOS arayüzü ve ortak protokol.
- Menzili aşamalı genişletme: doğrudan bağlantıdan sonra izinli ara cihazlar ve çok atlamalı mesh.

## Cihaz keşfi ve güven

Gizlilik varsayılan olmalı. Keşif kullanıcı tarafından başlatılır; yakındaki cihazı görmek onu otomatik olarak güvenilir kişi yapmaz. İlk sürüm Android sistem eşleştirmesini kullanır. Sonraki aşamada iki taraflı doğrulama kodu veya QR parmak izi eklenmelidir. Bluetooth adı veya adresi tek başına kriptografik kimlik değildir.

## Mevcut durum

- [x] Açık kaynak depo ve Rust çalışma alanı
- [x] Android Jetpack Compose arayüzü
- [x] Apple platformları için SwiftUI arayüz başlangıcı
- [x] Rust çerçeveleme kodu ve testleri
- [x] Android Bluetooth keşfi ve sistem eşleştirmesi kodlandı (gerçek cihaz testi bekliyor)
- [x] Doğrudan RFCOMM metin aktarımı kodlandı (gerçek cihaz testi bekliyor)
- [x] İki telefonla deneme rehberi
- [ ] Android CI derlemesi ve kurulum doğrulaması
- [ ] İnternet kapalıyken iki fiziksel Android cihaz arasında çift yönlü mesaj testi
- [ ] Uygulama seviyesinde kimlik doğrulama ve incelenmiş uçtan uca şifreleme
- [ ] Kalıcı sohbet geçmişi ve güvenilir teslim durumu
- [ ] iOS Xcode projesi ve gerçek cihaz derlemesi
- [ ] İzinli mesh aktarma ve saha menzili testleri
- [ ] Dosya, ses ve görüntülü arama

Kaynak kodda bulunması, özelliğin test edilmiş çalışır durumda olduğu anlamına gelmez.

## Yapı

- android/ — Android uygulaması ve Bluetooth aktarımı
- ios/ — SwiftUI arayüz başlangıcı ve Apple platformu notları
- crates/mesh-core/ — Rust protokol çekirdeği
- docs/PRODUCT_VISION.md — ürün vizyonu ve gizlilik kuralları
- docs/PROTOCOL.md — protokol notları
- docs/THREAT_MODEL.md — güvenlik sınırları
- docs/ROADMAP.md — geliştirme aşamaları
- docs/ANDROID_BLUETOOTH_TEST.md — iki fiziksel cihaz test adımları
- tools/ — Python yardımcı araçları ve testler

## Menzil hakkında

Bluetooth menzili sınırsız değildir; donanım, arazi, engeller, parazit ve pil durumu etkiler. Ara cihazlarla çok atlamalı aktarım erişimi artırabilir fakat teslimatı veya belirli bir mesafeyi garanti etmez. Mesh aktarımı henüz uygulanmadı.

## Güvenlik ilkeleri

1. Kriptografi kendimiz icat edilmeyecek.
2. Anonimlik veya izlenemezlik garantisi verilmeyecek.
3. Özel mesajlaşma olarak sunulmadan önce incelenmiş kütüphanelerle uçtan uca şifreleme uygulanacak.
4. Keşif otomatik olarak güven oluşturmayacak.
5. Teslim alınmayan mesaj teslim edildi gösterilmeyecek.
6. Acil durumlara hazır olduğu gerçek saha testleri olmadan iddia edilmeyecek.
7. Belgelenmiş gereklilik olmadıkça Android manifestine internet izni eklenmeyecek.

Ayrıntılar: docs/PRODUCT_VISION.md, docs/PROTOCOL.md, docs/THREAT_MODEL.md ve SECURITY.md.