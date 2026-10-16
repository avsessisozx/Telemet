"""Telemet'in ürettiği örnek FIT dosyasını bağımsız bir okuyucuyla (fitdecode) doğrular.

Birim testi app/build/fit-sample/sample.fit dosyasını yazar; GitHub Actions bu betiği çalıştırır.
"""
import sys

import fitdecode

path = sys.argv[1]
mimo = "--mimo" in sys.argv[2:]
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
    for name in ("lean_angle", "lateral_g", "longitudinal_g", "g_force"):
        if r.get(name) is None:
            errors.append(f"sensör alanı yok: {name}")
    if r.get("lean_angle") is not None and abs(abs(r["lean_angle"]) - 30.0) > 0.01 and abs(abs(r["lean_angle"]) - 25.0) > 0.01:
        errors.append(f"yatış açısı yanlış: {r['lean_angle']}")
    if mimo:
        # Mimo için: yatış açısı kadansa, G × 100 güce yazılır.
        if r.get("cadence") != round(abs(r["lean_angle"])):
            errors.append(f"kadans (yatış) yanlış: {r.get('cadence')}")
        expected_power = round(r["g_force"] * 100)
        if r.get("power") is None or abs(r["power"] - expected_power) > 1:
            errors.append(f"güç (G) yanlış: {r.get('power')} != {expected_power}")
    elif r.get("cadence") is not None or r.get("power") is not None:
        errors.append("Mimo alanları kapalıyken kadans/güç yazılmış")

if errors:
    print("HATA:", "; ".join(errors))
    sys.exit(1)
print("FIT dosyası geçerli")
