# Nexora Mesh — pil, arka plan ve ölçeklenebilirlik test planı

## Mevcut uygulama davranışı

- Android BLE keşfi kısa tarama pencereleri ve Rust tarafından önerilen cadence/power profilleri kullanır.
- Android Activity ON_STOP olduğunda BLE taraması ve reklam yayını durdurulur; görünmez/sürekli arka plan taraması yapılmaz.
- iOS CoreBluetooth keşfi kısa ön plan pencereleri kullanır. Arka plan çalışma zamanı iOS tarafından yönetilir; sürekli tarama veya relay garantisi yoktur.
- Relay izni varsayılan olarak kapalıdır ve kullanıcı tarafından açılmalıdır.
- Bu nedenle mevcut tasarımda sürekli BLE taraması için bir Foreground Service eklenmemiştir. İleride uzun süreli, kullanıcının açıkça başlattığı bir aktarım gerektirirse görünür bildirimli, süre/kullanım sınırları olan bir servis ayrıca tasarlanmalıdır. Servis, OS Doze veya pil kurallarını aşmak için kullanılmamalıdır.

## Pil doğrulama matrisi

Her senaryo en az iki desteklenen Android üretici/modelinde, fiziksel cihazlarda ölçülmelidir:

1. Uygulama ön planda, kısa keşif penceresi.
2. Kullanıcı keşfi durdurur; BLE scanner ve advertiser kapandığı doğrulanır.
3. Uygulama arka plana alınır; taramanın durduğu ve pil tüketiminin idle düzeye döndüğü doğrulanır.
4. Düşük pil / Battery Saver açıkken önerilen düşük frekans cadence uygulanır.
5. Şarj olurken foreground cadence uygulanır.
6. Bluetooth kapatılır/açılır, izin reddedilir/geri verilir ve uygulama yeniden açılır.
7. Süre, pil yüzdesi, Android sürümü, üretici güç yönetimi, tarama süresi ve bağlantı sayısı kaydedilir.

Ölçümler yapılmadan pil tasarrufu yüzdesi veya sürekli arka plan kullanılabilirliği iddia edilmemelidir.

## Paket yoğunluğu ve relay simülasyonu

Mevcut Rust/Python simülasyon testleri temel çok düğümlü davranışı doğrular; gerçek radyo saha testi yerine geçmez. Ek yük testleri şu durumları kapsamalıdır:

- 2, 5, 10 ve 25 sanal düğüm; burst ve sürekli paket akışı.
- Yinelenen message ID, TTL/expiry, hop limiti ve bozuk zarf.
- Kuyruk dolması, büyük paket, yavaş eş, bağlantı kopması ve yeniden bağlanma.
- Aynı anda çok sayıda komşudan gelen paket; kuyruk ve bellek üst sınırları.
- ACK kaybı, yeniden deneme, tekrar oynatma ve duplicate suppression.
- Her senaryoda CPU, bellek, kuyruk boyutu, gecikme, kayıp/tekrar sayısı ve hata oranı.

## Saha testi kapısı

Simülasyon yeşil olsa bile en az üç fiziksel cihazla A→B→C testi, B uygulamasını yeniden başlatma, düşük pil, arka plan, ACK kaybı ve parazitli ortam testleri tamamlanmadan canlı çok atlamalı relay veya acil durum güvenilirliği iddia edilmemelidir.
