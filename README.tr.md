# Nexora Mesh

**Android ve Apple platformları için internet olmadan yakındaki cihazlarla iletişim kurmayı hedefleyen, afet dayanıklılığı odaklı bir mesajlaşma projesi.** Hedefimiz; orman yangını, deprem, sel ve dağ arama-kurtarma gibi bağlantının kesilebildiği koşullarda da işe yarayabilecek güvenilir bir iletişim markası oluşturmaktır.

> **Dürüst durum notu:** Bu dal, Android Bluetooth Classic keşfini, Android sistem eşleştirmesini, doğrudan RFCOMM metin aktarımını ve bu aktarıma bağlı sohbet arayüzünü ekler. Önceki CI çalışması Android debug APK'sını başarıyla derledi; son QR/kriptografi/medya yapı taşı değişiklikleri için yeni CI doğrulaması gerekiyor. İki gerçek telefonda test henüz doğrulanmadı. ECDH/AES-GCM ve QR kimlik yükü yapı taşları eklendi ancak mesaj taşımasına bağlanmadı; bu nedenle uygulama seviyesinde çalışan E2EE henüz yok. Medya parçalama/yeniden birleştirme çekirdeği eklendi ancak Bluetooth'a ve kullanıcı arayüzüne bağlanmadı. Çok atlamalı aktarım için Rust store-and-forward çekirdeği de Android Bluetooth aktarımına bağlı değil. iOS radyo bağlantısı uygulanmadı. Acil durumlarda kullanıma hazır değildir.

## Güvenilir kişiler ve QR tarama durumu

Android uygulamasına QR tarayıcı ve yerel güvenilir kişi kaydı eklendi. Kaydetme ekranı, parmak izini ayrı ve güvenli bir kanaldan karşılaştırma uyarısı verir. Kişi kaydı şu an Bluetooth bağlantısını veya mesaj gönderimini engelleyen zorunlu bir güven kapısı değildir; ECDH/AES-GCM el sıkışması da mevcut Bluetooth satır protokolüne bağlanmış değildir. Bu nedenle uygulama düzeyinde uçtan uca şifreleme (E2EE) tamamlandı olarak değerlendirilmemelidir. Kimlik anahtarının uygulama yeniden başlatmaları arasında kalıcı tutulması da henüz yapılmadı.

## Android çevrimdışı bağlantı prototipi

İlk aktarım katmanı Bluetooth Classic RFCOMM kullanır. Kullanıcı cihaz aramasını kendisi başlatır; yakındaki cihaz otomatik olarak güvenilir sayılmaz. Kullanıcı cihazı seçmeli ve her iki telefonda da Android sistem eşleştirme isteğini onaylamalıdır. Eşleştirmeden sonra bir telefon gelen bağlantıyı bekler, diğeri bağlanır. İnternet kapalıyken doğrudan metin iletimi hedeflenmiştir.

İki fiziksel cihazla deneme adımları: [Android Bluetooth test rehberi](docs/ANDROID_BLUETOOTH_TEST.md). APK indirme ve sürüm yayımlama: [Android APK rehberi](docs/ANDROID_INSTALL.md).

**Sınırlar:** Aynı anda tek doğrudan bağlantı vardır. Android sistem eşleştirmesi uygulama seviyesinde kimlik doğrulama değildir. Güvenlik sekmesi oturumluk QR açık anahtarını ve parmak izini gösterebilir; QR tarama/kontak kaydetme/doğrulama akışı henüz yoktur. ECDH/AES-GCM ve medya parçalama kodu mevcut Bluetooth taşımasına bağlanmamıştır. Kalıcı mesaj geçmişi, teslim raporları, Apple uyumluluğu ve gerçek radyo üzerinden mesh aktarma tamamlanmamıştır. CI derlemesi gerçek telefonlarda Bluetooth'un çalıştığını kanıtlamaz.

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
- [x] Rust tarafında sınırlı, test edilebilir relay queue / hop-limit çekirdeği (Android transport bağlantısı yok)
- [x] CI debug APK artefaktı ve etiket tabanlı GitHub Release iş akışı eklendi
- [ ] Güncel CI derlemesinin ve relay testlerinin başarıyla tamamlanması
- [ ] İnternet kapalıyken iki fiziksel Android cihaz arasında çift yönlü mesaj testi
- [ ] QR tarama, karşılıklı anahtar doğrulama ve güvenilir kişi kaydetme akışı\n- [ ] ECDH/AES-GCM'i taşıma protokolüne bağlama ve anahtar doğrulamasını zorunlu kılma\n- [ ] Uygulama seviyesinde doğrulanmış uçtan uca şifreleme
- [ ] Kalıcı sohbet geçmişi ve güvenilir teslim durumu
- [ ] iOS Xcode projesi ve gerçek cihaz derlemesi
- [ ] Android Bluetooth taşıyıcısına gerçek multi-hop aktarımının bağlanması ve üç cihaz testi
- [ ] Medya parçalama/yeniden birleştirmeyi Bluetooth aktarımına ve dosya seçici arayüzüne bağlama\n- [ ] Ses ve görüntü için gerçek zamanlı çağrı/codec protokolü

Kaynak kodda bulunması, özelliğin test edilmiş çalışır durumda olduğu anlamına gelmez.

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
