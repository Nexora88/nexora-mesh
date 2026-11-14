# Nexora Mesh — Apple kurulum ve uyumluluk durumu

## Mevcut gerçek durum

- iOS arayüzü SwiftUI ile yazılmıştır.
- XcodeGen projesinin minimum iOS sürümü **17.0** olarak tanımlıdır.
- CI içindeki iOS adımı imzasız **Simulator uygulaması** üretir; fiziksel iPhone'a kurulabilir IPA üretmez.
- Bluetooth CoreBluetooth kodu prototiptir; iki Apple cihaz arasında gerçek mesaj teslimi doğrulanmamıştır.

## iPhone 6–17 model ailesi uyumluluğu

Mevcut uygulama için doğrulanmış minimum sürüm iOS 17'dir. iPhone 6–iPhone 17 aralığındaki her modelin desteklendiği henüz söylenemez; modelin çalıştırabildiği iOS sürümü belirleyicidir. iOS 17 çalıştırabilen cihazlar mevcut uygulama hedefiyle uyumluluk adayıdır; fiziksel cihaz testleri yine gerekir.

## iPhone 6 uyumluluğu

iPhone 6 en fazla iOS 12.5.7 çalıştırır. Mevcut uygulamanın iOS 17 hedefi bu telefona kurulamaz. SwiftUI arayüzü de iOS 13 ve üzerini gerektirdiğinden yalnızca deployment target değerini düşürmek çözüm değildir.

iPhone 6'yı desteklemek için ayrı bir **UIKit tabanlı iOS 12 uyumlu arayüz**, uyumlu derleme araçları/bağımlılıklar ve eski cihazda fiziksel test gerekir. Bu çalışma tamamlanmadan iPhone 6 için IPA veya tek tık kurulum bağlantısı vaat edilmemelidir.

## iPhone'a kurulabilir dağıtım

Fiziksel cihaza kurulabilir IPA için Apple'ın kod imzalama ve provisioning süreci gerekir. TestFlight dağıtımı Apple Developer Program hesabı ve App Store Connect yüklemesi gerektirir. Geliştirici hesabı, imzalama sertifikası ve provisioning profile olmadan GitHub Actions'ın ürettiği Simulator paketi telefona kurulamaz.

## Sonraki adımlar

1. Önce iOS 17+ destekleyen bir iPhone üzerinde imzalı test dağıtımı hazırlamak.
2. Apple Developer erişimi ve sertifikalar yetkili hesap sahibi tarafından sağlanırsa IPA/TestFlight iş akışını eklemek.
3. iPhone 6 desteği hâlâ isteniyorsa ayrı UIKit/iOS 12 portunu ve BLE yeteneklerini değerlendirmek.
4. Her iki platformda gerçek cihazlar arası bağlantı ve mesaj teslimi testlerini tamamlamak.

Bu prototip acil durum iletişimi veya yüksek hassasiyetli haberleşme için doğrulanmış bir ürün değildir.
