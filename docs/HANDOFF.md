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
  The app screens are `CalibrationWizard.kt`; Settings also has manual factor entry, and a
  first-calibration side panel (2 s after connecting, then every 5 min, never during a weighing).
  Tested on hardware: every step, rejection of 100 g, back/cancel, and a full run with a 250 g weight.
- **Settings menu in the app** (`SettingsScreen.kt`) laid out like the original's list: Scale, WiFi, Account,
  Calibration Wizard (shows the factor), Manual calibration, Language, RFID, Firmware (read-only, no OTA), Restart
  (amber) and Factory reset (red, last). Each row shows its current value. Left out because the hardware is absent:
  volume, screen, power-off, live view. The "Scale" row holds the app-only connection tools (switch/forget scale,
  search, manual IP). Icons are drawn with the same primitives as the original (rings, bars, pill outlines).
- Restart and factory reset go over the **encrypted** BLE characteristic (`restart`, `factory_reset`). The app
  asks for a confirmation; the factory reset button only fires after a 3 s press-and-hold. It wipes Wi-Fi, account
  and calibration (NVS namespace `scale`), and then the scale restarts. The firmware no longer asks for BOOT.
- Wi-Fi dialog: show/hide password, and it closes by itself once the scale is connected to the chosen network.

## Open items (in priority order)
Calibration is done and checked: the first real factor is **943.37** (250 g reference, raw 235 842 counts) and the
owner confirmed a second, different weight reads correctly. The wizard's "within 1 g" limits fall back to 400
counts/g when the stored factor is below 50, because this scale held 0.072 before its first calibration, which made
the zero check impossible to pass. The cloud account used here is a test account, so the inventory values written
during early testing (e.g. spool pair `1D6EAB64121080` / `1D77F85F121080`) do not matter.

1. **Main-loop latency: two causes fixed, extended real-world use not yet measured.**
   (a) The PN532 library's `readBytes()` waited the serial timeout (1000 ms) on every "no tag" reply, limiting the
   loop to 1 pass/s (weight took ~4 s to appear and >15 s to return to zero): fixed with `Serial2.setTimeout(30)`.
   (b) `scale.tare(10)` blocked `loop()` for ~900 ms on every tare (the HX711 gives ~10 samples/s, and the auto-tare
   fires constantly): found with the per-section timing (`[LOOP] slow pass ...: cmds=850`), fixed by making the tare a
   job fed one sample per pass by `updateScale()`. After the fix, 5 tares in a row and a tare with a load on the
   platform (217 -> 0 in 0.8 s) gave no stall. Rule that came out of it: nothing in `loop()` may wait for the HX711;
   `get_value(n)`, `get_units(n)`, `tare(n)` and `wait_ready_timeout()` all block for n/10 s. The only ones left are
   `doCalibrate()` (the old one-shot `/api/calibrate`, not used by the wizard) and the tare in `setup()`.
   Still to do: use the scale for a while with the app unplugged from USB and check that no `[LOOP]` line appears.
   The weight the owner puts on the platform reads 217 g, the same before and after the change; if it should be
   250 g the factor deserves another look.
2. **The phone's Wi-Fi link to the scale is flaky, the app lives mostly on BLE.** From the phone: 24 `wifi link down:
   failed to connect to /<scale> (port 80) ... after 4000ms` in 150 s, only 3 connections. From the PC the same
   scale answers fine (ping 11-53 ms, HTTP 100-250 ms, occasional ~1 s spike). Not caused by the stalls (it kept
   happening with none). Not investigated yet: look at the phone's side (Wi-Fi power save, AP/band isolation) and at
   how many connections AsyncTCP accepts with ~30 KB of free heap.
3. Heap is tight on the classic ESP32 (largest free block ~17-37 KB, lower the longer it has been up). AsyncTCP stack
   was cut to 7 KB and the Firebase task to 10 KB for that reason; do not add a second simultaneous TLS session.
4. BLE link occasionally drops (supervision timeout, status 8; the app logs `ble link stale ... -> reconnect`) when
   Wi-Fi/TLS is busy; the app reconnects in ~3 s.
5. No OTA (single 3.9 MB app partition). Flash by USB.
6. Not ported from the original: second NFC reader, servo, battery/PMIC, sound, OTA, web UI from `data/www`,
   rack/position editing, language sync with the account.
7. Debug logging to remove when done diagnosing: the firmware's `[LOOP]` timing (keep it cheap, it only prints on slow
   passes) and the app's `FilScale` log. The app's `ble frame gap` line is noise: it fires above 1.5 s, but the scale
   only sends a keep-alive every 2 s when nothing changes, so ~2 s gaps are normal.

## Handy
- Wireless adb (no cable): `adb tcpip 5555`, `adb connect <phone-ip>:5555`.
- Phone mirror on the PC: scrcpy (`scrcpy --stay-awake`), set `ADB` to the SDK's adb first.
- `pio run -e esp32dev_hsu_debug` adds a byte-level PN532 trace.
- ESP32 serial lines to look for: `[WF]` workflow, `[FB]` cloud (`PATCH inventory ... HTTP 200`), `[RFID]`,
  `[WIFI]` scan, `[CAL]` wizard (zero checks, `raw= ref= factor=`), and `[LOOP]`: `stall N ms` = gap between two
  passes, `slow pass N ms: <section>=ms ...` = which section of `loop()` took the time (cmds, wifi, fbpub, scale,
  rfid, status, ws, wsclean, ble, delay), `outside loop() N ms` = time in the scheduler, not in our code.
- To find what blocks the loop: leave a timestamped serial logger running (a PowerShell `SerialPort` loop appending
  to a file) while using the scale, then grep `LOOP`. It held the COM port, so stop it before flashing.
- The phone holds the scale's BLE link, which hides it from other BLE clients: close the app before testing from a PC.
  A PC test can drive the wizard with Python + `bleak` (write JSON commands to characteristic `6e5f0003-b5a3-f393-e0a9-e50e24dcca9e`,
  frames arrive as notifications on `...0002...`).
