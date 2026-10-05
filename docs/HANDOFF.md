# Handoff: ESP32 WROOM-32 filament scale + Android app

Where things stand, so work can continue on another PC. Written 2026-10-05.

## What this project is
A headless fork of TigerScale V3. The ESP32 (KEYESTUDIO ESP32-WROOM-32, 4 MB flash, no PSRAM) does the
weighing, NFC and cloud sync; an Android app replaces the LCD and shows the same 480x320 screen.
Upstream (MIT): https://github.com/TigerTag-Project/Tiger-Scale-V3 — a modified fork must not be called "TigerScale".

```
firmware/   PlatformIO project (esp32dev_hsu)      android/   Kotlin + Compose app
docs/WROOM32_PORT.md  plan and pin map             docs/HANDOFF.md  this file
```

## Wiring (see WROOM32_PORT.md)
HX711 DT=32 SCK=33 | PN532 (HSU, switch OFF/OFF) TXD->16 RXD->17 RSTPDN->27 | BOOT button = GPIO0.
One PN532, no servo, no screen, no battery.

## Set up a new PC (Windows)
1. Python 3.12 or 3.14 (both build the firmware), then `pip install --user platformio`.
2. JDK 17 (Temurin) for Gradle: `winget install EclipseAdoptium.Temurin.17.JDK --source winget`.
   Android Studio's bundled JDK (21/25) is not what Gradle 8.7 is set up for. Set `JAVA_HOME` to it.
3. Android SDK 34 + build-tools 34.0.0 + platform-tools (Android Studio installs them, or use Google's
   command-line tools and `sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools"`), and put
   `sdk.dir=...` in `android/local.properties` (git-ignored).
4. Firmware: `cd firmware && pio run -e esp32dev_hsu -t upload --upload-port COMx` (first build downloads
   ~1.5 GB and can take over 10 minutes). The COM number changes per PC and port: look for the CH340 in
   Device Manager. If `esptool` hangs on upload, kill it and run again.
5. App: `cd android && set JAVA_HOME=<jdk17> && gradlew.bat assembleDebug`, install with `adb install -r`.
   Xiaomi/Redmi phones need "Install via USB" enabled in developer options. A debug build from another PC
   is signed with a different key, so `adb install -r` fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE:
   `adb uninstall io.github.rp3ds.filscale` first (only the app's own settings are lost: chosen scale, IP, language).
6. Serial console 115200 on the ESP32's COM port. No Wi-Fi/Firebase secrets are in the repo
   (the Firebase web key in the code is the public client key, same as upstream).

## What works (tested on hardware)
- BLE (NimBLE) in parallel with Wi-Fi; same JSON on both. App picks and remembers one scale.
- Wi-Fi provisioning from the app over an encrypted BLE characteristic (BOOT pressed to change an existing network).
- TigerTag account login (password never stored), avatar from `userProfiles/{uid}.photoURL`, heartbeat to
  `users/{uid}/scales/{mac}` with the original's field names.
- Weigh workflow ported from the original: IDLE -> SCANNING -> STABLE_WAIT -> SENDING -> DONE; writes
  `weight_available` + `last_update` on the spool and its twin; refuses to create missing inventory docs.
- Tag brand/material/colour from pages 5-8, names resolved in the app from the public TigerTag database.
- Remote commands from Studio (tare, workflow_stop, rfid_test_*, heartbeat_now, calibration_set, restart, factory_reset).
- Idle auto-tare and negative-drift tare. RFID test screen. App in 9 languages with an in-app picker.
- Local API: `GET /api/status` (now includes workflow state), `POST /api/tare`, `/api/calibrate`, `/api/rfid/test`,
  `/api/calibration` (set the factor), `/api/cal` (calibration wizard commands, below).
- Wi-Fi network list in the app: `WiFi.scanNetworks()` returns -2 while the scale is still trying to connect, so
  `doWifiScan()` stops the reconnect, rescans, then resumes the saved network (log: `[WIFI] scan ...`).
- **Calibration wizard**, a port of the original's 3-step flow (empty + TARE with a held-zero check, reference
  150-4500 g or a preset spool, place the weight and Calibrate once the reading is steady, auto-save, back/cancel
  restore the old factor and tare). The state machine is in firmware (`calTick()` in `main.cpp`, non-blocking; the
  weigh loop and workflow are suspended while it runs, 3 min idle timeout). Commands: `cal_start`, `cal_tare`,
  `cal_ref {grams}`, `cal_measure`, `cal_back`, `cal_cancel`, `cal_factor {value}` over BLE, or `POST /api/cal`.
  The scale reports `cal` (phase 0 off, 1 tare, 2 taring, 3 reference, 4 place, 5 measuring, 6 saved, 7 error),
  `cal_ok` (steady), `cal_ref`, `cal_err` (zero / read / ref), `cal_done` (calibrated at least once; NVS key `cal`).
  The app screens are `CalibrationWizard.kt`; Settings also has the factor and manual factor entry, and a
  first-calibration side panel (2 s after connecting, then every 5 min, never during a weighing).
  Tested on hardware: every step, rejection of 100 g, back/cancel, and a full run with a 250 g weight.

