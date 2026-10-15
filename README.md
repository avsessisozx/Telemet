# Telemet

DJI Osmo Action 4 için açık kaynak Android uygulaması.

Telefon, **DJI Osmo Action GPS Bluetooth kumandası** gibi davranarak kameraya anlık konum, hız ve yükseklik verisi gönderir. Veri doğrudan videonun içine yazılır; böylece DJI Mimo'da gösterge paneli (hız, rota, yükseklik) FIT dosyası ya da dönüştürme olmadan çalışır. Motosiklet, bisiklet, araba fark etmez.

- Ek donanım gerekmez, sadece telefon ve kamera
- Google Play Hizmetleri kullanmaz, ek kütüphane yok
- Her sürüşü telefonda ayrıca **FIT + GPX** olarak kaydeder (yedek; DJI Studio'ya içe aktarılabilir)
- Navigasyon uygulamaları gibi kesintisiz çalışır: Başlat'tan sonra ekran kapansa ya da başka uygulama açılsa da Durdur'a basana kadar sürer

## Kurulum

1. Telefonda bu sayfadaki **Releases** bölümüne gidin (sağ tarafta ya da en altta).
2. En üstteki sürümde **Telemet-x.y.z.apk** dosyasına dokunup indirin.
3. İndirilen dosyayı açın. Telefon "bilinmeyen kaynak" uyarısı verirse, tarayıcıya ya da dosya yöneticisine kurulum izni verin.
4. Yeni sürümler eskisinin üzerine kurulur; silmenize gerek yoktur.

> Android 10 ve üzeri gerekir.

## İlk bağlantı

1. **Kamerada:** Ekranı aşağı kaydırın → Ayarlar → Kablosuz bağlantı açık olsun.
2. **Telefonda DJI Mimo kapalı olsun.** Kamera aynı anda yalnızca bir Bluetooth kumandayla konuşur.
3. Telemet'i açın, **Başlat**'a basın ve istenen izinleri verin (Konum, Yakındaki cihazlar, Bildirim).
4. Uygulama kamerayı bulur ve bağlanır. **Kamera ekranında bağlantı onayı çıkarsa onaylayın.** Uygulamada büyük harflerle görünen kod, kamerada görünenle aynı olmalı.
5. "Kamera bağlı" yazınca hazırsınız. Kameranın ekranında kumanda ya da GPS simgesi görünebilir.
6. Açık havada GPS sinyalinin gelmesini bekleyin (ilk seferde 30–60 saniye).

Sonraki bağlantılarda onay çıkmaz; uygulama kamerayı hatırlar.

## Sürüşte kullanım

- Kaydı kameradan ya da uygulamadaki **Kayıt** düğmesinden başlatabilirsiniz. Bildirimdeki **Kayıt** düğmesi de çalışır.
- Ekranı kapatabilirsiniz; uygulama arka planda çalışmaya devam eder.
- Uzun sürüşler için **Pil kısıtlamasını kaldır** düğmesine bir kez basın. Özellikle Xiaomi, Huawei, Samsung gibi telefonlar arka plandaki uygulamaları kapatabiliyor.
- Bağlantı koparsa uygulama kendiliğinden yeniden bağlanır.
- Bitince **Durdur**'a basın.

Videoyu DJI Mimo'ya aktardıktan sonra düzenleme ekranında **gösterge paneli (dashboard)** seçeneğinden hız, rota ve yükseklik göstergelerini ekleyebilirsiniz.

## Ayarlar

| Ayar | Açıklama |
|---|---|
| Akıcı GPS (10 Hz) | Gerçek DJI kumandası saniyede 10 kez veri gönderir. Telefon GPS'i genelde saniyede 1 ölçüm verdiği için ara değerler son ölçümün hız ve yönüne göre tahmin edilir. Göstergede tuhaflık görürseniz kapatıp deneyin. |
| FIT + GPX olarak kaydet | Varsayılan açık. Durdur'a basınca **İndirilenler/Telemet** klasörüne `.fit` ve `.gpx` çıkar. Uygulama sürüş ortasında kapanırsa kayıt kaldığı yerden sürer. |
| Ekranı açık tut | Telemet öndeyken ve sürüş sürerken ekran kararmaz. |
| Kamera eşleşmesini unut | Başka bir kameraya bağlanmak için. |

## Kamera veriyi videoya yazmadıysa (FIT ile kurtarma)

1. Sürüş bitince telefonda **İndirilenler/Telemet** klasöründeki `.fit` dosyasını bilgisayara aktarın.
2. DJI Studio'da videoyu açın → **Pano** → **Veri kaynağı** → **FIT Dosyasını İçe Aktar**.
3. Gerekirse **Süre Hizalama** ile zamanı videoya oturtun.

FIT dosyasında konum, hız, yükseklik, mesafe ve eğim bulunur. Nabız, pedal, güç, tork için ayrı sensör gerekir; telefon bunları ölçemez.

## Sorun giderme

Her şey uygulamanın altındaki **Günlük** bölümüne yazılır. Bir sorun olursa **Paylaş** düğmesiyle günlüğü gönderin; sorunun nerede olduğu oradan anlaşılır.

| Sorun | Çözüm |
|---|---|
| "Kamera aranıyor" bitmiyor | Kamera açık mı? DJI Mimo telefonda ya da başka bir telefonda bağlı mı? Kamerada Ayarlar → Kablosuz bağlantı → bağlantıyı sıfırla. |
| Kamera bağlantıyı reddetti | Kamerada kablosuz bağlantıyı sıfırlayıp tekrar deneyin, onay ekranında kabul edin. |
| Bağlı ama göstergede veri yok | GPS sinyali var mı (yeşil nokta)? "Kameraya … paket/sn" değeri 0'dan büyük mü? Günlüğü paylaşın. |
| Arka planda kopuyor | Pil kısıtlamasını kaldırın; telefon ayarlarında uygulamanın arka plan kullanımına izin verin. |

## Nasıl çalışıyor?

DJI, GPS kumandasının Bluetooth protokolünü açık kaynak olarak yayınladı: [dji-sdk/Osmo-GPS-Controller-Demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo). O proje ESP32 kartı ve ayrı bir GPS modülüyle çalışıyor; Telemet aynı protokolü telefonun kendi Bluetooth'u ve GPS'iyle uyguluyor.

1. Kamera, üretici verisi `AA 08 .. .. FA` olan Bluetooth reklamlarından bulunur.
2. `FFF0` servisine bağlanılır; `FFF4` kameradan gelen, `FFF5` kameraya giden veridir.
3. `0x0019` bağlantı isteği gönderilir; kamera onay verince bağlantı kurulur.
4. `0x1D05` ile kamera durumu (mod, kayıt, pil) istenir.
5. `0x0017` ile saniyede 10 kez GPS verisi gönderilir.

Kod yapısı:

```
app/src/main/java/io/github/avsessisozx/telemet/
├── protocol/   DJI çerçeve yapısı, CRC, komutlar (Android'den bağımsız, testli)
├── ble/        Bluetooth tarama, bağlantı ve el sıkışma
├── service/    Arka plan servisi, GPS okuma, FIT/GPX yedek kaydı
├── ui/         Ana ekran
└── util/       Günlük ve ayarlar
```

Protokol kodu, DJI'nin örnek projesindeki gerçek kamera paketleriyle birim testlerinde doğrulanır.

## Derleme

Her `main` dalına gönderimde GitHub Actions APK'yı derler ve **Releases** bölümüne yükler.

Bilgisayarda derlemek için: Android Studio ile projeyi açın ya da `./gradlew assembleDebug` çalıştırın (JDK 17 gerekir).

`keystore/telemet-debug.jks` herkese açık bir imzalama anahtarıdır. Sadece yeni sürümlerin eskisinin üzerine kurulabilmesi içindir; güvenlik sağlamaz.

## Desteklenen kameralar

DJI protokol belgesine göre: Osmo Action 4, Osmo Action 5 Pro, Osmo Action 6, Osmo 360. Yalnızca Osmo Action 4 hedeflenerek geliştirildi.

## Lisans

MIT. DJI R SDK protokolü DJI'nin [son kullanıcı lisans sözleşmesine](https://developer.dji.com/policies/eula/) tabidir. Bu proje DJI ile bağlantılı değildir.
