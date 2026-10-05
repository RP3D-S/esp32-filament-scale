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
   `adb uninstall io.github.rp3ds.tigerscalelite` first (only the app's own settings are lost: chosen scale, IP, language).
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
  Calibration Wizard (shows the factor), Manual calibration, Language, RFID, Firmware (opens the update screen), Restart
  (amber) and Factory reset (red, last). Each row shows its current value. Left out because the hardware is absent:
  volume, screen, power-off, live view. The "Scale" row holds the app-only connection tools (switch/forget scale,
  search, manual IP). Icons are drawn with the same primitives as the original (rings, bars, pill outlines).
- Restart and factory reset go over the **encrypted** BLE characteristic (`restart`, `factory_reset`). The app
  asks for a confirmation; the factory reset button only fires after a 3 s press-and-hold. It wipes Wi-Fi, account
  and calibration (NVS namespace `scale`), and then the scale restarts. The firmware no longer asks for BOOT.
- **OTA from the PC (development)**, tested on hardware. The partition table has two app slots of 1.94 MB
  (`app0` / `app1`, the firmware is ~1.66 MB, so ~19 % spare) plus `otadata`; `nvs` kept its offset, so Wi-Fi,
  account and calibration survived the switch. Update over Wi-Fi with
  `pio run -e esp32dev_hsu_ota -t upload --upload-port <scale-ip>` (the IP, not the `.local` name: mDNS does not
  resolve on this Windows PC). The password is generated on the first build into `firmware/.ota_password`
  (git-ignored, compiled into the firmware, passed to espota as `--auth`): a scale can only be updated from a PC that
  has that file, otherwise flash it once by USB. The boot log prints which slot runs (`[BOOT] running from app1`).
  While an update runs the cloud task is paused (`fbPause()`) and WebSocket clients are closed: the first attempt
  died at 16 % when a TLS heartbeat starved the TCP buffers (`write() errno 11`). This espota route takes 4 to 8
  minutes: espota sends 1 KB and waits for each answer, so its speed is 1 KB per round trip (25 KB/s at 40 ms, 3.6 KB/s
  at the ~280 ms an idle ESP32 radio in power save takes to answer; both showed up in one upload). It is kept as the
  fallback; the streamed route below replaces it. Windows may ask to allow Python through the firewall the first
  time. Changing the partition table needs one USB flash; after that every update can go by OTA.
- **Streamed OTA (`firmware/src/ota.cpp`, used by the app and by `firmware/scripts/ota_push.py`)**: the image goes to
  the scale as one HTTP POST that TCP can stream, so the speed is not tied to the round trip: **~30 s for the 1.67 MB
  image (53-58 KB/s)**, about the limit of the flash writes. Flow: (1) arm with the size and the MD5: over the
  encrypted BLE link (`ota_arm`, the app) or over HTTP with a password challenge (`GET /api/ota/nonce`, then
  `POST /api/ota/arm` with `sha256(nonce + password)`, the dev PC); the scale answers a one-time token (BLE `ota_tok`);
  (2) `loop()` pauses the cloud and closes the WebSocket clients, then reports `ota` = 1; (3) `POST /api/ota` with the
  header `X-OTA-Token` and the exact length (without a valid token the route does not exist: 404); the image is
  written to the other slot and the slot only switches if the MD5 matches; (4) the scale restarts. An armed update
  nobody uploads expires after 120 s, and any failure leaves the running firmware untouched. Progress is in
  `GET /api/ota/status` and in the BLE/WebSocket fields `ota` (0 idle, 1 armed, 2 receiving, 3 done, 4 error) and
  `ota_pct`. Dev push: `python firmware/scripts/ota_push.py <scale-ip>`.
- **Firmware screen in the app** (`FirmwareDialog.kt`, `FirmwareUpdater.kt`, orchestration in `ScaleViewModel`):
  Settings > Firmware shows the installed version and offers "Check for updates" (newest non-draft GitHub release
  that has `firmware.bin` + `firmware.json`; the SHA-256 of the download is checked against the manifest) and
  "Install from file". The phone needs the BLE link (to arm) and the scale's Wi-Fi address (to upload); while the
  update streams, the app opens no Wi-Fi link of its own to the scale (each costs heap). Tested on hardware with
  "Install from file": 0.2.0 -> 0.2.1 in 28.6 s of streaming, the scale came back on `app0` (it was on `app1`).
  Publish a release with `python firmware/scripts/release_firmware.py` (needs `gh`; it builds, writes
  `firmware/dist/firmware.{bin,json}` and creates `fw-v<version>`; `--dry-run` only writes the files). The release
  source is `FirmwareUpdater.RELEASES_API`: change it if the GitHub repository is renamed.
- Wi-Fi and TigerTag account dialogs: show/hide password, and each closes by itself once the scale is connected to
  the chosen network / signed in to the account (a failed attempt keeps it open with the error).

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
   A second, different weight (217 g, the first calibration used 250 g) reads 217 g before and after the change,
   which confirms the factor 943.37 and that the tare change did not touch the grams conversion.
2. **Phone Wi-Fi link to the scale: understood, two mitigations in, to be watched.** Measured with adb over Wi-Fi:
   with the screen off the phone is in Doze (`mWakefulness=Dozing`, `deviceidle mState=IDLE`), the network of
   background apps is cut, and the app logged 24 `failed to connect ... after 4000ms` in 150 s (phone->scale ping
   274 ms average). With the screen on: 0 failures, ping 44 ms. In normal use (one phone, app open, BLE + Wi-Fi
   together, scale freshly booted) 200 s gave no cloud failure, no `[LOOP]` stall and a stable largest free block
   of ~34.8 KB, with only one transient 6 s "wifi link stale" about 30 s after boot.
   The real weakness is the heap under client churn: a second WebSocket client plus repeated reconnects pushed the
   largest free block down to ~19 KB, and then **every cloud heartbeat failed with `[FB] heartbeat HTTP -1`** (TLS
   needs contiguous heap) and the scale refused TCP connections for several seconds. One WebSocket client costs
   only ~4 KB and is harmless. Mitigations in place: the firmware keeps at most 2 WebSocket clients, oldest first
   (`ws.cleanupClients(2)`, not yet exercised with 3 simultaneous clients), and the app drops its Wi-Fi link when it
   goes to the background (`onBackground()`, Bluetooth stays; tested: 0 attempts in 40 s, Wi-Fi back 0.4 s after
   returning) and brings it back in `onForeground()`. If `heartbeat HTTP -1` ever shows up in a normal run,
   look at the heap first (`[FB] heap free=... largest=...` prints every 30 s).
3. Heap is tight on the classic ESP32 (largest free block ~17-37 KB, lower with more clients connected; right after
   a reboot with the app reconnecting it read 17 396 and the first heartbeat failed with `HTTP -11`, a read
   timeout, twice in one session: not explained yet, watch `[FB] heartbeat HTTP` together with `[FB] heap`). AsyncTCP
   stack was cut to 7 KB and the Firebase task to 10 KB for that reason; do not add a second simultaneous TLS
   session, and treat every new client connection as costing contiguous heap that the cloud TLS needs.
4. BLE link occasionally drops (supervision timeout, status 8; the app logs `ble link stale ... -> reconnect`) when
   Wi-Fi/TLS is busy; the app reconnects in ~3 s.
5. **OTA: the online path is untested, and there is no rollback.** "Check for updates" and the download have not run
   against a real release (none is published yet, and publishing is visible to everyone): publish one with
   `release_firmware.py` and try it from the app. What the app shows on screen during an update was not seen by
   me (I only had the scale console and the app log). There is no automatic rollback (the bootloader of this
   platform is not built for it): a firmware that boots but is broken needs a USB flash. The scale never pulls from
   GitHub itself: that needs TLS, and the heap cannot afford it.
6. Not ported from the original: second NFC reader, servo, battery/PMIC, sound, web UI from `data/www`,
   rack/position editing, language sync with the account.
7. Debug logging to remove when done diagnosing: the firmware's `[LOOP]` timing (keep it cheap, it only prints on slow
   passes) and the app's `TigerScaleLite` log. The app's `ble frame gap` line is noise: it fires above 1.5 s, but the scale
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
