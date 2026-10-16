<p align="center">
  <img src="docs/icon.png" width="120" alt="Telemet icon">
</p>

<h1 align="center">Telemet</h1>

<p align="center">
  <b>Free GPS telemetry for DJI Osmo Action cameras — use your Android phone instead of the DJI GPS Bluetooth Remote.</b><br>
  Speed, route, altitude and distance dashboards in DJI Mimo / DJI Studio, plus lean angle and G-force from your phone's sensors.
</p>

<p align="center">
  <a href="https://github.com/avsessisozx/Telemet/releases/latest"><img src="https://img.shields.io/github/v/release/avsessisozx/Telemet?label=download&color=FF7A1A" alt="Latest release"></a>
  <img src="https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white" alt="Android 10+">
  <a href="LICENSE"><img src="https://img.shields.io/github/license/avsessisozx/Telemet" alt="MIT license"></a>
  <img src="https://img.shields.io/github/downloads/avsessisozx/Telemet/total?color=555" alt="Downloads">
</p>

<p align="center">
  <b>English</b> ·
  <a href="docs/README.tr.md">Türkçe</a> ·
  <a href="docs/README.es.md">Español</a> ·
  <a href="docs/README.fr.md">Français</a> ·
  <a href="docs/README.it.md">Italiano</a> ·
  <a href="docs/README.de.md">Deutsch</a> ·
  <a href="docs/README.pt-BR.md">Português</a> ·
  <a href="docs/README.zh-CN.md">简体中文</a> ·
  <a href="docs/README.ja.md">日本語</a> ·
  <a href="docs/README.ko.md">한국어</a> ·
  <a href="docs/README.ru.md">Русский</a> ·
  <a href="docs/README.ar.md">العربية</a>
</p>

---

## What it does

DJI Osmo Action cameras can embed GPS data (speed, route, altitude) into their videos, but only when DJI's **GPS Bluetooth Remote Controller** is connected. Telemet turns your Android phone into that remote: it connects to the camera over Bluetooth, sends the phone's GPS 10 times per second, and the camera writes it straight into the video file. Open the video in **DJI Mimo** or **DJI Studio** and add the speed / route / altitude dashboard — no FIT conversion, no extra hardware.

Built for motorcycle riders, but works the same on a bicycle, in a car or on foot.

## Features

- 📍 **GPS remote emulation** — the phone acts as the DJI GPS Bluetooth Remote; data is embedded in the video
- 🎬 **Camera control** — start/stop recording from the app or the notification, see mode, recording state and battery
- 🗂️ **Every ride saved separately** — "My rides" lists each ride grouped by day with distance, duration, max/avg speed and climb
- 🗺️ **Ride details** — tap a ride to see its route, speed, altitude and lean charts; rename, share or delete it
- ⚙️ **Settings** — km or miles, camera pairing reset, permission status, log, backup and restore, update check
- 🛟 **Safe reset** — "Clear all data" backs up first and can be restored in one tap; ride files are never deleted
- 💾 **FIT + GPX backup of every ride** — import the FIT file in DJI Studio (Dashboard → Data source → Import FIT file) if the camera missed data
- 🏍️ **Lean angle and G-force** — calculated from the phone's gyroscope and GPS, independent of how the phone is mounted; written to the FIT file
- 🎞️ **Match with camera videos** — the camera splits long recordings into parts; pick the parts (or share them to Telemet from your gallery) and get a FIT + GPX for each part, or one merged file. Start times are matched automatically, with a seconds offset if needed
- 🟩 **Gauge video** — speed, lean angle, G, altitude, distance and a mini route map on a green background, sized and timed for each video part; lay it over your video in CapCut or any editor with chroma key
- 🎛️ **Lean angle and G in DJI Mimo** (optional) — also written into the cadence and power fields, which Mimo can show as gauges
- 🤖 **Automatic recording** (optional) — the camera starts recording when you ride off and stops after 3 minutes standing still
- 📈 **Barometric altitude** — on phones with a barometer, much steadier altitude and grade than GPS alone
- 🔋 **Runs like a navigation app** — keeps recording with the screen off or another app (e.g. maps) in front, restarts itself if the system kills it
- ⚡ **Fast first fix** — GPS warms up as soon as the app opens; gaps before the first fix and during tunnels are filled so the FIT file covers the whole video
- 🌍 **12 languages** — English, Türkçe, Español, Français, Italiano, Deutsch, Português, 简体中文, 日本語, 한국어, Русский, العربية
- 🔓 **Free and open source** — no ads, no account, no Google Play Services, no internet required

## Supported cameras