## Open items (in priority order)
1. **Verify the calibration with a second weight.** First real factor is **943.37** (250 g, raw 235 842 counts); the
   display then read 250 g. It is higher than the 400-460 typical of this load cell/HX711 pair, so put a different
   known weight on the platform and confirm it reads right before trusting it. The app's wizard screens were built
   and installed but not yet confirmed visually on the phone (the firmware side was driven over BLE from a PC).
   The wizard's "within 1 g" limits fall back to 400 counts/g when the stored factor is below 50: this scale held
   0.072 before the first calibration, which made the zero check impossible to pass.
2. **Inventory values overwritten during testing.** The spool pair `1D6EAB64121080` / `1D77F85F121080` now has
   `weight_available = 562`. Its value before testing was almost certainly **192 g** — restore it in Studio, or
   re-weigh now that the scale is calibrated. Weighings made before the first calibration (factor 0.072, weights
   like -643 g or 280 g on an empty pan) may also have written wrong values to other spools.
3. **Latency fix just made, not yet re-measured on the phone.** The PN532 library's `readBytes()` waited the
   serial timeout (1000 ms) on every "no tag" reply, blocking the main loop to 1 pass/s (weight took ~4 s to
   appear and >15 s to return to zero). Fixed with `Serial2.setTimeout(30)`; loop stalls are gone on the bench.
   Re-test the app unplugged from USB. Keep-alive frames (2 s) and stale-link reconnect were added too.
4. Heap is tight on the classic ESP32 (largest free block ~20-35 KB during TLS). AsyncTCP stack was cut to 7 KB
   and the Firebase task to 10 KB for that reason; do not add a second simultaneous TLS session.
5. BLE link occasionally drops (supervision timeout, status 8) when Wi-Fi/TLS is busy; the app reconnects in ~3 s.
6. No OTA (single 3.9 MB app partition). Flash by USB.
7. Not ported from the original: second NFC reader, servo, battery/PMIC, sound, OTA, web UI from `data/www`,
   rack/position editing, language sync with the account.
8. The app prints a `[LOOP] stall` / `FilScale` debug log; harmless, remove when done diagnosing.

## Handy
- Wireless adb (no cable): `adb tcpip 5555`, `adb connect <phone-ip>:5555`.
- Phone mirror on the PC: scrcpy (`scrcpy --stay-awake`), set `ADB` to the SDK's adb first.
- `pio run -e esp32dev_hsu_debug` adds a byte-level PN532 trace.
- ESP32 serial lines to look for: `[WF]` workflow, `[FB]` cloud (`PATCH inventory ... HTTP 200`), `[RFID]`, `[LOOP]`,
  `[WIFI]` scan, `[CAL]` wizard (zero checks, `raw= ref= factor=`).
- The phone holds the scale's BLE link, which hides it from other BLE clients: close the app before testing from a PC.
  A PC test can drive the wizard with Python + `bleak` (write JSON commands to characteristic `6e5f0003-b5a3-f393-e0a9-e50e24dcca9e`,
  frames arrive as notifications on `...0002...`).
