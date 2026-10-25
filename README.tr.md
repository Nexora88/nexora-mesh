# Nexora Mesh

**Android için internet gerektirmeyen, yakındaki cihazlarla mesajlaşmayı hedefleyen bir uygulama.** Amaç; mesajların bulut sunucusuna veya merkezi bir mesajlaşma hizmetine ihtiyaç duymadan yerel bağlantılar üzerinden aktarılmasıdır.

> Erken prototip. Bu depodaki Android ekranı şu an yerel arayüz prototipidir; gerçek cihazlar arası mesaj iletimi ve üretim seviyesinde şifreleme henüz uygulanmış değildir.

## Ürün hedefi

- Kotlin ve Jetpack Compose ile yerel Android uygulaması.
- İlk bağlantı hedefleri: Bluetooth ve Wi-Fi Direct.
- Daha sonraki aşamada cihazların mesajları birbirine aktardığı çok atlamalı ağ.
- Taşımadan bağımsız mesaj türleri ve çerçeveleme için Rust çekirdeği.
- Bulut hesabı veya merkezi mesaj sunucusu gerektirmeyen yerel tasarım.

## Durum

- [x] Açık kaynak depo ve temel proje yapısı
- [x] Android uygulama kabuğu ve yerel sohbet arayüzü prototipi
- [x] Rust çalışma alanı başlangıcı
- [ ] Android derlemesinin CI ve cihaz üzerinde doğrulanması
- [ ] Bluetooth/Wi-Fi Direct cihaz keşfi ve bağlantısı
- [ ] Gerçek cihazlar arasında metin iletimi
- [ ] İncelenmiş uçtan uca şifreleme ve kimlik doğrulama
- [ ] Çok atlamalı yönlendirme, dosya, ses ve görüntülü arama

Tamamlanmamış özellikler çalışıyormuş gibi tanıtılmamalıdır.

## Yapı

- `android/` — Kotlin ve Jetpack Compose uygulaması
- `crates/mesh-core/` — Rust protokol çekirdeği
- `docs/PROTOCOL.md` — protokol notları
- `docs/THREAT_MODEL.md` — tehdit modeli ve sınırlar
- `tools/` — Python yardımcı araçları ve testler

## İnternetsiz çalışma ilkesi

Mesaj yolu internetteki bir sunucuya bağlı olmamalıdır. Bluetooth ve Wi-Fi Direct internet olmadan yerel bağlantı kurabilir; ancak menzil, izinler, pil tüketimi ve cihaz uyumluluğu değişir. Çok atlamalı yönlendirme için katılan ara cihazlar gerekir ve bu özellik henüz uygulanmamıştır.

## Android derleme

`android/` dizinini Android Studio ile açıp Gradle eşitlemesini tamamlayın. Gerçek cihazda derleme ve iki cihazla bağlantı testi yapılmadan uygulama kullanılabilir bir çevrimdışı mesajlaşma ürünü olarak değerlendirilmemelidir.

## Güvenlik ilkeleri

1. Kriptografi kendimiz icat edilmeyecek.
2. Anonimlik veya izlenemezlik garantisi verilmeyecek.
3. Gerçek özel mesajlaşma açılmadan önce uçtan uca şifreleme uygulanacak.
4. Gelen veriler doğrulanacak ve boyut sınırları uygulanacak.
5. Gerekli olduğu belgelenmedikçe internet izni eklenmeyecek.

Ayrıntılar: [Protokol](docs/PROTOCOL.md), [Tehdit modeli](docs/THREAT_MODEL.md), [Güvenlik politikası](SECURITY.md).
