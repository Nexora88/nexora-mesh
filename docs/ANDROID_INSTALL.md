# Android APK indirme ve kurma

Nexora Mesh şu anda erken geliştirme prototipidir. APK'nın derlenmesi, iki telefonda Bluetooth bağlantısının doğrulanması veya güvenlik denetiminin tamamlanması anlamına gelmez.

## Test APK'sını GitHub Actions'tan indirme

1. Depoda **Actions → CI** sayfasını aç.
2. Başarılı bir CI çalışmasını seç.
3. Sayfanın altındaki **Artifacts** bölümünden `nexora-mesh-android-debug` dosyasını indir.
4. ZIP dosyasını aç; içindeki `app-debug.apk` dosyasını Android telefona aktar.
5. Android, tarayıcı/dosya yöneticisi üzerinden kurulum için izin isteyebilir. Yalnızca bu açık kaynak depodan ve beklediğin derlemeden indirdiğin APK'yı kur; bilinmeyen APK'lara izin verme.

GitHub Actions artefaktı süre sınırlıdır ve sürüm yayımlama yerine geçmez.

## Sürüm indirmesi

`.github/workflows/android-release.yml` sürüm etiketi `v*` gönderildiğinde Android debug APK'yı derleyip GitHub Releases'a ekleyecek şekilde hazırlanmıştır. Henüz yayınlanmış bir sürüm yoktur. Sürüm oluşturulmadan önce CI, cihaz testi, imzalama ve güvenlik sınırları değerlendirilmelidir.

Bu akış **debug APK** üretir; üretim dağıtımına uygun, özel anahtarla imzalanmış bir sürüm değildir. Herkese açık bir APK yayımlamadan önce ayrı ve güvenli bir release signing düzeni kurulmalıdır. İmzalama anahtarı veya parolası depoya yazılmamalıdır.

## Uygulama durumu

Şu anki uygulama doğrudan Bluetooth Classic üzerinden metin sohbeti prototipidir. Çok atlamalı aktarım çekirdeği Rust tarafında test edilebilir bir başlangıçtır, ancak Android radyo katmanına bağlanmamıştır. E2EE, Apple ile cihazlar arası iletişim ve medya aktarımı tamamlanmamıştır. Acil durumlarda güvenilir haberleşme aracı olarak kullanılmamalıdır.