| Camera | Status |
|---|---|
| DJI Osmo Action 4 | ✅ Developed and tested on this camera |
| DJI Osmo Action 5 Pro, Osmo Action 6, Osmo 360 | Should work (same protocol per DJI's documentation), not yet tested — reports welcome |

Phone: Android 10 or newer with Bluetooth LE and GPS.

## Download and install

1. On your phone, open the **[latest release](https://github.com/avsessisozx/Telemet/releases/latest)**.
2. Tap **Telemet-x.y.z.apk** to download it, then open it.
3. If Android warns about unknown sources, allow installs from your browser or file manager.
4. New versions install over the old one; your rides are kept.

## Quick start

1. **On the camera:** make sure wireless connection is enabled. **Close DJI Mimo** on your phone — the camera talks to only one remote at a time.
2. Open Telemet. The **setup wizard** walks you through the permissions it needs, one per page (location "Allow all the time", Bluetooth, notifications, battery exemption; on Xiaomi also Autostart and Battery saver).
3. Tap **Start**. Approve the connection on the camera screen the first time (the code shown in the app must match).
4. Wait for the green GPS dot, then start recording from the camera or from Telemet.
5. Tap **Stop** when you're done. The ride appears in **My rides** and its FIT/GPX files are saved to `Downloads/Telemet`.

In DJI Mimo or DJI Studio, open the video and enable the **dashboard** to show speed, route and altitude.

## Using the FIT backup in DJI Studio

If a video has no GPS data (for example the camera was started before the phone had a fix):

1. Share the ride from **My rides** (or copy `Downloads/Telemet/*.fit`) to your computer.
2. In DJI Studio open the video → **Dashboard** → **Data source** → **Import FIT file**.
3. Use **Time alignment** if needed.

The FIT file contains position, speed, altitude, distance and grade, plus lean angle, lateral G, longitudinal G and total G as developer fields.

## Phone sensors used

| Sensor | Used for |
|---|---|
| GPS | Position, speed, route, distance (also sent to the camera) |
| Gyroscope + gravity | Lean angle and cornering G: tan(lean) = speed × yaw rate / g |
| Barometer (if present) | Steadier altitude and grade |
| GPS speed changes | Braking and acceleration G |

Heart rate, cadence, power and torque need external sensors and are not supported.

## FAQ

**Does it need internet?** No. GPS works offline. With internet, the first GPS fix is faster (assisted GPS).

**Can I use it together with DJI Mimo?** Not at the same time — the camera accepts one Bluetooth remote. Close Mimo while riding; use it afterwards to edit.

**Is this the same as the official DJI GPS remote?** It uses the same Bluetooth protocol that DJI published for its GPS remote ([dji-sdk/Osmo-GPS-Controller-Demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo)), implemented on the phone instead of an ESP32 board with a GPS module.

**Recording stops in the background on my Xiaomi / Samsung / Huawei phone.** Finish the setup steps shown on the Ride screen. On Xiaomi set Autostart on and Battery saver to "No restrictions".

**DJI Studio says the duration doesn't match.** The FIT file must cover the video's time range. Make sure the phone clock and camera clock agree, or use Time alignment.

## Troubleshooting

Everything is written to the **Log** section at the bottom of the app. If something goes wrong, tap **Share** and attach the log to a [GitHub issue](https://github.com/avsessisozx/Telemet/issues).

| Problem | What to try |
|---|---|
| "Searching for camera" never ends | Is the camera on? Is DJI Mimo connected to it (on this or another phone)? Reset wireless connections on the camera. |
| Camera rejected the connection | Reset wireless connections on the camera and approve the request on its screen. |
| Connected but no data in the dashboard | Is the GPS dot green? Is "packets/s to camera" above 0? Share the log. |
| Stops in the background | Complete the setup steps on the Ride screen; allow background usage in the phone's settings. |

## How it works

1. The camera is found by its Bluetooth advertisement (manufacturer data `AA 08 … FA`).
2. Telemet connects to service `FFF0` (`FFF4` notifications from the camera, `FFF5` writes to it).
3. Sends a `0x0019` connection request; the camera asks the user to approve once.
4. Subscribes to camera status with `0x1D05` (mode, recording, battery).
5. Pushes GPS with `0x0017` ten times per second.

```
app/src/main/java/io/github/avsessisozx/telemet/
├── protocol/   DJI frame format, CRC, commands (pure Kotlin, unit-tested)
├── ble/        Bluetooth scan, connection and handshake
├── sensors/    Gyroscope, gravity, barometer; lean angle and G-force math
├── fit/        FIT encoder and gap filling (unit-tested, validated with fitdecode)
├── service/    Background service, GPS, ride recording and ride list
├── ui/         Screens
└── util/       Log, settings, language
```

## Building

Every push to `main` builds the APK with GitHub Actions and publishes it under **Releases**. Locally: open in Android Studio or run `./gradlew assembleDebug` (JDK 17).

`keystore/telemet-debug.jks` is a public signing key so new versions install over old ones. It provides no security.

## Translations

App texts live in `app/src/main/res/values-*/strings.xml`. Corrections from native speakers are very welcome — open a pull request or an issue.

## License

MIT. The DJI R SDK protocol is subject to DJI's [end user license agreement](https://developer.dji.com/policies/eula/). This project is not affiliated with or endorsed by DJI. DJI, Osmo and Osmo Action are trademarks of SZ DJI Technology Co., Ltd.

<sub>Keywords: DJI Osmo Action 4 GPS, Osmo Action 5 Pro GPS, DJI GPS Bluetooth Remote alternative, DJI Mimo dashboard, DJI Studio telemetry, GPS overlay, speed overlay, motorcycle telemetry, lean angle app, FIT file, GPX, action camera telemetry, Android</sub>
