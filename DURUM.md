# DURUM — Telemet

> Claude'un çalışma notu. Yalnızca `durum` dalında durur; `main`'e, README'ye ve sürüm notlarına girmez.
> Yeni sohbette önce bunu oku, repoyu baştan tarama. Her sürümden sonra güncelle.

Son güncelleme: 2026-10-19 · Son sürüm: **v0.1.21** (2026-10-19)

## Ne yapıyor
Telefonu DJI Osmo Action 4 için Bluetooth GPS kumandası gibi kullanır: hız, rota, yükseklik
DJI Mimo / DJI Studio panolarına gider. Ek olarak yatış açısı, G kuvveti, barometrik yükseklik,
FIT + GPX yedeği. Sadece telefonun kendi sensörleri; ek cihaz yok.

## Teknik
- Android, Kotlin, Gradle KTS · `io.github.avsessisozx.telemet` · minSdk 29
- Sürüm: `versionName = 0.1.<buildNumber>` (CI derleme numarası)
- CI: `.github/workflows/build.yml` → her dalda derleme + emülatör arayüz testleri (`UiFlowTest`, 9 test);
  ekran görüntüleri `ci-screenshots` dalına yazılır; yalnız `main`'e push'ta sürüm yayınlanır
- Sürüm notu = `main`'deki son commit mesajı (imza satırları CI'da otomatik siliniyor). `main`'e squash ile, temiz mesajla al
- Metinler `tools/i18n/*.py` dosyalarında (12 dil), `tools/i18n/build.py` ile strings.xml üretilir
- `repo-settings.yml`: repo açıklaması/etiketleri `.github/repo-settings.json`'dan
- Araçlar: `tools/check_strings.py` (çeviri anahtarları), `tools/validate_fit.py` (FIT doğrulama)
- Kullanıcının telefonu: Xiaomi Mi 10T Pro

## Yapılanlar
- Kumanda emülasyonu, ekran kapalıyken yeniden bağlanma
- Kesintisiz arka plan kaydı (navigasyon uygulaması gibi), her sürüş ayrı FIT+GPX yedeği
- Sürüşlerim ekranı (güne göre gruplu)
- Sensörler: yatış, G, barometre · GPS ön ısıtma, A-GPS
- Sahte konum uygulamasıyla test edildi, çalışıyor (kullanıcı onayı)
- v0.1.8: 12 dil, yeni ikon, İngilizce README + `docs/README.*.md` (11 dil)
- v0.1.13: yeni tasarım — kurulum sihirbazı (eski sürümden yükseltmede izinler tamsa atlanır), alt sekmeler,
  sürüş ayrıntısı (rota + grafikler), ayarlar, yedek/geri yükleme, güvenli "tüm verileri temizle"

- 2026-10-19 sürümü (v0.1.21, main c2f4db4):
  - Rakip analizi: Strava v485.12, Yamaha MyRide v6.2.4, Pirelli Diablo Super Biker v7.5.2 APK'leri
    REA/JADX ile incelendi. Sonuç: Telemet bu üçünün yaptığının çoğunu zaten yapıyor (çökme kurtarma,
    tırmanış/iniş istatistiği dahil). Gerçekten eksik bulunan iki şey eklendi. MyRide bir telemetri
    uygulaması değil (Yamaha motoruna bağlanan yardımcı), ondan alınacak az; Diablo + Strava asıl kaynak.
  - Hareket süresi: `service/MovingTime` (saf Kotlin). Sürüş ayrıntısında, duruş toplam süreden ≥30 sn
    azsa "hareket süresi" + "hareket ort. hızı" tile'ları gösterilir. Ham noktalardan hesaplanır, eski
    sürüşlerde de çalışır. Eşik 0.8 m/sn (~3 km/sa), büyük boşluklar (>600 sn) atlanır.
  - Rota yumuşatma: `service/RouteSmoother` (saf Kotlin). RideDetailActivity'de RouteView'a verilen
    lat/lon, çizimden önce üçgen ağırlıklı pencereyle yumuşatılır (uçlar sabit). Dış servis/harita yok.
    Yalnızca görsel — ham FIT/GPX değişmez. `Prefs.smoothRoute` (varsayılan AÇIK), ayar satırı eklendi.
    NOT: Bu gerçek snap-to-road değil (yol haritası gerektirir, bağımsızlık felsefesine aykırı); titreme
    azaltan yerel filtre. Ayar adı "Rota çizgisini yumuşat" olarak dürüstçe kondu.
  - Testler: RouteSmootherTest, MovingTimeTest. CI 58 test, 0 başarısız; build+ui-test+release hepsi yeşil.

