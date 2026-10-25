# Nexora Mesh

**Android ve Apple platformları için internet olmadan yakındaki cihazlarla iletişim kurmayı hedefleyen, afet dayanıklılığı odaklı bir mesajlaşma projesi.** Hedefimiz yalnızca anonim bir sohbet uygulaması değil; orman yangını, deprem, sel ve dağ arama-kurtarma gibi bağlantının kesilebildiği koşullarda da işe yarayabilecek, güvenilir bir iletişim markası oluşturmaktır.

> **Dürüst durum notu:** Proje erken prototip aşamasında. Android arayüzü yerel bir UI prototipidir; ios/NexoraMeshApp.swift ise SwiftUI başlangıç arayüzüdür. Gerçek cihaz keşfi, eşleştirme, iki cihaz arasında mesaj iletimi, uçtan uca şifreleme ve mesh aktarma henüz tamamlanmadı. Acil durumlarda kullanıma hazır değildir.

## Vizyon

- **Günlük mesajlaşma deneyimi:** sohbet listesi, güvenilir kişiler, mesaj durumu ve ileride dosya/ses/görüntülü arama.
- **Çevrimdışı iletişim:** internet sunucusu olmadan yerel radyo bağlantısıyla iki cihaz arasında mesaj.
- **Afet dayanıklılığı:** altyapı kesintilerinde ekiplerin iletişimini desteklemek; saha testleri ve açık sınırlamalarla.
- **Android + Apple:** önce Android radyo katmanı; iOS için yerel SwiftUI uygulaması ve iki platformla uyumlu ortak protokol.
- **Menzili aşamalı genişletme:** doğrudan bağlantıdan sonra izinli ara cihazlarla store-and-forward ve çok atlamalı mesh.

## Cihaz keşfi ve güven

Gizlilik varsayılan olmalı: cihazlar mümkün olduğunda varsayılan olarak keşfedilemez olmalı; kullanıcı keşfi bilerek ve süreli başlatmalı. Yakındaki cihazı görmek onu otomatik olarak kişi yapmamalı. Normal kişi listesinde yalnızca açıkça eşleştirilmiş ve güvenilir cihazlar yer almalı. Eşleştirme için iki tarafın onayı ve bir doğrulama kodu/QR parmak izi hedefleniyor. Cihaz adı veya Bluetooth adresi tek başına güvenilir kimlik değildir.

## Mevcut durum

- [x] Açık kaynak depo ve Rust çalışma alanı
- [x] Android Jetpack Compose arayüz prototipi
- [x] Apple platformları için SwiftUI arayüz başlangıcı
- [x] Rust çerçeveleme kodu ve testleri
- [x] Ürün vizyonu, tehdit modeli ve yol haritası
- [ ] Android CI derlemesi ve gerçek cihaz kurulumu doğrulaması
- [ ] iOS Xcode projesi ve gerçek cihaz derlemesi
- [ ] Bluetooth keşfi ve güvenli eşleştirme
- [ ] İnternet kapalıyken iki gerçek cihaz arasında şifreli metin iletimi
- [ ] Kalıcı sohbet geçmişi ve teslim durumları
- [ ] İzinli mesh aktarma ve saha menzili testleri
- [ ] Dosya, ses ve görüntülü arama

Tamamlanmamış özellikler çalışıyormuş gibi tanıtılmamalıdır.

## Yapı

- android/ — Android uygulama kabuğu
- ios/ — SwiftUI arayüz başlangıcı ve Apple platformu notları
- crates/mesh-core/ — Rust protokol çekirdeği
- docs/PRODUCT_VISION.md — ürün vizyonu ve gizlilik kuralları
- docs/PROTOCOL.md — protokol notları
- docs/THREAT_MODEL.md — güvenlik sınırları
- docs/ROADMAP.md — geliştirme aşamaları
- tools/ — Python yardımcı araçları ve testler

## Menzil hakkında

Bluetooth veya Wi-Fi Direct menzili sınırsız değildir; donanım, arazi, engeller, parazit ve pil durumu etkiler. Ara cihazlarla çok atlamalı aktarım daha uzak noktalara mesaj ulaştırma olasılığını artırabilir, fakat teslimatı veya belirli bir mesafeyi garanti etmez. Bu özellik henüz uygulanmadı.

## Güvenlik ilkeleri

1. Kriptografi kendimiz icat edilmeyecek.
2. Anonimlik veya izlenemezlik garantisi verilmeyecek.
3. Gerçek özel mesajlaşma açılmadan önce incelenmiş kütüphanelerle uçtan uca şifreleme uygulanacak.
4. Keşfedilen cihaz otomatik olarak güvenilir sayılmayacak.
5. Teslim alınmayan mesaj, teslim edildi gibi gösterilmeyecek.
6. Acil durumlarda kullanıma hazır olduğu, gerçek saha testleri olmadan iddia edilmeyecek.
7. İnternet izni belgelenmiş zorunlu bir özellik olmadıkça Android manifestine eklenmeyecek.

Ayrıntılar: Product vision, Protokol, Tehdit modeli ve Güvenlik politikası.