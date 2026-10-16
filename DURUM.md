# DURUM — Telemet

> Claude'un çalışma notu. Yalnızca `durum` dalında durur; `main`'e, README'ye ve sürüm notlarına girmez.
> Yeni sohbette önce bunu oku, repoyu baştan tarama. Her sürümden sonra güncelle.

Son güncelleme: 2026-10-16 · Son sürüm: **v0.1.8** (2026-10-15)

## Ne yapıyor
Telefonu DJI Osmo Action 4 için Bluetooth GPS kumandası gibi kullanır: hız, rota, yükseklik
DJI Mimo / DJI Studio panolarına gider. Ek olarak yatış açısı, G kuvveti, barometrik yükseklik,
FIT + GPX yedeği. Sadece telefonun kendi sensörleri; ek cihaz yok.

## Teknik
- Android, Kotlin, Gradle KTS · `io.github.avsessisozx.telemet` · minSdk 29
- Sürüm: `versionName = 0.1.<buildNumber>` (CI derleme numarası)
- CI: `.github/workflows/build.yml` → `main`'e push'ta APK derler ve yayınlar
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

## Yarım / sıradaki
- Uygulamaya yeni isim düşünülüyordu; henüz seçilmedi (şu an "Telemet")
- Mimo panosunda daha fazla veri alanı ve eksik kumanda özellikleri (mod değiştirme,
  fotoğraf, uyku/uyandırma, durum bilgisi) — hangilerinin bittiğini kodda doğrula
- Gerçek motosiklet sürüşüyle uzun test henüz raporlanmadı

## Bilinen sorunlar
- Kayıtlı bilinen hata yok.

## Kurallar
- Sürüm vermeden önce kendin derle ve test et; kullanıcıya bozuk sürüm test ettirme
- Sürüm notlarına ve commit mesajlarına sohbet / oturum bağlantısı (`Claude-Session:`) ekleme