- 2026-10-16 sürümü (main a94d318):
  - Hız sapması: 10 Hz modunda paketlerdeki saat saniye hassasiyetinde; konum artık tam saniyeye hizalanıyor
    (`GpsTracker.alignedToSecond`), `SpeedFilter` tek seferlik sıçramaları ayıklıyor. Kullanıcı Google Haritalar'a göre
    %5–10 fazla görüyordu; gerçek sürüşte doğrulanmadı.
  - Videolarla eşleştirme: `ui/VideoSyncActivity` (Sürüşlerim'deki düğme + galeriden "Paylaş → Telemet"),
    `video/Mp4Info` (mvhd okur), `video/VideoSync` (UTC / yerel / dosya adı yorumlarından en çok örtüşeni seçer, ± sn düzeltme).
    Parça başına FIT+GPX, birleşik dosya.
  - Göstergeli video: `overlay/OverlayRenderer` (yeşil zemin), `OverlayEncoder` (EGL + MediaCodec, açık zaman damgası),
    Filmler/Telemet'e yazılır. Emülatörde 1080p ≈ 1,3× gerçek zaman.
  - Ayarlar: "Mimo'da yatış/G" (FIT'te kadans=|yatış|, güç=G×100), "Hareket edince otomatik kayıt" (`service/AutoRecord`,
    15 km/sa, 3 dk duruş; elle başlatılan kayda dokunmaz). İkisi de varsayılan kapalı.
  - Testler: yeni birim testleri (SpeedTest, VideoSyncTest, AutoRecordTest, Mimo FIT) + emülatör t10–t12.
    UI testlerinde emülatörün ANR penceresi gizleniyor (`hide_error_dialogs`).

## Yarım / sıradaki
- Hareket süresi ve rota yumuşatma gerçek sürüşte doğrulanmadı (yalnız emülatör arayüz testi geçti).
  Kullanıcı gerçek sürüşte rota çiziminin ve hareket süresinin nasıl göründüğüne bakacak.
- Rakip analizinden çıkan isteğe bağlı eklemeler (henüz yapılmadı): lap/bölüm (viraj kesimi) istatistiği
  (Strava+Diablo), park yeri kaydet (MyRide). Kullanıcı istedikçe eklenir.
- 3B rota izleme konuşuldu: harita olmadan, barometre+GPS'ten döndürülebilir 3B çizgi yapılabilir
  (bağımsız, ücretsiz); gerçek uydu üstünde uçuş (Strava flyby) harita servisi/ücret ister — reddedildi.
- Uygulamaya yeni isim düşünülüyordu; henüz seçilmedi (şu an "Telemet")
- Mimo panosunda daha fazla veri alanı ve eksik kumanda özellikleri (mod değiştirme,
  fotoğraf, uyku/uyandırma, durum bilgisi) — hangilerinin bittiğini kodda doğrula
- Gerçek motosiklet sürüşüyle uzun test henüz raporlanmadı
- Gerçek cihazda doğrulanacak: hız düzeltmesi Mimo'da; DJI Action 4 MP4'ünün mvhd saati UTC mi yerel mi;
  Mimo'nun kadans/güç göstergeleri; otomatik kayıt; göstergeli videonun telefondaki hızı (uzun videolarda dakikalar sürebilir)
- DJI Action 4 dosya adı biçimi varsayımı: DJI_YYYYMMDDHHMMSS_xxxx_D.MP4 (yalnız mvhd yoksa yedek olarak kullanılıyor)

## Bilinen sorunlar
- Kayıtlı bilinen hata yok.

## Kurallar
- Sürüm vermeden önce kendin derle ve test et; kullanıcıya bozuk sürüm test ettirme
- Sürüm notlarına ve commit mesajlarına sohbet / oturum bağlantısı (`Claude-Session:`) ekleme
