<p align="center"><img src="icon.png" width="100" alt="Telemet"></p>

<h1 align="center">Telemet</h1>

<p align="center"><b>DJI Osmo Action kameralar için ücretsiz GPS telemetrisi. DJI GPS Bluetooth kumandası yerine Android telefonunu kullan.</b></p>

<p align="center">[English](../README.md) · **Türkçe** · [Español](README.es.md) · [Français](README.fr.md) · [Italiano](README.it.md) · [Deutsch](README.de.md) · [Português](README.pt-BR.md) · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Русский](README.ru.md) · [العربية](README.ar.md)</p>

---

DJI Osmo Action kameralar videoya hız, rota ve yükseklik verisini ancak DJI'nin **GPS Bluetooth kumandası** bağlıyken yazabiliyor. Telemet, telefonunu bu kumandaya dönüştürür: kameraya Bluetooth ile bağlanır, telefonun GPS'ini saniyede 10 kez gönderir, kamera da veriyi doğrudan video dosyasına yazar. Videoyu **DJI Mimo** ya da **DJI Studio**'da açıp hız/rota/yükseklik panosunu eklemen yeterli. FIT dönüştürme ya da ek cihaz gerekmez. Motosiklet için yapıldı; bisiklet, araba ve yürüyüşte de aynı şekilde çalışır.

## Özellikler

- 📍 **GPS kumandası taklidi:** telefon DJI GPS kumandası gibi davranır, veri videoya gömülür
- 🎬 **Kamera kontrolü:** uygulamadan ya da bildirimden kaydı başlat/durdur; mod, kayıt durumu ve pil görünür
- 🗂️ **Her sürüş ayrı:** Sürüşlerim ekranında sürüşler güne göre gruplu; mesafe, süre, en yüksek/ortalama hız, tırmanış
- 🗺️ **Sürüş ayrıntısı:** sürüşe dokununca rota, hız, yükseklik ve yatış grafikleri; yeniden adlandır, paylaş, sil
- ⚙️ **Ayarlar:** km ya da mil, kamera eşleşmesini sıfırlama, izin durumu, günlük, yedek alma ve geri yükleme, güncelleme kontrolü
- 🛟 **Güvenli sıfırlama:** "Tüm verileri temizle" önce yedek alır, tek dokunuşla geri yüklenir; sürüş dosyaları silinmez
- 💾 **Her sürüşün FIT + GPX yedeği:** kamera veriyi kaçırırsa FIT'i DJI Studio'ya aktar
- 🏍️ **Yatış açısı ve G kuvveti:** jiroskop ve GPS'ten hesaplanır, telefonun nasıl takıldığından bağımsızdır; FIT'e yazılır
- 📈 **Barometrik yükseklik:** barometresi olan telefonlarda çok daha kararlı yükseklik ve eğim
- 🔋 **Navigasyon uygulaması gibi çalışır:** ekran kapalıyken ya da başka uygulama öndeyken kayda devam eder
- ⚡ **Hızlı ilk sinyal:** GPS uygulama açılır açılmaz ısınır; sinyal öncesi ve tünel boşlukları doldurulur
- 🌍 **12 dil** · 🔓 **Ücretsiz ve açık kaynak:** reklam yok, hesap yok, internet gerekmez

## İndirme ve kurulum

1. Telefonda **[en son sürümü](https://github.com/avsessisozx/Telemet/releases/latest)** aç.
2. **Telemet-x.y.z.apk** dosyasına dokunup indir ve aç.
3. Android bilinmeyen kaynak uyarısı verirse tarayıcıya ya da dosya yöneticisine kurulum izni ver.
4. Yeni sürümler eskisinin üzerine kurulur, sürüşlerin silinmez.

## Hızlı başlangıç

1. **Kamerada** kablosuz bağlantı açık olsun. Telefonda **DJI Mimo'yu kapat**; kamera aynı anda tek kumandayla konuşur.
2. Telemet'i aç. **Kurulum sihirbazı** gereken izinleri her biri ayrı sayfada sırayla ister (konumda "Her zaman izin ver", Bluetooth, bildirim, pil; Xiaomi'de Otomatik başlatma ve Pil tasarrufu).
3. **Başlat**'a bas. İlk seferde kamera ekranındaki bağlantı isteğini onayla (uygulamadaki kod aynı olmalı).
4. GPS noktası yeşil olunca kaydı kameradan ya da Telemet'ten başlat.
5. Bitince **Durdur**'a bas. Sürüş **Sürüşlerim**'e eklenir, FIT/GPX dosyaları `İndirilenler/Telemet` klasörüne kaydedilir.

## FIT yedeğini DJI Studio'da kullanma

Sürüşü **Sürüşlerim**'den bilgisayara paylaş → DJI Studio'da videoyu aç → **Pano** → **Veri kaynağı** → **FIT Dosyasını İçe Aktar** → gerekirse **Süre Hizalama**.

---

Kamera listesi, sensörler, SSS, sorun giderme ve teknik ayrıntılar için [İngilizce ana sayfaya](../README.md) bak.

MIT lisansı. Bu proje DJI ile bağlantılı değildir. DJI, Osmo ve Osmo Action, SZ DJI Technology Co., Ltd. şirketinin ticari markalarıdır.
