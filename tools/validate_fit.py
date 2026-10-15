"""Telemet'in ürettiği örnek FIT dosyasını bağımsız bir okuyucuyla (fitdecode) doğrular.

Birim testi app/build/fit-sample/sample.fit dosyasını yazar; GitHub Actions bu betiği çalıştırır.
"""
import sys

import fitdecode

path = sys.argv[1]
records = []
messages = {}
with fitdecode.FitReader(path, check_crc=fitdecode.CrcCheck.RAISE) as fit:
    for frame in fit:
        if frame.frame_type == fitdecode.FIT_FRAME_DATA:
            messages[frame.name] = messages.get(frame.name, 0) + 1
            if frame.name == "record":
                records.append({f.name: f.value for f in frame.fields})
            elif frame.name in ("session", "file_id", "activity"):
                print(frame.name, {f.name: f.value for f in frame.fields if f.value is not None})

print("mesajlar:", messages)
errors = []
for name in ("file_id", "event", "record", "lap", "session", "activity"):
    if name not in messages:
        errors.append(f"{name} mesajı yok")
if len(records) != 600:
    errors.append(f"600 nokta bekleniyordu, {len(records)} bulundu")
else:
    r = records[400]
    print("örnek nokta:", r)
    if abs(r["enhanced_speed"] - 35.0) > 0.01:
        errors.append(f"hız yanlış: {r['enhanced_speed']}")
    if abs(r["position_long"] * 180 / 2**31 - 29.0) > 1e-6:
        errors.append("boylam yanlış")
    if abs(r["enhanced_altitude"] - 130.0) > 0.3:
        errors.append(f"yükseklik yanlış: {r['enhanced_altitude']}")
    if r.get("distance") is None or r["distance"] <= 0:
        errors.append("mesafe yok")

if errors:
    print("HATA:", "; ".join(errors))
    sys.exit(1)
print("FIT dosyası geçerli")
