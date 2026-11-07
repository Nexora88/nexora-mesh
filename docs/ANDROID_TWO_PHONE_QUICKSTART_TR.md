# İki Android Telefonla İnternetsiz Sohbet Hızlı Testi

Bu rehber, **Bluetooth Classic doğrudan sohbet** test derlemesi içindir. BLE, Rust relay ve çoklu atlama bu APK'da kapalıdır.

## 1. APK üret

Windows bilgisayarda, depo kök dizininde PowerShell aç:

\`\`\`powershell
powershell -ExecutionPolicy Bypass -File scripts/build-android-direct-debug.ps1
\`\`\`

Script:
- Kurulum yapmadan önce boş disk alanını kontrol eder.
- Android SDK 35, NDK 27.2.12479018, CMake 3.22.1 ve JDK 17'nin mevcut olmasını ister.
- Doğrudan sohbet derlemesini, JVM testlerini ve instrumentation test kaynaklarının derlenmesini çalıştırır.
- APK'yı \`dist/android/nexora-mesh-direct-debug.apk\` ve Masaüstü'ne kopyalar.
- SHA-256 özeti üretir.

Gerekli araçlar yoksa veya boş alan 1 GB'ın altındaysa durur; otomatik paket indirmez.

## 2. İki telefona kur

- Android 8.0 veya üzeri iki gerçek Android telefon kullan.
- Aynı \`NexoraMesh-DirectChat-debug.apk\` dosyasını iki telefona da kur.
- Her iki telefonda Bluetooth'u aç.
- Android'in Bluetooth ayarlarından telefonları eşleştir.
- Test için kişisel veya hassas mesajlar kullanma.

## 3. İnternet kapalıyken bağlan

1. İki telefonda da Wi-Fi ve mobil veriyi kapat. Bluetooth açık kalmalı.
2. Telefon A'da Nexora Mesh'i aç ve **Cihazlar → Bağlantı bekle** seçeneğine bas.
3. Telefon B'de **Cihazlar → Yakındaki cihazları ara** seçeneğine bas.
4. A telefonunu listeden seç. İlk seçim Android eşleştirme isteğini açabilir; eşleştirme tamamlanınca listeden tekrar seçerek bağlan.
5. İki telefonda **Güvenlik** sekmesine gir. QR kodlarını karşılıklı tara.
6. Her iki ekranda görünen parmak izlerini karşılaştır. Eşleşiyorsa kişiyi kaydet.
7. Güvenli oturum etkinleşince Sohbet sekmesinden kısa bir test mesajı gönder.

## 4. Başarı ölçütü

- B telefonu mesajı tam ve yalnızca bir kez göstermeli.
- A telefonu, doğrulanmış teslim onayı gelmeden mesajı **Teslim edildi** göstermemeli.
- Onay geldikten sonra A'da **Teslim edildi** görünmeli.
- Wi-Fi ve mobil veri test boyunca kapalı kalmalı.

Bağlantı kopması, yeniden bağlanma, uygulamayı kapatıp açma ve tekrar gönderim senaryoları için ayrıca [fiziksel cihaz test planına](ANDROID_PHYSICAL_DEVICE_TEST_PLAN.md) bak.

## Sınırlar

Bu test APK'sı yalnızca iki Android cihaz arasındaki doğrudan Bluetooth Classic metin sohbetini doğrulamak içindir. Çoklu atlama, BLE mesaj taşıması, iOS, medya kasası ve üretim sürümü bu paketin kapsamı dışındadır. ECDH/AES-GCM oturumu QR ile doğrulanmış kimliğe dayanır; ileri gizlilik (forward secrecy) henüz sağlanmış değildir ve bağımsız kriptografik denetim yapılmamıştır. Acil durum iletişimi için kullanma.
