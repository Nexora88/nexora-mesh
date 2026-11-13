# Android APK indirme ve kurma

Nexora Mesh erken geliştirme prototipidir. APK'nın derlenmesi, iki telefonda Bluetooth bağlantısının doğrulanması veya güvenlik denetiminin tamamlandığı anlamına gelmez.

## Tek bağlantıyla en son yayımlanan APK

İlk kararlı sürüm GitHub Release olarak yayımlandıktan sonra şu sabit bağlantı en son yayımlanan APK'yı indirir:

[**Nexora Mesh Android APK — indir**](https://github.com/Nexora88/nexora-mesh/releases/latest/download/NexoraMesh-Android-debug.apk)

Bu bağlantı, henüz bir sürüm yayımlanmadıysa çalışmaz. Yeni sürüm etiketi (`v*`) gönderildiğinde GitHub Actions APK'yı yeniden derler, birim testlerini çalıştırır ve APK'yı Release dosyası olarak ekler.

## Her başarılı ana dal derlemesinin APK'sı

`.github/workflows/android-release.yml` ana dala her yeni commit geldiğinde de çalışır:

1. Depoda **Actions → Android APK Release** sayfasını aç.
2. En son başarılı çalışmayı seç.
3. **Artifacts → nexora-mesh-android-debug** paketini indir.
4. ZIP içindeki APK'yı Android cihazına aktar.

Actions artefaktı 90 gün saklanır. Release bağlantısı ise yayımlanan sürüm kaldırılmadığı sürece kalıcıdır.

## Android SDK lisans onayı

Android SDK lisans koşulları otomatik olarak kabul edilmez. Yetkili depo sahibi lisansı inceleyip kabul ettikten sonra GitHub deposunda **Settings → Secrets and variables → Actions → Variables** bölümüne `ANDROID_SDK_LICENSES_ACCEPTED=true` değişkenini eklemelidir. Bu değişken olmadan Android derlemesi güvenli biçimde durur.

## Kurulum ve güvenlik notları

- Paket kimliği: `org.nexora.mesh`
- Minimum Android: 8.0 (API 26)
- Üretilen paket: debug APK; üretim dağıtımı için imzalı release APK değildir.
- İmzalama anahtarı veya parolası depoya eklenmemelidir.
- Yalnızca beklediğin Nexora Mesh deposundan indirdiğin APK'yı kur.

Mevcut uygulama erken aşamadadır. Fiziksel iki cihaz testi ve bağımsız güvenlik incelemesi tamamlanmadan acil durum iletişimi için güvenilir araç olarak değerlendirilmemelidir.
