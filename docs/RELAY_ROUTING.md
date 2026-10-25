# Çok atlamalı aktarım — ilk çekirdek

Bu belge bir tasarım ve prototip sınırıdır; telefonlar arasında çalışan çok atlamalı aktarımın tamamlandığı anlamına gelmez.

## Çalışma modeli

1. Gönderen uygulama mesajı alıcının açık anahtarıyla/oturum anahtarıyla uçtan uca şifreler.
2. Paket rastgele bir mesaj kimliği, opak bir hedef yönlendirme belirteci, son kullanma zamanı ve sınırlı atlama bütçesi taşır.
3. Her izinli röle yalnızca paketi saklar ve iletir. İçeriği çözmez. Uygulama katmanında röle izni kullanıcı tarafından açıkça verilmelidir.
4. Röleler mesaj kimliğine göre yinelenen paketleri atar, süresi dolmuş paketleri siler ve atlama bütçesini azaltır.
5. Alıcı, kimliği ve bütünlüğü doğrulanmış şifreli paketi açar; teslim alındı onayı yalnızca gerçekten alındığında gösterilir.

## Bu committeki kod

`crates/mesh-core/src/relay.rs` transport bağımsız bir Rust prototipi sağlar:
- opak şifreli yük için 256 KiB sınırı;
- en fazla 8 atlama;
- son kullanma zamanı kontrolü;
- tekrar iletimini azaltan sınırlı yinelenen mesaj önbelleği;
- sınırlı RAM kuyruğu ve kuyruk testleri.

Bu modül henüz Android Bluetooth soketine bağlanmamıştır; kalıcı saklama, yönlendirme keşfi, kimlik doğrulama, E2EE, gerçek radyo üzerinden üç cihaz testi veya arka plan servis yaşam döngüsü sağlamaz. Dolayısıyla şu an A→B→C gerçek aktarımı varmış gibi sunulamaz.

## Gizlilik gerçekleri

Şifreli içerik, rölenin metni okumasını engelleyebilir; fakat sıradan bir mesh otomatik olarak göndereni/alıcıyı gizlemez. Paket boyutu, zamanlama, radyo adresleri, tekrar eden trafik ve hedef belirteçleri ilişkilendirmeye izin verebilir. Değişen başlıklar tek başına Tor benzeri anonimlik sağlamaz. Güçlü trafik analizi direnci için ayrıca tasarlanmış anonim yönlendirme, kimlik doğrulama, anahtar yenileme, kötü niyetli röle modeli ve bağımsız güvenlik incelemesi gerekir.

## Medya

Rastgele bir videoyu 10 MB'tan 50 KB'a kayıpsız biçimde dönüştüren genel bir “AI motion codec” yoktur. Gerçekçi yol:
- standart ve test edilmiş ses/video codec'leri;
- kullanıcıya açık kalite/bit hızı ayarı;
- parçalı aktarım, bütünlük kontrolü ve devam ettirme;
- ağın bant genişliği ve pil durumuna göre uyarlama;
- çok düşük bant genişliğinde isteğe bağlı ayrı özet/thumbnail veya özel animasyon temsili.

Önce metin rölesi, kimlik ve şifreleme güvenilir şekilde tamamlanmalı; medya bundan sonra eklenmelidir.
